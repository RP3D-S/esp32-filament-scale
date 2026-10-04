#include "firebase.h"

#include <ArduinoJson.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <time.h>

// Public client key of the TigerTag Firebase project. It is not a secret: every Firebase
// web client ships it (upstream documents the same).
static const char *API_KEY = "AIzaSyCkxPTs_Cv0KVLqsZj-UKWWqIY0OtfVpnw";
static const char *PROJECT = "tigertag-connect";

static const uint32_t HEARTBEAT_MS = 30000;
static const uint32_t TOKEN_LIFETIME_MS = 3300000;   // refresh 5 min before the 1 h expiry

// ---- shared state (guarded by gLock) -------------------------------------
static SemaphoreHandle_t gLock;
static FbSnapshot gSnap;
static String gMac;

static volatile int gState = FB_SIGNED_OUT;
static String gEmail, gName, gError;

static String gUid, gIdToken, gRefresh;
static String gColor;                     // account colour as RRGGBB (the original draws initials on it)
static FbSpool gSpool;
static bool gProfileDocDone = false;
static bool gProfilePhotoDone = false;
static String gAvatar;                    // avatar URL of the signed-in account
static bool gNeedProfile = false;         // fetch name + avatar once per session
static int gProfileTries = 0;
static uint32_t gTokenMs = 0;

static String gPendEmail, gPendPass;
static volatile bool gPendLogin = false, gPendLogout = false;

static bool gTimeOk = false;

static void saveSession() {
    Preferences p;
    p.begin("fb", false);
    p.putString("refresh", gRefresh);
    p.putString("uid", gUid);
    p.putString("email", gEmail);
    p.putString("name", gName);
    p.putString("avatar", gAvatar);
    p.putString("color", gColor);
    p.end();
}

static void clearSession() {
    Preferences p;
    p.begin("fb", false);
    p.clear();
    p.end();
    gUid = gIdToken = gRefresh = gEmail = gName = gAvatar = gColor = "";
    gProfileDocDone = false;
    gProfilePhotoDone = false;
    gSpool = FbSpool();
}

// TLS with the certificate bundle built into the Arduino core, so the server is verified.
extern const uint8_t rootca_crt_bundle_start[] asm("_binary_x509_crt_bundle_start");
extern const uint8_t rootca_crt_bundle_end[] asm("_binary_x509_crt_bundle_end");

static bool ensureTime() {
    if (gTimeOk) return true;
    if (time(nullptr) > 1700000000L) { gTimeOk = true; return true; }
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    for (int i = 0; i < 40 && time(nullptr) < 1700000000L; i++) delay(250);
    gTimeOk = time(nullptr) > 1700000000L;
    return gTimeOk;
}

// POST/GET helper. Returns the HTTP status, fills `resp`.
static int request(const char *method, const String &url, const String &body,
                   const char *contentType, const String &bearer, String &resp) {
    WiFiClientSecure client;
    client.setCACertBundle(rootca_crt_bundle_start, rootca_crt_bundle_end - rootca_crt_bundle_start);
    HTTPClient http;
    http.setTimeout(10000);
    if (!http.begin(client, url)) return -1;
    if (contentType) http.addHeader("Content-Type", contentType);
    if (bearer.length()) http.addHeader("Authorization", "Bearer " + bearer);
    int code = !strcmp(method, "GET") ? http.GET() : http.POST(body);
    resp = http.getString();
    http.end();
    return code;
}

static String extractStr(const String &json, const char *key) {
    String needle = String("\"") + key + "\"";
    int s = json.indexOf(needle);
    if (s < 0) return "";
    s += needle.length();
    while (s < (int)json.length() && (json[s] == ':' || json[s] == ' ')) s++;
    if (s >= (int)json.length() || json[s] != '"') return "";
    s++;
    int e = json.indexOf('"', s);
    return e > s ? json.substring(s, e) : String("");
}

