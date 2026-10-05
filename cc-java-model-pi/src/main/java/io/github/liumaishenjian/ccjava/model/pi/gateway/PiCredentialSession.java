package io.github.liumaishenjian.ccjava.model.pi.gateway;

import io.github.liumaishenjian.ccjava.model.pi.protocol.ProtocolFrame;
import tools.jackson.databind.node.ObjectNode;

/**
 * 单次模型操作的凭证事务端口，不负责存储、环境读取或身份选择。
 * <p>CLI 工厂绑定可信身份及 epoch；网关只转发 credential.request，关闭必须确认事务释放。
 * 不允许把秘密、底层异常或响应正文写入诊断。</p>
 */
public interface PiCredentialSession extends AutoCloseable {
    /**
     * 处理当前操作的凭证请求并返回私有 credential.response 数据。
     * @param frame 已校验操作身份与序号的 credential.request
     * @return 仅供私有连接发送的响应对象，不能记录
     */
    ObjectNode handle(ProtocolFrame frame);

    /**
     * 确认本会话及未结束事务关闭；失败必须显式抛出，不能假装已释放。
     * @throws Exception 无法确认凭证事务释放
     */
    @Override
    void close() throws Exception;
}
