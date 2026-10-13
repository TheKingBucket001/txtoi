"""R8 artifact checks use structural DEX records without running Android or Gradle."""

import copy
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile


SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("verify_r8_entrypoints", SCRIPTS / "verify_r8_entrypoints.py")
r8_check = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(r8_check)


def class_dump(descriptor, methods, class_access=0x11, fields=""):
    sections = []
    for section in ("Direct", "Virtual"):
        records = []
        for name, signature, access in methods:
            if (name == "<init>") != (section == "Direct"):
                continue
            records.append(
                f"    #{len(records)}              : (in {descriptor})\n"
                f"      name          : '{name}'\n"
                f"      type          : '{signature}'\n"
                f"      access        : 0x{access:04x} (FLAGS)\n"
                "      code          -\n"
                "      registers     : 2\n"
            )
        sections.append(f"  {section} methods    -\n" + "".join(records))
    return (
        "Class #0            -\n"
        f"  Class descriptor  : '{descriptor}'\n"
        f"  Access flags      : 0x{class_access:04x} (FLAGS)\n"
        "  Superclass        : 'Ljava/lang/Object;'\n"
        "  Static fields     -\n" + fields +
        "  Instance fields   -\n" + "".join(sections) +
        "  source_file_idx   : 1 (Source.kt)\n"
    )


