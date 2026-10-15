# Worklog — since the last checkpoint

The single source of truth for everything done since the last commit. Read it at
the start of a session; append to it the moment a change is done, never in a
batch at the end.

The headings match [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), so
at release time this file is synthesised into the `CHANGELOG.md` entry and the
release note in `docs/release-notes/` without being re-derived from the diff.

Keep it clean as you go: describe the **end state**, not the journey. An "Added
X" and a later "Fixed X" from the same cycle collapse into one entry. Anything
reverted disappears entirely — it never shipped. One bullet, one logical change,
always naming the files touched.

Reset this file to the header above at each checkpoint. Nothing is lost: the raw
log lives in git history and the synthesised entry lives in the changelog.

---

## 2026-10-14

### Added
- Added the TigerTag QR account-pairing flow to the ESP32 and Android app, keeping the polling token on the device and displaying the approval QR/code in Settings; updated `firmware/src/firebase.cpp`, `firmware/src/firebase.h`, `firmware/src/main.cpp`, Android account UI/state/resources, and `docs/ACCOUNT-PAIRING.md`.
- Added PlatformIO ESP32-S3 N16R8 build and OTA environments, an S3-safe peripheral pin map, and wiring/build notes in `firmware/platformio.ini`, `firmware/src/main.cpp`, `firmware/src/buzzer.cpp`, `firmware/src/buzzer.h`, `firmware/README.md`, and `docs/WROOM32_PORT.md`.

### Changed
- Firebase errors returned directly over BLE now leave the app's connecting state, and the account dialog offers a cancel action that stops pending sign-in or QR polling; updated the Android Firebase state/dialog and firmware command/task handling.
- The ESP32-S3 is now the default PlatformIO target, and its onboard RGB LED shows weight in red, tag reads in blue, successful Firebase sends in green, and send errors as alternating blue/red; updated `firmware/platformio.ini`, `firmware/src/main.cpp`, and `docs/WROOM32_PORT.md`.
