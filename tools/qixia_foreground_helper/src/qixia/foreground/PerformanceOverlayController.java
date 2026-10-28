package qixia.foreground;

import android.os.Handler;
import android.os.SystemClock;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.app.AppOpsManager;
import android.app.ActivityManager;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 只负责前台生命周期通知；采样仍在 Rust，胶囊仍由 APK 绘制。 */
final class PerformanceOverlayController {
    private static volatile String shown = "", desired = "";
    private static volatile long generation;
    private static long lastAttempt;
    private static Context systemContext;
    private static final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(4), runnable -> { Thread t = new Thread(runnable, "QiXiaOverlayTrigger"); t.setDaemon(true); return t; },
        new ThreadPoolExecutor.DiscardOldestPolicy());
    static { worker.allowCoreThreadTimeOut(true); }

    static void update(File config, Handler handler, String focused, boolean interactive) {
        String next = interactive && enabled(config, focused) ? focused : "";
        long now = SystemClock.elapsedRealtime();
        // 同一前台也定期确认服务存活；被清理后可重新拉起，失败不能永久锁住 shown。
        if (next.equals(desired) && ((next.isEmpty() && shown.isEmpty()) || now-lastAttempt < 10000)) return;
        lastAttempt = now;
        desired = next;
        final long revision = ++generation;
        // 系统权限页、通知栏等短暂切换不反复拆建悬浮窗。
        handler.postDelayed(() -> {
            if (generation != revision) return;
            String current = desired;
            // ActivityThread 必须在有主消息循环的线程初始化，不能在通知工作线程创建。
            try { initialize(); }
            catch (Exception error) { System.err.println("胶囊启动环境初始化失败: " + error); return; }
            worker.execute(() -> {
                if (generation != revision) return;
                int user = registeredUser(config);
                if (user < 0) return;
                String old = shown;
                if (!old.isEmpty() && !old.equals(current)) {
                    if (!dispatch(user, old, "leave")) return;
                    shown = "";
                }
                if (generation != revision) return;
                if (!current.isEmpty() && dispatch(user, current, "show")) shown = current;
            });
        }, next.isEmpty() || !shown.isEmpty() ? 2400 : 120);
    }

    private static boolean enabled(File config, String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return false;
        try {
            File calibration = new File(config, "calibrate.state");
            if (calibration.isFile() && calibration.length() <= 4096 &&
                new String(Files.readAllBytes(calibration.toPath()), StandardCharsets.UTF_8).trim().startsWith("sampling ")) return false;
            File file = new File(config, "calib_policy.conf");
            if (file.length() > 65536) return false;
            String match = null;
            for (String row : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String clean = row.split("#", 2)[0].trim();
                int split = clean.indexOf('=');
                if (split < 0 || !clean.substring(0, split).trim().equals("performance_recording_apps")) continue;
                if (match != null) return false;
                match = clean.substring(split + 1);
            }
            if (match != null) for (String entry : match.split(",")) if (entry.trim().equals(pkg)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private static int registeredUser(File config) {
        try {
            File file = new File(config, "state/app_storage.conf");
            if (file.length() > 4096) return -1;
            for (String row : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                if (row.startsWith("uid=")) return Integer.parseInt(row.substring(4).trim()) / 100000;
            }
        } catch (Exception ignored) { }
        return -1;
    }

    static void initialize() throws ReflectiveOperationException {
        if (systemContext == null) {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            systemContext = (Context)thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
        }
    }

    static boolean dispatch(int user, String pkg, String action) {
        try {
            if ("show".equals(action)) {
                // 仅在用户持久启用的目标进入前台时解除停止标志，不打开主界面。
                Object pm = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
                Class.forName("android.content.pm.IPackageManager")
                    .getMethod("setPackageStoppedState",String.class,boolean.class,int.class)
                    .invoke(pm,"top.qixia.threads",false,user);
            }
            Intent intent = new Intent().setComponent(new ComponentName("top.qixia.threads", "top.qixia.threads.PerformanceOverlayService"))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("target_pkg",pkg).putExtra("operation",action);
            // 直接核对框架返回值，避免 am 只打印错误却仍返回成功退出码。
            Object manager = ActivityManager.class.getMethod("getService").invoke(null);
            java.lang.reflect.Method start = Class.forName("android.app.IActivityManager").getMethod("startService",
                Class.forName("android.app.IApplicationThread"),Intent.class,String.class,boolean.class,
                String.class,String.class,int.class);
            // Root app_process 没有注册为 APK 的 ApplicationThread，必须使用空 caller，
            // 与 am 一样由 Binder 的实际 UID 鉴权，不能传入 systemMain 的虚拟应用线程。
            Object result = start.invoke(manager,null,intent,null,true,"com.android.shell",null,user);
            if (result == null && "show".equals(action) &&
                Integer.valueOf(AppOpsManager.MODE_ALLOWED).equals(vendorAutoStartMode(user, true))) {
                result = start.invoke(manager,null,intent,null,true,"com.android.shell",null,user);
            }
            if (!(result instanceof ComponentName)) throw new IllegalStateException("系统未接受胶囊服务启动请求");
            ComponentName component = (ComponentName)result;
            if (!"top.qixia.threads".equals(component.getPackageName())) {
                throw new IllegalStateException("胶囊启动被拒绝: " + component);
            }
            return true;
        } catch (Exception error) {
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            System.err.println("性能胶囊通知失败: " + action + " " + cause);
            return false;
        }
    }

    /** 环境检查只读取；不支持检测或读取失败时不能假定已经授权。 */
    static String autoStartStatus(int user) {
        try {
            Integer mode = vendorAutoStartMode(user, false);
            if (mode == null) return "unknown";
            if (mode == AppOpsManager.MODE_ALLOWED) return "allowed";
            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) return "blocked";
        } catch (Exception error) { System.err.println("自启动状态读取失败: " + error); }
        return "unknown";
    }

    /** 只有胶囊拉起失败后的既有恢复路径允许补齐权限；环境检查传入 false。 */
    private static Integer vendorAutoStartMode(int user, boolean allow) throws ReflectiveOperationException {
        final int op;
        try { op = AppOpsManager.class.getField("OP_AUTO_START").getInt(null); }
        catch (NoSuchFieldException unsupported) { return null; }
        Object pm = systemContext.getPackageManager();
        ApplicationInfo app = (ApplicationInfo)pm.getClass()
            .getMethod("getApplicationInfoAsUser",String.class,int.class,int.class)
            .invoke(pm,"top.qixia.threads",0,user);
        AppOpsManager ops = systemContext.getSystemService(AppOpsManager.class);
        java.lang.reflect.Method check = AppOpsManager.class.getMethod("checkOpNoThrow",int.class,int.class,String.class);
        int current = (Integer)check.invoke(ops,op,app.uid,app.packageName);
        if (!allow || current == AppOpsManager.MODE_ALLOWED) return current;
        AppOpsManager.class.getMethod("setMode",int.class,int.class,String.class,int.class)
            .invoke(ops,op,app.uid,app.packageName,AppOpsManager.MODE_ALLOWED);
        int updated = (Integer)check.invoke(ops,op,app.uid,app.packageName);
        if (updated == AppOpsManager.MODE_ALLOWED) System.err.println("性能胶囊已启用柒夏线程自启动权限");
        return updated;
    }
}
