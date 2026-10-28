package qixia.foreground;

import android.os.Looper;

/** 直接复用实际拉起入口，核对 Root 助手可否独立唤起胶囊，不启动 App 主界面。 */
public final class PerformanceOverlayProbe {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+") ||
            !(args[1].equals("show") || args[1].equals("leave"))) throw new IllegalArgumentException("需要目标包名和 show/leave");
        Looper.prepareMainLooper();
        PerformanceOverlayController.initialize();
        boolean ok = PerformanceOverlayController.dispatch(0,args[0],args[1]);
        System.out.println("overlay_dispatch="+ok);
        System.exit(ok ? 0 : 1);
    }
}
