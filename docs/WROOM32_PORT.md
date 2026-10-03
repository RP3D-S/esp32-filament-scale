# WROOM-32 port plan

Goal: run this scale on a **KEYESTUDIO ESP32-WROOM-32** (classic ESP32, 4 MB flash,
no PSRAM) with **no on-board display**. All readings and controls are served to a
phone over Wi-Fi (web UI / PWA already present in `data/www`).

## Components kept
| Part | Qty | Interface |
|------|-----|-----------|
| HX711 + 5 kg load cell | 1 | 2 GPIO |
| PN532 NFC reader | 2 | UART (HSU), mode switch OFF/OFF |
| Continuous-rotation servo | 1 | PWM |

## Components dropped
Touch LCD (AXS15231B/ST7796) + LVGL, AXP2101 PMIC/battery, ES8311 codec/speaker,
TCA9554 expander. Power is plain USB 5 V.

## Proposed pin map (avoids flash 6-11, strapping 0/2/12/15, input-only 34-39 for outputs)
| Signal | GPIO |
|--------|------|
| HX711 DOUT | 32 |
| HX711 SCK | 33 |
| PN532 #1 RX (<- PN532 TXD) | 16 (Serial2) |
| PN532 #1 TX (-> PN532 RXD) | 17 (Serial2) |
| PN532 #1 RSTPDN | 27 |
| PN532 #2 RX (<- PN532 TXD) | 25 (Serial1, remapped) |
| PN532 #2 TX (-> PN532 RXD) | 26 (Serial1, remapped) |
| PN532 #2 RSTPDN | 13 |
| Servo signal | 18 |

PN532 modules run on 3.3 V or 5 V per the module; HSU logic is 3.3 V-tolerant on
the V3 board.

## Approach
Strip, don't patch: the original is a ~16 000-line single `.ino` with ~2 400 LVGL
calls interleaved with scale logic. The port is a new slim firmware that lifts
the scale (§23), RFID (§24), weigh state machine (§21), web server (§19) and
cloud/WS sections from upstream, with:

- `platformio.ini` env `esp32dev_hsu` (board `esp32dev`, 4 MB partition table with
  two OTA slots + LittleFS for the web UI)
- no LVGL / GFX / XPowersLib dependencies
- phone UI = the existing PWA, plus a live weight/status view over WebSocket

## Steps
1. [x] New `firmware/platformio.ini` env + 4 MB partitions (written, not compiled)
2. [x] Skeleton firmware: Wi-Fi portal, web server, WebSocket (written, not compiled)
3. [x] HX711 scale: tare, calibration, filtering (written)
4. [x] 2x PN532 over HSU, UID read (written; TigerTag page decoding + cloud still to port)
5. [~] Servo scan done; full weigh workflow/cloud send not ported
6. [x] Android app in `android/` (written, not compiled)
7. [ ] Bench test on hardware

Upstream: https://github.com/TigerTag-Project/Tiger-Scale-V3 (MIT). Per upstream's
TRADEMARK.md, a modified fork must not be called "TigerScale".
