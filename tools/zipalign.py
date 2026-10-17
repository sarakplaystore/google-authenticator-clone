#!/usr/bin/env python3
"""A dependency-free, Linux/macOS zipalign for the manual APK build.

The Android SDK ships ``zipalign`` as a native executable, which is not always
available (and is not installable in every CI sandbox).  Android requires the
uncompressed entries of an APK -- ``resources.arsc`` and ``classes.dex`` in
particular -- to start on a 4-byte boundary, so this script implements the two
operations ``build.sh`` needs:

    zipalign.py -f 4 unsigned.apk aligned.apk   # rewrite with padding
    zipalign.py -c 4 Authenticator.apk          # verify, exit 1 if unaligned

It is not a byte-for-byte clone of the SDK tool: it preserves each entry's
compression method and metadata, and it inserts the standard ZIP extra field
(header id 0xD935) used by zipalign.  Shared-library page alignment (``-p``)
is not implemented because this project ships no native libraries.

Only the Python standard library is used.
"""

import struct
import sys
import zipfile

ALIGNMENT_EXTRA_ID = 0xD935


def _name_bytes(name):
    return name.encode("utf-8")


def align(source, destination, alignment):
    """Rewrite ``source`` so every entry's payload starts on a boundary."""
    with zipfile.ZipFile(source, "r") as archive:
        entries = [(info, archive.read(info.filename))
                   for info in archive.infolist()]
        comment = archive.comment

    with zipfile.ZipFile(destination, "w") as archive:
        archive.comment = comment
        for info, data in entries:
            target = zipfile.ZipInfo(info.filename, info.date_time)
            target.compress_type = info.compress_type
            target.external_attr = info.external_attr
            target.internal_attr = info.internal_attr
            target.create_system = info.create_system
            target.comment = info.comment

            extra = info.extra
            header_at = archive.fp.tell()
            data_at = header_at + 30 + len(_name_bytes(info.filename)) + len(extra)
            pad = (-data_at) % alignment
            if pad >= 4:
                # zipalign's own padding field: just enough payload to land on
                # the requested boundary.  The 4-byte header is part of it.
                extra += struct.pack("<HH", ALIGNMENT_EXTRA_ID, pad - 4)
                extra += b"\x00" * (pad - 4)
            elif pad:
                # Less than one extra-field header fits; zero bytes are read
                # as a zero-length field and remain valid ZIP.
                extra += b"\x00" * pad
            target.extra = extra
            archive.writestr(target, data)
    return destination


def entry_data_offset(raw, info):
    """Return the offset of an entry's payload, read from its local header."""
    header = raw[info.header_offset:info.header_offset + 30]
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("invalid local header for %s" % info.filename)
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def check(source, alignment):
    """Return a list of (entry name, offset) whose payloads are unaligned."""
    with open(source, "rb") as handle:
        raw = handle.read()
    with zipfile.ZipFile(source, "r") as archive:
        bad = []
        for info in archive.infolist():
            offset = entry_data_offset(raw, info)
            if offset % alignment:
                bad.append((info.filename, offset))
        return bad


def main(argv):
    args = list(argv[1:])
    force = False
    verify = False
    verbose = False
    while args and args[0].startswith("-") and args[0] != "-":
        option = args.pop(0)
        if option == "-f":
            force = True
        elif option == "-c":
            verify = True
        elif option == "-v":
            verbose = True
        else:
            sys.stderr.write("zipalign.py: unknown option %s\n" % option)
            return 2
    if len(args) < 2:
        sys.stderr.write("usage: zipalign.py [-f] [-c] [-v] <alignment> "
                         "<infile> [<outfile>]\n")
        return 2
    try:
        alignment = int(args.pop(0))
    except ValueError:
        sys.stderr.write("zipalign.py: alignment must be an integer\n")
        return 2
    if alignment <= 0:
        sys.stderr.write("zipalign.py: alignment must be positive\n")
        return 2
    source = args.pop(0)

    if verify:
        if args:
            sys.stderr.write("zipalign.py: -c does not take an output file\n")
            return 2
        bad = check(source, alignment)
        for name, offset in bad:
            sys.stderr.write("%s: %s is not %d-byte aligned (offset %d)\n"
                             % (source, name, alignment, offset))
        if bad:
            return 1
        if verbose:
            print("Verification successful")
        return 0

    if not args:
        sys.stderr.write("zipalign.py: an output file is required\n")
        return 2
    destination = args.pop(0)
    if args:
        sys.stderr.write("zipalign.py: too many arguments\n")
        return 2
    if not force:
        try:
            with open(destination, "rb"):
                pass
        except FileNotFoundError:
            pass
        else:
            sys.stderr.write("zipalign.py: %s already exists; use -f to overwrite\n"
                             % destination)
            return 1
    align(source, destination, alignment)
    bad = check(destination, alignment)
    if bad:
        sys.stderr.write("zipalign.py: failed to align %s\n" % destination)
        return 1
    if verbose:
        print("Alignment successful")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
