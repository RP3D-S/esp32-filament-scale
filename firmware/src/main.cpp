// Headless filament scale for ESP32 WROOM-32.
// HX711 load cell + 1x PN532 (HSU).
// State goes to the phone over HTTP + WebSocket; protocol mirrors the upstream
// TigerScale V3 API (docs/API.md) for the fields it shares, plus a few additions.
#include <Arduino.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <ESPmDNS.h>
#include <ESPAsyncWebServer.h>
#include <AsyncJson.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <HX711.h>
#include <Adafruit_PN532.h>

#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif

// ---- Pins (see docs/WROOM32_PORT.md) --------------------------------------
#define HX711_DOUT   32
#define HX711_SCK    33
#define PN532_RXD    16   // Serial2 (<- PN532 TXD)
#define PN532_TXD    17
#define PN532_RST    27

// ---- Tuning ---------------------------------------------------------------
static const uint32_t WS_INTERVAL_MS      = 100;
static const uint32_t WS_FULL_INTERVAL_MS = 30000;
static const uint32_t RFID_POLL_MS        = 250;
static const uint32_t RFID_LOST_MS        = 1500;   // tag gone after this long unseen
static const float    PRESENT_G           = 10.0f;
static const float    EMA_SLOW            = 0.15f;
static const float    EMA_FAST            = 0.6f;
static const float    FAST_DELTA_G        = 5.0f;
static const uint32_t STABLE_MS           = 1200;

// ---- PN532 over HSU -------------------------------------------------------
class Reader {
public:
    Reader(uint8_t rst, HardwareSerial &ser, int8_t rx, int8_t tx)
        : _pn(rst, &ser), _ser(ser), _rx(rx), _tx(tx) {}

    bool init() {
        // Adafruit's begin() calls serial->begin(115200) with no pins, so the
        // pins have to be claimed first.
        _ser.begin(115200, SERIAL_8N1, _rx, _tx);
        _pn.begin();
        ok = _pn.getFirmwareVersion() != 0;
        if (ok) { _pn.SAMConfig(); _pn.setPassiveActivationRetries(1); }
        return ok;
    }

    // Returns true and fills `out` with an uppercase hex UID when a tag is present.
    bool poll(String &out) {
        uint8_t buf[255] = {0};   // library trusts the frame's length byte; leave room
        uint8_t len = 0;
        bool found = _pn.readPassiveTargetID(PN532_MIFARE_ISO14443A, buf, &len, 30);
        if (!found || (len != 4 && len != 7 && len != 10)) return false;
        out = "";
        for (uint8_t i = 0; i < len; i++) {
            char h[3]; snprintf(h, sizeof h, "%02X", buf[i]); out += h;
        }
        return true;
    }

    bool ok = false;
private:
    Adafruit_PN532 _pn;
    HardwareSerial &_ser;
    int8_t _rx, _tx;
};

// ---- Globals --------------------------------------------------------------
static HX711 scale;
static Reader reader(PN532_RST, Serial2, PN532_RXD, PN532_TXD);
static Preferences prefs;
static AsyncWebServer server(80);
static AsyncWebSocket ws("/ws");

static float   calFactor   = 420.0f;   // placeholder: calibrate with a known weight
static String  mdnsName;

static float   rawWeight = 0, filtered = 0;
static int     shownWeight = 0;
static bool    scaleOk = false;
static uint32_t lastScaleOkMs = 0;

static String  uid;
static uint32_t seenMs = 0;

static String  scaleStatus = "idle";
static float   stableRef = 0;
static uint32_t stableSince = 0;

// Commands from HTTP handlers; executed in loop() because tare/calibration
// block for ~1 s on the HX711 and must not run on the async_tcp task.
static volatile bool  pendTare = false;
static volatile float pendCalGrams = 0;


// ---- Scale ----------------------------------------------------------------
static void saveTare() {
    prefs.begin("scale", false);
    prefs.putLong("tare", scale.get_offset());
    prefs.end();
}

static void doTare() {
    if (!scale.wait_ready_timeout(1000)) return;
    scale.tare(10);
    saveTare();
    filtered = 0; shownWeight = 0;
    uid = "";
}

static void doCalibrate(float grams) {
    if (grams <= 0 || !scale.wait_ready_timeout(1000)) return;
    double v = scale.get_value(10);          // counts above the tare offset
    if (v == 0) return;
    calFactor = (float)(v / grams);
    scale.set_scale(calFactor);
    prefs.begin("scale", false);
    prefs.putFloat("cal", calFactor);
    prefs.end();
}

