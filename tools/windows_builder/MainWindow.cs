using System;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Markup;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace Qixia.Builder
{
    internal sealed class MainWindow
    {
        public readonly Window Window;
        readonly ConcurrentQueue<string> pending = new ConcurrentQueue<string>();
        readonly Stopwatch stopwatch = new Stopwatch();
        readonly DispatcherTimer timer;
        BuildRunner runner;
        bool closeWhenDone;
        string logPath, zipPath, apkPath;
        int queuedChars;
        bool dropped;
        ProjectVersion loadedVersion;
        string loadedProject;
        bool readingVersion;

        T Get<T>(string name) where T : class { return (T)Window.FindName(name); }
        TextBox Field(string name) { return Get<TextBox>(name); }
        Button Button(string name) { return Get<Button>(name); }
        TextBlock Label(string name) { return Get<TextBlock>(name); }

        public MainWindow()
        {
            using (var stream = typeof(MainWindow).Assembly.GetManifestResourceStream("MainWindow.xaml"))
                Window = (Window)XamlReader.Load(stream);
            Field("ProjectPath").Text = HostPaths.FindProject();
            Field("BashPath").Text = HostPaths.FindBash();
            Field("Log").Text = "等待开始。\r\n\r\n构建日志会实时显示在这里。\r\n遇到错误时，可复制或导出日志进行排查。\r\n";
            Button("BrowseProject").Click += (s, e) => Browse();
            Button("StartButton").Click += async (s, e) => await Start(false);
            Button("CheckButton").Click += async (s, e) => await Start(true);
            Button("SaveVersion").Click += (s, e) => Guard(SaveVersion);
            Button("ReloadVersion").Click += (s, e) => ReadVersion();
            Field("VersionName").TextChanged += (s, e) => VersionChanged();
            Field("VersionCode").TextChanged += (s, e) => VersionChanged();
            Button("StopButton").Click += (s, e) => Stop();
            Button("CopyLog").Click += (s, e) => Guard(() => Clipboard.SetText(Field("Log").Text));
            Button("ExportLog").Click += (s, e) => Guard(Export);
            Button("OpenZip").Click += (s, e) => Reveal(zipPath);
            Button("OpenApk").Click += (s, e) => Reveal(apkPath);
            Button("OpenFolder").Click += (s, e) => Guard(() => {
                string path = Path.Combine(Field("ProjectPath").Text, "build");
                if (!Directory.Exists(path)) throw new IOException("尚未生成 build 目录，请先开始构建。");
                Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
            });
            Get<ComboBox>("Action").SelectionChanged += (s, e) => RefreshOptions();
            Get<ComboBox>("Variant").SelectionChanged += (s, e) => RefreshOptions();
            Get<ComboBox>("Target").SelectionChanged += (s, e) => RefreshOptions();
            Field("ProjectPath").LostFocus += (s, e) => {
                if (!String.Equals(Field("ProjectPath").Text.Trim(), loadedProject, StringComparison.OrdinalIgnoreCase)) ReadVersion();
            };
            Window.Closing += (s, e) => {
                if (runner == null) return;
                e.Cancel = true;
                if (MessageBox.Show(Window, "当前任务尚未结束。停止本次构建并关闭窗口？", "停止构建", MessageBoxButton.YesNo, MessageBoxImage.Question) == MessageBoxResult.Yes) {
                    closeWhenDone = true; Stop();
                }
            };
            timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(120) };
            timer.Tick += (s, e) => {
                FlushLog();
                Label("Elapsed").Text = stopwatch.Elapsed.ToString(@"hh\:mm\:ss");
            };
            Window.Closed += (s, e) => timer.Stop();
            timer.Start();
            ReadVersion();
            RefreshOptions();
        }

        BuildOptions Options(bool check)
        {
            return new BuildOptions {
                Project = Field("ProjectPath").Text.Trim(), Bash = Field("BashPath").Text.Trim().Trim('"'),
                JavaHome = Field("JavaPath").Text.Trim().Trim('"'), NdkHome = Field("NdkPath").Text.Trim().Trim('"'),
                Token = Get<PasswordBox>("Token").Password.Trim(),
                Variant = new[] { "release", "debug", "no" }[Math.Max(0, Get<ComboBox>("Variant").SelectedIndex)],
                Action = check ? "check" : new[] { "build", "preview", "publish" }[Math.Max(0, Get<ComboBox>("Action").SelectedIndex)],
                Target = Get<ComboBox>("Target").SelectedIndex == 1 ? "github" : "gitee",
                UpdateSubmodule = Get<CheckBox>("UpdateSubmodule").IsChecked == true,
                VersionName = check ? null : Field("VersionName").Text.Trim(),
                VersionCode = Field("VersionCode").Text.Trim(), ExpectedVersion = loadedVersion
            };
        }

        void RefreshOptions()
        {
            bool publish = Get<ComboBox>("Action").SelectedIndex > 0;
            Get<StackPanel>("PublishOptions").Visibility = publish ? Visibility.Visible : Visibility.Collapsed;
            Get<ComboBox>("Variant").IsEnabled = !publish;
            if (publish) Get<ComboBox>("Variant").SelectedIndex = 0;
            Button("StartButton").Content = Get<ComboBox>("Action").SelectedIndex == 2 ? "构建并发布…" : publish ? "构建并预演发布" : "开始构建";
            Label("CommandLabel").Text = Options(false).CommandPreview();
        }

        void ReadVersion()
        {
            readingVersion = true;
            try {
                loadedVersion = ProjectVersion.Read(Field("ProjectPath").Text.Trim());
                loadedProject = Field("ProjectPath").Text.Trim();
                Field("VersionName").Text = loadedVersion.Name;
                Field("VersionCode").Text = loadedVersion.Code.ToString();
                Label("VersionLabel").Text = loadedVersion.Name + "  /  " + loadedVersion.Code;
                Label("VersionNote").Text = "保存或开始构建时，同步 APK、模块、Rust 和更新配置。";
            } catch {
                loadedVersion = null; loadedProject = null;
                Field("VersionName").Text = ""; Field("VersionCode").Text = "";
                Label("VersionLabel").Text = "请选择项目";
                Label("VersionNote").Text = "无法读取版本，请检查项目目录后重新载入。";
            } finally { readingVersion = false; }
        }

        void VersionChanged()
        {
            if (readingVersion) return;
            Label("VersionNote").Text = "有待保存的版本修改 · 开始构建时也会自动保存。";
        }

        void SaveVersion()
        {
            if (loadedVersion == null || !String.Equals(Field("ProjectPath").Text.Trim(), loadedProject, StringComparison.OrdinalIgnoreCase))
                throw new IOException("请先选择项目并重新载入版本。");
            ProjectVersion.Save(loadedProject, Field("VersionName").Text.Trim(), Field("VersionCode").Text.Trim(), loadedVersion);
            ReadVersion();
            Label("VersionNote").Text = "已保存 · APK、模块、Rust、更新配置的版本已同步。";
        }

        async System.Threading.Tasks.Task Start(bool check)
        {
            if (runner != null) return;
            BuildOptions options;
            try {
                options = Options(check); options.Validate();
                if (options.Action == "publish" && MessageBox.Show(Window,
                    "项目：" + options.Project + "\n版本：" + options.VersionName + " / " + options.VersionCode +
                    "\n平台：" + options.Target + "\n\n将编译正式版、上传模块 ZIP，并推送该平台 modules-update 分支。\n地址和版本检查沿用项目脚本。确认开始？",
                    "确认构建并发布", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            } catch (Exception ex) { ShowError(ex); return; }
            zipPath = apkPath = null;
            Button("OpenZip").IsEnabled = Button("OpenApk").IsEnabled = false;
            Label("ArtifactNote").Text = check ? "环境检查不会生成构建产物。" : "正在构建；完成后显示本次生成的产物。";
            Field("Log").Clear();
            logPath = null;
            Label("CommandLabel").Text = options.CommandPreview();
            Label("StateLabel").Text = check ? "检查环境" : "正在构建";
            Label("StateLabel").Foreground = new SolidColorBrush(Color.FromRgb(37, 99, 235));
            Label("StageLabel").Text = "正在启动 Git Bash…";
            Get<StackPanel>("Configuration").IsEnabled = false;
            SetVersionEnabled(false);
            Button("StartButton").IsEnabled = Button("CheckButton").IsEnabled = false;
            Button("StopButton").IsEnabled = true;
            Get<ProgressBar>("Progress").Visibility = Visibility.Visible;
            Get<ProgressBar>("Progress").IsIndeterminate = true;
            stopwatch.Restart();
            runner = new BuildRunner { OnLine = QueueLine };
            try {
                var result = await runner.Run(options);
                logPath = result.LogPath;
                FlushLog();
                Label("StateLabel").Text = result.Cancelled ? "已停止" : result.ExitCode != 0 ? "任务失败" : check ? "环境检查通过" : options.Action == "publish" ? "发布完成" : options.Action == "preview" ? "发布预演完成" : "构建完成";
                Label("StateLabel").Foreground = new SolidColorBrush(result.ExitCode == 0 ? Color.FromRgb(37, 99, 235) : Color.FromRgb(166, 66, 36));
                Label("StageLabel").Text = result.Cancelled ? "已结束本次构建的进程树，可调整配置后重试。" : result.ExitCode != 0 ? "退出码 " + result.ExitCode + "，请查看下方日志；修正后可重新构建。" : check ? "工具检测已完成，具体版本见日志。编译结果仍以完整构建为准。" : "全部步骤已结束，用时 " + stopwatch.Elapsed.ToString(@"hh\:mm\:ss") + "。";
                if (!check) UpdateArtifacts(options, result.ModulePackaged, result.ExitCode == 0 && !result.Cancelled);
            } catch (Exception ex) {
                logPath = runner.LogPath;
                QueueLine("错误：" + new LogSanitizer(options.Token).Clean(ex.Message)); FlushLog();
                Label("StateLabel").Text = "无法完成任务";
                Label("StageLabel").Text = "请检查下方错误信息后重试。";
                Label("ArtifactNote").Text = "本次任务未完成。";
            } finally {
                stopwatch.Stop(); runner = null;
                if (!check) ReadVersion();
                Get<StackPanel>("Configuration").IsEnabled = true;
                SetVersionEnabled(true);
                Button("StartButton").IsEnabled = Button("CheckButton").IsEnabled = true;
                Button("StopButton").IsEnabled = false;
                Get<ProgressBar>("Progress").IsIndeterminate = false;
                Get<ProgressBar>("Progress").Visibility = Visibility.Collapsed;
                if (closeWhenDone) Window.Close();
            }
        }

        void UpdateArtifacts(BuildOptions options, bool packaged, bool success)
        {
            var zip = Path.Combine(options.Project, "build", "QixiaThreads.zip");
            var apk = Path.Combine(options.Project, "app", "build", "outputs", "apk", options.Variant, "app-" + options.Variant + ".apk");
            // ZIP 必须是本次生成；APK 可以由 Gradle 增量复用，但仅完整构建成功才确认它。
            bool newZip = packaged && File.Exists(zip);
            if (newZip) { zipPath = zip; Button("OpenZip").IsEnabled = true; }
            if (success && options.Variant != "no" && File.Exists(apk)) { apkPath = apk; Button("OpenApk").IsEnabled = true; }
            Label("ArtifactNote").Text = success && newZip ? "本次产物已就绪 · " + (new FileInfo(zip).Length / 1048576.0).ToString("0.0") + " MB" : newZip ? "ZIP 已生成，但后续任务未成功；请先检查日志。" : "本次未确认完整产物；旧文件不会标记为成功。";
        }

        void Stop()
        {
            if (runner == null) return;
            Guard(() => runner.Cancel());
            Button("StopButton").IsEnabled = false;
            Label("StageLabel").Text = "正在停止构建进程树…";
        }

        void SetVersionEnabled(bool enabled)
        {
            Field("VersionName").IsEnabled = Field("VersionCode").IsEnabled = enabled;
            Button("SaveVersion").IsEnabled = Button("ReloadVersion").IsEnabled = enabled;
        }

        void QueueLine(string line)
        {
            // 文件保留完整日志；界面限制待显示队列，避免编译器密集输出占满内存。
            if (System.Threading.Interlocked.Add(ref queuedChars, line.Length) > 400000) {
                System.Threading.Interlocked.Add(ref queuedChars, -line.Length); dropped = true; return;
            }
            pending.Enqueue(line);
        }

        void FlushLog()
        {
            var batch = new StringBuilder(); string line;
            while (batch.Length < 64000 && pending.TryDequeue(out line)) {
                System.Threading.Interlocked.Add(ref queuedChars, -line.Length);
                batch.AppendLine(line);
                if (runner != null && stopwatch.IsRunning && line.StartsWith("- ")) Label("StageLabel").Text = line.Substring(2);
            }
            if (batch.Length == 0) return;
            if (dropped) { batch.AppendLine("[界面已合并密集输出，完整内容请导出日志]"); dropped = false; }
            var box = Field("Log");
            if (box.Text.Length + batch.Length > 300000) box.Text = "[较早输出已收起，完整内容请导出日志]\r\n" + box.Text.Substring(Math.Max(0, box.Text.Length - 180000));
            box.AppendText(batch.ToString());
            if (Get<CheckBox>("AutoScroll").IsChecked == true) box.ScrollToEnd();
        }

        void Browse()
        {
            using (var dialog = new System.Windows.Forms.FolderBrowserDialog { Description = "选择包含 build_module.sh 的项目目录", SelectedPath = Field("ProjectPath").Text, ShowNewFolderButton = false }) {
                if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK) { Field("ProjectPath").Text = dialog.SelectedPath; ReadVersion(); }
            }
        }

        void Export()
        {
            var dialog = new Microsoft.Win32.SaveFileDialog { Filter = "日志文件 (*.log)|*.log", FileName = "Qixia-build-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".log" };
            if (dialog.ShowDialog(Window) != true) return;
            var currentLog = runner == null ? logPath : runner.LogPath;
            if (currentLog != null && File.Exists(currentLog)) {
                if (String.Equals(Path.GetFullPath(currentLog), Path.GetFullPath(dialog.FileName), StringComparison.OrdinalIgnoreCase)) return;
                // 写入中的日志用共享读取方式取得当前快照。
                using (var source = new FileStream(currentLog, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                using (var target = File.Create(dialog.FileName)) source.CopyTo(target);
            } else File.WriteAllText(dialog.FileName, Field("Log").Text, new UTF8Encoding(false));
        }

        void Reveal(string path)
        {
            Guard(() => {
                if (path == null || !File.Exists(path)) throw new IOException("产物不存在，请重新构建。");
                Process.Start(new ProcessStartInfo("explorer.exe", "/select," + HostPaths.Quote(path)) { UseShellExecute = true });
            });
        }
        void Guard(Action action) { try { action(); } catch (Exception ex) { ShowError(ex); } }
        void ShowError(Exception ex) { MessageBox.Show(Window, new LogSanitizer(Get<PasswordBox>("Token").Password).Clean(ex.Message), "柒夏构建中心", MessageBoxButton.OK, MessageBoxImage.Information); }

        public async System.Threading.Tasks.Task VerifyUi(string screenshot)
        {
            string sourceProject = Field("ProjectPath").Text;
            Field("ProjectPath").Text = SelfTests.VersionFixture(sourceProject);
            ReadVersion();
            Field("VersionName").Text = "v9.8.8";
            Field("VersionCode").Text = "988";
            SaveVersion();
            if (Field("VersionName").Text != "v9.8.8" || loadedVersion.Code != 988)
                throw new Exception("版本编辑、保存或重新读取失败。");
            Field("ProjectPath").Text = sourceProject;
            ReadVersion();
            Get<ComboBox>("Variant").SelectedIndex = 1;
            if (Options(false).Variant != "debug") throw new Exception("调试版选择失效。");
            Get<ComboBox>("Variant").SelectedIndex = 2;
            if (Options(false).Variant != "no") throw new Exception("仅模块选择失效。");
            Get<ComboBox>("Action").SelectedIndex = 1;
            if (Options(false).Variant != "release" || Get<ComboBox>("Variant").IsEnabled) throw new Exception("发布模式没有限制正式版。");
            Get<ComboBox>("Target").SelectedIndex = 1;
            if (Options(false).Target != "github" || Get<StackPanel>("PublishOptions").Visibility != Visibility.Visible) throw new Exception("发布平台选项异常。");
            Get<ComboBox>("Action").SelectedIndex = 0;
            Get<ComboBox>("Target").SelectedIndex = 0;
            if (!Get<ComboBox>("Variant").IsEnabled) throw new Exception("返回构建模式后未解锁构建类型。");
            await Start(true);
            if (Label("StateLabel").Text != "环境检查通过" || !Button("StartButton").IsEnabled || Button("StopButton").IsEnabled)
                throw new Exception("界面环境检查未通过，或按钮状态未恢复。");
            RenderPreview(screenshot, 1180, 860);
        }

        public void RenderPreview(string path, double width, double height)
        {
            Window.Width = width; Window.Height = height;
            Window.Show(); Window.UpdateLayout();
            var content = (FrameworkElement)Window.Content;
            var bounds = VisualTreeHelper.GetDescendantBounds(content);
            var target = new RenderTargetBitmap((int)Math.Ceiling(content.ActualWidth + 56), (int)Math.Ceiling(content.ActualHeight + 42), 96, 96, PixelFormats.Pbgra32);
            var visual = new DrawingVisual();
            using (var context = visual.RenderOpen()) {
                context.DrawRectangle(Window.Background, null, new Rect(0, 0, target.PixelWidth, target.PixelHeight));
                context.DrawRectangle(new VisualBrush(content), null, new Rect(28, 22, bounds.Width, bounds.Height));
            }
            target.Render(visual);
            var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(target));
            using (var output = File.Create(path)) encoder.Save(output);
            Window.Close();
        }
    }
}
