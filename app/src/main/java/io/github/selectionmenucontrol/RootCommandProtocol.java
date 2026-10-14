package io.github.selectionmenucontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Shell protocol for fenced, short-lived writes. Contains no executable discovery or logging. */
final class RootCommandProtocol {
    static final int LOCK_BUSY = 73;
    static final int EXPIRED = 74;
    static final int STALE_REVISION = 75;
    static final int WRITE_FAILED = 76;
    static final int VERIFY_FAILED = 77;
    static final int PROTOCOL_FAILED = 78;
    static final int STORAGE_FAILED = 79;
    private static final String DIRECTORY = "/data/system/selectionmenucontrol";
    private static final int MAX_VALUE_LENGTH = 65_536;

    private RootCommandProtocol() {
    }

    static boolean validKey(String key) {
        return key != null && key.length() <= 128 && key.matches("[a-z0-9_]+");
    }

    static boolean validValue(String value) {
        return value != null && value.length() <= MAX_VALUE_LENGTH
                && value.matches("[A-Za-z0-9_:+/=]+");
    }

    static String put(String key, String value, boolean verify, long revisionNanos,
                      long deadlineMillis) {
        return put(key, value, verify, false, null, revisionNanos, deadlineMillis);
    }

    static String put(String key, String value, boolean verify, boolean compareExpected,
                      String expectedValue, long revisionNanos, long deadlineMillis) {
        if (!validKey(key) || !validValue(value) || !validClock(revisionNanos, deadlineMillis)) {
            return null;
        }
        StringBuilder command = begin(revisionNanos, deadlineMillis);
        fence(command, key);
        if (compareExpected) {
            if (expectedValue != null && !validValue(expectedValue)) {
                return null;
            }
            compare(command, key, expectedValue);
        }
        mark(command, key);
        deadline(command);
        // Keep a single value copy so the su -c argument stays below Linux MAX_ARG_STRLEN.
        command.append("value='").append(value).append("';\n")
                .append("/system/bin/settings put global ").append(key).append(" \"$value\"")
                .append(" >/dev/null 2>&1 || exit ").append(WRITE_FAILED).append(";\n");
        if (verify) {
            command.append("observed=\"$(/system/bin/settings get global ").append(key)
                    .append(" 2>/dev/null)\" || exit ").append(VERIFY_FAILED).append(";\n")
                    .append("[ \"$observed\" = \"$value\" ] || exit ")
                    .append(VERIFY_FAILED).append(";\n");
        }
        return command.append("exit 0\n").toString();
    }

    static String stable(String key, String expectedValue, long revisionNanos, long deadlineMillis) {
        if (!validKey(key) || (expectedValue != null && !validValue(expectedValue))
                || !validClock(revisionNanos, deadlineMillis)) {
            return null;
        }
        StringBuilder command = begin(revisionNanos, deadlineMillis);
        fence(command, key);
        // Advancing this fence cancels older scripts that have not acquired the lock yet.
        mark(command, key);
        deadline(command);
        compare(command, key, expectedValue);
        return command.append("exit 0\n").toString();
    }

    static String probe(String requestKey, String responseKey, String requestPrefix,
                        long revisionNanos, long deadlineMillis) {
        if (!validKey(requestKey) || !validKey(responseKey) || requestKey.equals(responseKey)
                || !validValue(requestPrefix) || !requestPrefix.endsWith(":")
                || requestPrefix.length() > 512 || !validClock(revisionNanos, deadlineMillis)) {
            return null;
        }
        StringBuilder command = begin(revisionNanos, deadlineMillis);
        fence(command, requestKey);
        fence(command, responseKey);
        mark(command, requestKey);
        mark(command, responseKey);
        // A fresh nonce rejects every older response. Do not create an ownerless pending
        // value that prefix cleanup cannot remove when the hook is unavailable.
        // Authorization can be slow. Generate the timestamp immediately before request commit.
        deadline(command);
        command.append("request='").append(requestPrefix).append("'\"$now_ms\";\n")
                .append("/system/bin/settings put global ").append(requestKey)
                .append(" \"$request\" >/dev/null 2>&1 || exit ").append(WRITE_FAILED).append(";\n")
                .append("observed=\"$(/system/bin/settings get global ").append(requestKey)
                .append(" 2>/dev/null)\" || exit ").append(VERIFY_FAILED).append(";\n")
                .append("[ \"$observed\" = \"$request\" ] || exit ")
                .append(VERIFY_FAILED).append(";\nexit 0\n");
        return command.toString();
    }

