// Headless filament scale for ESP32 WROOM-32.
// HX711 load cell + 1x PN532 (HSU).
// State goes to the phone over HTTP + WebSocket; protocol mirrors the upstream
// TigerScale V3 API (docs/API.md) for the fields it shares, plus a few additions.
#include <Arduino.h>
#include <WiFi.h>
#include <ESPmDNS.h>
#include <ESPAsyncWebServer.h>
#include <AsyncJson.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <HX711.h>
#include <NimBLEDevice.h>
#include <ArduinoOTA.h>
#include <esp_ota_ops.h>
#include "ota.h"
#include "firebase.h"
#include "workflow.h"
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

#define BOOT_BTN    0    // Wi-Fi changes over BLE need this pressed (unless offline)

// ---- Tuning ---------------------------------------------------------------
static const uint32_t WS_INTERVAL_MS      = 100;
static const uint32_t WS_FULL_INTERVAL_MS = 30000;
static const uint32_t RFID_POLL_MS        = 250;
static const uint32_t RFID_TEST_POLL_MS   = 100;
static const uint32_t RFID_LOST_MS        = 1500;   // tag gone after this long unseen
static const float    PRESENT_G           = 10.0f;
static const float    EMA_SLOW            = 0.15f;
static const float    EMA_FAST            = 0.6f;
static const float    FAST_DELTA_G        = 5.0f;
static const uint32_t STABLE_MS           = 1200;

// ---- PN532 over HSU -------------------------------------------------------
// RF transmit power / receive sensitivity, same five levels as the original scale
// (its Hardware/RFID test screen steps through them). Level 0 is TX almost off.
struct RfLevel { uint8_t gsNOn, cwGsP, modGsP, rxMinLevel; };
static const RfLevel RF_LEVELS[] = {
    { 0x00, 0x00, 0x00, 0xF },
    { 0x10, 0x04, 0x01, 0xE },
    { 0x20, 0x08, 0x02, 0xD },
    { 0x30, 0x0C, 0x03, 0xC },
    { 0x40, 0x10, 0x04, 0xB },
};
static const uint8_t RF_LEVEL_COUNT = sizeof(RF_LEVELS) / sizeof(RF_LEVELS[0]);
static uint8_t rfPow = 3;

// What a TigerTag keeps in pages 5..8: product, material (bytes 4-5), brand (10-11) and colour (12-14).
struct TagMeta {
    bool valid = false;
    uint16_t brand = 0, material = 0;
    uint8_t r = 0, g = 0, b = 0;
};

class Reader {
public:
    Reader(uint8_t rst, HardwareSerial &ser, int8_t rx, int8_t tx)
        : _pn(rst, &ser), _ser(ser), _rx(rx), _tx(tx) {}

    bool init() {
        // Adafruit's begin() calls serial->begin(115200) with no pins, so the
        // pins have to be claimed first.
        _ser.begin(115200, SERIAL_8N1, _rx, _tx);
        // Adafruit_PN532 reads replies with Stream::readBytes(), which waits the stream's timeout (1000 ms
        // by default) whenever fewer bytes arrive than it asked for. A "no tag" reply is short, so every
        // poll with nothing in the field blocked the whole main loop for a second: weight and status
        // reached the phone once per second and the filter got one sample per second. A real reply
        // arrives in ~2 ms at 115200, so 30 ms is plenty.
        _ser.setTimeout(30);
        _pn.begin();
        version = _pn.getFirmwareVersion();
        ok = version != 0;
        if (ok) { _pn.SAMConfig(); _pn.setPassiveActivationRetries(1); applyRfPower(rfPow); }
        return ok;
    }

    // Applied live, no re-init needed. A command sent right behind another is not always
    // acknowledged on this hardware, so settle first and retry once (as the original does).
    void applyRfPower(uint8_t level) {
        if (!ok) return;
        if (level >= RF_LEVEL_COUNT) level = RF_LEVEL_COUNT - 1;
        const RfLevel &lv = RF_LEVELS[level];
        uint8_t cmd[13] = {
            PN532_COMMAND_RFCONFIGURATION, 0x0A, 0x59, lv.gsNOn, lv.cwGsP, lv.modGsP,
            0x4D, (uint8_t)((lv.rxMinLevel << 4) | 0x5), 0x61, 0x6F, 0x26, 0x62, 0x87,
        };
        delay(20);
        if (!_pn.sendCommandCheckAck(cmd, sizeof(cmd), 500)) {
            delay(40);
            _pn.sendCommandCheckAck(cmd, sizeof(cmd), 500);
        }
    }

    // "PN532 v1.6" from the firmware-version word (IC, ver, rev, support).
    String versionText() const {
        if (!version) return "";
        char b[24]; snprintf(b, sizeof b, "PN5%02X v%u.%u", (unsigned)(version >> 24), (unsigned)((version >> 16) & 0xFF), (unsigned)((version >> 8) & 0xFF));
        return b;
    }

    // Returns true and fills `out` with an uppercase hex UID when a tag is present.
    bool poll(String &out) {
        uint8_t buf[255] = {0};   // library trusts the frame's length byte; leave room
        uint8_t len = 0;
        drain();
        // 150 ms, not 30: the PN532 answers a detection late on a real spool. A reply that arrives after
        // the library gave up stays in the UART and is read as the ACK of the next command, which
        // desynchronises everything (seen as "No ACK frame received" on the page reads).
        bool found = _pn.readPassiveTargetID(PN532_MIFARE_ISO14443A, buf, &len, 150);
        if (!found || (len != 4 && len != 7 && len != 10)) return false;
        out = "";
        for (uint8_t i = 0; i < len; i++) {
            char h[3]; snprintf(h, sizeof h, "%02X", buf[i]); out += h;
        }
        return true;
    }

    // Reads the TigerTag fields right after poll() selected the tag. Short retry, as the original does.
    bool readMeta(TagMeta &m) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt) delay(attempt == 1 ? 10 : 60);
            drain();
            uint8_t d[16];
            bool good = true;
            uint8_t failedPage = 0;
            for (uint8_t i = 0; i < 4 && good; i++) {
                good = _pn.mifareultralight_ReadPage(5 + i, d + 4 * i);
                if (!good) failedPage = 5 + i;
            }
            if (!good) {
                static int shown = 0;
                if (shown++ < 4) Serial.printf("[RFID] ReadPage(%u) failed\n", failedPage);
                continue;
            }
            Serial.printf("[RFID] pages 5-8: ");
            for (int k = 0; k < 16; k++) Serial.printf("%02X ", d[k]);
            Serial.println();
            m.material = (uint16_t)((d[4] << 8) | d[5]);
            m.brand = (uint16_t)((d[10] << 8) | d[11]);
            m.r = d[12]; m.g = d[13]; m.b = d[14];
            m.valid = true;
            return true;
        }
        return false;
    }

    // Throws away anything left in the UART so the next ACK is read from a clean stream.
    void drain() { while (_ser.available()) _ser.read(); }

    bool ok = false;
    uint32_t version = 0;
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

static String  uid;        // tag latched by the workflow (the original's lastUID), shown everywhere
static String  tagLive;    // tag the reader sees right now
static TagMeta tagMeta;    // brand / material / colour of the tag being handled
static String  metaUid;    // tag tagMeta was read from
static volatile bool rfTest = false;      // RFID test screen open: poll faster, keep the last UID
static String  testUid;                    // sticky: stays after the tag is removed, until reset
static volatile int pendRfPow = -1;
static uint32_t seenMs = 0;

static String  scaleStatus = "idle";
static float   stableRef = 0;
static uint32_t stableSince = 0;

