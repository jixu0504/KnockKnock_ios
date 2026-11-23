# 规格卡：iOS 设置「数据与备份」UI 接线（T38，「可用软件」里程碑 2）

> 模板：`ios-port/specs/规格卡模板-v1.md`。上游规格：`backup-restore-minimal-spec.md`（T10）、`chat-import-impl-spec.md`（T30）、
> `backup-full-restore-spec.md`（T31）、`ios-bridge-spec.md`（T29 门面纪律）、`message-history-bridge-spec.md`（T34 追加式协作模式）。
> 状态：**实现并验证（2026-11-23）**；实现记录见 `ios-port/handoffs/impl-backup-ui.md`。

## 1. 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | iOS 设置 →「数据与备份」子页 + 完整备份/角色卡/聊天记录 UI 接线 |
| 所属阶段 | Phase 4「可用软件」里程碑 2（T38） |
| 范围映射 | A5 修订：「完整备份/恢复、导入导出（含角色卡与聊天记录）」；计划书 Phase 3 Gate「备份恢复 UI 可操作」 |
| 管线来源 | T10 `BackupBridge`（DB 文件导出 + 两阶段恢复）、T30 `OperatorCard*` / `ChatExportReader` / `ChatMergeRestorer`、T31 全类目域层（本卡不直接调用，保留给后续在线恢复）、T17 `MediaStore` |
| 新增域层 | `shared/commonMain/.../backup/chatimport/ChatExportWriter.kt`（iOS 侧聊天导出 writer，T30 只交 Reader） |
| 新增桥 | `shared/iosMain/.../iosbridge/DataBackupBridge.kt`（角色卡/聊天记录导入导出桥 + DTO；`IosAppGraph.dataBackup()` 暴露） |
| 新增 Swift | `Features/Settings/BackupSettingsView.swift`、`Features/Settings/BackupAutomation.swift`、`Bridge/DataBackupClient.swift`、`Bridge/BackupSelfCheck.swift` |
| 接线（追加） | `AppRouter.swift`（`.settingsBackup` 路由 + `rhodes://settings/backup`）、`RouteView.swift`、`SettingsView.swift`（新行替换原占位行）、`AppGraphClient.swift`（`backup()` / `dataBackup()` 访问器） |

## 2. 输入 / 输出 / 边界

- **完整备份导出**
  - 输入：无（导出当前库）；输出：`Documents/BackupExports/rhodes-backup-<ts>.db` + `FullBackupFileInfo(size, sha256, SQLite 自检)`。
  - 流程：Swift 先 `PRAGMA wal_checkpoint(FULL)`（经 SQLite3 C API）→ `BackupBridge.exportToFile`（文件复制 + SQLite 头校验）→ 大小/SHA-256（CryptoKit）+ `PRAGMA integrity_check` + 关键表计数。
  - UI：显示文件大小 / SHA-256 前 24 hex / SQLite 头 / integrity / 表计数；`fileExporter` 让用户存到「文件」App（模拟器上由用户点击，自动化不依赖该弹窗）。
- **完整备份导入（两阶段恢复）**
  - 输入：DocumentPicker 选中文件（`.db`）；输出：`.restore-pending` 暂存 + 待恢复状态（pending 文件自检）。
  - 硬门禁（UI 侧 + 桥侧双保险）：SQLite 文件头无效 / `integrity_check != ok` → 拒绝进入确认弹窗；桥梁 `BackupBridge.restoreFromFile` 再次校验 SQLite 头。
  - **恢复前确认弹窗**：明确「覆盖当前全部数据」风险 + 文件自检明细 + 「暂存本身不修改当前数据、可撤销、需重启生效」。
  - 失败语义：暂存失败 → 不触碰现网 DB（文件级复制，只有 pending 可能残留，失败提示）；用户可「撤销待恢复」删除 pending。
- **恢复后自检**（T10 规格 #13 补强项的 UI 落地）
  - staging 时 `BackupSelfCheck.recordStagedRestore` 记录 pending 文件的 `integrity_check` + 关键表计数 + 现网计数；
  - 下次冷启动 `IosAppGraph.init → BackupBridge.applyPendingRestoreIfAny` 换入后，`BackupSettingsView.task` 调
    `evaluateRestoreSelfCheck()`：pending 已消失 → 以「换入时期望计数 vs 现网计数」比对，`passed` 与 mismatch 明细持久化（UserDefaults）并在「恢复后自检」卡展示。
  - 自检不翻转已完成的换入（与 T10/T31 口径一致，仅展示）。
