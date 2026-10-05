// Over-the-air update, pushed to the scale over local Wi-Fi as one streamed HTTP POST.
//
// Why a streamed POST and not ArduinoOTA/espota: espota sends 1 KB and waits for the answer, so its speed is
// 1 KB / round-trip time (3.6 KB/s when the radio is in power save, 25 KB/s when awake). A TCP stream is not
// bound by the round trip. No TLS is involved, which matters because the heap cannot afford it.
//
// Flow:  1. the client arms an update (size + MD5) and gets a one-time token: over the encrypted BLE link
//           (the app) or over HTTP with a password challenge (a dev PC, scripts/ota_push.py);
//        2. loop() pauses the cloud and drops the WebSocket clients to free heap and TCP buffers;
//        3. the client POSTs the image to /api/ota with the token; it is written to the other app slot and
//           checked against the MD5; only then does the boot slot switch;
//        4. the scale restarts. A failed or abandoned update leaves the running firmware untouched.
#pragma once
#include <Arduino.h>
#include <ESPAsyncWebServer.h>

enum OtaPhase { OTAP_IDLE = 0, OTAP_ARMED = 1, OTAP_RECEIVING = 2, OTAP_DONE = 3, OTAP_ERROR = 4 };

struct OtaHooks {
    void (*pause)();     // free the heap: pause the cloud, close WebSocket clients (runs from loop())
    void (*resume)();    // undo pause()
    void (*restart)();   // schedule the restart after a good update
};

/** Registers /api/ota/{nonce,arm,status} and POST /api/ota. `devPassword` empty disables the HTTP arming. */
void otaInit(AsyncWebServer &server, const OtaHooks &hooks, const char *devPassword);

/** Called every loop() pass: runs the pause, expires an abandoned arm, restarts after a good update. */
void otaLoop();

/**
 * Arms an update for an image of `size` bytes with MD5 `md5hex`. Returns false (and says why in `err`) if it
 * cannot start. On success `token` is the 16-hex-char one-time token the upload must carry.
 */
bool otaArm(uint32_t size, const char *md5hex, String &token, const char *&err);

int  otaPhase();
int  otaPercent();
