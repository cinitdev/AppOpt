package qixia.display;

import android.content.Context;
import android.os.Looper;
import java.nio.file.*;
import java.util.*;

/** 显示接口回归入口；不随正式模块打包。真机往返验证必须显式指定匹配的设备代号。 */
public final class RefreshRateProbe {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> c = Class.forName("android.app.ActivityThread");
        Context context = (Context)c.getMethod("getSystemContext").invoke(c.getMethod("systemMain").invoke(null));
        RefreshDisplay d = new RefreshDisplay(context);
        if (args.length == 1 && args[0].equals("binding-repeat")) {
            // 只读验证首次连接、刷新屏幕身份和重建控制器，不改变系统刷新率。
            for (int round = 0; round < 3; round++) {
                RefreshDisplay check = round == 0 ? d : new RefreshDisplay(context);
                System.out.println("before_"+round+"="+check.specs());
                check.rates();
                System.out.println("after_"+round+"="+check.specs());
            }
            System.out.println("BINDING_REPEAT_OK"); System.exit(0);
        }
        String boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id"))).trim();
        Properties p = new Properties(); d.capture(p,boot);
        System.out.println("rates="+Arrays.toString(d.rates())); System.out.println("modes="+d.modes());
        try { System.out.println("spec="+d.specs()); }
        catch(Exception e) { System.out.println("spec_unavailable="+RefreshDisplay.message(e)); }
        System.out.println("actual="+d.actualRate());
        System.out.println("platform="+d.platform.name()+" modern_mtk="+d.platform.modernMtk+" settings="+d.platform.settings());
        if(args.length==1 && args[0].equals("physical-roundtrip")) {
            if(!android.os.Build.MODEL.contains("sdk_gphone"))throw new IllegalStateException("物理入口单独回归仅在模拟器执行");
            try {
                if(!d.preparePhysicalFallback(p))throw new AssertionError("物理入口不可用");
                System.out.println("PHYSICAL_REQUEST="+d.applyPhysical(d.rates()[0],""));
                if(!RefreshSurface.overridden())throw new AssertionError("物理控制未生效");
            }finally { d.restore(p,boot); }
            if(RefreshSurface.overridden())throw new AssertionError("物理控制未释放");
            System.out.println("PHYSICAL_RESTORE_OK");System.exit(0);
        }
        if(args.length==1 && args[0].equals("settings-roundtrip")) {
            if(!android.os.Build.MODEL.contains("sdk_gphone") && !android.os.Build.FINGERPRINT.contains("generic"))
                throw new IllegalStateException("故障注入仅允许在模拟器执行");
            try {
                d.prepareApply(p,true);
                // 模拟 ROM 未开放物理观测接口，兼容写入仍必须可以独立工作。
                for(String name:new String[]{"token","physicalId","singleDisplay"}) {
                    java.lang.reflect.Field f=RefreshDisplay.class.getDeclaredField(name);f.setAccessible(true);
                    if(name.equals("token"))f.set(d,null);else if(name.equals("physicalId"))f.setLong(d,-1);else f.setBoolean(d,false);
                }
                java.lang.reflect.Field cached=RefreshDisplay.class.getDeclaredField("observedAt");cached.setAccessible(true);cached.setLong(d,-30000);
                if(Float.isFinite(d.actualRate()))throw new AssertionError("未能模拟观测接口缺失");
                System.out.println("REQUEST_WITHOUT_OBSERVATION="+d.apply(d.rates()[0],true,"",p));
            }finally { d.restore(p,boot); }
            if(p.containsKey("baseline.peak"))throw new AssertionError("兼容恢复未完成");
            System.out.println("SETTINGS_RESTORE_OK");System.exit(0);
        }
        for (java.lang.reflect.Method method : Class.forName("android.view.SurfaceControl").getDeclaredMethods())
            if (method.getName().matches(".*(DesiredDisplay|ActiveConfig|ActiveDisplay|RefreshRate).*")) System.out.println("setter="+method);
        boolean deviceRoundtrip = args.length == 2 && args[0].equals("device-roundtrip") && args[1].equals(android.os.Build.DEVICE);
        if (deviceRoundtrip || args.length>0 && args[0].equals("roundtrip")) {
            if (!deviceRoundtrip && !android.os.Build.FINGERPRINT.contains("generic") && !android.os.Build.MODEL.contains("sdk_gphone")) throw new IllegalStateException("真机需要明确匹配设备代号");
            String original = d.specs().toString();
            float originalRate=d.actualRate();
            boolean legacy=false;
            try {
                d.prepareApply(p,false);
                for (float rate : d.rates()) {
                    String backend;
                    if (legacy) backend=d.applyPhysical(rate,"");
                    else {
                        backend=d.apply(rate,false,"",p);
                        if (!d.requestMatches(rate,"") && d.preparePhysicalFallback(p)) {
                            legacy=true;
                            backend=d.applyPhysical(rate,"");
                        }
                    }
                    System.out.println("direct="+rate+":"+backend);
                    awaitRate(d,rate);
                    System.out.println("settled="+d.specs()+" actual="+d.actualRate());
                    if (Math.abs(d.actualRate()-rate) > .6f) throw new AssertionError("物理刷新率未切换到目标档位");
                }
            } finally { d.restore(p,boot); }
            awaitRate(d,originalRate);
            if (legacy) {
                if (RefreshSurface.overridden() || Math.abs(d.actualRate()-originalRate) > .6f)
                    throw new AssertionError("MIUI 物理档位或控制权未恢复");
            } else if (!original.equals(d.specs().toString())) throw new AssertionError("显示范围未完整恢复");
            if (p.containsKey("baseline.peak")) throw new AssertionError("恢复基线未清除");
            System.out.println("RESTORE_OK actual="+d.actualRate());
        }
        System.exit(0);
    }
    private static void awaitRate(RefreshDisplay display,float rate) throws Exception {
        long deadline=android.os.SystemClock.elapsedRealtime()+3000;
        while (Math.abs(display.actualRate()-rate) > .6f && android.os.SystemClock.elapsedRealtime()<deadline)
            Thread.sleep(100);
    }
}
