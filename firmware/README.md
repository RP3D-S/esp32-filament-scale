# Firmware (ESP32 WROOM-32)

```
cd firmware
pio run -t upload
pio device monitor
```
First boot opens the Wi-Fi portal `TigerScaleLite-Setup`. Then the scale is at
`http://tigerscalelite-XXXX.local` (built-in page) and the Android app finds it by itself.

Calibration: tare empty, place a known weight, then in the app press Calibrate
(or `curl -X POST http://<ip>/api/calibrate -d '{"knownGrams":500}' -H 'Content-Type: application/json'`).

Status: not compiled or bench-tested yet. Pins: ../docs/WROOM32_PORT.md
