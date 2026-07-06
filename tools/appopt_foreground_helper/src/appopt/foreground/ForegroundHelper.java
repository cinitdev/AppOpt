package appopt.foreground;

import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 常驻 root app_process 前台任务监听器。
 *
 * ActivityTaskManager 和 TaskStackListener 都由设备 framework 提供。本 helper 只依赖
 * Android 12-17 一直存在的 getTasks(int) 门面和三个稳定回调，避免绑定会变化的 Binder
 * 事务编号及底层 getTasks 参数。
 */
public final class ForegroundHelper {
    private static final long LISTENER_RECONCILE_MS = 5000L;
    private static final long POLL_RETRY_MS = 1200L;
    private static final long EVENT_DEBOUNCE_MS = 80L;
    private static final int MAX_TASKS = 24;

    private static final Object SCHEDULE_LOCK = new Object();
    private static Handler handler;
    private static File stateFile;
    private static Object activityTaskManager;
    private static Method getTasksMethod;
    private static Method registerMethod;
    private static Method unregisterMethod;
    private static Listener listener;
    private static boolean listenerRegistered;
    private static boolean refreshScheduled;
    private static long generation;
    private static String startupError = "";

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

        Looper.prepare();
        handler = new Handler(Looper.myLooper());
        connectActivityTaskManager();
        Runtime.getRuntime().addShutdownHook(new Thread(
            ForegroundHelper::unregisterListener,
            "AppOptForegroundShutdown"
        ));
        requestRefresh("start", 0L);
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

            try {
                invoke(registerMethod, activityTaskManager, listener);
                listenerRegistered = true;
                startupError = "";
                System.out.println("[前台助手] TaskStackListener 已注册, SDK=" + Build.VERSION.SDK_INT);
            } catch (Throwable listenerError) {
                listenerRegistered = false;
                startupError = "listener=" + errorText(listenerError);
                System.err.println("[前台助手] TaskStackListener 注册失败, 降级轮询: " + startupError);
            }
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
        synchronized (SCHEDULE_LOCK) {
            refreshScheduled = false;
        }
        refreshState(listenerRegistered ? "reconcile" : "poll");
        handler.postDelayed(ForegroundHelper::reconcile, listenerRegistered
            ? LISTENER_RECONCILE_MS : POLL_RETRY_MS);
    }

    private static void requestRefresh(String reason, long delayMs) {
        synchronized (SCHEDULE_LOCK) {
            if (refreshScheduled) {
                return;
            }
            refreshScheduled = true;
        }
        handler.postDelayed(() -> {
            synchronized (SCHEDULE_LOCK) {
                refreshScheduled = false;
            }
            refreshState(reason);
        }, delayMs);
    }

    @SuppressWarnings("unchecked")
    private static void refreshState(String reason) {
        if (activityTaskManager == null || getTasksMethod == null) {
            connectActivityTaskManager();
            if (activityTaskManager == null || getTasksMethod == null) {
                return;
            }
        }

        try {
            Object value = invoke(getTasksMethod, activityTaskManager, MAX_TASKS);
            List<ActivityManager.RunningTaskInfo> tasks = value instanceof List
                ? (List<ActivityManager.RunningTaskInfo>) value : null;
            writeTaskState(tasks, reason);
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

    private static void writeTaskState(List<ActivityManager.RunningTaskInfo> tasks, String reason) {
        TaskRecord focused = null;
        TaskRecord defaultVisible = null;
        TaskRecord firstVisible = null;
        TaskRecord first = null;
        Set<String> visiblePackages = new LinkedHashSet<>();
        int taskCount = tasks == null ? 0 : tasks.size();

        if (tasks != null) {
            for (ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null || task.topActivity == null) {
                    continue;
                }
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

        TaskRecord selected = focused != null ? focused
            : defaultVisible != null ? defaultVisible
            : firstVisible != null ? firstVisible
            : first;
        final String selection = focused != null ? "focused"
            : defaultVisible != null ? "default-visible"
            : firstVisible != null ? "visible"
            : first != null ? "first" : "none";
        final TaskRecord result = selected;
        final long currentGeneration = ++generation;
        writeState(out -> {
            line(out, "version", "1");
            line(out, "status", result == null ? "empty" : "ok");
            line(out, "mode", listenerRegistered ? "listener" : "poll");
            line(out, "sdk", String.valueOf(Build.VERSION.SDK_INT));
            line(out, "pid", String.valueOf(Process.myPid()));
            line(out, "generation", String.valueOf(currentGeneration));
            line(out, "updated_elapsed_ms", String.valueOf(SystemClock.elapsedRealtime()));
            line(out, "updated_wall_ms", String.valueOf(System.currentTimeMillis()));
            line(out, "reason", reason);
            line(out, "selection", selection);
            line(out, "focused_package", result == null ? "" : result.component.getPackageName());
            line(out, "focused_activity", result == null ? "" : result.component.flattenToShortString());
            line(out, "focused_task_id", result == null ? "0" : String.valueOf(result.taskId));
            line(out, "focused_display_id", result == null ? "-1" : String.valueOf(result.displayId));
            line(out, "focused_flag", result != null && result.focused ? "1" : "0");
            line(out, "focused_visible", result != null && result.visible ? "1" : "0");
            line(out, "visible_packages", join(visiblePackages));
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
            line(out, "version", "1");
            line(out, "status", "error");
            line(out, "mode", listenerRegistered ? "listener" : "poll");
            line(out, "sdk", String.valueOf(Build.VERSION.SDK_INT));
            line(out, "pid", String.valueOf(Process.myPid()));
            line(out, "generation", String.valueOf(currentGeneration));
            line(out, "updated_elapsed_ms", String.valueOf(SystemClock.elapsedRealtime()));
            line(out, "updated_wall_ms", String.valueOf(System.currentTimeMillis()));
            line(out, "reason", reason);
            line(out, "focused_package", "");
            line(out, "visible_packages", "");
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

    private static void unregisterListener() {
        if (!listenerRegistered || unregisterMethod == null || activityTaskManager == null || listener == null) {
            return;
        }
        try {
            invoke(unregisterMethod, activityTaskManager, listener);
        } catch (Throwable ignored) {
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
            requestRefresh("task_moved_to_front", EVENT_DEBOUNCE_MS);
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) throws RemoteException {
            requestRefresh("task_focus_changed", EVENT_DEBOUNCE_MS);
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
                visible = readBooleanField(task, "isVisible", true);
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
}
