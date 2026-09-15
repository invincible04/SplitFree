#!/usr/bin/env python3
"""Check the two manifest-discovered ML Kit registrars in compiled APKs.

This checks DEX definitions and constructor code presence, not bytecode validity,
linking, APK signatures, or whether scanning works on a device.
"""
import argparse
import io
import re
import struct
import zipfile
import zlib

from artifacts import ReleaseError, read_apk_bytes, require


REGISTRARS = (
    "com.google.mlkit.common.internal.CommonComponentRegistrar",
    "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
)
DESCRIPTORS = {("L" + name.replace(".", "/") + ";").encode(): name for name in REGISTRARS}
MAX_DEX_BYTES = 64 * 1024 * 1024
MAX_TOTAL_DEX_BYTES = 256 * 1024 * 1024
MAX_DEX_FILES = 32
MAX_TABLE_ITEMS = 1_000_000
MAX_STRING_BYTES = 65536
PUBLIC = 0x1
PRIVATE_OR_PROTECTED = 0x6
STATIC = 0x8
NATIVE = 0x100
INTERFACE = 0x200
ABSTRACT = 0x400
CONSTRUCTOR = 0x10000


class Dex:
    def __init__(self, data):
        self.data = data
        require(112 <= len(data) <= MAX_DEX_BYTES, "DEX size outside limits")
        require(data[:8] in {b"dex\n" + version + b"\0" for version in
                            (b"035", b"037", b"038", b"039", b"040")}, "Unsupported DEX magic/version")
        require(self.u32(32) == len(data) and self.u32(36) == 112, "Invalid DEX file/header size")
        require(self.u32(40) == 0x12345678, "Unsupported DEX endian tag")
        self.data_start = self.u32(108)
        require(self.data_start >= 112 and self.data_start % 4 == 0
                and self.u32(104) == len(data) - self.data_start, "Invalid DEX data bounds")
        self.tables = {}
        end = 112
        for name, header, width in (("string", 56, 4), ("type", 64, 4), ("proto", 72, 12),
                                    ("field", 80, 8), ("method", 88, 8), ("class", 96, 32)):
            count, offset = self.unpack("II", header)
            require(count <= MAX_TABLE_ITEMS, f"DEX {name} count exceeds limit")
            if count:
                require(offset % 4 == 0 and offset >= end
                        and count * width <= self.data_start - offset, f"Invalid DEX {name} table bounds")
                end = offset + count * width
            else:
                require(offset == 0, f"Empty DEX {name} table has offset")
            self.tables[name] = (count, offset, width)
        self.strings = {}

    def bounds(self, offset, size, data=False):
        require(offset >= (self.data_start if data else 0) and size >= 0
                and offset <= len(self.data) - size, "DEX offset/size out of bounds")

    def unpack(self, format, offset):
        self.bounds(offset, struct.calcsize("<" + format))
        return struct.unpack_from("<" + format, self.data, offset)

    def u32(self, offset):
        return self.unpack("I", offset)[0]

    def item(self, table, index):
        count, offset, width = self.tables[table]
        require(0 <= index < count, f"DEX {table} index out of bounds")
        return offset + index * width

    def uleb(self, offset):
        value = 0
        for shift in range(0, 35, 7):
            self.bounds(offset, 1, data=True)
            byte = self.data[offset]
            offset += 1
            require(shift != 28 or byte <= 0x0f, "Invalid DEX ULEB128 overflow")
            value |= (byte & 0x7f) << shift
            if not byte & 0x80:
                require(shift == 0 or byte != 0, "Noncanonical DEX ULEB128")
                return value, offset
        raise ReleaseError("Unterminated DEX ULEB128")

    def string(self, index):
        if index not in self.strings:
            offset = self.u32(self.item("string", index))
            length, offset = self.uleb(offset)
            end = self.data.find(b"\0", offset, min(len(self.data), offset + MAX_STRING_BYTES + 1))
            require(end >= 0, "Unterminated or oversized DEX string")
            raw = self.data[offset:end]
            # DEX uses modified UTF-8: encoded NUL and UTF-16 surrogate pairs are legal.
            try:
                decoded = raw.replace(b"\xc0\x80", b"\0").decode("utf-8", errors="surrogatepass")
            except UnicodeError as error:
                raise ReleaseError("Invalid DEX string encoding") from error
            require(all(ord(char) <= 0xffff for char in decoded) and len(decoded) == length,
                    "Invalid DEX string UTF-16 length/encoding")
            self.strings[index] = raw
        return self.strings[index]

    def type(self, index):
        return self.string(self.u32(self.item("type", index)))

    def noarg_void(self, index):
        shorty, result, parameters = self.unpack("III", self.item("proto", index))
        shorty = self.string(shorty)
        result = self.type(result)
        count = 0
        if parameters:
            require(parameters % 4 == 0, "Unaligned DEX parameter list")
            self.bounds(parameters, 4, data=True)
            count = self.u32(parameters)
            self.bounds(parameters + 4, count * 2, data=True)
            require(count <= MAX_TABLE_ITEMS, "DEX parameter count exceeds limit")
            for position in range(count):
                self.type(self.unpack("H", parameters + 4 + 2 * position)[0])
        if result == b"V" and count == 0:
            require(shorty == b"V", "Invalid DEX constructor shorty")
            return True
        return False

    def code(self, offset):
        require(offset != 0 and offset % 4 == 0, "Constructor has missing/unaligned DEX code")
        self.bounds(offset, 16, data=True)
        registers, inputs, _, tries, debug, instructions = self.unpack("HHHHII", offset)
        require(instructions > 0 and registers >= inputs and inputs == 1,
                "Constructor has empty/invalid DEX code header")
        self.bounds(offset + 16, instructions * 2, data=True)
        if debug:
            self.bounds(debug, 1, data=True)
        if tries:
            self.bounds(offset + 16 + instructions * 2 + (instructions % 2) * 2, tries * 8 + 1, data=True)

    def constructor(self, class_index, offset):
        require(offset != 0, "Registrar has no DEX class data / constructor definition")
        counts = []
        for _ in range(4):
            count, offset = self.uleb(offset)
            counts.append(count)
        found = 0
        for section, count in enumerate(counts):
            table = "field" if section < 2 else "method"
            require(count <= self.tables[table][0], f"DEX encoded {table} count out of bounds")
            index = 0
            for position in range(count):
                delta, offset = self.uleb(offset)
                require(position == 0 or delta > 0, f"Duplicate DEX encoded {table} index")
                index += delta
                flags, offset = self.uleb(offset)
                owner, type_or_proto, name_index = self.unpack("HHI", self.item(table, index))
                require(owner == class_index, f"DEX encoded {table} belongs to another class")
                name = self.string(name_index)
                if table == "field":
                    self.type(type_or_proto)
                    continue
                code, offset = self.uleb(offset)
                noarg_void = self.noarg_void(type_or_proto)
                if name == b"<init>" and noarg_void:
                    require(section == 2, "Registrar constructor is not a direct method")
                    require(flags & PUBLIC and flags & CONSTRUCTOR
                            and not flags & (PRIVATE_OR_PROTECTED | STATIC | NATIVE | ABSTRACT),
                            "Registrar constructor is not public nonstatic concrete")
                    self.code(code)
                    found += 1
        require(found == 1, f"Registrar must define one public noarg <init>()V; found {found}")

    def registrars(self):
        found = set()
        for index in range(self.tables["class"][0]):
            offset = self.item("class", index)
            class_index, flags = self.unpack("II", offset)
            name = DESCRIPTORS.get(self.type(class_index))
            if name is None:
                continue
            require(name not in found, f"Duplicate registrar definition: {name}")
            require(flags & PUBLIC and not flags & (PRIVATE_OR_PROTECTED | INTERFACE | ABSTRACT),
                    f"Registrar is not a public concrete class: {name}")
            try:
                self.constructor(class_index, self.u32(offset + 24))
            except ReleaseError as error:
                raise ReleaseError(f"{name}: {error}") from error
            found.add(name)
        return found


