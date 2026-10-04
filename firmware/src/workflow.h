// Weigh workflow, ported from the original TigerScale firmware (section 21):
//   IDLE -> SCANNING -> STABLE_WAIT -> SENDING -> DONE
// The thresholds and the order of the checks are the original's. What differs: one NFC
// reader and no motor, so the scan waits for the tag instead of turning the spool.
#pragma once
#include <Arduino.h>

struct WfInputs {
    float  w = 0;               // filtered weight, grams
    String tagUid;              // tag currently seen by the reader (may flicker)
    bool   signedIn = false;    // the scale only starts a session with a cloud account
    bool   wifiUp = false;
    bool   spoolFetched = false;   // inventory lookup for the latched tag has finished
    int    container = -1;      // empty-spool weight in g, -1 unknown
    String twin;                // twin tag UID from the inventory, empty when none
};

struct WfOutputs {
    bool tare = false;          // negative drift: ask the scale to tare
};

struct WfStats {
    uint32_t sessionId = 0, sessions = 0, sendOk = 0, sendFail = 0;
    uint32_t rfidOk = 0, rfidFail = 0, autoTare = 0, resets = 0;
};

struct WfLast {                 // last measurement attempt, for the heartbeat
    String uid1, uid2, status;
    float  weight = 0;
};

void wfUpdate(const WfInputs &in, WfOutputs &out);

/** Tag UID latched for the session (the original's lastUID): stays until the spool is removed. */
String wfUid();

/** For the phone: idle, scanning, sending, success, error, no_tag, cancelled, weigh_error, remove. */
const char *wfStatus();

/** The original's strings for the cloud: workflow_phase and send_phase. */
const char *wfPhaseName();
const char *wfSendPhase();

/** Remote "workflow_stop": drop the session and go back to idle. */
void wfStop();

WfStats wfStats();
WfLast  wfLast();
