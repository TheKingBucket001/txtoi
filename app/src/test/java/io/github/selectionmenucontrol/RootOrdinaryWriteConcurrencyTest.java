package io.github.selectionmenucontrol;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Runs the generated production shell against a deterministic local Settings-provider fixture. */
public class RootOrdinaryWriteConcurrencyTest {
    private static final String KEY = "selection_menu_test_rules";
    private static final String BASELINE = "v3::";
    private static final String DRAFT = rules("本地名称");
    private static final String EXTERNAL = rules("外部名称");
    private static final long REVISION = 123_450_000_000L;
    private static final long DEADLINE = 124_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String rules(String alias) {
        return new RuleConfig(Collections.emptySet(), Collections.emptyList(),
                Map.of("p/p.First", alias)).encode();
    }

    @Test(timeout = 10_000L)
    public void ordinarySaveRejectsAnExternalValueChangedSinceThePageRead() throws Exception {
        Environment environment = environment();
        SystemRuleStore.Snapshot page = SystemRuleStore.decodeGlobal(BASELINE);
        environment.setValue(EXTERNAL);
        SystemRuleStore.SaveResult result = environment.save(SystemRuleStore.decode(DRAFT), page.sourceGlobal.value);
        assertFalse(result.verified);
        assertFalse(result.uncertain);
        assertEquals(RootAccess.Failure.STALE_REVISION, result.failure);
        assertTrue(result.requiresReconciliation());
        assertEquals("An ordinary save must preserve the external value", EXTERNAL, environment.value());
    }