class R8EntrypointTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.apk = self.directory / "candidate.apk"
        self.mapping = self.directory / "mapping.txt"
        self.names = {
            "SelectionMenuModule": "io.github.selectionmenucontrol.SelectionMenuModule",
            "EnvironmentViewModel": "a.b",
            "UpdateViewModel": "u",
            "RuleEditorViewModel": "r",
        }
        self.mapping_text = "\n".join(r8_check.PACKAGE + name + " -> " + mapped + ":" for name, mapped in self.names.items())
        self.mapping.write_text(self.mapping_text, encoding="utf-8")
        self.descriptors = {name: "L" + mapped.replace(".", "/") + ";" for name, mapped in self.names.items()}
        self.methods = {
            name: [(method, signature, 0x10001 if method == "<init>" else 0x1)
                   for method, signature in required]
            for name, required in r8_check.REQUIRED_METHODS.items()
        }
        self.dump_paths = []
        self.write_apk({"classes.dex": self.dump()})

    def dump(self, names=None):
        return "".join(class_dump(self.descriptors[name], self.methods[name]) for name in (names or self.names))

    def write_apk(self, contents):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for filename, dump in contents.items():
                archive.writestr(filename, dump.encode("utf-8"))

    def runner(self, arguments):
        self.assertEqual(arguments[0], "test-dexdump")
        path = Path(arguments[1])
        self.dump_paths.append(path)
        self.assertTrue(path.is_file())
        self.assertNotEqual(path.parent, self.directory)
        return path.read_text(encoding="utf-8")

    def verify(self):
        return r8_check.verify_entrypoints(self.apk, self.mapping, "test-dexdump", runner=self.runner)

    def test_exact_public_entrypoints_are_reported(self):
        result = self.verify()
        self.assertEqual(set(result["entrypoints"]), set(self.names))
        self.assertEqual(result["entrypoints"]["EnvironmentViewModel"]["descriptor"], "La/b;")
        self.assertEqual(result["dex_files"], ["classes.dex"])
        self.assertTrue(all(not path.exists() for path in self.dump_paths))

    def test_entries_can_be_in_multiple_dex_files(self):
        self.write_apk({"classes2.dex": self.dump(["UpdateViewModel", "RuleEditorViewModel"]),
                        "classes.dex": self.dump(["SelectionMenuModule", "EnvironmentViewModel"]),
                        "assets/other.dex": b"unused".decode()})
        result = self.verify()
        self.assertEqual(result["dex_files"], ["classes.dex", "classes2.dex"])
        self.assertEqual(len(self.dump_paths), 2)

    def test_missing_class_cannot_be_replaced_by_method_name_text(self):
        self.write_apk({"classes.dex": self.dump(["SelectionMenuModule", "EnvironmentViewModel", "UpdateViewModel"])
                        + "# RuleEditorViewModel Lr; <init> ()V PUBLIC\n"})
        with self.assertRaisesRegex(ValueError, "Missing DEX entry class RuleEditorViewModel"):
            self.verify()

    def test_missing_callback_is_not_found_in_another_class(self):
        callback = self.methods["SelectionMenuModule"].pop()
        self.methods["RuleEditorViewModel"].append(callback)
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Missing exact method SelectionMenuModule.onSystemServerStarting"):
            self.verify()

    def test_callback_with_wrong_parameter_descriptor_is_rejected(self):
        method, _, access = self.methods["SelectionMenuModule"][1]
        self.methods["SelectionMenuModule"][1] = (method, "(Ljava/lang/Object;)V", access)
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Missing exact method SelectionMenuModule.onModuleLoaded"):
            self.verify()

    def test_private_or_static_required_methods_are_rejected(self):
        original = copy.deepcopy(self.methods)
        cases = [("RuleEditorViewModel", 0, 0x10002), ("EnvironmentViewModel", 0, 0x10002),
                 ("SelectionMenuModule", 0, 0x10002), ("SelectionMenuModule", 1, 0x9),
                 ("UpdateViewModel", 0, 0x10001 | 0x400)]
        for name, index, flags in cases:
            with self.subTest(name=name, flags=flags):
                self.methods = copy.deepcopy(original)
                method, signature, _ = self.methods[name][index]
                self.methods[name][index] = (method, signature, flags)
                self.write_apk({"classes.dex": self.dump()})
                with self.assertRaisesRegex(ValueError, "must be public"):
                    self.verify()

    def test_application_constructor_does_not_accept_zero_arg_overload(self):
        self.methods["UpdateViewModel"] = [("<init>", "()V", 0x10001)]
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Missing exact method UpdateViewModel"):
            self.verify()

    def test_constructor_requires_actual_constructor_access_flag(self):
        self.methods["RuleEditorViewModel"] = [("<init>", "()V", 0x1)]
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "constructor flag"):
            self.verify()

    def test_public_text_cannot_override_private_numeric_access(self):
        dump = self.dump().replace("0x10001 (FLAGS)", "0x10002 (PUBLIC CONSTRUCTOR)", 1)
        self.write_apk({"classes.dex": dump})
        with self.assertRaisesRegex(ValueError, "must be public"):
            self.verify()

    def test_entry_class_must_be_public_and_concrete(self):
        for flags in (0x10, 0x411):
            with self.subTest(flags=flags):
                dump = self.dump().replace("0x0011 (FLAGS)", f"0x{flags:04x} (FLAGS)", 1)
                self.write_apk({"classes.dex": dump})
                with self.assertRaisesRegex(ValueError, "public and concrete"):
                    self.verify()

    def test_module_mapping_must_retain_literal_name(self):
        self.mapping.write_text(self.mapping_text.replace(self.names["SelectionMenuModule"] + ":", "m:"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "literal name"):
            self.verify()
        self.assertFalse(self.dump_paths)

    def test_missing_duplicate_invalid_and_colliding_mapping_are_rejected(self):
        bad_mappings = [
            (self.mapping_text.replace(r8_check.PACKAGE + "RuleEditorViewModel -> r:", "# absent"), "missing"),
            (self.mapping_text + "\n" + r8_check.PACKAGE + "RuleEditorViewModel -> r:", "Duplicate"),
            (self.mapping_text.replace(" -> r:", " -> Lwrong/Descriptor;:"), "Invalid"),
            (self.mapping_text.replace(" -> r:", " -> u:"), "share a mapped"),
        ]
        for text, reason in bad_mappings:
            with self.subTest(reason=reason):
                self.mapping.write_text(text, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, reason):
                    self.verify()

    def test_field_records_cannot_satisfy_a_constructor(self):
        self.methods["RuleEditorViewModel"] = []
        fake_field = "    #0 : (in Lr;)\n      name : '<init>'\n      type : '()V'\n      access : 0x10001 (PUBLIC)\n"
        dump = self.dump(["SelectionMenuModule", "EnvironmentViewModel", "UpdateViewModel"])
        dump += class_dump("Lr;", [], fields=fake_field)
        self.write_apk({"classes.dex": dump})
        with self.assertRaisesRegex(ValueError, "Missing exact method RuleEditorViewModel"):
            self.verify()

    def test_duplicate_class_and_duplicate_method_are_rejected(self):
        duplicate_class = self.dump() + self.dump(["RuleEditorViewModel"])
        with self.subTest(kind="class"):
            self.write_apk({"classes.dex": duplicate_class})
            with self.assertRaisesRegex(ValueError, "Duplicate DEX class"):
                self.verify()
        with self.subTest(kind="method"):
            self.methods["RuleEditorViewModel"] *= 2
            self.write_apk({"classes.dex": self.dump()})
            with self.assertRaisesRegex(ValueError, "Duplicate DEX method"):
                self.verify()

    def test_duplicate_class_across_dex_files_is_rejected(self):
        self.write_apk({"classes.dex": self.dump(), "classes2.dex": self.dump(["RuleEditorViewModel"])})
        with self.assertRaisesRegex(ValueError, "Duplicate class across"):
            self.verify()

    def test_malformed_dump_is_rejected_and_temporary_files_are_cleaned(self):
        bad_dumps = [("<init> PUBLIC ()V", "class records"),
                     (self.dump().replace("  Access flags", "  Unknown flags", 1), "class record"),
                     (self.dump().replace("      access", "      Unknown access", 1), "method record")]
        for dump, reason in bad_dumps:
            with self.subTest(reason=reason):
                self.write_apk({"classes.dex": dump})
                with self.assertRaisesRegex(ValueError, reason):
                    self.verify()
                self.assertTrue(all(not path.exists() for path in self.dump_paths))

    def test_nonstandard_or_noncontiguous_dex_names_are_rejected(self):
        for name in ("classes2.dex", "classes01.dex", "other.dex"):
            with self.subTest(name=name):
                self.write_apk({name: self.dump()})
                with self.assertRaises(ValueError):
                    self.verify()

    def test_duplicate_archive_entries_are_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w") as archive:
                archive.writestr("classes.dex", self.dump())
                archive.writestr("classes.dex", self.dump())
        with self.assertRaisesRegex(ValueError, "duplicate DEX entries"):
            self.verify()

    def test_dex_size_limit_is_enforced_before_extraction(self):
        with patch.object(r8_check, "MAX_DEX_BYTES", 1):
            with self.assertRaisesRegex(ValueError, "verification limit"):
                self.verify()
        self.assertFalse(self.dump_paths)

    def test_runner_failure_cleans_temporary_files(self):
        def failed_runner(arguments):
            self.runner(arguments)
            raise subprocess.TimeoutExpired(arguments, 120)
        with self.assertRaises(subprocess.TimeoutExpired):
            r8_check.verify_entrypoints(self.apk, self.mapping, "test-dexdump", runner=failed_runner)
        self.assertTrue(all(not path.exists() for path in self.dump_paths))

    def test_dexdump_nonzero_exit_is_rejected_even_with_valid_stdout(self):
        completed = subprocess.CompletedProcess(["dexdump"], 1, self.dump(), "invalid DEX")
        with patch.object(r8_check.subprocess, "run", return_value=completed) as run:
            with self.assertRaisesRegex(ValueError, "dexdump failed"):
                r8_check.run_dexdump(["dexdump", "input.dex"])
        self.assertEqual(run.call_args.kwargs["timeout"], 120)
        self.assertEqual(run.call_args.kwargs["errors"], "strict")


if __name__ == "__main__":
    unittest.main()
