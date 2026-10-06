// Buzzer feedback: a short beep when a tag is read, a rising two-tone when the weight was sent to the cloud,
// a low tone on an error. The original scale had a speaker and a codec; this port has a buzzer on one GPIO.
//
// Passive buzzer driven by PWM (LEDC): the tone is the PWM frequency, the volume is the duty cycle. An active
// buzzer works too but only gives one fixed tone. Sounds are a queue that buzzerTick() advances from loop(),
// so playing one never waits for it to finish.
//
// The pin is configurable: BUZZER_PIN at build time is the default, and buzzerConfigure() changes it (and the
// volume) at run time; both are saved in NVS. Only pins that are safe as plain outputs on a WROOM-32 are accepted.
#pragma once
#include <Arduino.h>

#ifndef BUZZER_PIN
#define BUZZER_PIN 26      // free on the pin map in docs/WROOM32_PORT.md; -1 = no buzzer
#endif

/** Loads the saved pin and volume (or the defaults) and starts the buzzer. Call once from setup(). */
void buzzerBegin();

/** Advances the sound being played. Call every loop() pass. */
void buzzerTick();

/**
 * Sets the pin and the volume (0 off, 1 low, 2 medium, 3 loud) and saves them. `pin` -1 disables the buzzer,
 * -2 keeps the current pin. Returns false (and leaves everything as it was) if the pin is not allowed.
 */
bool buzzerConfigure(int pin, int level);

int  buzzerPin();
int  buzzerLevel();

/** True for the GPIOs that can drive a buzzer: free outputs, no strapping pins, no flash, no input-only. */
bool buzzerPinAllowed(int pin);

void buzzerTagRead();   // short high beep
void buzzerSuccess();   // two rising tones
void buzzerError();     // one low, longer tone
void buzzerSaved();     // three quick rising notes (calibration saved)
