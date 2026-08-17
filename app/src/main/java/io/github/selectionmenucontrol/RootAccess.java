package io.github.selectionmenucontrol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
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

    private RootAccess() {
    }

    static Result check() {
        String lastError = "无法找到可用的 su";
        for (String suCommand : SU_COMMANDS) {
            try {
                return checkWith(suCommand);
            } catch (IOException error) {
                lastError = "无法调用 su";
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Result(false, "Root 检测被中断");
            }
        }
        return new Result(false, lastError);
    }

    static boolean putGlobalSetting(String key, String value) {
        if (key == null || !key.matches("[a-z0-9_]+")
                || value == null || !value.matches("[A-Za-z0-9_:+/=]+")) {
            return false;
        }
        String command = "/system/bin/settings put global " + key + " " + value;
        for (String suCommand : SU_COMMANDS) {
            try {
                Process process = start(suCommand, command);
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0;
            } catch (IOException error) {
                // Only fall back when the candidate executable does not exist.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static Result checkWith(String suCommand) throws IOException, InterruptedException {
        Process process = start(suCommand, "id");
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return new Result(false, "Root 授权请求超时");
        }
        String output = readOutput(process);
        if (process.exitValue() == 0 && ROOT_ID.matcher(output).find()) {
            return new Result(true, "已获得 uid=0");
        }
        return new Result(false, process.exitValue() == 0
                ? "su 返回的身份不是 uid=0" : "Root 请求被拒绝");
    }

    private static Process start(String suCommand, String command) throws IOException {
        return new ProcessBuilder(suCommand, "-c", command)
                .redirectErrorStream(true)
                .start();
    }

    private static String readOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    static final class Result {
        final boolean granted;
        final String message;

        Result(boolean granted, String message) {
            this.granted = granted;
            this.message = message;
        }
    }
}
