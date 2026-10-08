using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace Qixia.Builder
{
    internal sealed class BuildResult
    {
        public int ExitCode;
        public bool Cancelled;
        public bool ModulePackaged;
        public string LogPath;
    }

    // 工作进程等待 RUN 后才启动 Bash，确保所有后代都已纳入本次构建的作业对象。
    internal sealed class BuildRunner
    {
        readonly object gate = new object();
        JobObject job;
        bool cancelled;
        public Action<string> OnLine = delegate { };
        public string LogPath { get; private set; }

        public Task<BuildResult> Run(BuildOptions options)
        {
            options.Validate();
            return Task.Run(() => RunCore(options, "--worker", null));
        }

        internal Task<BuildResult> RunTest(BuildOptions options, string test)
        {
            return Task.Run(() => RunCore(options, "--test-worker", test));
        }

        BuildResult RunCore(BuildOptions options, string workerMode, string test)
        {
            using (var mutex = new Mutex(false, HostPaths.MutexName(options.Project))) {
                bool owns = false;
                try {
                    try { owns = mutex.WaitOne(0); } catch (AbandonedMutexException) { owns = true; }
                    if (!owns) throw new InvalidOperationException("另一个构建窗口正在使用这个项目，请等待它结束。");
                    var directory = Path.Combine(options.Project, "build");
                    Directory.CreateDirectory(directory);
                    var logPath = Path.Combine(directory, "build-gui.log");
                    var cleaner = new LogSanitizer(options.Token);
                    using (var log = new StreamWriter(logPath, false, new UTF8Encoding(false))) {
                        LogPath = logPath;
                        log.AutoFlush = true;
                        var outputGate = new object();
                        bool packaged = false;
                        Action<string> emit = value => {
                            if (value == null) return;
                            lock (outputGate) {
                                var safe = cleaner.Clean(value);
                                if (safe.StartsWith("- 完成: ") && safe.TrimEnd().EndsWith("/QixiaThreads.zip", StringComparison.Ordinal)) packaged = true;
                                log.WriteLine(safe);
                                OnLine(safe);
                            }
                        };
                        emit("柒夏线程 · " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
                        emit("项目：" + options.Project);
                        emit(options.CommandPreview());
                        if (options.Action != "check" && options.VersionName != null) {
                            ProjectVersion.SaveLocked(options.Project, options.VersionName, options.VersionCode, options.ExpectedVersion);
                            emit("- 已同步版本：" + options.VersionName + "（" + options.VersionCode + "）· APK / 模块 / Rust / 更新配置");
                        }
                        var info = new ProcessStartInfo(Program.Executable, workerMode) {
                            WorkingDirectory = options.Project, UseShellExecute = false, CreateNoWindow = true,
                            RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true,
                            StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
                        };
                        foreach (var pair in options.EnvironmentValues()) info.EnvironmentVariables[pair.Key] = pair.Value;
                        info.EnvironmentVariables["QIXIA_GUI_BASH"] = options.Bash;
                        info.EnvironmentVariables["QIXIA_GUI_ARGUMENTS"] = String.Join(" ", Array.ConvertAll(options.ScriptArguments(), HostPaths.Quote));
                        if (test != null) info.EnvironmentVariables["QIXIA_GUI_TEST"] = test;
                        using (var process = new Process { StartInfo = info }) {
                            process.OutputDataReceived += (s, e) => emit(e.Data);
                            process.ErrorDataReceived += (s, e) => emit(e.Data);
                            lock (gate) {
                                if (cancelled) return new BuildResult { ExitCode = -1, Cancelled = true, LogPath = logPath };
                                job = new JobObject();
                                try {
                                    process.Start();
                                    job.Assign(process);
                                } catch {
                                    try { if (!process.HasExited) process.Kill(); } catch { }
                                    throw;
                                }
                                process.BeginOutputReadLine();
                                process.BeginErrorReadLine();
                                process.StandardInput.WriteLine("RUN");
                                process.StandardInput.Close();
                            }
                            process.WaitForExit();
                            bool wasCancelled;
                            lock (gate) wasCancelled = cancelled;
                            emit(wasCancelled ? "已停止构建。" : "进程结束，退出码：" + process.ExitCode);
                            return new BuildResult { ExitCode = process.ExitCode, Cancelled = wasCancelled, ModulePackaged = packaged, LogPath = logPath };
                        }
                    }
                } finally {
                    lock (gate) { if (job != null) job.Dispose(); job = null; }
                    if (owns) mutex.ReleaseMutex();
                }
            }
        }

        public void Cancel()
        {
            lock (gate) { cancelled = true; if (job != null) job.Terminate(); }
        }

        public static int Worker()
        {
            if (Console.ReadLine() != "RUN") return 125;
            var info = new ProcessStartInfo(Environment.GetEnvironmentVariable("QIXIA_GUI_BASH"),
                "--noprofile --norc " + Environment.GetEnvironmentVariable("QIXIA_GUI_ARGUMENTS")) {
                UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true,
                RedirectStandardError = true, RedirectStandardInput = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
            };
            using (var process = new Process { StartInfo = info }) {
                process.OutputDataReceived += (s, e) => { if (e.Data != null) Console.WriteLine(e.Data); };
                process.ErrorDataReceived += (s, e) => { if (e.Data != null) Console.Error.WriteLine(e.Data); };
                process.Start();
                // 图形构建不能处理交互式密码提示；凭据应在终端预先配置。
                process.StandardInput.Close();
                process.BeginOutputReadLine();
                process.BeginErrorReadLine();
                process.WaitForExit();
                return process.ExitCode;
            }
        }
    }

    internal sealed class JobObject : IDisposable
    {
        IntPtr handle;
        public JobObject()
        {
            handle = CreateJobObject(IntPtr.Zero, null);
            if (handle == IntPtr.Zero) throw new System.ComponentModel.Win32Exception();
            var info = new ExtendedLimit();
            info.Basic.LimitFlags = 0x2000; // 关闭句柄时终止整个构建进程树。
            int size = Marshal.SizeOf(info);
            IntPtr memory = Marshal.AllocHGlobal(size);
            try {
                Marshal.StructureToPtr(info, memory, false);
                if (!SetInformationJobObject(handle, 9, memory, (uint)size)) throw new System.ComponentModel.Win32Exception();
            } catch { Dispose(); throw; }
            finally { Marshal.FreeHGlobal(memory); }
        }
        public void Assign(Process process)
        {
            if (!AssignProcessToJobObject(handle, process.Handle)) throw new System.ComponentModel.Win32Exception();
        }
        public void Terminate()
        {
            if (handle != IntPtr.Zero && !TerminateJobObject(handle, 130)) throw new System.ComponentModel.Win32Exception();
        }
        public void Dispose() { if (handle != IntPtr.Zero) CloseHandle(handle); handle = IntPtr.Zero; }
        [StructLayout(LayoutKind.Sequential)] struct BasicLimit {
            public long PerProcess, PerJob; public uint LimitFlags; public UIntPtr Min, Max;
            public uint Active; public UIntPtr Affinity; public uint Priority, Scheduling;
        }
        [StructLayout(LayoutKind.Sequential)] struct IoCounters { public ulong A, B, C, D, E, F; }
        [StructLayout(LayoutKind.Sequential)] struct ExtendedLimit {
            public BasicLimit Basic; public IoCounters Io; public UIntPtr Process, Job, PeakProcess, PeakJob;
        }
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] static extern IntPtr CreateJobObject(IntPtr attrs, string name);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr job, int type, IntPtr info, uint size);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", SetLastError = true)] static extern bool TerminateJobObject(IntPtr job, uint code);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    }
}
