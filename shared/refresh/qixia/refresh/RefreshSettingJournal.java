package qixia.refresh;

import java.util.*;

/** 写入前保存全部原值，逐项恢复并核验；任一恢复失败都保留基线供下次重试。 */
public final class RefreshSettingJournal {
    public interface Store {
        String get(String key) throws Exception;
        void put(String key, String value) throws Exception;
    }
    private static final String PREFIX = "baseline.setting.";
    public static void capture(Properties state, List<String> keys, Store store) throws Exception {
        for (String key : keys) if (!state.containsKey(PREFIX + key)) {
            String value = store.get(key);
            state.setProperty(PREFIX + key, value == null ? "<absent>" : value);
        }
    }
    public static void apply(Properties state, List<String> keys, String value, Store store) throws Exception {
        for (String key : keys) if (!state.containsKey(PREFIX + key)) throw new IllegalStateException("未保存设置原值: " + key);
        for (String key : keys) {
            store.put(key, value);
            if (!Objects.equals(value, store.get(key))) throw new IllegalStateException("设置未生效: " + key);
        }
    }
    public static void restore(Properties state, Store store) throws Exception {
        Exception failure = null;
        for (String saved : new TreeSet<>(state.stringPropertyNames())) if (saved.startsWith(PREFIX)) {
            String key = saved.substring(PREFIX.length());
            String value = state.getProperty(saved); if ("<absent>".equals(value)) value = null;
            try {
                store.put(key, value);
                if (!Objects.equals(value, store.get(key))) throw new IllegalStateException("恢复校验失败: " + key);
            } catch (Exception e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        if (failure != null) throw failure;
    }
}
