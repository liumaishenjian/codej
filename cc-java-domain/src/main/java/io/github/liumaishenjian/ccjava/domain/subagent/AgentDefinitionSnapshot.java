package io.github.liumaishenjian.ccjava.domain.subagent;

import io.github.liumaishenjian.ccjava.domain.PermissionMode;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Session 启动时冻结的严格 Agent definition。
 *
 * <p>Tool、Permission、模型和预算都是 ceiling；任何 Hook 或委托只能进一步收窄。
 * digest 只标识已验证内容，不表示来源文件仍未变化。</p>
 *
 * @param id 稳定定义 ID
 * @param description 有界展示说明
 * @param instructions 子 Session 的系统说明
 * @param visibleTools Tool allowlist
 * @param permissionCeiling 权限模式上界
 * @param modelOverride 显式模型覆盖；为空表示在委托边界继承已捕获的父模型，不在此选择账号
 * @param budget 子任务预算上界
 * @param backgroundDefault 默认后台行为
 * @param contentDigest 严格内容 SHA-256
 * @param sourceKind user 或 project
 * @since 0.12.0
 */
public record AgentDefinitionSnapshot(AgentDefinitionId id, String description, String instructions,
        Set<String> visibleTools, PermissionMode permissionCeiling, Optional<String> modelOverride, ChildBudget budget,
        boolean backgroundDefault, String contentDigest, String sourceKind) {
    /**
     * 保持旧字符串构造器为明确模型覆盖，绝不把旧配置解释成继承。
     * @param id 稳定定义ID
     * @param description 有界说明
     * @param instructions 子系统说明
     * @param visibleTools 工具上界
     * @param permissionCeiling 权限上界
     * @param modelName 明确模型名，不能为null或空白
     * @param budget 子预算
     * @param backgroundDefault 后台默认值
     * @param contentDigest 内容身份
     * @param sourceKind 来源类型
     */
    public AgentDefinitionSnapshot(AgentDefinitionId id, String description, String instructions,
            Set<String> visibleTools, PermissionMode permissionCeiling, String modelName, ChildBudget budget,
            boolean backgroundDefault, String contentDigest, String sourceKind) {
        this(id, description, instructions, visibleTools, permissionCeiling,
                Optional.of(bounded(modelName, 128, "modelName")), budget, backgroundDefault, contentDigest, sourceKind);
    }

    /** 校验全部 ceiling 与内容身份后冻结 Session snapshot。 */
    public AgentDefinitionSnapshot {
        id = Objects.requireNonNull(id, "id 不能为空");
        description = bounded(description, 512, "description");
        instructions = bounded(instructions, 32_768, "instructions");
        visibleTools = Set.copyOf(Objects.requireNonNull(visibleTools, "visibleTools 不能为空"));
        if (visibleTools.size() > 64 || visibleTools.stream().anyMatch(v -> !v.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,127}"))) {
            throw new IllegalArgumentException("visibleTools 无效或超过上限");
        }
        permissionCeiling = Objects.requireNonNull(permissionCeiling, "permissionCeiling 不能为空");
        modelOverride = Objects.requireNonNull(modelOverride, "modelOverride 不能为空").map(value -> {
            String model = bounded(value, 128, "modelName");
            if (!model.equals(model.strip()) || model.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("modelName 无效");
            }
            return model;
        });
        budget = Objects.requireNonNull(budget, "budget 不能为空");
        if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("digest 无效");
        if (!"user".equals(sourceKind) && !"project".equals(sourceKind)) throw new IllegalArgumentException("sourceKind 无效");
    }
    /**
     * 兼容仅处理显式覆盖的旧调用者；继承必须由可信捕获来源解析，不能输出假模型名。
     * @return 明确的模型覆盖
     * @throws IllegalStateException 定义要求继承且尚未在创建边界解析
     */
    public String modelName() {
        return modelOverride.orElseThrow(() -> new IllegalStateException("继承模型尚未绑定"));
    }

    private static String bounded(String value, int max, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > max) throw new IllegalArgumentException(field + " 无效");
        return value;
    }
}