static String friendlyError(const String &resp) {
    String m = extractStr(resp, "message");
    if (m.startsWith("INVALID_PASSWORD") || m.startsWith("INVALID_LOGIN_CREDENTIALS")) return "Email ou palavra-passe errados";
    if (m.startsWith("EMAIL_NOT_FOUND")) return "Conta não encontrada";
    if (m.startsWith("MISSING_PASSWORD")) return "Conta sem palavra-passe (criada com Google)";
    if (m.startsWith("TOO_MANY_ATTEMPTS")) return "Demasiadas tentativas, espera um pouco";
    if (m.startsWith("USER_DISABLED")) return "Conta desativada";
    return m.length() ? m : String("Erro de rede");
}

static bool signIn(const String &email, const String &pass) {
    StaticJsonDocument<256> body;
    body["email"] = email;
    body["password"] = pass;
    body["returnSecureToken"] = true;
    String b; serializeJson(body, b);

    String resp;
    String url = String("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=") + API_KEY;
    int code = request("POST", url, b, "application/json", "", resp);
    if (code != 200) {
        gError = code < 0 ? String("Sem ligação à internet") : friendlyError(resp);
        Serial.printf("[FB] sign-in HTTP %d\n", code);
        return false;
    }
    // The response carries a ~1.5 kB JWT, so pull out the few fields we need.
    StaticJsonDocument<96> filter;
    filter["idToken"] = true; filter["refreshToken"] = true; filter["localId"] = true; filter["displayName"] = true;
    DynamicJsonDocument doc(2560);
    if (deserializeJson(doc, resp, DeserializationOption::Filter(filter))) { gError = "Resposta inválida"; return false; }
    gIdToken = String(doc["idToken"] | "");
    gRefresh = String(doc["refreshToken"] | "");
    gUid = String(doc["localId"] | "");
    gName = String(doc["displayName"] | "");
    gEmail = email;
    gTokenMs = millis();
    if (gIdToken.isEmpty() || gUid.isEmpty()) { gError = "Resposta inválida"; return false; }
    gAvatar = gColor = "";
    gProfileDocDone = gProfilePhotoDone = false;
    saveSession();
    gNeedProfile = true;
    gProfileTries = 0;
    return true;
}

static bool refreshToken() {
    if (gRefresh.isEmpty()) return false;
    String resp;
    String url = String("https://securetoken.googleapis.com/v1/token?key=") + API_KEY;
    int code = request("POST", url, "grant_type=refresh_token&refresh_token=" + gRefresh,
                       "application/x-www-form-urlencoded", "", resp);
    if (code != 200) { Serial.printf("[FB] refresh HTTP %d\n", code); return false; }
    String id = extractStr(resp, "id_token");
    String rt = extractStr(resp, "refresh_token");
    if (id.isEmpty()) return false;
    gIdToken = id;
    if (rt.length()) gRefresh = rt;
    gTokenMs = millis();
    saveSession();
    return true;
}

// ---- profile (name + avatar) --------------------------------------------------
// The user document can be large and the heap is small, so the response is never held whole:
// it streams through this scanner line by line and only the interesting values are kept.
static String unescapeJson(String v) {
    v.replace("\\u0026", "&");
    v.replace("\\/", "/");
    v.replace("\\\"", "\"");
    return v;
}

class ProfileScanner : public Stream {
public:
    String line, lastKey, avatar, avatarKey, name, keys;
    int cr = -1, cg = -1, cb = -1;
    String colorHex;
    size_t write(uint8_t c) override {
        if (c == '\n') { process(); line = ""; }
        else if (c != '\r' && line.length() < 700) line += (char)c;
        return 1;
    }
    size_t write(const uint8_t *b, size_t n) override { for (size_t i = 0; i < n; i++) write(b[i]); return n; }
    int available() override { return 0; }
    int read() override { return -1; }
    int peek() override { return -1; }
    void flush() override {}

private:
    void process() {
        String t = line; t.trim();
        if (t.endsWith("{") && t.startsWith("\"")) {            // "key": {
            int e = t.indexOf('"', 1);
            if (e > 1) { lastKey = t.substring(1, e); keys += lastKey + " "; }
            return;
        }
        if (t.startsWith("\"integerValue\"") || t.startsWith("\"doubleValue\"")) {
            int c0 = t.indexOf(':');
            String n = t.substring(c0 + 1); n.trim(); n.replace("\"", ""); n.replace(",", "");
            int val = (int)n.toFloat();
            if (lastKey == "color_r") cr = val; else if (lastKey == "color_g") cg = val; else if (lastKey == "color_b") cb = val;
            return;
        }
        if (!t.startsWith("\"stringValue\"")) return;
        int c = t.indexOf(':');
        if (c < 0) return;
        String v = t.substring(c + 1); v.trim();
        if (v.endsWith(",")) v.remove(v.length() - 1);
        if (v.length() < 2 || v[0] != '"' || v[v.length() - 1] != '"') return;
        v = v.substring(1, v.length() - 1);
        String kl = lastKey; kl.toLowerCase();
        if (kl == "displayname") name = unescapeJson(v);
        if (kl == "color" && v.length() == 7 && v[0] == '#') colorHex = v.substring(1);
        bool avatarish = kl.indexOf("avatar") >= 0 || kl.indexOf("photo") >= 0 || kl.indexOf("picture") >= 0 ||
                         kl.indexOf("image") >= 0 || kl.indexOf("pic") >= 0;
        if (v.startsWith("http") && avatarish && (avatar.isEmpty() || kl.indexOf("avatar") >= 0)) {
            avatar = unescapeJson(v);
            avatarKey = lastKey;
        }
    }
};