// Commands from HTTP handlers; executed in loop() because tare/calibration
// block for ~1 s on the HX711 and must not run on the async_tcp task.
static volatile bool  pendTare = false;
static volatile float pendCalFactor = 0;
static volatile bool  pendWfStop = false;
static volatile uint32_t pendRestartAt = 0;
static volatile float pendCalGrams = 0;
static volatile bool  pendWifi = false, pendScan = false;
static String         pendSsid, pendPass;
static uint32_t       bootBtnMs = 0;          // last time BOOT was seen pressed
static uint32_t       wifiAttemptUntil = 0;   // >0 while a provisioning attempt runs
static bool           wifiFailed = false;
static bool           mdnsOn = false;


// ---- Scale ----------------------------------------------------------------
static void saveTare() {
    prefs.begin("scale", false);
    prefs.putLong("tare", scale.get_offset());
    prefs.end();
}

// Tare without blocking. scale.tare(10) waits for 10 HX711 samples (~0.9 s at 10 SPS) and froze the
// main loop for that long on every tare, which the auto-tare triggers constantly. Instead the job
// below is fed one sample per pass by updateScale() and applied when all 10 have arrived.
static const int TARE_SAMPLES = 10;
static int     tareN = -1;          // -1 = no tare running
static int64_t tareSum = 0;

static void doTare() {
    tareN = 0; tareSum = 0;
    filtered = 0; shownWeight = 0;  // weighing restarts from zero, as it did after the blocking tare
}

// The NVS key "cal" doubles as the "has ever been calibrated" sentinel (same idea as the
// original's calFactor key): the app nags until it exists, factory reset erases it.
static bool calDone = false;

static void saveCalFactor(float f) {
    prefs.begin("scale", false);
    prefs.putFloat("cal", f);
    prefs.end();
    calDone = true;
}

static void doCalibrate(float grams) {
    if (grams <= 0 || !scale.wait_ready_timeout(1000)) return;
    double v = scale.get_value(10);          // counts above the tare offset
    if (v == 0) return;
    calFactor = (float)(v / grams);
    scale.set_scale(calFactor);
    saveCalFactor(calFactor);
}

// Erases everything the owner configured: the scale's NVS namespace (calibration, tare, RF level),
// the Firebase session and the saved Wi-Fi, then restarts. Shared by Studio's factory_reset command
// and the app's Settings row.
static volatile bool pendFactoryReset = false;

static void doFactoryReset() {
    prefs.begin("scale", false); prefs.clear(); prefs.end();
    fbLogout();
    WiFi.disconnect(true, true);
    pendRestartAt = millis() + 1500;
}

// ---- Calibration wizard ---------------------------------------------------
// Port of the original's 3-step wizard (runCalibrationWizard): 1 empty + TARE (and the zero must
// hold), 2 pick the reference weight, 3 place it and CALIBRATE once the reading is steady.
// The original blocks the main loop on its LCD; here it is a non-blocking state machine driven
// by commands from the app, so BLE/Wi-Fi keep flowing. The app only draws the screens.
enum CalPhase { CAL_OFF = 0, CAL_TARE_WAIT, CAL_TARING, CAL_REF, CAL_PLACE, CAL_MEASURING, CAL_SAVED, CAL_ERROR };
enum CalCmd   { CC_NONE = 0, CC_START, CC_TARE, CC_REF, CC_MEASURE, CC_BACK, CC_CANCEL };

static const float    CAL_REF_MIN_G       = 150.0f;    // keypad range of the original
static const float    CAL_REF_MAX_G       = 4500.0f;
static const float    CAL_MIN_FACTOR      = 0.5f;      // below this the reading is not plausible
static const float    CAL_STABLE_BAND_G   = 1.0f;      // last 6 samples within 1 g
static const uint32_t CAL_SAMPLE_MS       = 250;
static const uint32_t CAL_SETTLE_MS       = 2000;      // pause before the 30-sample average
static const uint32_t CAL_SAVED_SHOW_MS   = 2000;
static const uint32_t CAL_ERROR_SHOW_MS   = 2500;
static const uint32_t CAL_STEP_TIMEOUT_MS = 20000;     // taring / measuring that never completes
static const uint32_t CAL_IDLE_TIMEOUT_MS = 180000;    // app gone mid-wizard: give the scale back

static volatile int   pendCalCmd = CC_NONE;
static volatile float pendCalArg = 0;
static int      calPhase = CAL_OFF;
static bool     calStable = false;
static float    calRef = 0;
static String   calErr;                  // "" | "zero" (pan would not hold zero) | "read" | "ref"
static float    calSavedFactor = 0;
static long     calSavedOffset = 0, calOffset = 0;
static uint32_t calPhaseAt = 0, calLastCmdMs = 0, calNextAt = 0;
static int64_t  calSum = 0;
static int      calN = 0, calSub = 0, calChk = 0;
static float    calRing[6];
static int      calRingN = 0, calRingI = 0;

static inline bool calActive() { return calPhase != CAL_OFF; }

static void calCommand(int cmd, float arg = 0) { pendCalArg = arg; pendCalCmd = cmd; }

static void calSetPhase(int p) { calPhase = p; calPhaseAt = millis(); calSub = 0; }

// Leaves the wizard. Not saved = put the tare offset back, exactly as the original's cancel path.
static void calEnd(bool saved) {
    if (!saved) scale.set_offset(calSavedOffset);
    calStable = false;
    calSetPhase(CAL_OFF);
    filtered = 0; shownWeight = 0;       // the weigh loop restarts from zero
}

static bool calReadRaw(long &v) {
    if (!scale.is_ready()) return false;
    v = scale.read();
    return true;
}

static void calFail(const char *why) {
    calErr = why;
    Serial.printf("[CAL] error: %s\n", why);
    calSetPhase(CAL_ERROR);
}