    static String cleanupProbe(String requestKey, String responseKey, String prefix,
                               long revisionNanos, long deadlineMillis) {
        if (!validKey(requestKey) || !validKey(responseKey) || requestKey.equals(responseKey)
                || !validValue(prefix) || prefix.length() > 512
                || !validClock(revisionNanos, deadlineMillis)) {
            return null;
        }
        StringBuilder command = begin(revisionNanos, deadlineMillis);
        for (String key : new String[]{requestKey, responseKey}) {
            fence(command, key);
        }
        for (String key : new String[]{requestKey, responseKey}) {
            mark(command, key);
            deadline(command);
            command.append("current=\"$(/system/bin/settings get global ").append(key)
                    .append(" 2>/dev/null)\" || exit ").append(PROTOCOL_FAILED).append(";\n")
                    .append("case \"$current\" in '").append(prefix)
                    .append("'*)\n");
            // A slow settings read may outlive the deadline. Check again before a new delete.
            deadline(command);
            command.append("/system/bin/settings delete global ").append(key)
                    .append(" >/dev/null 2>&1 || exit ").append(WRITE_FAILED)
                    .append(";; esac;\n");
        }
        return command.append("exit 0\n").toString();
    }

    private static boolean validClock(long revisionNanos, long deadlineMillis) {
        return revisionNanos > 0L && deadlineMillis > revisionNanos / 1_000_000L;
    }

    private static StringBuilder begin(long revisionNanos, long deadlineMillis) {
        StringBuilder command = new StringBuilder(1200);
        command.append("umask 077;\n")
                .append("LC_ALL=C; export LC_ALL;\n")
                .append("[ -x /system/bin/flock ] || exit ").append(PROTOCOL_FAILED).append(";\n")
                .append("directory='").append(DIRECTORY).append("';\n")
                .append("[ ! -L \"$directory\" ] || exit ").append(STORAGE_FAILED).append(";\n")
                .append("/system/bin/mkdir -p \"$directory\" >/dev/null 2>&1 || exit ")
                .append(STORAGE_FAILED).append(";\n")
                .append("/system/bin/chmod 700 \"$directory\" >/dev/null 2>&1 || exit ")
                .append(STORAGE_FAILED).append(";\n")
                // Android's shell closes extra descriptors before starting Toybox. Use stdin,
                // which remains inherited, as the shared lock descriptor.
                .append("exec 0<>\"$directory/transaction.lock\" || exit ")
                .append(STORAGE_FAILED).append(";\n")
                .append("/system/bin/flock -n 0 >/dev/null 2>&1 || exit ").append(LOCK_BUSY)
                .append(";\n")
                .append("IFS= read -r boot < /proc/sys/kernel/random/boot_id || exit ")
                .append(PROTOCOL_FAILED).append(";\n")
                .append("case \"$boot\" in ''|*[!0-9a-f-]*) exit ")
                .append(PROTOCOL_FAILED).append(";; esac;\n")
                .append("[ \"${#boot}\" -eq 36 ] || exit ").append(PROTOCOL_FAILED).append(";\n")
                .append("revision=").append(revisionNanos).append(";\n")
                // Android mksh arithmetic and numeric test operands wrap at 32 bits.
                // Compare canonical decimal strings, converting only their small lengths.
                .append("decimal_lt() { [ \"${#1}\" -lt \"${#2}\" ] && return 0; ")
                .append("[ \"${#1}\" -eq \"${#2}\" ] && [ \"$1\" '<' \"$2\" ]; };\n")
                // Moving the 10 ms margin into Java preserves now_ms + 10 < deadline
                // without overflowing shell arithmetic after 24.8 days of uptime.
                .append("latest_start_ms=").append(Math.max(0L, deadlineMillis - 10L)).append(";\n")
                .append("clock_now() { IFS=' .' read -r seconds centis ignored < /proc/uptime || return 1; ")
                .append("case \"$seconds\" in ''|*[!0-9]*) return 1;; esac; ")
                .append("case \"$centis\" in [0-9][0-9]) ;; *) return 1;; esac; ")
                .append("now_ms=\"$seconds$centis\"0; ")
                .append("while :; do case \"$now_ms\" in 0?*) now_ms=${now_ms#0};; *) break;; esac; done; ")
                .append("[ \"${#now_ms}\" -le 19 ] || return 1; ")
                .append("! decimal_lt 9223372036854775807 \"$now_ms\"; };\n");
        deadline(command);
        return command;
    }

