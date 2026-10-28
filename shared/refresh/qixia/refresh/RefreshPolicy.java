package qixia.refresh;

import java.util.*;

/** App 与独立刷新率服务共用的策略格式；0 表示最低档，-1 表示最高档。 */
public final class RefreshPolicy {
    public static final Map<String, String> DEFAULTS;
    static {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        d.put("enabled", "0"); d.put("active_hz", "-1"); d.put("idle_hz", "0");
        d.put("lock_hz", "0"); d.put("gesture_hz", "-1");
        d.put("battery_hz", "0"); d.put("battery_threshold", "5");
        d.put("brightness_hz", "-1"); d.put("brightness_threshold", "0");
        d.put("tap_ms", "900"); d.put("scroll_ms", "2000");
        d.put("strict", "auto"); d.put("compatible", "auto");
        d.put("display_modes", "-1 -1 -1");
        DEFAULTS = Collections.unmodifiableMap(d);
    }
    public final Map<String, String> values;
    public RefreshPolicy(Map<String, String> values) { this.values = Collections.unmodifiableMap(new TreeMap<>(values)); }
    public static RefreshPolicy parse(String raw) {
        Map<String, String> m = new TreeMap<>();
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq < 1) continue;
            String key = line.substring(0, eq).trim(), value = line.substring(eq + 1).trim();
            if (valid(key, value)) m.put(key, value);
        }
        return new RefreshPolicy(m);
    }
    public static boolean valid(String key, String value) {
        try {
            if (key.startsWith("app.")) {
                if (!key.substring(4).matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) return false;
                String[] p = value.split(",", -1);
                // 只使用闲置、活动两档；已有第三列不再参与分类，避免丢失用户设定的两档。
                return (p.length == 2 || p.length == 3 && (p[2].equals("0") || p[2].equals("1")))
                    && hz(p[0], true) && hz(p[1], true);
            }
            if (key.equals("display_modes")) {
                for (String id : value.split(" +", -1)) if (!modeId(id)) return false;
                return true;
            }
            if (!DEFAULTS.containsKey(key)) return false;
            if ((key.equals("strict") || key.equals("compatible")) && value.equals("auto")) return true;
            if (key.endsWith("_hz")) return hz(value, false);
            int n = Integer.parseInt(value);
            if (key.endsWith("_threshold")) return n >= 0 && n <= 100;
            if (key.endsWith("_ms")) return n >= 300 && n <= 10000;
            return n == 0 || n == 1;
        } catch (RuntimeException e) { return false; }
    }
    private static boolean hz(String s, boolean inherit) {
        if (!s.matches("-?[0-9]+(?:\\.[0-9]+)?")) return false;
        float v = Float.parseFloat(s);
        return Float.isFinite(v) && (v == -1 || v == 0 || (inherit && v == -2) || v > 0);
    }
    public String get(String k) { return values.getOrDefault(k, DEFAULTS.getOrDefault(k, "")); }
    public int integer(String k) { return Integer.parseInt(get(k)); }
    public boolean on(String k) { return get(k).equals("1"); }
    /** 自动值按设备解析，用户明确写入的 0/1 始终优先。 */
    public boolean on(String k, boolean automatic) { return get(k).equals("auto") ? automatic : on(k); }
    /** ID 按设备实际档位从高到低对应，实际应用前还会核验物理模式的 Hz 和分辨率。 */
    public String displayMode(float hz, float[] supported) {
        float[] rates = displayRates(supported);
        String[] ids = get("display_modes").split(" +");
        // 设备档位数量变化时自动匹配，不把数量不符的手动 ID 挪到其他档位。
        if (ids.length != rates.length || !valid("display_modes", get("display_modes"))) return "";
        for (int i = 0; i < rates.length; i++)
            if (Float.compare(hz, rates[i]) == 0) return ids[i].equals("-1") ? "" : ids[i];
        return "";
    }
    private static boolean modeId(String id) {
        try { return id.matches("-1|[0-9]+") && Integer.parseInt(id) >= -1; }
        catch (NumberFormatException e) { return false; }
    }
    /** 仅保留设备报告的有效档位，按从高到低排列，不生成预设档位。 */
    public static float[] displayRates(float[] supported) {
        TreeSet<Float> sorted = new TreeSet<>(Collections.reverseOrder());
        for (float hz : supported) if (Float.isFinite(hz) && hz > 0) sorted.add(hz);
        float[] rates = new float[sorted.size()];
        int i = 0; for (float hz : sorted) rates[i++] = hz;
        return rates;
    }
    /** 标签隐藏驱动浮点尾差，模式匹配仍使用设备原始档位。 */
    public static String rateLabel(float hz) {
        return new java.math.BigDecimal(Float.toString(hz)).setScale(3, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }
    /** 默认三项均为自动；界面按当前设备档位数展开，避免限定为三档。 */
    public String displayModeIds(float[] supported) {
        float[] rates = displayRates(supported);
        String configured = get("display_modes");
        if (valid("display_modes", configured) && configured.split(" +").length == rates.length)
            return String.join(" ", configured.split(" +"));
        StringJoiner ids = new StringJoiner(" ");
        for (float ignored : rates) ids.add("-1");
        return ids.toString();
    }
    public static String bindDisplayModes(String input, float[] supported) {
        float[] rates = displayRates(supported);
        String[] ids = input.trim().split(" +");
        if (rates.length == 0 || ids.length != rates.length)
            throw new IllegalArgumentException("模式 ID 数量需要与设备刷新率档位一致");
        StringJoiner mapping = new StringJoiner(" ");
        for (int i = 0; i < rates.length; i++) {
            if (!modeId(ids[i])) throw new IllegalArgumentException("模式 ID 必须是 -1 或非负整数");
            mapping.add(ids[i]);
        }
        return Arrays.stream(ids).allMatch("-1"::equals) ? DEFAULTS.get("display_modes") : mapping.toString();
    }
    public RefreshPolicy with(String key, String value) {
        Map<String, String> m = new TreeMap<>(values);
        if (value == null) m.remove(key);
        else { if (!valid(key, value)) throw new IllegalArgumentException("无效刷新率配置: " + key); m.put(key, value); }
        return new RefreshPolicy(m);
    }
    public String encode() {
        StringBuilder s = new StringBuilder("# 柒夏线程刷新率管理；应用规则=闲置Hz,活动Hz；-2 跟随全局，-1 最高，0 最低\n");
        Map<String, String> all = new TreeMap<>(DEFAULTS); all.putAll(values);
        all.forEach((k,v) -> s.append(k).append('=').append(v).append('\n'));
        return s.toString();
    }
    public static final class Decision {
        public final float hz; public final String reason;
        Decision(float hz, String reason) { this.hz = hz; this.reason = reason; }
    }
    public Decision decide(String pkg, boolean launcher, boolean locked, boolean active,
                           int battery, float brightness, float[] supported) {
        String[] app = values.getOrDefault("app." + pkg, "-2,-2").split(",");
        String reason = locked ? "锁屏" : launcher ? active ? "桌面／系统界面 · 活动" : "桌面／系统界面 · 闲置" : active ? "活动" : "闲置";
        float value = Float.parseFloat(get(locked ? "lock_hz" : launcher && active ? "gesture_hz" : active ? "active_hz" : "idle_hz"));
        if (!locked && !launcher) {
            float override = Float.parseFloat(app[active ? 1 : 0]);
            if (override != -2) { value = override; reason = "应用 · " + reason; }
        }
        float rate = resolve(value, supported);
        if (battery >= 0 && integer("battery_threshold") > 0 && battery < integer("battery_threshold")) {
            rate = Math.min(rate, resolve(Float.parseFloat(get("battery_hz")), supported)); reason += " · 低电量";
        }
        // 低亮度下保持用户选定的档位，避免部分屏幕低刷闪烁；优先于低电量策略。
        if (!locked && brightness >= 0 && integer("brightness_threshold") > 0 && brightness < integer("brightness_threshold")) {
            rate = resolve(Float.parseFloat(get("brightness_hz")), supported); reason += " · 低亮度";
        }
        return new Decision(rate, reason);
    }
    public static float resolve(float requested, float[] supported) {
        if (supported.length == 0) throw new IllegalArgumentException("设备未提供刷新率档位");
        float min = Float.MAX_VALUE, max = 0, best = supported[0];
        for (float v : supported) {
            min = Math.min(min, v); max = Math.max(max, v);
            if (Math.abs(v-requested) < Math.abs(best-requested)) best = v;
        }
        return requested == -1 ? max : requested == 0 ? min : best;
    }
}
