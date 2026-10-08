using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

namespace Qixia.Builder
{
    internal sealed class ProjectVersion
    {
        public string Name;
        public int Code;
        public static ProjectVersion Read(string project)
        {
            return Parse(File.ReadAllText(Path.Combine(project, "app", "build.gradle.kts")));
        }
        static ProjectVersion Parse(string text)
        {
            var name = new Regex("(?m)^\\s*versionName\\s*=\\s*\"([^\"]+)\"").Matches(text);
            var code = new Regex(@"(?m)^\s*versionCode\s*=\s*(\d+)").Matches(text);
            if (name.Count != 1 || code.Count != 1) throw new IOException("无法唯一识别 App 版本字段，请检查 app/build.gradle.kts。");
            return new ProjectVersion { Name = name[0].Groups[1].Value, Code = Int32.Parse(code[0].Groups[1].Value) };
        }
        public static int Validate(string name, string code)
        {
            int number;
            if (!Regex.IsMatch(name, @"^v?\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$"))
                throw new ArgumentException("版本名请使用 v2.0.2 或 2.0.2 这样的格式，可带 beta 等后缀。");
            if (!Int32.TryParse(code, out number) || number < 1 || number > 2100000000)
                throw new ArgumentException("版本编号必须为 1～2100000000 的整数；发布新版时请增大编号。");
            return number;
        }
        public static void Save(string project, string name, string code, ProjectVersion expected)
        {
            using (var mutex = new Mutex(false, HostPaths.MutexName(project))) {
                bool owned = false;
                try {
                    try { owned = mutex.WaitOne(0); } catch (AbandonedMutexException) { owned = true; }
                    if (!owned) throw new IOException("这个项目正在构建，请等待结束后修改版本。");
                    SaveLocked(project, name, code, expected);
                } finally { if (owned) mutex.ReleaseMutex(); }
            }
        }

        // 调用者持有项目构建锁。先校验并锁定全部文件，避免只修改一半或覆盖外部编辑。
        public static void SaveLocked(string project, string name, string code, ProjectVersion expected)
        {
            int number = Validate(name, code);
            var paths = new[] { "app/build.gradle.kts", "app/src/main/java/top/qixia/threads/DaemonBridge.kt",
                "native_daemon/daemon_rs/src/daemon_core/preamble.rs", "magisk_module/module.prop", "modules_update/QixiaThreads.json" };
            var files = new List<FileStream>();
            var original = new List<byte[]>();
            var output = new List<byte[]>();
            string simple = name.TrimStart('v'), tag = "v" + simple;
            var encoding = new UTF8Encoding(false, true);
            try {
                foreach (var path in paths) {
                    var file = new FileStream(Path.Combine(project, path), FileMode.Open, FileAccess.ReadWrite, FileShare.Read);
                    files.Add(file);
                    if (file.Length > 4000000) throw new IOException("版本文件过大：" + path);
                    var bytes = new byte[(int)file.Length];
                    int read = 0;
                    while (read < bytes.Length) {
                        int next = file.Read(bytes, read, bytes.Length - read);
                        if (next == 0) throw new EndOfStreamException(path);
                        read += next;
                    }
                    original.Add(bytes);
                    bool bom = bytes.Length >= 3 && bytes[0] == 239 && bytes[1] == 187 && bytes[2] == 191;
                    string text = encoding.GetString(bytes, bom ? 3 : 0, bytes.Length - (bom ? 3 : 0));
                    if (path == paths[0]) {
                        var current = Parse(text);
                        if (expected != null && (current.Name != expected.Name || current.Code != expected.Code))
                            throw new IOException("版本已被其他编辑器修改，请点击“重新载入”后重试。");
                        text = Replace(text, "(?m)^(\\s*versionName\\s*=\\s*)\"[^\"]*\"", "\"" + name + "\"");
                        text = Replace(text, @"(?m)^(\s*versionCode\s*=\s*)\d+", number.ToString());
                    } else if (path == paths[1]) {
                        text = Replace(text, "(REQUIRED_MODULE_VERSION_NAME\\s*=\\s*)\"[^\"]*\"", "\"" + simple + "\"");
                        text = Replace(text, @"(REQUIRED_MODULE_VERSION_CODE\s*=\s*)\d+", number.ToString());
                    } else if (path == paths[2]) {
                        text = Replace(text, "(?m)^(pub\\(super\\)\\s+const\\s+VERSION:\\s*&str\\s*=\\s*)\"[^\"]*\"", "\"" + simple + "\"");
                    } else if (path == paths[3]) {
                        text = Replace(text, @"(?m)^(version=)[^\r\n]*", name);
                        text = Replace(text, @"(?m)^(versionCode=)[^\r\n]*", number.ToString());
                    } else {
                        var json = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(text);
                        if (json == null || !json.ContainsKey("zipUrl")) throw new IOException("更新配置缺少 zipUrl。");
                        string url = Convert.ToString(json["zipUrl"]);
                        // 只替换 Release 下载路径的标签；不改用户配置的平台和更新日志地址。
                        url = Regex.Replace(url, @"(/releases/download/)[^/]+(/)", m => m.Groups[1].Value + tag + m.Groups[2].Value);
                        text = Replace(text, "(\"version\"\\s*:\\s*)\"[^\"]*\"", "\"" + tag + "\"");
                        text = Replace(text, "(\"versionCode\"\\s*:\\s*)\\d+", number.ToString());
                        text = Replace(text, "(\"zipUrl\"\\s*:\\s*)\"[^\"]*\"", new JavaScriptSerializer().Serialize(url));
                        new JavaScriptSerializer().DeserializeObject(text);
                    }
                    var data = encoding.GetBytes(text);
                    output.Add(bom ? new byte[] { 239, 187, 191 }.Concat(data).ToArray() : data);
                }
                int last = -1;
                try {
                    for (int i = 0; i < files.Count; i++) {
                        if (original[i].SequenceEqual(output[i])) continue;
                        last = i; Write(files[i], output[i]);
                    }
                } catch (Exception writeError) {
                    var errors = new List<Exception> { writeError };
                    for (int i = 0; i <= last; i++) {
                        try { Write(files[i], original[i]); } catch (Exception rollback) { errors.Add(rollback); }
                    }
                    throw new AggregateException("版本写入失败，已尝试恢复原文件。", errors);
                }
            } finally { foreach (var file in files) file.Dispose(); }
        }
        static void Write(FileStream stream, byte[] data) {
            stream.Position = 0; stream.Write(data, 0, data.Length); stream.SetLength(data.Length); stream.Flush(true);
        }
        static string Replace(string text, string pattern, string value) {
            var regex = new Regex(pattern);
            if (regex.Matches(text).Count != 1) throw new IOException("版本字段缺失或重复，未修改任何文件：" + pattern);
            return regex.Replace(text, m => m.Groups[1].Value + value);
        }
    }
}
