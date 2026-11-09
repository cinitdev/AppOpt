package qixia.foreground;

/** 与 App、Rust 共用同一行格式，只读取 record 开关，不改变任何调度状态。 */
final class PerformanceRecordingConfig {
    static boolean enabled(String text, String pkg) {
        if (pkg == null || pkg.length() >= 128 || !pkg.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) return false;
        for (String line : text.split("\n")) {
            String[] parts = line.split("#", 2)[0].trim().split(",", -1);
            if (!parts[0].trim().equals(pkg)) continue;
            boolean valid = true, recording = false, custom = false;
            for (int i = 1; i < parts.length; i++) {
                String flag = parts[i].trim();
                if (flag.equals("record")) recording = true;
                else if (flag.startsWith("custom=")) {
                    if (custom || !validCustom(flag.substring(7))) valid = false;
                    custom = true;
                }
                else if (!flag.equals("auto") && !flag.equals("fallback")) valid = false;
            }
            if (valid && recording) return true;
        }
        return false;
    }

    // 仅校验共用格式，不在助手中执行负载分档或选核。
    private static boolean validCustom(String value) {
        if (value.length() > 2048) return false;
        String[] tiers = value.split("\\|", -1);
        if (tiers.length < 1 || tiers.length > 8) return false;
        boolean[] seen = new boolean[101];
        for (String tier : tiers) {
            String[] pair = tier.split(":", -1);
            if (pair.length != 2 || !pair[0].matches("(?:[1-9][0-9]?|100)")) return false;
            int threshold = Integer.parseInt(pair[0]);
            if (seen[threshold]) return false;
            seen[threshold] = true;
            for (String range : pair[1].split("\\+", -1)) {
                if (!range.matches("(?:0|[1-9][0-9]?)(?:-(?:0|[1-9][0-9]?))?")) return false;
                String[] ends = range.split("-", -1);
                int first = Integer.parseInt(ends[0]);
                int last = Integer.parseInt(ends[ends.length - 1]);
                if (first > last || last > 63) return false;
            }
        }
        return true;
    }
}
