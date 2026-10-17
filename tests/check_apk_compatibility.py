#!/usr/bin/env python3
"""Dependency-free APK packaging and signature checks.

Usage: python3 tests/check_apk_compatibility.py [Authenticator.apk]

The signature checks matter as much as the packaging ones.  An APK whose
v2/v3 signature no longer matches its own contents is rejected by every
Android release from 7.0 onwards with INSTALL_PARSE_FAILED_NO_CERTIFICATES,
while its v1 (JAR) signature can still look perfectly fine - so a packaging
check that only looks for the presence of META-INF/CERT.RSA will happily
pass an APK that no device will install.

This module does not replace ``apksigner verify``; it reproduces the parts of
the platform's verification that can be done with the standard library only,
including the RSA signature over each v2/v3 signed-data block.

Checking the content digest alone is not enough: the signed APK that shipped
with this project carried v2/v3 signature blocks whose RSA signature was
perfectly valid but whose content digest no longer matched the file, which is
why no Android 7.0+ device would install it while every packaging check passed.
Verifying the signature bytes as well catches the opposite failure - a block
that quotes the right digest but is not actually signed by the certificate it
presents.
"""
import base64
import hashlib
import struct
import sys
import zipfile
from pathlib import Path

SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
V3_BLOCK_ID = 0xF05368C0
CHUNK_SIZE = 1024 * 1024

# Content digest algorithm of each v2/v3 "chunked" signature algorithm id.
# 0x0101/0x0102 are RSASSA-PSS, 0x0103/0x0104 RSASSA-PKCS1-v1_5, 0x020x ECDSA
# and 0x0301 DSA; the first two digest the APK in 1 MB chunks with SHA-256 or
# SHA-512.  There is no SHA-384 content digest in any APK signature scheme.
CONTENT_DIGEST_ALGORITHMS = {
    0x0101: "sha256",
    0x0102: "sha512",
    0x0103: "sha256",
    0x0104: "sha512",
    0x0201: "sha256",
    0x0202: "sha512",
    0x0301: "sha256",
}

# Signature algorithm ids whose content digest is the fs-verity style digest
# (4 KB blocks, salted) rather than the 1 MB chunked digest above.  The chunked
# digest is present alongside them whenever apksigner emits them, so these are
# skipped here instead of being checked with the wrong construction.
VERITY_DIGEST_ALGORITHMS = {0x0421, 0x0423, 0x0425}

# id -> (hash used by the signature, mechanism)
SIGNATURE_ALGORITHMS = {
    0x0101: ("sha256", "pss"),
    0x0102: ("sha512", "pss"),
    0x0103: ("sha256", "pkcs1"),
    0x0104: ("sha512", "pkcs1"),
    0x0201: ("sha256", "ecdsa"),
    0x0202: ("sha512", "ecdsa"),
    0x0301: ("sha256", "dsa"),
    0x0421: ("sha256", "pkcs1"),
    0x0423: ("sha256", "ecdsa"),
    0x0425: ("sha256", "dsa"),
}

# DigestInfo prefixes of the hash algorithms used above (RFC 8017, section 9.2).
DIGEST_INFO_PREFIX = {
    "sha1": bytes.fromhex("3021300906052b0e03021a05000414"),
    "sha256": bytes.fromhex("3031300d060960864801650304020105000420"),
    "sha512": bytes.fromhex("3051300d060960864801650304020305000440"),
}

RSA_ENCRYPTION_OID = bytes.fromhex("06092a864886f70d010101")


# ---------------------------------------------------------------------------
# ZIP helpers
# ---------------------------------------------------------------------------


def central_directory_entries(raw):
    """Return the central directory entries of a ZIP as dictionaries."""
    eocd = raw.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a ZIP file")
    cd_size, cd_offset = struct.unpack_from("<II", raw, eocd + 12)
    entries = []
    offset = cd_offset
    end = cd_offset + cd_size
    while offset < end and raw[offset:offset + 4] == b"PK\x01\x02":
        nlen, elen, clen = struct.unpack_from("<HHH", raw, offset + 28)
        local_offset, = struct.unpack_from("<I", raw, offset + 42)
        name = raw[offset + 46:offset + 46 + nlen]
        entries.append({"name": name, "local_offset": local_offset})
        offset += 46 + nlen + elen + clen
    return entries