static bool fetchProfile() {
    // Phase 1: the Firestore user document (name, colour). The client lives in its own scope
    // so its TLS buffers are freed before phase 2 opens another connection: two at once
    // do not fit in this chip's heap.
    if (!gProfileDocDone) {
        ProfileScanner sc;
        int code;
        {
            WiFiClientSecure client;
            client.setCACertBundle(rootca_crt_bundle_start, rootca_crt_bundle_end - rootca_crt_bundle_start);
            HTTPClient http;
            http.setTimeout(10000);
            String url = String("https://firestore.googleapis.com/v1/projects/") + PROJECT + "/databases/(default)/documents/users/" + gUid;
            if (!http.begin(client, url)) return false;
            http.addHeader("Authorization", "Bearer " + gIdToken);
            code = http.GET();
            if (code == 200) http.writeToStream(&sc);
            http.end();
        }
        if (code != 200) { Serial.printf("[FB] profile HTTP %d\n", code); return false; }

        Serial.printf("[FB] user doc keys: %s\n", sc.keys.c_str());
        if (sc.name.length()) gName = sc.name;
        if (sc.cr >= 0 && sc.cg >= 0 && sc.cb >= 0) {
            char hex[8];
            snprintf(hex, sizeof hex, "%02X%02X%02X", constrain(sc.cr, 0, 255), constrain(sc.cg, 0, 255), constrain(sc.cb, 0, 255));
            gColor = hex;
        }
        gProfileDocDone = true;
        saveSession();
    }

    // Phase 2: userProfiles/{uid}. Tiger Studio Manager takes the avatar photo only from
    // `photoURL` here (a Firebase Storage URL) and the colour from `color`; it never uses
    // the Google-CDN photo from Firebase Auth, so neither do we.
    if (!gProfilePhotoDone) {
        ProfileScanner sc;
        int code;
        {
            WiFiClientSecure client;
            client.setCACertBundle(rootca_crt_bundle_start, rootca_crt_bundle_end - rootca_crt_bundle_start);
            HTTPClient http;
            http.setTimeout(10000);
            String url = String("https://firestore.googleapis.com/v1/projects/") + PROJECT + "/databases/(default)/documents/userProfiles/" + gUid +
                         "?mask.fieldPaths=photoURL&mask.fieldPaths=color&mask.fieldPaths=displayName";
            if (!http.begin(client, url)) return false;
            http.addHeader("Authorization", "Bearer " + gIdToken);
            code = http.GET();
            if (code == 200) http.writeToStream(&sc);
            http.end();
        }
        if (code == 404) {
            gAvatar = "";            // no public profile document: initials only
        } else if (code != 200) {
            Serial.printf("[FB] userProfiles HTTP %d\n", code);
            return false;
        } else {
            gAvatar = sc.avatar;
            if (sc.colorHex.length() == 6) gColor = sc.colorHex;
            if (sc.name.length() && gName.isEmpty()) gName = sc.name;
        }
        Serial.printf("[FB] avatar: %s\n", gAvatar.length() ? gAvatar.c_str() : "(none, initials on colour)");
        gProfilePhotoDone = true;
        saveSession();
    }
    return true;
}

