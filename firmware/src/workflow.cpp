#include "workflow.h"

#include <math.h>

#include "firebase.h"

// ---- thresholds: the original's values -------------------------------------------------
static const float    SLOPE_REMOVAL_G_PER_S        = -25.0f;   // slope below this: spool being lifted
static const float    SLOPE_STABLE_G_PER_S         = 5.0f;     // |slope| below this: steady
static const float    STABLE_EPSILON_G             = 0.8f;
static const uint32_t STABLE_WINDOW_MS             = 1200;
static const uint32_t STABLE_WAIT_TIMEOUT_MS       = 15000;
static const float    MIN_WEIGHT_TO_SEND_G         = 5.0f;
static const float    SCAN_START_WEIGHT_G          = 20.0f;    // reject startup / EMI spikes
static const uint32_t SCAN_START_HOLD_MS           = 600;
static const float    SPOOL_REMOVED_WEIGHT_G       = 50.0f;
static const uint32_t SPOOL_REMOVAL_DEBOUNCE_MS    = 400;
static const uint32_t SPOOL_SETTLING_GUARD_MS      = 1500;
static const float    SESSION_RESET_WEIGHT_RATIO   = 0.70f;
static const float    SCANNING_REMOVAL_MIN_DROP_G  = 150.0f;
static const uint32_t SCAN_DURATION_MS             = 8000;     // original NO_MOTOR_UID_WAIT_MS
static const uint32_t NO_UID_RESCAN_COOLDOWN_MS    = 2500;
static const uint32_t SEND_TIMEOUT_MS              = 30000;
static const uint32_t NEG_DRIFT_TARE_DELAY_MS      = 1000;
static const uint32_t NEG_DRIFT_TARE_COOLDOWN_MS   = 10000;
static const uint32_t SLOPE_SAMPLE_MS              = 400;
static const int      SLOPE_BUF_SIZE               = 6;        // 6 x 400 ms = 2.4 s window
static const uint32_t BANNER_MS                    = 2000;

// ---- state ------------------------------------------------------------------------------
enum Phase { P_IDLE, P_SCANNING, P_STABLE_WAIT, P_SENDING, P_DONE };
static Phase phase = P_IDLE;
static const char *sendPhase = "idle";      // idle scanning stabilizing send success error ready done
static uint32_t sendPhaseChangedMs = 0;

static String latched;                      // the original's lastUID
static WfStats stats;
static WfLast last;

static uint32_t scanStartMs = 0, stableWaitStartMs = 0, sendStartMs = 0;
static uint32_t presentSinceMs = 0, rescanBlockedUntilMs = 0;
static float    peak = 0;
static uint32_t belowThreshMs = 0;
static bool     readyWasZero = false;

static float    stableCandidate = NAN;
static uint32_t stableSinceMs = 0;

static float    slopeBuf[SLOPE_BUF_SIZE];
static uint32_t slopeBufMs[SLOPE_BUF_SIZE];
static int      slopeIdx = 0;
static bool     slopeFull = false;
static float    slope = 0;

static uint32_t sendId = 0;
static bool     sendStarted = false;
static float    sendNet = 0;

static uint32_t negSinceMs = 0, lastNegTareMs = 0;

static const char *transient = "";          // banner shown for BANNER_MS: success error no_tag cancelled weigh_error
static uint32_t transientUntilMs = 0;

static void showBanner(const char *what, uint32_t now) {
    transient = what;
    transientUntilMs = now + BANNER_MS;
}

static void resetSlope() {
    for (int i = 0; i < SLOPE_BUF_SIZE; i++) { slopeBuf[i] = 0; slopeBufMs[i] = 0; }
    slopeIdx = 0; slopeFull = false; slope = 0;
}

static void updateSlope(float w, uint32_t now) {
    int head = slopeIdx % SLOPE_BUF_SIZE;
    if (slopeBufMs[head] == 0 || now - slopeBufMs[head] >= SLOPE_SAMPLE_MS) {
        slopeBuf[head] = w;
        slopeBufMs[head] = now;
        slopeIdx++;
        if (slopeIdx >= SLOPE_BUF_SIZE) slopeFull = true;
    }
    if (!slopeFull) { slope = 0; return; }
    int oldI = slopeIdx % SLOPE_BUF_SIZE;
    int newI = (slopeIdx - 1 + SLOPE_BUF_SIZE) % SLOPE_BUF_SIZE;
    uint32_t dt = slopeBufMs[newI] - slopeBufMs[oldI];
    slope = dt == 0 ? 0 : (slopeBuf[newI] - slopeBuf[oldI]) / (dt / 1000.0f);
}

// Advances the settle window and says whether the weight is steady right now. An unfilled
// slope buffer reads 0 ("perfectly steady"), so it must not count as stable.
static bool updateStableWindow(float w, uint32_t now) {
    bool steady = slopeFull && fabsf(slope) < SLOPE_STABLE_G_PER_S;
    if (!steady || isnan(stableCandidate) || fabsf(w - stableCandidate) > STABLE_EPSILON_G) {
        stableCandidate = w;
        stableSinceMs = now;
    }
    return steady;
}

