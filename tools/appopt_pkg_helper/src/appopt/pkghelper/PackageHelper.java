package appopt.pkghelper;

import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class PackageHelper {
    private static final int INSTALL_WAIT_MS = 20000;
    private static final int POLL_MS = 500;
    private static final int OUTPUT_LIMIT = 600;

    private final Object packageManager;

    private PackageHelper() throws Exception {
        this.packageManager = packageManagerService();
    }

    public static void main(String[] args) {
        try {
            if (args.length < 1) {
                usage();
                System.exit(2);
            }
            PackageHelper helper = new PackageHelper();
            String cmd = args[0];
            if ("app-info".equals(cmd)) {
                requireArgs(args, 2);
                helper.appInfo(args[1]);
            } else if ("install".equals(cmd)) {
                requireArgs(args, 2);
                helper.install(args[1]);
            } else {
                usage();
                System.exit(2);
            }
        } catch (Throwable t) {
            print("ok", "0");
            print("error", t.getClass().getName() + ": " + safe(t.getMessage()));
            StackTraceElement[] trace = t.getStackTrace();
            if (trace != null && trace.length > 0) {
                print("where", trace[0].toString());
            }
            System.exit(1);
        }
    }

    private static Object packageManagerService() throws Exception {
        Class<?> appGlobals = Class.forName("android.app.AppGlobals");
        Object service = appGlobals.getMethod("getPackageManager").invoke(null);
        if (service == null) {
            throw new IllegalStateException("package manager service is null");
        }
        return service;
    }

    private static void requireArgs(String[] args, int count) {
        if (args.length < count) {
            throw new IllegalArgumentException("missing argument");
        }
    }

    private static void usage() {
        System.err.println("\u7528\u6cd5:");
        System.err.println("  app-info <package>");
        System.err.println("  install <apk>");
    }

    private void appInfo(String packageName) throws Exception {
        print("ok", "1");
        print("package", packageName);
        PackageInfo info = getPackageInfo(packageName);
        if (info != null) {
            print("installed", "1");
            printPackageInfo(info);
        } else {
            print("installed", "0");
        }
    }

    private void install(String apkPath) throws Exception {
        String packageName = System.getenv("APP_OPT_PACKAGE");
        long expectedVersionCode = parseLong(System.getenv("APP_OPT_VERSION_CODE"), -1);
        String expectedVersionName = System.getenv("APP_OPT_VERSION_NAME");
        File file = new File(apkPath);

        if (!file.isFile()) {
            print("ok", "0");
            print("error", "\u627e\u4e0d\u5230 APK \u6587\u4ef6");
            return;
        }
        if (packageName == null || packageName.length() == 0 || expectedVersionCode < 0) {
            print("ok", "0");
            print("error", "\u7f3a\u5c11 App \u5305\u540d\u6216\u7248\u672c\u4fe1\u606f");
            return;
        }

        CommandResult result = installByCommands(file);
        if (!result.success) {
            print("ok", "0");
            print("error", "\u5b89\u88c5\u547d\u4ee4\u6267\u884c\u5931\u8d25");
            print("command", result.command);
            print("exit", String.valueOf(result.exitCode));
            print("output", limit(result.output));
            return;
        }

        boolean matched = waitForVersion(packageName, expectedVersionCode);
        print("ok", matched ? "1" : "0");
        print("package", packageName);
        print("expectedVersionCode", String.valueOf(expectedVersionCode));
        print("expectedVersionName", safe(expectedVersionName));
        print("method", result.command);
        if (matched) {
            PackageInfo installed = getPackageInfo(packageName);
            print("installed", "1");
            printPackageInfo(installed);
        } else {
            print("error", "\u5b89\u88c5\u547d\u4ee4\u5df2\u6267\u884c\uff0c\u4f46\u7b49\u5f85\u7248\u672c\u53d8\u66f4\u8d85\u65f6");
            print("output", limit(result.output));
        }
    }

    private CommandResult installByCommands(File file) {
        CommandResult last = installByCmdSession(file);
        if (last.success) {
            return last;
        }

        CommandResult direct = runIfExecutable("/system/bin/pm", "install", "-r", "-d", file.getAbsolutePath());
        if (direct.success) {
            return direct;
        }
        last = direct;

        direct = runIfExecutable("/system/bin/cmd", "package", "install", "-r", "-d", file.getAbsolutePath());
        if (direct.success) {
            return direct;
        }
        return direct.exitCode == CommandResult.MISSING ? last : direct;
    }

    private CommandResult installByCmdSession(File file) {
        if (!new File("/system/bin/cmd").canExecute()) {
            return CommandResult.missing("/system/bin/cmd");
        }

        CommandResult create = runCommand("/system/bin/cmd", "package", "install-create", "-r",
            "-S", String.valueOf(file.length()));
        if (!create.success) {
            return create;
        }

        String sessionId = parseSessionId(create.output);
        if (sessionId == null || sessionId.length() == 0) {
            return CommandResult.failure("cmd package install-create", create.exitCode,
                "\u65e0\u6cd5\u89e3\u6790\u5b89\u88c5 session: " + create.output);
        }

        CommandResult write = runCommand("/system/bin/cmd", "package", "install-write",
            "-S", String.valueOf(file.length()), sessionId, "AppOpt.apk", file.getAbsolutePath());
        if (!write.success) {
            abandonSession(sessionId);
            return write;
        }

        CommandResult commit = runCommand("/system/bin/cmd", "package", "install-commit", sessionId);
        if (!commit.success) {
            abandonSession(sessionId);
        }
        return commit;
    }

    private static void abandonSession(String sessionId) {
        runCommand("/system/bin/cmd", "package", "install-abandon", sessionId);
    }

    private boolean waitForVersion(String packageName, long expectedVersionCode) {
        long end = System.currentTimeMillis() + INSTALL_WAIT_MS;
        while (System.currentTimeMillis() < end) {
            try {
                PackageInfo info = getPackageInfo(packageName);
                if (info != null && versionCode(info) == expectedVersionCode) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private PackageInfo getPackageInfo(String packageName) throws Exception {
        for (Method method : packageManager.getClass().getMethods()) {
            if (!"getPackageInfo".equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 3 || types[0] != String.class || types[2] != int.class) {
                continue;
            }
            Object flags;
            if (types[1] == long.class || types[1] == Long.TYPE) {
                flags = 0L;
            } else if (types[1] == int.class || types[1] == Integer.TYPE) {
                flags = 0;
            } else {
                continue;
            }
            Object result = invokeRemote(method, packageManager, packageName, flags, 0);
            return result instanceof PackageInfo ? (PackageInfo) result : null;
        }
        throw new NoSuchMethodException("IPackageManager.getPackageInfo");
    }

    private static Object invokeRemote(Method method, Object target, Object... args) throws Exception {
        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static CommandResult runIfExecutable(String binary, String... args) {
        if (!new File(binary).canExecute()) {
            return CommandResult.missing(binary);
        }
        String[] command = new String[args.length + 1];
        command[0] = binary;
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(command);
    }

    private static CommandResult runCommand(String... command) {
        String commandLine = joinCommand(command);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    output.write(buffer, 0, n);
                }
            }
            int exitCode = process.waitFor();
            String text = output.toString("UTF-8");
            boolean success = exitCode == 0 && looksSuccessful(text);
            return new CommandResult(commandLine, exitCode, text, success);
        } catch (IOException e) {
            return CommandResult.failure(commandLine, -1, e.getClass().getName() + ": " + safe(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.failure(commandLine, -2, e.getClass().getName() + ": " + safe(e.getMessage()));
        }
    }

    private static boolean looksSuccessful(String output) {
        String lower = output == null ? "" : output.toLowerCase();
        return lower.indexOf("failure") < 0 &&
               lower.indexOf("failed") < 0 &&
               lower.indexOf("exception") < 0 &&
               lower.indexOf("error") < 0;
    }

    private static String parseSessionId(String output) {
        if (output == null) {
            return null;
        }
        int open = output.indexOf('[');
        int close = output.indexOf(']', open + 1);
        if (open >= 0 && close > open + 1) {
            return output.substring(open + 1, close).trim();
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            char c = output.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        return digits.length() > 0 ? digits.toString() : null;
    }

    private static void printPackageInfo(PackageInfo info) {
        print("package", safe(info.packageName));
        print("versionCode", String.valueOf(versionCode(info)));
        print("versionName", safe(info.versionName));
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private static String joinCommand(String[] command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(command[i]);
        }
        return sb.toString();
    }

    private static String limit(String value) {
        value = safe(value);
        return value.length() > OUTPUT_LIMIT ? value.substring(0, OUTPUT_LIMIT) : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void print(String key, String value) {
        System.out.println(key + "=" + safe(value));
    }

    private static final class CommandResult {
        static final int MISSING = -127;

        final String command;
        final int exitCode;
        final String output;
        final boolean success;

        CommandResult(String command, int exitCode, String output, boolean success) {
            this.command = command;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.success = success;
        }

        static CommandResult missing(String command) {
            return new CommandResult(command, MISSING, "\u547d\u4ee4\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u6267\u884c", false);
        }

        static CommandResult failure(String command, int exitCode, String output) {
            return new CommandResult(command, exitCode, output, false);
        }
    }
}
