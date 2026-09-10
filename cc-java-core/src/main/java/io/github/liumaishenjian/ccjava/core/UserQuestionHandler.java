package io.github.liumaishenjian.ccjava.core;

import io.github.liumaishenjian.ccjava.domain.UserQuestionAnswer;
import io.github.liumaishenjian.ccjava.domain.UserQuestionRequest;

/**
 * 把同步 Agent Tool 调用桥接到结构化用户交互 Surface。
 *
 * <p>实现必须按 callId 关联，并在取消、连接关闭或非法答案时失败关闭；不得退化为 y/n
 * 字符串解析或把模型 Tool JSON 直接输出到终端。</p>
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface UserQuestionHandler {
    /** 发布问题并等待同一 callId 的单题或整批答案；取消时不得伪造答案。 */
    UserQuestionAnswer ask(UserQuestionRequest request, CancellationToken cancellationToken);

    /** 返回不支持交互的失败关闭实现。 */
    static UserQuestionHandler unavailable() {
        return (request, cancellationToken) -> {
            throw new IllegalStateException("结构化用户问题 Surface 不可用");
        };
    }
}
