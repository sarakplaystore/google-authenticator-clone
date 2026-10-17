import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import apk_sign  # noqa: E402
import check_apk_compatibility  # noqa: E402
from check_apk_compatibility import (  # noqa: E402
    check_apk,
    check_v1_signature,
    check_v2_v3_signature,
)

KEY = ROOT / "tools" / "debug_key.pem"
CERTIFICATE = ROOT / "tools" / "debug_cert.pem"


def entry(name, data):
    item = apk_sign.Entry(name, zipfile.ZIP_STORED, 0, len(data), len(data), data,
                          0, 0x21, 0)
    item.crc = zipfile.crc32(data) & 0xFFFFFFFF
    return item


class ApkCompatibilityTests(unittest.TestCase):
    def make_unsigned_apk(self, path, dex=b"dex\n035\0", compressed=False):
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("AndroidManifest.xml", b"test fixture")
            info = zipfile.ZipInfo("resources.arsc")
            info.compress_type = zipfile.ZIP_DEFLATED if compressed else zipfile.ZIP_STORED
            apk.writestr(info, b"resources")
            apk.writestr("classes.dex", dex)
        return path

    def make_signed_apk(self, directory, **kwargs):
        unsigned = self.make_unsigned_apk(Path(directory) / "unsigned.apk", **kwargs)
        signed = Path(directory) / "signed.apk"
        apk_sign.sign_apk(str(unsigned), str(signed), str(KEY), str(CERTIFICATE))
        return signed

    def test_signed_apk_passes_every_check(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            check_apk(signed, CERTIFICATE)

    def test_packaging_requirements(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            check_apk(signed)
            for options, message in [
                ({"dex": b"dex\n039\0"}, "DEX 035"),
                ({"compressed": True}, "uncompressed"),
            ]:
                with self.subTest(options=options):
                    signed = self.make_signed_apk(directory, **options)
                    with self.assertRaisesRegex(ValueError, message):
                        check_apk(signed)

    def test_entries_must_be_4_byte_aligned(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "unaligned.apk"
            entries = [
                entry(b"AndroidManifest.xml", b"test fixture"),
                entry(b"resources.arsc", b"resources"),
                entry(b"classes.dex", b"dex\n035\0"),
                entry(b"META-INF/MANIFEST.MF", b"fixture"),
                entry(b"META-INF/CERT.SF", b"fixture"),
                entry(b"META-INF/CERT.RSA", b"fixture"),
            ]
            # No alignment padding: the payloads start at 30 + len(name).
            blob, records = apk_sign.write_entries(entries, alignment=1)
            central_directory = apk_sign.build_central_directory(records)
            eocd = apk_sign.build_eocd(len(entries), len(central_directory), len(blob))
            path.write_bytes(blob + central_directory + eocd)
            with self.assertRaisesRegex(ValueError, "aligned"):
                check_apk(path)

    def test_v1_signature_must_cover_the_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            # Rewrite the archive with different contents but keep the old
            # signature: exactly the situation of a stale signature.
            tampered = Path(directory) / "tampered.apk"
            with zipfile.ZipFile(signed) as source, zipfile.ZipFile(tampered, "w") as target:
                for name in source.namelist():
                    if name == "classes.dex":
                        target.writestr(name, b"dex\n035\0tampered")
                    else:
                        target.writestr(name, source.read(name))
            with zipfile.ZipFile(tampered) as archive:
                with self.assertRaisesRegex(ValueError, "v1 signature does not"):
                    check_v1_signature(archive, set(archive.namelist()))

    def test_v2_signature_must_match_the_contents(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            raw = bytearray(signed.read_bytes())
            offset = raw.find(b"dex\n035\0")
            self.assertGreater(offset, 0)
            raw[offset + 8] ^= 0xFF
            tampered = Path(directory) / "tampered.apk"
            tampered.write_bytes(bytes(raw))
            with zipfile.ZipFile(tampered) as archive:
                with self.assertRaisesRegex(ValueError, "does not match the contents"):
                    check_v2_v3_signature(bytes(raw), archive)

    def test_v2_signature_must_be_cryptographically_valid(self):
        """A signature that quotes the right digest but is not signed is rejected.

        The signature bytes live in the signing block, which the content digest
        does not cover, so only the signature check itself can catch this.
        """
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            raw = bytearray(signed.read_bytes())
            pairs, _, _ = check_apk_compatibility.find_signing_block(bytes(raw))
            for block_id, v3 in ((check_apk_compatibility.V2_BLOCK_ID, False),
                                 (check_apk_compatibility.V3_BLOCK_ID, True)):
                signer = check_apk_compatibility.parse_signature_scheme_block(
                    pairs[block_id], v3=v3)
                algorithm, signature = signer["signatures"][0]
                with self.subTest(block=hex(block_id)):
                    tampered = bytearray(raw)
                    offset = bytes(raw).find(signature)
                    self.assertGreater(offset, 0)
                    tampered[offset + 5] ^= 0xFF
                    with zipfile.ZipFile(signed) as archive:
                        with self.assertRaisesRegex(ValueError, "signature is not valid"):
                            check_v2_v3_signature(bytes(tampered), archive)

    def test_public_key_must_match_the_certificate(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            raw = bytearray(signed.read_bytes())
            pairs, _, _ = check_apk_compatibility.find_signing_block(bytes(raw))
            signer = check_apk_compatibility.parse_signature_scheme_block(
                pairs[check_apk_compatibility.V2_BLOCK_ID])
            # The same bytes are also inside the certificate, which the content
            # digest covers; the signer's copy is the last occurrence in the file.
            offset = bytes(raw).rfind(signer["public_key"])
            self.assertGreater(offset, 0)
            raw[offset + 4] ^= 0xFF
            with zipfile.ZipFile(signed) as archive:
                with self.assertRaisesRegex(ValueError, "public key"):
                    check_v2_v3_signature(bytes(raw), archive)

    def test_the_checked_in_apk_passes_every_check(self):
        check_apk(ROOT / "Authenticator.apk", CERTIFICATE)

    def test_certificate_must_match_the_repository(self):
        with tempfile.TemporaryDirectory() as directory:
            signed = self.make_signed_apk(directory)
            with tempfile.TemporaryDirectory() as scratch:
                other = Path(scratch) / "other.pem"
                subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048",
                                "-sha256", "-days", "1", "-nodes",
                                "-subj", "/CN=Somebody Else",
                                "-keyout", str(Path(scratch) / "key.pem"),
                                "-out", str(other)],
                               check=True, capture_output=True)
                with self.assertRaisesRegex(ValueError, "not signed with the repository"):
                    check_apk(signed, other)


if __name__ == "__main__":
    unittest.main()