// ---- inventory lookup (read-only) -------------------------------------------
// users/{uid}/inventory/{UID hex}: container_weight, measure_gr, rack{id,level,position,name}.
// Streamed line by line like the profile, so the document size does not matter.
class SpoolScanner : public Stream {
public:
    String line, lastKey, rackId, rackName, posLabel;
    long container = -1, level = -1, position = -1;
    size_t write(uint8_t c) override {
        if (c == '\n') { process(); line = ""; }
        else if (c != '\r' && line.length() < 500) line += (char)c;
        return 1;
    }
    size_t write(const uint8_t *b, size_t n) override { for (size_t i = 0; i < n; i++) write(b[i]); return n; }
    int available() override { return 0; }
    int read() override { return -1; }
    int peek() override { return -1; }
    void flush() override {}

private:
    static bool isNameKey(const String &k) {
        return k == "name" || k == "label" || k == "display_name" || k == "title" || k == "rack_name";
    }
    void process() {
        String t = line; t.trim();
        if (t.endsWith("{") && t.startsWith("\"")) {
            int e = t.indexOf('"', 1);
            if (e > 1) lastKey = t.substring(1, e);
            return;
        }
        int c = t.indexOf(':');
        if (c < 0) return;
        String v = t.substring(c + 1); v.trim();
        if (v.endsWith(",")) v.remove(v.length() - 1);
        if (v.length() >= 2 && v[0] == '"') v = v.substring(1, v.length() - 1);
        if (t.startsWith("\"integerValue\"") || t.startsWith("\"doubleValue\"")) {
            long n = lroundf(v.toFloat());
            if (lastKey == "container_weight") container = n;
            else if (lastKey == "level") level = n;
            else if (lastKey == "position") position = n;
        } else if (t.startsWith("\"stringValue\"")) {
            if (lastKey == "id") rackId = v;
            else if (isNameKey(lastKey) && rackName.isEmpty()) rackName = v;
            else if (lastKey == "position_label" || lastKey == "positionLabel") posLabel = v;
        }
    }
};

static bool streamGet(const String &url, Stream &sink, int &code) {
    WiFiClientSecure client;
    client.setCACertBundle(rootca_crt_bundle_start, rootca_crt_bundle_end - rootca_crt_bundle_start);
    HTTPClient http;
    http.setTimeout(10000);
    if (!http.begin(client, url)) return false;
    http.addHeader("Authorization", "Bearer " + gIdToken);
    code = http.GET();
    if (code == 200) http.writeToStream(&sink);
    http.end();
    return true;   // the client is destroyed on return: one TLS session alive at a time
}

static void fetchSpool(const String &uidHex) {
    String base = String("https://firestore.googleapis.com/v1/projects/") + PROJECT + "/databases/(default)/documents/users/" + gUid;
    SpoolScanner sc;
    int code = -1;
    if (!streamGet(base + "/inventory/" + uidHex +
                   "?mask.fieldPaths=container_weight&mask.fieldPaths=rack", sc, code) || code != 200) {
        Serial.printf("[FB] inventory %s -> HTTP %d (not in the inventory or unreachable)\n", uidHex.c_str(), code);
        FbSpool none;
        xSemaphoreTake(gLock, portMAX_DELAY); gSpool = none; xSemaphoreGive(gLock);
        return;
    }
    String rackName = sc.rackName;
    if (rackName.isEmpty() && sc.rackId.length()) {   // the name lives on the rack document
        SpoolScanner rs;
        int rc = -1;
        if (streamGet(base + "/racks/" + sc.rackId, rs, rc) && rc == 200) rackName = rs.rackName;
    }
    String pos = sc.posLabel;
    if (pos.isEmpty() && sc.level >= 0 && sc.position >= 0) {
        pos = String((char)('A' + (sc.level % 26))) + String(sc.position + 1);   // level 0 = A, position 0 = 1
    }
    FbSpool s;
    s.container = (int)sc.container;
    s.rackName = rackName;
    s.rackPos = pos;
    Serial.printf("[FB] inventory %s: container=%d g rack='%s' pos='%s'\n", uidHex.c_str(), s.container, rackName.c_str(), pos.c_str());
    xSemaphoreTake(gLock, portMAX_DELAY); gSpool = s; xSemaphoreGive(gLock);
}

