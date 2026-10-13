package io.github.selectionmenucontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** Runs a short-lived command with one deadline, including output consumption. */
final class ProcessExecutor {
    private static final int BUFFER_SIZE = 4096;
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private final Starter starter;
    private final LongSupplier clock;

    interface Starter {
        Process start(String executable, String command) throws IOException;
    }

    enum Status {
        EXITED, START_FAILED, TIMEOUT, INTERRUPTED, OUTPUT_FAILED
    }

    static final class Result {
        final Status status;
        final int exitCode;
        final long elapsedMillis;
        // The caller requests this only for the bounded `id` response. Commands discard output.
        final String output;
        final boolean outputTruncated;

        Result(Status status, int exitCode, long elapsedMillis, String output,
               boolean outputTruncated) {
            this.status = status;
            this.exitCode = exitCode;
            this.elapsedMillis = elapsedMillis;
            this.output = output;
            this.outputTruncated = outputTruncated;
        }
    }

    ProcessExecutor() {
        this(System::nanoTime);
    }

    ProcessExecutor(LongSupplier clock) {
        this((executable, command) -> new ProcessBuilder(executable, "-c", command)
                .redirectErrorStream(true).start(), clock);
    }

    ProcessExecutor(Starter starter) {
        this(starter, System::nanoTime);
    }

    ProcessExecutor(Starter starter, LongSupplier clock) {
        this.starter = starter;
        this.clock = clock;
    }

    Result execute(String executable, String command, long timeoutMillis, int outputLimit) {
        long started = clock.getAsLong();
        long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
        long deadline = started + timeoutNanos;
        Process process = null;
        OutputPump pump = null;
        Thread outputThread = null;
        Status status = Status.START_FAILED;
        int exitCode = -1;
        try {
            if (Thread.currentThread().isInterrupted()) {
                status = Status.INTERRUPTED;
            } else {
                process = starter.start(executable, command);
                pump = new OutputPump(process.getInputStream(), process, clock, deadline,
                        Math.max(0, Math.min(2048, outputLimit)));
                outputThread = new Thread(pump, "selection-menu-command-output");
                outputThread.setDaemon(true);
                outputThread.start();
                boolean finished = false;
                long remaining;
                while ((remaining = deadline - clock.getAsLong()) > 0L) {
                    if (process.waitFor(Math.min(remaining, WAIT_SLICE_NANOS), TimeUnit.NANOSECONDS)) {
                        finished = true;
                        break;
                    }
                }
                if (!finished || clock.getAsLong() >= deadline) {
                    status = Status.TIMEOUT;
                } else {
                    exitCode = process.exitValue();
                    // A descendant retaining stdout must not keep this call waiting for EOF.
                    pump.exited = true;
                    remaining = deadline - clock.getAsLong();
                    if (remaining > 0L) {
                        outputThread.join(Math.max(1L,
                                TimeUnit.NANOSECONDS.toMillis(remaining)));
                    }
                    status = outputThread.isAlive() || clock.getAsLong() >= deadline ? Status.TIMEOUT
                            : pump.failed ? Status.OUTPUT_FAILED : Status.EXITED;
                }
            }
        } catch (IOException error) {
            // Never retain exception messages: su may include command content in them.
            status = Status.START_FAILED;
        } catch (InterruptedException error) {
            status = Status.INTERRUPTED;
            Thread.currentThread().interrupt();
        } finally {
            if (pump != null) {
                pump.stopped = true;
            }
            if (outputThread != null) {
                outputThread.interrupt();
            }
            if (process != null) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                // Killing the local su client is best-effort cleanup, not remote cancellation.
                close(process.getInputStream());
                close(process.getErrorStream());
                close(process.getOutputStream());
            }
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(clock.getAsLong() - started);
        return new Result(status, exitCode, elapsed,
                pump == null ? "" : pump.output(), pump != null && pump.truncated);
    }

    private static void close(java.io.Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Cleanup does not change an already determined command result.
        }
    }

    private static final class OutputPump implements Runnable {
        private final InputStream input;
        private final Process process;
        private final LongSupplier clock;
        private final long deadline;
        private final int limit;
        private final ByteArrayOutputStream captured;
        volatile boolean stopped;
        volatile boolean exited;
        volatile boolean failed;
        volatile boolean truncated;

        OutputPump(InputStream input, Process process, LongSupplier clock, long deadline, int limit) {
            this.input = input;
            this.process = process;
            this.clock = clock;
            this.deadline = deadline;
            this.limit = limit;
            captured = new ByteArrayOutputStream(limit);
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                while (!stopped && clock.getAsLong() < deadline) {
                    int available = input.available();
                    if (available > 0) {
                        // Process pipe streams expose available bytes; one reader consumes them.
                        // Avoid a blocking read at zero bytes, even if a child retains the fd.
                        int count = input.read(buffer, 0, Math.min(available, buffer.length));
                        if (count < 0) {
                            return;
                        }
                        if (count > 0 && limit > 0) {
                            synchronized (captured) {
                                int keep = Math.min(count, limit - captured.size());
                                captured.write(buffer, 0, keep);
                                truncated |= keep < count;
                            }
                        }
                    } else if (exited || !process.isAlive()) {
                        return;
                    } else {
                        LockSupport.parkNanos(POLL_NANOS);
                    }
                }
            } catch (IOException error) {
                if (!stopped) {
                    failed = true;
                }
            }
        }

        String output() {
            synchronized (captured) {
                return new String(captured.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
