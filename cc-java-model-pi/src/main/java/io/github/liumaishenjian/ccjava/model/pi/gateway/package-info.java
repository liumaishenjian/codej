/**
 * ADR-100 / S15 / MODEL-01/04/05/06/08/10/13 的单回合 Pi 模型边缘。
 *
 * <p>只把 Domain 消息映射到私有 Worker 协议并聚合合法回合，依赖方向为 Domain/Core
 * 到 Pi process/protocol 适配器；不依赖 CLI、Spring AI、Pi Agent 或凭证持久化。
 * Java Runtime 保持循环、工具执行、权限、重试及终态权威；凭证身份由组合根工厂闭包绑定。</p>
 *
 * <p>源码对照：Pi0.85.1 公开 SDK Models.streamSimple、types.d.ts Usage，以及
 * api/openai-responses-shared.js/openai-completions.js 的缓存分项（源码 Observed，非在线证据）。
 * 保留项目 SpringAiPromptMapper 的不可信 Base64 User 信封契约，独立序列化且不引入 Spring AI。
 * 请求模型 ID 不代表实际响应模型，providerModel 保持未知；只合成同口径 token 分项，不宣称价格。</p>
 */
package io.github.liumaishenjian.ccjava.model.pi.gateway;