static void jsonString(JsonObject f, const char *k, const String &v) {
    if (v.length()) f[k]["stringValue"] = v; else f[k]["nullValue"] = "NULL_VALUE";
}

static bool sendHeartbeat(bool full) {
    Serial.printf("[FB] heap free=%u largest=%u\n", (unsigned)ESP.getFreeHeap(), (unsigned)ESP.getMaxAllocHeap());
    FbSnapshot s;
    xSemaphoreTake(gLock, portMAX_DELAY);
    s = gSnap;
    xSemaphoreGive(gLock);

    String docPath = "users/" + gUid + "/scales/" + gMac;
    String base = String("https://firestore.googleapis.com/v1/projects/") + PROJECT + "/databases/(default)/documents";

    // First beat of the session: only name the scale if it has no document yet,
    // so a name the owner chose in the cloud is never overwritten.
    bool nameIt = false;
    if (full) {
        String r;
        nameIt = request("GET", base + "/" + docPath + "?mask.fieldPaths=display_name", "", nullptr, gIdToken, r) == 404;
    }

    // The JSON document is scoped so its memory is back before the TLS handshake, which
    // needs every contiguous block it can get on this chip.
    String payload;
    {
    DynamicJsonDocument doc(2048);
    JsonObject w = doc.createNestedArray("writes").createNestedObject();
    JsonObject upd = w.createNestedObject("update");
    upd["name"] = String("projects/") + PROJECT + "/databases/(default)/documents/" + docPath;
    JsonObject f = upd.createNestedObject("fields");
    JsonArray mask = w.createNestedObject("updateMask").createNestedArray("fieldPaths");
    auto m = [&](const char *k) { mask.add(k); };

    f["current_weight_g"]["doubleValue"] = s.weight;           m("current_weight_g");
    jsonString(f, "current_spool_uid_1", s.uid);               m("current_spool_uid_1");
    f["calibration_factor"]["doubleValue"] = s.cal;            m("calibration_factor");
    if (s.rssi) f["wifi_signal_dbm"]["integerValue"] = String(s.rssi); else f["wifi_signal_dbm"]["nullValue"] = "NULL_VALUE";
    m("wifi_signal_dbm");
    f["ip_address"]["stringValue"] = s.ip;                     m("ip_address");
    f["battery_present"]["booleanValue"] = false;              m("battery_present");
    f["battery_percent"]["nullValue"] = "NULL_VALUE";          m("battery_percent");
    f["is_charging"]["nullValue"] = "NULL_VALUE";              m("is_charging");
    f["power_source"]["stringValue"] = "usb";                  m("power_source");
    f["power_state"]["stringValue"] = "active";                m("power_state");
    if (full) {
        f["fw_version"]["stringValue"] = s.fw;                 m("fw_version");
        f["mdns_hostname"]["stringValue"] = s.mdns;            m("mdns_hostname");
        f["mac"]["stringValue"] = gMac;                        m("mac");
        if (nameIt) {
            String suffix = gMac.substring(8); suffix.toUpperCase();
            f["display_name"]["stringValue"] = "FilScale-" + suffix;   m("display_name");
        }
    }
    JsonObject tr = w.createNestedArray("updateTransforms").createNestedObject();
    tr["fieldPath"] = "last_heartbeat_at";
    tr["setToServerValue"] = "REQUEST_TIME";

    serializeJson(doc, payload);
    }
    String resp;
    int code = request("POST", base + ":commit", payload, "application/json", gIdToken, resp);
    if (code >= 200 && code < 300) return true;
    Serial.printf("[FB] heartbeat HTTP %d: %.120s\n", code, resp.c_str());
    return false;
}

