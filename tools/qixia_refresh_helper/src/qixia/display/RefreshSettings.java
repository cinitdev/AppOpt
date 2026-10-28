package qixia.display;

import android.content.AttributionSource;
import android.os.*;
import java.lang.reflect.*;

/** Root 助手通过外部 Provider 接口读取设置，避免伪装成未登记的普通应用进程。 */
final class RefreshSettings implements qixia.refresh.RefreshSettingJournal.Store {
    final int user;
    RefreshSettings() { this(currentUser()); }
    RefreshSettings(int user) { this.user=user; }
    static int currentUser() {
        try { return (Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null); }
        catch(Exception e) { throw new IllegalStateException("无法确认当前 Android 用户",e); }
    }
    private Object provider;
    private final Binder token = new Binder();
    private final AttributionSource source = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
    private Object provider() throws Exception {
        if (provider == null) {
            Object am = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Object holder = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(am,"settings",user,token,"qixia-refresh");
            if (holder == null) throw new IllegalStateException("SettingsProvider 不可用");
            provider = holder.getClass().getField("provider").get(holder);
        }
        return provider;
    }
    private Bundle call(String operation,String key,Bundle extras) throws Exception {
        extras.putInt("_user",user);
        Method call = Class.forName("android.content.IContentProvider").getMethod("call",AttributionSource.class,String.class,String.class,String.class,Bundle.class);
        try { return (Bundle)call.invoke(provider(),source,"settings",operation,key,extras); }
        catch (Exception e) { provider=null;throw e; }
    }
    private String[] parts(String key) {
        String[] parts=key.contains("/")?key.split("/",2):new String[]{"system",key};
        if(!parts[0].equals("system") && !parts[0].equals("secure")) throw new IllegalArgumentException("无效设置命名空间");
        return parts;
    }
    public String get(String key) throws Exception { String[] p=parts(key);Bundle b=call("GET_"+p[0],p[1],new Bundle()); return b==null?null:b.getString("value"); }
    public void put(String key,String value) throws Exception {
        String[] p=parts(key);
        Bundle b=new Bundle();if(value!=null) b.putString("value",value);
        call((value==null?"DELETE_":"PUT_")+p[0],p[1],b);
        if (!java.util.Objects.equals(value,get(key))) throw new IllegalStateException("设置写入校验失败: "+key);
    }
}