    @Test(timeout = 10_000L)
    public void conditionalSaveRejectsAnExternalValueAlreadyChangedBeforeCompare() throws Exception {
        Environment environment = environment();
        environment.setValue(EXTERNAL);
        ProcessExecutor.Result result = environment.execute(RootCommandProtocol.put(KEY, DRAFT,
                true, true, BASELINE, REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.STALE_REVISION, result.exitCode);
        assertEquals(EXTERNAL, environment.value());
        assertFalse(RootAccess.result(result, true, true).uncertainWrite);
    }

    @Test(timeout = 10_000L)
    public void conditionalSaveCannotProvideAtomicCasAgainstANonCooperatingWriter() throws Exception {
        Environment environment = environment();
        environment.setValue(BASELINE);
        Files.writeString(environment.directory.resolve("inject-after-get"), EXTERNAL);
        ProcessExecutor.Result result = environment.execute(RootCommandProtocol.put(KEY, DRAFT,
                true, true, BASELINE, REVISION, DEADLINE));
        assertEquals("The fixture must prove the external write actually ran", EXTERNAL,
                Files.readString(environment.directory.resolve("external-write-receipt")));
        assertEquals(0, result.exitCode);
        // The shared flock covers only cooperating writers; get/put are separate provider calls.
        assertEquals(DRAFT, environment.value());
    }

    @Test(timeout = 10_000L)
    public void providerCommitThenFailureRemainsUncertainAndRecoveryObservesComplete() throws Exception {
        Environment environment = environment();
        environment.setValue(BASELINE);
        Files.createFile(environment.directory.resolve("fail-after-put"));
        ProcessExecutor.Result result = environment.execute(RootCommandProtocol.put(KEY, DRAFT,
                true, REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.WRITE_FAILED, result.exitCode);
        assertEquals(DRAFT, environment.value());
        assertTrue(RootAccess.result(result, true, false).uncertainWrite);
        assertEquals(PendingRuleRecovery.Action.COMPLETE,
                PendingRuleRecovery.decide(BASELINE, DRAFT, DRAFT, environment.value(), true));
    }

    @Test(timeout = 10_000L)
    public void providerFailureBeforeCommitAlsoRemainsUncertainButRecoveryObservesReplay() throws Exception {
        Environment environment = environment();
        environment.setValue(BASELINE);
        Files.createFile(environment.directory.resolve("fail-before-put"));
        ProcessExecutor.Result result = environment.execute(RootCommandProtocol.put(KEY, DRAFT,
                true, REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.WRITE_FAILED, result.exitCode);
        assertEquals(BASELINE, environment.value());
        assertTrue(RootAccess.result(result, true, false).uncertainWrite);
        assertEquals(PendingRuleRecovery.Action.REPLAY,
                PendingRuleRecovery.decide(BASELINE, DRAFT, DRAFT, environment.value(), true));
    }

    @Test(timeout = 10_000L)
    public void missingBaselineRejectsCanonicalEmptyEvenThoughDecodedRulesMatch() throws Exception {
        Environment environment = environment();
        environment.setValue(BASELINE);
        ProcessExecutor.Result result = environment.execute(RootCommandProtocol.put(KEY, DRAFT,
                true, true, null, REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.STALE_REVISION, result.exitCode);
        assertEquals(BASELINE, environment.value());
    }

    private Environment environment() throws IOException {
        String defaultShell = System.getProperty("os.name").startsWith("Windows")
                ? "C:/msys64/usr/bin/sh.exe" : "/bin/sh";
        String shell = System.getProperty("selectionmenu.rootProtocolTestShell", defaultShell);
        Assume.assumeTrue("POSIX shell not installed", new File(shell).isFile());
        Path directory = temporary.newFolder().toPath();
        Files.writeString(directory.resolve("boot"), "12345678-1234-1234-1234-123456789abc\n");
        Files.writeString(directory.resolve("uptime"), "123.45 234.56\n");
        Path settings = directory.resolve("fake-settings.sh");
        Files.writeString(settings, "#!/bin/sh\n"
                + "[ \"$2\" = global ] || exit 90\n"
                + "case \"$1\" in\n"
                + "put) [ ! -f \"$ROOT_TEST_DIR/fail-before-put\" ] || exit 12; "
                + "printf '%s' \"$4\" > \"$ROOT_TEST_DIR/$3\"; "
                + "[ ! -f \"$ROOT_TEST_DIR/fail-after-put\" ] || exit 14;;\n"
                + "get) if [ -f \"$ROOT_TEST_DIR/$3\" ]; then cat \"$ROOT_TEST_DIR/$3\"; else printf null; fi; "
                // Emit the old read result, then inject an external write before the caller's put.
                + "if [ -f \"$ROOT_TEST_DIR/inject-after-get\" ]; then "
                + "cat \"$ROOT_TEST_DIR/inject-after-get\" > \"$ROOT_TEST_DIR/$3\"; "
                + "cp \"$ROOT_TEST_DIR/inject-after-get\" \"$ROOT_TEST_DIR/external-write-receipt\"; "
                + "rm \"$ROOT_TEST_DIR/inject-after-get\"; fi;;\n"
                + "*) exit 91;;\nesac\n");
        return new Environment(shell, directory, settings);
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'";
    }

    private static final class Environment {
        final String shell;
        final Path directory;
        final Path settings;

        Environment(String shell, Path directory, Path settings) {
            this.shell = shell;
            this.directory = directory;
            this.settings = settings;
        }

        void setValue(String value) throws IOException { Files.writeString(directory.resolve(KEY), value); }
        String value() throws IOException { return Files.readString(directory.resolve(KEY)); }

        SystemRuleStore.SaveResult save(SystemRuleStore.Snapshot snapshot, String expected) {
            return SystemRuleStore.saveDetailed(snapshot, expected, (encoded, baseline) -> {
                try {
                    return RootAccess.result(execute(RootCommandProtocol.put(KEY, encoded, true,
                            true, baseline, REVISION, DEADLINE)), true, false);
                } catch (IOException error) { throw new AssertionError(error); }
            }, () -> { throw new AssertionError("A stale save must not repair the backup"); });
        }

        ProcessExecutor.Result execute(String script) throws IOException {
            String translated = "PATH=/usr/bin:/bin; export PATH; ROOT_TEST_DIR=" + quote(directory)
                    + "; export ROOT_TEST_DIR;\n"
                    + script.replace("'/data/system/selectionmenucontrol'", quote(directory.resolve("root")))
                    .replace("/proc/sys/kernel/random/boot_id", quote(directory.resolve("boot")))
                    .replace("/proc/uptime", quote(directory.resolve("uptime")))
                    .replace("/system/bin/settings", "sh " + quote(settings))
                    .replace("[ -x /system/bin/flock ]", "[ -x /usr/bin/flock ]")
                    .replace("/system/bin/flock", "/usr/bin/flock")
                    .replace("/system/bin/", "");
            Path scriptFile = directory.resolve("transaction.sh");
            Files.writeString(scriptFile, translated);
            ProcessExecutor.Result result = new ProcessExecutor((executable, command) ->
                    new ProcessBuilder(executable, scriptFile.toString()).redirectErrorStream(true).start())
                    .execute(shell, "", 3_000L, 2048);
            assertEquals("Shell status=" + result.status + ", output=" + result.output,
                    ProcessExecutor.Status.EXITED, result.status);
            assertEquals("Protocol must not emit raw setting values", "", result.output);
            return result;
        }
    }
}
