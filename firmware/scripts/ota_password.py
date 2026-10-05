"""PlatformIO pre-script: the OTA password lives in firmware/.ota_password (git-ignored), never in the repo.

The first build generates it. Every build compiles it into the firmware (OTA_PASSWORD), and an espota upload
passes it to the uploader as --auth. To update a scale from another PC, copy the .ota_password file over (or
flash that PC's firmware once by USB, which sets its own password).
"""
import os
import secrets

Import("env")  # noqa: F821  (provided by PlatformIO/SCons)

pw_file = os.path.join(env.subst("$PROJECT_DIR"), ".ota_password")  # noqa: F821
if not os.path.exists(pw_file):
    with open(pw_file, "w", encoding="utf-8") as f:
        f.write(secrets.token_urlsafe(9))
    print("[ota] created %s (git-ignored): the OTA password of scales built from this PC" % pw_file)

with open(pw_file, encoding="utf-8") as f:
    password = f.read().strip()

env.Append(CPPDEFINES=[("OTA_PASSWORD", env.StringifyMacro(password))])  # noqa: F821
if env.GetProjectOption("upload_protocol", "") == "espota":  # noqa: F821
    env.Append(UPLOAD_FLAGS=["--auth=" + password])  # noqa: F821
