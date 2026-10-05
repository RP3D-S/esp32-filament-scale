#include "ota.h"
#include <AsyncJson.h>
#include <ArduinoJson.h>
#include <Update.h>
#include <esp_ota_ops.h>
#include <mbedtls/sha256.h>

static const uint32_t ARM_TIMEOUT_MS   = 120000;   // an armed update nobody uploads is cancelled
static const uint32_t ERROR_SHOW_MS    = 10000;    // keep the error visible to the app, then resume the cloud
static const uint32_t NONCE_TIMEOUT_MS = 60000;
static const uint32_t MIN_IMAGE_BYTES  = 100000;   // anything smaller is not a firmware image

static OtaHooks gHooks;
static String   gDevPassword;

static volatile int gPhase = OTAP_IDLE;
static volatile int gPct = 0;
static volatile bool gPendPause = false;           // armed from another task: loop() does the pausing
static char     gToken[17] = "";
static char     gMd5[33] = "";
static uint32_t gSize = 0;
static uint32_t gPhaseAt = 0;                      // millis() when the phase last changed
static bool     gRestartScheduled = false;

static String   gNonce;
static uint32_t gNonceAt = 0;

static void setPhase(int p) { gPhase = p; gPhaseAt = millis(); }

static String randomHex(int bytes) {
    String s;
    for (int i = 0; i < bytes; i++) { char b[3]; snprintf(b, sizeof b, "%02x", (unsigned)(esp_random() & 0xFF)); s += b; }
    return s;
}

static String sha256Hex(const String &in) {
    unsigned char out[32];
    mbedtls_sha256_context c;
    mbedtls_sha256_init(&c);
    mbedtls_sha256_starts(&c, 0);
    mbedtls_sha256_update(&c, (const unsigned char *)in.c_str(), in.length());
    mbedtls_sha256_finish(&c, out);
    mbedtls_sha256_free(&c);
    String s;
    for (int i = 0; i < 32; i++) { char b[3]; snprintf(b, sizeof b, "%02x", out[i]); s += b; }
    return s;
}

static bool validMd5(const char *s) {
    if (!s || strlen(s) != 32) return false;
    for (int i = 0; i < 32; i++) if (!isxdigit((unsigned char)s[i])) return false;
    return true;
}

bool otaArm(uint32_t size, const char *md5hex, String &token, const char *&err) {
    const esp_partition_t *next = esp_ota_get_next_update_partition(nullptr);
    if (gPhase == OTAP_RECEIVING) { err = "busy"; return false; }
    if (!next) { err = "no spare slot"; return false; }
    if (size < MIN_IMAGE_BYTES || size > next->size) { err = "bad size"; return false; }
    if (!validMd5(md5hex)) { err = "bad md5"; return false; }

    gSize = size;
    strlcpy(gMd5, md5hex, sizeof gMd5);
    token = randomHex(8);
    strlcpy(gToken, token.c_str(), sizeof gToken);
    gPct = 0;
    // The pause (cloud task, WebSocket clients) can block for seconds, so it happens in loop(), not here.
    setPhase(OTAP_IDLE);
    gPendPause = true;
    return true;
}

void otaLoop() {
    uint32_t now = millis();
    if (gPendPause) {
        gPendPause = false;
        if (gHooks.pause) gHooks.pause();
        setPhase(OTAP_ARMED);
        Serial.printf("[OTA] armed: %u bytes, md5 %s\n", (unsigned)gSize, gMd5);
        now = millis();   // the pause above can take seconds; an older now would wrap the unsigned subtractions below
    }
    if (gPhase == OTAP_ARMED && now - gPhaseAt > ARM_TIMEOUT_MS) {
        Serial.println("[OTA] arm expired");
        gToken[0] = 0;
        if (gHooks.resume) gHooks.resume();
        setPhase(OTAP_IDLE);
    }
    if (gPhase == OTAP_ERROR && now - gPhaseAt > ERROR_SHOW_MS) {
        gToken[0] = 0;
        if (gHooks.resume) gHooks.resume();
        setPhase(OTAP_IDLE);
    }
    if (gPhase == OTAP_DONE && !gRestartScheduled) {
        gRestartScheduled = true;
        Serial.println("[OTA] image written and verified, restarting");
        if (gHooks.restart) gHooks.restart();
    }
}

