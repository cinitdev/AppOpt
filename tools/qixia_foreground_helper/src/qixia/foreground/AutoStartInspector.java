package qixia.foreground;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.ContentProviderClient;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import java.util.ArrayList;
import java.util.Locale;

/** 自启动检查只读，不改变厂商开关，也不把缺失接口当成授权成功。 */
@SuppressWarnings("deprecation") // 保留 Android 12 可用的 Bundle 与包管理接口。
final class AutoStartInspector {
    private static final String PACKAGE = "top.qixia.threads";
    private static final int FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS;

    static void print(int user) {
        try {
            PerformanceOverlayController.initialize();
            Context system = PerformanceOverlayController.systemContext();
            UserHandle handle = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, user);
            Context context = (Context) Context.class.getMethod("createContextAsUser", UserHandle.class, int.class)
                .invoke(system, handle, 0);
            inspect(context);
        } catch (Throwable error) {
            while (error.getCause() != null) error = error.getCause();
            emit("unreadable", "自启动检查失败：" + error.getClass().getSimpleName(), "", "", "");
        }
    }

    private static void inspect(Context context) throws Exception {
        PackageManager pm = context.getPackageManager();
        String brand = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.ROOT);
        ProviderInfo oplus = pm.resolveContentProvider("com.oplus.startup.provider", FLAGS);
        String manager = firstInstalled(pm, "com.oplus.battery", "com.oplus.safecenter", "com.coloros.safecenter");
        if (oplus != null || contains(brand, "oppo", "realme", "oneplus") || !property("ro.build.version.oplusrom").isEmpty()) {
            if (oplus != null) manager = oplus.packageName;
            if ((!manager.isEmpty() && !pm.getApplicationInfo(manager, FLAGS).enabled) || (oplus != null && !oplus.enabled)) {
                emit("disabled", "系统启动管理组件已禁用，无法读取开关", "oplus", "", manager);
                return;
            }
            if (oplus == null) {
                emit("unsupported", "当前系统未提供已适配的启动管理接口", "oplus", "", manager);
                return;
            }
            readOplus(context, manager);
            return;
        }
        // 澎湃使用独立的系统属性识别，不要求保留 MIUI 属性或旧安全中心包名。
        boolean hyperOs = !property("ro.mi.os.version.name").isEmpty()
            || !property("ro.mi.os.version.code").isEmpty();
        manager = firstInstalled(pm, "com.miui.securitycenter");
        if (hyperOs || !manager.isEmpty() || contains(brand, "xiaomi", "redmi", "poco")
                || !property("ro.miui.ui.version.name").isEmpty()) {
            String source = hyperOs ? "hyperos" : "miui";
            if (!manager.isEmpty() && !pm.getApplicationInfo(manager, FLAGS).enabled) {
                emit("disabled", "系统启动管理组件已禁用，无法读取开关", source, "", manager);
                return;
            }
            final int op;
            try { op = AppOpsManager.class.getField("OP_AUTO_START").getInt(null); }
            catch (NoSuchFieldException absent) {
                emit("unsupported", "当前系统未提供可识别的自启动 AppOps，请在系统启动管理中核对", source, "", manager);
                return;
            }
            ApplicationInfo app = pm.getApplicationInfo(PACKAGE, 0);
            int mode = (Integer) AppOpsManager.class.getMethod("checkOpNoThrow", int.class, int.class, String.class)
                .invoke(context.getSystemService(AppOpsManager.class), op, app.uid, PACKAGE);
            String status = mode == AppOpsManager.MODE_ALLOWED ? "allowed" :
                mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED ? "blocked" : "unreadable";
            emit(status, "厂商自启动 AppOps" + ("unreadable".equals(status) ? "未返回明确授权状态" : ""), source, "", manager);
            return;
        }
        manager = firstInstalled(pm, "com.vivo.permissionmanager", "com.iqoo.secure", "com.huawei.systemmanager",
            "com.hihonor.systemmanager", "com.honor.systemmanager", "com.samsung.android.lool", "com.samsung.android.sm");
        if (!manager.isEmpty() && !pm.getApplicationInfo(manager, FLAGS).enabled) {
            emit("disabled", "系统启动管理组件已禁用，无法读取开关", "vendor", "", manager);
            return;
        }
        // 仅确认过的 Google/原生镜像可标记不适用，不能因为查不到厂商接口就放行所有设备。
        String fingerprint = Build.FINGERPRINT.toLowerCase(Locale.ROOT);
        boolean stock = manager.isEmpty() &&
            (("google".equalsIgnoreCase(Build.MANUFACTURER) && "google".equalsIgnoreCase(Build.BRAND) && fingerprint.startsWith("google/")) ||
             ("android".equalsIgnoreCase(Build.BRAND) && fingerprint.startsWith("android/")));
        emit(stock ? "not_required" : "unsupported", stock ? "当前系统无需单独授予厂商自启动权限；后台服务仍受系统规则约束" :
            "当前系统暂不支持读取，请在系统启动管理中核对", stock ? "aosp" : "vendor", "", manager);
    }

    private static void readOplus(Context context, String manager) {
        String status = "unreadable", associate = "", detail = "系统启动管理接口无法读取";
        try (ContentProviderClient client = context.getContentResolver()
                .acquireUnstableContentProviderClient(Uri.parse("content://com.oplus.startup.provider"))) {
            if (client == null) throw new IllegalStateException("Provider unavailable");
            Bundle input = new Bundle();
            ArrayList<String> packages = new ArrayList<>();
            packages.add(PACKAGE);
            input.putStringArrayList("packageList", packages);
            Bundle value = client.call("getStartupState", null, input);
            if (value != null && value.get(PACKAGE) instanceof Boolean) {
                status = value.getBoolean(PACKAGE) ? "allowed" : "blocked";
                detail = "已读取系统自启动开关";
            } else detail = "系统未返回此应用的自启动状态，不能判定已授权";
            try {
                Bundle linked = client.call("getAssociateStartState", null, input);
                if (linked != null && linked.get(PACKAGE) instanceof Boolean)
                    associate = linked.getBoolean(PACKAGE) ? "allowed" : "blocked";
            } catch (Exception ignored) { /* 关联启动不可读时保留独立的自启动结果。 */ }
        } catch (Exception error) {
            detail = "系统启动管理接口无法读取：" + error.getClass().getSimpleName();
        }
        emit(status, detail, "oplus", associate, manager);
    }

    private static String firstInstalled(PackageManager pm, String... packages) {
        for (String pkg : packages) {
            try { pm.getApplicationInfo(pkg, FLAGS); return pkg; }
            catch (PackageManager.NameNotFoundException ignored) { }
        }
        return "";
    }

    private static String property(String key) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key); }
        catch (Exception ignored) { return ""; }
    }

    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static void emit(String status, String detail, String source, String associate, String manager) {
        System.out.println("qixia_autostart=" + status);
        System.out.println("qixia_autostart_detail=" + detail.replace('\n', ' ').replace('\r', ' '));
        System.out.println("qixia_autostart_source=" + source);
        System.out.println("qixia_autostart_associate=" + associate);
        System.out.println("qixia_autostart_manager=" + manager);
    }
}
