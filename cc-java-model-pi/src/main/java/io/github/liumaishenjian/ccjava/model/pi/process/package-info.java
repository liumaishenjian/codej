/**
 * ADR-100 / S15 MODEL-13 的私有 Worker 进程适配器：可信安装配置、最小环境、
 * 有界双工 NDJSON、不可重置 deadline 与显式资源所有权。
 *
 * <p>协议结构复用相邻 protocol 包；取消仅依赖 Core 端口。这里没有 Provider 选择、
 * 凭证持久化、模型调用、重试或 Agent Loop；业务层须在终态和有效退出同时满足后接受产物。
 * 进程约束不属于 OS sandbox，不承诺跨进程注销或 JVM 字符串秘密物理擦除。</p>
 */
package io.github.liumaishenjian.ccjava.model.pi.process;
