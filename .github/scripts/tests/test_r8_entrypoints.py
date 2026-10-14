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


def class_dump(descriptor, methods, class_access=0x11, fields="", superclass="Ljava/lang/Object;"):
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
        f"  Superclass        : '{superclass}'\n"
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
        self.base_names = {"AndroidViewModel": "base.Android", "ViewModel": "base.Model"}
        all_names = {**self.names, **self.base_names}
        self.mapping_text = "\n".join(r8_check.ORIGINAL_CLASSES[name] + " -> " + mapped + ":" for name, mapped in all_names.items())
        self.mapping.write_text(self.mapping_text, encoding="utf-8")
        self.descriptors = {name: "L" + mapped.replace(".", "/") + ";" for name, mapped in all_names.items()}
        self.methods = {
            name: [(method, signature, 0x10001 if method == "<init>" else 0x1)
                   for method, signature in required]
            for name, required in r8_check.REQUIRED_METHODS.items()
        }
        self.parents = {"SelectionMenuModule": r8_check.XPOSED_BASE,
                        "EnvironmentViewModel": self.descriptors["AndroidViewModel"],
                        "UpdateViewModel": self.descriptors["AndroidViewModel"],
                        "RuleEditorViewModel": self.descriptors["ViewModel"],
                        "AndroidViewModel": self.descriptors["ViewModel"],
                        "ViewModel": "Ljava/lang/Object;"}
        self.dump_paths = []
        self.write_apk({"classes.dex": self.dump()})

    def dump(self, names=None, include_bases=True):
        entries = list(self.base_names) if include_bases else []
        entries += list(names if names is not None else self.names)
        return "".join(class_dump(self.descriptors[name], self.methods.get(name, []), superclass=self.parents[name]) for name in entries)

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
        self.write_apk({"classes2.dex": self.dump(["UpdateViewModel", "RuleEditorViewModel"], include_bases=False),
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
                marker = "  Class descriptor  : '" + self.descriptors["SelectionMenuModule"] + "'\n  Access flags      : "
                dump = self.dump().replace(marker + "0x0011 (FLAGS)", marker + f"0x{flags:04x} (FLAGS)", 1)
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
        dump += class_dump("Lr;", [], fields=fake_field, superclass=self.parents["RuleEditorViewModel"])
        self.write_apk({"classes.dex": dump})
        with self.assertRaisesRegex(ValueError, "Missing exact method RuleEditorViewModel"):
            self.verify()

    def test_duplicate_class_and_duplicate_method_are_rejected(self):
        duplicate_class = self.dump() + self.dump(["RuleEditorViewModel"], include_bases=False)
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
        self.write_apk({"classes.dex": self.dump(), "classes2.dex": self.dump(["RuleEditorViewModel"], include_bases=False)})
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

    def test_real_mapped_ancestor_chains_are_reported(self):
        result = self.verify()
        self.assertEqual(result["entrypoints"]["EnvironmentViewModel"]["ancestors"],
                         [self.descriptors["AndroidViewModel"], self.descriptors["ViewModel"], "Ljava/lang/Object;"])
        self.assertEqual(result["entrypoints"]["SelectionMenuModule"]["ancestors"], [r8_check.XPOSED_BASE])

    def test_intermediate_app_superclasses_can_be_in_another_dex(self):
        self.parents["EnvironmentViewModel"] = "Lmiddle/Base;"
        self.parents["SelectionMenuModule"] = "Lmiddle/Module;"
        intermediates = class_dump("Lmiddle/Base;", [], superclass=self.descriptors["AndroidViewModel"])
        intermediates += class_dump("Lmiddle/Module;", [], superclass=r8_check.XPOSED_BASE)
        self.write_apk({"classes.dex": self.dump(), "classes2.dex": intermediates})
        result = self.verify()
        self.assertEqual(result["entrypoints"]["EnvironmentViewModel"]["ancestors"][0], "Lmiddle/Base;")

    def test_matching_constructors_on_object_subclasses_cannot_pass(self):
        for name in self.names:
            with self.subTest(name=name):
                previous = self.parents[name]
                self.parents[name] = "Ljava/lang/Object;"
                self.write_apk({"classes.dex": self.dump()})
                with self.assertRaisesRegex(ValueError, "not assignable"):
                    self.verify()
                self.parents[name] = previous

    def test_application_viewmodels_must_extend_androidviewmodel_not_only_viewmodel(self):
        self.parents["UpdateViewModel"] = self.descriptors["ViewModel"]
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "UpdateViewModel is not assignable"):
            self.verify()

    def test_androidviewmodel_base_must_extend_mapped_viewmodel(self):
        self.parents["AndroidViewModel"] = "Ljava/lang/Object;"
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "AndroidViewModel is not assignable"):
            self.verify()

    def test_missing_mapped_base_and_mapping_are_rejected(self):
        self.write_apk({"classes.dex": self.dump(include_bases=False)})
        with self.assertRaisesRegex(ValueError, "Missing mapped ViewModel base"):
            self.verify()
        self.mapping.write_text(self.mapping_text.replace("androidx.lifecycle.AndroidViewModel -> base.Android:", "# absent"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Mapping is missing"):
            self.verify()

    def test_unknown_missing_ancestor_cannot_be_assumed_a_valid_base(self):
        self.parents["RuleEditorViewModel"] = "Lmissing/Intermediate;"
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Missing superclass chain"):
            self.verify()

    def test_self_and_intermediate_superclass_cycles_are_rejected(self):
        self.parents["RuleEditorViewModel"] = self.descriptors["RuleEditorViewModel"]
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            self.verify()
        self.parents["RuleEditorViewModel"] = "Lmiddle/A;"
        cyclic = class_dump("Lmiddle/A;", [], superclass="Lmiddle/B;")
        cyclic += class_dump("Lmiddle/B;", [], superclass="Lmiddle/A;")
        self.write_apk({"classes.dex": self.dump() + cyclic})
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            self.verify()

    def test_missing_or_ambiguous_superclass_records_are_rejected(self):
        valid = self.dump()
        line = "  Superclass        : '" + self.parents["ViewModel"] + "'\n"
        for dump in (valid.replace(line, "", 1), valid.replace(line, line + line, 1)):
            with self.subTest(dump=dump[:100]):
                self.write_apk({"classes.dex": dump})
                with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
                    self.verify()

    def test_apk_cannot_shadow_runtime_xposed_base(self):
        self.write_apk({"classes.dex": self.dump() + class_dump(r8_check.XPOSED_BASE, [])})
        with self.assertRaisesRegex(ValueError, "shadow"):
            self.verify()

    def test_viewmodel_base_cannot_end_at_xposedmodule(self):
        self.parents["ViewModel"] = r8_check.XPOSED_BASE
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "ViewModel must end"):
            self.verify()

    def test_cycle_beyond_required_base_is_rejected(self):
        self.parents["ViewModel"] = self.descriptors["AndroidViewModel"]
        self.write_apk({"classes.dex": self.dump()})
        with self.assertRaisesRegex(ValueError, "Cyclic"):
            self.verify()

    def test_mapped_base_descriptors_cannot_be_ambiguous(self):
        self.mapping.write_text(self.mapping_text.replace(" -> base.Android:", " -> base.Model:"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "share a mapped descriptor"):
            self.verify()

    def test_superclass_chain_cannot_use_an_interface_as_a_class(self):
        marker = "  Class descriptor  : '" + self.descriptors["AndroidViewModel"] + "'\n  Access flags      : "
        dump = self.dump().replace(marker + "0x0011 (FLAGS)", marker + "0x0601 (PUBLIC INTERFACE ABSTRACT)", 1)
        self.write_apk({"classes.dex": dump})
        with self.assertRaisesRegex(ValueError, "contains an interface"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
