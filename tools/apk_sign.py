#!/usr/bin/env python3
"""Dependency-free APK signer for the Open Authenticator build.

This implements the three APK signature schemes that Android understands:

  * v1 (JAR signing)  -- required by Android 6.0, ignored by newer releases.
  * v2 (APK Signature Scheme v2) -- Android 7.0 and newer.
  * v3 (APK Signature Scheme v3) -- Android 9.0 and newer.

The point of having this in the repository is that a broken signature is
invisible to the packaging checks unless the signature is actually verified
against the file contents.  ``tests/check_apk_compatibility.py`` performs that
verification; this module produces the artifact it verifies.

Only the Python standard library is used.  The RSA private key is read from a
PKCS#8 PEM file and the certificate from an X.509 PEM file.

Usage:
    python3 tools/apk_sign.py --key tools/debug_key.pem \\
        --cert tools/debug_cert.pem --out Authenticator.apk input.apk
"""
import argparse
import base64
import hashlib
import struct
import sys
import zipfile
import zlib

# ---------------------------------------------------------------------------
# Minimal DER reader
# ---------------------------------------------------------------------------


def der_read(data, offset):
    """Read one DER TLV. Returns (tag, value, next_offset)."""
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
    """Yield (tag, value, full_bytes) for the children of a constructed value."""
    offset = 0
    while offset < len(data):
        start = offset
        tag, value, offset = der_read(data, offset)
        yield tag, value, data[start:offset]


