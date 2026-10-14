package io.github.selectionmenucontrol;

import android.os.SystemClock;
import java.io.File;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

final class RootAccess {
    private static final String[] SU_COMMANDS = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
    };
    private static final Pattern ROOT_ID = Pattern.compile(
            "(^|\\s)uid=0(?:\\([^\\r\\n]*\\))?(?=\\s|$)");
    private static final long TIMEOUT_MS = 3_000L;
    private static final AtomicLong LAST_REVISION = new AtomicLong();
    private static final ProcessExecutor EXECUTOR = new ProcessExecutor(SystemClock::elapsedRealtimeNanos);

    enum Failure {
        NONE, INVALID_INPUT, NO_SU, START_FAILED, DENIED, NOT_ROOT, TIMEOUT, INTERRUPTED,
        OUTPUT_FAILED, LOCK_BUSY, EXPIRED, STALE_REVISION, STORAGE_FAILED, WRITE_FAILED,
        VERIFY_FAILED, PROTOCOL_FAILED
    }

    static final class ExecutionResult {
        final boolean success;
        final Failure failure;
        final long elapsedMillis;
        final int exitCode;
        final boolean uncertainWrite;

        ExecutionResult(Failure failure, long elapsedMillis, int exitCode, boolean uncertainWrite) {
            this.success = failure == Failure.NONE;
            this.failure = failure;
            this.elapsedMillis = elapsedMillis;
            this.exitCode = exitCode;
            this.uncertainWrite = uncertainWrite;
        }
    }

    private RootAccess() {
    }

    /** Perform the short, side-effect-free Root gate before any Global mutation. */
    static Result check() {
        return check(executeWithCandidates("id", 2048, SU_COMMANDS, EXECUTOR));
    }

    /** Package-visible seam keeps the identity rule deterministic in unit tests. */
    static Result check(ProcessExecutor.Result process) {
        ExecutionResult execution = result(process, false, false);
        if (execution.success && (process.outputTruncated || !ROOT_ID.matcher(process.output).find())) {
            execution = new ExecutionResult(Failure.NOT_ROOT, process.elapsedMillis,
                    process.exitCode, false);
        }
        return new Result(execution);
    }

    static ExecutionResult putGlobalSettingVerifiedResult(String key, String value) {
        return putGlobalSettingResult(key, value, true);
    }

    /** CAS restoration: expectedValue == null means the Global key must be absent. */
    static ExecutionResult putGlobalSettingVerifiedResult(String key, String value,
                                                         String expectedValue) {
        if (!RootCommandProtocol.validKey(key) || !RootCommandProtocol.validValue(value)
                || (expectedValue != null && !RootCommandProtocol.validValue(expectedValue))) {
            return invalid();
        }
        long revision = nextRevision();
        return executeMutation(RootCommandProtocol.put(key, value, true, true, expectedValue,
                revision, revision / 1_000_000L + TIMEOUT_MS), false);
    }

    private static ExecutionResult putGlobalSettingResult(String key, String value, boolean verify) {
        if (!RootCommandProtocol.validKey(key) || !RootCommandProtocol.validValue(value)) {
            return invalid();
        }
        long revision = nextRevision();
        return executeMutation(RootCommandProtocol.put(key, value, verify, revision,
                revision / 1_000_000L + TIMEOUT_MS), false);
    }

    static ExecutionResult prepareProbe(String requestKey, String responseKey,
                                        String requestPrefix) {
        long revision = nextRevision();
        String command = RootCommandProtocol.probe(requestKey, responseKey,
                requestPrefix, revision, revision / 1_000_000L + TIMEOUT_MS);
        if (command == null) return invalid();
        ProcessExecutor.Result process = executeWithCandidates(command, 0, SU_COMMANDS, EXECUTOR);
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.PROBE_PREPARE_MS, process.elapsedMillis);
        return result(process, true, true);
    }

    static ExecutionResult cleanupProbe(String requestKey, String responseKey, String prefix) {
        long revision = nextRevision();
        long started = SystemClock.elapsedRealtime();
        ExecutionResult result = executeMutation(RootCommandProtocol.cleanupProbe(requestKey, responseKey,
                prefix, revision, revision / 1_000_000L + TIMEOUT_MS), true);
        ModuleDiagnostics.metric(ModuleDiagnostics.Metric.PROBE_CLEANUP_MS,
                SystemClock.elapsedRealtime() - started);
        return result;
    }

    /** A successful fence observes stable Global and blocks all older not-yet-started scripts. */
    static ExecutionResult verifyGlobalStableResult(String key, String expectedValue) {
        if (!RootCommandProtocol.validKey(key)
                || (expectedValue != null && !RootCommandProtocol.validValue(expectedValue))) {
            return invalid();
        }
        long revision = nextRevision();
        String command = RootCommandProtocol.stable(key, expectedValue, revision,
                revision / 1_000_000L + TIMEOUT_MS);
        if (command == null) return invalid();
        ExecutionResult execution = result(executeWithCandidates(command, 0, SU_COMMANDS, EXECUTOR),
                true, false);
        // This protocol changes the revision file only. It never writes a Global setting.
        return new ExecutionResult(execution.failure, execution.elapsedMillis, execution.exitCode, false);
    }

    private static long nextRevision() {
        long clock = SystemClock.elapsedRealtimeNanos();
        return LAST_REVISION.updateAndGet(previous -> Math.max(clock, previous + 1L));
    }

    private static ExecutionResult executeMutation(String command, boolean mayPartiallyExpire) {
        if (command == null) {
            return invalid();
        }
        return result(executeWithCandidates(command, 0, SU_COMMANDS, EXECUTOR), true,
                mayPartiallyExpire);
    }

    static ProcessExecutor.Result executeWithCandidates(String command, int captureLimit,
                                                        String[] candidates, ProcessExecutor executor) {
        long started = executor.clockNowNanos();
        for (String candidate : candidates) {
            long elapsed = (executor.clockNowNanos() - started) / 1_000_000L;
            if (elapsed >= TIMEOUT_MS) {
                return new ProcessExecutor.Result(ProcessExecutor.Status.TIMEOUT, -1,
                        elapsed, "", false);
            }
            ProcessExecutor.Result attempt = executor.execute(candidate, command,
                    TIMEOUT_MS - elapsed, captureLimit);
            if (attempt.status == ProcessExecutor.Status.START_FAILED && isMissingExecutable(candidate)) {
                continue;
            }
            // A launched su refusing authorization or timing out must never trigger more prompts.
            return new ProcessExecutor.Result(attempt.status, attempt.exitCode,
                    elapsed + attempt.elapsedMillis,
                    attempt.output, attempt.outputTruncated);
        }
        return new ProcessExecutor.Result(ProcessExecutor.Status.START_FAILED, -2,
                (executor.clockNowNanos() - started) / 1_000_000L, "", false);
    }

    static ExecutionResult result(ProcessExecutor.Result process, boolean mutation,
                                   boolean mayPartiallyExpire) {
        Failure failure;
        switch (process.status) {
            case START_FAILED:
                failure = process.exitCode == -2 ? Failure.NO_SU : Failure.START_FAILED;
                break;
            case TIMEOUT:
                failure = Failure.TIMEOUT;
                break;
            case INTERRUPTED:
                failure = Failure.INTERRUPTED;
                break;
            case OUTPUT_FAILED:
                failure = Failure.OUTPUT_FAILED;
                break;
            default:
                failure = !mutation ? (process.exitCode == 0 ? Failure.NONE : Failure.DENIED)
                        : mapExit(process.exitCode);
        }
        boolean uncertain = mutation && (failure == Failure.TIMEOUT || failure == Failure.INTERRUPTED
                || failure == Failure.OUTPUT_FAILED || failure == Failure.DENIED
                || failure == Failure.WRITE_FAILED || failure == Failure.VERIFY_FAILED
                || (mayPartiallyExpire && (failure == Failure.EXPIRED
                        || failure == Failure.PROTOCOL_FAILED || failure == Failure.STORAGE_FAILED)));
        return new ExecutionResult(failure, process.elapsedMillis, process.exitCode, uncertain);
    }

    private static Failure mapExit(int exit) {
        switch (exit) {
            case 0: return Failure.NONE;
            case RootCommandProtocol.LOCK_BUSY: return Failure.LOCK_BUSY;
            case RootCommandProtocol.EXPIRED: return Failure.EXPIRED;
            case RootCommandProtocol.STALE_REVISION: return Failure.STALE_REVISION;
            case RootCommandProtocol.WRITE_FAILED: return Failure.WRITE_FAILED;
            case RootCommandProtocol.VERIFY_FAILED: return Failure.VERIFY_FAILED;
            case RootCommandProtocol.PROTOCOL_FAILED: return Failure.PROTOCOL_FAILED;
            case RootCommandProtocol.STORAGE_FAILED: return Failure.STORAGE_FAILED;
            default: return Failure.DENIED;
        }
    }

    private static ExecutionResult invalid() {
        return new ExecutionResult(Failure.INVALID_INPUT, 0L, -1, false);
    }

    static String failureMessage(ExecutionResult execution) {
        switch (execution.failure) {
            case NONE: return "Root 命令执行通过";
            case TIMEOUT: return "Root 命令执行超时，请重试";
            case INTERRUPTED: return "Root 命令被中断";
            case DENIED: return "Root 请求被拒绝";
            case NOT_ROOT: return "su 返回的身份不是 uid=0";
            case NO_SU: return "无法找到可用的 su";
            case START_FAILED: return "无法启动 su";
            case LOCK_BUSY: return "Root 操作繁忙，请重试";
            case EXPIRED: return "Root 操作请求已过期";
            case STALE_REVISION: return "Root 操作请求已被更新";
            case WRITE_FAILED: return "系统设置写入失败";
            case VERIFY_FAILED: return "系统设置写入核验失败";
            case STORAGE_FAILED: return "Root 临时锁不可用";
            case PROTOCOL_FAILED: return "Root 命令协议失败";
            case OUTPUT_FAILED: return "Root 命令输出失败";
            case INVALID_INPUT: return "Root 命令参数无效";
            default: return "Root 命令未完成";
        }
    }

    private static boolean isMissingExecutable(String suCommand) {
        if (suCommand.indexOf('/') >= 0) {
            return !new File(suCommand).isFile();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (new File(directory, suCommand).isFile()) {
                return false;
            }
        }
        return true;
    }

    static final class Result {
        final boolean granted;
        final String message;
        final ExecutionResult execution;

        Result(ExecutionResult execution) {
            this.execution = execution;
            granted = execution.success;
            message = execution.success ? "已获得 uid=0" : failureMessage(execution);
        }
    }

}
