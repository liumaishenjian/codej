package io.github.liumaishenjian.ccjava.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 可在排队前捕获、在任意工作线程打开独立 Run 的非秘密模型来源。
 *
 * <p>配置身份的捕获不取得子租约、不启动进程，也不持有父 Run 活动网关。来源不是授权绕过：
 * 打开时重新验证捕获的认证版本及 fence，不能采用最新账号代替原身份。
 * 认证版本仅留在边缘闭包，不进入 Core。父 scope 关闭不撤销来源，注销或重新登录则可使其失效。</p>
 * <p>旧启动兼容源可引用外部管理的legacy端口，但不能用于Pi或扩展未知模型；此时独立scope不表示旧client物理独占。</p>
 */
@FunctionalInterface
public interface CapturedRunSource {
    /**
     * 在入队前校验模型覆盖并返回已固定目标的来源，不取得租约或读取材料。
     * <p>默认兼容实现只支持继承；生产来源应在同provider目录内验证明确覆盖。</p>
     * @param modelOverride 可选模型覆盖，空表示保留原目标
     * @return 后续以空覆盖打开的冻结来源
     * @throws IllegalArgumentException 兼容来源无法验证明确覆盖时
     */
    default CapturedRunSource forModel(Optional<String> modelOverride) {
        if (Objects.requireNonNull(modelOverride).isPresent()) {
            throw new IllegalArgumentException("Captured source model override unavailable");
        }
        return this;
    }

    /**
     * 打开独立作用域；调用方负责关闭，不经过 Root 的选择互斥门。
     * @param modelOverride 空表示继承；有值只允许原 provider 的合法模型
     * @param budget 本次打开及运行的正墙钟预算
     * @return 新作用域；失败不得改选身份或回退
     */
    RunScopedModelGateway.RunScope open(Optional<String> modelOverride, Duration budget);

    /**
     * 传播启动等待取消；生产来源必须覆盖并将同一 token 传给元数据/凭证读取。
     * @param modelOverride 可选同 provider 模型覆盖
     * @param budget 正墙钟预算
     * @param cancellation 启动及等待取消信号
     * @return 独立作用域；若打开期间取消，先关闭再拒绝返回
     */
    default RunScopedModelGateway.RunScope open(Optional<String> modelOverride, Duration budget,
            CancellationToken cancellation) {
        Objects.requireNonNull(cancellation);
        if (cancellation.isCancellationRequested()) throw new IllegalStateException("Run source cancelled");
        var scope = open(modelOverride, budget);
        if (cancellation.isCancellationRequested()) {
            scope.close();
            throw new IllegalStateException("Run source cancelled");
        }
        return scope;
    }
}
