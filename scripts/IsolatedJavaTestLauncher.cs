using System;
using System.Diagnostics;
using System.IO;
using System.Text;

// 仅供 Windows 发行验收：真实启动器仍执行真实 Java，只在外部测试适配器固定临时 user.home。
// 不拦截协议、不读取凭证、不注入 Java agent；STDIO/退出码原样由真实 JVM 提供。
internal static class IsolatedJavaTestLauncher {
    private static int Main(string[] args) {
        string config = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "isolated-java.paths");
        string[] paths = File.Exists(config) ? File.ReadAllLines(config, Encoding.UTF8) : null;
        if (paths != null && paths.Length != 3) return 2;
        string java = paths == null ? Environment.GetEnvironmentVariable("CODEJ_TEST_JAVA") : paths[0];
        string home = paths == null ? Environment.GetEnvironmentVariable("CODEJ_TEST_HOME") : paths[1];
        if (String.IsNullOrEmpty(java) || String.IsNullOrEmpty(home)
            || !Path.IsPathRooted(java) || !Path.IsPathRooted(home)
            || !File.Exists(java) || !Directory.Exists(home)) return 2;
        string worker = paths == null ? Environment.GetEnvironmentVariable("CODEJ_TEST_PI_WORKER") : paths[2];
        if (!String.IsNullOrEmpty(worker) && (!Path.IsPathRooted(worker) || !File.Exists(worker))) return 2;
        var command = new StringBuilder(Quote("-Duser.home=" + home));
        foreach (string arg in args) {
            // 可选loopback测试入口仍加载包内操作/SDK；不修改发行物或普通协议。
            string value = !String.IsNullOrEmpty(worker) && arg.StartsWith("-Dcodej.piWorker=", StringComparison.Ordinal)
                ? "-Dcodej.piWorker=" + worker : arg;
            command.Append(' ').Append(Quote(value));
        }
        File.AppendAllText(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "isolated-java.invocations"), "started\n", Encoding.UTF8);
        bool piped = Console.IsInputRedirected || Console.IsOutputRedirected;
        using (var process = Process.Start(new ProcessStartInfo(java, command.ToString()) {
            UseShellExecute = false, CreateNoWindow = piped,
            RedirectStandardInput = piped, RedirectStandardOutput = piped, RedirectStandardError = piped,
            WorkingDirectory = Environment.CurrentDirectory
        })) {
            if (!piped) {process.WaitForExit(); return process.ExitCode;}
            // .NET子进程不能依赖父Node的重定向句柄自动继承；测试适配器仅按字节传输，不解码协议。
            System.Threading.Tasks.Task.Run(async () => {
                byte[] buffer = new byte[16384];
                try {
                    using (var source = Console.OpenStandardInput()) {
                        int count;
                        while ((count = await source.ReadAsync(buffer, 0, buffer.Length)) != 0) {
                            await process.StandardInput.BaseStream.WriteAsync(buffer, 0, count);
                            // NDJSON初始化不能等到stdin EOF才从适配器缓冲区交给真实JVM。
                            await process.StandardInput.BaseStream.FlushAsync();
                            Array.Clear(buffer, 0, count);
                        }
                    }
                    process.StandardInput.Close();
                } catch (IOException) { }
                finally {Array.Clear(buffer, 0, buffer.Length);}
            });
            var output = process.StandardOutput.BaseStream.CopyToAsync(Console.OpenStandardOutput());
            var error = process.StandardError.BaseStream.CopyToAsync(Console.OpenStandardError());
            process.WaitForExit();
            System.Threading.Tasks.Task.WaitAll(output, error);
            return process.ExitCode;
        }
    }
    // Windows argv 中，仅引号之前及尾部的反斜杠需要加倍；普通路径反斜杠不能全局替换。
    private static string Quote(string value) {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value) {
            if (c == '\\') {++slashes; continue;}
            result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes).Append(c);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }
}