def entry_data_offset(raw, local_offset):
    """Offset of an entry's payload inside the file."""
    nlen, elen = struct.unpack_from("<HH", raw, local_offset + 26)
    return local_offset + 30 + nlen + elen


# ---------------------------------------------------------------------------
# APK Signing Block (v2 / v3)
# ---------------------------------------------------------------------------


def length_prefixed(data, offset):
    length, = struct.unpack_from("<I", data, offset)
    return data[offset + 4:offset + 4 + length], offset + 4 + length


def find_signing_block(raw):
    """Return {block id: value} of the APK Signing Block, or None."""
    eocd = raw.rfind(b"PK\x05\x06")
    cd_size, cd_offset = struct.unpack_from("<II", raw, eocd + 12)
    position = raw.rfind(SIG_BLOCK_MAGIC, 0, cd_offset)
    if position < 0:
        return None, None, None
    size, = struct.unpack_from("<Q", raw, position - 8)
    start = position + 16 - size - 8
    pairs = {}
    offset = start + 8
    while offset < position - 8:
        pair_length, = struct.unpack_from("<Q", raw, offset)
        block_id, = struct.unpack_from("<I", raw, offset + 8)
        pairs[block_id] = raw[offset + 12:offset + 8 + pair_length]
        offset += 8 + pair_length
    return pairs, start, position + 16 - start


def signed_data_digests(block):
    """Return {algorithm id: digest} from a v2/v3 signature scheme block."""
    return parse_signature_scheme_block(block)["digests"]


def parse_signature_scheme_block(block, v3=False):
    """Read one APK Signature Scheme v2/v3 block.

    signer = signed_data, [minSdk, maxSdk], signatures, public_key
    signed_data = digests, certificates, [minSdk, maxSdk], attributes

    The SDK range is present in the v3 layout only, which is why the block id
    decides how the signer is read.
    """
    signers, _ = length_prefixed(block, 0)
    signer, _ = length_prefixed(signers, 0)
    signed_data, offset = length_prefixed(signer, 0)
    if v3:
        offset += 8
    signatures_section, offset = length_prefixed(signer, offset)
    public_key, _ = length_prefixed(signer, offset)

    digests_section, position = length_prefixed(signed_data, 0)
    digests = {}
    cursor = 0
    while cursor < len(digests_section):
        entry, cursor = length_prefixed(digests_section, cursor)
        algorithm, = struct.unpack_from("<I", entry, 0)
        digest, _ = length_prefixed(entry, 4)
        digests[algorithm] = digest

    certificates_section, position = length_prefixed(signed_data, position)
    if v3:
        position += 8
    certificates = []
    cursor = 0
    while cursor < len(certificates_section):
        certificate, cursor = length_prefixed(certificates_section, cursor)
        certificates.append(certificate)

    signatures = []
    cursor = 0
    while cursor < len(signatures_section):
        entry, cursor = length_prefixed(signatures_section, cursor)
        algorithm, = struct.unpack_from("<I", entry, 0)
        signature, _ = length_prefixed(entry, 4)
        signatures.append((algorithm, signature))

    return {
        "signed_data": signed_data,
        "digests": digests,
        "certificates": certificates,
        "signatures": signatures,
        "public_key": public_key,
    }


# ---------------------------------------------------------------------------
# RSA verification (PKCS#1 v1.5), using nothing but the standard library
# ---------------------------------------------------------------------------


def find_subject_public_key_info(blob):
    """Return (encoded SubjectPublicKeyInfo, BIT STRING element) of the first RSA key."""
    def walk(data):
        try:
            children = list(der_children(data))
        except (IndexError, struct.error, ValueError):
            return None
        for tag, value, encoded in children:
            if tag != 0x30:
                continue
            try:
                inner = list(der_children(value))
            except (IndexError, struct.error, ValueError):
                continue
            if (len(inner) == 2 and inner[0][0] == 0x30 and inner[1][0] == 0x03
                    and RSA_ENCRYPTION_OID in inner[0][2]):
                return encoded, inner[1]
            found = walk(value)
            if found is not None:
                return found
        return None

    return walk(blob)


