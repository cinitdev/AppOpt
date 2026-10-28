package qixia.display;

import android.os.Process;
import android.system.*;
import java.io.*;
import java.nio.*;
import java.util.*;

/** 只检测交互发生与滑动，不保存输入内容；不用 getevent 子进程，不独占输入设备。 */
final class RefreshInput implements AutoCloseable {
    interface Listener { void activity(boolean scroll); void error(String message); }
    private volatile boolean closed;
    private final Thread worker;
    RefreshInput(Listener listener) {
        worker = new Thread(() -> {
            while (!closed) {
                run(listener);
                if (!closed) try { Thread.sleep(5000); } catch (InterruptedException ignored) { }
            }
        }, "QixiaRefreshInput");
        worker.setDaemon(true); worker.start();
    }
    private void run(Listener listener) {
        List<StructPollfd> devices = new ArrayList<>();
        byte[] bytes = new byte[4096]; int size = Process.is64Bit() ? 24 : 16;
        int offset = size - 8;
        try {
            File[] nodes = new File("/dev/input").listFiles(f -> f.getName().matches("event[0-9]+"));
            if (nodes != null) for (File node : nodes) {
                try {
                    // 传感器也可能发布 EV_ABS；只监听带按键或相对指针能力的输入设备。
                    File caps = new File("/sys/class/input/"+node.getName()+"/device/capabilities");
                    if (!hasBits(new File(caps,"key")) && !hasBits(new File(caps,"rel"))) continue;
                    StructPollfd p = new StructPollfd();
                    p.fd = Os.open(node.getPath(), OsConstants.O_RDONLY | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC, 0);
                    p.events = (short) OsConstants.POLLIN; devices.add(p);
                } catch (ErrnoException ignored) { }
            }
            if (devices.isEmpty()) { listener.error("无法读取输入设备，暂时保持活动档位"); return; }
            listener.error("");
            StructPollfd[] poll = devices.toArray(new StructPollfd[0]);
            Map<FileDescriptor, int[]> positions = new HashMap<>();
            while (!closed) {
                Os.poll(poll, 1000);
                for (StructPollfd p : poll) {
                    if ((p.revents & (OsConstants.POLLHUP | OsConstants.POLLERR | OsConstants.POLLNVAL)) != 0)
                        throw new IOException("输入设备发生变化，正在重新连接");
                    if ((p.revents & OsConstants.POLLIN) == 0) continue;
                    int n;
                    try { n = Os.read(p.fd, bytes, 0, bytes.length); }
                    catch (ErrnoException e) { if (e.errno == OsConstants.EAGAIN) continue; throw e; }
                    ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                    boolean activity = false, scroll = false;
                    int[] xy = positions.computeIfAbsent(p.fd, k -> new int[]{Integer.MIN_VALUE,Integer.MIN_VALUE});
                    for (int i = 0; i + size <= n; i += size) {
                        int type = buffer.getShort(i + offset) & 0xffff, code = buffer.getShort(i+offset+2)&0xffff, value = buffer.getInt(i+offset+4);
                        if (type == 1 && value != 0) { activity = true; xy[0]=xy[1]=Integer.MIN_VALUE; }
                        if (type == 3 && (code == 53 || code == 54 || code == 0 || code == 1)) {
                            int axis = code == 53 || code == 0 ? 0 : 1;
                            if (xy[axis] != Integer.MIN_VALUE && Math.abs((long)value-xy[axis]) > 12) scroll = true;
                            if (xy[axis] == Integer.MIN_VALUE) xy[axis] = value;
                            activity = true;
                        }
                        if (type == 2 && value != 0) { activity = true; scroll = true; }
                    }
                    if (activity) listener.activity(scroll);
                }
            }
        } catch (Exception e) { if (!closed) listener.error(RefreshDisplay.message(e)); }
        finally { for (StructPollfd p : devices) try { Os.close(p.fd); } catch (Exception ignored) { } }
    }
    private static boolean hasBits(File file) {
        try {
            String s = new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.US_ASCII).replaceAll("\\s+","");
            return new java.math.BigInteger(s,16).signum()!=0;
        } catch (Exception e) { return false; }
    }
    @Override public void close() { closed = true; worker.interrupt(); }
}
