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
- Replaced hostname-based SNTP with direct UDP DNS/NTP and numeric Google NTP fallbacks; HTTPS uses a resolved IP with the original TLS hostname. Added retry throttling and network diagnostics in `firmware/src/firebase.cpp`.
- Firebase errors returned directly over BLE now leave the app's connecting state, and the account dialog offers a cancel action that stops pending sign-in or QR polling; updated the Android Firebase state/dialog and firmware command/task handling.
- The ESP32-S3 is now the default PlatformIO target, and its onboard RGB LED shows weight in red, tag reads in blue, successful Firebase sends in green, and send errors as alternating blue/red; updated `firmware/platformio.ini`, `firmware/src/main.cpp`, and `docs/WROOM32_PORT.md`.

## 2026-10-15

### Added
- Added an isolated native USB-host enumeration diagnostic for the headless S3 N16R8 board in `firmware/diagnostics/usb_pn532/`; the build and flash passed, but a 35-second bench capture reported `host=OK device=NONE`. The README records the physical connection checks needed before USB-serial/PN532 communication can be tested.
- Added `firmware/diagnostics/usb_pn532/probe_reader.py` for PC USB-serial firmware identification and read-only tag UID tests, including a long HSU wake preamble. The direct-PC test identified CH340/PN532 v1.6 and completed 152 successful UID reads in 20 seconds without errors. Updated the diagnostic README with results and the S3 power/host checks still required.

### Changed
- The single-reader weighing workflow advances to stability checks as soon as the first tag is read; the 8-second RFID timeout now applies only when no tag is found. Inventory lookup for net weight and any twin-tag update remains in the send stage; updated `firmware/src/workflow.cpp` and `firmware/src/workflow.h`. The S3 build and flash hash verification passed, and boot detected the RFID reader and reconnected Wi-Fi; a physical weighing cycle remains unverified.
- Updated `docs/HANDOFF.md` with the S3 checkpoint, flashed single-tag workflow, measured DNS delay, pending physical weighing test, USB diagnostic results, and the existing local DNS/NTP work still excluded from commits.

## 2026-10-16

### Fixed
- Prioritized inventory lookup and pending weight sends over profile reads and heartbeats in `firmware/src/firebase.cpp`, retaining remote stop commands and logging send queue/duration. Added a TTL-bounded DNS cache with network invalidation and configured/working resolver selection in `firmware/src/firebase_dns.h`; read and write requests now share that resolver, without bench-specific routing. Added host regression tests in `firmware/tests/firebase_dns_test.cpp` and recorded the measured 23.807-second baseline and validation status in `docs/HANDOFF.md`.
- Host cache tests, the S3 build and application flash/hash verification passed. The same 210 g physical retest completed in 8.827 seconds after the tag versus 23.807 seconds before (62.92% shorter), with both inventory writes HTTP 200, a 340 ms queue and 4652 ms for the writes. The cycle used cached DNS and had no DNS timeout, main-loop stall or negative-weight event; updated `docs/HANDOFF.md` with the measured result and the earlier idle auto-tare anomaly still unexplained.

## 2026-10-19

### Changed
- The two inventory writes of a weighing (spool and its twin) go out as one atomic `documents:commit` instead of two PATCH requests, falling back to the sequential PATCHes if the commit fails (for example a twin that is not in the inventory); each request costs ~1.8 s of TLS handshake on the S3, so this should save about 2.8 s per weighing. Built, flashed and hash-verified on the S3, but not exercised end to end because the NFC reader was unplugged during the bench test; updated `firmware/src/firebase.cpp`.
- The remote-command poll backs off from 15 s to 5 min after an HTTP 403 (the account's Firestore rules do not let the scale list `commands`), so a request that can only fail no longer blocks the single-threaded cloud task every 15 s; measured: one 403 in 180 s instead of one every 15 s. Updated `firmware/src/firebase.cpp`.

### Added
- One `[FB] req <method> <path> -> HTTP <code> dns=<ms> tls=<ms> total=<ms>` log line per cloud request, to see where the time goes (bench: dns 0-1 ms with the cache, tls 1.8-2.0 s, total 2.7-3.0 s per request); updated `firmware/src/firebase.cpp`.

## 2026-10-20

### Fixed
- The firmware update in the app no longer downloads 1.6 MB before finding out the scale cannot be reached over Wi-Fi: it first checks that the scale answers (about 4 s) and, if not, stops with a message that names the address tried and the network the scale reports (`fw_unreachable`, nine languages); it also prefers the address the scale reports over Bluetooth to the saved one. The failure seen on the bench was not a stale address: the scale was on `ESP32-REPETIDOR` (192.168.4.2, behind a repeater) and the phone on `Atome3D` (192.168.1.x), two subnets that cannot reach each other. Verified on the device: with the scale on that network the app failed in about 4 s with the new message and downloaded nothing; with the phone moved to the scale's network the update then completed: the scale went from 0.3.0 (flashed by USB on `app0`) to 0.3.1 running from `app1`, confirmed on the serial console (the progress screen itself was not watched). Updated `FirmwareUpdater.kt`, `ScaleViewModel.kt` and every `strings.xml`.