def check_apk(apk):
    found = {}
    with zipfile.ZipFile(io.BytesIO(read_apk_bytes(apk))) as archive:
        entries = [entry for entry in archive.infolist()
                   if entry.filename.startswith("classes") and entry.filename.endswith(".dex")
                   and "/" not in entry.filename]
        require(0 < len(entries) <= MAX_DEX_FILES, "APK DEX count outside limits")
        names = [entry.filename for entry in entries]
        require(len(set(names)) == len(names), "Duplicate APK DEX entry")
        require("classes.dex" in names and all(re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name)
                                              for name in names), "Noncanonical APK DEX names")
        require(all(entry.file_size <= MAX_DEX_BYTES for entry in entries)
                and sum(entry.file_size for entry in entries) <= MAX_TOTAL_DEX_BYTES,
                "APK DEX decompressed size exceeds limit")
        for entry in entries:
            require(entry.filename == entry.orig_filename and not entry.flag_bits & 1
                    and entry.compress_type in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED},
                    "Unsupported APK DEX entry")
            with archive.open(entry) as stream:
                try:
                    data = stream.read(MAX_DEX_BYTES + 1)
                except (zlib.error, zipfile.BadZipFile) as error:
                    raise ReleaseError(f"{entry.filename}: corrupt compressed DEX entry") from error
            require(len(data) == entry.file_size, "APK DEX size mismatch")
            try:
                registrars = Dex(data).registrars()
            except ReleaseError as error:
                raise ReleaseError(f"{entry.filename}: {error}") from error
            for name in registrars:
                require(name not in found, f"Duplicate registrar definition across DEX files: {name}")
                found[name] = entry.filename
    require(set(found) == set(REGISTRARS), "Missing registrar definitions: " + ", ".join(sorted(set(REGISTRARS) - set(found))))
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args(argv)
    try:
        found = check_apk(args.apk)
    except (ReleaseError, OSError, zipfile.BadZipFile, zlib.error) as error:
        parser.exit(1, f"dex-contract: {error}\n")
    for name in REGISTRARS:
        print(f"PASS {name}: public <init>()V in {found[name]}")


if __name__ == "__main__":
    main()
