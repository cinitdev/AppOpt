package qixia.display;

import android.content.Context;
import android.os.IBinder;
import android.view.Display;
import java.lang.reflect.*;
import java.util.*;
import qixia.refresh.*;

/** 控制与观测独立降级；先保存恢复基线，再下发设备实际支持的模式。 */
final class RefreshDisplay {
    final Context context;
    private final Class<?> surface;
    private static Class<?> serverControl;
    private Object token;
    private long physicalId;
    private boolean singleDisplay;
    private String identity = "";
    private RefreshSettings settings = new RefreshSettings();
    final RefreshPlatform platform = RefreshDevice.profile();
    private final RefreshDevice device = new RefreshDevice();
    private List<RefreshModes.Mode> cachedModes=Collections.emptyList();
    private long modesAt=-30000, observedAt=-30000;
    private float observed=Float.NaN;
    String observation="未知";
    RefreshDisplay(Context context) throws Exception {
        this.context = context;
        surface = Class.forName("android.view.SurfaceControl");
        bind();
    }
    private static synchronized Class<?> serverDisplayControl() throws Exception {
        if (serverControl != null) return serverControl;
        // Android 新版把物理屏接口移至 services.jar。类与原生库必须共用同一个
        // 加载器；重复创建加载器会因库已被占用而导致后续重新绑定丢失接口。
        ClassLoader loader = new dalvik.system.PathClassLoader("/system/framework/services.jar",ClassLoader.getSystemClassLoader());
        Class<?> control = Class.forName("com.android.server.display.DisplayControl",false,loader);
        String library = android.os.Process.is64Bit() ? "/system/lib64/libandroid_servers.so" : "/system/lib/libandroid_servers.so";
        Method load; String error;
        try {
            load=Runtime.class.getDeclaredMethod("nativeLoad",String.class,ClassLoader.class,Class.class);
            load.setAccessible(true); error=(String)load.invoke(null,library,loader,control);
        } catch(NoSuchMethodException e) {
            load=Runtime.class.getDeclaredMethod("nativeLoad",String.class,ClassLoader.class);
            load.setAccessible(true); error=(String)load.invoke(null,library,loader);
        }
        if (error != null) throw new IllegalStateException(error);
        serverControl = control;
        return control;
    }
    private void bind() {
        Object foundToken = null; long foundId = -1; boolean single = false;
        for (String name : new String[]{"android.view.SurfaceControl", "com.android.server.display.DisplayControl"}) {
            try {
                Class<?> c = name.startsWith("com.android.server") ? serverDisplayControl() : surface;
                long[] ids = (long[]) c.getDeclaredMethod("getPhysicalDisplayIds").invoke(null);
                if (ids != null && ids.length > 0) {
                    long candidateId = defaultPhysicalId(ids);
                    Object candidateToken = c.getDeclaredMethod("getPhysicalDisplayToken", long.class).invoke(null, candidateId);
                    if (candidateToken == null) continue;
                    single = ids.length == 1;
                    foundId = candidateId;
                    foundToken = candidateToken;
                    break;
                }
            } catch (Exception | LinkageError ignored) { }
        }
        if(foundId<0)try {
            String dump=RefreshSurface.dump("SurfaceFlinger","--displays");
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?m)^Display (\\d+)\\s*$").matcher(dump);
            List<Long> ids=new ArrayList<>();while(m.find())ids.add(Long.parseLong(m.group(1)));
            if(!ids.isEmpty()) { single=ids.size()==1;foundId=defaultPhysicalId(ids.stream().mapToLong(Long::longValue).toArray()); }
        }catch(Exception ignored) { }
        token = foundToken; physicalId = foundId; singleDisplay = single;
        modesAt=-30000; observedAt=-30000;
    }
    Display display() { return context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY); }
    void refreshIdentity() {
        Display d=display();
        String next=d==null?"":d.toString();
        // Display.toString 包含刷新率，不能作为缓存键；只关注默认屏地址和分辨率。
        if(d!=null) {
            String address="";try { address=String.valueOf(Display.class.getMethod("getAddress").invoke(d)); }catch(Exception ignored) { }
            next=address+":"+d.getMode().getPhysicalWidth()+"x"+d.getMode().getPhysicalHeight();
        }
        if(!next.equals(identity)) { identity=next;bind(); }
    }
    float brightness() { return device.brightness(display()); }
    int user() { return settings.user; }
    private long defaultPhysicalId(long[] ids) throws Exception {
        try {
            Object address = Display.class.getMethod("getAddress").invoke(display());
            long id = (Long)address.getClass().getMethod("getPhysicalDisplayId").invoke(address);
            for (long candidate : ids) if (candidate == id) return id;
        } catch (ReflectiveOperationException | NullPointerException ignored) { }
        if (ids.length == 1) return ids[0];
        throw new IllegalStateException("无法确认主屏物理 ID，保持系统控制");
    }
    float[] rates() {
        refreshIdentity();
        Display d = display(); if (d == null) return new float[0];
        Display.Mode current = d.getMode();
        TreeSet<Float> rates = new TreeSet<>();
        for (Display.Mode m : d.getSupportedModes())
            if (m.getPhysicalWidth() == current.getPhysicalWidth() && m.getPhysicalHeight() == current.getPhysicalHeight()) rates.add(m.getRefreshRate());
        for(RefreshModes.Mode m:modeList()) if(m.width==current.getPhysicalWidth() && m.height==current.getPhysicalHeight()
                && rates.stream().noneMatch(r -> Math.abs(r-m.hz)<.1f)) rates.add(m.hz);
        float[] result = new float[rates.size()]; int i = 0; for (float r : rates) result[i++] = r;
        return qixia.refresh.RefreshPolicy.displayRates(result);
    }
    Object specs() throws Exception {
        if (token == null) bind();
        if (token == null) throw new IllegalStateException("暂时无法连接主屏显示接口，请重试或使用兼容模式");
        return surface.getDeclaredMethod("getDesiredDisplayModeSpecs", IBinder.class).invoke(null, token);
    }
    String modes() {
        try {
            StringJoiner out = new StringJoiner(";");
            for (RefreshModes.Mode m : modeList()) if(m.physical) out.add(m.id+","+m.hz+","+m.width+","+m.height);
            return out.toString();
        } catch (Exception e) { return ""; }
    }
    private Object dynamicInfo() throws Exception {
        if(physicalId<0 || token==null)throw new IllegalStateException("系统未提供主屏物理显示接口");
        try { return surface.getDeclaredMethod("getDynamicDisplayInfo", long.class).invoke(null, physicalId); }
        catch (NoSuchMethodException e) { return surface.getDeclaredMethod("getDynamicDisplayInfo", IBinder.class).invoke(null, token); }
    }
    private Object[] physicalModes() throws Exception {
        Object info = dynamicInfo();
        return (Object[]) field(info,"supportedDisplayModes").get(info);
    }
    private List<RefreshModes.Mode> modeList() {
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-modesAt<30000)return cachedModes;
        modesAt=now;
        List<RefreshModes.Mode> modes=new ArrayList<>();
        if(platform.oplus && android.os.Build.VERSION.SDK_INT>34) modes.addAll(surfaceSnapshot().modes);
        if(modes.isEmpty()) try {
            for(Object m:physicalModes()) modes.add(new RefreshModes.Mode(modeId(m),field(m,"width").getInt(m),field(m,"height").getInt(m),modeRate(m),true));
        }catch(Exception ignored) { }
        if(modes.isEmpty())modes.addAll(surfaceSnapshot().modes);
        if(modes.isEmpty())try { modes.addAll(RefreshModes.display(RefreshSurface.dump("display"),singleDisplay)); }catch(Exception ignored) { }
        return cachedModes=modes;
    }
    private RefreshModes.Snapshot surfaceSnapshot() {
        try { return RefreshModes.surface(RefreshSurface.dump("SurfaceFlinger","--displays"),physicalId,singleDisplay); }
        catch(Exception e) { return RefreshModes.surface("",physicalId,singleDisplay); }
    }
    /** 读取合成器当前物理模式，避免把应用逻辑刷新率当成屏幕实际刷新率。 */
    float actualRate() {
      try {
        Object info = dynamicInfo();
        int active = field(info,"activeDisplayModeId").getInt(info);
        for (Object mode : (Object[]) field(info,"supportedDisplayModes").get(info))
            if (modeId(mode) == active) { observation="物理模式";return modeRate(mode); }
      } catch(Exception ignored) { }
      long now=android.os.SystemClock.elapsedRealtime();
      if(now-observedAt<1500)return observed;
      observedAt=now;observed=surfaceSnapshot().active;observation="物理模式";
      if(!Float.isFinite(observed) && singleDisplay) try {
          String line=RefreshSurface.dump("SurfaceFlinger","--latency","null").split("\\n",2)[0].trim();
          long ns=Long.parseLong(line);
          if(ns>0) {
              float hz=1_000_000_000f/ns;
              // 只接受设备已有的档位，不能把错误输出当成超高刷新率。
              for(RefreshModes.Mode m:modeList())if(Math.abs(m.hz-hz)<.6f) { observed=hz;observation="合成器 VSYNC";break; }
          }
      }catch(Exception ignored) { }
      if(!Float.isFinite(observed))observation="未知";
      return observed;
    }
    private static int modeId(Object m) throws Exception { return field(m,"id").getInt(m); }
    private static float modeRate(Object m) throws Exception {
        try { return field(m,"refreshRate").getFloat(m); }
        catch (NoSuchFieldException e) { return field(m,"vsyncRate").getFloat(m); }
    }
    private int selectMode(float hz, String custom) throws Exception {
        Display.Mode current = display().getMode();
        int bestId = RefreshModes.select(modeList(),current.getPhysicalWidth(),current.getPhysicalHeight(),hz,custom);
        if (bestId >= 0) return bestId;
        throw new IllegalStateException("当前分辨率没有对应的物理模式，请重新选择档位或检查 Mode ID");
    }
    void capture(Properties state, String boot) throws Exception {
        if (!state.containsKey("baseline.peak")) {
            state.setProperty("baseline.peak", setting("peak_refresh_rate"));
            state.setProperty("baseline.min", setting("min_refresh_rate"));
            state.setProperty("baseline.user", Integer.toString(settings.user));
        }
        if (!boot.equals(state.getProperty("baseline.boot"))) {
            state.keySet().removeIf(k -> k.toString().startsWith("baseline.sf.") || k.toString().startsWith("baseline.miui_"));
            state.setProperty("baseline.boot", boot);
        }
        if (!hasSurfaceBaseline(state)) {
            try {
                flatten(specs(), "", state, true);
                Display.Mode mode = display().getMode();
                state.setProperty("baseline.width",Integer.toString(mode.getPhysicalWidth()));
                state.setProperty("baseline.height",Integer.toString(mode.getPhysicalHeight()));
            }
            catch (Exception e) { state.setProperty("surface_error", message(e)); }
        }
    }
    boolean prepareApply(Properties state, boolean compatible) throws Exception {
        if(compatible || platform.flyme) {
            Properties before=new Properties();before.putAll(state);
            // 旧服务已保存的两个通用原值继续作为本次接管基线，避免保存成已锁定的值。
            if(state.containsKey("baseline.settings_touched")) {
                state.putIfAbsent("baseline.setting.system/peak_refresh_rate",state.getProperty("baseline.peak"));
                state.putIfAbsent("baseline.setting.system/min_refresh_rate",state.getProperty("baseline.min"));
            }
            RefreshSettingJournal.capture(state,platform.settings(),settings);
            state.setProperty("baseline.settings_touched","1");
            return !before.equals(state);
        }
        // Scene 使用的物理控制入口有可读的占用标志；能力齐全时优先使用，避免先修改另一套范围。
        if(state.containsKey("baseline.miui_mode"))return false;
        if(preparePhysicalFallback(state))return true;
        if (!hasSurfaceBaseline(state)) {
            throw new IllegalStateException("无法保存显示范围，请开启兼容模式");
        }
        String key = compatible ? "baseline.settings_touched" : "baseline.surface_touched";
        if (state.containsKey(key)) return false;
        state.setProperty(key,"1");
        return true;
    }
    boolean preparePhysicalFallback(Properties state) throws Exception {
        if (!singleDisplay) return false;
        if (!state.containsKey("baseline.miui_mode")) {
            boolean overridden;
            try { overridden=RefreshSurface.overridden(); }catch(Exception e) { return false; }
            if (overridden) throw new IllegalStateException("其他工具已锁定物理显示模式，请先关闭其刷新率控制");
            float hz=actualRate();if(!Float.isFinite(hz))return false;
            state.setProperty("baseline.miui_mode",Integer.toString(selectMode(hz,"")));
            state.setProperty("baseline.miui_hz",Float.toString(hz));
        }
        return true;
    }
    String applyPhysical(float hz,String custom) throws Exception {
        if (!singleDisplay) throw new IllegalStateException("多屏环境无法确认物理控制目标");
        RefreshSurface.select(selectMode(hz,custom));
        observedAt=-30000;
        return "SurfaceFlinger 物理模式";
    }
    boolean requestMatches(float hz,String custom) throws Exception {
        Object spec=specs();
        if (modeField(spec).getInt(spec) != selectMode(hz,custom)) return false;
        Properties actual=new Properties(), expected=new Properties();
        flatten(spec,"",actual,true);
        restrict(spec,hz,0); flatten(spec,"",expected,true);
        return actual.equals(expected);
    }
    String apply(float hz, boolean compatible, String custom, Properties state) throws Exception {
        if (compatible || platform.flyme) {
            // 写入成功不代表厂商已执行，由控制器继续核对物理刷新率。
            RefreshSettingJournal.apply(state,platform.settings(),RefreshPolicy.rateLabel(hz),settings);
            observedAt=-30000;
            return platform.name()+"设置";
        }
        Object spec = specs();
        modeField(spec).setInt(spec, selectMode(hz, custom));
        try { field(spec,"allowGroupSwitching").setBoolean(spec, false); } catch (NoSuchFieldException ignored) { }
        int changed = restrict(spec, hz, 0);
        if (changed < 2) throw new IllegalStateException("无法识别此系统的刷新率范围，请使用兼容模式");
        Object result = setSpecs(spec);
        if (Boolean.FALSE.equals(result)) throw new IllegalStateException("显示服务拒绝模式切换");
        return "显示服务";
    }
    void restore(Properties state, String boot) throws Exception {
        Exception failure=null;
        try { if (state.containsKey("baseline.miui_mode") && boot.equals(state.getProperty("baseline.boot"))) {
            // 先恢复原档位，再解除强制模式；释放失败不能清掉恢复记录。
            try {
                RefreshSurface.select(selectMode(Float.parseFloat(state.getProperty("baseline.miui_hz")),""));
            } finally { RefreshSurface.release(); }
        } }catch(Exception e) { failure=e; }
        try { if (state.containsKey("baseline.settings_touched")) {
            RefreshSettings savedUser=new RefreshSettings(Integer.parseInt(state.getProperty("baseline.user","0")));
            if(state.stringPropertyNames().stream().noneMatch(k->k.startsWith("baseline.setting."))) {
                state.setProperty("baseline.setting.system/peak_refresh_rate",state.getProperty("baseline.peak"));
                state.setProperty("baseline.setting.system/min_refresh_rate",state.getProperty("baseline.min"));
            }
            RefreshSettingJournal.restore(state,savedUser);
        } }catch(Exception e) { if(failure==null)failure=e;else failure.addSuppressed(e); }
        try { if (state.containsKey("baseline.surface_touched") && boot.equals(state.getProperty("baseline.boot")) && hasSurfaceBaseline(state)) {
            Object spec = specs();
            int currentMode = modeField(spec).getInt(spec);
            Display.Mode mode = display().getMode();
            boolean sameResolution = Integer.toString(mode.getPhysicalWidth()).equals(state.getProperty("baseline.width"))
                && Integer.toString(mode.getPhysicalHeight()).equals(state.getProperty("baseline.height"));
            flatten(spec, "", state, false);
            // 管理期间用户可能更换分辨率，恢复范围时不把分辨率一起改回去。
            if (!sameResolution) modeField(spec).setInt(spec,currentMode);
            Object result = setSpecs(spec);
            if (Boolean.FALSE.equals(result)) throw new IllegalStateException("显示服务拒绝恢复原范围");
            Properties expected=new Properties(),actual=new Properties();
            flatten(spec,"",expected,true);flatten(specs(),"",actual,true);
            if(!expected.equals(actual)) {
                // 旧 MIUI 会忽略标准范围写入，物理入口又会改变该 getter 的视图。
                // 只有已核对原档位且释放控制权，才接受该后端的恢复；其他环境继续保留基线。
                boolean miuiPhysical=platform.xiaomi && android.os.Build.VERSION.SDK_INT<=32 && state.containsKey("baseline.miui_mode");
                float original=Float.parseFloat(state.getProperty("baseline.miui_hz","NaN"));
                float restored=actualRate();
                if(!miuiPhysical || !Float.isFinite(restored) || Math.abs(restored-original)>.6f || RefreshSurface.overridden())
                    throw new IllegalStateException("显示范围恢复校验失败，已保留原值等待重试");
                state.setProperty("restore_note","厂商标准范围接口未接受写入；已核对原物理档位并释放控制权");
            }
        } }catch(Exception e) { if(failure==null)failure=e;else failure.addSuppressed(e); }
        if(failure!=null)throw failure;
        state.keySet().removeIf(k -> k.toString().startsWith("baseline."));
        settings=new RefreshSettings();observedAt=-30000;
    }
    private String setting(String key) throws Exception { String s = settings.get(key); return s == null ? "<absent>" : s; }
    private Object setSpecs(Object spec) throws Exception {
        try { return surface.getDeclaredMethod("setDesiredDisplayModeSpecs",IBinder.class,spec.getClass()).invoke(null,token,spec); }
        catch (NoSuchMethodException e) {
            Object array = java.lang.reflect.Array.newInstance(spec.getClass(),1);
            java.lang.reflect.Array.set(array,0,spec);
            return surface.getDeclaredMethod("setDesiredDisplayModeSpecs",IBinder.class,array.getClass()).invoke(null,token,array);
        }
    }
    private static int restrict(Object value, float hz, int depth) throws Exception {
        if (depth > 3 || value == null) return 0;
        int count = 0;
        for (Field f : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.getType() == float.class && (n.contains("min") || n.contains("max"))) { f.setFloat(value,hz); count++; }
            else if (n.contains("range") || n.equals("physical") || n.equals("render")) count += restrict(f.get(value),hz,depth+1);
        }
        return count;
    }
    private static void flatten(Object value, String path, Properties state, boolean save) throws Exception {
        if (value == null || path.split("\\.").length > 5) return;
        for (Field f : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true); String p = path + f.getName(), key = "baseline.sf." + p;
            Class<?> type = f.getType();
            if (type == float.class || type == int.class || type == boolean.class) {
                if (save) state.setProperty(key, String.valueOf(f.get(value)));
                else if (state.containsKey(key)) {
                    String s = state.getProperty(key);
                    if (type == float.class) f.setFloat(value,Float.parseFloat(s));
                    else if (type == int.class) f.setInt(value,Integer.parseInt(s));
                    else f.setBoolean(value,Boolean.parseBoolean(s));
                }
            } else if (type.getName().startsWith("android.view.SurfaceControl$")) flatten(f.get(value),p+".",state,save);
        }
    }
    private static Field field(Object v, String name) throws NoSuchFieldException { Field f = v.getClass().getDeclaredField(name); f.setAccessible(true); return f; }
    private static Field modeField(Object spec) throws NoSuchFieldException {
        try { return field(spec,"defaultMode"); } catch (NoSuchFieldException e) { return field(spec,"defaultConfig"); }
    }
    private static boolean hasSurfaceBaseline(Properties p) { return p.containsKey("baseline.sf.defaultMode") || p.containsKey("baseline.sf.defaultConfig"); }
    static String message(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
    }
}
