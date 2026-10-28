package qixia.display;

import android.os.Build;
import android.os.SystemClock;
import java.nio.file.*;
import java.util.*;
import qixia.refresh.RefreshPlatform;

final class RefreshDevice {
    static final String[] BACKLIGHTS={"/sys/class/backlight/panel0-backlight",
        "/sys/devices/platform/soc/soc:mtk_leds/leds/lcd-backlight",
        "/sys/devices/platform/soc/soc:mtk-leds/leds/lcd-backlight",
        "/sys/devices/platform/mtk-leds/leds/lcd-backlight",
        "/sys/devices/platform/panel_drv_0/backlight/panel"};
    static String read(String path) {
        try { return new String(Files.readAllBytes(Paths.get(path)),java.nio.charset.StandardCharsets.UTF_8).trim(); }
        catch(Exception e) { return ""; }
    }
    static RefreshPlatform profile() {
        Map<String,String> props=new HashMap<>();
        for(String key:new String[]{"ro.product.brand","ro.miui.ui.version.name","ro.board.platform","ro.hardware","ro.soc.manufacturer"}) {
            try { props.put(key,(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,key)); }
            catch(Exception ignored) { }
        }
        Set<String> nodes=new HashSet<>();
        for(String path:new String[]{"/proc/mi_display","/sys/module/vivo_display","/sys/kernel/oplus_display/min_fps"})
            if(Files.exists(Paths.get(path)))nodes.add(path);
        return new RefreshPlatform(Build.VERSION.SDK_INT,props,nodes,read("/sys/bus/soc/devices/soc0/soc_id"),read("/proc/mz_info/model_name"));
    }
    private long brightnessAt=-10000;
    private float lastBrightness=-1;
    float brightness(android.view.Display display) {
        try {
            Object info=android.view.Display.class.getMethod("getBrightnessInfo").invoke(display);
            if(info!=null) {
                float value=RefreshPlatform.brightness(info.getClass().getField("brightness").getFloat(info),1f);
                if(value>=0)return value;
            }
        } catch(Exception ignored) { }
        long now=SystemClock.elapsedRealtime();if(now-brightnessAt<1000)return lastBrightness;
        brightnessAt=now;lastBrightness=-1;
        for(String path:BACKLIGHTS) try {
            // brightness_clone 在部分 ROM 使用另一套量程；只配对标准 brightness/max_brightness。
            float value=RefreshPlatform.brightness(Float.parseFloat(read(path+"/brightness")),Float.parseFloat(read(path+"/max_brightness")));
            if(value>=0)return lastBrightness=value;
        } catch(NumberFormatException ignored) { }
        return lastBrightness;
    }
}