static void fbTask(void *) {
    uint32_t lastBeat = 0;
    bool needFull = true;
    String lastUid;
    String spoolUid;   // tag the inventory info belongs to

    for (;;) {
        vTaskDelay(pdMS_TO_TICKS(400));

        if (gPendLogout) {
            gPendLogout = false;
            clearSession();
            gState = FB_SIGNED_OUT;
            gError = "";
            needFull = true;
        }

        if (WiFi.status() != WL_CONNECTED) continue;

        if (gPendLogin) {
            gPendLogin = false;
            gState = FB_BUSY;
            gError = "";
            if (!ensureTime()) { gError = "Sem hora da internet"; gState = FB_ERROR; }
            else if (signIn(gPendEmail, gPendPass)) { gState = FB_SIGNED_IN; needFull = true; lastBeat = 0; }
            else gState = FB_ERROR;
            gPendPass = "";
            continue;
        }

        if (gRefresh.isEmpty()) continue;   // nothing to do while signed out

        if (!ensureTime()) continue;

        // Restore a saved session after boot, and renew the token before it expires.
        if (gIdToken.isEmpty() || millis() - gTokenMs > TOKEN_LIFETIME_MS) {
            if (refreshToken()) { if (gState != FB_SIGNED_IN) gState = FB_SIGNED_IN; }
            else { gState = FB_ERROR; gError = "Sessão expirada, entra de novo"; vTaskDelay(pdMS_TO_TICKS(10000)); continue; }
        } else if (gState != FB_SIGNED_IN && gState != FB_BUSY) {
            gState = FB_SIGNED_IN;
        }

        if (gNeedProfile) {
            if (fetchProfile() || ++gProfileTries >= 3) gNeedProfile = false;
        }

        // Heartbeat every 30 s, or at once when the tag changes.
        String curUid;
        xSemaphoreTake(gLock, portMAX_DELAY);
        curUid = gSnap.uid;
        xSemaphoreGive(gLock);
        if (curUid != spoolUid) {
            spoolUid = curUid;
            if (curUid.isEmpty()) {
                FbSpool none;
                xSemaphoreTake(gLock, portMAX_DELAY); gSpool = none; xSemaphoreGive(gLock);
            } else {
                fetchSpool(curUid);
            }
        }
        bool changed = curUid != lastUid;
        if (lastBeat == 0 || changed || millis() - lastBeat >= HEARTBEAT_MS) {
            if (sendHeartbeat(needFull)) {
                needFull = false;
                lastUid = curUid;
                lastBeat = millis();
                if (gState == FB_ERROR) gState = FB_SIGNED_IN;
            } else {
                lastBeat = millis();   // retry on the normal cadence, do not hammer
            }
        }
    }
}

// ---- public API ----------------------------------------------------------
void fbBegin(const String &mac) {
    gLock = xSemaphoreCreateMutex();
    gMac = mac;
    Preferences p;
    p.begin("fb", true);
    gRefresh = p.getString("refresh", "");
    gUid = p.getString("uid", "");
    gEmail = p.getString("email", "");
    gName = p.getString("name", "");
    gColor = p.getString("color", "");
    p.end();
    gNeedProfile = gRefresh.length() > 0;
    gState = gRefresh.length() ? FB_BUSY : FB_SIGNED_OUT;   // BUSY until the first refresh succeeds
    xTaskCreatePinnedToCore(fbTask, "fb", 10240, nullptr, 1, nullptr, 0);
}

void fbPublish(const FbSnapshot &s) {
    if (!gLock) return;
    xSemaphoreTake(gLock, portMAX_DELAY);
    gSnap = s;
    xSemaphoreGive(gLock);
}

void fbLogin(const String &email, const String &password) {
    if (gState == FB_BUSY && gPendLogin) return;
    gPendEmail = email;
    gPendPass = password;
    gState = FB_BUSY;
    gPendLogin = true;
}

void fbLogout() { gPendLogout = true; }

int fbState() { return gState; }
String fbEmail() { return gEmail; }
String fbDisplayName() { return gName; }
String fbErrorText() { return gError; }
FbSpool fbSpool() {
    FbSpool s;
    xSemaphoreTake(gLock, portMAX_DELAY); s = gSpool; xSemaphoreGive(gLock);
    return s;
}
String fbAvatarColor() { return gState == FB_SIGNED_IN ? gColor : String(""); }
String fbAvatarUrl() { return gState == FB_SIGNED_IN ? gAvatar : String(""); }
