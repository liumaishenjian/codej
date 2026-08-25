/**
 * 实现 Session-local Task Board 的确定性状态机。
 *
 * <p>Core 负责 DAG、revision、claim、capability 与 actor/callId 幂等，不负责文件布局、JSON、
 * Tool Adapter、Permission 默认策略、stdio 或 TUI。持久化和 Surface 由后续 Adapter 批次接入。</p>
 *
 * @since 0.15.0
 */
package io.github.liumaishenjian.ccjava.core.task;
