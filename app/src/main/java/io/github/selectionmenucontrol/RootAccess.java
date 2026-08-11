package io.github.selectionmenucontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

final class RootAccess {
    private RootAccess() {
    }

    static Result check() {
        try {
            Process process = new ProcessBuilder("/system/bin/su", "-c", "id")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "Root 授权请求超时");
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line);
                }
            }
            return new Result(process.exitValue() == 0 && output.toString().contains("uid=0"),
                    process.exitValue() == 0 ? "未获得 uid=0" : "Root 请求被拒绝");
        } catch (Throwable ignored) {
            return new Result(false, "无法调用 /system/bin/su");
        }
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
