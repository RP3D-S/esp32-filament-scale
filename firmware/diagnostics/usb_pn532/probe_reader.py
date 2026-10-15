#!/usr/bin/env python3
"""Probe a PN532 USB-serial reader from the PC, without writing any tags."""
import argparse
import sys
import time

import serial
from serial.tools import list_ports


class PN532:
    def __init__(self, port):
        self.port = port
        self.buffer = bytearray()
        self.received = 0

    def packet(self, deadline):
        while time.monotonic() < deadline:
            chunk = self.port.read(min(self.port.in_waiting or 1, 512))
            self.received += len(chunk)
            self.buffer.extend(chunk)
            while len(self.buffer) >= 6:
                start = self.buffer.find(b"\x00\x00\xff")
                if start < 0:
                    del self.buffer[:-2]
                    break
                if start:
                    del self.buffer[:start]
                if len(self.buffer) < 6:
                    break
                if self.buffer[:6] == b"\x00\x00\xff\x00\xff\x00":
                    del self.buffer[:6]
                    return None  # ACK
                length = self.buffer[3]
                if (length + self.buffer[4]) & 0xff:
                    raise ValueError("Invalid PN532 frame length checksum")
                if length in (0, 255):
                    raise ValueError("Unexpected PN532 frame type")
                if len(self.buffer) < length + 7:
                    break
                payload = bytes(self.buffer[5:5 + length])
                if ((sum(payload) + self.buffer[5 + length]) & 0xff) or self.buffer[6 + length]:
                    raise ValueError("Invalid PN532 frame data checksum/postamble")
                del self.buffer[:length + 7]
                return payload
        raise TimeoutError(f"Timed out waiting for PN532 reply; received={self.received} bytes; pending={self.buffer.hex()}")

    def command(self, command, args=b"", timeout=1.5, wake=False):
        payload = bytes((0xd4, command)) + args
        frame = b"\x00\x00\xff" + bytes((len(payload), (-len(payload)) & 0xff))
        # Leading zeroes keep the HSU line active while the PN532 wakes up.
        preamble = bytes(10) if wake else b""
        self.port.write(preamble + frame + payload + bytes(((-sum(payload)) & 0xff, 0)))
        self.port.flush()
        deadline = time.monotonic() + timeout
        while True:
            reply = self.packet(deadline)
            if reply is None:
                continue
            if reply[:2] != bytes((0xd5, command + 1)):
                raise ValueError(f"Unexpected reply to command {command:02X}: {reply.hex()}")
            return reply[2:]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", required=True)
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument("--seconds", type=float, default=0, help="Duration of optional ISO14443A UID scanning")
    args = parser.parse_args()
    info = next((p for p in list_ports.comports() if p.device.lower() == args.port.lower()), None)
    if info is None:
        parser.error(f"Port {args.port} is not present")
    if args.seconds < 0:
        parser.error("--seconds must be nonnegative")
    print(f"USB reader: port={info.device} {info.hwid}", flush=True)
    port = serial.Serial()
    port.port = info.device
    port.baudrate = args.baud
    port.timeout = 0.05
    port.write_timeout = 2
    port.dtr = False
    port.rts = False
    port.open()
    try:
        port.reset_input_buffer()
        pn = PN532(port)
        version = pn.command(0x02, wake=True)
        if len(version) != 4 or version[0] != 0x32:
            raise ValueError(f"Unexpected chip/version: {version.hex()}")
        pn.command(0x14, b"\x01\x00\x00")  # SAMConfiguration: normal mode, no IRQ wire.
        print(f"PN532 v{version[1]}.{version[2]} support=0x{version[3]:02X}; SAMConfiguration OK", flush=True)
        if args.seconds == 0:
            return
        pn.command(0x32, b"\x05\xff\x01\x01")  # Bound passive activation retries.
        deadline = time.monotonic() + args.seconds
        uids = set()
        reads = 0
        polls = 0
        print(f"Scanning ISO14443A tags for {args.seconds:g}s; no tag writes", flush=True)
        while time.monotonic() < deadline:
            data = pn.command(0x4a, b"\x01\x00")
            polls += 1
            if not data:
                raise ValueError("Empty InListPassiveTarget response")
            if data[0]:
                if len(data) < 6 or data[0] != 1:
                    raise ValueError(f"Malformed target response: {data.hex()}")
                length = data[5]
                if length not in (4, 7, 10) or len(data) < 6 + length:
                    raise ValueError(f"Malformed UID response: {data.hex()}")
                uid = data[6:6 + length].hex().upper()
                reads += 1
                if uid not in uids:
                    print(f"TAG UID={uid} ATQA={data[2:4].hex().upper()} SAK={data[4]:02X}", flush=True)
                uids.add(uid)
            time.sleep(0.1)
        print(f"Result: polls={polls} tag_reads={reads} unique_tags={len(uids)}", flush=True)
    finally:
        port.close()


if __name__ == "__main__":
    try:
        main()
    except (serial.SerialException, TimeoutError, ValueError) as error:
        print(f"Reader probe failed: {error}", file=sys.stderr, flush=True)
        sys.exit(1)
