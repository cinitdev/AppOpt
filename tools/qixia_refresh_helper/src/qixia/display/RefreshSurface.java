package qixia.display;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** 单屏且可确认控制权时使用物理模式入口；不按 Android 版本猜测事务号。 */
final class RefreshSurface {
    private static IBinder service(String name) throws Exception {
        IBinder binder = (IBinder)Class.forName("android.os.ServiceManager")
            .getMethod("getService",String.class).invoke(null,name);
        if (binder == null) throw new IOException("显示服务尚未就绪");
        return binder;
    }
    /** 仅启用前读取一次，不能覆盖其他工具已经持有的强制模式。 */
    static boolean overridden() throws Exception {
        String dump=dump("SurfaceFlinger");
        Boolean active=qixia.refresh.RefreshModes.overrideState(dump);
        if(active!=null)return active;
        throw new IOException("系统未提供物理模式控制权状态");
    }
    static String dump(String service, String... args) throws Exception {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        FutureTask<String> read = new FutureTask<>(() -> {
          try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                  new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]),StandardCharsets.UTF_8))) {
            StringBuilder out=new StringBuilder();
            String line;
            while ((line=reader.readLine()) != null) {
                out.append(line).append('\n');
                if(out.length()>4*1024*1024)throw new IOException("显示服务输出超出读取上限");
            }
            return out.toString();
          }
        });
        Thread thread=new Thread(read,"QixiaDisplayCheck"); thread.setDaemon(true); thread.start();
        try {
            service(service).dumpAsync(pipe[1].getFileDescriptor(),args);
            pipe[1].close();
            return read.get(2,TimeUnit.SECONDS);
        } finally {
            pipe[0].close(); pipe[1].close(); read.cancel(true);
        }
    }
    static void select(int mode) throws Exception {
        Parcel request=Parcel.obtain(), reply=Parcel.obtain();
        try {
            request.writeInterfaceToken("android.ui.ISurfaceComposer");
            request.writeInt(mode);
            // 与报告中的协议一致；-1 释放控制。ID 必须来自主屏物理列表。
            if (!service("SurfaceFlinger").transact(1035,request,reply,0)) throw new IOException("系统不支持物理模式控制");
            if (reply.dataAvail() >= 4 && reply.readInt() != 0) throw new IOException("系统拒绝物理模式控制");
        } finally { request.recycle(); reply.recycle(); }
    }
    static void release() throws Exception {
        Exception response=null;
        try { select(-1); }catch(Exception e) { response=e; }
        // Android 17 可在成功释放后仍返回 BAD_VALUE；以控制权状态为准，不能仅看事务返回值。
        if(!overridden())return;
        if(response!=null)throw response;
        throw new IOException("物理模式控制权尚未释放");
    }
}
