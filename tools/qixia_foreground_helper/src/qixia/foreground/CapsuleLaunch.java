package qixia.foreground;

import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 复用 Binder 回执，不新增常驻进程或轮询脚本。 */
final class CapsuleLaunch {
    private static File stateFile;
    private static String bootId = "";
    private static CapsuleLaunch active;
    final String request = UUID.randomUUID().toString();
    final String pkg;
    final long revision;
    final long apkUpdated;
    private final boolean leaving;
    private final CountDownLatch reply = new CountDownLatch(1);
    private volatile String result = "starting";
    private volatile long lastReply;
    private boolean failureLogged;
    private String route = "direct";
    private final ResultReceiver receiver = new ResultReceiver(null) {
        @Override protected void onReceiveResult(int code, Bundle data) {
            if (data == null || !request.equals(data.getString("request_id")) || !pkg.equals(data.getString("target_pkg"))) return;
            synchronized (CapsuleLaunch.class) {
                if (active != CapsuleLaunch.this || !PerformanceOverlayController.isCurrent(revision)) return;
                String next = data.getString("state", "");
                if (!("running".equals(next) || "failed".equals(next) || "idle".equals(next))) return;
                if ("failed".equals(result) || (leaving && "running".equals(next))) return;
                result = next;
                lastReply = SystemClock.elapsedRealtime();
                write(next, data.getString("reason", ""));
                reply.countDown();
            }
        }
    };

    private CapsuleLaunch(String target, long generation, long updated, boolean leave) {
        pkg = target; revision = generation; apkUpdated = updated; leaving = leave;
    }

    static synchronized void initialize(File file) {
        stateFile = file;
        try { bootId = new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.UTF_8).trim(); }
        catch (Exception ignored) { bootId = ""; }
    }

    static synchronized CapsuleLaunch begin(String pkg, long revision, long apkUpdated, boolean leaving) {
        active = new CapsuleLaunch(pkg, revision, apkUpdated, leaving);
        active.write(leaving ? "stopping" : "starting", "");
        return active;
    }

    static synchronized boolean healthy(String pkg) {
        return active != null && active.pkg.equals(pkg) && "running".equals(active.result) &&
            SystemClock.elapsedRealtime() - active.lastReply < 15000;
    }

    Intent decorate(Intent intent, String method) {
        route = method;
        // APK 不加载助手的匿名子类，只传系统 ResultReceiver 和其中的 Binder。
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            ResultReceiver wireReceiver = ResultReceiver.CREATOR.createFromParcel(parcel);
            return intent.putExtra("request_id", request).putExtra("result_receiver", wireReceiver);
        } finally { parcel.recycle(); }
    }

    boolean await(long timeout) throws InterruptedException {
        long until = SystemClock.elapsedRealtime() + timeout;
        while (PerformanceOverlayController.isCurrent(revision)) {
            long remaining = until - SystemClock.elapsedRealtime();
            if (remaining <= 0) break;
            if (reply.await(Math.min(remaining, 100), TimeUnit.MILLISECONDS))
                return leaving ? "idle".equals(result) : "running".equals(result);
        }
        return false;
    }

    boolean rejected() { return "failed".equals(result) || "idle".equals(result); }

    boolean confirmed() {
        return PerformanceOverlayController.isCurrent(revision) &&
            (leaving ? "idle".equals(result) : "running".equals(result));
    }

    void fail(String reason) {
        synchronized (CapsuleLaunch.class) {
            if (active != this || !PerformanceOverlayController.isCurrent(revision)) return;
            if ("running".equals(result) && SystemClock.elapsedRealtime() - lastReply < 15000) return;
            result = leaving ? "idle" : "failed";
            write(result, leaving ? "" : reason);
            reply.countDown();
        }
    }

    private void write(String state, String reason) {
        String clean = reason.replace('\n', ' ').replace('\r', ' ');
        if (clean.length() > 240) clean = clean.substring(0, 240);
        // 失败原因保留在限量事件日志中，首页不再为展示状态单独读取文件。
        if ("failed".equals(state) && !failureLogged) {
            failureLogged = true;
            EventLog.warn("性能记录胶囊启动失败: pkg=" + pkg + " route=" + route + " 原因=" + clean);
        }
        if (stateFile == null) return;
        try {
            File parent = stateFile.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return;
            String text = "version=2\nresult=" + state + "\nrequest_id=" + request + "\ntarget_pkg=" + pkg +
                "\nroute=" + route + "\nboot_id=" + bootId + "\napk_updated_ms=" + apkUpdated +
                "\nupdated_elapsed_ms=" + SystemClock.elapsedRealtime() + "\nreason=" + clean + "\n";
            File temporary = new File(stateFile.getPath() + ".tmp");
            Files.write(temporary.toPath(), text.getBytes(StandardCharsets.UTF_8));
            Files.move(temporary.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) { /* 诊断状态写入失败不能干扰记录。 */ }
    }
}
