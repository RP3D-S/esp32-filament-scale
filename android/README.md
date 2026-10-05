# Tiger Scale Lite Android app

Phone replacement for the scale's removed LCD. It draws a 480x320 "screen"
(weight, status, tag UID, reader LED) and has Tare and Calibrate controls.

- Live data: `ws://<scale>/ws` (delta frames; absent field = unchanged)
- Commands: `POST /api/tare`, `/api/calibrate`
- Finds the scale by mDNS (`tigerscalelite-XXXX`), or type the IP manually

## Build
Open the `android/` folder in Android Studio (Koala or newer, JDK 17) and run.
The Gradle wrapper is included (needs JDK 17: `JAVA_HOME` = a JDK 17, then `gradlew assembleDebug`). The phone must be on the
same Wi-Fi as the scale.


Builds clean with JDK 17 + Gradle 8.7 + SDK 34. BLE and Wi-Fi provisioning not yet tested on a phone.