static void calTick() {
    uint32_t now = millis();
    int cmd = pendCalCmd; float arg = pendCalArg; pendCalCmd = CC_NONE;
    if (cmd != CC_NONE) calLastCmdMs = now;

    if (calPhase == CAL_OFF) {
        if (cmd == CC_START) {
            tareN = -1;                                 // the wizard owns the load cell: drop any tare in flight
            wfStop();                                   // no weigh session may run underneath
            calSavedFactor = calFactor;
            calSavedOffset = scale.get_offset();
            calErr = ""; calStable = false; calRef = 0;
            calSetPhase(CAL_TARE_WAIT);
            Serial.println("[CAL] start");
        }
        return;
    }

    // Commands
    if (cmd == CC_CANCEL && calPhase != CAL_SAVED) { Serial.println("[CAL] cancelled"); calEnd(false); return; }
    if (cmd == CC_BACK) {
        calErr = ""; calStable = false;
        if (calPhase == CAL_TARE_WAIT) { Serial.println("[CAL] cancelled"); calEnd(false); return; }
        if (calPhase == CAL_REF) calSetPhase(CAL_TARE_WAIT);
        else if (calPhase == CAL_PLACE) calSetPhase(CAL_REF);
    } else if (cmd == CC_TARE && calPhase == CAL_TARE_WAIT) {
        calErr = ""; calSum = 0; calN = 0;
        calSetPhase(CAL_TARING);
    } else if (cmd == CC_REF && calPhase == CAL_REF) {
        if (arg >= CAL_REF_MIN_G && arg <= CAL_REF_MAX_G) {
            calRef = arg; calErr = ""; calStable = false; calRingN = 0; calRingI = 0; calNextAt = 0;
            calSetPhase(CAL_PLACE);
            Serial.printf("[CAL] reference %.0f g\n", calRef);
        } else calErr = "ref";
    } else if (cmd == CC_MEASURE && calPhase == CAL_PLACE && calStable) {
        calSum = 0; calN = 0;
        calSetPhase(CAL_MEASURING);
    }

    now = millis();   // calSetPhase() above stamped a later time; an unsigned now - stamp would wrap
    // Counts per gram used to judge "within 1 g" while the real factor is not known yet. The
    // original trusts the stored factor, but a never-calibrated scale can hold garbage (this one
    // held 0.072: 1 g = 0.07 counts, so the zero could never hold). Below 50 fall back to a typical
    // 5 kg cell + HX711 value; the real factor is computed from the measurement anyway.
    float dispFactor = fabsf(calSavedFactor) >= 50.0f ? fabsf(calSavedFactor) : 400.0f;
    long r;
    switch (calPhase) {
    case CAL_TARING:
        if (calSub == 0) {                              // 20-sample tare
            if (calReadRaw(r)) {
                calSum += r;
                if (++calN >= 20) {
                    calOffset = (long)(calSum / calN);
                    scale.set_offset(calOffset);
                    calSub = 1; calChk = 0; calNextAt = now + CAL_SAMPLE_MS;
                }
            }
        } else if ((int32_t)(now - calNextAt) >= 0 && calReadRaw(r)) {
            // The pan must HOLD that zero: 4 quarter-second reads within 1 g, else ask again.
            float g = (float)(r - calOffset) / dispFactor;
            Serial.printf("[CAL] zero check %d: %.2f g\n", calChk + 1, g);
            if (fabsf(g) >= 1.0f) { calErr = "zero"; calSetPhase(CAL_TARE_WAIT); break; }
            calNextAt = now + CAL_SAMPLE_MS;
            if (++calChk >= 4) { calErr = ""; calSetPhase(CAL_REF); }
        }
        if (calPhase == CAL_TARING && now - calPhaseAt > CAL_STEP_TIMEOUT_MS) calFail("read");
        break;

    case CAL_PLACE:
        // Steady = last 6 samples within 1 g. CALIBRATE arms only then, so the 30-sample average
        // can never start on a still-swinging pan.
        if ((int32_t)(now - calNextAt) >= 0 && calReadRaw(r)) {
            calNextAt = now + CAL_SAMPLE_MS;
            float g = (float)(r - calOffset) / dispFactor;
            calRing[calRingI] = g; calRingI = (calRingI + 1) % 6; if (calRingN < 6) calRingN++;
            float mn = calRing[0], mx = calRing[0];
            for (int i = 1; i < calRingN; i++) { if (calRing[i] < mn) mn = calRing[i]; if (calRing[i] > mx) mx = calRing[i]; }
            calStable = (calRingN == 6) && (mx - mn < CAL_STABLE_BAND_G) && (fabsf(g) > 1.0f);
        }
        break;

    case CAL_MEASURING:
        if (now - calPhaseAt < CAL_SETTLE_MS) break;
        if (calReadRaw(r)) { calSum += r; calN++; }
        if (calN >= 30) {
            float raw = (float)((double)calSum / calN - (double)calOffset);
            float f = fabsf(raw / calRef);
            Serial.printf("[CAL] raw=%.1f ref=%.0f factor=%.4f\n", raw, calRef, f);
            if (f < CAL_MIN_FACTOR) { calFail("read"); break; }
            calFactor = f;
            scale.set_scale(calFactor);
            saveCalFactor(calFactor);
            saveTare();
            calErr = "";
            calSetPhase(CAL_SAVED);
        } else if (now - calPhaseAt > CAL_STEP_TIMEOUT_MS) calFail("read");
        break;

    case CAL_SAVED:
        if (now - calPhaseAt >= CAL_SAVED_SHOW_MS) calEnd(true);
        break;

    case CAL_ERROR:
        if (now - calPhaseAt >= CAL_ERROR_SHOW_MS) calEnd(false);
        break;
    }

    if ((calPhase == CAL_TARE_WAIT || calPhase == CAL_REF || calPhase == CAL_PLACE)
        && now - calLastCmdMs > CAL_IDLE_TIMEOUT_MS) {
        Serial.println("[CAL] idle timeout");
        calEnd(false);
    }
}

static void updateScale() {
    if (!scale.is_ready()) {
        if (millis() - lastScaleOkMs > 2000) scaleOk = false;
        return;
    }
    scaleOk = true; lastScaleOkMs = millis();
    long raw = scale.read();
    if (tareN >= 0) {                       // a tare is collecting its samples: this pass feeds it
        tareSum += raw;
        if (++tareN >= TARE_SAMPLES) {
            scale.set_offset((long)(tareSum / tareN));
            tareN = -1;
            saveTare();
            filtered = 0; shownWeight = 0;
            tagLive = "";
        }
        return;
    }
    rawWeight = (float)(raw - scale.get_offset()) / scale.get_scale();
    float a = fabsf(rawWeight - filtered) > FAST_DELTA_G ? EMA_FAST : EMA_SLOW;
    filtered += a * (rawWeight - filtered);
    // 1 g display resolution with hysteresis so the last digit does not flicker
    if (fabsf(filtered - shownWeight) > 0.7f) shownWeight = (int)lroundf(filtered);

    if (fabsf(filtered - stableRef) > 1.0f) { stableRef = filtered; stableSince = millis(); }
}

static void updateStatus() {
    WfInputs in;
    in.w = filtered;
    in.tagUid = tagLive;
    in.signedIn = fbState() == FB_SIGNED_IN;
    in.wifiUp = WiFi.status() == WL_CONNECTED;
    FbSpool sp = fbSpool();
    in.spoolFetched = sp.fetched;
    in.container = sp.container;
    in.twin = sp.twin;
    WfOutputs out;
    wfUpdate(in, out);
    if (out.tare) pendTare = true;
    uid = wfUid();
    if (uid.isEmpty() && tagLive.isEmpty() && metaUid.length()) { metaUid = ""; tagMeta = TagMeta(); }
    scaleStatus = wfStatus();
}

// ---- RFID -----------------------------------------------------------------
static void pollRfid() {
    static uint32_t last = 0;
    if (millis() - last < (rfTest ? RFID_TEST_POLL_MS : RFID_POLL_MS)) return;
    last = millis();
    String u;
    if (reader.ok && reader.poll(u)) {
        tagLive = u; seenMs = millis();
        if (rfTest) testUid = u;
        // Brand / material / colour live in the tag's pages 5-8. A single failed read must not mean "no
        // data for this tag": keep trying (a few times) while the tag is in the field.
        static int metaTries = 0;
        if (u != metaUid) { metaUid = u; metaTries = 0; tagMeta = TagMeta(); }
        if (!tagMeta.valid && metaTries < 12) {
            metaTries++;
            TagMeta m;
            if (reader.readMeta(m)) {
                tagMeta = m;
                Serial.printf("[RFID] tag %s brand=%u material=%u colour=%02X%02X%02X\n", u.c_str(), m.brand, m.material, m.r, m.g, m.b);
            } else if (metaTries == 1 || metaTries == 12) {
                Serial.printf("[RFID] tag %s: could not read pages 5-8 (try %d)\n", u.c_str(), metaTries);
            }
        }
    }
    if (tagLive.length() && millis() - seenMs > RFID_LOST_MS) tagLive = "";
}

// ---- JSON / WebSocket -----------------------------------------------------
template <typename T>
static void putField(JsonDocument &d, const char *k, const T &v, T &last, bool full) {
    if (full || v != last) { d[k] = v; last = v; }
}

