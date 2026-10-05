package io.github.liumaishenjian.ccjava.model.pi.gateway;

import io.github.liumaishenjian.ccjava.core.CancellationToken;

/**
 * 由组合根实现的单操作凭证会话工厂；身份、认证代次及存储均封装在可信闭包中。
 * <p>每次调用返回新会话；创建失败须自行释放部分资源。取消后迟到返回的会话仍由网关关闭。</p>
 */
@FunctionalInterface
public interface PiCredentialSessionFactory {
    /**
     * 为本次模型操作打开绑定身份的会话，不接受模型提供的身份切换参数。
     * @param operationId 宿主生成的新操作身份
     * @param cancellation 同时包含 Runtime 取消、网关关闭及总预算的取消端口
     * @return 由网关独占并确认关闭的非空会话
     */
    PiCredentialSession open(String operationId, CancellationToken cancellation);
}
