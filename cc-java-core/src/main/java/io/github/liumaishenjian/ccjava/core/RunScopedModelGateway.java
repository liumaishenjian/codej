package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;

/**
 * 在 Agent Run 边界冻结底层模型 route 的 Gateway。
 *
 * <p>Headless Runtime 在任何模型调用前打开一次 scope，并在唯一 run 终态后关闭；实现可借此绑定
 * credential generation 与取消回调。该接口不允许在模型回合内部改选 Provider。</p>
 */
public interface RunScopedModelGateway extends ModelGateway {
    /**
     * 声明每次打开都提供显式绑定；只描述端口契约，不表示账号可用或Capability等级。
     * @return true时宿主必须拒绝缺失binding，不能回退静态端口；旧Fake默认false
     */
    default boolean providesRunBindings() { return false; }

    /**
     * 在 Run 开始前冻结 route并返回显式所有权句柄。
     *
     * @return 当前 Run 持有的 route 与 credential lease
     */
    RunScope openRun();

    /**
     * 在 Run 开始前冻结 route，并把 Provider request timeout 收窄到 Run 预算。
     *
     * <p>兼容实现可忽略该提示并走旧入口；会在 Run 边界创建 HTTP client 的 Provider 实现必须覆盖，
     * 使底层单请求上限不超过 Run 总预算。</p>
     *
     * @param maxDuration 当前 Run 的正墙钟预算
     * @return 当前 Run 持有的 route 与 credential lease
     */
    default RunScope openRun(Duration maxDuration) {
        java.util.Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        return openRun();
    }

    /**
     * 在启动阶段传播同一个墙钟取消边界，包括存储与凭据读取。
     * 默认入口只保持旧 Fake 源码兼容；生产适配器必须覆盖并传递取消，不以 HTTP 超时代替总预算。
     * @param maxDuration 正的请求预算提示
     * @param cancellation 含剩余墙钟预算的调用取消边界
     * @return 本次 Run 独占的模型作用域
     */
    default RunScope openRun(Duration maxDuration, CancellationToken cancellation) {
        java.util.Objects.requireNonNull(cancellation, "cancellation 不能为空");
        return openRun(maxDuration);
    }

    /**
     * 无 Run 总 deadline 的交互入口仍传播启动取消；不凭空增加交互超时。
     * @param cancellation 启动期取消边界
     * @return 本次模型作用域；旧 Fake 保持无预算入口的兼容行为
     */
    default RunScope openRun(CancellationToken cancellation) {
        java.util.Objects.requireNonNull(cancellation, "cancellation 不能为空");
        return openRun();
    }

    /** Run 持有的 route/credential lease。 */
    interface RunScope extends AutoCloseable {
        /**
         * 返回显式模型决策；empty 仅保留旧 Fake 源码兼容，不表示允许生产 fallback。
         * @return 本 scope 的固定模型、摘要及独立 child 来源
         */
        default java.util.Optional<RunModelBinding> binding() {
            return java.util.Optional.empty();
        }

        /**
         * 注册 logout fence 可调用的同进程 Run 取消动作。
         *
         * @param cancellation 需要取消当前 Run 时调用的动作
         */
        void bindCancellation(Runnable cancellation);
        /**
         * 查询本 scope 声明 owned 资源的清理证据；正常 close 返回不等于释放。
         * @return 独立清理状态；旧 Fake 无证据时为 UNKNOWN
         */
        default io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus cleanupStatus() {
            return io.github.liumaishenjian.ccjava.domain.ResourceCleanupStatus.UNKNOWN;
        }

        /** 请求幂等释放route；只有资源清理确认后才能发布lease terminal，失败须保留fence。 */
        @Override void close();
    }
}
