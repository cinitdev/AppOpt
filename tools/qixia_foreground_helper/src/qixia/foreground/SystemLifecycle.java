package qixia.foreground;

import java.lang.reflect.Method;

/** 只读系统生命周期信号；读取失败时不推断正在关机，保留真实故障诊断。 */
final class SystemLifecycle {
    private static final Method GET = propertyGetter();

    private SystemLifecycle() { }

    static boolean shuttingDown() {
        String requested = property("sys.shutdown.requested");
        // ShutdownThread 写入“0/1 + 原因”，因此单独的 0 也表示已请求关机。
        if (requested.startsWith("0") || requested.startsWith("1")) return true;
        String power = property("sys.powerctl");
        return power.equals("shutdown") || power.startsWith("shutdown,")
            || power.equals("reboot") || power.startsWith("reboot,");
    }

    private static Method propertyGetter() {
        try { return Class.forName("android.os.SystemProperties").getMethod("get", String.class); }
        catch (ReflectiveOperationException | SecurityException ignored) { return null; }
    }

    private static String property(String key) {
        if (GET == null) return "";
        try { return (String) GET.invoke(null, key); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return ""; }
    }
}
