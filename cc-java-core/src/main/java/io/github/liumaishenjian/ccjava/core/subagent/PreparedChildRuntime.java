package io.github.liumaishenjian.ccjava.core.subagent;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import io.github.liumaishenjian.ccjava.domain.subagent.ChildTaskRequest;

/**
 * 提交线程已捕获可信来源、但尚未取得运行资源的子 Runtime 准备结果。
 *
 * <p>只在 worker 启动时创建独立 scope；不得重新解析父 Run 的当前模型或凭证身份。
 * 有效请求仅允许附加 Start Hook 的不可信 prompt，其他身份、权限和预算使用捕获值。</p>
 * @since 0.15.0
 */
@FunctionalInterface
public interface PreparedChildRuntime {
    /**
     * 使用已捕获来源创建由调用方关闭的独立 scope。
     * @param effectiveRequest 仅 prompt 可附加 Hook 上下文的请求
     * @param token 当前子任务的取消信号
     * @return 独占运行资源及初始化接缝
     */
    ChildRuntimeScope create(ChildTaskRequest effectiveRequest, CancellationToken token);
}
