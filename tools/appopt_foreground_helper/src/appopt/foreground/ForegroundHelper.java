package appopt.foreground;

import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * 常驻 root app_process 前台任务监听器。
 *
 * ActivityTaskManager 和 TaskStackListener 都由设备 framework 提供。本 helper 只依赖
 * Android 12-17 一直存在的 getTasks(int) 门面和三个稳定回调，避免绑定会变化的 Binder
 * 事务编号及底层 getTasks 参数。
 */
public final class ForegroundHelper {
    private static final long LISTENER_RECONCILE_MS = 5000L;
    private static final long LISTENER_REGISTER_RETRY_MS = 5000L;
    private static final long BOOT_ID_READ_RETRY_MS = 60000L;
    private static final long POLL_RETRY_MS = 1200L;
    private static final long UID_MAP_INITIAL_DELAY_MS = 1500L;
    private static final long UID_MAP_RECONCILE_MS = 30000L;
    private static final long UID_MAP_FORCE_REFRESH_MS = 300000L;
    private static final long EVENT_DEBOUNCE_MS = 80L;
    private static final int MAX_TASKS = 24;
    private static final int MAX_EXIT_RECORDS = 64;
    private static final int MAX_RESTORED_LIFECYCLE_RECORDS = 64;
    private static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");

    private static final Object SCHEDULE_LOCK = new Object();
    private static Handler handler;
    private static File stateFile;
    private static File configDir;
    private static File appListFile;
    private static File uidMapFile;
    private static Object activityTaskManager;
    private static Object packageManager;
    private static Method getTasksMethod;
    private static Method registerMethod;
    private static Method unregisterMethod;
    private static Listener listener;
    private static boolean listenerRegistered;
    private static long lastListenerRegisterAttemptElapsed;
    private static boolean refreshScheduled;
    private static long pendingReliableEventElapsed;
    private static final List<FrontMoveEvent> pendingFrontMoveEvents = new ArrayList<>();
    private static long generation;
    private static long lastUidMapConfigMtime = -1L;
    private static long lastUidMapConfigLength = -1L;
    private static long lastUidMapSyncElapsed = 0L;
    private static int lastUidMapCount = -1;
    private static String startupError = "";
    private static String bootId = "";
    private static long lastBootIdReadAttemptElapsed;
    private static String lastReliableFrontPackage;
    private static Set<String> lastInScopePackages = new LinkedHashSet<>();
    private static final TreeMap<String, Long> lastExitElapsed = new TreeMap<>();
    private static final TreeMap<String, LifecycleRecord> lifecyclePackages = new TreeMap<>();