static void updateScale() {
    if (!scale.is_ready()) {
        if (millis() - lastScaleOkMs > 2000) scaleOk = false;
        return;
    }
    scaleOk = true; lastScaleOkMs = millis();
    rawWeight = scale.get_units(1);
    float a = fabsf(rawWeight - filtered) > FAST_DELTA_G ? EMA_FAST : EMA_SLOW;
    filtered += a * (rawWeight - filtered);
    // 1 g display resolution with hysteresis so the last digit does not flicker
    if (fabsf(filtered - shownWeight) > 0.7f) shownWeight = (int)lroundf(filtered);

    if (fabsf(filtered - stableRef) > 1.0f) { stableRef = filtered; stableSince = millis(); }
}

static void updateStatus() {
    if (shownWeight < PRESENT_G) { scaleStatus = "idle"; return; }
    bool stable = millis() - stableSince >= STABLE_MS;
    scaleStatus = (uid.length() && stable) ? "stable" : "scanning";
}

// ---- RFID -----------------------------------------------------------------
static void pollRfid() {
    static uint32_t last = 0;
    if (millis() - last < RFID_POLL_MS) return;
    last = millis();
    String u;
    if (reader.ok && reader.poll(u)) { uid = u; seenMs = millis(); }
    if (uid.length() && millis() - seenMs > RFID_LOST_MS) uid = "";
}

// ---- JSON / WebSocket -----------------------------------------------------
template <typename T>
static void putField(JsonDocument &d, const char *k, const T &v, T &last, bool full) {
    if (full || v != last) { d[k] = v; last = v; }
}

// Delta-compressed like upstream: a field is present only when it changed.
// `full` forces every field (periodic snapshot).
static String buildFrame(bool full) {
    static int    lWeight = -99999;
    static String lUid, lStatus;
    static bool   lRdR = false, lScale = false;
    static int    lRssi = 1;
    StaticJsonDocument<768> d;

    putField<int>   (d, "weight",          shownWeight,        lWeight, full);
    putField<String>(d, "uid",             uid,                lUid,    full);
    putField<String>(d, "scaleStatus",     scaleStatus,        lStatus, full);
    putField<bool>  (d, "reader_ok",       reader.ok,          lRdR,    full);
    putField<bool>  (d, "scale_ok",        scaleOk,            lScale,  full);
    int rssi = (int)WiFi.RSSI();
    putField<int>   (d, "wifi_signal_dbm", rssi,               lRssi,   full);
    if (full) {
        d["cloud"] = WiFi.status() == WL_CONNECTED;
        d["calibrationFactor"] = calFactor;
        d["uptime_s"] = millis() / 1000;
        d["fw_version"] = FW_VERSION;
        d["mdns"] = mdnsName + ".local";
    }
    if (d.size() == 0) return "";
    String out; serializeJson(d, out); return out;
}

static void onWsEvent(AsyncWebSocket *s, AsyncWebSocketClient *c, AwsEventType t,
                      void *arg, uint8_t *data, size_t len) {
    if (t == WS_EVT_CONNECT) {
        // Full snapshot for the new client only; do not touch the shared delta state.
        StaticJsonDocument<768> d;
        d["weight"] = shownWeight; d["uid"] = uid;
        d["scaleStatus"] = scaleStatus; d["reader_ok"] = reader.ok;
        d["scale_ok"] = scaleOk; d["calibrationFactor"] = calFactor;
        d["uptime_s"] = millis() / 1000; d["fw_version"] = FW_VERSION;
        d["wifi_signal_dbm"] = (int)WiFi.RSSI(); d["cloud"] = true;
        d["mdns"] = mdnsName + ".local";
        String out; serializeJson(d, out); c->text(out);
    }
    // Inbound frames are ignored; commands go over HTTP like upstream.
}

static void sendStatusJson(AsyncWebServerRequest *r) {
    StaticJsonDocument<768> d;
    d["weight"] = shownWeight; d["rawWeight"] = rawWeight;
    d["uid"] = uid;
    d["wifi"] = WiFi.SSID(); d["ip"] = WiFi.localIP().toString();
    d["mdns"] = mdnsName + ".local"; d["cloud"] = WiFi.status() == WL_CONNECTED;
    d["scaleStatus"] = scaleStatus; d["scale_ok"] = scaleOk;
    d["reader_ok"] = reader.ok; d["calibrationFactor"] = calFactor;
    d["uptime_s"] = millis() / 1000; d["fw_version"] = FW_VERSION;
    d["wifi_signal_dbm"] = (int)WiFi.RSSI();
    String out; serializeJson(d, out);
    r->send(200, "application/json", out);
}

