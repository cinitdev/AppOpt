package qixia.pkghelper;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PackageHelper {
    private static final int INSTALL_WAIT_MS = 20000;
    private static final int BINDER_TIMEOUT_MS = 5000;
    private static final int COMMAND_TIMEOUT_MS = 30000;
    private static final int COMMAND_OUTPUT_LIMIT = 64 * 1024;
    private static final int POLL_MS = 500;
    private static final int OUTPUT_LIMIT = 600;
    private static final AtomicBoolean PACKAGE_QUERY_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicBoolean COMPONENT_QUERY_IN_FLIGHT = new AtomicBoolean(false);

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
            } else if ("component-state".equals(cmd)) {
                requireArgs(args, 2);
                int userId = args.length >= 3 ? (int) parseLong(args[2], 0) : 0;
                helper.componentState(args[1], userId);
            } else if ("install".equals(cmd)) {
                requireArgs(args, 2);
                helper.install(args[1]);
            } else {
                usage();
                System.exit(2);
            }
        } catch (Throwable t) {
            while (t instanceof InvocationTargetException && t.getCause() != null) {
                t = t.getCause();
            }
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
        System.err.println("  component-state <package/component> [userId]");
        System.err.println("  install <apk>");
    }

    private void appInfo(String packageName) throws Exception {
        PackageInfo info = getPackageInfo(packageName);
        print("ok", "1");
        print("package", packageName);
        if (info != null) {
            print("installed", "1");
            printPackageInfo(info);
        } else {
            print("installed", "0");
        }
    }

    private void componentState(String flattenedComponent, int userId) throws Exception {
        ComponentName component = ComponentName.unflattenFromString(flattenedComponent);
        if (component == null || userId < 0) {
            throw new IllegalArgumentException("invalid component or userId");
        }
        int state = getComponentEnabledSetting(component, userId);
        print("ok", "1");
        print("component", component.flattenToShortString());
        print("user", String.valueOf(userId));
        print("stateCode", String.valueOf(state));
        print("state", componentStateName(state));
    }

    private static String componentStateName(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "default";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "disabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "disabled-user";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "disabled-until-used";
            default:
                return "unknown";
        }
    }

    private void install(String apkPath) throws Exception {
        String packageName = System.getenv("QIXIA_PACKAGE");
        long expectedVersionCode = parseLong(System.getenv("QIXIA_VERSION_CODE"), -1);
        String expectedVersionName = System.getenv("QIXIA_VERSION_NAME");
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
        if (!"top.qixia.threads".equals(packageName)) {
            print("ok", "0");
            print("error", "拒绝安装非 QixiaThreads 包");
            return;
        }
        String validationError = validateApkBeforeInstall(
            file,
            packageName,
            expectedVersionCode,
            expectedVersionName
        );
        if (validationError != null) {
            print("ok", "0");
            print("error", validationError);
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

        boolean matched = waitForVersion(
            packageName,
            expectedVersionCode,
            expectedVersionName
        );
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
        if (last.success || last.timedOut()) {
            return last;
        }

        CommandResult direct = runIfExecutable("/system/bin/pm", "install", "-r", file.getAbsolutePath());
        if (direct.success || direct.timedOut()) {
            return direct;
        }
        last = direct;

        direct = runIfExecutable("/system/bin/cmd", "package", "install", "-r", file.getAbsolutePath());
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
            "-S", String.valueOf(file.length()), sessionId, "QixiaThreads.apk", file.getAbsolutePath());
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

    private String validateApkBeforeInstall(
            File file,
            String packageName,
            long expectedVersionCode,
            String expectedVersionName) throws Exception {
        PackageInfo archive = getArchivePackageInfo(file.getAbsolutePath());
        if (archive == null) {
            return "无法读取待安装 APK 包信息";
        }
        if (!packageName.equals(archive.packageName)) {
            return "待安装 APK 包名不匹配";
        }
        if (versionCode(archive) != expectedVersionCode) {
            return "待安装 APK 版本号不匹配";
        }
        if (expectedVersionName != null && expectedVersionName.length() > 0 &&
                !expectedVersionName.equals(archive.versionName)) {
            return "待安装 APK 版本名称不匹配";
        }

        Set<String> archiveSigners = signerDigests(archive);
        if (archiveSigners.isEmpty()) {
            return "无法读取待安装 APK 签名证书";
        }
        PackageInfo installed = getPackageInfo(
            packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        );
        if (installed != null) {
            Set<String> installedSigners = signerDigests(installed);
            if (installedSigners.isEmpty() || !installedSigners.equals(archiveSigners)) {
                return "待安装 APK 签名与当前 App 不一致";
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private PackageInfo getArchivePackageInfo(String apkPath) throws Exception {
        // app_process 进入 main() 时尚未初始化 Android Looper；即使助手不打开
        // Activity，ActivityThread 仍会创建 Handler，因此需要先准备消息循环。
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getMethod("systemMain").invoke(null);
        Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        Object value = getSystemContext.invoke(activityThread);
        if (!(value instanceof Context)) {
            throw new IllegalStateException("system context is unavailable");
        }
        Context context = (Context) value;
        return context.getPackageManager().getPackageArchiveInfo(
            apkPath,
            PackageManager.GET_SIGNING_CERTIFICATES
        );
    }

    private static Set<String> signerDigests(PackageInfo info) throws Exception {
        Set<String> result = new HashSet<>();
        if (info == null || info.signingInfo == null) {
            return result;
        }
        android.content.pm.Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers == null) {
            return result;
        }
        for (android.content.pm.Signature signer : signers) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            result.add(toHex(digest.digest(signer.toByteArray())));
        }
        return result;
    }

    private static String toHex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int current = value[i] & 0xff;
            out[i * 2] = digits[current >>> 4];
            out[i * 2 + 1] = digits[current & 0x0f];
        }
        return new String(out);
    }

    private boolean waitForVersion(
            String packageName,
            long expectedVersionCode,
            String expectedVersionName) {
        long end = System.currentTimeMillis() + INSTALL_WAIT_MS;
        while (System.currentTimeMillis() < end) {
            try {
                PackageInfo info = getPackageInfo(packageName);
                if (info != null) {
                    boolean nameMatches = expectedVersionName == null || expectedVersionName.length() == 0 ||
                        expectedVersionName.equals(info.versionName);
                    if (versionCode(info) == expectedVersionCode && nameMatches) {
                        return true;
                    }
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

    private PackageInfo getPackageInfo(final String packageName) throws Exception {
        return getPackageInfo(packageName, 0L);
    }

    private PackageInfo getPackageInfo(final String packageName, final long flags) throws Exception {
        if (!PACKAGE_QUERY_IN_FLIGHT.compareAndSet(false, true)) {
            throw new IOException("previous package manager query is still running");
        }
        FutureTask<PackageInfo> task = new FutureTask<>(() -> {
            try {
                return getPackageInfoUnchecked(packageName, flags);
            } finally {
                // 若 Binder 调用超时后忽略中断，继续保留调用中的标记，
                // 直到原调用真正返回，避免叠加新的调用。
                PACKAGE_QUERY_IN_FLIGHT.set(false);
            }
        });
        Thread worker = new Thread(task, "QixiaThreadsPackageQuery");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            PACKAGE_QUERY_IN_FLIGHT.set(false);
            throw error;
        }
        try {
            return task.get(BINDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IOException("package manager query timed out after " + BINDER_TIMEOUT_MS + "ms", e);
        } catch (ExecutionException e) {
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

    private int getComponentEnabledSetting(final ComponentName component, final int userId)
            throws Exception {
        if (!COMPONENT_QUERY_IN_FLIGHT.compareAndSet(false, true)) {
            throw new IOException("previous component state query is still running");
        }
        FutureTask<Integer> task = new FutureTask<>(() -> {
            try {
                return getComponentEnabledSettingUnchecked(component, userId);
            } finally {
                COMPONENT_QUERY_IN_FLIGHT.set(false);
            }
        });
        Thread worker = new Thread(task, "QixiaThreadsComponentQuery");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException error) {
            COMPONENT_QUERY_IN_FLIGHT.set(false);
            throw error;
        }
        try {
            return task.get(BINDER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new IOException("component state query timed out after " + BINDER_TIMEOUT_MS + "ms", e);
        } catch (ExecutionException e) {
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

    private int getComponentEnabledSettingUnchecked(ComponentName component, int userId)
            throws Exception {
        for (Method method : packageManager.getClass().getMethods()) {
            if (!"getComponentEnabledSetting".equals(method.getName())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 2 || types[0] != ComponentName.class || types[1] != int.class) {
                continue;
            }
            try {
                Object result = method.invoke(packageManager, component, userId);
                return result instanceof Integer ? (Integer) result : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
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
        throw new NoSuchMethodException("IPackageManager.getComponentEnabledSetting");
    }

    private PackageInfo getPackageInfoUnchecked(String packageName, long requestedFlags) throws Exception {
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
                flags = requestedFlags;
            } else if (types[1] == int.class || types[1] == Integer.TYPE) {
                flags = (int) requestedFlags;
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
            Thread reader = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        synchronized (output) {
                            int remaining = COMMAND_OUTPUT_LIMIT - output.size();
                            if (remaining > 0) {
                                output.write(buffer, 0, Math.min(n, remaining));
                            }
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "QixiaThreadsInstallOutput");
            reader.setDaemon(true);
            reader.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (IOException ignored) {
                }
                reader.interrupt();
                reader.join(1000);
                String text;
                synchronized (output) {
                    text = output.toString("UTF-8");
                }
                return CommandResult.failure(commandLine, CommandResult.TIMED_OUT,
                    text + "\ncommand timed out after " + COMMAND_TIMEOUT_MS + "ms");
            }
            reader.join(1000);
            int exitCode = process.exitValue();
            String text;
            synchronized (output) {
                text = output.toString("UTF-8");
            }
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
        static final int TIMED_OUT = -3;

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

        boolean timedOut() {
            return exitCode == TIMED_OUT;
        }
    }
}
