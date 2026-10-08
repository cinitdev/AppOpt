package android.app;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.RemoteException;

/** 仅供编译的声明，运行时由设备框架提供实际类。 */
public abstract class TaskStackListener {
    public TaskStackListener() {
    }

    public void onTaskStackChanged() throws RemoteException {
    }

    public void onTaskMovedToFront(RunningTaskInfo taskInfo) throws RemoteException {
    }

    public void onTaskFocusChanged(int taskId, boolean focused) throws RemoteException {
    }
}
