package qixia.display;

import android.content.AttributionSource;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import java.util.*;

/** Root 助手通过外部 Provider 接口读取设置，避免伪装成未登记的普通应用进程。 */
final class RefreshSettings implements qixia.refresh.RefreshSettingJournal.Store, AutoCloseable {
    final int user;
    RefreshSettings() { this(currentUser()); }
    RefreshSettings(int user) {
        this.user=user;
        // insert 按调用进程所属用户处理；副用户继续走显式携带 _user 的原接口。
        batchUnavailable=user!=0;
    }
    static int currentUser() {
        try { return (Integer)Class.forName("android.app.ActivityManager").getMethod("getCurrentUser").invoke(null); }
        catch(Exception e) { throw new IllegalStateException("无法确认当前 Android 用户",e); }
    }
    private Object provider;
    private Object activityManager;
    private Method callMethod, batchMethod;
    private boolean batchUnavailable;
    private final Binder token = new Binder();
    private final AttributionSource source = new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
    private Object provider() throws Exception {
        if (provider == null) {
            activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
            Object holder = Class.forName("android.app.IActivityManager").getMethod("getContentProviderExternal",String.class,int.class,IBinder.class,String.class)
                .invoke(activityManager,"settings",user,token,"qixia-refresh");
            if (holder == null) throw new IllegalStateException("SettingsProvider 不可用");
            provider = holder.getClass().getField("provider").get(holder);
        }
        return provider;
    }
    private Bundle call(String operation,String key,Bundle extras) throws Exception {
        extras.putInt("_user",user);
        if (callMethod == null) callMethod = Class.forName("android.content.IContentProvider").getMethod("call",
            AttributionSource.class,String.class,String.class,String.class,Bundle.class);
        return (Bundle)invoke(callMethod,source,"settings",operation,key,extras);
    }
    private Object invoke(Method method,Object... args) throws Exception {
        for (int attempt=0;;attempt++) {
            try { return method.invoke(provider(),args); }
            catch (InvocationTargetException wrapped) {
                Throwable cause=wrapped.getCause();
                // Provider 重启时释放旧引用，只重连一次；写入内容相同，可安全重试。
                if(cause instanceof DeadObjectException) { close(); if(attempt==0)continue; }
                if(cause instanceof Exception)throw (Exception)cause;
                throw wrapped;
            }
        }
    }
    private String[] parts(String key) {
        String[] parts=key.contains("/")?key.split("/",2):new String[]{"system",key};
        if(parts.length!=2 || (!parts[0].equals("system") && !parts[0].equals("secure") && !parts[0].equals("global")) || parts[1].isEmpty())
            throw new IllegalArgumentException("无效设置命名空间或键名");
        return parts;
    }
    public String get(String key) throws Exception {
        String[] p=parts(key);Bundle b=call("GET_"+p[0],p[1],new Bundle());
        if(b==null)throw new IllegalStateException("读取设置回复为空: "+key);
        return b.getString("value");
    }
    public void put(String key,String value) throws Exception {
        String[] p=parts(key);
        Bundle b=new Bundle();if(value!=null) b.putString("value",value);
        call((value==null?"DELETE_":"PUT_")+p[0],p[1],b);
        // 写入核验由 Journal 统一执行，避免每个键重复读取两遍。
    }
    @Override public void putAll(Map<String,String> values) throws Exception {
        if(values.isEmpty())return;
        // 先验证全部键名，避免遇到无效键时已经写入前面几项。
        for(String key:values.keySet())parts(key);
        Exception batchFailure=null;
        if(!batchUnavailable)try {
            if(batchMethod==null)batchMethod=Class.forName("android.content.IContentProvider").getMethod("applyBatch",
                AttributionSource.class,String.class,ArrayList.class);
            ArrayList<ContentProviderOperation> operations=new ArrayList<>(values.size());
            for(Map.Entry<String,String> entry:values.entrySet()) {
                String[] p=parts(entry.getKey());
                // 使用系统生成的 Binder 代理封送；不写死事务号和 Parcel 布局。
                // SettingsProvider 的 PUT_ 返回空 Bundle，不能包装成批量 call 操作。
                Uri uri=Uri.parse("content://settings/"+p[0]);
                operations.add(entry.getValue()==null
                    ? ContentProviderOperation.newDelete(Uri.withAppendedPath(uri,p[1])).build()
                    : ContentProviderOperation.newInsert(uri).withValue("name",p[1]).withValue("value",entry.getValue()).build());
            }
            ContentProviderResult[] results=(ContentProviderResult[])invoke(batchMethod,source,"settings",operations);
            if(results==null || results.length!=operations.size())throw new IllegalStateException("设置批量写入回复不完整");
            for(ContentProviderResult result:results)
                if(result==null || result.exception!=null)throw new IllegalStateException("设置批量写入失败",result==null?null:result.exception);
            return;
        }catch(Exception e) {
            batchFailure=e;batchUnavailable=true;
            System.err.println("刷新率批量设置接口不可用，回退逐项写入: "+e);
        }
        // 厂商接口不支持批量操作时回退原调用，后续仍核验，不把失败当作成功。
        try { qixia.refresh.RefreshSettingJournal.Store.super.putAll(values); }
        catch(Exception e) { if(batchFailure!=null)e.addSuppressed(batchFailure);throw e; }
    }
    @Override public void close() {
        if(provider==null)return;
        try {
            Class.forName("android.app.IActivityManager").getMethod("removeContentProviderExternalAsUser",
                String.class,IBinder.class,int.class).invoke(activityManager,"settings",token,user);
        }catch(Exception e) { System.err.println("释放刷新率设置接口引用失败: "+e); }
        finally { provider=null; }
    }
}
