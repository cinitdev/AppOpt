package qixia.refresh;

import java.util.*;

/** 厂商分支来自实际接口特征；未知厂商沿用通用能力，不猜测私有设置键。 */
public final class RefreshPlatform {
    public final boolean xiaomi, vivo, flyme, oplus, modernMtk;
    public RefreshPlatform(int sdk, Map<String,String> props, Set<String> nodes, String soc, String model) {
        String brand = props.getOrDefault("ro.product.brand", "").toLowerCase(Locale.ROOT);
        String platform = (soc + " " + props.getOrDefault("ro.board.platform", "") + " "
            + props.getOrDefault("ro.hardware", "") + " " + props.getOrDefault("ro.soc.manufacturer", "")).toLowerCase(Locale.ROOT);
        xiaomi = nodes.contains("/proc/mi_display") || !props.getOrDefault("ro.miui.ui.version.name", "").isEmpty()
            || Arrays.asList("xiaomi", "redmi", "poco").contains(brand);
        vivo = nodes.contains("/sys/module/vivo_display");
        flyme = Arrays.asList("m2391", "m2392", "m2481", "m2468").contains(model.trim().toLowerCase(Locale.ROOT));
        oplus = nodes.contains("/sys/kernel/oplus_display/min_fps");
        modernMtk = sdk > 34 && (platform.matches(".*\\bmt[0-9][a-z0-9]*\\b.*") || platform.contains("mediatek"));
    }
    public String name() { return flyme ? "Flyme" : vivo ? "vivo" : xiaomi ? "小米" : oplus ? "Oplus" : "通用"; }
    public List<String> settings() {
        if (flyme) return Collections.singletonList("system/flyme_force_rate");
        if (vivo) return Arrays.asList("system/peak_refresh_rate", "system/min_refresh_rate", "secure/vivo_screen_refresh_rate_mode");
        if (xiaomi) return Arrays.asList("system/peak_refresh_rate", "secure/miui_refresh_rate", "secure/user_refresh_rate");
        return Arrays.asList("system/peak_refresh_rate", "system/min_refresh_rate", "secure/user_refresh_rate");
    }
    public static float brightness(float current, float maximum) {
        return Float.isFinite(current) && Float.isFinite(maximum) && maximum > 0 && current >= 0 && current <= maximum
            ? 100f * current / maximum : -1;
    }
}
