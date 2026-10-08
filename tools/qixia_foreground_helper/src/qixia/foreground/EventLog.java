package qixia.foreground;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 与 Rust 共用 v1 事件格式，仅在事件发生时写入，最多保留三个 512 KiB 文件。 */
final class EventLog {
    private static final long LIMIT = 512 * 1024;
    private static final long WINDOW_NS = 30_000_000_000L;
    private static File file;
    private static FileOutputStream output;
    private static long bytes, sequence, retryAfter, lastEmit, pending, lastTimestamp;
    private static int pid;
    private static String previousLevel = "", previousMessage = "";

    static synchronized void init(File configDir, int processId) {
        pid = processId;
        file = new File(new File(configDir.getParentFile(), "logs"), "ForegroundEvents.log");
    }

    static void info(String message) { record("INFO", message); }
    static void warn(String message) { record("WARN", message); }
    static void error(String message) { record("ERROR", message); }

    private static synchronized void record(String level, String message) {
        if (file == null) { System.err.println(message); return; }
        long now = System.nanoTime();
        if (retryAfter != 0 && now - retryAfter < 0) return;
        String text = message;
        if (text.length() > 8192) {
            int end = Character.isHighSurrogate(text.charAt(8191)) ? 8191 : 8192;
            text = text.substring(0, end) + " …[日志过长，已截断]";
        }
        try {
            long timestamp = System.currentTimeMillis();
            if (text.equals(previousMessage) && level.equals(previousLevel)) {
                pending++;
                lastTimestamp = timestamp;
                if (now - lastEmit >= WINDOW_NS) { flushPending(); lastEmit = now; }
            } else {
                flushPending();
                append(level, text, timestamp, 1);
                previousMessage = text;
                previousLevel = level;
                lastEmit = now;
                lastTimestamp = timestamp;
            }
            retryAfter = 0;
        } catch (IOException failure) {
            retryAfter = now + WINDOW_NS;
            System.err.println("[日志] 前台事件写入失败，30 秒后重试: " + failure.getMessage());
        }
    }

    static synchronized void flush() {
        try { flushPending(); } catch (IOException ignored) { }
    }

    private static void flushPending() throws IOException {
        if (pending > 0) { append(previousLevel, previousMessage, lastTimestamp, pending); pending = 0; }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static void append(String level, String text, long timestamp, long count) throws IOException {
        String message = text.startsWith("[前台助手]") ? text.substring(6).trim() : text;
        byte[] line = ("@QIXIA/1\t" + timestamp + "\t" + pid + ":" + (++sequence) + "\t" + level
            + "\t前台助手\t" + count + "\t" + escape(message) + "\n").getBytes(StandardCharsets.UTF_8);
        if (output == null) open();
        if (bytes > 0 && bytes + line.length > LIMIT) {
            output.close(); output = null;
            File older = new File(file + ".2"), previous = new File(file + ".1");
            if (older.exists() && !older.delete()) throw new IOException("无法轮转旧事件文件");
            if (previous.exists() && !previous.renameTo(older)) throw new IOException("无法轮转事件文件");
            if (!file.renameTo(previous)) throw new IOException("无法归档事件文件");
            open();
        }
        output.write(line);
        bytes += line.length;
    }

    private static void open() throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建日志目录");
        output = new FileOutputStream(file, true);
        bytes = file.length();
    }
}