// Tiny built-in page so a browser works without the app.
static const char INDEX_HTML[] PROGMEM = R"HTML(<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Scale</title><style>body{font-family:sans-serif;background:#111;color:#eee;text-align:center}
#w{font-size:25vw;margin:.3em 0}button{font-size:5vw;margin:2vw;padding:2vw 5vw}</style>
<div id=w>--</div><div id=s></div><div id=u></div>
<button onclick="fetch('/api/tare',{method:'POST'})">Tare</button>
<script>let st={};const c=new WebSocket('ws://'+location.host+'/ws');
c.onmessage=e=>{Object.assign(st,JSON.parse(e.data));w.textContent=st.weight+' g';
s.textContent=st.scaleStatus;u.textContent=st.uid||''};</script>)HTML";

static void setupRoutes() {
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
    ws.onEvent(onWsEvent);
    server.addHandler(&ws);

    server.on("/", HTTP_GET, [](AsyncWebServerRequest *r) { r->send(200, "text/html", INDEX_HTML); });
    server.on("/api/ping", HTTP_GET, [](AsyncWebServerRequest *r) { r->send(200, "text/plain", "pong"); });
    server.on("/api/status", HTTP_GET, sendStatusJson);
    server.on("/api/tare", HTTP_POST, [](AsyncWebServerRequest *r) {
        pendTare = true; r->send(200, "application/json", "{\"ok\":true}");
    });

    auto *cal = new AsyncCallbackJsonWebHandler("/api/calibration",
        [](AsyncWebServerRequest *r, JsonVariant &j) {
            float f = j["factor"] | 0.0f;
            if (f == 0.0f) f = j["value"] | 0.0f;
            if (f == 0.0f) { r->send(400, "application/json", "{\"ok\":false}"); return; }
            calFactor = f; scale.set_scale(f);
            prefs.begin("scale", false); prefs.putFloat("cal", f); prefs.end();
            r->send(200, "application/json", "{\"ok\":true}");
        });
    server.addHandler(cal);

    // Put a known weight on the platform, then POST {"knownGrams":500}.
    auto *cal2 = new AsyncCallbackJsonWebHandler("/api/calibrate",
        [](AsyncWebServerRequest *r, JsonVariant &j) {
            float g = j["knownGrams"] | 0.0f;
            if (g <= 0) { r->send(400, "application/json", "{\"ok\":false}"); return; }
            pendCalGrams = g; r->send(200, "application/json", "{\"ok\":true}");
        });
    server.addHandler(cal2);


    server.onNotFound([](AsyncWebServerRequest *r) { r->send(404, "text/plain", "not found"); });
}

// ---- setup / loop ---------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\n[BOOT] filament scale " FW_VERSION);

    prefs.begin("scale", true);
    calFactor    = prefs.getFloat("cal", calFactor);
    long tare    = prefs.getLong("tare", 0);
    prefs.end();

    scale.begin(HX711_DOUT, HX711_SCK);
    scale.set_scale(calFactor);
    if (tare != 0) scale.set_offset(tare);
    else if (scale.wait_ready_timeout(1000)) { scale.tare(10); saveTare(); }


    bool okR = reader.init();
    Serial.printf("[RFID] reader=%d\n", okR);

    uint8_t mac[6]; WiFi.macAddress(mac);
    char n[24]; snprintf(n, sizeof n, "filscale-%02X%02X", mac[4], mac[5]);
    mdnsName = n;

    WiFi.mode(WIFI_STA);
    WiFi.setHostname(n);
    WiFiManager wm;
    wm.setConfigPortalTimeout(180);
    if (!wm.autoConnect("FilScale-Setup")) {
        Serial.println("[WiFi] no connection, restarting");
        ESP.restart();
    }
    if (MDNS.begin(n)) MDNS.addService("http", "tcp", 80);
    Serial.printf("[WiFi] %s  http://%s.local\n", WiFi.localIP().toString().c_str(), n);

    setupRoutes();
    server.begin();
}

void loop() {
    static uint32_t lastWs = 0, lastFull = 0;

    if (pendTare)           { pendTare = false; doTare(); }
    if (pendCalGrams > 0)   { float g = pendCalGrams; pendCalGrams = 0; doCalibrate(g); }

    updateScale();
    pollRfid();
    updateStatus();

    if (millis() - lastWs >= WS_INTERVAL_MS) {
        lastWs = millis();
        bool full = millis() - lastFull >= WS_FULL_INTERVAL_MS;
        if (full) lastFull = millis();
        if (ws.count()) {
            String f = buildFrame(full);
            if (f.length()) ws.textAll(f);
        } else {
            buildFrame(true);   // keep the delta baseline in step with no listeners
        }
        ws.cleanupClients();
    }
    delay(1);
}