def der_element(tag, value):
    """Encode one DER TLV."""
    if len(value) < 0x80:
        length = bytes([len(value)])
    else:
        raw = len(value).to_bytes((len(value).bit_length() + 7) // 8, "big")
        length = bytes([0x80 | len(raw)]) + raw
    return bytes([tag]) + length + value


def der_sequence(*parts):
    return der_element(0x30, b"".join(parts))


def der_set(*parts):
    return der_element(0x31, b"".join(parts))


def der_oid(dotted):
    parts = [int(p) for p in dotted.split(".")]
    body = bytearray([parts[0] * 40 + parts[1]])
    for value in parts[2:]:
        chunk = bytearray([value & 0x7F])
        value >>= 7
        while value:
            chunk.insert(0, 0x80 | (value & 0x7F))
            value >>= 7
        body += chunk
    return der_element(0x06, bytes(body))


def der_integer(value):
    raw = value.to_bytes(max(1, (value.bit_length() + 8) // 8), "big")
    return der_element(0x02, raw)


def der_octet_string(value):
    return der_element(0x04, value)


def der_null():
    return der_element(0x05, b"")


def der_explicit(number, value):
    return der_element(0xA0 | number, value)


# ---------------------------------------------------------------------------
# PEM / key / certificate handling
# ---------------------------------------------------------------------------


def pem_decode(text, label):
    """Return the DER bytes of the first PEM block with the given label."""
    begin = "-----BEGIN %s-----" % label
    end = "-----END %s-----" % label
    start = text.index(begin) + len(begin)
    stop = text.index(end, start)
    return base64.b64decode("".join(text[start:stop].split()))


def load_private_key(path):
    """Return (n, e, d) from a PKCS#8 PEM RSA private key."""
    with open(path, "r", encoding="ascii") as handle:
        der = pem_decode(handle.read(), "PRIVATE KEY")
    tag, value, _ = der_read(der, 0)
    if tag != 0x30:
        raise ValueError("private key is not a SEQUENCE")
    children = list(der_children(value))
    # PrivateKeyInfo ::= SEQUENCE { version, algorithm, privateKey OCTET STRING }
    inner = children[2][1]
    tag, rsa, _ = der_read(inner, 0)
    if tag != 0x30:
        raise ValueError("private key payload is not an RSAPrivateKey")
    numbers = [c[1] for c in der_children(rsa)]
    n = int.from_bytes(numbers[1], "big")
    e = int.from_bytes(numbers[2], "big")
    d = int.from_bytes(numbers[3], "big")
    return n, e, d


def load_certificate(path):
    """Return (der, issuer_der, serial_der, spki_der) from an X.509 PEM file."""
    with open(path, "r", encoding="ascii") as handle:
        der = pem_decode(handle.read(), "CERTIFICATE")
    tag, cert, _ = der_read(der, 0)
    children = list(der_children(cert))
    tbs = children[0][1]
    fields = list(der_children(tbs))
    index = 0
    if fields and fields[0][0] == 0xA0:
        index = 1  # explicit version
    serial_der = fields[index][2]
    issuer_der = fields[index + 2][2]
    spki_der = fields[index + 5][2]
    return der, issuer_der, serial_der, spki_der


SHA256_DIGEST_INFO = bytes.fromhex("3031300d060960864801650304020105000420")


def rsa_sign_sha256(n, d, message):
    """RSASSA-PKCS1-v1_5 signature over SHA-256(message)."""
    digest = hashlib.sha256(message).digest()
    encoded = SHA256_DIGEST_INFO + digest
    padding = b"\xff" * (0x100 - len(encoded) - 3)
    block = b"\x00\x01" + padding + b"\x00" + encoded
    value = pow(int.from_bytes(block, "big"), d, n)
    return value.to_bytes((n.bit_length() + 7) // 8, "big")


# ---------------------------------------------------------------------------
# PKCS#7 SignedData (the META-INF/*.RSA file)
# ---------------------------------------------------------------------------

OID_SIGNED_DATA = "1.2.840.113549.1.7.2"
OID_DATA = "1.2.840.113549.1.7.1"
OID_SHA256 = "2.16.840.1.101.3.4.2.1"
OID_RSA = "1.2.840.113549.1.1.1"


def build_pkcs7(cert_der, issuer_der, serial_der, signature, content):
    """Build a PKCS#7 SignedData blob over `content`."""
    digest_alg = der_sequence(der_oid(OID_SHA256), der_null())
    signed_data = der_sequence(
        der_integer(1),
        der_set(digest_alg),
        der_sequence(der_oid(OID_DATA)),
        der_element(0xA0, cert_der),
        der_set(der_sequence(
            der_integer(1),
            der_sequence(issuer_der, serial_der),
            digest_alg,
            der_sequence(der_oid(OID_RSA), der_null()),
            der_octet_string(signature),
        )),
    )
    return der_sequence(der_oid(OID_SIGNED_DATA), der_explicit(0, signed_data))


# ---------------------------------------------------------------------------
# ZIP reading / writing
# ---------------------------------------------------------------------------


class Entry:
    def __init__(self, name, method, crc, csize, usize, data, mtime, mdate, flags):
        self.name = name
        self.method = method
        self.crc = crc
        self.csize = csize
        self.usize = usize
        self.data = data          # stored (compressed) bytes
        self.mtime = mtime
        self.mdate = mdate
        self.flags = flags


def read_entries(path):
    """Read every entry of an APK, preserving the stored (compressed) bytes.

    The central directory is authoritative: local headers may be separated by
    alignment padding, so they cannot simply be walked in order.
    """
    entries = []
    with open(path, "rb") as handle:
        raw = handle.read()
    eocd = raw.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("%s is not a ZIP file" % path)
    cd_size, cd_offset = struct.unpack_from("<II", raw, eocd + 12)
    offset = cd_offset
    end = cd_offset + cd_size
    while offset < end and raw[offset:offset + 4] == b"PK\x01\x02":
        flags, method = struct.unpack_from("<HH", raw, offset + 8)
        mtime, mdate = struct.unpack_from("<HH", raw, offset + 12)
        crc, csize, usize = struct.unpack_from("<III", raw, offset + 16)
        nlen, elen, clen = struct.unpack_from("<HHH", raw, offset + 28)
        local_offset, = struct.unpack_from("<I", raw, offset + 42)
        name = raw[offset + 46:offset + 46 + nlen]
        offset += 46 + nlen + elen + clen
        if name.endswith(b"/"):
            continue
        header = raw[local_offset:local_offset + 30]
        if header[:4] != b"PK\x03\x04":
            raise ValueError("bad local header for %r" % name)
        lnlen, lelen = struct.unpack_from("<HH", header, 26)
        data = raw[local_offset + 30 + lnlen + lelen:
                   local_offset + 30 + lnlen + lelen + csize]
        entries.append(Entry(name, method, crc, csize, usize, data, mtime, mdate,
                             flags))
    return entries


def entry_content(entry):
    """Uncompressed content of an entry."""
    if entry.method == zipfile.ZIP_STORED:
        return entry.data
    return zlib.decompress(entry.data, -15)


def write_entries(entries, alignment=4):
    """Emit local headers plus data, aligned to `alignment` bytes.

    Returns (blob, records) where records describes each entry's local header
    offset so the central directory can be generated afterwards.
    """
    blob = bytearray()
    records = []
    for entry in entries:
        extra = b""
        if alignment:
            need = (-(len(blob) + 30 + len(entry.name))) % alignment
            if need >= 4:
                # The padding block zipalign uses (header id 0xd935).
                extra = struct.pack("<HH", 0xD935, need - 4) + b"\x00" * (need - 4)
            elif need:
                extra = b"\x00" * need
        # The sizes are always written into the local header, so a stale
        # "data descriptor" flag would make the archive unparseable.
        flags = entry.flags & ~0x08
        local_offset = len(blob)
        blob += struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, entry.method,
                            entry.mtime, entry.mdate, entry.crc, entry.csize,
                            entry.usize, len(entry.name), len(extra))
        blob += entry.name + extra + entry.data
        records.append((entry, local_offset, flags))
    return bytes(blob), records


def build_central_directory(records):
    blob = bytearray()
    for entry, local_offset, flags in records:
        blob += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, flags,
                            entry.method, entry.mtime, entry.mdate, entry.crc,
                            entry.csize, entry.usize, len(entry.name), 0, 0, 0, 0,
                            0, local_offset)
        blob += entry.name
    return bytes(blob)


def build_eocd(count, cd_size, cd_offset):
    return struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, count, count, cd_size,
                       cd_offset, 0)


# ---------------------------------------------------------------------------
# APK signature scheme v2 / v3
# ---------------------------------------------------------------------------

CHUNK_SIZE = 1024 * 1024
SIG_BLOCK_MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
V3_BLOCK_ID = 0xF05368C0
VERITY_PADDING_ID = 0x42726577
ALG_RSA_PKCS1_SHA256 = 0x0103
STRIPPING_PROTECTION_ATTR = 0xBEEFF00D


def chunk_digest(chunk, hasher):
    return hasher(b"\xa5" + struct.pack("<I", len(chunk)) + chunk).digest()


def top_level_digest(digests, hasher):
    return hasher(b"\x5a" + struct.pack("<I", len(digests)) + b"".join(digests)).digest()


def content_digest(prefix, central_directory, eocd, hasher):
    """The APK Signature Scheme v2/v3 'chunked' content digest.

    `prefix` is everything in the APK before the APK Signing Block,
    `central_directory` the CD bytes and `eocd` the end-of-central-directory
    record.  The CD offset inside the EOCD is replaced by the offset of the
    APK Signing Block, which is what the platform does as well.
    """
    digests = []
    offset = 0
    while offset < len(prefix):
        chunk = prefix[offset:offset + CHUNK_SIZE]
        if not chunk:
            break
        digests.append(chunk_digest(chunk, hasher))
        offset += len(chunk)
    if central_directory:
        digests.append(chunk_digest(central_directory, hasher))
    patched = bytearray(eocd)
    struct.pack_into("<I", patched, 16, len(prefix))
    digests.append(chunk_digest(bytes(patched), hasher))
    return top_level_digest(digests, hasher)


def length_prefixed(payload):
    return struct.pack("<I", len(payload)) + payload


def build_signed_data(digest, alg_id, cert_der, v3, min_sdk, max_sdk):
    """Build the 'signed data' content of a v2/v3 signer.

    The layout mirrors what apksigner emits, because the platform verifies the
    signature over exactly these bytes:

        v2: digests | certificates | stripping protection | (empty)
        v3: digests | certificates | minSdk | maxSdk | (empty)
    """
    digests = length_prefixed(
        length_prefixed(struct.pack("<I", alg_id) + length_prefixed(digest)))
    certificates = length_prefixed(length_prefixed(cert_der))
    content = digests + certificates
    if v3:
        content += struct.pack("<II", min_sdk, max_sdk)
    else:
        protection = struct.pack("<I", STRIPPING_PROTECTION_ATTR) + b"\x03\x00\x00\x00"
        content += length_prefixed(length_prefixed(protection))
    content += length_prefixed(b"")
    return content


def build_signer(signed_data, signature, cert_der, spki_der, v3, min_sdk, max_sdk,
                 alg_id):
    """Build one length-prefixed signer of a v2/v3 signature scheme block."""
    signatures = length_prefixed(
        length_prefixed(struct.pack("<I", alg_id) + length_prefixed(signature)))
    body = length_prefixed(signed_data)
    if v3:
        body += struct.pack("<II", min_sdk, max_sdk)
    body += signatures
    body += length_prefixed(spki_der)
    return length_prefixed(body)


def build_signing_block(pairs, padding_to=4096):
    """Build an APK Signing Block containing the given (id, value) pairs."""
    body = b""
    for block_id, value in pairs:
        body += struct.pack("<Q", len(value) + 4) + struct.pack("<I", block_id) + value
    size = len(body) + 8 + len(SIG_BLOCK_MAGIC)
    if padding_to:
        # apksigner pads the block so that it starts on a 4096 byte boundary.
        padded = size + 8 + len(SIG_BLOCK_MAGIC)
        padding = (-padded) % padding_to
        if padding:
            body += struct.pack("<Q", padding + 4) + struct.pack("<I", VERITY_PADDING_ID) \
                + b"\x00" * padding
            size = len(body) + 8 + len(SIG_BLOCK_MAGIC)
    return struct.pack("<Q", size) + body + struct.pack("<Q", size) + SIG_BLOCK_MAGIC


# ---------------------------------------------------------------------------
# v1 (JAR) signature
# ---------------------------------------------------------------------------


def manifest_section(name, digest):
    return ("Name: %s\r\nSHA-256-Digest: %s\r\n\r\n"
            % (name, base64.b64encode(digest).decode("ascii")))


def build_v1_manifest(entries, digests):
    parts = ["Manifest-Version: 1.0\r\n", "Created-By: 1.0 (Android)\r\n\r\n"]
    for entry in entries:
        parts.append(manifest_section(entry.name.decode("utf-8"), digests[entry.name]))
    return "".join(parts).encode("utf-8")


def build_v1_signature_file(manifest_bytes, sections):
    lines = ["Signature-Version: 1.0\r\n",
             "Created-By: 1.0 (Android)\r\n",
             "SHA-256-Digest-Manifest: %s\r\n"
             % base64.b64encode(hashlib.sha256(manifest_bytes).digest()).decode("ascii"),
             "X-Android-APK-Signed: 2, 3\r\n\r\n"]
    for name, section in sections:
        lines.append("Name: %s\r\nSHA-256-Digest: %s\r\n\r\n"
                     % (name, base64.b64encode(hashlib.sha256(section).digest())
                        .decode("ascii")))
    return "".join(lines).encode("utf-8")


def split_manifest_sections(manifest_bytes):
    """Split MANIFEST.MF into (name, raw_section_bytes) pairs."""
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


# ---------------------------------------------------------------------------
# Top level
# ---------------------------------------------------------------------------


def sign_apk(input_path, output_path, key_path, cert_path,
             min_sdk=23, target_sdk=35, v2=True, v3=True, v1=True):
    entries = read_entries(input_path)
    payload = [e for e in entries if not e.name.startswith(b"META-INF/")]

    digests = {}
    for entry in payload:
        digests[entry.name] = hashlib.sha256(entry_content(entry)).digest()

    n, e, d = load_private_key(key_path)
    cert_der, issuer_der, serial_der, spki_der = load_certificate(cert_path)

    # --- v1 ---------------------------------------------------------------
    v1_files = []
    if v1:
        manifest_bytes = build_v1_manifest(payload, digests)
        sections = split_manifest_sections(manifest_bytes)
        signature_file = build_v1_signature_file(manifest_bytes, sections)
        signature = rsa_sign_sha256(n, d, signature_file)
        pkcs7 = build_pkcs7(cert_der, issuer_der, serial_der, signature, signature_file)
        v1_files = [
            Entry(b"META-INF/MANIFEST.MF", zipfile.ZIP_STORED, 0, 0, 0,
                  manifest_bytes, 0, 0x21, 0),
            Entry(b"META-INF/CERT.SF", zipfile.ZIP_STORED, 0, 0, 0,
                  signature_file, 0, 0x21, 0),
            Entry(b"META-INF/CERT.RSA", zipfile.ZIP_STORED, 0, 0, 0,
                  pkcs7, 0, 0x21, 0),
        ]
        for entry in v1_files:
            entry.crc = zipfile.crc32(entry.data) & 0xFFFFFFFF
            entry.csize = len(entry.data)
            entry.usize = len(entry.data)

    all_entries = payload + v1_files
    prefix, records = write_entries(all_entries)
    central_directory = build_central_directory(records)

    # The EOCD is hashed with its CD offset replaced by the offset of the
    # APK Signing Block, so the real offset does not matter here.
    placeholder_eocd = build_eocd(len(all_entries), len(central_directory),
                                  len(prefix))

    hasher = hashlib.sha256
    digest = content_digest(prefix, central_directory, placeholder_eocd, hasher)
    alg_id = ALG_RSA_PKCS1_SHA256

    pairs = []
    if v2:
        signed_data = build_signed_data(digest, alg_id, cert_der, False, 0, 0)
        signature = rsa_sign_sha256(n, d, signed_data)
        signer = build_signer(signed_data, signature, cert_der, spki_der, False,
                              0, 0, alg_id)
        pairs.append((V2_BLOCK_ID, length_prefixed(signer)))
    if v3:
        signed_data = build_signed_data(digest, alg_id, cert_der, True,
                                        min_sdk, 0x7FFFFFFF)
        signature = rsa_sign_sha256(n, d, signed_data)
        signer = build_signer(signed_data, signature, cert_der, spki_der, True,
                              min_sdk, 0x7FFFFFFF, alg_id)
        pairs.append((V3_BLOCK_ID, length_prefixed(signer)))

    block = build_signing_block(pairs)
    cd_offset = len(prefix) + len(block)
    eocd = build_eocd(len(all_entries), len(central_directory), cd_offset)

    with open(output_path, "wb") as handle:
        handle.write(prefix)
        handle.write(block)
        handle.write(central_directory)
        handle.write(eocd)
    return output_path


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input")
    parser.add_argument("--key", required=True, help="PKCS#8 PEM private key")
    parser.add_argument("--cert", required=True, help="X.509 PEM certificate")
    parser.add_argument("--out", required=True)
    parser.add_argument("--min-sdk", type=int, default=23)
    parser.add_argument("--no-v1", action="store_true")
    parser.add_argument("--no-v2", action="store_true")
    parser.add_argument("--no-v3", action="store_true")
    args = parser.parse_args(argv)
    sign_apk(args.input, args.out, args.key, args.cert,
             min_sdk=args.min_sdk, v1=not args.no_v1, v2=not args.no_v2,
             v3=not args.no_v3)
    print("signed: %s" % args.out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
