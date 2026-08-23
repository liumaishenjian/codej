# S14 可安装 codej CLI corrective evidence

- Date: 2026-08-16
- Commit: working tree（不得解释为已发布 artifact）
- Stage: S14 corrective maintenance during S15
- Feature IDs: `BOOT-01`、`DIST-02`、`DIST-06`
- Capability Level: L2，no level change

## Windows 实际结果

```text
BuildRelease.ps1 installable app-dir: PASS
codej --version: codej 0.1.0
codej --help: PASS
codej doctor: files/runtime PASS
PackageDistribution.ps1 0.1.0 windows-x64 public release candidate: PASS
TestInstallDistribution.ps1: install/shim/version/doctor/tamper rejection/uninstall PASS
self-contained Windows ZIP: 195,857,096 bytes; external SHA-256 d04f9d459b4dc2fa1c818879c046f7f51044169d127b6785f6e3309c889bfdfd
system PATH without Java/Node + JAVA_HOME removed: bundled Node v24.14.0 / bundled Java 21 doctor and version PASS
Maven 0.1.0 verify: 1,012 tests / 32 skips / 0 failures / 0 errors
TUI 0.1.0 check: 11 files / 194 tests PASS
Apache-2.0 explicit public release manifest gate: PASS
```

构建出的产品入口包含 Java app-dir、编译 TUI、41 个 production npm package、manifest、
内部 checksum、CycloneDX SBOM、安装器与 launchers。安装测试仅在仓库 `target/release` 下的隔离根
执行，并使用 `SkipPathUpdate`，没有修改用户 PATH。最终候选还携带 jlink Java 21 runtime 与
Node executable；清除 `JAVA_HOME` 且 PATH 不含 Java/Node 后仍从安装目录启动。篡改 `.sha256`
后安装在解压/激活前失败。

## 首个远端 tag workflow

`v0.1.0` 的首个 GitHub Actions run `31927341615` 在创建 Release 前失败：Windows 与 Linux runner
均把多个 Node Application executable 拼成一个路径；Linux 还实际暴露 `BuildRelease.ps1` 固定调用
`mvnw.cmd`。因此该 run 不计 PASS，也没有公开 artifact。corrective 改为只选首个 Node executable、
按平台选择 Maven Wrapper，并把 Maven resolver 的 artifact path 从 Windows-only 扩展为 Unix 绝对路径；
corrective workflow `31927683009` 随后通过：GitHub-hosted Ubuntu 1m47s、Windows 3m29s，
两端均完成 bundled Java/Node、固定平台 archive 和 artifact 上传；手动 workflow 的 Release job
按设计跳过。运行仅留下 upload-artifact v4 的 Node 20 弃用警告，正式 workflow 已升级为官方
upload-artifact v7 / download-artifact v8，仍需随 tag 进行最终发布对账。

## 产品启动 Surface corrective

- Feature IDs：`BOOT-01`、`CLI-01`；Capability Level 无变化；
- 空 Session 且终端宽度不少于 52、可用高度不少于 16 时显示五行 `CODEJ` 字标、版本、
  `Java-powered coding agent` 与一句产品说明；
- 窄窗口或短窗口自动降级为紧凑 `codej` 标题，20 列回归不会裁掉产品名，Composer 始终保留；
- 常规终端使用青色、蓝色、洋红色 ANSI 层次区分字标与版本定位；首屏不额外堆叠 `/connect`、`/help` 或 `@file` 操作说明；
- 启动首屏不再暴露 `S15`、内部快速失败说明或无 Checkpoint 时的 Undo 操作提示；
- 品牌只存在于交互式空会话投影，不进入 `--print`、stdio 协议、Session 或模型 Prompt；
- `npm --prefix cc-java-tui run check`：11 files / 194 tests PASS。

## 公开发布对账（2026-08-23）

- 首次 `v0.1.1` tag run `32633807689` 因 workflow 在没有 build attestation 时传入
  `SkipBuild/SkipTuiBuild`，于 Linux `Package installable archive` 失败且没有创建 Release；
- 维护者明确授权重建尚无公开资产的 `v0.1.1` tag；corrective commit
  `aff16a0a33fceee02a3885a79a91c011ce8ebb6a` 移除无证明的 skip，并加入 workflow 回归断言；
- 重建后的 tag run `32634663859` 生成 Windows x64 185 MB 与 Linux x64 195 MB workflow
  artifacts，随后公开 ZIP、tar.gz、两个 checksum sidecar 和两个安装脚本；
- 从公开 Release 执行 Windows 隔离安装后，`codej --version` 报告 `0.1.1` 与 commit
  `aff16a0`，bundled Node 22、Java 21、files doctor 均为 PASS；隔离卸载后安装根与 shim 均不存在；
- `https://codej.sixmai.top/install.ps1` 与 `install.sh` 已返回真实脚本，公网长度与 SHA-256
  分别为 `6668 / 37a82a57...4888`、`3222 / b6c7e5c1...7938`，与仓库文件一致。

## 仍未计为通过

- 没有 Linux 公网安装生命周期、macOS、签名、撤销、透明日志或自动后台更新；
- 没有已发布 N-1 artifact 的真实升级/回滚证据。
