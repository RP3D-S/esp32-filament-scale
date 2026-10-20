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

### Changed
- The spool photo no longer waits for the Firestore inventory lookup: the firmware reads the product id from the tag (page 5, bytes 0-3, u32 big-endian) and, for a TigerTag+, sends the app the catalogue image URL `https://cdn.tigertag.io/img?id=<product>` as soon as the tag is read, with no request from the scale (the lookup cost about 4 s, mostly a TLS handshake); a standard TigerTag (`0xFFFFFFFF`, no product) still uses the inventory `url_img`. The CDN answers 200 with a placeholder for an id that is not in the catalogue. Verified on the S3 with real tags: a photo not seen before showed on the phone about 2.8 s after the tag was read (Bluetooth plus the phone's own download, one sample, about 0.4 s resolution), a photo already cached by the app in under 0.5 s, and the photo cleared about 4 s after the spool was removed; at the same time the scale's own cloud requests were failing on that network (token refresh `HTTP -1`, TLS handshakes timing out after 12-23 s), where the inventory lookup would not have produced a photo at all. Updated `firmware/src/main.cpp`.
- Reading a tag's pages 5-8 (product, brand, material, colour) no longer gives up after 12 failed tries: it keeps trying once every 3 s for as long as the tag stays in the field, so a weak coupling where the UID answers but the page reads do not can recover and the spool photo comes from the catalogue instead of falling back to the inventory one; the success log line now carries the try number. The slow pace is deliberate, because each failed try blocks `loop()` for a few hundred ms. Built and flashed on the S3; not yet seen recovering on a real marginal read; updated `firmware/src/main.cpp`.
- The app shows a spool card under the scale screen, like the card in the TigerTag app, instead of the bare photo: tag type (TigerTag+), a Refill badge, brand, series and name, the catalogue image, material, aspect, weight, diameter, the colour swatches (several for a gradient) and the nozzle, bed and drying temperatures, plus a percentage bar and `net / capacity` when the account's inventory gives the empty-spool weight. The product id comes from the catalogue photo URL the scale already sends (`cdn.tigertag.io/img?id=N`) and the phone asks the public TigerTag API (`product/get`) for the rest; the endpoint takes a chip UID but answers the same for any value, so the app sends 0 and no spool UID leaves the phone. Without a catalogue id (a standard TigerTag), without network or while the answer is on its way, it shows the photo alone as before. Labels are in the nine languages. Verified on the phone with two real spools (ROSA3D Andromeda, Smart3DPT Energy Orange); the percentage bar was not seen, because the empty-spool weight was not available on that bench; added `SpoolCard.kt`, updated `MainActivity.kt` and every `strings.xml`.
- The spool card no longer blinks when the reader loses the tag for a moment: the app keeps the last photo URL for 5 s before treating the spool as gone, while a different spool changes the card at once. Watching the phone for 80 s, the card disappeared a single time, 5 s after the spool was taken away, with no cut in between; whether the shortest gap really made the scale lose the tag was not confirmed. Also, nothing is shown while the catalogue request is on its way (the bare photo first and the card after looked like two screens), and a failed request is retried up to five times, 3 s apart; a standard TigerTag, which has no catalogue entry, still shows its inventory photo; updated `SpoolCard.kt`.

