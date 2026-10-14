# Firmware (ESP32 WROOM-32 / ESP32-S3 N16R8)

```
cd firmware
pio run -t upload
pio device monitor
```

For the ESP32-S3 N16R8 board, connect over USB and use:

```
pio run -e esp32s3_n16r8 -t upload
pio device monitor
```

The S3 pin map is HX711 DOUT/SCK on GPIO 4/5, PN532 HSU RX/TX on GPIO 16/17,
PN532 RSTPDN on GPIO 18, and buzzer on GPIO 6. The S3 target uses the existing
two-slot OTA partition table; its 16 MB flash is configured, while the partition
table remains within the first 4 MB for compatibility with the WROOM target.
First boot opens the Wi-Fi portal `TigerScaleLite-Setup`. Then the scale is at
`http://tigerscalelite-XXXX.local` (built-in page) and the Android app finds it by itself.

Calibration: tare empty, place a known weight, then in the app press Calibrate
(or `curl -X POST http://<ip>/api/calibrate -d '{"knownGrams":500}' -H 'Content-Type: application/json'`).

The ESP32-S3 target has been build-tested; its peripherals still need to be wired
to the pin map above and tested on the device. WROOM pins: ../docs/WROOM32_PORT.md