    private ForegroundHelper() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || args[0] == null || args[0].isEmpty()) {
            System.err.println("用法: ForegroundHelper <state-file>");
            System.exit(2);
        }

        stateFile = new File(args[0]);
        File parent = stateFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.err.println("无法创建状态目录: " + parent);
            System.exit(1);
        }
        configDir = parent == null ? new File("/data/adb/modules/AppOpt/config") : parent;
        appListFile = new File(configDir, "applist.conf");
        uidMapFile = new File(configDir, "package_uid.map");
        lastBootIdReadAttemptElapsed = SystemClock.elapsedRealtime();
        bootId = readBootId();
        restorePreviousState();

        Looper.prepare();
        handler = new Handler(Looper.myLooper());
        connectActivityTaskManager();
        Runtime.getRuntime().addShutdownHook(new Thread(
            ForegroundHelper::unregisterListener,
            "AppOptForegroundShutdown"
        ));
        requestRefresh("start", 0L);
        handler.postDelayed(ForegroundHelper::uidMapLoop, UID_MAP_INITIAL_DELAY_MS);
        handler.postDelayed(ForegroundHelper::reconcile, listenerRegistered
            ? LISTENER_RECONCILE_MS : POLL_RETRY_MS);
        Looper.loop();
    }

    private static void connectActivityTaskManager() {
        try {
            Class<?> managerClass = Class.forName("android.app.ActivityTaskManager");
            Method getInstance = managerClass.getMethod("getInstance");
            activityTaskManager = invoke(getInstance, null);
            if (activityTaskManager == null) {
                throw new IllegalStateException("ActivityTaskManager.getInstance() returned null");
            }

            getTasksMethod = managerClass.getMethod("getTasks", int.class);
            Class<?> listenerClass = Class.forName("android.app.TaskStackListener");
            registerMethod = managerClass.getMethod("registerTaskStackListener", listenerClass);
            unregisterMethod = managerClass.getMethod("unregisterTaskStackListener", listenerClass);
            listener = new Listener();

            tryRegisterListener("connect");
        } catch (Throwable error) {
            activityTaskManager = null;
            getTasksMethod = null;
            registerMethod = null;
            unregisterMethod = null;
            listener = null;
            listenerRegistered = false;
            startupError = "connect=" + errorText(error);
            writeErrorState("connect", error);
            System.err.println("[前台助手] ActivityTaskManager 初始化失败: " + startupError);
        }
    }

    private static void reconcile() {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (!listenerRegistered
                && nowElapsed - lastListenerRegisterAttemptElapsed >= LISTENER_REGISTER_RETRY_MS) {
            tryRegisterListener("retry");
        }
        final boolean listenerRefreshPending;
        synchronized (SCHEDULE_LOCK) {
            listenerRefreshPending = refreshScheduled;
        }
        // 已入队的监听器刷新持有捕获到的事件时间。此处若直接校准，可能提前消耗状态变化，
        // 导致监听器刷新时无法再把应用离开事件关联到原来的事件时间。
        if (!listenerRefreshPending) {
            refreshState(listenerRegistered ? "reconcile" : "poll", 0L);
        }
        handler.postDelayed(ForegroundHelper::reconcile, listenerRegistered
            ? LISTENER_RECONCILE_MS : POLL_RETRY_MS);
    }

    private static boolean tryRegisterListener(String reason) {
        if (listenerRegistered) {
            return true;
        }
        if (activityTaskManager == null || registerMethod == null || listener == null) {
            return false;
        }

        lastListenerRegisterAttemptElapsed = SystemClock.elapsedRealtime();
        try {
            invoke(registerMethod, activityTaskManager, listener);
            listenerRegistered = true;
            startupError = "";
            System.out.println("[前台助手] TaskStackListener 已注册, SDK="
                + Build.VERSION.SDK_INT + " reason=" + reason);
            return true;
        } catch (Throwable listenerError) {
            listenerRegistered = false;
            String error = "listener=" + errorText(listenerError);
            boolean changed = !error.equals(startupError);
            startupError = error;
            if (changed || "connect".equals(reason)) {
                System.err.println("[前台助手] TaskStackListener 注册失败, 降级轮询: "
                    + startupError);
            }
            return false;
        }
    }

    private static void requestRefresh(String reason, long delayMs) {
        final long eventElapsed = reason.startsWith("task_")
            ? SystemClock.elapsedRealtime() : 0L;
        requestRefresh(reason, delayMs, eventElapsed, null);
    }

    private static void requestMovedToFrontRefresh(
            String movedPackage,
            long eventElapsed,
            long delayMs
    ) {
        requestRefresh("task_moved_to_front", delayMs, eventElapsed, movedPackage);
    }

    private static void requestRefresh(
            String reason,
            long delayMs,
            long eventElapsed,
            String movedPackage
    ) {
        synchronized (SCHEDULE_LOCK) {
            if (eventElapsed > 0L && (pendingReliableEventElapsed == 0L
                    || eventElapsed < pendingReliableEventElapsed)) {
                pendingReliableEventElapsed = eventElapsed;
            }
            if (eventElapsed > 0L && movedPackage != null && !movedPackage.isEmpty()) {
                pendingFrontMoveEvents.add(new FrontMoveEvent(movedPackage, eventElapsed));
            }
            if (refreshScheduled) {
                return;
            }
            refreshScheduled = true;
        }
        handler.postDelayed(() -> {
            final long reliableEventElapsed;
            final List<FrontMoveEvent> frontMoveEvents;
            synchronized (SCHEDULE_LOCK) {
                refreshScheduled = false;
                reliableEventElapsed = pendingReliableEventElapsed;
                pendingReliableEventElapsed = 0L;
                frontMoveEvents = new ArrayList<>(pendingFrontMoveEvents);
                pendingFrontMoveEvents.clear();
            }
            applyFrontMoveEvents(frontMoveEvents);
            refreshState(reason, reliableEventElapsed);
        }, delayMs);
    }

    private static void applyFrontMoveEvents(List<FrontMoveEvent> events) {
        events.sort((left, right) -> Long.compare(left.elapsed, right.elapsed));
        for (FrontMoveEvent event : events) {
            String previous = lastReliableFrontPackage;
            if (previous != null && !previous.equals(event.packageName)
                    && (lifecyclePackages.containsKey(previous)
                        || lastInScopePackages.contains(previous))) {
                recordExit(previous, event.elapsed);
                lifecyclePackages.remove(previous);
            }
            lastReliableFrontPackage = event.packageName;
        }
        pruneExitRecords();
    }

    @SuppressWarnings("unchecked")
    private static void refreshState(String reason, long reliableEventElapsed) {
        refreshBootIdIfNeeded();
        if (activityTaskManager == null || getTasksMethod == null) {
            connectActivityTaskManager();
            if (activityTaskManager == null || getTasksMethod == null) {
                return;
            }
        }

        try {
            // 此时间戳描述快照查询的开始时刻；若使用查询结束时间，可能把健康检查截止前
            // 刚发生的状态变化误判到截止后。
            long snapshotElapsed = SystemClock.elapsedRealtime();
            long snapshotWall = System.currentTimeMillis();
            Object value = invoke(getTasksMethod, activityTaskManager, MAX_TASKS);
            if (!(value instanceof List)) {
                throw new IllegalStateException("ActivityTaskManager.getTasks returned no list");
            }
            List<ActivityManager.RunningTaskInfo> tasks =
                (List<ActivityManager.RunningTaskInfo>) value;
            writeTaskState(tasks, reason, snapshotElapsed, snapshotWall, reliableEventElapsed);
        } catch (Throwable error) {
            writeErrorState(reason, error);
            unregisterListener();
            activityTaskManager = null;
            getTasksMethod = null;
            registerMethod = null;
            unregisterMethod = null;
            listener = null;
            listenerRegistered = false;
            System.err.println("[前台助手] 读取任务失败: " + errorText(error));
        }
    }

    private static void writeTaskState(
            List<ActivityManager.RunningTaskInfo> tasks,
            String reason,
            long snapshotElapsed,
            long snapshotWall,
            long reliableEventElapsed
    ) {
        TaskRecord focused = null;
        TaskRecord defaultVisible = null;
        TaskRecord firstVisible = null;
        TaskRecord first = null;
        Set<String> visiblePackages = new LinkedHashSet<>();
        int taskCount = tasks == null ? 0 : tasks.size();
        int usableTaskCount = 0;

        if (tasks != null) {
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || task.topActivity == null) {
                    continue;
                }
                usableTaskCount++;
                TaskRecord record = TaskRecord.from(task);
                if (first == null) {
                    first = record;
                }
                if (record.visible) {
                    visiblePackages.add(record.component.getPackageName());
                    if (firstVisible == null) {
                        firstVisible = record;
                    }
                    if (defaultVisible == null && record.displayId == 0) {
                        defaultVisible = record;
                    }
                }
                if (record.focused && focused == null) {
                    focused = record;
                }
            }
        }
        if (taskCount > 0 && usableTaskCount == 0) {
            throw new IllegalStateException("task snapshot contains no usable top activity");
        }

        TaskRecord selected = focused != null ? focused
            : defaultVisible != null ? defaultVisible
            : firstVisible != null ? firstVisible
            : first;
        final String selection = focused != null ? "focused"
            : defaultVisible != null ? "default-visible"
            : firstVisible != null ? "visible"
            : first != null ? "first" : "none";
        final TaskRecord result = selected;
        lastReliableFrontPackage = result != null && isHealthSelection(selection)
            ? result.component.getPackageName() : null;
        Set<String> inScopePackages = new LinkedHashSet<>(visiblePackages);
        if (result != null && isHealthSelection(selection)) {
            inScopePackages.add(result.component.getPackageName());
        }
        long observedExitElapsed = reliableEventElapsed > 0L
            ? Math.min(reliableEventElapsed, snapshotElapsed) : snapshotElapsed;
        for (String pkg : lastInScopePackages) {
            if (inScopePackages.contains(pkg)) {
                continue;
            }
            recordExit(pkg, observedExitElapsed);
            lifecyclePackages.remove(pkg);
        }
        for (String pkg : inScopePackages) {
            if (!lifecyclePackages.containsKey(pkg)) {
                lifecyclePackages.put(pkg, new LifecycleRecord(snapshotElapsed, snapshotWall));
            }
        }
        // 应用快速重新进入后仍保留退出记录。消费者会将退出时间与生命周期进入时间比较，
        // 因此旧退出记录不会中断新生命周期，也不会在两次读取之间丢失。
        lastInScopePackages = new LinkedHashSet<>(inScopePackages);
        pruneExitRecords();
        final long currentGeneration = ++generation;
        writeState(out -> {
            line(out, "version", "2");
            line(out, "boot_id", bootId);
            line(out, "status", result == null ? "empty" : "ok");
            line(out, "mode", listenerRegistered ? "listener" : "poll");
            line(out, "sdk", String.valueOf(Build.VERSION.SDK_INT));
            line(out, "pid", String.valueOf(Process.myPid()));
            line(out, "generation", String.valueOf(currentGeneration));
            line(out, "updated_elapsed_ms", String.valueOf(snapshotElapsed));
            line(out, "updated_wall_ms", String.valueOf(snapshotWall));
            line(out, "reason", reason);
            line(out, "selection", selection);
            line(out, "focused_package", result == null ? "" : result.component.getPackageName());
            line(out, "focused_activity", result == null ? "" : result.component.flattenToShortString());
            line(out, "focused_task_id", result == null ? "0" : String.valueOf(result.taskId));
            line(out, "focused_display_id", result == null ? "-1" : String.valueOf(result.displayId));
            line(out, "focused_flag", result != null && result.focused ? "1" : "0");
            line(out, "focused_visible", result != null && result.visible ? "1" : "0");
            line(out, "visible_packages", join(visiblePackages));
            line(out, "lifecycle_packages", joinLifecycleRecords());
            line(out, "exited_packages", joinExitRecords());
            line(out, "task_count", String.valueOf(taskCount));
            line(out, "error", startupError);
        });
    }

    private static void writeErrorState(String reason, Throwable error) {
        if (stateFile == null) {
            return;
        }
        final long currentGeneration = ++generation;
        writeState(out -> {
            line(out, "version", "2");
            line(out, "boot_id", bootId);
            line(out, "status", "error");
            line(out, "mode", listenerRegistered ? "listener" : "poll");
            line(out, "sdk", String.valueOf(Build.VERSION.SDK_INT));
            line(out, "pid", String.valueOf(Process.myPid()));
            line(out, "generation", String.valueOf(currentGeneration));
            line(out, "updated_elapsed_ms", String.valueOf(SystemClock.elapsedRealtime()));
            line(out, "updated_wall_ms", String.valueOf(System.currentTimeMillis()));
            line(out, "reason", reason);
            line(out, "selection", "none");
            line(out, "focused_package", "");
            line(out, "focused_activity", "");
            line(out, "focused_task_id", "0");
            line(out, "focused_display_id", "-1");
            line(out, "focused_flag", "0");
            line(out, "focused_visible", "0");
            line(out, "visible_packages", "");
            line(out, "lifecycle_packages", joinLifecycleRecords());
            line(out, "exited_packages", joinExitRecords());
            line(out, "task_count", "0");
            line(out, "error", errorText(error));
        });
    }

    private static void writeState(StateWriter writer) {
        File parent = stateFile.getParentFile();
        File temp = new File(parent, stateFile.getName() + ".tmp." + Process.myPid());
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
            new FileOutputStream(temp, false), StandardCharsets.UTF_8))) {
            writer.write(out);
            out.flush();
            if (out.checkError()) {
                throw new IllegalStateException("state write failed");
            }
        } catch (Throwable error) {
            System.err.println("[前台助手] 写入状态失败: " + errorText(error));
            temp.delete();
            return;
        }

        if (!temp.renameTo(stateFile)) {
            stateFile.delete();
            if (!temp.renameTo(stateFile)) {
                System.err.println("[前台助手] 原子替换状态文件失败: " + stateFile);
                temp.delete();
                return;
            }
        }
        stateFile.setReadable(true, false);
    }

    private static void uidMapLoop() {
        syncPackageUidMap("loop");
        handler.postDelayed(ForegroundHelper::uidMapLoop, UID_MAP_RECONCILE_MS);
    }

    private static void syncPackageUidMap(String reason) {
        if (appListFile == null || uidMapFile == null || !appListFile.isFile()) {
            return;
        }

        long mtime = appListFile.lastModified();
        long length = appListFile.length();
        long nowElapsed = SystemClock.elapsedRealtime();
        boolean uidMapMissing = !uidMapFile.isFile() || uidMapFile.length() == 0;
        boolean forceRefresh = nowElapsed - lastUidMapSyncElapsed >= UID_MAP_FORCE_REFRESH_MS;
        if (!uidMapMissing
                && !forceRefresh
                && mtime == lastUidMapConfigMtime
                && length == lastUidMapConfigLength
                && lastUidMapCount >= 0) {
            return;
        }

        Set<String> packages;
        try {
            packages = readRulePackages(appListFile);
        } catch (Throwable error) {
            System.err.println("[前台助手] 读取 applist.conf 失败: " + errorText(error));
            return;
        }

        TreeMap<String, Integer> uidMap = new TreeMap<>();
        if (!packages.isEmpty()) {
            try {
                ensurePackageManager();
                for (String pkg : packages) {
                    int uid = packageUid(pkg);
                    if (uid >= 0) {
                        uidMap.put(pkg, uid);
                    }
                }
            } catch (Throwable error) {
                packageManager = null;
                System.err.println("[前台助手] 生成 package_uid.map 失败: " + errorText(error));
                return;
            }
        }

        if (!packages.isEmpty() && uidMap.isEmpty()) {
            System.err.println("[前台助手] package_uid.map 未更新: 未解析到任何已安装应用 UID");
            return;
        }

        if (!writeUidMap(uidMap)) {
            return;
        }

        lastUidMapConfigMtime = mtime;
        lastUidMapConfigLength = length;
        lastUidMapSyncElapsed = nowElapsed;
        lastUidMapCount = uidMap.size();
        System.out.println("[前台助手] package_uid.map 已更新: rules="
            + packages.size() + " uid=" + uidMap.size() + " reason=" + reason);
    }

    private static Set<String> readRulePackages(File file) throws IOException {
        String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Set<String> out = new LinkedHashSet<>();
        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String base = extractBasePackage(line.substring(0, eq).trim());
            if (base.length() > 0) {
                out.add(base);
            }
        }
        return out;
    }

    private static String extractBasePackage(String left) {
        int brace = left.indexOf('{');
        if (brace >= 0) {
            left = left.substring(0, brace).trim();
        }
        int colon = left.indexOf(':');
        if (colon >= 0) {
            left = left.substring(0, colon).trim();
        }
        if (left.indexOf('.') < 0) {
            return "";
        }
        for (int i = 0; i < left.length(); i++) {
            char ch = left.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '.';
            if (!ok) {
                return "";
            }
        }
        return left;
    }

    private static void ensurePackageManager() throws Exception {
        if (packageManager != null) {
            return;
        }
        Class<?> appGlobals = Class.forName("android.app.AppGlobals");
        Object service = appGlobals.getMethod("getPackageManager").invoke(null);
        if (service == null) {
            throw new IllegalStateException("package manager service is null");
        }
        packageManager = service;
    }

    private static int packageUid(String packageName) throws Exception {
        PackageInfo info = getPackageInfo(packageName);
        if (info == null) {
            return -1;
        }
        ApplicationInfo appInfo = info.applicationInfo;
        return appInfo == null ? -1 : appInfo.uid;
    }

    private static PackageInfo getPackageInfo(String packageName) throws Exception {
        ensurePackageManager();
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
            Object result = invoke(method, packageManager, packageName, flags, 0);
            return result instanceof PackageInfo ? (PackageInfo) result : null;
        }
        throw new NoSuchMethodException("IPackageManager.getPackageInfo");
    }

    private static boolean writeUidMap(TreeMap<String, Integer> uidMap) {
        File parent = uidMapFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            System.err.println("[前台助手] 无法创建 UID 映射目录: " + parent);
            return false;
        }
        String content = uidMapContent(uidMap);
        try {
            if (uidMapFile.isFile()) {
                String old = new String(java.nio.file.Files.readAllBytes(uidMapFile.toPath()), StandardCharsets.UTF_8);
                if (old.equals(content)) {
                    uidMapFile.setReadable(true, false);
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        File temp = new File(parent, uidMapFile.getName() + ".tmp." + Process.myPid());
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
            new FileOutputStream(temp, false), StandardCharsets.UTF_8))) {
            out.print(content);
            out.flush();
            if (out.checkError()) {
                throw new IllegalStateException("uid map write failed");
            }
        } catch (Throwable error) {
            System.err.println("[前台助手] 写入 package_uid.map 失败: " + errorText(error));
            temp.delete();
            return false;
        }

        if (!temp.renameTo(uidMapFile)) {
            uidMapFile.delete();
            if (!temp.renameTo(uidMapFile)) {
                System.err.println("[前台助手] 原子替换 package_uid.map 失败: " + uidMapFile);
                temp.delete();
                return false;
            }
        }
        uidMapFile.setReadable(true, false);
        return true;
    }

    private static String uidMapContent(TreeMap<String, Integer> uidMap) {
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> entry : uidMap.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return out.toString();
    }

    private static void unregisterListener() {
        if (!listenerRegistered || unregisterMethod == null || activityTaskManager == null || listener == null) {
            return;
        }
        try {
            invoke(unregisterMethod, activityTaskManager, listener);
        } catch (Throwable ignored) {
        } finally {
            listenerRegistered = false;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw error;
        }
    }

    private static boolean readBooleanField(Object target, String name, boolean fallback) {
        try {
            Field field = target.getClass().getField(name);
            return field.getBoolean(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int readIntField(Object target, String name, int fallback) {
        try {
            Field field = target.getClass().getField(name);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String errorText(Throwable error) {
        Throwable value = error;
        while (value instanceof InvocationTargetException && value.getCause() != null) {
            value = value.getCause();
        }
        String message = value.getMessage();
        return clean(value.getClass().getName() + (message == null ? "" : ": " + message));
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    private static String readBootId() {
        try {
            String value = new String(
                java.nio.file.Files.readAllBytes(BOOT_ID_FILE.toPath()),
                StandardCharsets.UTF_8
            ).trim();
            if (!value.isEmpty() && value.length() <= 128
                    && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
                return value;
            }
        } catch (Throwable error) {
            System.err.println("[前台助手] 读取 boot_id 失败: " + errorText(error));
        }
        return "";
    }

    private static void refreshBootIdIfNeeded() {
        if (!bootId.isEmpty()) {
            return;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        if (lastBootIdReadAttemptElapsed > 0L
                && nowElapsed - lastBootIdReadAttemptElapsed < BOOT_ID_READ_RETRY_MS) {
            return;
        }
        lastBootIdReadAttemptElapsed = nowElapsed;
        bootId = readBootId();
    }

    private static void restorePreviousState() {
        if (bootId.isEmpty() || stateFile == null || !stateFile.isFile()) {
            return;
        }

        try {
            String text = new String(
                java.nio.file.Files.readAllBytes(stateFile.toPath()),
                StandardCharsets.UTF_8
            );
            TreeMap<String, String> values = new TreeMap<>();
            for (String raw : text.split("\\r?\\n")) {
                int eq = raw.indexOf('=');
                if (eq > 0) {
                    values.put(raw.substring(0, eq).trim(), raw.substring(eq + 1).trim());
                }
            }
            if (!bootId.equals(values.get("boot_id"))) {
                return;
            }

            parseExitRecords(values.get("exited_packages"));
            parseLifecycleRecords(values.get("lifecycle_packages"));
            lastInScopePackages = new LinkedHashSet<>(lifecyclePackages.keySet());
            String restoredSelection = values.get("selection");
            String restoredFrontPackage = values.get("focused_package");
            if (isHealthSelection(restoredSelection)
                    && isSafePackageName(restoredFrontPackage)
                    && lifecyclePackages.containsKey(restoredFrontPackage)) {
                lastReliableFrontPackage = restoredFrontPackage;
            }
            pruneExitRecords();
        } catch (Throwable error) {
            lastExitElapsed.clear();
            lifecyclePackages.clear();
            lastInScopePackages.clear();
            lastReliableFrontPackage = null;
            System.err.println("[前台助手] 恢复前台状态失败: " + errorText(error));
        }
    }

    private static void parseExitRecords(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String item : encoded.split(",")) {
            int at = item.lastIndexOf('@');
            if (at <= 0 || at == item.length() - 1) {
                continue;
            }
            String pkg = item.substring(0, at);
            long elapsed = parsePositiveLong(item.substring(at + 1));
            if (isSafePackageName(pkg) && elapsed > 0L
                    && elapsed <= SystemClock.elapsedRealtime()) {
                recordExit(pkg, elapsed);
            }
        }
    }

    private static void parseLifecycleRecords(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String item : encoded.split(",")) {
            if (lifecyclePackages.size() >= MAX_RESTORED_LIFECYCLE_RECORDS) {
                return;
            }
            int secondAt = item.lastIndexOf('@');
            int firstAt = secondAt <= 0 ? -1 : item.lastIndexOf('@', secondAt - 1);
            if (firstAt <= 0 || secondAt == item.length() - 1) {
                continue;
            }
            String pkg = item.substring(0, firstAt);
            long enteredElapsed = parsePositiveLong(item.substring(firstAt + 1, secondAt));
            long enteredWall = parsePositiveLong(item.substring(secondAt + 1));
            if (isSafePackageName(pkg) && enteredElapsed > 0L && enteredWall > 0L
                    && enteredElapsed <= SystemClock.elapsedRealtime()) {
                lifecyclePackages.put(pkg, new LifecycleRecord(enteredElapsed, enteredWall));
            }
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static boolean isSafePackageName(String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || value.indexOf('.') < 0) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_' || ch == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHealthSelection(String selection) {
        return "focused".equals(selection)
            || "default-visible".equals(selection)
            || "visible".equals(selection);
    }

    private static void recordExit(String pkg, long elapsed) {
        Long previous = lastExitElapsed.get(pkg);
        if (previous == null || elapsed > previous) {
            lastExitElapsed.put(pkg, elapsed);
        }
    }

    private static void pruneExitRecords() {
        while (lastExitElapsed.size() > MAX_EXIT_RECORDS) {
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, Long> entry : lastExitElapsed.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldestKey = entry.getKey();
                    oldestTime = entry.getValue();
                }
            }
            if (oldestKey == null) {
                return;
            }
            lastExitElapsed.remove(oldestKey);
        }
    }

    private static String joinExitRecords() {
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, Long> entry : lastExitElapsed.entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(entry.getKey()).append('@').append(entry.getValue());
        }
        return out.toString();
    }

    private static String joinLifecycleRecords() {
        StringBuilder out = new StringBuilder();
        for (java.util.Map.Entry<String, LifecycleRecord> entry : lifecyclePackages.entrySet()) {
            if (out.length() > 0) {
                out.append(',');
            }
            LifecycleRecord lifecycle = entry.getValue();
            out.append(entry.getKey())
                .append('@').append(lifecycle.enteredElapsed)
                .append('@').append(lifecycle.enteredWall);
        }
        return out.toString();
    }

    private static void line(PrintWriter out, String key, String value) {
        out.println(key + "=" + clean(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private interface StateWriter {
        void write(PrintWriter out);
    }

    private static final class Listener extends TaskStackListener {
        @Override
        public void onTaskStackChanged() throws RemoteException {
            requestRefresh("task_stack_changed", EVENT_DEBOUNCE_MS);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo)
                throws RemoteException {
            long eventElapsed = SystemClock.elapsedRealtime();
            String movedPackage = taskInfo == null || taskInfo.topActivity == null
                ? null : taskInfo.topActivity.getPackageName();
            requestMovedToFrontRefresh(movedPackage, eventElapsed, EVENT_DEBOUNCE_MS);
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) throws RemoteException {
            requestRefresh("task_focus_changed", EVENT_DEBOUNCE_MS);
        }
    }

    private static final class FrontMoveEvent {
        final String packageName;
        final long elapsed;

        FrontMoveEvent(String packageName, long elapsed) {
            this.packageName = packageName;
            this.elapsed = elapsed;
        }
    }

    private static final class TaskRecord {
        final ComponentName component;
        final int taskId;
        final int displayId;
        final boolean focused;
        final boolean visible;

        TaskRecord(ComponentName component, int taskId, int displayId, boolean focused,
                boolean visible) {
            this.component = component;
            this.taskId = taskId;
            this.displayId = displayId;
            this.focused = focused;
            this.visible = visible;
        }

        static TaskRecord from(ActivityManager.RunningTaskInfo task) {
            boolean visible;
            try {
                visible = task.isVisible();
            } catch (Throwable ignored) {
                visible = readBooleanField(task, "isVisible", false);
            }
            return new TaskRecord(
                task.topActivity,
                task.taskId,
                readIntField(task, "displayId", 0),
                readBooleanField(task, "isFocused", false),
                visible
            );
        }
    }

    private static final class LifecycleRecord {
        final long enteredElapsed;
        final long enteredWall;

        LifecycleRecord(long enteredElapsed, long enteredWall) {
            this.enteredElapsed = enteredElapsed;
            this.enteredWall = enteredWall;
        }
    }
}
