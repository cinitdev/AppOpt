using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace Qixia.Builder
{
    internal static class SelfTests
    {
        static int count;
        static void Check(bool value, string message) {
            if (!value) throw new Exception("FAIL: " + message);
            count++; Console.WriteLine("PASS: " + message);
        }
        static bool Gone(int pid) {
            try { return Process.GetProcessById(pid).HasExited; } catch (ArgumentException) { return true; }
        }
        public static int Run()
        {
            var options = new BuildOptions { Project = HostPaths.FindProject(), Bash = HostPaths.FindBash() };
            options.Validate();
            VersionTests(options.Project);
            foreach (var variant in new[] { "release", "debug", "no" }) {
                options.Variant = variant;
                Check(String.Join(" ", options.ScriptArguments()) == "build_module.sh " + variant, "本地构建参数 " + variant);
            }
            options.Variant = "release";
            foreach (var action in new[] { "publish", "preview" })
                foreach (var target in new[] { "gitee", "github" }) {
                    options.Action = action; options.Target = target;
                    Check(String.Join(" ", options.ScriptArguments()) == "build_module.sh release --" + target + (action == "publish" ? " --publish" : " --dry-run"), action + " / " + target);
                }
            options.Variant = "debug";
            bool refused = false;
            try { options.ScriptArguments(); } catch (ArgumentException) { refused = true; }
            Check(refused, "拒绝发布调试版");
            options.Action = "build"; options.Variant = "release";
            Check(HostPaths.MutexName(options.Project) == HostPaths.MutexName(options.Project.ToUpperInvariant() + "\\"), "项目锁路径规范化");
            var arguments = new[] { "", "中文 & 空格", "$(echo bad);`echo bad`", "C:\\path with space\\", "a\"b", "C:\\x\\\"q" };
            using (var echo = Process.Start(new ProcessStartInfo(Program.Executable, "--echo-args " + String.Join(" ", arguments.Select(HostPaths.Quote))) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, StandardOutputEncoding = Encoding.UTF8 })) {
                var output = echo.StandardOutput.ReadToEnd(); echo.WaitForExit();
                var decoded = output.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None).Take(arguments.Length)
                    .Select(v => Encoding.UTF8.GetString(Convert.FromBase64String(v))).ToArray();
                Check(decoded.SequenceEqual(arguments), "参数空格、中文、引号、尾部反斜线和 shell 元字符保真");
            }
            var clean = new LogSanitizer("secret123!x");
            Check(!clean.Clean("url?access_token=secret123!x").Contains("secret123"), "令牌隐藏");
            Check(clean.Clean("\x1b[31m中文\x1b[0m") == "中文", "清理 ANSI 颜色");
            options.Project = Path.Combine(options.Project, "build", "builder-tests");
            var lines = new List<string>();
            var runner = new BuildRunner { OnLine = line => { lock (lines) lines.Add(line); } };
            var result = runner.RunTest(options, "fail").GetAwaiter().GetResult();
            Check(result.ExitCode == 23 && !result.Cancelled, "失败退出码原样返回");
            Check(lines.Any(l => l == "标准输出：中文") && lines.Any(l => l == "标准错误：中文"), "双通道 UTF-8 日志");
            Check(File.ReadAllText(result.LogPath).Contains("标准错误：中文"), "完整日志写入");
            result = new BuildRunner().RunTest(options, "publish-fail").GetAwaiter().GetResult();
            Check(result.ModulePackaged && result.ExitCode == 23, "ZIP 完成后发布失败仍返回失败，产物状态单独保留");
            options.Token = "only_for_test_secret";
            lines.Clear();
            result = new BuildRunner { OnLine = line => lines.Add(line) }.RunTest(options, "token").GetAwaiter().GetResult();
            Check(!File.ReadAllText(result.LogPath).Contains(options.Token) && !lines.Any(l => l.Contains(options.Token)), "凭据不进入界面或磁盘日志");
            var ready = new ManualResetEventSlim(false); int leaf = 0;
            runner = new BuildRunner { OnLine = line => {
                if (line.StartsWith("LEAF=")) { leaf = Int32.Parse(line.Substring(5)); ready.Set(); }
            }};
            var running = runner.RunTest(options, "tree");
            try {
                Check(ready.Wait(15000), "工作进程启动子进程");
                bool locked = false;
                try { new BuildRunner().RunTest(options, "fail").GetAwaiter().GetResult(); }
                catch (InvalidOperationException) { locked = true; }
                Check(locked, "同一项目拒绝并行构建");
                runner.Cancel();
                result = running.GetAwaiter().GetResult();
                Check(result.Cancelled && SpinWait.SpinUntil(() => Gone(leaf), 5000), "停止工作进程及其子进程");
            } finally { runner.Cancel(); running.GetAwaiter().GetResult(); }
            result = new BuildRunner().RunTest(options, "ok").GetAwaiter().GetResult();
            Check(result.ExitCode == 0, "停止后释放项目锁并可重新构建");
            runner = new BuildRunner(); runner.Cancel();
            result = runner.RunTest(options, "tree").GetAwaiter().GetResult();
            Check(result.Cancelled, "启动前取消不会遗漏子进程");
            Console.WriteLine("通过 " + count + " 项检查。");
            return 0;
        }

        internal static string VersionFixture(string project)
        {
            string directory = Path.Combine(project, "build", "builder-tests", "版本 fixture & spaces");
            foreach (var file in new[] { "app/build.gradle.kts", "app/src/main/java/top/qixia/threads/DaemonBridge.kt",
                "native_daemon/daemon_rs/src/daemon_core/preamble.rs", "magisk_module/module.prop", "modules_update/QixiaThreads.json" }) {
                string target = Path.Combine(directory, file);
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                File.Copy(Path.Combine(project, file), target, true);
            }
            return directory;
        }

        static void VersionTests(string project)
        {
            var directory = VersionFixture(project);
            var original = ProjectVersion.Read(directory);
            var json = new System.Web.Script.Serialization.JavaScriptSerializer();
            string manifest = Path.Combine(directory, "modules_update/QixiaThreads.json");
            var before = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(manifest));
            ProjectVersion.Save(directory, "v9.8.7-rc.1", "987", original);
            var changed = ProjectVersion.Read(directory);
            Check(changed.Name == "v9.8.7-rc.1" && changed.Code == 987, "版本编辑保存并重新读取");
            Check(File.ReadAllText(Path.Combine(directory, "magisk_module/module.prop")).Contains("versionCode=987") &&
                File.ReadAllText(Path.Combine(directory, "native_daemon/daemon_rs/src/daemon_core/preamble.rs")).Contains("\"9.8.7-rc.1\"") &&
                File.ReadAllText(Path.Combine(directory, "app/src/main/java/top/qixia/threads/DaemonBridge.kt")).Contains("REQUIRED_MODULE_VERSION_CODE = 987"), "模块、Rust 和 App 要求版本同步");
            var after = json.Deserialize<Dictionary<string, object>>(File.ReadAllText(manifest));
            Check(Convert.ToString(after["version"]) == "v9.8.7-rc.1" && Convert.ToInt32(after["versionCode"]) == 987 &&
                Convert.ToString(after["zipUrl"]).Contains("/v9.8.7-rc.1/") && Equals(before["changelog"], after["changelog"]), "更新清单版本及下载标签同步，保留日志地址");
            bool refused = false;
            try { ProjectVersion.Save(directory, "v9.8.8", "988", original); } catch (IOException) { refused = true; }
            Check(refused && ProjectVersion.Read(directory).Code == 987, "拒绝覆盖外部更改后的版本");
            using (var held = new Mutex(true, HostPaths.MutexName(directory))) {
                bool locked = System.Threading.Tasks.Task.Run(() => {
                    try { ProjectVersion.Save(directory, "v9.8.8", "988", changed); return false; } catch (IOException) { return true; }
                }).GetAwaiter().GetResult();
                held.ReleaseMutex(); Check(locked, "构建锁阻止中途修改版本");
            }
            string prop = Path.Combine(directory, "magisk_module/module.prop");
            File.WriteAllText(prop, "invalid fixture", new UTF8Encoding(false));
            refused = false;
            try { ProjectVersion.Save(directory, "v9.8.8", "988", changed); } catch (IOException) { refused = true; }
            Check(refused && ProjectVersion.Read(directory).Code == 987 && File.ReadAllText(manifest).Contains("v9.8.7-rc.1"), "任一版本字段不完整时全部文件保持原状");
            refused = false;
            try { ProjectVersion.Validate("v2.0.2\"; execute", "202"); } catch (ArgumentException) { refused = true; }
            Check(refused, "拒绝非法版本文本");
        }

        public static int Worker()
        {
            if (Console.ReadLine() != "RUN") return 125;
            var test = Environment.GetEnvironmentVariable("QIXIA_GUI_TEST");
            if (test == "tree") {
                using (var child = Process.Start(new ProcessStartInfo(Program.Executable, "--test-leaf") { UseShellExecute = false, CreateNoWindow = true })) {
                    Console.WriteLine("LEAF=" + child.Id); Console.Out.Flush(); child.WaitForExit();
                }
            }
            if (test == "token") Console.WriteLine(Environment.GetEnvironmentVariable("GITEE_TOKEN"));
            if (test == "publish-fail") Console.WriteLine("- 完成: /test/build/QixiaThreads.zip");
            Console.WriteLine("标准输出：中文");
            Console.Error.WriteLine("标准错误：中文");
            return test == "fail" || test == "publish-fail" ? 23 : 0;
        }
    }
}