// Per-channel delta baseline: WebSocket and BLE each remember what they last sent.
struct FrameState {
    int    weight = -99999;
    String uid, status;
    bool   readerOk = false, scaleOk = false;
    int    rssi = 1;
    String ava = "\x01";   // sentinel: never equal to a real URL, so the first frame always sends it
};

// Delta-compressed like upstream: a field is present only when it changed.
// `full` forces every field (periodic snapshot). `compact` keeps the frame
// under one BLE notification (MTU 185) by dropping the Wi-Fi-only extras and
// adding the IP, so the app can switch to Wi-Fi by itself.
static String buildFrame(bool full, FrameState &st, bool compact = false) {
    StaticJsonDocument<1024> d;

    putField<int>   (d, "weight",          shownWeight,        st.weight,   full);
    putField<String>(d, "uid",             uid,                st.uid,      full);
    putField<String>(d, "scaleStatus",     scaleStatus,        st.status,   full);
    putField<bool>  (d, "reader_ok",       reader.ok,          st.readerOk, full);
    putField<bool>  (d, "scale_ok",        scaleOk,            st.scaleOk,  full);
    if (!compact) {
        String ava = fbAvatarUrl();
        putField<String>(d, "fb_avatar", ava, st.ava, full);
        int rssi = (int)WiFi.RSSI();
        putField<int>(d, "wifi_signal_dbm", rssi,              st.rssi,     full);
    }
    if (full) {
        if (!compact) {
            d["calibrationFactor"] = calFactor;
            d["cloud"] = WiFi.status() == WL_CONNECTED;
            d["uptime_s"] = millis() / 1000;
            d["fw_version"] = FW_VERSION;
            d["mdns"] = mdnsName + ".local";
        }
    }
    if (d.size() == 0) return "";
    String out; serializeJson(d, out); return out;
}

// RFID test state, sent as its own small frame so the core frames stay under one BLE MTU.
struct RfState { int pow = -1; int test = -1; String uid, ver, tc; int tb = -2, tm = -2; bool first = true; };

static String buildRfFrame(bool full, RfState &st) {
    StaticJsonDocument<192> d;
    String ver = reader.versionText();
    int pow = rfPow, test = rfTest ? 1 : 0;
    putField<int>(d, "rf_pow", pow, st.pow, full);
    putField<int>(d, "rf_test", test, st.test, full);
    putField<String>(d, "rf_uid", testUid, st.uid, full);
    putField<String>(d, "rf_ver", ver, st.ver, full);
    // TigerTag brand / material ids and colour (the app turns the ids into names)
    int tb = tagMeta.valid ? tagMeta.brand : -1, tm = tagMeta.valid ? tagMeta.material : -1;
    char hex[8] = "";
    if (tagMeta.valid) snprintf(hex, sizeof hex, "%02X%02X%02X", tagMeta.r, tagMeta.g, tagMeta.b);
    String tc = hex;
    putField<int>(d, "tb", tb, st.tb, full);
    putField<int>(d, "tm", tm, st.tm, full);
    putField<String>(d, "tc", tc, st.tc, full);
    if (d.size() == 0) return "";
    String out; serializeJson(d, out); return out;
}

// Calibration wizard state, its own small frame like the RFID one. cal = CalPhase, cal_ok = the
// reading is steady (CALIBRATE may be pressed), cal_err = "" | zero | read | ref, cal_done = the
// scale has been calibrated at least once (the app's first-calibration reminder keys off it).
struct CalState { int phase = -1, ref = -1, done = -1; bool ok = false; String err = "\x01"; float cf = -1; };

static String buildCalFrame(bool full, CalState &st) {
    StaticJsonDocument<192> d;
    int ph = calPhase, ref = (int)calRef, done = calDone ? 1 : 0;
    bool ok = calStable;
    String err = calErr;
    putField<int>   (d, "cal",         ph,        st.phase, full);
    putField<bool>  (d, "cal_ok",      ok,        st.ok,    full);
    putField<int>   (d, "cal_ref",     ref,       st.ref,   full);
    putField<String>(d, "cal_err",     err,       st.err,   full);
    putField<int>   (d, "cal_done",    done,      st.done,  full);
    putField<float> (d, "calibrationFactor", calFactor, st.cf, full);
    if (d.size() == 0) return "";
    String out; serializeJson(d, out); return out;
}

// OTA progress for the app: ota = OtaPhase (0 idle, 1 armed, 2 receiving, 3 done, 4 error), ota_pct = 0..100.
struct OtaState { int phase = -1, pct = -1; };

static String buildOtaFrame(bool full, OtaState &st) {
    StaticJsonDocument<64> d;
    int ph = otaPhase(), pct = otaPercent();
    putField<int>(d, "ota", ph, st.phase, full);
    putField<int>(d, "ota_pct", pct, st.pct, full);
    if (d.size() == 0) return "";
    String out; serializeJson(d, out); return out;
}

static FrameState wsState, bleState;
static RfState wsRf, bleRf;
static CalState wsCal, bleCal;
static OtaState wsOta, bleOta;

// ---- BLE (NimBLE) ----------------------------------------------------------
// Same JSON as the WebSocket, over one notify characteristic. Commands come back
// as JSON on a write characteristic: {"cmd":"tare"} / {"cmd":"calibrate","grams":500}.
static const char *BLE_SVC   = "6e5f0001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *BLE_STATE = "6e5f0002-b5a3-f393-e0a9-e50e24dcca9e";
static const char *BLE_CMD   = "6e5f0003-b5a3-f393-e0a9-e50e24dcca9e";
// Needs an encrypted (paired) link: carries Wi-Fi and account passwords.
static const char *BLE_SEC   = "6e5f0004-b5a3-f393-e0a9-e50e24dcca9e";
static NimBLECharacteristic *bleStateChr = nullptr;
static volatile int  bleClients = 0;
static volatile bool bleNeedFull = false;

class BleServerCb : public NimBLEServerCallbacks {
    void onConnect(NimBLEServer *s, NimBLEConnInfo &info) override { bleClients = bleClients + 1; }
    void onDisconnect(NimBLEServer *s, NimBLEConnInfo &info, int reason) override {
        Serial.printf("[BLE] client disconnected, reason=0x%X\n", reason);
        if (bleClients > 0) bleClients = bleClients - 1;
        NimBLEDevice::startAdvertising();
    }
};

class BleStateCb : public NimBLECharacteristicCallbacks {
    // A client just enabled notifications: send it the full snapshot.
    void onSubscribe(NimBLECharacteristic *c, NimBLEConnInfo &info, uint16_t subValue) override {
        if (subValue) bleNeedFull = true;
    }
};

static void bleSend(const String &json) {
    if (!bleStateChr || bleClients == 0 || json.isEmpty()) return;
    bleStateChr->setValue((const uint8_t *)json.c_str(), json.length());
    bleStateChr->notify();
}

// 0 idle, 1 connecting, 2 connected, 3 failed
static int wifiStateCode() {
    if (WiFi.status() == WL_CONNECTED) return 2;
    if (wifiAttemptUntil) return 1;
    return wifiFailed ? 3 : 0;
}

