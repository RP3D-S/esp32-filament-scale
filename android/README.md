# FilScale Android app

Phone replacement for the scale's removed LCD. It draws a 480x320 "screen"
(weight, status, tag UID, reader LEDs) and has Tare, Calibrate and servo controls.

- Live data: `ws://<scale>/ws` (delta frames; absent field = unchanged)
- Commands: `POST /api/tare`, `/api/calibrate`, `/api/servo`
- Finds the scale by mDNS (`filscale-XXXX`), or type the IP manually

## Build
Open the `android/` folder in Android Studio (Koala or newer, JDK 17) and run.
Android Studio generates the Gradle wrapper on first sync. The phone must be on the
same Wi-Fi as the scale.

Status: written without an Android SDK available, so it has not been compiled yet.