int otaPhase() { return gPhase; }
int otaPercent() { return gPct; }

static bool tokenMatches(const String &t) {
    return gPhase == OTAP_ARMED && gToken[0] && t.length() == 16 && t.equals(gToken);
}

void otaInit(AsyncWebServer &server, const OtaHooks &hooks, const char *devPassword) {
    gHooks = hooks;
    gDevPassword = devPassword ? devPassword : "";

    server.on("/api/ota/status", HTTP_GET, [](AsyncWebServerRequest *r) {
        char b[48]; snprintf(b, sizeof b, "{\"phase\":%d,\"pct\":%d}", (int)gPhase, (int)gPct);
        r->send(200, "application/json", b);
    });

    // Dev arming: the PC proves it knows the password without sending it (SHA-256 of nonce + password).
    server.on("/api/ota/nonce", HTTP_GET, [](AsyncWebServerRequest *r) {
        if (gDevPassword.isEmpty()) { r->send(404); return; }
        gNonce = randomHex(8); gNonceAt = millis();
        r->send(200, "application/json", String("{\"nonce\":\"") + gNonce + "\"}");
    });
    auto *arm = new AsyncCallbackJsonWebHandler("/api/ota/arm", [](AsyncWebServerRequest *r, JsonVariant &j) {
        String auth = j["auth"] | "";
        bool ok = !gDevPassword.isEmpty() && gNonce.length() && millis() - gNonceAt < NONCE_TIMEOUT_MS
                  && auth.equalsIgnoreCase(sha256Hex(gNonce + gDevPassword));
        gNonce = "";                                    // single use, right or wrong
        if (!ok) { r->send(403, "application/json", "{\"ok\":false,\"err\":\"auth\"}"); return; }
        String token; const char *err = "";
        if (!otaArm(j["size"] | 0u, j["md5"] | "", token, err)) {
            r->send(400, "application/json", String("{\"ok\":false,\"err\":\"") + err + "\"}"); return;
        }
        r->send(200, "application/json", String("{\"ok\":true,\"token\":\"") + token + "\"}");
    });
    server.addHandler(arm);

    // The upload itself. The filter keeps it invisible (404) unless an update is armed and the token and
    // length match, so a stray POST never reaches the flash.
    server.on("/api/ota", HTTP_POST,
        [](AsyncWebServerRequest *r) {                  // after the whole body
            if (gPhase == OTAP_DONE) r->send(200, "application/json", "{\"ok\":true}");
            else r->send(500, "application/json", "{\"ok\":false}");
        },
        nullptr,
        [](AsyncWebServerRequest *r, uint8_t *data, size_t len, size_t index, size_t total) {
            if (index == 0) {
                if (gPhase != OTAP_ARMED || total != gSize || !Update.begin(total, U_FLASH)) {
                    Serial.printf("[OTA] cannot start: phase=%d total=%u armed=%u\n", (int)gPhase, (unsigned)total, (unsigned)gSize);
                    setPhase(OTAP_ERROR);
                    return;
                }
                Update.setMD5(gMd5);
                gToken[0] = 0;                          // one-time
                setPhase(OTAP_RECEIVING);
                Serial.printf("[OTA] receiving %u bytes\n", (unsigned)total);
            }
            if (gPhase != OTAP_RECEIVING) return;
            if (Update.write(data, len) != len) {
                Serial.printf("[OTA] write failed: %s\n", Update.errorString());
                Update.abort();
                setPhase(OTAP_ERROR);
                return;
            }
            gPct = (int)(((uint64_t)(index + len) * 100) / total);
            if (index + len == total) {
                if (Update.end(true)) { gPct = 100; setPhase(OTAP_DONE); }
                else { Serial.printf("[OTA] image rejected: %s\n", Update.errorString()); setPhase(OTAP_ERROR); }
            }
        }
    ).setFilter([](AsyncWebServerRequest *r) {
        return r->hasHeader("X-OTA-Token") && tokenMatches(r->header("X-OTA-Token")) && r->contentLength() == gSize;
    });
}
