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
    // weigh workflow, written to the scale document like the original does
    String wfPhase, sendPhase;
    uint32_t sessionId = 0, sessions = 0, sendOk = 0, sendFail = 0;
    uint32_t rfidOk = 0, rfidFail = 0, autoTare = 0, resets = 0;
    String lastUid1, lastUid2, lastStatus;
    float  lastWeight = 0;
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

/** What the signed-in account's inventory says about the spool on the platform (read-only). */
struct FbSpool {
    int    container = -1;   // empty-spool weight in grams, -1 when unknown (tag not in the inventory)
    String rackName;         // empty when the spool is not placed in a rack
    String rackPos;          // e.g. "A3": level letter + position
    bool   fetched = false;  // the lookup for the current tag has finished (found or not)
    String twin;             // the spool's other tag, empty when none
    String imageUrl;         // the spool's photo in the inventory (https URL), empty when it has none
};
FbSpool fbSpool();

/**
 * Writes the net weight to the spool's inventory document (and its twin's), as the original does:
 * weight_available + last_update, three attempts. Returns an id for fbSendStatus().
 */
uint32_t fbSendWeight(const String &uid, const String &twin, int netGrams);

/** 1 in flight, 2 done, 3 failed (also for an id that was superseded). */
int fbSendStatus(uint32_t id);

/**
 * Remote commands from Tiger Studio Manager (users/{uid}/scales/{mac}/commands). The handler runs on
 * the cloud task: it must only set flags. `ok` false marks the command as failed; the returned
 * text is the message shown in Studio.
 */
typedef String (*FbCommandHandler)(const String &type, float value, bool &ok);
void fbSetCommandHandler(FbCommandHandler h);
void fbForceBeat();

/**
 * Stops (on) or resumes (off) all cloud traffic. Pausing waits up to timeoutMs for a request in flight
 * to finish and returns false if it did not. Used around over-the-air updates.
 */
bool fbPause(bool on, uint32_t timeoutMs = 0);

/** Account colour as RRGGBB (from the user's profile), empty when signed out. */
String fbAvatarColor();
