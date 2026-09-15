"""Small structural DEX fixtures; no SDK, Gradle, signatures, or device required."""
import contextlib
import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dex_contract as d


def uleb(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return result


def dex_fixture(names=d.REGISTRARS, class_flags=d.PUBLIC,
                method_flags=d.PUBLIC | d.CONSTRUCTOR, method_name="<init>",
                defined=True, class_data=True, virtual=False, parameters=False,
                return_type="V", code=True):
    descriptors = ["L" + name.replace(".", "/") + ";" for name in names]
    types = descriptors + ["I", "V"]
    strings = sorted(set(types + [method_name, "VI" if parameters else return_type]))
    string_index = {value: index for index, value in enumerate(strings)}
    data = bytearray(112)
    tables = {}
    for name, header, count, width in (("string", 56, len(strings), 4), ("type", 64, len(types), 4),
                                       ("proto", 72, 1, 12), ("field", 80, 0, 8),
                                       ("method", 88, len(names), 8), ("class", 96, len(names), 32)):
        offset = len(data) if count else 0
        struct.pack_into("<II", data, header, count, offset)
        tables[name] = offset
        data.extend(bytes(count * width))
    data_start = len(data)
    for index, value in enumerate(strings):
        struct.pack_into("<I", data, tables["string"] + 4 * index, len(data))
        data.extend(uleb(len(value)) + value.encode() + b"\0")
    for index, value in enumerate(types):
        struct.pack_into("<I", data, tables["type"] + 4 * index, string_index[value])
    data.extend(bytes(-len(data) % 4))
    parameters_offset = 0
    if parameters:
        parameters_offset = len(data)
        data.extend(struct.pack("<IH", 1, types.index("I")))
        data.extend(bytes(-len(data) % 4))
    struct.pack_into("<III", data, tables["proto"], string_index["VI" if parameters else return_type],
                     types.index(return_type), parameters_offset)
    codes, class_offsets = [], []
    for index in range(len(names)):
        struct.pack_into("<HHI", data, tables["method"] + 8 * index, index, 0, string_index[method_name])
        data.extend(bytes(-len(data) % 4))
        codes.append(len(data))
        data.extend(struct.pack("<HHHHIIH", 1, 1, 0, 0, 0, 1, 0x000e))
        class_offsets.append(len(data))
        data.extend(bytes([0, 0, int(defined and not virtual), int(defined and virtual)]))
        if defined:
            data.extend(uleb(index) + uleb(method_flags) + uleb(codes[-1] if code else 0))
        struct.pack_into("<IIIIIIII", data, tables["class"] + 32 * index,
                         index, class_flags, 0xffffffff, 0, 0xffffffff, 0,
                         class_offsets[-1] if class_data else 0, 0)
    data[:8] = b"dex\n039\0"
    struct.pack_into("<III", data, 32, len(data), 112, 0x12345678)
    struct.pack_into("<II", data, 104, len(data) - data_start, data_start)
    return data, {**tables, "codes": codes, "class_data": class_offsets, "data_start": data_start}


def put_u32(data, offset, value):
    struct.pack_into("<I", data, offset, value)
    return data


class DexContractTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.apk = Path(self.temporary.name) / "fixture.apk"

    def archive(self, entries, compression=zipfile.ZIP_DEFLATED):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w", compression=compression) as archive:
                for name, data in entries:
                    archive.writestr(name, data)
        return self.apk

    def check_dex(self, data):
        return d.check_apk(self.archive([("classes.dex", data)]))

    def reject(self, data, message):
        with self.assertRaisesRegex(d.ReleaseError, message):
            self.check_dex(data)

    def test_both_exact_registrars_with_real_direct_constructors(self):
        data, _ = dex_fixture()
        self.assertEqual(self.check_dex(data), dict.fromkeys(d.REGISTRARS, "classes.dex"))

    def test_multidex_checks_all_entries_and_ignores_non_runtime_asset_dex(self):
        entries = [("classes.dex", dex_fixture(names=())[0]),
                   ("classes2.dex", dex_fixture(names=d.REGISTRARS[:1])[0]),
                   ("classes20.dex", dex_fixture(names=d.REGISTRARS[1:])[0]),
                   ("assets/classes.dex", b"not runtime DEX")]
        self.assertEqual(d.check_apk(self.archive(entries)),
                         dict(zip(d.REGISTRARS, ("classes2.dex", "classes20.dex"))))

    def test_missing_either_class_or_renamed_class_is_rejected(self):
        for names in ((), d.REGISTRARS[:1], d.REGISTRARS[1:],
                      (d.REGISTRARS[0] + "Renamed", d.REGISTRARS[1])):
            with self.subTest(names=names):
                self.reject(dex_fixture(names=names)[0], "Missing registrar definitions")

    def test_class_only_and_method_reference_without_definition_are_rejected(self):
        for changes in ({"class_data": False}, {"defined": False}, {"method_name": "other"}):
            with self.subTest(changes=changes):
                self.reject(dex_fixture(**changes)[0], "constructor definition|found 0")
        data, _ = dex_fixture()
        put_u32(data, 96, 0)
        put_u32(data, 100, 0)
        self.reject(data, "Missing registrar definitions")

    def test_public_concrete_class_is_required(self):
        for flags in (0, d.PUBLIC | d.ABSTRACT, d.PUBLIC | d.INTERFACE,
                      d.PUBLIC | 2, d.PUBLIC | 4):
            with self.subTest(flags=flags):
                self.reject(dex_fixture(class_flags=flags)[0], "not a public concrete class")

    def test_constructor_requires_public_nonstatic_concrete_flags(self):
        for flags in (d.CONSTRUCTOR, d.PUBLIC, d.PUBLIC | d.CONSTRUCTOR | d.STATIC,
                      d.PUBLIC | d.CONSTRUCTOR | d.ABSTRACT, d.PUBLIC | d.CONSTRUCTOR | d.NATIVE,
                      d.PUBLIC | d.CONSTRUCTOR | 2, d.PUBLIC | d.CONSTRUCTOR | 4):
            with self.subTest(flags=flags):
                self.reject(dex_fixture(method_flags=flags)[0], "not public nonstatic concrete")

    def test_constructor_requires_noargs_void_and_direct_method(self):
        for changes, error in (({"parameters": True}, "found 0"), ({"return_type": "I"}, "found 0"),
                               ({"virtual": True}, "not a direct method"),
                               ({"code": False}, "missing/unaligned DEX code")):
            with self.subTest(changes=changes):
                self.reject(dex_fixture(**changes)[0], error)

    def test_nonzero_code_offset_requires_real_nonempty_bounded_instructions(self):
        for field, value, error in ((12, 0, "empty/invalid"), (12, 0xffffffff, "out of bounds"),
                                    (0, 0, "empty/invalid"), (8, 1, "out of bounds")):
            with self.subTest(field=field, value=value):
                data, offsets = dex_fixture()
                put_u32(data, offsets["codes"][0] + field, value)
                self.reject(data, error)
        data, offsets = dex_fixture()
        for offset in (1, offsets["codes"][0] + 1, len(data) + 4):
            with self.subTest(offset=offset):
                with self.assertRaises(d.ReleaseError):
                    d.Dex(bytes(data)).code(offset)

    def test_duplicate_class_definitions_in_one_dex_and_across_multidex_are_rejected(self):
        data, _ = dex_fixture(names=(d.REGISTRARS[0], d.REGISTRARS[0], d.REGISTRARS[1]))
        self.reject(data, "Duplicate registrar definition")
        data, _ = dex_fixture()
        with self.assertRaisesRegex(d.ReleaseError, "across DEX files"):
            d.check_apk(self.archive([("classes.dex", data), ("classes2.dex", data)]))

    def test_duplicate_zip_dex_entries_are_rejected(self):
        data, _ = dex_fixture()
        with self.assertRaisesRegex(d.ReleaseError, "Duplicate APK DEX entry"):
            d.check_apk(self.archive([("classes.dex", data), ("classes.dex", data)]))

    def test_dex_names_count_and_size_limits_are_enforced_before_parsing(self):
        data, _ = dex_fixture()
        for entries in ([], [("classes2.dex", data)], [("classes.dex", data), ("classes1.dex", data)],
                        [("classes.dex", data), ("classes02.dex", data)],
                        [("classes.dex", data), ("classesOther.dex", data)]):
            with self.subTest(names=[name for name, _ in entries]), self.assertRaises(d.ReleaseError):
                d.check_apk(self.archive(entries))
        for constant, limit, error in (("MAX_DEX_BYTES", len(data) - 1, "decompressed size"),
                                       ("MAX_TOTAL_DEX_BYTES", len(data) - 1, "decompressed size"),
                                       ("MAX_DEX_FILES", 0, "count outside limits")):
            with self.subTest(constant=constant), patch.object(d, constant, limit):
                self.reject(data, error)
        self.archive([("classes.dex", data)], compression=zipfile.ZIP_BZIP2)
        with self.assertRaisesRegex(d.ReleaseError, "Unsupported APK DEX entry"):
            d.check_apk(self.apk)

    def test_bad_magic_version_endian_and_truncated_header_are_rejected(self):
        data, _ = dex_fixture()
        self.reject(data[:100], "size outside limits")
        for offset, value, error in ((0, 0, "magic/version"), (4, 0, "magic/version"),
                                    (32, len(data) + 1, "file/header size"),
                                    (36, 120, "file/header size"), (40, 0x78563412, "endian"),
                                    (104, len(data), "data bounds"), (108, 1, "data bounds")):
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), offset, value), error)

    def test_supported_standard_dex_versions(self):
        for version in (b"035", b"037", b"038", b"039", b"040"):
            with self.subTest(version=version):
                data, _ = dex_fixture()
                data[4:7] = version
                self.check_dex(data)

    def test_table_bounds_overlap_alignment_counts_and_empty_offsets(self):
        data, offsets = dex_fixture()
        for offset, value, error in ((56, 0xffffffff, "count exceeds limit"),
                                    (60, len(data), "table bounds"), (60, 113, "table bounds"),
                                    (68, offsets["string"], "table bounds"),
                                    (84, 112, "Empty DEX field table")):
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), offset, value), error)

    def test_visited_string_type_method_and_proto_indices_are_checked(self):
        data, offsets = dex_fixture()
        cases = ((offsets["class"], 0xffffffff, "type index"),
                 (offsets["type"], 0xffffffff, "string index"),
                 (offsets["method"] + 4, 0xffffffff, "string index"),
                 (offsets["proto"], 0xffffffff, "string index"),
                 (offsets["proto"] + 4, 0xffffffff, "type index"))
        for offset, value, error in cases:
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), offset, value), error)
        changed = bytearray(data)
        struct.pack_into("<H", changed, offsets["method"] + 2, 0xffff)
        self.reject(changed, "proto index")
        changed = bytearray(data)
        struct.pack_into("<H", changed, offsets["method"], 1)
        self.reject(changed, "belongs to another class")
        changed = bytearray(data)
        changed[offsets["class_data"][0] + 4] = 127
        self.reject(changed, "method index")

    def test_parameter_list_bounds_alignment_and_type_indices(self):
        data, offsets = dex_fixture(parameters=True)
        for offset in (1, len(data) + 4):
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), offsets["proto"] + 8, offset),
                            "Unaligned|out of bounds")
        parameters = struct.unpack_from("<I", data, offsets["proto"] + 8)[0]
        self.reject(put_u32(bytearray(data), parameters, 0xffffffff), "out of bounds")
        struct.pack_into("<H", data, parameters + 4, 0xffff)
        self.reject(data, "type index")

    def test_class_data_bounds_counts_and_malformed_uleb(self):
        data, offsets = dex_fixture()
        for offset in (1, len(data), len(data) + 1):
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), offsets["class"] + 24, offset), "out of bounds")
        changed = bytearray(data)
        changed[offsets["class_data"][0] + 2] = 127
        self.reject(changed, "encoded method count")
        for encoded in (b"\x80\x80\x80\x80\x80", b"\xff\xff\xff\xff\x10", b"\x80\0"):
            with self.subTest(encoded=encoded):
                changed = bytearray(data)
                changed[offsets["class_data"][0]:offsets["class_data"][0] + len(encoded)] = encoded
                self.reject(changed, "ULEB128")
        changed = bytearray(data)
        changed[-1] = 0x80
        with self.assertRaisesRegex(d.ReleaseError, "out of bounds"):
            d.Dex(bytes(changed)).uleb(len(changed) - 1)

    def test_string_bounds_encoding_utf16_length_and_termination(self):
        data, offsets = dex_fixture()
        descriptor_string = struct.unpack_from("<I", data, offsets["type"])[0]
        string_slot = offsets["string"] + 4 * descriptor_string
        for offset in (1, len(data)):
            with self.subTest(offset=offset):
                self.reject(put_u32(bytearray(data), string_slot, offset), "out of bounds")
        start = struct.unpack_from("<I", data, string_slot)[0]
        changed = bytearray(data)
        changed[start] = 1
        self.reject(changed, "UTF-16 length")
        changed = bytearray(data)
        changed[start + 1] = 0xff
        self.reject(changed, "string encoding")
        with patch.object(d, "MAX_STRING_BYTES", 1):
            self.reject(data, "Unterminated or oversized")

    def test_invalid_constructor_shorty_is_rejected(self):
        data, offsets = dex_fixture()
        int_string = struct.unpack_from("<I", data, offsets["type"] + 4 * len(d.REGISTRARS))[0]
        put_u32(data, offsets["proto"], int_string)
        self.reject(data, "constructor shorty")

    def test_cli_pass_and_fail_are_clear_and_do_not_dump_dex_strings(self):
        self.archive([("classes.dex", dex_fixture()[0])])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            d.main([str(self.apk)])
        self.assertEqual(output.getvalue().count("PASS "), 2)
        self.archive([("classes.dex", dex_fixture(defined=False)[0])])
        with contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit) as exit:
            d.main([str(self.apk)])
        self.assertEqual(exit.exception.code, 1)
        self.assertIn("dex-contract: classes.dex:", error.getvalue())
        self.assertIn("found 0", error.getvalue())
        self.apk.write_bytes(b"not a zip")
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as exit:
            d.main([str(self.apk)])
        self.assertEqual(exit.exception.code, 1)

    def test_corrupt_deflate_stream_is_a_clean_rejection_not_a_traceback(self):
        self.archive([("classes.dex", dex_fixture()[0])])
        raw = bytearray(self.apk.read_bytes())
        header = raw.index(b"PK\x03\x04")
        name_length, extra_length = struct.unpack_from("<HH", raw, header + 26)
        start = header + 30 + name_length + extra_length
        raw[start:start + 8] = b"\xff" * 8  # damage the compressed payload, leave ZIP records intact
        self.apk.write_bytes(bytes(raw))
        with self.assertRaisesRegex(d.ReleaseError, "corrupt compressed DEX entry"):
            d.check_apk(self.apk)
        with contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit) as exit:
            d.main([str(self.apk)])
        self.assertEqual(exit.exception.code, 1)
        self.assertIn("dex-contract: classes.dex: corrupt", error.getvalue())


if __name__ == "__main__":
    unittest.main()
