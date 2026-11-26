# handoff：impl-a5-import（T30 A5 聊天导入导出 + 角色卡 + 备份恢复补齐）

> 代理：impl-a5-import ｜ 任务：task-30 ｜ 状态：**完成**（双路径测试 + app 回归 + Android 构建全绿）
> 规格卡：`ios-port/specs/chat-import-impl-spec.md`（reviewer PASS-WITH-FIXES + lead 批准 2026-11-23，三点裁决见文末）
> 金样：`ios-port/golden/chat-import/`（4 包 + 生成器 + README + sync 脚本）
> git：**未提交**（lead 统一提交）；写域外文件未触碰。

## 1. 交付物清单

### 1.1 Android 侧（授权新增/最小入口）

| 文件 | 说明 |
|---|---|
| `RhodesLink/app/src/main/java/com/rhodes/privatechat/data/backup/ChatExportModels.kt`（新增） | `ChatExportConversation`/`ChatExportMediaItem`/`ChatExportResult` wire 模型 |
| `RhodesLink/app/src/main/java/com/rhodes/privatechat/data/backup/ChatExportService.kt`（新增） | 单会话结构化导出：manifest（复用 `BackupManifest`）+ conversation.json + media/；内容派生 mediaId；zip+sha256 复用 `BackupFileWriter` 模式 |
| `RhodesLink/app/src/main/java/com/rhodes/privatechat/ui/settings/BackupRestoreScreen.kt`（最小改动） | 新增 `createChatPackage` launcher（镜像可读导出 `:235-260`）+ 聊天选择器第三行「导出聊天包（.zip，可跨平台导入）」+ 文案 |

### 1.2 shared 侧（全部新增文件，不改 T10/T17 现有文件）

| 文件 | 说明 |
|---|---|
| `shared/.../backup/chatimport/ChatExportWireModels.kt` + `ChatExportReader.kt` | v1 读取/校验（复用 `BackupZip`/路径安全/manifest files/sha256 校验；**非** rhodes-backup 子集，T10 reader 拒绝为预期） |
| `shared/.../backup/chatimport/ChatImportMediaSink.kt` | 导入媒体落运行时媒体库（T17 `MediaStore`，内容寻址 `media/import-<sha24>.<ext>`，幂等 + 失败清理） |
| `shared/.../backup/chatimport/ChatImportRepository.kt` | 导入仓储依赖面 + 生产适配器（`ChatRepository`） |
| `shared/.../backup/chatimport/ChatMergeRestorer.kt` | 合并导入：会话匹配/合并、幂等去重、T17 `MessageIdAllocator` 重分配、displayEvents/存档/历史片段引用重映射、媒体引用重写、单事务 + 提交前自检、三层回滚 |
| `shared/.../backup/operatorcard/OperatorCardWire.kt` + `OperatorCardReader.kt` + `OperatorCardWriter.kt` + `OperatorCardService.kt` | rhodes-operator v1 读写（镜像 `OperatorPackage.kt:37-119`）+ 导入三模式/导出执行器（镜像 `OperatorPackageService`） |
| `shared/.../backup/BackupZipWriter.kt`（新增） | 纯 Kotlin 最小 ZIP 写入器（角色的卡写出 + 测试 v1 备份构造；commonMain 零新依赖） |
| `shared/.../backup/BackupSelectiveRestoreExecutor.kt` | 分类选择恢复门禁（插入也按选择，修复 T10 只做删除门禁的不对称） |
| `shared/.../backup/BackupRestoreSelfCheck.kt` | 恢复后 recordCounts 对账（不翻转已提交恢复） |
| `shared/.../backup/BackupRestoreCoordinatorEx.kt` | 补齐版编排（selective + 设置 apply + 自检；三层回滚同 T10） |

### 1.3 测试

| 文件 | 覆盖 |
|---|---|
| `shared/src/commonTest/.../backup/chatimport/ChatImportTestSupport.kt` | 金样加载（expect/actual bytes）、测试 env、坏包改写工具 |
| `shared/src/commonTest/.../backup/chatimport/ChatImportGoldenTest.kt` | 验收 #1-#7：4 金样校验/往返、T10 读取器互斥、重复导入幂等、坏包 4 变体无残留、formatVersion 3 类门禁、2000 条、会话合并、注入失败回滚 |
| `shared/src/commonTest/.../backup/operatorcard/OperatorCardTest.kt` | 验收 #8-#11：往返/无头像/坏包 6 变体/旧版 JSON 兼容/导入三模式/导出组装 |
| `shared/src/commonTest/.../backup/BackupRestoreCompletionTest.kt` | 验收 #12-#14：自检通过/失配、分类门禁、v1 单 payload 恢复、失败回滚 |
| `shared/src/{androidUnitTest,iosTest}/.../backup/chatimport/ChatImportPlatformTest*.kt` | 平台 env（JVM 内存驱动 / iOS NativeSqliteDriver + NSUserDefaults）+ 金样 bytes 读取 actual |

### 1.4 golden

