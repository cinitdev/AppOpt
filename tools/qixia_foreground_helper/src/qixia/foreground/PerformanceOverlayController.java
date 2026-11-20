package qixia.foreground;

import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.ActivityManager;
import android.content.pm.PackageInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 前台助手负责触发；服务实际显示悬浮窗口后才确认成功。 */
final class PerformanceOverlayController {
    private static volatile String desired = "", sentPackage = "";
    private static volatile long generation;
    private static volatile boolean inFlight;
    private static volatile int attempts;
    private static boolean bootstrapAttempted;
    private static long lastAttempt;
    private static Context context;
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), runnable -> { Thread t = new Thread(runnable, "QiXiaOverlayTrigger"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.DiscardOldestPolicy());
    static { worker.allowCoreThreadTimeOut(true); }

    static synchronized void update(File config, Handler handler, String focused, boolean interactive) {
        if (SystemLifecycle.shuttingDown()) return;
        String next = interactive && enabled(config, focused) ? focused : "";
        long now = SystemClock.elapsedRealtime();
        if (next.equals(desired)) {
            if (next.isEmpty() || inFlight || CapsuleLaunch.healthy(next) || attempts >= 3 || now - lastAttempt < 10000) return;
        } else {
            desired = next;
            attempts = 0;
            bootstrapAttempted = false;
        }
        final long revision = ++generation;
        lastAttempt = now;
        handler.postDelayed(() -> {
            if (!isCurrent(revision)) return;
            try { initialize(); }
            catch (Exception error) { EventLog.warn("胶囊启动环境初始化失败: " + error); return; }
            worker.execute(() -> {
                if (!isCurrent(revision) || SystemLifecycle.shuttingDown()) return;
                int user = registeredUser(config);
                if (user < 0) return;
                inFlight = true;
                try {
                    String current = desired;
                    if (!sentPackage.isEmpty() && !sentPackage.equals(current)) {
                        dispatch(user, sentPackage, "leave", revision);
                        sentPackage = "";
                    }
                    if (!isCurrent(revision) || current.isEmpty()) return;
                    sentPackage = current;
                    attempts++;
                    if (dispatch(user, current, "show", revision)) attempts = 0;
                } finally { inFlight = false; }
            });
        }, next.isEmpty() || !sentPackage.isEmpty() ? 2400 : 120);
    }

    static boolean isCurrent(long revision) { return generation == revision; }
    /** 同一次前台停留只允许一次 Activity 兜底，后续重试仍可直接启动或通过 Provider 唤醒。 */
    private static synchronized boolean claimBootstrap(long revision) {
        if (!isCurrent(revision) || desired.isEmpty() || bootstrapAttempted || SystemLifecycle.shuttingDown()) return false;
        bootstrapAttempted = true;
        return true;
    }
    static Context systemContext() { return context; }
    static void initializeState(File file) { CapsuleLaunch.initialize(file); }

    private static boolean enabled(File config, String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return false;
        try {
            File calibration = new File(config, "calibrate.state");
            if (calibration.isFile() && calibration.length() <= 4096 &&
                new String(Files.readAllBytes(calibration.toPath()), StandardCharsets.UTF_8).trim().startsWith("sampling ")) return false;
            File file = new File(config, "auto_affinity.conf");
            return file.length() <= 65536 && PerformanceRecordingConfig.enabled(
                new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8), pkg);
        } catch (Exception ignored) { return false; }
    }

    private static int registeredUser(File config) {
        try {
            File file = new File(config, "state/app_storage.conf");
            if (file.length() > 4096) return -1;
            for (String row : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                if (row.startsWith("uid=")) return Integer.parseInt(row.substring(4).trim()) / 100000;
        } catch (Exception ignored) { }
        return -1;
    }

    static void initialize() throws ReflectiveOperationException {
        if (context == null) {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            context = (Context) thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
        }
    }

    private static long appUpdated(int user) throws Exception {
        UserHandle handle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, user);
        Context appContext = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, handle, 0);
        PackageInfo info = appContext.getPackageManager().getPackageInfo("top.qixia.threads", 0);
        return info.lastUpdateTime;
    }

    private static boolean dispatch(int user, String pkg, String action, long revision) {
        CapsuleLaunch launch = null;
        try {
            launch = CapsuleLaunch.begin(pkg, revision, appUpdated(user), "leave".equals(action));
            if ("show".equals(action)) {
                Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
                Class.forName("android.content.pm.IPackageManager")
                    .getMethod("setPackageStoppedState", String.class, boolean.class, int.class)
                    .invoke(pm, "top.qixia.threads", false, user);
            }
            Intent intent = new Intent().setComponent(new ComponentName("top.qixia.threads", "top.qixia.threads.PerformanceOverlayService"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).putExtra("target_pkg", pkg).putExtra("operation", action);
            boolean accepted = startService(user, launch.decorate(intent, "direct"));
            if (accepted && launch.await(4000)) return true;
            if (launch.confirmed()) return true;
            if (!isCurrent(revision) || launch.rejected()) return false;
            if ("leave".equals(action)) { launch.fail(""); return false; }
            boolean awake = wakeProcess(user);
            // Provider 唤醒期间可能收到前一步的成功回执，或用户已经切换应用。
            if (launch.confirmed()) return true;
            if (!isCurrent(revision) || launch.rejected()) return false;
            if (awake && startService(user, launch.decorate(intent, "provider")) && launch.await(4000)) return true;
            if (launch.confirmed()) return true;
            if (!isCurrent(revision) || launch.rejected()) return false;
            if (claimBootstrap(revision)) {
                Intent bootstrap = new Intent().setComponent(new ComponentName("top.qixia.threads", "top.qixia.threads.BootstrapActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("target_pkg", pkg);
                if (bootstrapOverlay(user, launch.decorate(bootstrap, "bootstrap")) && launch.await(4000)) return true;
            }
            if (!launch.rejected()) launch.fail("胶囊未确认显示，请检查悬浮窗权限及系统启动管理");
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            if (launch != null) launch.fail("胶囊启动失败：" + error.getClass().getSimpleName());
            EventLog.warn("胶囊通知失败: " + error);
        }
        return false;
    }

    private static boolean startService(int user, Intent intent) {
        try {
            Object manager = ActivityManager.class.getMethod("getService").invoke(null);
            Object result = Class.forName("android.app.IActivityManager").getMethod("startService",
                Class.forName("android.app.IApplicationThread"), Intent.class, String.class, boolean.class,
                String.class, String.class, int.class).invoke(manager, null, intent, null, true, "com.android.shell", null, user);
            return result instanceof ComponentName && "top.qixia.threads".equals(((ComponentName) result).getPackageName());
        } catch (Exception error) { return false; }
    }

    /** 显式选择用户并核对返回内容，不能把命令退出码当成唤醒成功。 */
    private static boolean wakeProcess(int user) {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/content", "query", "--user", String.valueOf(user),
                "--uri", "content://top.qixia.threads.wake/ping").redirectErrorStream(true).start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) return false;
            byte[] bytes = new byte[4096];
            int length = process.getInputStream().read(bytes);
            String output = length > 0 ? new String(bytes, 0, length, StandardCharsets.UTF_8) : "";
            return process.exitValue() == 0 && output.contains("result=qixia_overlay_ready");
        } catch (Exception error) { return false; }
        finally { if (process != null) process.destroy(); }
    }

    /** 无界面引导仅兜底，真正成功仍等待服务回执。 */
    private static boolean bootstrapOverlay(int user, Intent intent) {
        try {
            Object manager = ActivityManager.class.getMethod("getService").invoke(null);
            for (java.lang.reflect.Method start : Class.forName("android.app.IActivityManager").getMethods()) {
                if (!"startActivityAsUser".equals(start.getName())) continue;
                Class<?>[] types = start.getParameterTypes();
                int totalInts = 0;
                boolean hasIntent = false;
                for (Class<?> type : types) { if (type == int.class) totalInts++; if (type == Intent.class) hasIntent = true; }
                if (!hasIntent || totalInts < 3) continue;
                Object[] args = new Object[types.length];
                int strings = 0, ints = 0;
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == Intent.class) args[i] = intent;
                    else if (types[i] == String.class) args[i] = strings++ == 0 ? "com.android.shell" : null;
                    else if (types[i] == int.class) args[i] = ++ints == totalInts ? user : ints == 1 ? -1 : 0;
                }
                Object value = start.invoke(manager, args);
                if (value instanceof Integer && (Integer) value >= 0 && (Integer) value <= 3) return true;
            }
        } catch (Exception error) { EventLog.warn("胶囊引导失败: " + error); }
        return false;
    }
}