static void setSendPhase(const char *p, uint32_t now) { sendPhase = p; sendPhaseChangedMs = now; }

static void clearSession() {
    latched = "";
    stableCandidate = NAN; stableSinceMs = 0;
    peak = 0; belowThreshMs = 0;
    sendStarted = false;
    resetSlope();
}

// ---- the state machine --------------------------------------------------------------------
void wfUpdate(const WfInputs &in, WfOutputs &out) {
    const uint32_t now = millis();
    const float w = in.w;

    updateSlope(w, now);
    const bool removingNow = slope < SLOPE_REMOVAL_G_PER_S;

    // Negative drift: weight below zero for a second means the zero has wandered. Tare.
    if (w < 0) {
        if (!negSinceMs) negSinceMs = now;
        if (now - negSinceMs >= NEG_DRIFT_TARE_DELAY_MS && now - lastNegTareMs >= NEG_DRIFT_TARE_COOLDOWN_MS) {
            out.tare = true;
            lastNegTareMs = now;
            negSinceMs = 0;
            stats.autoTare++;
        }
    } else {
        negSinceMs = 0;
    }

    // Latch the first tag read. Nothing is latched while the platform is empty and idle:
    // a tag waved in the air is not a spool.
    if (latched.isEmpty() && in.tagUid.length() && (phase != P_IDLE || w >= MIN_WEIGHT_TO_SEND_G)) {
        latched = in.tagUid;
        stats.rfidOk++;
    }

    // success / error banners decay into "done" (kept until the spool is removed)
    if ((!strcmp(sendPhase, "success") || !strcmp(sendPhase, "error")) && now - sendPhaseChangedMs > BANNER_MS) {
        sendPhase = phase == P_DONE ? "done" : "idle";
    }

    // ---- IDLE ----
    if (phase == P_IDLE) {
        if (latched.length() && w < MIN_WEIGHT_TO_SEND_G) latched = "";   // stale: spool already gone

        if (w >= SCAN_START_WEIGHT_G) { if (!presentSinceMs) presentSinceMs = now; }
        else presentSinceMs = 0;

        // After a finished session, the scale must be seen empty before a new one may start;
        // this keeps the descent of a removed spool from opening a ghost session.
        if ((!strcmp(sendPhase, "ready") || !strcmp(sendPhase, "done")) && w < MIN_WEIGHT_TO_SEND_G) readyWasZero = true;

        bool confirmed = presentSinceMs && now - presentSinceMs >= SCAN_START_HOLD_MS;
        if (w >= SCAN_START_WEIGHT_G && confirmed && now >= rescanBlockedUntilMs &&
            in.signedIn && in.wifiUp && !removingNow &&
            ((strcmp(sendPhase, "ready") && strcmp(sendPhase, "done")) || readyWasZero)) {
            phase = P_SCANNING;
            scanStartMs = now;
            presentSinceMs = 0;
            stableCandidate = NAN; stableSinceMs = 0;
            peak = 0; belowThreshMs = 0;
            sendStarted = false;
            stats.sessionId = ++stats.sessions;
            setSendPhase("scanning", now);
            readyWasZero = false;
            transient = "";
            Serial.printf("[WF] IDLE -> SCANNING (w=%.1f)\n", w);
        }
        return;
    }

    // ---- spool removal (SCANNING / STABLE_WAIT / SENDING) ----
    // Three layers: near zero; a fast, large drop after the settling guard; or below 50 g
    // for 400 ms after having peaked above it.
    if (phase != P_DONE) {
        if (w > peak) peak = w;
        bool peaked = peak >= SPOOL_REMOVED_WEIGHT_G;
        if (peaked && w < SPOOL_REMOVED_WEIGHT_G) { if (!belowThreshMs) belowThreshMs = now; }
        else belowThreshMs = 0;

        bool settling = now - scanStartMs < SPOOL_SETTLING_GUARD_MS;
        float drop = peak - w;
        bool strongDrop = drop >= SCANNING_REMOVAL_MIN_DROP_G;
        bool ratioOk = peak > 0 && w <= peak * SESSION_RESET_WEIGHT_RATIO;
        bool removed = w < MIN_WEIGHT_TO_SEND_G ||
                       (!settling && peaked && strongDrop && ratioOk && removingNow) ||
                       (belowThreshMs && now - belowThreshMs >= SPOOL_REMOVAL_DEBOUNCE_MS);
        if (removed) {
            bool hadUid = latched.length() > 0;
            Serial.printf("[WF] spool removed (w=%.1f peak=%.1f) -> IDLE\n", w, peak);
            phase = P_IDLE;
            setSendPhase("idle", now);
            clearSession();
            if (hadUid) showBanner("cancelled", now);    // an anonymous object lifted mid-scan just goes back to ready
            stats.resets++;
            return;
        }
    }

    // ---- SCANNING: wait for the tag ----
    if (phase == P_SCANNING) {
        updateStableWindow(w, now);   // keep the settle window warm, it is already earned by the time we need it

        // A spool carries two tags. With one reader we get one; the inventory names the other,
        // so a known twin plus a finished lookup is as good as reading both.
        bool twinReady = latched.length() && in.spoolFetched && in.twin.length();
        if (twinReady || now - scanStartMs >= SCAN_DURATION_MS) {
            if (latched.isEmpty()) {
                phase = P_IDLE;
                setSendPhase("idle", now);
                rescanBlockedUntilMs = now + NO_UID_RESCAN_COOLDOWN_MS;
                if (w >= MIN_WEIGHT_TO_SEND_G) showBanner("no_tag", now);
                Serial.println("[WF] SCANNING -> IDLE (no tag)");
            } else {
                phase = P_STABLE_WAIT;
                stableWaitStartMs = now;
                setSendPhase("stabilizing", now);
                Serial.printf("[WF] SCANNING -> STABLE_WAIT uid=%s (%s)\n", latched.c_str(), twinReady ? "twin from inventory" : "timeout");
            }
        }
        return;
    }

    // ---- STABLE_WAIT: weight steady for the window ----
    if (phase == P_STABLE_WAIT) {
        bool steady = updateStableWindow(w, now);
        if (now - stableWaitStartMs >= STABLE_WAIT_TIMEOUT_MS) {
            // never settles (unstable surface): send the best candidate
            if (isnan(stableCandidate) || stableCandidate < MIN_WEIGHT_TO_SEND_G) stableCandidate = w;
            phase = P_SENDING;
            Serial.printf("[WF] STABLE_WAIT -> SENDING (timeout, fallback %.1f)\n", stableCandidate);
            return;
        }
        if (steady && now - stableSinceMs >= STABLE_WINDOW_MS) {
            phase = P_SENDING;
            Serial.printf("[WF] STABLE_WAIT -> SENDING (stable %.1f)\n", stableCandidate);
        }
        return;
    }

    // ---- SENDING ----
    if (phase == P_SENDING) {
        if (!sendStarted) {
            float container = in.container > 0 ? (float)in.container : 0.0f;
            if (container > 0 && w < container - 5.0f) {
                // gross below the empty spool: negative net, not worth sending
                Serial.printf("[WF] send aborted: %.1f g < container %.1f g\n", w, container);
                last.uid1 = latched; last.uid2 = ""; last.weight = w; last.status = "aborted_negative_net";
                showBanner("weigh_error", now);
                setSendPhase("error", now);
                phase = P_DONE;
                resetSlope();
                return;
            }
            sendNet = roundf(fmaxf(0.0f, w - container));
            sendStartMs = now;
            setSendPhase("send", now);
            sendId = fbSendWeight(latched, in.twin, (int)sendNet);
            sendStarted = true;
            Serial.printf("[WF] SENDING raw=%.1f container=%.0f net=%.0f uid=%s twin=%s\n", w, container, sendNet,
                          latched.c_str(), in.twin.c_str());
        }
        int st = fbSendStatus(sendId);   // 1 in flight, 2 ok, 3 failed
        if (st == 1 && now - sendStartMs < SEND_TIMEOUT_MS) return;
        bool ok = st == 2;

        last.uid1 = latched; last.uid2 = "";
        if (ok) {
            last.weight = sendNet; last.status = "ok";
            stats.sendOk++;
            showBanner("success", now);
            setSendPhase("success", now);
            Serial.printf("[WF] SENT OK net=%.0f\n", sendNet);
        } else {
            last.weight = w; last.status = "fail";
            stats.sendFail++;
            showBanner("error", now);
            setSendPhase("error", now);
            Serial.println("[WF] SEND FAILED");
        }
        phase = P_DONE;
        resetSlope();
        return;
    }

    // ---- DONE: locked until the spool is lifted off ----
    if (phase == P_DONE) {
        if (w < SPOOL_REMOVED_WEIGHT_G) {
            Serial.printf("[WF] DONE -> IDLE (empty, w=%.1f)\n", w);
            phase = P_IDLE;
            clearSession();
            readyWasZero = false;
            setSendPhase("ready", now);
        }
        return;
    }
}

String wfUid() { return latched; }

const char *wfStatus() {
    if (transient[0] && millis() < transientUntilMs) return transient;
    switch (phase) {
        case P_SCANNING:
        case P_STABLE_WAIT: return "scanning";
        case P_SENDING:     return "sending";
        case P_DONE:        return latched.length() ? "remove" : "idle";   // "remove the material"
        default:            return "idle";
    }
}

const char *wfPhaseName() {
    switch (phase) {
        case P_IDLE:        return "idle";
        case P_SCANNING:    return "scanning";
        case P_STABLE_WAIT: return "stable_wait";
        case P_SENDING:     return "sending";
        case P_DONE:        return "done";
    }
    return "unknown";
}

const char *wfSendPhase() { return sendPhase; }
WfStats wfStats() { return stats; }
WfLast wfLast() { return last; }
