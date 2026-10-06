# ESP32 PPP-to-WiFi bridge

The PC shares its internet over the USB serial cable (PPP); the ESP32 re-shares
it as a WiFi access point with NAT.

    Internet <- PC (pppd + NAT) <-USB/UART0-> ESP32 (PPP client + NAPT) <-WiFi AP-> clients

Target: classic ESP32 with a USB-UART chip (Keyestudio ESP32 PLUS), ESP-IDF v5.2+.
Status: **written but not built or run** — no ESP-IDF toolchain or hardware was
available when it was authored. Expect to fix small API differences.

## Build and flash (ESP-IDF terminal)

    idf.py set-target esp32
    idf.py menuconfig        # "PPP-to-WiFi bridge": SSID, password, baud
    idf.py -p /dev/ttyUSB0 flash

Flash first, then start the PC side: after flashing, UART0 belongs to PPP, so
`idf.py monitor` shows nothing useful. Logs go to UART1 (TX=GPIO17, RX=GPIO16)
at 115200 — attach a second USB-UART adapter there if you need them.

## Run

    ./pc/start-bridge.sh /dev/ttyUSB0 460800

It enables forwarding, adds the NAT rules, runs `pppd` and removes the rules on
Ctrl-C. Then join the WiFi network `esp32-bridge` from a phone or laptop.

## Notes

- Windows has no practical PPP server; use Linux (or a Raspberry Pi).
- Throughput at 460800 baud is roughly 300-400 kbit/s. Try 921600 if the cable
  and chip allow it; change both the menuconfig value and the script argument.
- Opening the serial port can reset the ESP32 once (DTR/RTS). If the board stays
  silent, unplug and replug it.
- The ESP32 uses `10.0.0.2`, the PC `10.0.0.1`; WiFi clients get `192.168.4.x`.
- The default AP password in `Kconfig.projbuild` is a placeholder: change it.