- **角色卡导出/导入**
  - 导出：选择角色 → `OperatorCardExporter`（22 字段 + 关系 + prompt 槽位）→ `OperatorCardWriter`（rhodes-operator v1）→ zip 落 `Documents/BackupExports/`；内置头像数字串 1..147 从 Bundle `avatar_<N>.png` 读字节进包。
  - 导入：DocumentPicker → `OperatorCardReader.readCompatible`（v1 zip + 旧版单角色 JSON）→ 预览卡（角色/关系/头像）→ 确认 → `OperatorCardImporter`（UI 固定 `NEW` 模式：新 id + 名称去重后缀「（导入）」；关系仅在目标端角色存在时导入）。
- **聊天记录导出/导入**
  - 导出：选择会话 → 单会话快照（`getSession` + `getMessagesSync` + displayEvents + 存档 + 历史片段）→ `ChatExportWriter`（rhodes-chat v1；内容派生 mediaId）→ zip 落 `Documents/BackupExports/`；无消息会话明确拒绝。
  - 导入：DocumentPicker → `ChatExportReader.validate` 预览（消息/媒体/存档/告警）→ 确认 → `ChatMergeRestorer`（合并语义：会话匹配、内容哈希去重、消息 ID 重分配、单事务回滚）→ 结果展示（新增/去重/媒体/存档/告警）。
  - 重复导入同一包 = 幂等（`importedMessages=0`，`dedupedMessages=N`）。
- **错误路径（全部明确提示、不崩溃）**
  | 场景 | 行为 |
  |---|---|
  | 非 ZIP / 截断 ZIP / CRC 错 | `ChatExportReader`/`OperatorCardReader` 抛 `BackupFormatException` → 桥捕获为 `error` 文案 → UI 红色提示条「当前数据未受影响」 |
  | 跨格式（角色包当聊天包 / 聊天包当角色包 / 非 SQLite 当完整备份） | 各自门禁明确拒绝（「不支持的聊天导出类型」「不支持的角色包格式」「备份文件不是合法 SQLite」） |
  | 版本不匹配（format/formatVersion/scope/layout） | 读取器逐一拒绝并返回具体原因 |
  | 文件不存在 / 无权限 / 磁盘写失败 | 桥返回中文原因（如「角色卡写入失败（磁盘空间不足或没有写入权限）」）；导出失败不产生半成品（写入失败即报错） |
  | 恢复/导入中途失败 | 完整备份：暂存失败不动现网；聊天导入：单事务回滚 + 本次新增媒体清理，UI 提示「已回滚」 |

## 3. 行为契约（逐条）

1. **桥纪律**：`DataBackupBridge` 只导出普通 class DTO；回调在 Main 队列；错误经第二参数回传；所有 `Throwable`（含 `BackupFormatException`）转为中文 error，**绝不向 Swift 抛异常**。
2. **线程**：Kotlin 文件/DB 操作在 `workScope(Dispatchers.Default)`，回调 `mainScope(Dispatchers.Main)`；Swift 包装为 `async` + `Result`。
3. **full backup 导出一致性**：导出前 `wal_checkpoint(FULL)`（补强 T29 已知限制 4，两阶段恢复语义不变）；导出后必须通过 `SQLite format 3\0` 头 + `PRAGMA integrity_check=ok` 才在 UI 标记成功。
4. **两阶段恢复**：staging 只写 `rhodes.db.restore-pending`；换入仅在冷启动 `IosAppGraph` 打开 DB 之前；运行中不热换库。
5. **撤销**：`DataBackupBridge.cancelPendingRestore()` 删除 pending + 清 staging 标记；返回是否删除；当前 DB 从未被修改。
6. **恢复后自检**：`integrity_check` 必须为 `ok`；关键表（operators/chat_sessions/chat_messages/memories/relationships/chat_archives/chat_history_segments/knowledge_bases）计数与 staging 期望一致；pending 中不存在的表不计差异（兼容老库）。
7. **角色卡**：v1 容器（`manifest.json` + `data/operator.json` + 可选 `media/avatar.<ext>`，manifest 最后写，逐条目 size+SHA-256）；内置头像入包；导入默认 NEW 模式，不覆盖现有角色；关系跳过数量如实展示。
8. **聊天导出**：容器与 `chat-import-impl-spec.md` §A 完全一致（`format=rhodes-chat`/`formatVersion=1`/`scope=conversation`/`layout=single_payload`/`pages=[]`）；`mediaId="image/<sha256 全 64hex>"`、`archivePath="media/images/<sha24>.<ext>"`；消息按 `(timestamp,id)` 升序；manifest `recordCounts` 与内容一致（Reader 会强校验）。
9. **聊天导入**：合并语义完全复用 T30 `ChatMergeRestorer`（不新增语义）；UI 只展示结果。
10. **自动化验证钩子**：`--backup-selftest` 启动参数（用户路径零影响）执行两轮矩阵；`--open-url rhodes://settings/backup` 直达子页；日志落 `Documents/backup-selftest.log`，产物落 `Documents/BackupExports/`。

