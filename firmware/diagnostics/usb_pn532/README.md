# USB PN532 enumeration diagnostic

This standalone PlatformIO project tests the native USB host on a headless
ESP32-S3 N16R8 DevKitC-style board. It does not run weighing, NFC-over-UART,
Wi-Fi, or Firebase. The original Touch-LCD board's USB limitations are
documented separately in `../../../docs/USB_HOST_POSTMORTEM.md`.

The console uses UART0 at 115200 baud. The reader must connect to the native
USB port (GPIO19 D-, GPIO20 D+), with an appropriate host connection and 5 V
supply. The USB-to-UART programming port cannot act as a USB host.

Build from this directory with `pio run`. Before flashing, back up the running
application and configuration and confirm which OTA slot is active. The bench
test wrote only the diagnostic application at offset `0x10000` after confirming
that app0 was active, and restored the original application afterward. NVS,
the partition table, bootloader, and OTA selection were not written.

Successful startup reports `host install=ESP_OK` and `client register=ESP_OK`.
A detected USB device prints its VID/PID, interfaces, and endpoints. The probe
does not yet implement the USB-serial driver or issue PN532 commands.

## Bench result (2026-10-15)

The diagnostic compiled and was flashed with a verified hash. Over a 35-second
capture, the host initialized successfully but reported `device=NONE`; no USB
VID/PID or interface descriptor was received. S3-to-reader communication and
tag reading therefore remain unverified. The physical USB port, cable/OTG role,
and reader power must be checked before adding a USB-serial driver.

## PC USB-serial test

With the reader connected directly to the PC, install `pyserial` if necessary
and run `python probe_reader.py --port <reader-port> --seconds 20`. The port is
explicit to avoid probing another serial device. This test wakes the PN532,
checks its firmware version, and optionally reads ISO14443A tag UIDs. It does
not write tags. `--baud` defaults to 115200. Identification uses a ten-byte
zero preamble to wake HSU before GetFirmwareVersion, followed by normal-mode
SAMConfiguration without an IRQ wire.

With the reader connected directly to the PC, its CH340 bridge (VID:PID
`1A86:7523`, signed WCH driver) and PN532 v1.6 both responded at 115200 baud.
The 20-second UID scan completed 152 polls and 152 successful reads of the
same tag, without errors or tag writes. This validates the reader and PC
USB-serial path; it does not validate USB hosting from the S3.

The reader LED was reported brighter on the PC than with the direct USB-C
cable to the ESP32. Check VBUS and the host connection before retesting on
the S3, for example with a suitably connected self-powered USB hub. Hub
support is enabled in the Arduino/IDF package used by the diagnostic.
