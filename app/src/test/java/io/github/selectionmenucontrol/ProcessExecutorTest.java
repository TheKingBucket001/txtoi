package io.github.selectionmenucontrol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class ProcessExecutorTest {
    @Test(timeout = 10_000L)
    public void drainsLargeMergedOutputWithoutPipeDeadlockOrRetainingIt() {
        ProcessExecutor.Result result = childExecutor().execute("ignored", "flood", 5_000L, 0);
        assertEquals(ProcessExecutor.Status.EXITED, result.status);
        assertEquals(0, result.exitCode);
        assertEquals("", result.output);
        assertFalse(result.outputTruncated);
    }

    @Test(timeout = 10_000L)
    public void captureIsBoundedEvenWhenProcessOutputsMegabytes() {
        ProcessExecutor.Result result = childExecutor().execute("ignored", "flood", 5_000L, 50_000);
        assertEquals(ProcessExecutor.Status.EXITED, result.status);
        assertEquals(2048, result.output.length());
        assertTrue(result.outputTruncated);
    }

    @Test(timeout = 5_000L)
    public void descendantKeepingPipeOpenDoesNotMakeCallerWaitForEof() {
        ProcessExecutor.Result result = childExecutor().execute("ignored", "retain", 2_000L, 2048);
        assertEquals(ProcessExecutor.Status.EXITED, result.status);
        assertTrue("Retained descendant stdout delayed command: " + result.elapsedMillis,
                result.elapsedMillis < 1_200L);
        assertTrue(result.output.contains("uid=0"));
    }

    @Test(timeout = 5_000L)
    public void timeoutCoversProcessAndItsStreamsAndDestroysLocalClient() throws Exception {
        AtomicReference<Process> launched = new AtomicReference<>();
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> {
            Process process = launchChild("sleep");
            launched.set(process);
            return process;
        });
        ProcessExecutor.Result result = executor.execute("ignored", "", 180L, 0);
        assertEquals(ProcessExecutor.Status.TIMEOUT, result.status);
        assertTrue(result.elapsedMillis < 1_000L);
        assertTrue(launched.get().waitFor(1_000L, TimeUnit.MILLISECONDS));
    }

    @Test(timeout = 5_000L)
    public void interruptTerminatesLocalClientAndPreservesInterruptFlag() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Process> launched = new AtomicReference<>();
        AtomicReference<ProcessExecutor.Result> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>(false);
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> {
            Process process = launchChild("sleep");
            launched.set(process);
            started.countDown();
            return process;
        });
        Thread thread = new Thread(() -> {
            result.set(executor.execute("ignored", "", 2_000L, 0));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        thread.start();
        assertTrue(started.await(2_000L, TimeUnit.MILLISECONDS));
        thread.interrupt();
        thread.join(1_000L);
        assertFalse(thread.isAlive());
        assertEquals(ProcessExecutor.Status.INTERRUPTED, result.get().status);
        assertTrue(interrupted.get());
        assertTrue(launched.get().waitFor(1_000L, TimeUnit.MILLISECONDS));
    }

    @Test(timeout = 5_000L)
    public void sleepInclusiveClockExpiryIsObservedWithoutWaitingRemainingMonotonicTime() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ProcessExecutor.Result> result = new AtomicReference<>();
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> {
            Process process = launchChild("sleep");
            started.countDown();
            return process;
        }, clock::get);
        Thread caller = new Thread(() -> result.set(executor.execute("ignored", "", 3_000L, 0)));
        caller.start();
        assertTrue(started.await(2_000L, TimeUnit.MILLISECONDS));
        clock.addAndGet(6_000_000_000L);
        caller.join(500L);
        assertFalse(caller.isAlive());
        assertEquals(ProcessExecutor.Status.TIMEOUT, result.get().status);
        assertEquals(6_000L, result.get().elapsedMillis);
    }

    @Test
    public void unavailableOutputIsAnExplicitFailureWithoutItsExceptionText() {
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> new FinishedProcess(0,
                new InputStream() {
                    @Override public int read() throws IOException {
                        throw new IOException("private-command-value");
                    }
                    @Override public int available() throws IOException {
                        throw new IOException("private-command-value");
                    }
                }));
        ProcessExecutor.Result result = executor.execute("ignored", "", 200L, 2048);
        assertEquals(ProcessExecutor.Status.OUTPUT_FAILED, result.status);
        assertEquals("", result.output);
    }

    @Test
    public void suDenialNeverStartsAnotherCandidate() {
        AtomicInteger calls = new AtomicInteger();
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> {
            calls.incrementAndGet();
            return new FinishedProcess(1, new ByteArrayInputStream(new byte[0]));
        });
        ProcessExecutor.Result process = RootAccess.executeWithCandidates("id", 2048,
                new String[]{"candidate-one", "candidate-two"}, executor);
        assertEquals(1, calls.get());
        assertEquals(RootAccess.Failure.DENIED, RootAccess.result(process, false, false).failure);
    }

    @Test
    public void onlyAbsentExecutableAllowsFallback() {
        AtomicInteger calls = new AtomicInteger();
        ProcessExecutor executor = new ProcessExecutor((executable, command) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("not found");
            }
            return new FinishedProcess(0, new ByteArrayInputStream("uid=0\n".getBytes()));
        });
        ProcessExecutor.Result process = RootAccess.executeWithCandidates("id", 2048,
                new String[]{"/unavailable-selection-menu-su", "candidate-two"}, executor);
        assertEquals(2, calls.get());
        assertTrue(RootAccess.result(process, false, false).success);
    }

    @Test
    public void writeFailuresRetainTheirUncertainOutcomeButBusyOrExpiredWritesDoNot() {
        for (ProcessExecutor.Status status : new ProcessExecutor.Status[]{
                ProcessExecutor.Status.TIMEOUT, ProcessExecutor.Status.INTERRUPTED,
                ProcessExecutor.Status.OUTPUT_FAILED}) {
            ProcessExecutor.Result process = new ProcessExecutor.Result(status, -1, 123L, "", false);
            assertTrue(RootAccess.result(process, true, false).uncertainWrite);
            assertFalse(RootAccess.result(process, false, false).uncertainWrite);
        }
        ProcessExecutor.Result busy = new ProcessExecutor.Result(ProcessExecutor.Status.EXITED,
                RootCommandProtocol.LOCK_BUSY, 12L, "", false);
        assertFalse(RootAccess.result(busy, true, false).uncertainWrite);
        ProcessExecutor.Result expired = new ProcessExecutor.Result(ProcessExecutor.Status.EXITED,
                RootCommandProtocol.EXPIRED, 12L, "", false);
        assertFalse(RootAccess.result(expired, true, false).uncertainWrite);
        assertTrue(RootAccess.result(expired, true, true).uncertainWrite);
    }

    private static ProcessExecutor childExecutor() {
        return new ProcessExecutor((executable, command) -> launchChild(command));
    }

    private static Process launchChild(String action) throws IOException {
        return new ProcessBuilder(javaExecutable(), "-cp", childClasspath(), Child.class.getName(), action,
                javaExecutable(), childClasspath())
                .redirectErrorStream(true).start();
    }

    private static String javaExecutable() {
        String extension = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        return new File(System.getProperty("java.home"), "bin/java" + extension).getPath();
    }

    private static String childClasspath() {
        try {
            return new File(ProcessExecutorTest.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).getPath();
        } catch (java.net.URISyntaxException error) {
            throw new AssertionError(error);
        }
    }

    public static final class Child {
        public static void main(String[] arguments) throws Exception {
            switch (arguments[0]) {
                case "flood":
                    byte[] block = new byte[8192];
                    java.util.Arrays.fill(block, (byte) 'x');
                    for (int index = 0; index < 128; index++) {
                        System.out.write(block);
                        System.err.write(block);
                    }
                    System.out.flush();
                    System.err.flush();
                    break;
                case "retain":
                    new ProcessBuilder(arguments[1], "-cp", arguments[2], Child.class.getName(),
                            "hold").inheritIO().start();
                    System.out.println("uid=0(root)");
                    break;
                case "hold":
                    Thread.sleep(1_800L);
                    break;
                case "sleep":
                    Thread.sleep(10_000L);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown child action");
            }
        }
    }

    private static final class FinishedProcess extends Process {
        private final int code;
        private final InputStream input;

        FinishedProcess(int code, InputStream input) {
            this.code = code;
            this.input = input;
        }

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return input; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return code; }
        @Override public int exitValue() { return code; }
        @Override public void destroy() { }
        @Override public boolean isAlive() { return false; }
    }
}