class BleCmdCb : public NimBLECharacteristicCallbacks {
    bool secure;
public:
    explicit BleCmdCb(bool sec) : secure(sec) {}
    void onWrite(NimBLECharacteristic *c, NimBLEConnInfo &info) override {
        NimBLEAttValue v = c->getValue();
        StaticJsonDocument<320> d;
        if (deserializeJson(d, (const char *)v.data(), v.size())) return;
        const char *cmd = d["cmd"] | "";
        if (!secure) {
            if (!strcmp(cmd, "tare")) pendTare = true;
            else if (!strcmp(cmd, "calibrate")) {
                float g = d["grams"] | 0.0f;
                if (g > 0) pendCalGrams = g;
            } else if (!strcmp(cmd, "cal_start"))   { calCommand(CC_START);
            } else if (!strcmp(cmd, "cal_tare"))    { calCommand(CC_TARE);
            } else if (!strcmp(cmd, "cal_ref"))     { calCommand(CC_REF, d["grams"] | 0.0f);
            } else if (!strcmp(cmd, "cal_measure")) { calCommand(CC_MEASURE);
            } else if (!strcmp(cmd, "cal_back"))    { calCommand(CC_BACK);
            } else if (!strcmp(cmd, "cal_cancel"))  { calCommand(CC_CANCEL);
            } else if (!strcmp(cmd, "cal_factor")) {
                float f = d["value"] | 0.0f;
                if (f > 0) pendCalFactor = f;       // manual entry, same path as Studio's calibration_set
            } else if (!strcmp(cmd, "wifi_scan")) {
                pendScan = true;
            } else if (!strcmp(cmd, "rfid_test")) {
                bool on = d["on"] | false;
                if (on) testUid = "";
                rfTest = on;
            } else if (!strcmp(cmd, "rfid_reset")) {
                testUid = "";
            } else if (!strcmp(cmd, "rf_power")) {
                int lv = d["level"] | -1;
                if (lv >= 0 && lv < RF_LEVEL_COUNT) pendRfPow = lv;
            }
            return;
        }
        if (!strcmp(cmd, "wifi")) {
            // Anyone in Bluetooth range could otherwise repoint the scale. Allow it
            // when the scale has no Wi-Fi (first setup) or BOOT was pressed in the
            // last 30 s (physical presence).
            bool allowed = WiFi.status() != WL_CONNECTED || (bootBtnMs && millis() - bootBtnMs < 30000);
            if (!allowed) { bleSend("{\"wifi_err\":\"boot\"}"); return; }
            const char *ssid = d["ssid"] | "";
            if (!*ssid) return;
            pendSsid = ssid;
            pendPass = String((const char *)(d["pass"] | ""));
            pendWifi = true;
        } else if (!strcmp(cmd, "fb_login")) {
            // The cloud account belongs to whoever is paired, but replacing an existing
            // session needs the same physical-presence proof as changing the network.
            bool allowed = fbState() != FB_SIGNED_IN || (bootBtnMs && millis() - bootBtnMs < 30000);
            if (!allowed) { bleSend("{\"fb_err\":\"boot\"}"); return; }
            const char *email = d["email"] | "";
            const char *pass = d["pass"] | "";
            if (*email && *pass) fbLogin(email, pass);
        } else if (!strcmp(cmd, "fb_logout")) {
            fbLogout();
        } else if (!strcmp(cmd, "restart")) {
            pendRestartAt = millis() + 1500;
        } else if (!strcmp(cmd, "factory_reset")) {
            // Wipes Wi-Fi, account and calibration. The encrypted link (paired phone) is the authority;
            // the app makes the user hold a button for 3 s before it sends this.
            pendFactoryReset = true;
        } else if (!strcmp(cmd, "ota_arm")) {
            // The app is about to push a firmware image over Wi-Fi. The one-time token comes back over this
            // encrypted link, so only the paired phone can start an update.
            String tok; const char *err = "";
            if (calActive()) err = "busy";
            else if (otaArm(d["size"] | 0u, d["md5"] | "", tok, err)) { bleSend(String("{\"ota_tok\":\"") + tok + "\"}"); return; }
            bleSend(String("{\"ota_err\":\"") + err + "\"}");
        }
    }
};

static void setupBle() {
    NimBLEDevice::init(mdnsName.c_str());
    NimBLEDevice::setMTU(185);
    // LE Secure Connections, "Just Works" pairing (no display or keys on this board):
    // encrypts the link against passive eavesdropping, and bonds so it happens once.
    NimBLEDevice::setSecurityAuth(true, false, true);
    NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT);
    NimBLEServer *srv = NimBLEDevice::createServer();
    srv->setCallbacks(new BleServerCb());
    NimBLEService *svc = srv->createService(BLE_SVC);
    bleStateChr = svc->createCharacteristic(BLE_STATE, NIMBLE_PROPERTY::NOTIFY);
    bleStateChr->setCallbacks(new BleStateCb());
    NimBLECharacteristic *cmd = svc->createCharacteristic(BLE_CMD, NIMBLE_PROPERTY::WRITE);
    cmd->setCallbacks(new BleCmdCb(false));
    NimBLECharacteristic *sec = svc->createCharacteristic(BLE_SEC, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC);
    sec->setCallbacks(new BleCmdCb(true));
    svc->start();
    NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
    adv->setName(mdnsName.c_str());
    adv->addServiceUUID(BLE_SVC);
    adv->enableScanResponse(true);
    adv->start();
    Serial.printf("[BLE] advertising as %s\n", mdnsName.c_str());
}

static FrameState bleNetState;   // separate frame: network facts that would not fit the MTU

// Several notifications queued back to back overflow the controller's buffers and the last
// ones are dropped, so frames wait in a small queue and go out one per pass (every 100 ms).
static String bleQueue[12];
static int bleQueued = 0;

static void bleEnqueue(const String &json) {
    if (json.isEmpty()) return;
    if (bleQueued == 12) {                       // full: drop the oldest, newer state wins
        for (int i = 1; i < 12; i++) bleQueue[i - 1] = bleQueue[i];
        bleQueued--;
    }
    bleQueue[bleQueued++] = json;
}

static void bleFlushOne() {
    if (!bleQueued) return;
    bleSend(bleQueue[0]);
    for (int i = 1; i < bleQueued; i++) bleQueue[i - 1] = bleQueue[i];
    bleQueue[--bleQueued] = "";
}

// The avatar URL can be longer than one notification, so it goes in numbered chunks:
// {"fba":N,"fbi":i,"fbd":"..."}  (N chunks in total); {"fba":0} means "no avatar".
static void enqueueAvatar(const String &url) {
    if (url.isEmpty()) { bleEnqueue("{\"fba\":0}"); return; }
    const int CH = 100;
    int n = (url.length() + CH - 1) / CH;
    for (int i = 0; i < n; i++) {
        StaticJsonDocument<256> d;
        d["fba"] = n; d["fbi"] = i; d["fbd"] = url.substring(i * CH, min((int)url.length(), (i + 1) * CH));
        String out; serializeJson(d, out); bleEnqueue(out);
    }
}