    private static void deadline(StringBuilder command) {
        command.append("clock_now || exit ").append(PROTOCOL_FAILED).append(";\n")
                // /proc/uptime has centisecond resolution. Reject up to 10 ms early, never late.
                .append("decimal_lt \"$now_ms\" \"$latest_start_ms\" || exit ")
                .append(EXPIRED).append(";\n");
    }

    private static void fence(StringBuilder command, String key) {
        command.append("state=\"$directory/").append(key).append(".revision\";\n")
                .append("if [ -f \"$state\" ]; then IFS=' ' read -r previous_boot previous_revision extra < \"$state\" || exit ")
                .append(PROTOCOL_FAILED).append(";\n")
                .append("case \"$previous_boot\" in ''|*[!0-9a-f-]*) exit ")
                .append(PROTOCOL_FAILED).append(";; esac;\n")
                .append("[ \"${#previous_boot}\" -eq 36 ] || exit ").append(PROTOCOL_FAILED).append(";\n")
                .append("case \"$previous_revision\" in ''|0*|*[!0-9]*) exit ")
                .append(PROTOCOL_FAILED).append(";; esac;\n")
                .append("[ \"${#previous_revision}\" -le 19 ] || exit ").append(PROTOCOL_FAILED).append(";\n")
                .append("! decimal_lt 9223372036854775807 \"$previous_revision\" || exit ")
                .append(PROTOCOL_FAILED).append(";\n")
                .append("[ -z \"$extra\" ] || exit ").append(PROTOCOL_FAILED).append(";\n")
                .append("if [ \"$previous_boot\" = \"$boot\" ] && ! decimal_lt \"$previous_revision\" \"$revision\"; then exit ")
                .append(STALE_REVISION).append("; fi; fi;\n");
    }

    private static void mark(StringBuilder command, String key) {
        command.append("state=\"$directory/").append(key).append(".revision\";\n")
                .append("printf '%s %s\\n' \"$boot\" \"$revision\" > \"$state.next\" || exit ")
                .append(STORAGE_FAILED).append(";\n")
                .append("/system/bin/mv -f \"$state.next\" \"$state\" >/dev/null 2>&1 || exit ")
                .append(STORAGE_FAILED).append(";\n");
    }

    private static void compare(StringBuilder command, String key, String expectedValue) {
        command.append("current=\"$(/system/bin/settings get global ").append(key)
                .append(" 2>/dev/null)\" || exit ").append(PROTOCOL_FAILED).append(";\n");
        if (expectedValue == null) {
            command.append("[ \"$current\" = null ] || exit ").append(STALE_REVISION).append(";\n");
        } else {
            command.append("expected_hash='").append(sha256(expectedValue)).append("';\n")
                    .append("current_hash=\"$(printf '%s' \"$current\" | /system/bin/sha256sum 2>/dev/null)\" || exit ")
                    .append(PROTOCOL_FAILED).append(";\n")
                    .append("current_hash=${current_hash%% *};\n")
                    .append("[ \"$current_hash\" = \"$expected_hash\" ] || exit ")
                    .append(STALE_REVISION).append(";\n");
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(64);
            for (byte part : digest) {
                encoded.append(Character.forDigit((part >>> 4) & 0x0f, 16));
                encoded.append(Character.forDigit(part & 0x0f, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required digest is unavailable");
        }
    }
}