## 4. 数据与迁移

- 无 schema 变更 / 无迁移；角色卡与聊天包为只读导入格式（v1 冻结）。
- 涉及表（导入写）：`operators`、`relationships`（角色卡）；`chat_sessions`/`chat_messages`/`chat_display_events`/`chat_archives`/`chat_history_segments`/`operators`（聊天导入，单事务）。
- 媒体：角色卡头像 / 聊天图片落运行时媒体库（`Application Support/media/images/media/*`，内容寻址幂等）。
- 密钥/敏感设置：三种格式均不含 settings/密钥（`excludedCategories` 明确排除）。

## 5. 验收

| # | 验收项 | 判定方式 |
|---|---|---|
| 1 | xcodebuild 编译通过 | `xcodebuild build ... CODE_SIGNING_ALLOWED=NO` → BUILD SUCCEEDED |
| 2 | 设置页可进入「数据与备份」子页 | `--open-url rhodes://settings/backup` 后截图 |
| 3 | 完整备份导出且格式校验通过 | 容器内文件存在；`sqlite3` 头 + `PRAGMA integrity_check` + 表计数（`simctl get_app_container` 取出验证）+ 截图显示大小/SHA |
| 4 | 完整备份两阶段恢复 + 撤销 + 换入 | 暂存后 pending 存在；撤销后 pending 消失；再暂存后冷启动换入（pending 消失、DB 自检报告 passed） |
| 5 | 角色卡导出/导入 | zip 结构校验（`unzip -l`：manifest/data/media 三件）；导入后角色数 +1、关系导入计数 |
| 6 | 聊天记录导出/导入 | zip 结构校验（manifest/conversation/media）+ Kotlin Reader 校验通过；重复导入 `importedMessages=0`（幂等） |
| 7 | 坏包/跨格式/截断/不存在文件错误提示 | 自动化矩阵逐项 PASS（每项有明确中文错误）且 App 不崩 |
| 8 | 恢复后自检结果展示 | 冷启动后页面展示 passed + 期望/实际计数 + integrity |
| 9 | 截图证据 | `ios-port/handoffs/evidence/t38-*.png` |

## 6. 已知限制

1. 完整备份导出为**文件快照**（WAL checkpoint + 文件复制），不是 Android 的 24 步在线导出；Android `.rbackup`（zip）导入本卡未接 UI——
   域层 `BackupFullRestoreCoordinator` 已就绪（T31），在线恢复接线归 Phase 7（T31 handoff 遗留 6/7）。
2. 恢复后自检比较的是「表计数 + integrity」，不是 T31 的全类目字段级自检（后者在域层，在线恢复接线时替换）。
3. 聊天导出媒体引用保留原值（不重写 content；镜像 Android `ChatExportService` 语义）；导入时按 `originalUri` 重写为运行时媒体 ID。
4. 角色卡导入 UI 固定 `NEW`（不提供 PERSONA/FULL_REPLACE 模式选择；桥已支持，后续按需开放）。
5. `fileExporter` 存「文件」App 需用户交互；自动化验证以 App 沙盒 `Documents/BackupExports/` 产物为准（同一字节源）。
