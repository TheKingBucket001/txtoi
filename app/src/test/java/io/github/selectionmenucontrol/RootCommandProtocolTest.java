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
        assertNull(RootCommandProtocol.probe(KEY, KEY, "v3:nonce:", REVISION, DEADLINE));
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
    public void cleanupExpiryDuringFirstReadLeavesBothProbeKeysUntouched() throws Exception {
        Environment environment = environment();
        String response = "selection_menu_test_response";
        Files.writeString(environment.directory.resolve(KEY), "v2:nonce:123450");
        Files.writeString(environment.directory.resolve(response), "v2:nonce:123460");
        Files.writeString(environment.directory.resolve("expire-on-get-" + KEY), "1");

        int exit = environment.execute(RootCommandProtocol.cleanupProbe(KEY, response,
                "v2:nonce:", REVISION, DEADLINE));

        assertEquals(RootCommandProtocol.EXPIRED, exit);
        assertEquals("v2:nonce:123450", environment.value(KEY));
        assertEquals("v2:nonce:123460", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void cleanupExpiryDuringSecondReadKeepsTheRemainingResponseAndReportsPartialOutcome() throws Exception {
        Environment environment = environment();
        String response = "selection_menu_test_response";
        Files.writeString(environment.directory.resolve(KEY), "v2:nonce:123450");
        Files.writeString(environment.directory.resolve(response), "v2:nonce:123460");
        Files.writeString(environment.directory.resolve("expire-on-get-" + response), "1");

        int exit = environment.execute(RootCommandProtocol.cleanupProbe(KEY, response,
                "v2:nonce:", REVISION, DEADLINE));

        assertEquals(RootCommandProtocol.EXPIRED, exit);
        assertEquals("null", environment.value(KEY));
        assertEquals("v2:nonce:123460", environment.value(response));
        assertTrue(RootAccess.result(new ProcessExecutor.Result(ProcessExecutor.Status.EXITED,
                exit, 10L, "", false), true, true).uncertainWrite);
    }

    @Test(timeout = 10_000L)
    public void cleanupOfAnotherNonceNeverDeletesEvenWhenTheLastReadExpires() throws Exception {
        Environment environment = environment();
        String response = "selection_menu_test_response";
        Files.writeString(environment.directory.resolve(KEY), "v2:othernonce:123450");
        Files.writeString(environment.directory.resolve(response), "v2:othernonce:123460");
        Files.writeString(environment.directory.resolve("expire-on-get-" + response), "1");

        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(KEY, response,
                "v2:nonce:", REVISION, DEADLINE)));
        assertEquals("v2:othernonce:123450", environment.value(KEY));
        assertEquals("v2:othernonce:123460", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void probeUsesCommitTimeAndBatchCleanupChecksItsOwnNonce() throws Exception {
        Environment environment = environment();
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";
        assertEquals(0, environment.execute(RootCommandProtocol.probe(request, response,
                "v3:nonce:11:18:1:", REVISION, DEADLINE)));
        assertEquals("v3:nonce:11:18:1:123450", environment.value(request));
        assertEquals("null", environment.value(response));
        Files.writeString(environment.directory.resolve(response), "v3:nonce:11:18:1:123460");
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v3:othernonce:", REVISION + 10L, DEADLINE)));
        assertEquals("v3:nonce:11:18:1:123450", environment.value(request));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v3:nonce:", REVISION + 11L, DEADLINE)));
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

    @Test(timeout = 30_000L)
    public void revisionsRetainExactOrderingAcrossShellIntegerBoundaries() throws Exception {
        Environment environment = environment();
        long[] revisions = {2_147_483_647L, 2_147_483_648L, 4_294_967_295L,
                4_294_967_296L, 4_294_967_297L, REVISION, Long.MAX_VALUE - 1L, Long.MAX_VALUE};
        for (long revision : revisions) {
            assertEquals("revision=" + revision, 0, environment.execute(RootCommandProtocol.put(
                    KEY, "v3:NEW:", true, revision, Long.MAX_VALUE)));
            assertEquals("same revision=" + revision, RootCommandProtocol.STALE_REVISION,
                    environment.execute(RootCommandProtocol.put(KEY, "v3:EQUAL:", true,
                            revision, Long.MAX_VALUE)));
            assertEquals("older revision=" + revision, RootCommandProtocol.STALE_REVISION,
                    environment.execute(RootCommandProtocol.put(KEY, "v3:OLD:", true,
                            revision - 1L, Long.MAX_VALUE)));
            assertEquals("v3:NEW:", environment.value(KEY));
        }
    }

    @Test(timeout = 10_000L)
    public void revisionRecordsOutsideCanonicalPositiveLongRangeFailClosed() throws Exception {
        Environment environment = environment();
        Files.createDirectories(environment.directory.resolve("root"));
        Path record = environment.directory.resolve("root/" + KEY + ".revision");
        for (String revision : new String[]{"0", "01", "-1", "1x", "9223372036854775808",
                "10000000000000000000"}) {
            Files.writeString(record, BOOT + " " + revision + "\n");
            assertEquals("revision=" + revision, RootCommandProtocol.PROTOCOL_FAILED,
                    environment.execute(RootCommandProtocol.put(KEY, "v3::", true,
                            REVISION, DEADLINE)));
            assertEquals("null", environment.value(KEY));
        }
    }

    @Test(timeout = 10_000L)
    public void probeClockRemainsExactAfterSignedAndUnsigned32BitMilliseconds() throws Exception {
        for (long uptimeMillis : new long[]{2_147_483_640L, 2_147_483_650L, 4_294_967_300L}) {
            Environment environment = environment();
            Files.writeString(environment.uptime, (uptimeMillis / 1_000L) + "."
                    + String.format(java.util.Locale.ROOT, "%02d", (uptimeMillis % 1_000L) / 10L)
                    + " 234.56\n");
            assertEquals("uptime=" + uptimeMillis, 0, environment.execute(RootCommandProtocol.probe(
                    KEY, "selection_menu_test_response", "v3:nonce:",
                    uptimeMillis * 1_000_000L, uptimeMillis + 3_000L)));
            assertEquals("v3:nonce:" + uptimeMillis, environment.value(KEY));
            assertEquals("expired uptime=" + uptimeMillis, RootCommandProtocol.EXPIRED,
                    environment.execute(RootCommandProtocol.put(KEY, "v3:LATE:", true,
                            uptimeMillis * 1_000_000L + 1L, uptimeMillis + 10L)));
            assertEquals("v3:nonce:" + uptimeMillis, environment.value(KEY));
        }
    }

    @Test(timeout = 10_000L)
    public void clockHandlesZeroAndLargestRepresentableMillisecondsWithoutArithmeticOverflow() throws Exception {
        Environment zero = environment();
        Files.writeString(zero.uptime, "0.00 0.00\n");
        assertEquals(0, zero.execute(RootCommandProtocol.probe(KEY, "selection_menu_test_response",
                "v3:nonce:", 1L, 11L)));
        assertEquals("v3:nonce:0", zero.value(KEY));
        assertEquals(RootCommandProtocol.EXPIRED, zero.execute(RootCommandProtocol.put(KEY,
                "v3:LATE:", true, 2L, 10L)));

        Environment maximum = environment();
        Files.writeString(maximum.uptime, "9223372036854775.79 0.00\n");
        assertEquals(0, maximum.execute(RootCommandProtocol.probe(KEY, "selection_menu_test_response",
                "v3:nonce:", Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals("v3:nonce:9223372036854775790", maximum.value(KEY));
        Files.writeString(maximum.uptime, "9223372036854775.80 0.00\n");
        assertEquals(RootCommandProtocol.EXPIRED, maximum.execute(RootCommandProtocol.put(KEY,
                "v3:LATE:", true, Long.MAX_VALUE, Long.MAX_VALUE)));
        assertEquals("v3:nonce:9223372036854775790", maximum.value(KEY));
    }

    @Test(timeout = 10_000L)
    public void invalidOrOutOfLongRangeUptimeNeverBeginsMutation() throws Exception {
        for (String uptime : new String[]{"-1.00", "1.2", "1.xx", "9223372036854775.81",
                "9223372036854776.00", "10000000000000000.00"}) {
            Environment environment = environment();
            Files.writeString(environment.uptime, uptime + " 0.00\n");
            assertEquals("uptime=" + uptime, RootCommandProtocol.PROTOCOL_FAILED,
                    environment.execute(RootCommandProtocol.put(KEY, "v3::", true,
                            REVISION, Long.MAX_VALUE)));
            assertEquals("null", environment.value(KEY));
            assertFalse(Files.exists(environment.directory.resolve("root/" + KEY + ".revision")));
        }
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
    public void unrespondedProbeCleansBothKeysWithoutCreatingAnOwnerlessPendingValue() throws Exception {
        Environment environment = environment();
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";
        assertEquals(0, environment.execute(RootCommandProtocol.probe(request, response,
                "v3:nonce:11:18:1:", REVISION, DEADLINE)));
        assertEquals("null", environment.value(response));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v3:nonce:", REVISION + 10L, DEADLINE)));
        assertEquals("null", environment.value(request));
        assertEquals("null", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void failedProbeVerificationKeepsOwnedRequestThatTheSessionCanClean() throws Exception {
        Environment environment = environment();
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";
        Files.writeString(environment.directory.resolve("fail-get"), "1");
        int exit = environment.execute(RootCommandProtocol.probe(request, response,
                "v3:nonce:11:18:1:", REVISION, DEADLINE));
        assertEquals(RootCommandProtocol.VERIFY_FAILED, exit);
        assertEquals("v3:nonce:11:18:1:123450", environment.value(request));
        assertTrue(RootAccess.result(new ProcessExecutor.Result(ProcessExecutor.Status.EXITED,
                exit, 10L, "", false), true, true).uncertainWrite);
        Files.delete(environment.directory.resolve("fail-get"));
        assertEquals(0, environment.execute(RootCommandProtocol.cleanupProbe(request, response,
                "v3:nonce:", REVISION + 10L, DEADLINE)));
        assertEquals("null", environment.value(request));
        assertEquals("null", environment.value(response));
    }

    @Test(timeout = 10_000L)
    public void heldRemoteTransactionLockRejectsNewWriterWithoutWaiting() throws Exception {
        Environment environment = environment();
        Files.createDirectories(environment.directory.resolve("root"));
        Path locked = environment.directory.resolve("locked");
        Path holderScript = environment.directory.resolve("holder.sh");
        Files.writeString(holderScript, "PATH=/usr/bin:/bin; export PATH; exec 0<>"
                + quote(environment.directory.resolve("root/transaction.lock"))
                + "; flock -n 0 || exit 90; printf ready > " + quote(locked) + "; sleep 2");
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

    @Test(timeout = 10_000L)
    public void mutationLockSurvivesDescriptor9Closing() throws Exception {
        Environment environment = environment();
        String request = "selection_menu_test_request";
        String response = "selection_menu_test_response";

        String command = RootCommandProtocol.probe(request, response, "v3:nonce:11:18:1:",
                REVISION, DEADLINE);
        assertEquals(0, environment.executeWithDescriptor9Closed(command));
        assertEquals("v3:nonce:11:18:1:123450", environment.value(request));
        assertEquals("null", environment.value(response));

        String oldCommand = command.replace("exec 0<>", "exec 9>")
                .replace("flock -n 0", "flock -n 9");
        assertEquals(RootCommandProtocol.LOCK_BUSY,
                environment.executeWithDescriptor9Closed(oldCommand));
    }

    private Environment environment() throws IOException {
        String defaultShell = System.getProperty("os.name").startsWith("Windows")
                ? "C:/msys64/usr/bin/sh.exe" : "/bin/sh";
        // Run this suite with Android's mksh semantics as well as the host shell.
        String shell = System.getProperty("selectionmenu.rootProtocolTestShell", defaultShell);
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
                + "if [ -f \"$ROOT_TEST_DIR/expire-on-get-$3\" ]; then printf '124.01 234.56\\n' > \"$ROOT_TEST_DIR/uptime\"; fi; "
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

        int executeWithDescriptor9Closed(String script) {
            String invocation = "/system/bin/flock -n ";
            int start = script.indexOf(invocation);
            if (start < 0) throw new AssertionError("Generated command has no nonblocking lock");
            return execute(script.substring(0, start) + "exec 9>&-;\n" + script.substring(start));
        }

        int execute(String script) {
            String translated = "PATH=/usr/bin:/bin; export PATH; ROOT_TEST_DIR=" + quote(directory) + "; export ROOT_TEST_DIR;\n"
                    + script.replace("'/data/system/selectionmenucontrol'", quote(directory.resolve("root")))
                    .replace("/proc/sys/kernel/random/boot_id", quote(boot))
                    .replace("/proc/uptime", quote(uptime))
                    .replace("/system/bin/settings", "sh " + quote(settings))
                    .replace("[ -x /system/bin/flock ]", "[ -x /usr/bin/flock ]")
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
