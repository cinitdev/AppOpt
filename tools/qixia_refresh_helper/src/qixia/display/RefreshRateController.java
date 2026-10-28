package qixia.display;

import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import qixia.refresh.RefreshPolicy;

/** 独立刷新率服务；只读取前台快照，不在前台助手进程内执行显示控制。 */
final class RefreshRateController {
    private final Handler handler;
    private final File config, stateFile;
    private final Properties state = new Properties();
    private final String boot;
    private Context context;
    private RefreshDisplay display;
    private RefreshPolicy policy = RefreshPolicy.parse("");
    private RefreshInput input;
    private FileObserver observer;
    private FileObserver moduleObserver;
    private String published = "";
    private String pkg = "", home = "", inputError = "", appliedKey = "";
    private Object windowManager;
    private java.lang.reflect.Method keyguardMethod;
    private int battery = -1;
    private long activeUntil, lastWrite, lastApply, configModified = -1, configLength = -1;
    private int applyAttempts;
    private int controlFailures;
    private final qixia.refresh.RefreshPlatform platform=RefreshDevice.profile();
    private boolean compatible() { return policy.on("compatible",platform.modernMtk); }
    private boolean strict() { return policy.on("strict",platform.modernMtk); }
    private boolean settingsBackend() { return compatible() || platform.flyme; }
    private final Runnable tick = this::evaluate;
    private final Runnable retry = this::reload;
    private boolean stopping;
    RefreshRateController(File dir, String boot) {
        this.boot = boot; config = new File(dir,"refresh_rate.conf"); stateFile = new File(dir,"state/refresh_rate.state");
        HandlerThread thread = new HandlerThread("QixiaRefreshRate", android.os.Process.THREAD_PRIORITY_BACKGROUND); thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::initialize);
    }
    private void initialize() {
        try {
            if (stateFile.isFile()) try (Reader reader = new InputStreamReader(new FileInputStream(stateFile),StandardCharsets.UTF_8)) { state.load(reader); }
            state.setProperty("pid", Integer.toString(android.os.Process.myPid()));
            String stat = new String(Files.readAllBytes(new File("/proc/self/stat").toPath()), StandardCharsets.UTF_8);
            state.setProperty("start_ticks", stat.substring(stat.lastIndexOf(')')+2).split("\\s+")[19]);
            state.setProperty("worker", "standalone");
            // 没开启功能时不初始化显示服务、输入监听或显示评估定时器。
            observer = new FileObserver(config.getParentFile(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO | FileObserver.DELETE) {
                @Override public void onEvent(int event, String path) {
                    if ("refresh_rate.conf".equals(path)) handler.post(RefreshRateController.this::reload);
                    else if ("foreground_task.state".equals(path)) handler.post(RefreshRateController.this::readForeground);
                }
            };
            observer.startWatching(); reload();
            File module = config.getParentFile().getParentFile();
            moduleObserver = new FileObserver(module,FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_TO | FileObserver.MOVED_FROM) {
                @Override public void onEvent(int event,String path) {
                    if ("disable".equals(path) || "remove".equals(path)) handler.post(RefreshRateController.this::reload);
                }
            };
            moduleObserver.startWatching();
            handler.postDelayed(this::reconcile, 10000);
        } catch (Exception e) { fail(e); }
    }
    private void connect() throws Exception {
        if (display != null) return;
        Class<?> cls = Class.forName("android.app.ActivityThread");
        Object activity = cls.getMethod("systemMain").invoke(null);
        context = (Context) cls.getDeclaredMethod("getSystemContext").invoke(activity);
        display = new RefreshDisplay(context);
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo resolved = context.getPackageManager().resolveActivity(intent,0);
        home = resolved == null || resolved.activityInfo == null ? "" : resolved.activityInfo.packageName;

    }
    private void readForeground() {
        if (!policy.on("enabled") || display == null || stopping) return;
        try {
            Properties snapshot = new Properties();
            try (Reader reader = new InputStreamReader(new FileInputStream(new File(config.getParentFile(), "foreground_task.state")), StandardCharsets.UTF_8)) { snapshot.load(reader); }
            String next = boot.equals(snapshot.getProperty("boot_id")) && "ok".equals(snapshot.getProperty("status"))
                && "0".equals(snapshot.getProperty("focused_display_id")) ? snapshot.getProperty("focused_package", "") : "";
            if (Objects.equals(pkg, next)) return;
            pkg = next; activeUntil = SystemClock.elapsedRealtime() + policy.integer("tap_ms");
            schedule(0);
        } catch (IOException ignored) { /* 快照原子替换期间保留上一前台，下一次事件或评估重读。 */ }
    }
    private void reconcile() {
        if (stopping) return;
        if (config.lastModified() != configModified || config.length() != configLength) reload();
        handler.postDelayed(this::reconcile, 10000);
    }
    void shutdown() {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        handler.post(() -> {
            stopping = true; handler.removeCallbacksAndMessages(null);
            if (observer != null) observer.stopWatching();
            if (moduleObserver != null) moduleObserver.stopWatching();
            if (input != null) input.close();
            try {
                if (state.containsKey("baseline.peak")) { connect(); display.restore(state, boot); }
                state.setProperty("status", "stopped"); persist();
            } catch (Exception e) { fail(e); }
            finally { done.countDown(); }
        });
        try { done.await(3, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void reload() {
        if (stopping) return;
        handler.removeCallbacks(retry);
        try {
            policy = RefreshPolicy.parse(config.isFile() ? new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8) : "");
            File module = config.getParentFile().getParentFile();
            if (new File(module,"disable").exists() || new File(module,"remove").exists()) policy = policy.with("enabled","0");
            configModified = config.lastModified(); configLength = config.length(); appliedKey = "";
            state.setProperty("version","1"); state.setProperty("boot_id",boot); state.setProperty("enabled",policy.on("enabled")?"1":"0");
            state.setProperty("platform",platform.name());
            state.setProperty("automatic_compatible",platform.modernMtk?"1":"0");
            state.setProperty("automatic_strict",platform.modernMtk?"1":"0");
            state.setProperty("effective_compatible",compatible()?"1":"0");
            state.setProperty("effective_strict",strict()?"1":"0");
            if (!policy.on("enabled")) {
                if (input != null) { input.close(); input = null; }
                handler.removeCallbacks(tick);
                if (state.containsKey("baseline.peak")) { connect(); display.restore(state,boot); }
                state.setProperty("status","disabled"); state.setProperty("error","");
                state.setProperty("reason","策略已停用");
                for (String key : new String[]{"target_hz","actual_hz","backend","input_error"}) state.remove(key);
                persist(); return;
            }
            connect(); readForeground();
            if (settingsBackend() ? state.containsKey("baseline.surface_touched") || state.containsKey("baseline.miui_mode")
                : state.containsKey("baseline.settings_touched")) display.restore(state,boot);
            display.capture(state,boot); persist(); // 必须先持久保存恢复基线，再修改系统。
            if (input == null) {
                inputError = "";
                input = new RefreshInput(new RefreshInput.Listener() {
                    private long last;
                    @Override public void activity(boolean scroll) {
                        long now = SystemClock.elapsedRealtime();
                        if (now-last < 50) return; last = now;
                        handler.post(() -> {
                            boolean wasIdle = now >= activeUntil;
                            activeUntil = Math.max(activeUntil,now+policy.integer(scroll?"scroll_ms":"tap_ms"));
                            // 连续触摸只延长截止时间，不逐输入事件查询显示服务。
                            if (wasIdle) schedule(0);
                        });
                    }
                    @Override public void error(String message) { handler.post(() -> { inputError=message; schedule(0); }); }
                });
            }
            activeUntil = SystemClock.elapsedRealtime()+policy.integer("tap_ms"); schedule(0);
        } catch (Exception e) { fail(e); handler.postDelayed(retry,5000); }
    }
    private void schedule(long delay) { handler.removeCallbacks(tick); if (policy.on("enabled")) handler.postDelayed(tick,delay); }
    private void evaluate() {
        if (!policy.on("enabled") || display == null) return;
        long now = SystemClock.elapsedRealtime();
        try {
            readForeground();
            if(display.user()!=RefreshSettings.currentUser()) {
                display.restore(state,boot);persist();display.capture(state,boot);persist();appliedKey="";
            }
            if (!interactive()) {
                state.setProperty("status","screen_off"); state.setProperty("reason","熄屏暂停"); publish(now); schedule(2000); return;
            }
            boolean locked = keyguardLocked();
            float brightness = display.brightness();
            battery = context.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (battery < 0 || battery > 100) battery = -1;
            float[] rates = display.rates();
            RefreshPolicy.Decision decision = policy.decide(pkg,pkg.equals(home) || pkg.equals("com.android.systemui"),locked,now<activeUntil || !inputError.isEmpty(),battery,brightness,rates);
            String mode = policy.displayMode(decision.hz,rates);
            android.view.Display.Mode currentMode = display.display().getMode();
            String key = pkg+":"+decision.hz+":"+settingsBackend()+":"+mode+":"+currentMode.getPhysicalWidth()+"x"+currentMode.getPhysicalHeight();
            float actual = display.actualRate();
            boolean newTarget = !key.equals(appliedKey);
            if (newTarget) applyAttempts=0;
            boolean mismatch = Float.isFinite(actual) && Math.abs(actual-decision.hz) > .6f;
            // 目标或前台变化立即下发；持续被系统拒绝时退避，避免长时间重复写入。
            long retryInterval=applyAttempts>=3?30000:2000;
            if (newTarget || now-lastApply >= retryInterval && (strict() || mismatch && applyAttempts < 3)) {
                if (display.prepareApply(state,settingsBackend())) persist();
                if (!settingsBackend() && state.containsKey("baseline.miui_mode")) {
                    state.setProperty("backend",display.applyPhysical(decision.hz,mode));
                } else {
                    Exception rejected=null;
                    try { state.setProperty("backend",display.apply(decision.hz,settingsBackend(),mode,state)); }
                    catch(Exception e) { rejected=e; }
                    if(!settingsBackend()) {
                        boolean matches=false;try { matches=display.requestMatches(decision.hz,mode); }catch(Exception ignored) { }
                        if((rejected!=null || !matches || mismatch && applyAttempts>0) && display.preparePhysicalFallback(state)) {
                            persist(); // 物理入口也先保存基线，才能切换。
                            state.setProperty("backend",display.applyPhysical(decision.hz,mode));rejected=null;
                        }
                    }
                    if(rejected!=null)throw rejected;
                }
                appliedKey=key; lastApply=now;
                applyAttempts++;
            }
            actual=display.actualRate();
            boolean known=Float.isFinite(actual);
            mismatch=known && Math.abs(actual-decision.hz) > .6f;
            if (known && !mismatch) applyAttempts=0;
            boolean failed=mismatch && now-lastApply >= 1500;
            state.setProperty("status",!known ? "unverified" : failed ? "error" : mismatch ? "switching" : "active");
            state.setProperty("error",failed ? "刷新率未生效：目标 "+RefreshPolicy.rateLabel(decision.hz)
                +" Hz，屏幕实际 "+RefreshPolicy.rateLabel(actual)+" Hz；系统可能拒绝或覆盖了请求" : "");
            state.setProperty("package",pkg); state.setProperty("reason",decision.reason);
            state.setProperty("target_hz",Float.toString(decision.hz));
            if(known)state.setProperty("actual_hz",Float.toString(actual));else state.remove("actual_hz");
            state.setProperty("observation",display.observation);
            state.setProperty("verification",known?"":"已提交刷新率请求；系统未提供可验证的物理模式读数");
            state.setProperty("input_error",inputError); state.setProperty("modes",display.modes());
            state.setProperty("rates",java.util.Arrays.toString(rates));
            state.setProperty("resolution",currentMode.getPhysicalWidth()+"x"+currentMode.getPhysicalHeight());
            publish(now);
            controlFailures=0;
            long interval = mismatch && now-lastApply < 1500 ? 500 : 2000;
            schedule(now<activeUntil ? Math.min(interval,Math.max(50,activeUntil-now+10)) : interval);
        } catch (Exception e) { fail(e); schedule(Math.min(30000,5000L*++controlFailures)); }
    }
    private void publish(long now) throws IOException {
        String current = state.getProperty("status")+state.getProperty("package")+state.getProperty("reason")
            +state.getProperty("target_hz")+state.getProperty("actual_hz")+state.getProperty("input_error")
            +state.getProperty("error")+state.getProperty("backend");
        if (now-lastWrite >= 1000 && (!current.equals(published) || now-lastWrite >= 30000)) {
            persist(); lastWrite=now; published=current;
        }
    }
    private boolean keyguardLocked() throws Exception {
        if (windowManager == null) {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
            windowManager = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            keyguardMethod = Class.forName("android.view.IWindowManager").getMethod("isKeyguardLocked");
        }
        try { return Boolean.TRUE.equals(keyguardMethod.invoke(windowManager)); }
        catch (Exception e) { windowManager=null;throw e; }
    }
    private Object powerManager;
    private java.lang.reflect.Method interactiveMethod;
    private boolean interactive() throws Exception {
        if (powerManager == null) {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"power");
            powerManager = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            interactiveMethod = Class.forName("android.os.IPowerManager").getMethod("isInteractive");
        }
        try { return Boolean.TRUE.equals(interactiveMethod.invoke(powerManager)); }
        catch (Exception e) { powerManager=null;throw e; }
    }
    private void fail(Exception e) {
        if (!RefreshDisplay.message(e).equals(state.getProperty("error"))) e.printStackTrace(System.err);
        state.setProperty("status","error"); state.setProperty("error",RefreshDisplay.message(e));
        try { persist(); } catch (Exception ignored) { }
    }
    private void persist() throws IOException {
        state.setProperty("updated_elapsed_ms",Long.toString(SystemClock.elapsedRealtime()));
        File dir = stateFile.getParentFile(); if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建刷新率状态目录");
        File temp = new File(dir,stateFile.getName()+".tmp");
        try (FileOutputStream stream = new FileOutputStream(temp); Writer w = new OutputStreamWriter(stream,StandardCharsets.UTF_8)) {
            state.store(w,"柒夏线程刷新率状态与恢复基线"); w.flush(); stream.getFD().sync();
        }
        if (!temp.renameTo(stateFile)) throw new IOException("无法保存刷新率状态");
    }
}
