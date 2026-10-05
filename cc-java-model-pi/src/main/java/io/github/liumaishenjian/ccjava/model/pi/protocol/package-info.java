/**
 * S15 / MODEL-13 的 Pi Worker v1 私有帧基础，按 ADR-100 独立设计。
 *
 * <p>严格 JSON 语法使用已有 Jackson 3；封套、UTF-8、资源预算及单方向序号为本项目契约。
 * 仅作为边缘适配器依赖 Domain/Core，不把 Jackson 类型带入框架无关模块。
 * 本批不实现 StreamingModelGateway、凭证事务、Worker 生命周期或业务路由，
 * 也不把通用 payload 当成业务授权。对照源为本仓库 Node worker-protocol.mjs，
 * 不复制第三方源码表达。</p>
 */
package io.github.liumaishenjian.ccjava.model.pi.protocol;
