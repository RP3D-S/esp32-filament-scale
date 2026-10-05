#!/usr/bin/env python3
"""Build the firmware and publish it as a GitHub Release that the app can update from.

    python firmware/scripts/release_firmware.py [--notes "text"] [--no-build] [--dry-run]

It reads the version from FW_VERSION in firmware/platformio.ini, builds the esp32dev_hsu image, and writes
firmware/dist/firmware.bin plus firmware/dist/firmware.json ({version, size, sha256, md5}). Unless --dry-run, it
then creates the release `fw-v<version>` with `gh release create` (GitHub CLI, logged in), attaching both files.
The app reads the newest non-draft release that has both assets, checks the SHA-256 of the download against
firmware.json, and the scale checks the MD5 before it switches slots.

Publishing is visible to everyone who can see the repository: run it only when you mean to release.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FW_DIR = os.path.dirname(HERE)
DIST = os.path.join(FW_DIR, "dist")
ENV = "esp32dev_hsu"


def version() -> str:
    with open(os.path.join(FW_DIR, "platformio.ini"), encoding="utf-8") as f:
        m = re.search(r'-DFW_VERSION=\\"([^"\\]+)\\"', f.read())
    if not m:
        sys.exit("FW_VERSION not found in platformio.ini")
    return m.group(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--notes", default="", help="release notes")
    ap.add_argument("--no-build", action="store_true", help="use the image already built")
    ap.add_argument("--dry-run", action="store_true", help="write dist/ but do not publish")
    args = ap.parse_args()

    ver = version()
    if not args.no_build:
        subprocess.check_call(["pio", "run", "-e", ENV], cwd=FW_DIR)
    built = os.path.join(FW_DIR, ".pio", "build", ENV, "firmware.bin")
    if not os.path.exists(built):
        sys.exit(f"{built} not found: build first")

    os.makedirs(DIST, exist_ok=True)
    image = os.path.join(DIST, "firmware.bin")
    shutil.copyfile(built, image)
    with open(image, "rb") as f:
        blob = f.read()
    manifest = {
        "version": ver,
        "size": len(blob),
        "sha256": hashlib.sha256(blob).hexdigest(),
        "md5": hashlib.md5(blob).hexdigest(),
    }
    manifest_path = os.path.join(DIST, "firmware.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2))

    if args.dry_run:
        print(f"dry run: wrote {DIST}, nothing published")
        return
    tag = f"fw-v{ver}"
    subprocess.check_call([
        "gh", "release", "create", tag, image, manifest_path,
        "--title", f"Firmware {ver}", "--notes", args.notes or f"Firmware {ver} for the Tiger Scale Lite (ESP32 WROOM-32).",
    ], cwd=FW_DIR)
    print(f"published {tag}")


if __name__ == "__main__":
    main()