static void pumpBle(bool periodicFull) {
    if (!bleStateChr || bleClients == 0) {
        bleState = FrameState(); bleNetState = FrameState(); bleRf = RfState(); bleCal = CalState(); bleOta = OtaState();
        for (int i = 0; i < bleQueued; i++) bleQueue[i] = "";
        bleQueued = 0;
        return;
    }
    bool full = periodicFull || bleNeedFull;
    bleNeedFull = false;
    bleEnqueue(buildFrame(full, bleState, true));
    bleEnqueue(buildRfFrame(full, bleRf));
    bleEnqueue(buildCalFrame(full, bleCal));
    bleEnqueue(buildOtaFrame(full, bleOta));

    // Network frame, delta-compressed with the same rule: ssid / ip / wifi state.
    StaticJsonDocument<192> d;
    static String lSsid, lIp;
    static int lWst = -1;
    String ssid = WiFi.status() == WL_CONNECTED ? WiFi.SSID() : String("");
    String ip = WiFi.status() == WL_CONNECTED ? WiFi.localIP().toString() : String("");
    int wst = wifiStateCode();
    putField<String>(d, "ssid", ssid, lSsid, full);
    putField<String>(d, "ip", ip, lIp, full);
    putField<int>(d, "wst", wst, lWst, full);
    if (full) d["calibrationFactor"] = calFactor;
    if (d.size()) { String out; serializeJson(d, out); bleEnqueue(out); }

    // Cloud account state, same delta rule.
    StaticJsonDocument<192> fb;
    static int lFbs = -1;
    static String lFbe, lFbn, lFber;
    int fbs = fbState();
    String fbe = fbEmail(), fbn = fbDisplayName(), fber = fbErrorText();
    putField<int>(fb, "fbs", fbs, lFbs, full);
    putField<String>(fb, "fbe", fbe, lFbe, full);
    putField<String>(fb, "fbn", fbn, lFbn, full);
    putField<String>(fb, "fber", fber, lFber, full);
    static String lFbc;
    String fbc = fbAvatarColor();
    putField<String>(fb, "fbc", fbc, lFbc, full);


    if (fb.size()) { String out; serializeJson(fb, out); bleEnqueue(out); }

    {
        StaticJsonDocument<192> sf;
        static int lCw = -2;
        static String lRk, lRp;
        FbSpool sp = fbSpool();
        putField<int>(sf, "cw", sp.container, lCw, full);
        putField<String>(sf, "rk", sp.rackName, lRk, full);
        putField<String>(sf, "rp", sp.rackPos, lRp, full);
        if (sf.size()) { String out; serializeJson(sf, out); bleEnqueue(out); }
    }

    // Keep-alive: nothing else is sent while the weight and the tag do not change, and the phone cannot
    // tell "no news" from "dead link". A tiny frame every 2 s lets it notice a stalled link in seconds.
    static uint32_t lastKeepAlive = 0;
    if (millis() - lastKeepAlive >= 2000) {
        lastKeepAlive = millis();
        bleEnqueue(String("{\"up\":") + String(millis() / 1000) + "}");
    }

    static String lAva = "\x01";
    String ava = fbAvatarUrl();
    if (full || ava != lAva) { lAva = ava; enqueueAvatar(ava); }

    bleFlushOne();
}

// Scans for networks and sends the strongest few over BLE (fits one notification).
static void doWifiScan() {
    Serial.printf("[WIFI] scan start status=%d\n", (int)WiFi.status());
    int n = WiFi.scanNetworks(false, false);
    bool stopped = false;
    if (n < 0) {   // WIFI_SCAN_FAILED: the radio refuses to scan while a connect attempt is in flight
        Serial.printf("[WIFI] scan failed (%d), stopping reconnect and retrying\n", n);
        WiFi.scanDelete();
        WiFi.disconnect(false, false);   // keeps the saved credentials
        stopped = true;
        delay(500);
        n = WiFi.scanNetworks(false, false);
    }
    Serial.printf("[WIFI] scan n=%d\n", n);
    // ArduinoJson copies each String into the pool (~16 B slot + text), so 8 SSIDs
    // overflowed the old 256 B and the later ones were dropped silently.
    StaticJsonDocument<512> d;
    JsonArray arr = d.createNestedArray("networks");
    size_t used = 16;
    for (int pass = 0; pass < n && arr.size() < 8; pass++) {
        // WiFi.scanNetworks() already sorts by signal strength, strongest first
        String name = WiFi.SSID(pass);
        if (name.isEmpty()) continue;
        if (name.length() > 24) name = name.substring(0, 24);
        bool dup = false;
        for (JsonVariant e : arr) if (name == e.as<const char *>()) dup = true;
        if (dup || used + name.length() + 4 > 170) continue;
        arr.add(name);
        used += name.length() + 4;
    }
    WiFi.scanDelete();
    if (stopped && !pendWifi) WiFi.begin();   // resume the saved network
    String out; serializeJson(d, out);
    bleSend(out);
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
        d["rf_pow"] = rfPow; d["rf_test"] = rfTest ? 1 : 0; d["rf_uid"] = testUid; d["rf_ver"] = reader.versionText();
        String out; serializeJson(d, out); c->text(out);
    }
    // Inbound frames are ignored; commands go over HTTP like upstream.
}

