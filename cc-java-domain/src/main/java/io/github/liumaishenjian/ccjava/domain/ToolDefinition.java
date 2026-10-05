package io.github.liumaishenjian.ccjava.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 提供给模型和 Runtime 的框架无关 Tool 元数据。
 *
 * <p>{@code inputSchemaJson} 保存 JSON Schema 文本，但 S01 不内置完整
 * Schema 引擎；具体 Tool 仍必须在执行前完成确定性参数校验。超时和输出上限
 * 在本阶段只是契约元数据，分别由 S04 和 S03/S07 落实执行策略。</p>
 *
 * @param name                   稳定且唯一的 Tool 名称
 * @param description            面向模型的清晰用途说明
 * @param inputSchemaJson        Tool 输入的 JSON Schema 文本
 * @param effect                 最高副作用等级
 * @param source                 Tool 注册来源
 * @param supportsCancellation   实现是否声明支持取消
 * @param defaultTimeout         建议的默认执行超时
 * @param outputMediaType        Tool 输出内容类型
 * @param maxOutputCharacters    建议的最大输出字符数
 * @param planCapabilities       可信注册边缘显式声明的规划能力
 * @since 0.1.0
 */
public record ToolDefinition(
        String name,
        String description,
        String inputSchemaJson,
        ToolEffect effect,
        ToolSource source,
        boolean supportsCancellation,
        Duration defaultTimeout,
        String outputMediaType,
        int maxOutputCharacters,
        Set<PlanToolCapability> planCapabilities) {

    /**
     * 校验 Tool 元数据和资源边界后创建定义。
     *
     * @param name 稳定且唯一的 Tool 名称
     * @param description 面向模型的用途说明
     * @param inputSchemaJson Tool 输入的 JSON Schema 文本
     * @param effect 最高副作用等级
     * @param source Tool 注册来源
     * @param supportsCancellation 是否支持取消
     * @param defaultTimeout 建议的默认执行超时
     * @param outputMediaType Tool 输出内容类型
     * @param maxOutputCharacters 建议的最大输出字符数
     * @throws NullPointerException 必填引用为空时
     * @throws IllegalArgumentException 文本为空白、超时非正数或输出上限小于 1 时
     */
    public ToolDefinition {
        name = requireText(name, "name");
        description = requireText(description, "description");
        inputSchemaJson = requireText(inputSchemaJson, "inputSchemaJson");
        effect = Objects.requireNonNull(effect, "effect 不能为空");
        source = Objects.requireNonNull(source, "source 不能为空");
        defaultTimeout = Objects.requireNonNull(defaultTimeout, "defaultTimeout 不能为空");
        outputMediaType = requireText(outputMediaType, "outputMediaType");
        planCapabilities = Set.copyOf(Objects.requireNonNull(planCapabilities, "planCapabilities 不能为空"));
        if (defaultTimeout.isZero() || defaultTimeout.isNegative()) {
            throw new IllegalArgumentException("defaultTimeout 必须大于 0");
        }
        if (maxOutputCharacters < 1) {
            throw new IllegalArgumentException("maxOutputCharacters 必须大于 0");
        }
        if (planCapabilities.contains(PlanToolCapability.READ_ONLY_LOCAL)
                && effect != ToolEffect.READ_WORKSPACE) {
            throw new IllegalArgumentException("READ_ONLY_LOCAL 必须使用 READ_WORKSPACE Effect");
        }
        if (planCapabilities.contains(PlanToolCapability.READ_ONLY_NETWORK)
                && effect != ToolEffect.NETWORK_OR_REMOTE) {
            throw new IllegalArgumentException("READ_ONLY_NETWORK 必须使用 NETWORK_OR_REMOTE Effect");
        }
        if (planCapabilities.contains(PlanToolCapability.PLAN_ARTIFACT_WRITE)
                && effect != ToolEffect.PLAN_ARTIFACT_WRITE) {
            throw new IllegalArgumentException("PLAN_ARTIFACT_WRITE 能力与 Effect 不匹配");
        }
        if (planCapabilities.contains(PlanToolCapability.USER_QUESTION)
                && effect != ToolEffect.USER_INTERACTION) {
            throw new IllegalArgumentException("USER_QUESTION 能力与 Effect 不匹配");
        }
        if ((planCapabilities.contains(PlanToolCapability.PLAN_ARTIFACT_WRITE)
                || planCapabilities.contains(PlanToolCapability.USER_QUESTION))
                && source != ToolSource.BUILT_IN) {
            throw new IllegalArgumentException("Plan 控制能力只能由内置 Tool 声明");
        }
    }

    /**
     * 兼容既有 Tool 定义；仅内置 Workspace Read 自动获得本地只读规划能力。
     *
     * <p>外部、MCP、Plugin 与其他 Effect 默认不声明规划能力，必须使用完整构造器显式选择。</p>
     * @param name 注册表唯一工具名
     * @param description 面向模型的工具说明，不作为访问控制
     * @param inputSchemaJson 参数 JSON Schema
     * @param effect 由可信适配器声明的操作效果
     * @param source 绑定权限规则的可信来源
     * @param supportsCancellation 是否支持协作式取消
     * @param defaultTimeout 工具执行的默认超时预算
     * @param outputMediaType 结果媒体类型
     * @param maxOutputCharacters 结果字符上限
     */
    public ToolDefinition(
            String name,
            String description,
            String inputSchemaJson,
            ToolEffect effect,
            ToolSource source,
            boolean supportsCancellation,
            Duration defaultTimeout,
            String outputMediaType,
            int maxOutputCharacters) {
        this(name, description, inputSchemaJson, effect, source, supportsCancellation,
                defaultTimeout, outputMediaType, maxOutputCharacters,
                effect == ToolEffect.READ_WORKSPACE && source == ToolSource.BUILT_IN
                        ? Set.of(PlanToolCapability.READ_ONLY_LOCAL) : Set.of());
    }

    /**
     * 创建适合 S01 Fake Tool 的只读文本定义。
     *
     * @param name            Tool 名称
     * @param description     Tool 说明
     * @param inputSchemaJson 输入 Schema
     * @return 具有保守元数据默认值的定义
     */
    public static ToolDefinition readOnlyText(
            String name,
            String description,
            String inputSchemaJson) {
        return new ToolDefinition(
                name,
                description,
                inputSchemaJson,
                ToolEffect.READ_WORKSPACE,
                ToolSource.BUILT_IN,
                false,
                Duration.ofSeconds(30),
                "text/plain",
                16_384);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " 不能为空");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空白");
        }
        return value;
    }
}
