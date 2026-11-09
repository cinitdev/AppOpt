package qixia.foreground;

/** 可在普通 JDK 运行，确保助手与 App、Rust 接受相同的组合状态。 */
public final class PerformanceRecordingConfigTest {
    public static void main(String[] args) {
        for (String text : new String[] { "com.game,record", "com.game,auto,record", "com.game,fallback,record",
                "com.game,record,fallback", "com.game\ncom.game,record", " com.game , record # 开启\r\n",
                "com.game,custom=2:0-3+6|30:4-7,record", "com.game,fallback,custom=40:63|1:0,record" }) {
            if (!PerformanceRecordingConfig.enabled(text, "com.game")) throw new AssertionError(text);
        }
        for (String text : new String[] { "com.game", "com.game,fallback", "com.game,record,unknown",
                "com.game,record,", "com.game:child,record", "com.games,record",
                "com.game,custom=5:0", "com.game,custom=5:64,record", "com.game,custom=5:3-1,record",
                "com.game,custom=5:0|5:1,record", "com.game,custom=2:0,custom=5:1,record",
                "com.game,custom=5:01,record", "com.game,custom=5:,record", "com.game,custom=05:0,record" }) {
            if (PerformanceRecordingConfig.enabled(text, "com.game")) throw new AssertionError(text);
        }
        System.out.println("PerformanceRecordingConfigTest: 22 cases passed");
    }
}
