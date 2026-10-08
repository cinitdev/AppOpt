using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;

namespace Qixia.Builder
{
    internal static class Program
    {
        internal static readonly string Executable = typeof(Program).Assembly.Location;
        [STAThread]
        static int Main(string[] args)
        {
            // 图形 EXE 的后台工作模式也使用明确的 UTF-8 管道。
            if (args.Length > 0) {
                try {
                    Console.SetIn(new StreamReader(Console.OpenStandardInput(), Encoding.UTF8));
                    Console.SetOut(new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true });
                    Console.SetError(new StreamWriter(Console.OpenStandardError(), new UTF8Encoding(false)) { AutoFlush = true });
                    if (args[0] == "--worker") return BuildRunner.Worker();
                    if (args[0] == "--test-worker") return SelfTests.Worker();
                    if (args[0] == "--test-leaf") { System.Threading.Thread.Sleep(60000); return 0; }
                    if (args[0] == "--echo-args") {
                        for (int i = 1; i < args.Length; i++) Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(args[i])));
                        return 0;
                    }
                    if (args[0] == "--self-test") return SelfTests.Run();
                    if (args[0] == "--build" || args[0] == "--check") {
                        var options = new BuildOptions { Project = HostPaths.FindProject(), Bash = HostPaths.FindBash(),
                            Action = args[0] == "--check" ? "check" : "build",
                            Variant = args.Length > 1 ? args[1] : "release",
                            UpdateSubmodule = !Array.Exists(args, a => a == "--skip-submodule-update") };
                        if (options.Action == "build") {
                            options.ExpectedVersion = ProjectVersion.Read(options.Project);
                            options.VersionName = options.ExpectedVersion.Name;
                            options.VersionCode = options.ExpectedVersion.Code.ToString();
                        }
                        var result = new BuildRunner { OnLine = Console.WriteLine }.Run(options).GetAwaiter().GetResult();
                        return result.ExitCode;
                    }
                    if (args[0] != "--render-preview" && args[0] != "--ui-test") throw new ArgumentException("未知参数。");
                } catch (Exception ex) {
                    Console.Error.WriteLine(new LogSanitizer("").Clean(ex.ToString())); return 1;
                }
            }
            var app = new Application();
            app.DispatcherUnhandledException += (s, e) => {
                MessageBox.Show(new LogSanitizer("").Clean(e.Exception.Message), "柒夏构建中心", MessageBoxButton.OK, MessageBoxImage.Error);
                e.Handled = true;
            };
            var main = new MainWindow();
            if (args.Length > 0 && args[0] == "--ui-test") {
                app.ShutdownMode = ShutdownMode.OnExplicitShutdown;
                main.Window.Loaded += async (s, e) => {
                    try {
                        await main.VerifyUi(Path.Combine(HostPaths.FindProject(), "build", "builder-verified.png"));
                        Console.WriteLine("PASS: 版本编辑保存、界面选项联动、环境检查、按钮恢复、真实窗口渲染"); app.Shutdown(0);
                    } catch (Exception ex) { Console.Error.WriteLine(ex.ToString()); app.Shutdown(1); }
                };
            }
            if (args.Length > 0 && args[0] == "--render-preview") {
                string path = args.Length > 1 ? args[1] : Path.Combine(HostPaths.FindProject(), "build", "builder-preview.png");
                double width = args.Length > 2 ? Double.Parse(args[2], System.Globalization.CultureInfo.InvariantCulture) : 1180;
                double height = args.Length > 3 ? Double.Parse(args[3], System.Globalization.CultureInfo.InvariantCulture) : 860;
                main.RenderPreview(path, width, height); return 0;
            }
            return app.Run(main.Window);
        }
    }
}