def rsa_public_key(bit_string):
    """Return (modulus, exponent) of the RSA key inside a SubjectPublicKeyInfo BIT STRING."""
    content = bit_string[1]
    # BIT STRING content: one "unused bits" byte, then the RSAPublicKey sequence.
    children = list(der_children(content[1:]))
    if not children or children[0][0] != 0x30:
        raise ValueError("unsupported RSA public key encoding")
    numbers = list(der_children(children[0][1]))
    if len(numbers) < 2 or numbers[0][0] != 0x02 or numbers[1][0] != 0x02:
        raise ValueError("unsupported RSA public key encoding")
    return (int.from_bytes(numbers[0][1], "big"),
            int.from_bytes(numbers[1][1], "big"))


def rsa_verify_pkcs1_v15(public_key, signature, message, hash_name):
    """Verify an RSASSA-PKCS1-v1_5 signature; raise ValueError when it is wrong."""
    modulus, exponent = public_key
    size = (modulus.bit_length() + 7) // 8
    if len(signature) != size:
        raise ValueError("the signature is %d bytes, expected %d"
                         % (len(signature), size))
    value = pow(int.from_bytes(signature, "big"), exponent, modulus)
    encoded = value.to_bytes(size, "big")
    if encoded[0] != 0x00 or encoded[1] != 0x01:
        raise ValueError("the signature is not a valid PKCS#1 v1.5 block")
    separator = encoded.find(b"\x00", 2)
    if separator < 10 or any(byte != 0xFF for byte in encoded[2:separator]):
        raise ValueError("the signature padding is wrong")
    prefix = DIGEST_INFO_PREFIX.get(hash_name)
    if prefix is None:
        raise ValueError("unsupported signature hash " + hash_name)
    expected = prefix + hashlib.new(hash_name, message).digest()
    if encoded[separator + 1:] != expected:
        raise ValueError("the signed data does not match the signature")


