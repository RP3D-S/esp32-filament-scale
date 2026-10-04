// Firebase (TigerTag cloud) client: email/password sign-in, token refresh and the
// Firestore heartbeat at users/{uid}/scales/{mac}. Runs in its own task because TLS
// handshakes block for seconds and need a large stack.
#pragma once
#include <Arduino.h>

struct FbSnapshot {
    int    weight = 0;
    String uid;
    float  cal = 0;
    int    rssi = 0;
    String ip;
    String mdns;
    String fw;
    bool   readerOk = false;
    bool   scaleOk = false;
    String status;
};

enum FbState { FB_SIGNED_OUT = 0, FB_BUSY = 1, FB_SIGNED_IN = 2, FB_ERROR = 3 };

/** Loads the saved session from NVS and starts the task. `mac` is 12 lowercase hex chars. */
void fbBegin(const String &mac);

/** Called from loop() about once a second with the current readings. */
void fbPublish(const FbSnapshot &s);

/** Asynchronous; follow progress with fbState(). The password is never stored. */
void fbLogin(const String &email, const String &password);
void fbLogout();

int    fbState();
String fbEmail();
String fbDisplayName();
String fbErrorText();

/** URL of the signed-in account's avatar, empty when signed out or the account has none. */
String fbAvatarUrl();

/** Account colour as RRGGBB (from the user's profile), empty when signed out. */
String fbAvatarColor();
