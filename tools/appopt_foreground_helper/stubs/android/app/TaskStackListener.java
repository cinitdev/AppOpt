package android.app;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.RemoteException;

/** Compile-only declaration. The device framework provides the real class at runtime. */
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
