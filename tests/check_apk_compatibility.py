#!/usr/bin/env python3
"""Dependency-free APK packaging checks; cryptographic checks require apksigner.

Usage: python3 tests/check_apk_compatibility.py [Authenticator.apk]
This does not replace an installation/runtime test on an Android 6 device.
"""
import struct
import sys
import zipfile
from pathlib import Path


def check_apk(path):
    with zipfile.ZipFile(path) as apk, open(path, "rb") as raw:
        names = set(apk.namelist())
        for name in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            if name not in names:
                raise ValueError("Missing " + name)
        # Android 6 supports DEX 035, not the 037+ formats used by newer APIs.
        dex_names = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        for name in dex_names:
            if apk.read(name)[:8] != b"dex\n035\0":
                raise ValueError(name + " is not Android 6-compatible DEX 035")
        # v2/v3 signatures alone cannot be read by Android 6. Presence is only
        # a structural check: apksigner verify must also validate these entries.
        upper_names = {n.upper() for n in names}
        if "META-INF/MANIFEST.MF" not in upper_names:
            raise ValueError("Missing v1 signing manifest (required on Android 6)")
        signers = [n[:-3] for n in upper_names if n.startswith("META-INF/") and n.endswith(".SF")]
        if not any(base + ext in upper_names for base in signers for ext in (".RSA", ".DSA", ".EC")):
            raise ValueError("Missing v1 signature pair (required on Android 6)")
        # Apps targeting API 30+ must keep the resources table uncompressed and
        # 4-byte aligned or newer Android versions reject installation.
        table = apk.getinfo("resources.arsc")
        if table.compress_type != zipfile.ZIP_STORED:
            raise ValueError("resources.arsc must be uncompressed")
        raw.seek(table.header_offset + 26)
        name_length, extra_length = struct.unpack("<HH", raw.read(4))
        offset = table.header_offset + 30 + name_length + extra_length
        if offset % 4:
            raise ValueError("resources.arsc must be 4-byte aligned")
        corrupt = apk.testzip()
        if corrupt:
            raise ValueError("Corrupt APK entry: " + corrupt)


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "Authenticator.apk"
    try:
        check_apk(path)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit("APK compatibility check failed: " + str(error))
    print("APK packaging checks passed (DEX 035, v1 entries, aligned resources).")
