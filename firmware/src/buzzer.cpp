#include "buzzer.h"
#include <Preferences.h>

static const int LEVEL_COUNT = 4;
// 10-bit duty for each volume level: a passive buzzer is loudest at 50 % (512) and quiet at a few percent.
static const uint16_t DUTY[LEVEL_COUNT] = { 0, 30, 160, 512 };
static const int DEFAULT_LEVEL = 2;

struct Step { uint16_t freq; uint16_t ms; };     // freq 0 = silence for ms

static int      gPin = -1;
static int      gLevel = DEFAULT_LEVEL;
static bool     gAttached = false;
static Step     gQueue[8];
static int      gCount = 0, gIndex = 0;
static bool     gPlaying = false;
static uint32_t gStepEnd = 0;

bool buzzerPinAllowed(int pin) {
    // Free outputs on the pin map (docs/WROOM32_PORT.md). Not allowed: 0/2/12/15 (strapping), 1/3 (USB serial),
    // 6-11 (flash), 16/17/27/32/33 (PN532 and HX711), 34-39 (input only).
    static const int OK[] = { 4, 13, 14, 18, 19, 21, 22, 23, 25, 26 };
    for (int p : OK) if (p == pin) return true;
    return false;
}

static void silence() {
    if (gAttached) ledcWrite(gPin, 0);
}

static void detach() {
    if (gAttached) {
        ledcWrite(gPin, 0);
        ledcDetach(gPin);
        pinMode(gPin, INPUT);
        gAttached = false;
    }
    gPlaying = false;
}

static void attach() {
    if (gPin < 0 || gAttached) return;
    gAttached = ledcAttach(gPin, 2000, 10);
    if (gAttached) ledcWrite(gPin, 0);
}

static void startStep() {
    const Step &s = gQueue[gIndex];
    if (s.freq) {
        ledcWriteTone(gPin, s.freq);       // sets the frequency (and a 50 % duty)
        ledcWrite(gPin, DUTY[gLevel]);     // then the duty that gives the chosen volume
    } else {
        ledcWrite(gPin, 0);
    }
    gStepEnd = millis() + s.ms;
}

static void play(const Step *steps, int n) {
    if (!gAttached || gLevel <= 0 || n <= 0) return;
    if (n > 8) n = 8;
    for (int i = 0; i < n; i++) gQueue[i] = steps[i];
    gCount = n; gIndex = 0; gPlaying = true;
    startStep();
}

void buzzerTick() {
    if (!gPlaying) return;
    if ((int32_t)(millis() - gStepEnd) < 0) return;
    if (++gIndex >= gCount) { gPlaying = false; silence(); return; }
    startStep();
}

void buzzerBegin() {
    Preferences p;
    p.begin("scale", true);
    int pin = p.getShort("buzpin", BUZZER_PIN);
    int level = p.getUChar("buzlvl", DEFAULT_LEVEL);
    p.end();
    if (pin >= 0 && !buzzerPinAllowed(pin)) pin = -1;       // a bad saved or built-in value must not drive a strapping pin
    gPin = pin;
    gLevel = (level >= 0 && level < LEVEL_COUNT) ? level : DEFAULT_LEVEL;
    attach();
    Serial.printf("[BUZ] pin %d, level %d%s\n", gPin, gLevel, gAttached ? "" : " (disabled)");
}

bool buzzerConfigure(int pin, int level) {
    if (pin == -2) pin = gPin;
    if (pin >= 0 && !buzzerPinAllowed(pin)) return false;
    if (level < 0 || level >= LEVEL_COUNT) level = gLevel;
    if (pin != gPin) { detach(); gPin = pin; attach(); }
    gLevel = level;
    Preferences p;
    p.begin("scale", false);
    p.putShort("buzpin", (int16_t)gPin);
    p.putUChar("buzlvl", (uint8_t)gLevel);
    p.end();
    Serial.printf("[BUZ] pin %d, level %d\n", gPin, gLevel);
    return true;
}

int buzzerPin() { return gPin; }
int buzzerLevel() { return gLevel; }

void buzzerTagRead() {
    static const Step s[] = { { 2600, 70 } };
    play(s, 1);
}

void buzzerSuccess() {
    static const Step s[] = { { 1800, 90 }, { 0, 40 }, { 2700, 160 } };
    play(s, 3);
}

void buzzerError() {
    static const Step s[] = { { 380, 320 } };
    play(s, 1);
}

void buzzerSaved() {
    static const Step s[] = { { 1500, 70 }, { 0, 30 }, { 2000, 70 }, { 0, 30 }, { 2600, 140 } };
    play(s, 5);
}
