package qixia.display;

import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 独立 app_process 入口，由模块现有看门狗统一管理。 */
public final class RefreshService {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要刷新率状态文件路径");
        // MIUI 等系统初始化框架时会读取主消息循环，必须先于控制器创建。
        Looper.prepareMainLooper();
        File dir = new File(args[0]).getAbsoluteFile().getParentFile().getParentFile();
        String boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")), StandardCharsets.UTF_8).trim();
        RefreshRateController controller = new RefreshRateController(dir, boot);
        Runtime.getRuntime().addShutdownHook(new Thread(controller::shutdown, "QixiaRefreshShutdown"));
        Looper.loop();
    }
}
