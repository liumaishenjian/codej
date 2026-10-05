# ADR-111：文件审批中的真实变更片段

Proposed；2026-09-12；S15，CLI-04/05 L2、TOOL-10 L1不变。

AUTH-SRC-2026-07-29-A（Observed）：FileWritePermissionRequest/FileWriteToolDiff → getPatchForDisplay/StructuredDiff；FileEditToolDiff → loadDiffData → StructuredDiffList。参考把写入前后内容交给受宽度约束的变更视图，审批决定与实际写入分离，内容无法读取时退化。

本项目采用独立的变更片段契约：apply_patch显示调用中的待替换片段及替换内容，write_file显示待创建内容；不读取磁盘、不伪造文件行号、不把片段称为整文件diff。红/绿区分移除/新增，已有分页保持审批决定可见。真实结果继续取tool.completed/failed，不把批准当作完成。

Java stdio已有experienceV1专用呈现协商；只向该能力开启片段字段，旧客户端保持路径与行数摘要。生产协调器默认关闭正文，初始化时显式开启。只处理BUILT_IN文件工具，复用SecretCandidatePolicy整体拒显明显凭证；含异常控制字符拒显，单侧超过6000 UTF-16单元则不发送正文并明确说明超过预览上限。检测不是完整敏感信息分类器。该片段只服务交互审批，不加入日志/Session，不改变模型参数、权限、WorkspaceGuard、提交前复检或取消逻辑。

验证：Java默认关闭/开启/秘密/控制字符/超长/非内置工具；生产stdio协议与旧客户端；TUI六尺寸、中文长行、零行删除、拒显、执行失败、拒绝取消和草稿；真实Provider创建/修改→审批→实际文件→最终回答。与参考不同：本批不提供文件上下文、语法高亮或精确行号diff；超过上限保持摘要，不能称为完整预览。无新依赖。
