using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Qixia.Builder
{
    internal sealed class BuildOptions
    {
        public string Project, Bash, JavaHome = "", NdkHome = "", Token = "";
        public string Variant = "release", Action = "build", Target = "gitee";
        public bool UpdateSubmodule = true;
        public string VersionName, VersionCode;
        public ProjectVersion ExpectedVersion;

        public string[] ScriptArguments()
        {
            if (!new[] { "release", "debug", "no" }.Contains(Variant))
                throw new ArgumentException("未知的构建类型。");
            if (!new[] { "build", "publish", "preview", "check" }.Contains(Action))
                throw new ArgumentException("未知的构建操作。");
            if (Target != "gitee" && Target != "github") throw new ArgumentException("未知的发布平台。");
            if (Action == "check") return new[] { "tools/windows_builder/check_environment.sh" };
            if (Action != "build" && Variant != "release") throw new ArgumentException("发布和预演仅支持正式版。");
            var args = new List<string> { "build_module.sh", Variant };
            if (Action != "build")
            {
                args.Add("--" + Target);
                args.Add(Action == "publish" ? "--publish" : "--dry-run");
            }
            return args.ToArray();
        }

        public void Validate()
        {
            Project = Path.GetFullPath(Project.Trim());
            if (!File.Exists(Path.Combine(Project, "build_module.sh")))
                throw new ArgumentException("请选择包含 build_module.sh 的项目目录。");
            if (!File.Exists(Bash) || !File.Exists(Path.Combine(Path.GetDirectoryName(Bash), "..", "etc", "profile")) &&
                !File.Exists(Path.Combine(Path.GetDirectoryName(Bash), "..", "..", "etc", "profile")))
                throw new ArgumentException("请选择 Git for Windows 的 bash.exe（不支持 WSL Bash）。");
            if (JavaHome.Length > 0 && !File.Exists(Path.Combine(JavaHome, "bin", "javac.exe")))
                throw new ArgumentException("JDK 目录中没有 bin\\javac.exe。");
            if (NdkHome.Length > 0 && !Directory.Exists(Path.Combine(NdkHome, "toolchains", "llvm")))
                throw new ArgumentException("NDK 目录中没有 toolchains\\llvm。");
            ScriptArguments();
            if (Action != "check" && VersionName != null) ProjectVersion.Validate(VersionName, VersionCode);
        }

        public Dictionary<string, string> EnvironmentValues()
        {
            var env = new Dictionary<string, string> {
                { "QIXIA_SKIP_SUBMODULE_UPDATE", UpdateSubmodule ? "0" : "1" },
                { "QIXIA_PUBLISH_TARGET", Target }, { "PYTHONUTF8", "1" },
                { "PYTHONIOENCODING", "utf-8" }, { "PYTHONUNBUFFERED", "1" },
                { "NO_COLOR", "1" }, { "TERM", "dumb" }, { "GIT_TERMINAL_PROMPT", "0" },
                { "GCM_INTERACTIVE", "Never" }, { "GH_PROMPT_DISABLED", "1" }
            };
            if (JavaHome.Length > 0) {
                env["JAVA_HOME"] = JavaHome;
                env["PATH"] = Path.Combine(JavaHome, "bin") + ";" + Environment.GetEnvironmentVariable("PATH");
            }
            if (NdkHome.Length > 0) env["ANDROID_NDK_HOME"] = NdkHome;
            if (Token.Length > 0) env["GITEE_TOKEN"] = Token;
            return env;
        }

        public string CommandPreview()
        {
            return "bash " + String.Join(" ", ScriptArguments());
        }
    }

    internal static class HostPaths
    {
        public static string FindProject()
        {
            foreach (var start in new[] { AppDomain.CurrentDomain.BaseDirectory, Environment.CurrentDirectory }) {
                var dir = new DirectoryInfo(start);
                while (dir != null) {
                    if (File.Exists(Path.Combine(dir.FullName, "build_module.sh"))) return dir.FullName;
                    dir = dir.Parent;
                }
            }
            return Environment.CurrentDirectory;
        }

        public static string FindBash()
        {
            var paths = new List<string>();
            foreach (var hive in new[] { Registry.CurrentUser, Registry.LocalMachine }) {
                using (var key = hive.OpenSubKey(@"SOFTWARE\GitForWindows")) {
                    if (key != null) paths.Add(Path.Combine(Convert.ToString(key.GetValue("InstallPath")), "bin", "bash.exe"));
                }
            }
            paths.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Git", "bin", "bash.exe"));
            paths.Add(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "Git", "bin", "bash.exe"));
            return paths.FirstOrDefault(File.Exists) ?? "";
        }

        // 按 Windows 的命令行参数规则转义；不会经过 cmd 或 bash -c。
        public static string Quote(string value)
        {
            var result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') result.Append('\\', slashes * 2 + 1);
                else result.Append('\\', slashes);
                slashes = 0;
                result.Append(c);
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }

        public static string MutexName(string project)
        {
            string normalized = Path.GetFullPath(project).TrimEnd('\\', '/').ToUpperInvariant();
            using (var sha = SHA256.Create())
                return "Local\\QixiaBuilder_" + BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(normalized))).Replace("-", "");
        }
    }

    internal sealed class LogSanitizer
    {
        readonly string[] secrets;
        public LogSanitizer(string token)
        {
            var values = new List<string> { token };
            foreach (var name in new[] { "GITEE_TOKEN", "GH_TOKEN", "GITHUB_TOKEN" })
                foreach (EnvironmentVariableTarget target in Enum.GetValues(typeof(EnvironmentVariableTarget))) {
                    try { values.Add(Environment.GetEnvironmentVariable(name, target)); } catch { }
                }
            secrets = values.Where(v => !String.IsNullOrEmpty(v)).Distinct().OrderByDescending(v => v.Length).ToArray();
        }
        public string Clean(string value)
        {
            value = Regex.Replace(value ?? "", @"\x1B\[[0-?]*[ -/]*[@-~]", "");
            foreach (var secret in secrets) {
                value = value.Replace(secret, "[已隐藏]");
                value = value.Replace(Uri.EscapeDataString(secret), "[已隐藏]");
            }
            value = Regex.Replace(value, @"(?i)(access_token|authorization|gitee_token|github_token|gh_token)(\s*[:=]\s*)\S+", "$1$2[已隐藏]");
            return value;
        }
    }
}
