import tempfile
import unittest
import zipfile
from pathlib import Path

from check_apk_compatibility import check_apk


class ApkCompatibilityTests(unittest.TestCase):
    def make_apk(self, path, dex=b"dex\n035\0", signed=True, compressed=False, aligned=True):
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"test fixture")
            info = zipfile.ZipInfo("resources.arsc")
            offset = apk.fp.tell() + 30 + len(info.filename)
            padding = (-offset) % 4
            if aligned and padding:
                info.extra = b"\xff\xff" + padding.to_bytes(2, "little") + bytes(padding)
            elif not aligned:
                # Deliberately move the payload to an unaligned offset.
                info.extra = b"\xff\xff" + ((padding + 1) % 4).to_bytes(2, "little") + bytes((padding + 1) % 4)
            info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            apk.writestr(info, b"resources")
            apk.writestr("classes.dex", dex)
            if signed:
                apk.writestr("META-INF/MANIFEST.MF", b"fixture")
                apk.writestr("META-INF/CERT.SF", b"fixture")
                apk.writestr("META-INF/CERT.RSA", b"fixture")

    def test_packaging_requirements(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.apk"
            self.make_apk(path)
            check_apk(path)
            for options, message in [
                ({"dex": b"dex\n039\0"}, "DEX 035"),
                ({"signed": False}, "v1 signing"),
                ({"compressed": True}, "uncompressed"),
                ({"aligned": False}, "aligned"),
            ]:
                with self.subTest(options=options):
                    self.make_apk(path, **options)
                    with self.assertRaisesRegex(ValueError, message):
                        check_apk(path)


if __name__ == "__main__":
    unittest.main()
