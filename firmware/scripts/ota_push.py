#!/usr/bin/env python3
"""Push a firmware image to a scale over Wi-Fi with the streamed OTA endpoint (see src/ota.h).

    python firmware/scripts/ota_push.py <scale-ip> [path/to/firmware.bin]

Much faster than espota: the image goes out as one HTTP POST that TCP can stream, instead of 1 KB at a time
with an answer awaited for each. The password is read from firmware/.ota_password and never sent: the scale
sends a nonce and the PC answers with sha256(nonce + password).
"""
import hashlib
import http.client
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
FIRMWARE_DIR = os.path.dirname(HERE)


def call(conn, method, path, body=None, headers=None):
    conn.request(method, path, body=body, headers=headers or {})
    r = conn.getresponse()
    data = r.read().decode("utf-8", "replace")
    return r.status, data


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ip = sys.argv[1]
    image = sys.argv[2] if len(sys.argv) > 2 else os.path.join(FIRMWARE_DIR, ".pio", "build", "esp32dev_hsu", "firmware.bin")
    with open(os.path.join(FIRMWARE_DIR, ".ota_password"), encoding="utf-8") as f:
        password = f.read().strip()
    with open(image, "rb") as f:
        blob = f.read()
    size, md5 = len(blob), hashlib.md5(blob).hexdigest()
    print(f"image {image}: {size} bytes, md5 {md5}")

    conn = http.client.HTTPConnection(ip, 80, timeout=20)
    st, body = call(conn, "GET", "/api/ota/nonce")
    if st != 200:
        sys.exit(f"nonce failed: HTTP {st} (is OTA_PASSWORD compiled in?)")
    nonce = json.loads(body)["nonce"]
    auth = hashlib.sha256((nonce + password).encode()).hexdigest()

    st, body = call(conn, "POST", "/api/ota/arm", json.dumps({"size": size, "md5": md5, "auth": auth}),
                    {"Content-Type": "application/json"})
    if st != 200:
        sys.exit(f"arm refused: HTTP {st} {body}")
    token = json.loads(body)["token"]

    t0 = time.time()
    while time.time() - t0 < 20:                       # the scale pauses the cloud first
        st, body = call(conn, "GET", "/api/ota/status")
        if json.loads(body)["phase"] == 1:
            break
        time.sleep(0.3)
    else:
        sys.exit("the scale did not reach the armed state in 20 s")
    print(f"armed after {time.time() - t0:.1f} s")

    conn.close()
    conn = http.client.HTTPConnection(ip, 80, timeout=60)
    conn.putrequest("POST", "/api/ota")
    conn.putheader("X-OTA-Token", token)
    conn.putheader("Content-Type", "application/octet-stream")
    conn.putheader("Content-Length", str(size))
    conn.endheaders()
    t0 = time.time()
    sent, chunk, last = 0, 16384, 0
    while sent < size:
        conn.send(blob[sent:sent + chunk])
        sent += min(chunk, size - sent)
        pct = sent * 100 // size
        if pct // 10 != last // 10:
            print(f"  {pct}%  {sent / (time.time() - t0) / 1024:.0f} KB/s")
        last = pct
    r = conn.getresponse()
    print(f"scale answered HTTP {r.status} {r.read().decode()}")
    dt = time.time() - t0
    print(f"sent {size} bytes in {dt:.1f} s = {size / dt / 1024:.0f} KB/s")
    if r.status != 200:
        sys.exit(1)

    print("waiting for the scale to restart ...")
    time.sleep(6)
    for _ in range(40):
        try:
            c = http.client.HTTPConnection(ip, 80, timeout=3)
            st, body = call(c, "GET", "/api/status")
            if st == 200:
                print("running firmware", json.loads(body).get("fw_version"))
                return
        except OSError:
            pass
        time.sleep(1.5)
    sys.exit("the scale did not come back in 60 s")


if __name__ == "__main__":
    main()