static void sendStatusJson(AsyncWebServerRequest *r) {
    StaticJsonDocument<1536> d;
    d["weight"] = shownWeight; d["rawWeight"] = rawWeight;
    d["uid"] = uid;
    d["wifi"] = WiFi.SSID(); d["ip"] = WiFi.localIP().toString();
    d["mdns"] = mdnsName + ".local"; d["cloud"] = WiFi.status() == WL_CONNECTED;
    d["scaleStatus"] = scaleStatus; d["scale_ok"] = scaleOk;
    d["reader_ok"] = reader.ok; d["calibrationFactor"] = calFactor;
    d["uptime_s"] = millis() / 1000; d["fw_version"] = FW_VERSION;
    d["wifi_signal_dbm"] = (int)WiFi.RSSI();
    d["firebaseAuth"] = fbState() == FB_SIGNED_IN; d["firebaseEmail"] = fbEmail();
    // weigh workflow and last measurement, like the original's /api/session
    d["wfPhase"] = wfPhaseName(); d["sendPhase"] = wfSendPhase();
    WfLast lm = wfLast(); WfStats st = wfStats();
    d["lastMeasurementStatus"] = lm.status; d["lastMeasurementWeight"] = lm.weight; d["lastMeasurementUid"] = lm.uid1;
    d["sendOk"] = st.sendOk; d["sendFail"] = st.sendFail; d["sessions"] = st.sessions;
    FbSpool sp = fbSpool(); d["containerWeight"] = sp.container; d["rack"] = sp.rackName; d["rackPos"] = sp.rackPos;
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

// Commands from Tiger Studio Manager. Runs on the cloud task, so it only sets flags that loop() acts on.
static String onRemoteCommand(const String &type, float value, bool &ok) {
    if (type == "tare") { pendTare = true; return "Tare done"; }
    if (type == "workflow_stop") { pendWfStop = true; return "Workflow stopped"; }
    if (type == "rfid_test_start") { testUid = ""; rfTest = true; return "RFID test started"; }
    if (type == "rfid_test_stop") { rfTest = false; testUid = ""; return "RFID test stopped"; }
    if (type == "heartbeat_now") { fbForceBeat(); return "Heartbeat sent"; }
    if (type == "calibration_set") {
        if (value == 0.0f) { ok = false; return "Missing calibration factor"; }
        pendCalFactor = value;
        return "Calibration updated";
    }
    if (type == "restart") { pendRestartAt = millis() + 1500; return "Restarting..."; }
    if (type == "factory_reset") {
        pendFactoryReset = true;     // done in loop(), not on the cloud task
        return "Reset, restarting...";
    }
    ok = false;
    return String("Not supported by this firmware: ") + type;
}

static void setupRoutes() {
    DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
    ws.onEvent(onWsEvent);
    server.addHandler(&ws);

    server.on("/", HTTP_GET, [](AsyncWebServerRequest *r) { r->send(200, "text/html", INDEX_HTML); });
    server.on("/api/ping", HTTP_GET, [](AsyncWebServerRequest *r) { r->send(200, "text/plain", "pong"); });
    server.on("/api/status", HTTP_GET, sendStatusJson);
    server.on("/api/rfid/test", HTTP_GET, [](AsyncWebServerRequest *r) {
        StaticJsonDocument<256> d;
        d["active"] = (bool)rfTest;
        d["reader_right"] = reader.ok;
        if (testUid.length()) d["uid_right"] = testUid; else d["uid_right"] = nullptr;
        d["power"] = rfPow;
        d["version"] = reader.versionText();
        String out; serializeJson(d, out);
        r->send(200, "application/json", out);
    });
    auto *rfPost = new AsyncCallbackJsonWebHandler("/api/rfid/test",
        [](AsyncWebServerRequest *r, JsonVariant &j) {
            if (j["stop"] | false) rfTest = false;
            else if (j["reset"] | false) testUid = "";
            else { testUid = ""; rfTest = true; }
            int lv = j["power"] | -1;
            if (lv >= 0 && lv < RF_LEVEL_COUNT) pendRfPow = lv;
            r->send(200, "application/json", "{\"ok\":true}");
        });
    server.addHandler(rfPost);
    server.on("/api/tare", HTTP_POST, [](AsyncWebServerRequest *r) {
        pendTare = true; r->send(200, "application/json", "{\"ok\":true}");
    });

    auto *cal = new AsyncCallbackJsonWebHandler("/api/calibration",
        [](AsyncWebServerRequest *r, JsonVariant &j) {
            float f = j["factor"] | 0.0f;
            if (f == 0.0f) f = j["value"] | 0.0f;
            if (f == 0.0f) { r->send(400, "application/json", "{\"ok\":false}"); return; }
            if (f < 0.0f) { r->send(400, "application/json", "{\"ok\":false}"); return; }
            pendCalFactor = f;     // applied in loop(), never while the wizard is running
            r->send(200, "application/json", "{\"ok\":true}");
        });
    server.addHandler(cal);

    // Calibration wizard over Wi-Fi: {"cmd":"cal_start|cal_tare|cal_ref|cal_measure|cal_back|cal_cancel","grams":500}
    auto *calw = new AsyncCallbackJsonWebHandler("/api/cal",
        [](AsyncWebServerRequest *r, JsonVariant &j) {
            const char *c = j["cmd"] | "";
            if      (!strcmp(c, "cal_start"))   calCommand(CC_START);
            else if (!strcmp(c, "cal_tare"))    calCommand(CC_TARE);
            else if (!strcmp(c, "cal_ref"))     calCommand(CC_REF, j["grams"] | 0.0f);
            else if (!strcmp(c, "cal_measure")) calCommand(CC_MEASURE);
            else if (!strcmp(c, "cal_back"))    calCommand(CC_BACK);
            else if (!strcmp(c, "cal_cancel"))  calCommand(CC_CANCEL);
            else { r->send(400, "application/json", "{\"ok\":false}"); return; }
            r->send(200, "application/json", "{\"ok\":true}");
        });
    server.addHandler(calw);

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

// ---- OTA: update over Wi-Fi from the PC (development) -----------------------
// pio run -e esp32dev_hsu_ota -t upload --upload-port <scale-ip>. The password is compiled in from
// firmware/.ota_password (see scripts/ota_password.py); no password means no OTA.
#ifndef OTA_PASSWORD
#define OTA_PASSWORD ""
#endif

static void setupOta(const char *host) {
    if (!OTA_PASSWORD[0]) { Serial.println("[OTA] disabled: no password compiled in"); return; }
    ArduinoOTA.setHostname(host);
    ArduinoOTA.setPassword(OTA_PASSWORD);
    ArduinoOTA.setMdnsEnabled(false);       // loop() runs the one MDNS.begin() once Wi-Fi is up
    // The update streams ~1.6 MB over TCP while a TLS handshake to the cloud wants ~40 KB of the same heap;
    // the first attempt died at 16 % when the TCP stack ran out of buffers (errno 11) during a heartbeat.
    // So: no cloud traffic and no WebSocket clients for the length of the update.
    ArduinoOTA.onStart([]() {
        Serial.println("[OTA] start");
        bool idle = fbPause(true, 6000);
        ws.closeAll();
        // espota sends 1 KB and waits for the answer, so every round trip counts: with Wi-Fi modem sleep (the
        // default) an idle radio answers in ~250 ms and a full update took 7 minutes instead of ~70 s.
        WiFi.setSleep(false);
        Serial.printf("[OTA] cloud paused (%s), heap free=%u largest=%u\n", idle ? "idle" : "request still running",
                      (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
    });
    ArduinoOTA.onEnd([]() { Serial.println("[OTA] done, restarting"); });
    ArduinoOTA.onProgress([](unsigned p, unsigned t) {
        static int last = -1;
        int pct = t ? (int)((uint64_t)p * 100 / t) : 0;
        if (pct / 10 != last / 10) { Serial.printf("[OTA] %d%%\n", pct); }
        last = pct;
    });
    ArduinoOTA.onError([](ota_error_t e) {
        Serial.printf("[OTA] error %u\n", (unsigned)e);
        WiFi.setSleep(true);
        fbPause(false);
    });
    ArduinoOTA.begin();
    Serial.println("[OTA] ready");
}

// ---- setup / loop ---------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(200);
    Serial.println("\n[BOOT] filament scale " FW_VERSION);
    {
        const esp_partition_t *run = esp_ota_get_running_partition();
        if (run) Serial.printf("[BOOT] running from %s at 0x%x (size 0x%x)\n", run->label, (unsigned)run->address, (unsigned)run->size);
    }
    Serial.printf("[HEAP] boot %u\n", (unsigned)ESP.getFreeHeap());

    prefs.begin("scale", true);
    calFactor    = prefs.getFloat("cal", calFactor);
    rfPow        = prefs.getUChar("rfpow", rfPow);
    if (rfPow >= RF_LEVEL_COUNT) rfPow = RF_LEVEL_COUNT - 1;
    long tare    = prefs.getLong("tare", 0);
    calDone      = prefs.isKey("cal");
    prefs.end();

    scale.begin(HX711_DOUT, HX711_SCK);
    scale.set_scale(calFactor);
    if (tare != 0) scale.set_offset(tare);
    else if (scale.wait_ready_timeout(1000)) { scale.tare(10); saveTare(); }


    bool okR = reader.init();
    Serial.printf("[RFID] reader=%d\n", okR);

    uint64_t efuse = ESP.getEfuseMac();   // valid before Wi-Fi starts, unlike WiFi.macAddress()
    uint8_t mac[6]; for (int i = 0; i < 6; i++) mac[i] = (efuse >> (8 * i)) & 0xFF;
    char n[24]; snprintf(n, sizeof n, "tigerscalelite-%02X%02X", mac[4], mac[5]);
    mdnsName = n;

    // No captive portal: the phone app configures Wi-Fi over BLE. Saved credentials
    // (if any) are used by WiFi.begin() with no arguments and kept in NVS.
    WiFi.mode(WIFI_STA);
    WiFi.setHostname(n);
    WiFi.setAutoReconnect(true);
    WiFi.persistent(true);
    pinMode(BOOT_BTN, INPUT_PULLUP);
    Serial.printf("[HEAP] before BLE %u\n", (unsigned)ESP.getFreeHeap());
    setupBle();
    Serial.printf("[HEAP] after BLE %u\n", (unsigned)ESP.getFreeHeap());
    WiFi.begin();

    {
        char macHex[13];
        snprintf(macHex, sizeof macHex, "%02x%02x%02x%02x%02x%02x", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
        fbSetCommandHandler(onRemoteCommand);
        fbBegin(macHex);
    }
    setupRoutes();
    {
        // Streamed over-the-air update from the app or a dev PC (see ota.h). The hooks free heap while it runs.
        static const OtaHooks hooks = {
            []() { fbPause(true, 6000); ws.closeAll(); },
            []() { fbPause(false); },
            []() { pendRestartAt = millis() + 1500; },
        };
        otaInit(server, hooks, OTA_PASSWORD);
    }
    server.begin();
    setupOta(mdnsName.c_str());
    Serial.printf("[HEAP] after server %u largest %u\n", (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
}

void loop() {
    // Diagnostics: a long gap between two passes means something blocked the main loop, and the
    // phone sees every frame (weight, status) late by that much.
    static uint32_t lastPass = 0, lastEnd = 0;
    const uint32_t pass0 = millis();
    {
        if (lastPass && pass0 - lastPass > 400) Serial.printf("[LOOP] stall %u ms\n", (unsigned)(pass0 - lastPass));
        // Time spent between loop() returning and being called again: the scheduler, not our code.
        if (lastEnd && pass0 - lastEnd > 300) Serial.printf("[LOOP] outside loop() %u ms\n", (unsigned)(pass0 - lastEnd));
        lastPass = pass0;
    }
    // Per-section timing of this pass, printed when the pass is slow (find what blocks the loop).
    static const char *const SEC_NAMES[] = { "cmds", "wifi", "fbpub", "scale", "rfid", "status", "ws", "wsclean", "ble", "delay" };
    uint32_t secMs[10] = {0};
    uint32_t lapAt = pass0;
    auto lap = [&](int i) { uint32_t n = millis(); secMs[i] += n - lapAt; lapAt = n; };
    static uint32_t lastWs = 0, lastFull = 0;

    ArduinoOTA.handle();   // blocks for the length of an update, which is the intent; a no-op otherwise
    otaLoop();
    calTick();
    // While the wizard runs it owns the load cell: tare / factor changes wait until it is done.
    if (pendTare)           { pendTare = false; if (!calActive()) doTare(); }
    if (pendWfStop)         { pendWfStop = false; wfStop(); }
    if (pendCalFactor != 0 && !calActive()) {
        calFactor = pendCalFactor; pendCalFactor = 0;
        scale.set_scale(calFactor);
        saveCalFactor(calFactor);
    }
    if (pendFactoryReset)   { pendFactoryReset = false; doFactoryReset(); }
    if (pendRestartAt && (int32_t)(millis() - pendRestartAt) >= 0) ESP.restart();
    if (pendCalGrams > 0 && !calActive()) { float g = pendCalGrams; pendCalGrams = 0; doCalibrate(g); }
    lap(0);

    if (digitalRead(BOOT_BTN) == LOW) bootBtnMs = millis() ? millis() : 1;
    if (pendRfPow >= 0) {
        rfPow = pendRfPow; pendRfPow = -1;
        prefs.begin("scale", false); prefs.putUChar("rfpow", rfPow); prefs.end();
        reader.applyRfPower(rfPow);
    }
    if (pendScan)           { pendScan = false; doWifiScan(); }
    if (pendWifi) {
        pendWifi = false;
        WiFi.disconnect(false, false);
        WiFi.begin(pendSsid.c_str(), pendPass.c_str());
        wifiAttemptUntil = millis() + 20000;
        wifiFailed = false;
    }
    if (wifiAttemptUntil) {
        if (WiFi.status() == WL_CONNECTED) wifiAttemptUntil = 0;
        else if ((int32_t)(millis() - wifiAttemptUntil) >= 0) { wifiAttemptUntil = 0; wifiFailed = true; }
    }
    if (WiFi.status() == WL_CONNECTED && !mdnsOn) {
        if (MDNS.begin(mdnsName.c_str())) { MDNS.addService("http", "tcp", 80); mdnsOn = true; }
    } else if (WiFi.status() != WL_CONNECTED) {
        mdnsOn = false;
    }
    lap(1);

    static uint32_t lastFbPub = 0;
    if (millis() - lastFbPub >= 1000) {
        lastFbPub = millis();
        FbSnapshot fs;
        fs.weight = shownWeight; fs.uid = uid; fs.cal = calFactor; fs.rssi = (int)WiFi.RSSI();
        fs.ip = WiFi.localIP().toString(); fs.mdns = mdnsName + ".local"; fs.fw = FW_VERSION;
        fs.readerOk = reader.ok; fs.scaleOk = scaleOk; fs.status = scaleStatus;
        fs.wfPhase = wfPhaseName(); fs.sendPhase = wfSendPhase();
        WfStats ws_ = wfStats();
        fs.sessionId = ws_.sessionId; fs.sessions = ws_.sessions; fs.sendOk = ws_.sendOk; fs.sendFail = ws_.sendFail;
        fs.rfidOk = ws_.rfidOk; fs.rfidFail = ws_.rfidFail; fs.autoTare = ws_.autoTare; fs.resets = ws_.resets;
        WfLast lm = wfLast();
        fs.lastUid1 = lm.uid1; fs.lastUid2 = lm.uid2; fs.lastStatus = lm.status; fs.lastWeight = lm.weight;
        fbPublish(fs);
    }
    lap(2);

    if (!calActive()) updateScale();    // the wizard reads the HX711 itself
    lap(3);
    pollRfid();
    lap(4);
    if (!calActive()) updateStatus();   // and no weigh session runs underneath it
    lap(5);

    if (millis() - lastWs >= WS_INTERVAL_MS) {
        lastWs = millis();
        bool full = millis() - lastFull >= WS_FULL_INTERVAL_MS;
        if (full) lastFull = millis();
        if (ws.count()) {
            static uint32_t lastWsKeepAlive = 0;
            if (millis() - lastWsKeepAlive >= 2000 && ws.availableForWriteAll()) {
                lastWsKeepAlive = millis();
                ws.textAll(String("{\"up\":") + String(millis() / 1000) + "}");
            }
            String f = buildFrame(full, wsState);
            // a slow client (phone asleep) must not pile up messages: the library closes the socket when its queue fills
            if (f.length() && ws.availableForWriteAll()) ws.textAll(f);
            String rf = buildRfFrame(full, wsRf);
            if (rf.length() && ws.availableForWriteAll()) ws.textAll(rf);
            String cf = buildCalFrame(full, wsCal);
            if (cf.length() && ws.availableForWriteAll()) ws.textAll(cf);
            String of = buildOtaFrame(full, wsOta);
            if (of.length() && ws.availableForWriteAll()) ws.textAll(of);
        } else {
            wsState = FrameState();   // no listeners: next client gets a full frame
            wsRf = RfState();
            wsCal = CalState();
            wsOta = OtaState();
        }
        lap(6);
        // At most 2 WebSocket clients, oldest closed first. A phone that went to sleep (Doze) leaves a dead
        // socket behind; every one costs ~4 KB, and TLS to the cloud needs the contiguous heap they eat
        // (with 2 clients plus reconnect churn the largest free block fell to ~19 KB and every cloud
        // heartbeat failed with HTTP -1).
        ws.cleanupClients(2);
        lap(7);
        pumpBle(full);
        lap(8);
    }
    delay(1);
    lap(9);
    lastEnd = millis();
    if (lastEnd - pass0 > 250) {
        char b[160]; int n = snprintf(b, sizeof b, "[LOOP] slow pass %u ms:", (unsigned)(lastEnd - pass0));
        for (int i = 0; i < 10 && n < (int)sizeof b - 20; i++)
            if (secMs[i] > 20) n += snprintf(b + n, sizeof b - n, " %s=%u", SEC_NAMES[i], (unsigned)secMs[i]);
        Serial.println(b);
    }
}