def chunk_digest(chunk, hasher):
    return hasher(b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest()


def content_digest(raw, signing_block_start, cd_offset, eocd_offset, hasher):
    """The APK Signature Scheme v2/v3 'chunked' content digest."""
    digests = []
    offset = 0
    while offset < signing_block_start:
        chunk = raw[offset:min(offset + CHUNK_SIZE, signing_block_start)]
        if not chunk:
            break
        digests.append(chunk_digest(chunk, hasher))
        offset += len(chunk)
    central_directory = raw[cd_offset:eocd_offset]
    if central_directory:
        digests.append(chunk_digest(central_directory, hasher))
    eocd = bytearray(raw[eocd_offset:])
    # The platform replaces the central directory offset with the offset of
    # the APK Signing Block before hashing the end of central directory record.
    struct.pack_into("<I", eocd, 16, signing_block_start)
    digests.append(chunk_digest(bytes(eocd), hasher))
    return hasher(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


# ---------------------------------------------------------------------------
# v1 (JAR) signature
# ---------------------------------------------------------------------------


def manifest_sections(manifest_bytes):
    """Split MANIFEST.MF into (name, raw section bytes) pairs."""
    sections = []
    for chunk in manifest_bytes.split(b"\r\n\r\n"):
        if not chunk.strip():
            continue
        name = None
        for line in chunk.split(b"\r\n"):
            if line.startswith(b"Name: "):
                name = line[6:].decode("utf-8")
                break
        if name is not None:
            sections.append((name, chunk + b"\r\n\r\n"))
    return sections


def manifest_attributes(section_bytes):
    """Return the attributes of one MANIFEST.MF section."""
    attributes = {}
    for line in section_bytes.split(b"\r\n"):
        if b": " in line:
            key, value = line.split(b": ", 1)
            attributes[key.decode("utf-8")] = value.strip().decode("utf-8")
    return attributes


# ---------------------------------------------------------------------------
# Certificates
# ---------------------------------------------------------------------------


def read_der_tlv(data, offset):
    tag = data[offset]
    offset += 1
    length = data[offset]
    offset += 1
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    return tag, data[offset:offset + length], offset + length


def der_children(data):
    offset = 0
    while offset < len(data):
        start = offset
        tag, value, offset = read_der_tlv(data, offset)
        yield tag, value, data[start:offset]


def find_certificates(blob):
    """Return every X.509 certificate found in a DER blob."""
    certificates = []

    def walk(data):
        try:
            children = list(der_children(data))
        except (IndexError, struct.error, ValueError):
            return
        for tag, value, encoded in children:
            if tag != 0x30:
                if tag & 0x20:
                    walk(value)
                continue
            try:
                inner = list(der_children(value))
            except (IndexError, struct.error, ValueError):
                continue
            # Certificate ::= SEQUENCE { tbsCertificate, algorithm, signature }
            if len(inner) == 3 and inner[0][0] == 0x30 and inner[2][0] == 0x03:
                certificates.append(encoded)
                continue
            walk(value)

    walk(blob)
    return certificates


def certificate_fingerprint(der):
    return hashlib.sha256(der).hexdigest()


def pem_der(path, label):
    text = Path(path).read_text(encoding="ascii")
    begin = "-----BEGIN %s-----" % label
    end = "-----END %s-----" % label
    start = text.index(begin) + len(begin)
    stop = text.index(end, start)
    return base64.b64decode("".join(text[start:stop].split()))


# ---------------------------------------------------------------------------
# The checks
# ---------------------------------------------------------------------------


def check_apk(path, expected_certificate=None):
    """Raise ValueError when the APK would be rejected or is inconsistent."""
    with open(path, "rb") as handle:
        content = handle.read()
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        for name in ("AndroidManifest.xml", "resources.arsc", "classes.dex"):
            if name not in names:
                raise ValueError("Missing " + name)
        # Android 6 supports DEX 035, not the 037+ formats used by newer APIs.
        dex_names = sorted(n for n in names if n.startswith("classes") and n.endswith(".dex"))
        for name in dex_names:
            if archive.read(name)[:8] != b"dex\n035\0":
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
        table = archive.getinfo("resources.arsc")
        if table.compress_type != zipfile.ZIP_STORED:
            raise ValueError("resources.arsc must be uncompressed")
        name_length, extra_length = struct.unpack_from("<HH", content, table.header_offset + 26)
        offset = table.header_offset + 30 + name_length + extra_length
        if offset % 4:
            raise ValueError("resources.arsc must be 4-byte aligned")
        corrupt = archive.testzip()
        if corrupt:
            raise ValueError("Corrupt APK entry: " + corrupt)

        check_alignment(content)
        check_v1_signature(archive, names)
        check_v2_v3_signature(content, archive)
        if expected_certificate is not None:
            check_signer_certificate(archive, names, expected_certificate)


def check_alignment(raw):
    """Every entry payload must start on a 4-byte boundary (zipalign -c 4)."""
    for entry in central_directory_entries(raw):
        offset = entry_data_offset(raw, entry["local_offset"])
        if offset % 4:
            raise ValueError("%s is not 4-byte aligned; run zipalign"
                             % entry["name"].decode("utf-8", "replace"))


def check_v1_signature(archive, names):
    """The v1 signature must cover the actual contents of every entry."""
    manifest = archive.read("META-INF/MANIFEST.MF")
    sections = manifest_sections(manifest)
    covered = set()
    for name, section in sections:
        attributes = manifest_attributes(section)
        digest = attributes.get("SHA-256-Digest")
        if digest is None:
            raise ValueError("MANIFEST.MF section for %s has no SHA-256 digest" % name)
        if name not in names:
            raise ValueError("MANIFEST.MF covers a missing entry: " + name)
        want = base64.b64decode(digest)
        got = hashlib.sha256(archive.read(name)).digest()
        if want != got:
            raise ValueError("v1 signature does not match the contents of " + name)
        covered.add(name)
    missing = sorted(n for n in names if not n.startswith("META-INF/") and n not in covered)
    if missing:
        raise ValueError("v1 signature does not cover " + missing[0])


def check_v2_v3_signature(raw, archive):
    """The v2/v3 signature must match the file that carries it.

    Two things are checked for every v2/v3 block: the content digest quoted in
    the signed data must be the digest of this file, and the signature bytes
    must be a valid signature by the certificate in the block over that signed
    data.  A block that passes one and not the other is exactly the shape of
    the broken artifact this project shipped once.
    """
    pairs, block_start, _ = find_signing_block(raw)
    if not pairs:
        raise ValueError("Missing APK Signing Block; Android 7.0+ cannot verify the APK")
    eocd_offset = raw.rfind(b"PK\x05\x06")
    cd_size, cd_offset = struct.unpack_from("<II", raw, eocd_offset + 12)
    found = False
    for block_id, v3 in ((V2_BLOCK_ID, False), (V3_BLOCK_ID, True)):
        if block_id not in pairs:
            continue
        found = True
        signer = parse_signature_scheme_block(pairs[block_id], v3=v3)
        scheme = "v3" if v3 else "v2"

        for algorithm, expected in signer["digests"].items():
            if algorithm in VERITY_DIGEST_ALGORITHMS:
                # fs-verity style digest; the chunked digest is checked too
                # whenever apksigner emits one of these.
                continue
            name = CONTENT_DIGEST_ALGORITHMS.get(algorithm)
            if name is None:
                raise ValueError("Unknown content digest algorithm 0x%04x" % algorithm)
            hasher = getattr(hashlib, name)
            got = content_digest(raw, block_start, cd_offset, eocd_offset, hasher)
            if got != expected:
                raise ValueError(
                    "The %s %s signature does not match the contents of this APK; "
                    "Android 7.0 and newer will refuse to install it" % (scheme, name))

        if not signer["certificates"]:
            raise ValueError("The %s signature block carries no certificate" % scheme)
        certificate = signer["certificates"][0]
        key_info = find_subject_public_key_info(certificate)
        if key_info is None:
            raise ValueError("The %s signing certificate has no RSA public key" % scheme)
        spki_encoded, bit_string = key_info
        if spki_encoded != signer["public_key"]:
            raise ValueError(
                "The public key of the %s signature does not match its certificate" % scheme)

        verified = False
        for algorithm, signature in signer["signatures"]:
            algorithm_info = SIGNATURE_ALGORITHMS.get(algorithm)
            if algorithm_info is None:
                raise ValueError("Unknown %s signature algorithm 0x%04x" % (scheme, algorithm))
            hash_name, mechanism = algorithm_info
            if mechanism != "pkcs1":
                # PSS, ECDSA and DSA signatures are not reproduced here; the
                # content digest and certificate checks above still apply.
                continue
            try:
                rsa_verify_pkcs1_v15(rsa_public_key(bit_string), signature,
                                     signer["signed_data"], hash_name)
            except ValueError as error:
                raise ValueError("The %s signature is not valid: %s" % (scheme, error))
            verified = True
        if not verified:
            raise ValueError("The %s signature block contains no verifiable signature" % scheme)
    if not found:
        raise ValueError("Missing v2/v3 signature block")


def check_signer_certificate(archive, names, expected_certificate):
    """The APK must be signed with the repository's signing certificate."""
    expected = pem_der(expected_certificate, "CERTIFICATE")
    expected_fingerprint = certificate_fingerprint(expected)
    candidates = []
    for name in names:
        if name.upper().endswith((".RSA", ".DSA", ".EC")):
            candidates.extend(find_certificates(archive.read(name)))
    for certificate in candidates:
        if certificate_fingerprint(certificate) == expected_fingerprint:
            return
    raise ValueError(
        "The APK is not signed with the repository's signing certificate; "
        "Android will refuse to update an existing installation")


if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "Authenticator.apk"
    certificate = Path(__file__).resolve().parents[1] / "tools" / "debug_cert.pem"
    try:
        check_apk(path, certificate if certificate.exists() else None)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit("APK compatibility check failed: " + str(error))
    print("APK checks passed (DEX 035, aligned, v1 coverage, v2/v3 content digest "
          "and signature, signing certificate).")