| 文件 | 说明 |
|---|---|
| `ios-port/golden/chat-import/make_chat_exports.py` | 确定性生成器 4 类包 + `--verify` 自校验（镜像 reader 契约 #1-#8）+ 两次生成逐字节一致 |
| `ios-port/golden/chat-import/{chat-text,chat-image,chat-ai-json,chat-2000}-v1.zip` | 4 包（SHA-256 冻结见 README §1） |
| `ios-port/golden/chat-import/README.md` | v1 格式冻结说明 + 与 T10 reader 关系 |
| `ios-port/golden/chat-import/sync_golden.sh` | 同步 resources + 哈希防手改 + 双路径测试 |

## 2. 验收证据（实测 2026-11-23）

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 金样包 `ChatExportReader` 全项校验（4/4） | ✅ | `acceptance1_allGoldenArchivesValidate`（JVM+iOS PASS，消息数 8/6/5/2000、媒体数 0/2/1/0） |
| 2 | 金样往返一致（4 类夹具） | ✅ | `acceptance2_goldenRoundTrip`（text/image/ai-json：消息数/顺序/type/isMe/timestamp/displayEvents/存档引用重映射/媒体内容寻址）+ `acceptance7_2000MessagesImport`（2000 类内容/首尾消息/角色/会话） |
| 3 | 重复导入零重复 | ✅ | `acceptance3_duplicateImportIsIdempotent`：第二次 imported=0、deduped=6、消息/会话/displayEvents 快照不变、媒体文件数不变 |
| 4 | 坏 zip 无残留（缺 manifest / formatVersion=2 / `../` 路径 / 篡改字节） | ✅ | `acceptance4_invalidArchivesLeaveNoResidue`：4 变体均 Invalid + 导入失败 + DB/媒体零残留 |
| 5 | formatVersion 门禁 | ✅ | `acceptance5_formatVersionGate`：manifest formatVersion=2 / scope=full / format=rhodes-backup / conversation.formatVersion=2 四类明确 reason |
| 6 | 与 T10 完整备份读取器互斥 | ✅ | `acceptance1b_chatExportIsRejectedByFullBackupReader`：`BackupFileReader.validate` → Invalid「不支持的备份类型」 |
| 7 | 2000 条规模 | ✅ | `acceptance7_2000MessagesImport`：2000 导入、id=1..2000 严格递增、1000 displayEvents、**JVM 实测 272ms / iOS 模拟器 754ms**（<10s 软线） |
| 8 | 角色卡往返 | ✅ | `acceptance8_roundTripExact` / `8b_noAvatarCardRoundTrip`（JVM+iOS PASS） |
| 9 | 角色卡坏包门禁 | ✅ | `acceptance9_badCardsRejected` 6 变体 + `9b_avatarManifestMismatchRejected` |
| 10 | 角色卡导入三模式 | ✅ | `acceptance10_*`：NEW（名称去重后缀/头像媒体 ID/prompt 槽位回填）、PERSONA_AND_APPEARANCE（不改数值）、FULL_REPLACE（替换+关系） |
| 11 | 旧版单角色 JSON 兼容 | ✅ | `acceptance11_legacySingleOperatorJsonAccepted` / `11b`（多角色拒绝） |
| 12 | 分类恢复门禁 | ✅ | `acceptance13_selectionGatesChatsRestore`（chats=false 保留现网数据且 roles upsert） |
| 13 | 恢复后自检 | ✅ | `acceptance12_selfCheckPassesAfterGoldenRestore`（1/1/7/0 对账 PASS）+ `12b_selfCheckDetectsMismatch`（删消息后 mismatch） |
| 14 | v1 单 payload 备份读取恢复等价 | ✅ | `acceptance14_v1SinglePayloadBackupRestores`（`BackupZipWriter` 构造 v1 包 → validate Valid → 恢复 + 自检 PASS） |
| 15 | Android 导出器可编译 + 入口可达 | ✅ | `:app:assembleDebug` **BUILD SUCCESSFUL（40s）**；writer 规则与金样生成器同口径（字段序/清单写入序/sha256/内容派生 mediaId） |
| 16 | Android 测试不回归 | ✅ | `:app:testDebugUnitTest` **338 tests / 0 failed**（基线 324，差额为 T28 并行新增；本卡未新增 app 测试） |

双路径/全量回归实测：

| 套件 | 命令 | 结果 |
|---|---|---|
| JVM 新增 3 类 | `:shared:testDebugUnitTest --tests "*ChatImportGoldenTestJvm*" --tests "*OperatorCardTestJvm*" --tests "*BackupRestoreCompletionTestJvm*"` | 26 tests / 0 failed |
| iOS 新增 3 类 | `:shared:iosSimulatorArm64Test --tests "...Ios"`（iPhone 17 模拟器） | 26 tests / 0 failed |
| JVM 全量 | `:shared:testDebugUnitTest` | **504 tests / 0 failed** |
| iOS 全量 | `:shared:iosSimulatorArm64Test` | **532 tests / 0 failed** |
| Android app | `:app:testDebugUnitTest` | **338 tests / 0 failed** |
| 金样自校验 | `python3 make_chat_exports.py --verify <zip>` ×4 | 4/4 PASS（两次生成逐字节一致） |
| 坏包回滚 | `acceptance4`/`acceptance_rollbackOnDbFailureLeavesNoResidue`/`acceptance_mediaStagingFailureLeavesDbUntouched` | DB 无残留 + 新增媒体清理 |

