package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.PermissionSelector;
import io.github.liumaishenjian.ccjava.domain.ToolDefinition;
import io.github.liumaishenjian.ccjava.domain.ToolEffect;
import io.github.liumaishenjian.ccjava.domain.ToolSource;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * S05 默认 Hard Denial 策略。
 *
 * <p>System Effect 永久拒绝；Network Effect 默认也永久拒绝，仅允许已经由 Composition Root
 * 标记为 {@link ToolSource#MCP}、{@link ToolSource#PLUGIN} 的可信适配器，以及名称精确为
 * {@code web_search}、Source 为 {@link ToolSource#BUILT_IN} 的宿主内置搜索 Tool，继续进入后续
 * ASK/Rule 决策。Session state effect 也只允许名称/effect 精确匹配 task_create/update/list/get 的
 * BUILT_IN 定义；Plugin/MCP 或其他同名/同 effect 定义永久拒绝。该窄例外不适用于前缀匹配。
 * 文件写入 selector 必须具体且不得命中 Git 元数据、
 * Provider 本地配置或常见 Secret 文件。绝对路径、Traversal 与不可解释范围会被
 * selector resolver 收敛为 Tool-wide，并在写入/命令范围上拒绝。</p>
 *
 * @since 0.5.0
 */
public final class DefaultHardDenialPolicy implements HardDenialPolicy {

    private static final List<String> SECRET_NAMES = List.of(
            ".env", ".npmrc", ".pypirc", ".netrc", "id_rsa", "id_ed25519");

    private final Predicate<PermissionSelector> workspaceDenials;

    /** 创建只使用词法保护路径的 S05 固定安全策略。 */
    public DefaultHardDenialPolicy() {
        this(selector -> false);
    }

    /**
     * 创建可叠加 Workspace realpath/Junction/Symlink 预检的安全策略。
     *
     * <p>Predicate 只接收不含正文的规范化 selector；抛出异常时按拒绝处理。Composition
     * Root 可用 WorkspaceGuard 实现该谓词，Core 不因此依赖文件系统 Adapter。</p>
     *
     * @param workspaceDenials 额外 Workspace 写入拒绝条件
     */
    public DefaultHardDenialPolicy(Predicate<PermissionSelector> workspaceDenials) {
        this.workspaceDenials = Objects.requireNonNull(
                workspaceDenials, "workspaceDenials 不能为空");
    }

    @Override
    public boolean denies(
            ToolInvocation invocation,
            ToolDefinition definition,
            PermissionSelector selector) {
        Objects.requireNonNull(invocation, "invocation 不能为空");
        Objects.requireNonNull(definition, "definition 不能为空");
        Objects.requireNonNull(selector, "selector 不能为空");
        ToolEffect effect = definition.effect();
        boolean controlledBuiltinWebSearch = effect == ToolEffect.NETWORK_OR_REMOTE
                && definition.source() == ToolSource.BUILT_IN
                && "web_search".equals(definition.name());
        boolean controlledBuiltinTask = definition.source() == ToolSource.BUILT_IN
                && ((effect == ToolEffect.READ_SESSION_STATE
                        && ("task_list".equals(definition.name()) || "task_get".equals(definition.name())))
                    || (effect == ToolEffect.WRITE_SESSION_STATE
                        && ("task_create".equals(definition.name()) || "task_update".equals(definition.name()))));
        boolean controlledBuiltinDelegate = effect == ToolEffect.SYSTEM_OR_DESTRUCTIVE
                && definition.source() == ToolSource.BUILT_IN
                && "delegate_agent".equals(definition.name());
        if ((effect == ToolEffect.SYSTEM_OR_DESTRUCTIVE && !controlledBuiltinDelegate)
                || ((effect == ToolEffect.READ_SESSION_STATE || effect == ToolEffect.WRITE_SESSION_STATE)
                    && !controlledBuiltinTask)
                || (effect == ToolEffect.NETWORK_OR_REMOTE
                    && definition.source() != ToolSource.MCP
                    && definition.source() != ToolSource.PLUGIN
                    && !controlledBuiltinWebSearch)) {
            return true;
        }
        if (effect != ToolEffect.WRITE_WORKSPACE) {
            return false;
        }
        if (selector.toolWide()) {
            return true;
        }
        try {
            if (workspaceDenials.test(selector)) {
                return true;
            }
        } catch (RuntimeException exception) {
            return true;
        }
        String path = selector.value().replace('\\', '/').toLowerCase(Locale.ROOT);
        if (path.equals(".git") || path.startsWith(".git/")) {
            return true;
        }
        if (path.equals("config/provider.local.properties")
                || path.startsWith("config/provider.local.properties/")) {
            return true;
        }
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        return SECRET_NAMES.stream().anyMatch(secret ->
                fileName.equals(secret) || fileName.startsWith(secret + "."));
    }
}
