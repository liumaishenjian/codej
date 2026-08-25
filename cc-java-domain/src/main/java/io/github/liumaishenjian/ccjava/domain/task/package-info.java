/**
 * 定义 Session-local Task Board 的框架无关协议和值对象。
 *
 * <p>本包只表达任务身份、三态、依赖、claim、capability、revision、资源上限和隐私安全诊断，
 * 不负责文件持久化、Tool 执行、Permission、stdio 或终端渲染。Task Board 与 PlanArtifact、
 * S12 子任务执行状态及未来 Team Board 保持独立。</p>
 *
 * @since 0.15.0
 */
package io.github.liumaishenjian.ccjava.domain.task;
