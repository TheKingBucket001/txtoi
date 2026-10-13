package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RootCommandProtocolTest {
    private static final String KEY = "selection_menu_test_rules";
    private static final String BOOT = "12345678-1234-1234-1234-123456789abc";
    private static final long REVISION = 123_450_000_000L;
    private static final long DEADLINE = 124_000L;
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void invalidInputIsRejectedBeforeBuildingAnyShellCommand() {
        assertNull(RootCommandProtocol.put("key;id", "v3::", true, REVISION, DEADLINE));
        assertNull(RootCommandProtocol.put(KEY, "value'$(id)", true, REVISION, DEADLINE));
        assertNull(RootCommandProtocol.put(KEY, "v3::", true, 0L, DEADLINE));
        assertNull(RootCommandProtocol.put(KEY, "v3::", true, REVISION, 123_450L));
        assertNull(RootCommandProtocol.probe(KEY, KEY, "v2:nonce:", "v2:pending", REVISION, DEADLINE));
        assertNull(RootCommandProtocol.cleanupProbe(KEY, "selection_menu_test_response", "*", REVISION, DEADLINE));
    }

    @Test
    public void maximumRuleAndExpectedValuesFitTheLinuxSingleArgumentLimit() {
        String value = "v3:" + "A".repeat(65_533);
        assertTrue(RootCommandProtocol.validValue(value));
        String command = RootCommandProtocol.put(KEY, value, true, true, value, REVISION, DEADLINE);
        assertNotNull(command);
        assertTrue(command.getBytes(StandardCharsets.UTF_8).length < 131_072);
        assertNull(RootCommandProtocol.put(KEY, value + "A", true, REVISION, DEADLINE));
    }

    @Test(timeout = 10_000L)
    public void verifiedWriteCommitsAndOlderRevisionCannotOverwriteIt() throws Exception {
        Environment environment = environment();
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true,
                REVISION + 10L, DEADLINE)));
        assertEquals("v3:NEW:", environment.value(KEY));
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.put(KEY, "v3:OLD:", true, REVISION, DEADLINE)));
        assertEquals("v3:NEW:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void expiredAndQuantizedDeadlineNeverStartWriting() throws Exception {
        Environment environment = environment();
        assertEquals(RootCommandProtocol.EXPIRED,
                environment.execute(RootCommandProtocol.put(KEY, "v3::", true, REVISION, 123_455L)));
        assertFalse(Files.exists(environment.directory.resolve(KEY)));
        assertFalse(Files.exists(environment.directory.resolve("root/" + KEY + ".revision")));
    }

    @Test(timeout = 10_000L)
    public void changedBootStartsANewRevisionHistory() throws Exception {
        Environment environment = environment();
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:OLD:", true,
                REVISION + 100L, DEADLINE)));
        Files.writeString(environment.boot, "87654321-4321-4321-4321-cba987654321\n");
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true,
                REVISION, DEADLINE)));
        assertEquals("v3:NEW:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void compareAndSetProtectsExternalChangesAndDistinguishesAbsentFromNoCheck() throws Exception {
        Environment environment = environment();
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:BASE:", true,
                true, null, REVISION, DEADLINE)));
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.put(KEY, "v3:NEXT:", true,
                        true, null, REVISION + 10L, DEADLINE)));
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.put(KEY, "v3:NEXT:", true,
                        true, "v3:OTHER:", REVISION + 11L, DEADLINE)));
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:NEXT:", true,
                true, "v3:BASE:", REVISION + 12L, DEADLINE)));
        assertEquals("v3:NEXT:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void stableReadFenceNeverWritesAndRejectsOlderLateTransactions() throws Exception {
        Environment environment = environment();
        assertEquals(0, environment.execute(RootCommandProtocol.stable(KEY, null,
                REVISION + 10L, DEADLINE)));
        assertEquals("null", environment.value(KEY));
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.put(KEY, "v3:LATE:", true, REVISION, DEADLINE)));
        assertEquals("null", environment.value(KEY));
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true,
                true, null, REVISION + 11L, DEADLINE)));
        assertEquals(0, environment.execute(RootCommandProtocol.stable(KEY, "v3:NEW:",
                REVISION + 12L, DEADLINE)));
        assertEquals("v3:NEW:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void conflictedStableReadStillCancelsAnOlderDelayedWriter() throws Exception {
        Environment environment = environment();
        Files.writeString(environment.directory.resolve(KEY), "v3:EXTERNAL:");
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.stable(KEY, "v3:EXPECTED:",
                        REVISION + 10L, DEADLINE)));
        assertEquals(RootCommandProtocol.STALE_REVISION,
                environment.execute(RootCommandProtocol.put(KEY, "v3:OLD:", true,
                        REVISION, DEADLINE)));
        assertEquals("v3:EXTERNAL:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void prefixCleanupCannotDeleteAnotherProbe() throws Exception {
        Environment environment = environment();
        String response = "selection_menu_test_response";
        assertEquals(0, environment.execute(RootCommandProtocol.put(KEY, "v2:newnonce:1234", true,
                REVISION, DEADLINE)));
        assertEquals(0, environment.execute(RootCommandProtocol.put(response, "v2:othernonce:1234", true,
                REVISION + 1L, DEADLINE)));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(KEY, response, "v2:oldnonce:",
                REVISION + 10L, DEADLINE)));
        assertEquals("v2:newnonce:1234", environment.value(KEY));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(KEY, response, "v2:newnonce:",
                REVISION + 11L, DEADLINE)));
        assertEquals("null", environment.value(KEY));
        assertEquals("v2:othernonce:1234", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void probeUsesCommitTimeAndBatchCleanupChecksItsOwnNonce() throws Exception {
        Environment environment = environment();
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";
        assertEquals(0, environment.execute(RootCommandProtocol.probe(request, response,
                "v2:nonce:11:18:", "v2:pending:0:0:0", REVISION, DEADLINE)));
        assertEquals("v2:nonce:11:18:123450", environment.value(request));
        assertEquals("v2:pending:0:0:0", environment.value(response));
        Files.writeString(environment.directory.resolve(response), "v2:nonce:11:18:123460");
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v2:othernonce:", REVISION + 10L, DEADLINE)));
        assertEquals("v2:nonce:11:18:123450", environment.value(request));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v2:nonce:", REVISION + 11L, DEADLINE)));
        assertEquals("null", environment.value(request));
        assertEquals("null", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void corruptRevisionRecordFailsClosedWithoutWriting() throws Exception {
        Environment environment = environment();
        Files.createDirectories(environment.directory.resolve("root"));
        Files.writeString(environment.directory.resolve("root/" + KEY + ".revision"), "corrupt\n");
        assertEquals(RootCommandProtocol.PROTOCOL_FAILED,
                environment.execute(RootCommandProtocol.put(KEY, "v3::", true, REVISION, DEADLINE)));
        assertEquals("null", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void writeAndVerificationFailuresHaveDifferentExitCodes() throws Exception {
        Environment environment = environment();
        Files.writeString(environment.directory.resolve("fail-put"), "1");
        assertEquals(RootCommandProtocol.WRITE_FAILED,
                environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true, REVISION, DEADLINE)));
        assertEquals("null", environment.value(KEY));
        Files.delete(environment.directory.resolve("fail-put"));
        Files.writeString(environment.directory.resolve("fail-get"), "1");
        assertEquals(RootCommandProtocol.VERIFY_FAILED,
                environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true, REVISION + 10L, DEADLINE)));
        assertEquals("v3:NEW:", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void casReadFailureIsNotTreatedAsAnAbsentSetting() throws Exception {
        Environment environment = environment();
        Files.writeString(environment.directory.resolve("fail-get"), "1");
        assertEquals(RootCommandProtocol.PROTOCOL_FAILED,
                environment.execute(RootCommandProtocol.put(KEY, "v3:NEW:", true,
                        true, null, REVISION, DEADLINE)));
        assertEquals("null", environment.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void expiryBetweenProbeWritesCannotPublishAnAlreadyExpiredRequest() throws Exception {
        Environment environment = environment();
        Files.writeString(environment.directory.resolve("expire-on-put"), "1");
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";
        int exit = environment.execute(RootCommandProtocol.probe(request, response,
                "v2:nonce:11:18:", "v2:pending:0:0:0", REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.EXPIRED, exit);
        assertEquals("v2:pending:0:0:0", environment.value(response));
        assertEquals("null", environment.value(request));
        assertTrue(RootAccess.result(new ProcessExecutor.Result(ProcessExecutor.Status.EXITED,
                exit, 10L, "", false), true, true).uncertainWrite);
    }

    @Test(timeout = 10_000L)
    public void heldRemoteTransactionLockRejectsNewWriterWithoutWaiting() throws Exception {
        Environment environment = environment();
        Files.createDirectories(environment.directory.resolve("root"));
        Path locked = environment.directory.resolve("locked");
        Path holderScript = environment.directory.resolve("holder.sh");
        Files.writeString(holderScript, "PATH=/usr/bin:/bin; export PATH; exec 9>"
                + quote(environment.directory.resolve("root/transaction.lock"))
                + "; flock -n 9 || exit 90; printf ready > " + quote(locked) + "; sleep 2");
        Process holder = new ProcessBuilder(environment.shell, holderScript.toString())
                .redirectErrorStream(true).start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(locked) && holder.isAlive() && System.nanoTime() < until) {
                Thread.sleep(10L);
            }
            assertTrue("Native fd flock is unavailable", Files.exists(locked));
            assertEquals(RootCommandProtocol.LOCK_BUSY,
                    environment.execute(RootCommandProtocol.put(KEY, "v3::", true, REVISION, DEADLINE)));
            assertEquals("null", environment.value(KEY));
        } finally {
            holder.destroyForcibly();
            holder.waitFor(3, TimeUnit.SECONDS);
        }
    }

    private Environment environment() throws IOException {
        String shell = System.getProperty("os.name").startsWith("Windows")
                ? "C:/msys64/usr/bin/sh.exe" : "/bin/sh";
        Assume.assumeTrue("POSIX shell not installed", new File(shell).isFile());
        Path directory = temporary.newFolder().toPath();
        Path boot = directory.resolve("boot");
        Path uptime = directory.resolve("uptime");
        Path settings = directory.resolve("fake-settings.sh");
        Files.writeString(boot, BOOT + "\n");
        Files.writeString(uptime, "123.45 234.56\n");
        Files.writeString(settings, "#!/bin/sh\n"
                + "[ \"$2\" = global ] || exit 90\n"
                + "case \"$1\" in\n"
                + "put) if [ -f \"$ROOT_TEST_DIR/fail-put\" ]; then exit 12; fi; "
                + "printf '%s' \"$4\" > \"$ROOT_TEST_DIR/$3\"; "
                + "if [ -f \"$ROOT_TEST_DIR/expire-on-put\" ]; then printf '124.01 234.56\\n' > \"$ROOT_TEST_DIR/uptime\"; fi;;\n"
                + "get) if [ -f \"$ROOT_TEST_DIR/fail-get\" ]; then exit 13; fi; "
                + "if [ -f \"$ROOT_TEST_DIR/$3\" ]; then cat \"$ROOT_TEST_DIR/$3\"; else printf null; fi;;\n"
                + "delete) rm -f \"$ROOT_TEST_DIR/$3\";;\n"
                + "*) exit 91;;\nesac\n");
        return new Environment(shell, directory, boot, uptime, settings);
    }

    private static String quote(Path path) {
        return "'" + path.toAbsolutePath().toString().replace('\\', '/').replace("'", "'\\''") + "'";
    }

    private static final class Environment {
        final String shell;
        final Path directory;
        final Path boot;
        final Path uptime;
        final Path settings;

        Environment(String shell, Path directory, Path boot, Path uptime, Path settings) {
            this.shell = shell;
            this.directory = directory;
            this.boot = boot;
            this.uptime = uptime;
            this.settings = settings;
        }

        int execute(String script) {
            String translated = "PATH=/usr/bin:/bin; export PATH; ROOT_TEST_DIR=" + quote(directory) + "; export ROOT_TEST_DIR;\n"
                    + script.replace("'/data/system/selectionmenucontrol'", quote(directory.resolve("root")))
                    .replace("/proc/sys/kernel/random/boot_id", quote(boot))
                    .replace("/proc/uptime", quote(uptime))
                    .replace("/system/bin/settings", "sh " + quote(settings))
                    .replace("/system/bin/flock", "/usr/bin/flock")
                    .replace("/system/bin/", "");
            // A file avoids Windows/MSYS command-line quoting differences; Android uses execve argv.
            Path scriptFile = directory.resolve("transaction.sh");
            try {
                Files.writeString(scriptFile, translated);
            } catch (IOException error) {
                throw new AssertionError(error);
            }
            ProcessExecutor.Result result = new ProcessExecutor((executable, command) ->
                    new ProcessBuilder(executable, scriptFile.toString()).redirectErrorStream(true).start())
                    .execute(shell, "", 3_000L, 2048);
            assertEquals("Shell result=" + result.status + ", output=" + result.output,
                    ProcessExecutor.Status.EXITED, result.status);
            assertEquals("Protocol must not emit raw setting values", "", result.output);
            return result.exitCode;
        }

        String value(String key) throws IOException {
            Path file = directory.resolve(key);
            return Files.exists(file) ? Files.readString(file) : "null";
        }
    }
}
