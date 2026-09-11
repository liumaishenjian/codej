package io.github.liumaishenjian.ccjava.tools.local.command;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S04 固定的平台 Shell Adapter。
 *
 * <p>Windows 优先使用安装目录明确的 PowerShell 7，缺失时退回系统 Windows
 * PowerShell；其他平台固定使用 {@code /bin/sh}。模型不能选择可执行文件或启动参数，
 * 审批展示的命令正文会作为一个独立参数原样交给固定 Shell。</p>
 *
 * @since 0.4.0
 */
public enum CommandShell {

    /** Windows PowerShell 系列。 */
    WINDOWS_POWERSHELL("powershell", windowsExecutable()),

    /** POSIX /bin/sh。 */
    POSIX_SH("sh", Path.of("/bin/sh"), StandardCharsets.UTF_8);

    private static final String POWERSHELL_BODY_ENVIRONMENT = "CODEJ_PS_BODY";
    private static final String POWERSHELL_LAUNCHER = """
            $utf8=[Text.UTF8Encoding]::new($false)
            [Console]::OutputEncoding=$utf8
            $OutputEncoding=$utf8
            $body=[Environment]::GetEnvironmentVariable('CODEJ_PS_BODY','Process')
            [Environment]::SetEnvironmentVariable('CODEJ_PS_BODY',$null,'Process')
            Remove-Item Env:CODEJ_PS_BODY -ErrorAction SilentlyContinue
            $script=[ScriptBlock]::Create($body+"`nif (-not `$?) { exit 1 }")
            $body=$null
            . $script
            """;

    private final String id;
    private final Path executable;
    private final Charset outputCharset;

    CommandShell(String id, Path executable) {
        this(id, executable, windowsCharset(executable));
    }

    CommandShell(String id, Path executable, Charset outputCharset) {
        this.id = id;
        this.executable = executable;
        this.outputCharset = outputCharset;
    }

    /**
     * 返回当前操作系统固定的 Shell。
     *
     * @return Windows PowerShell 或 POSIX sh
     */
    public static CommandShell current() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win")
                ? WINDOWS_POWERSHELL : POSIX_SH;
    }

    /**
     * 返回可安全进入审批摘要的稳定 Shell ID。
     *
     * @return {@code powershell} 或 {@code sh}
     */
    public String id() {
        return id;
    }

    /**
     * 返回固定 Shell 输出使用的字符集。
     *
     * @return 输出解码字符集
     */
    public Charset outputCharset() {
        return outputCharset;
    }

    /**
     * 构造不经过第二层 Shell 字符串拼接的进程启动事实。
     *
     * <p>Windows 只把固定 launcher 作为 UTF-16LE {@code -EncodedCommand} 参数；已批准正文
     * 通过本次子进程专用环境值传入，launcher 在创建 ScriptBlock 前立即从 Process 环境和
     * PowerShell Env Drive 删除它。这样 argv 长度不随 supplementary Unicode 正文膨胀，正文
     * 启动的孙进程也不会继承原文。launcher 同时固定无 BOM UTF-8 输出和 Text 错误格式，并在
     * 正文未显式 {@code exit} 时按正文最终 {@code $?} 收敛失败。POSIX 仍将正文作为
     * {@code sh -c} 的单个参数。</p>
     *
     * @param command 已批准的完整命令正文
     * @param environment 已由执行策略裁剪的最小环境
     * @return 绑定固定 argv 与本次环境的启动事实
     */
    public ProcessInvocation processInvocation(
            String command,
            Map<String, String> environment) {
        ArrayList<String> arguments = new ArrayList<>();
        LinkedHashMap<String, String> processEnvironment =
                new LinkedHashMap<>(environment);
        arguments.add(executable.toString());
        if (this == WINDOWS_POWERSHELL) {
            arguments.add("-NoLogo");
            arguments.add("-NoProfile");
            arguments.add("-NonInteractive");
            arguments.add("-ExecutionPolicy");
            arguments.add("Bypass");
            arguments.add("-OutputFormat");
            arguments.add("Text");
            arguments.add("-EncodedCommand");
            arguments.add(Base64.getEncoder().encodeToString(
                    POWERSHELL_LAUNCHER.getBytes(StandardCharsets.UTF_16LE)));
            processEnvironment.put(POWERSHELL_BODY_ENVIRONMENT, command);
        } else {
            arguments.add("-c");
            arguments.add(command);
        }
        return new ProcessInvocation(arguments, processEnvironment);
    }

    /**
     * 由固定 Shell argv 与单次最小环境组成的不可变启动事实。
     *
     * @param arguments 固定可执行文件与 launcher 参数；不得包含 PowerShell 用户正文
     * @param environment 单次最小进程环境；Windows payload 值属于敏感内部传输
     */
    public record ProcessInvocation(
            List<String> arguments,
            Map<String, String> environment) {
        public ProcessInvocation {
            arguments = List.copyOf(arguments);
            environment = Map.copyOf(environment);
        }

        /** 默认诊断只显示规模，绝不展开可能包含完整命令正文的环境值。 */
        @Override
        public String toString() {
            return "ProcessInvocation[argumentCount=" + arguments.size()
                    + ", environmentEntryCount=" + environment.size() + "]";
        }
    }

    private static Path windowsExecutable() {
        Path executable = findPowerShell7(
                System.getenv("ProgramFiles"),
                System.getenv("LOCALAPPDATA"));
        if (executable != null) {
            return executable;
        }
        String systemRoot = System.getenv("SystemRoot");
        Path root = systemRoot == null ? Path.of("C:\\Windows") : Path.of(systemRoot);
        return root.resolve("System32")
                .resolve("WindowsPowerShell")
                .resolve("v1.0")
                .resolve("powershell.exe");
    }

    /**
     * 按机器级、用户级标准安装目录查找 PowerShell 7。
     *
     * <p>Windows Store/用户级安装常位于 {@code LOCALAPPDATA}。如果只检查
     * {@code ProgramFiles}，会在 PowerShell 7 已可用时错误回退到 Windows PowerShell，
     * 而企业策略可能禁止后者作为 Java 子进程启动。</p>
     *
     * @param programFiles 机器级 Program Files，可为空
     * @param localAppData 用户级 LocalAppData，可为空
     * @return 第一个存在的固定 {@code pwsh.exe}，不存在时为 {@code null}
     */
    static Path findPowerShell7(String programFiles, String localAppData) {
        Path[] candidates = {
            candidate(programFiles, "PowerShell", "7", "pwsh.exe"),
            candidate(localAppData, "Programs", "PowerShell", "7", "pwsh.exe")
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path candidate(String root, String... segments) {
        if (root == null || root.isBlank()) {
            return null;
        }
        return Path.of(root, segments);
    }

    private static Charset windowsCharset(Path executable) {
        // 固定 launcher 对 PowerShell 7 与 Windows PowerShell 使用同一无 BOM UTF-8 契约。
        return StandardCharsets.UTF_8;
    }
}
