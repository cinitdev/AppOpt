package qixia.display;

import android.content.Context;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** 卸载时助手已经停止，也能恢复兼容模式遗留的系统设置。 */
public final class RefreshRestore {
    @SuppressWarnings("deprecation") // 独立 app_process 入口需要初始化主消息循环。
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要刷新率状态文件路径");
        File file = new File(args[0]);
        if (!file.isFile()) return;
        Properties p = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8)) { p.load(reader); }
        if (!p.containsKey("baseline.peak")) return;
        Looper.prepareMainLooper();
        Class<?> c = Class.forName("android.app.ActivityThread");
        Context context = (Context)c.getMethod("getSystemContext").invoke(c.getMethod("systemMain").invoke(null));
        String boot = new String(Files.readAllBytes(Paths.get("/proc/sys/kernel/random/boot_id")),StandardCharsets.UTF_8).trim();
        new RefreshDisplay(context).restore(p,boot);
        p.setProperty("status","disabled"); p.setProperty("enabled","0");p.setProperty("error","");
        p.setProperty("reason","策略已停用");
        for (String key : new String[]{"target_hz","actual_hz","backend","input_error"}) p.remove(key);
        File temp=new File(file.getPath()+".restore.tmp");
        try (FileOutputStream stream=new FileOutputStream(temp); Writer w=new OutputStreamWriter(stream,StandardCharsets.UTF_8)) { p.store(w,"刷新率已恢复");w.flush();stream.getFD().sync(); }
        if (!temp.renameTo(file)) throw new IOException("恢复状态保存失败");
        System.out.println("刷新率原设置已恢复");
    }
}