## 3. 关键裁决落地记录

1. **lead 2026-11-23 裁决 1**：完整备份恢复补齐 = 分类选择门禁 + 分页/v1 读取覆盖 + 恢复后自检；全类目字段级重放单列 T31 —— 本卡已按此界定实现，T31 待办内容见 §4。
2. **裁决 2**：跨端验证口径 = python 金样（同格式同规则）+ writer 静态等价 + `:app:assembleDebug` 编译；真机端到端留 Phase 7 —— 已执行。
3. **裁决 3**：群聊合并 v1 只按导出 `session.id` 精确命中（私聊走确定性 `session_<operatorId>`）；成员集合模糊匹配二期。
4. reviewer PASS-WITH-FIXES（0 P1 / 1 P2 / 3 P3）：规格卡已回填裁决表述与行号修正（`MessageIdAllocator:48-60/68-72`、Coordinator 清理引用）。

## 4. 与 oracle 的差异（平台差异白名单 / 裁决范围内）

1. **chat mediaId 内容派生**（`image/<sha256(content)>`）vs 完整备份的路径派生（`BackupMediaCollector.kt:71-72`）：T6 裁决格式，两容器独立；`BackupMediaCollector` 未改动。
2. **消息 id 重分配**：聊天导入是合并语义，必须重分配（T6 §1.3 跨设备碰撞）；完整备份恢复仍原样插入（T10）。`max_used_message_id` 经 `MessageIdAllocator.observeUsed` 抬高。
3. **displayEvents revealOrder 保留导出值**（历史时间戳语义，使旧消息揭示顺序正确）；缺失/≤0 时按文件序补 1-based。
4. **存档/历史片段 messagesJson/stateJson 的 id 重映射**：递归替换键 `id`/`messageId` 的数字值（无 Android oracle，iOS 新逻辑）；`stateJson` 其他形态 id 若存在可能残留（风险已记规格卡 §风险 7，仅影响存档回放上下文）。
5. **角色卡 ZIP 写出为 STORED**（纯 Kotlin writer，commonMain 无 deflate 写出）；Android 生产的 deflate 角色包读取侧兼容（`BackupZip` 支持 deflate）。
6. **完整备份恢复补齐**：设置恢复补上 `PortableSettings.apply`（T10 coordinator 只有 snapshot/clearForRestore 未 apply）；自检不翻转 `success`（同 T10 规格 #13 口径）。

## 5. 已知限制 / 遗留（交后续波）

1. **T31（lead 裁决单列）**：全类目字段级重放（记忆/锚点/动态/评论/日记/知识库/派遣/礼物/共同经历/存档的完整字段恢复）——需新完整 wire 模型（现 `BackupWireModels.kt` 为校验级占位）+ 全量执行器 + 全类目金样。
2. **Android 真机端到端**：验收用「同格式规则金样 + writer 静态等价 + assembleDebug」；真机导出→iOS 导入留 Phase 7 设备 Gate（lead 裁决 2）。
3. **群聊合并**：v1 仅按导出 session.id 精确命中；按成员集合模糊匹配二期（lead 裁决 3）。
4. **去重键误合并**：同毫秒/同发送者/同 type/同 content/isMe 的两条真实消息会被合并为 1 条（概率极低；如需可把 narration/segmentGroup 并入键）。
5. **mediaIndex 单 originalUri**：同一内容被两个 URI 引用时只登记首个 originalUri（内容去重）；第二个引用导入后保留旧值（不崩溃，仅该引用不可渲染）。如需可在 v2 增加 `originalUris` 列表。
6. **设置与 DB 非原子**：角色卡 prompt 槽位/聊天导入不涉设置；完整恢复设置回滚为快照回放（与 Android 同语义，T10 规格卡风险 5 已接受）。
7. **iOS 生产接线**：`ChatImportMediaSink`/`OperatorCardMediaSink`/`MediaStore` 由 T29 `IosAppGraph` 注入（本卡只交付域层 + 默认 MediaStore 实现）；`OperatorCardExporter.export(operatorId, resolveAvatarBytes)` 的头像字节解析由宿主经 `MediaRepository.resolve` 提供。

## 6. 构建/测试命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh && cd RhodesLink
# 金样同步（改金样后必须）
bash ../ios-port/golden/chat-import/sync_golden.sh --sync-only
# JVM 双路径之一
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*ChatImportGoldenTestJvm*" --tests "*OperatorCardTestJvm*" --tests "*BackupRestoreCompletionTestJvm*"
# iOS 模拟器路径（iPhone 17）
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*ChatImportGoldenTestIos*" --tests "*OperatorCardTestIos*" --tests "*BackupRestoreCompletionTestIos*"
# Android 导出器编译
./gradlew --no-daemon :app:assembleDebug
# Android 回归
./gradlew --no-daemon :app:testDebugUnitTest
# 金样自校验
cd ../ios-port/golden/chat-import && python3 make_chat_exports.py --verify chat-text-v1.zip
```
