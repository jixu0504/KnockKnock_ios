# handoff：impl-backup-full（T31 A5 全类目字段级重放）

> 代理：impl-backup-full ｜ 任务：task-31 ｜ 状态：**实现完成，双路径/回归验证见 §2**
> 规格卡：`ios-port/specs/backup-full-restore-spec.md`（lead 2026-11-23 批准，D1-D4 裁决见 §3）
> 金样：`ios-port/golden/backup-full/`（8 包 + 生成器 `make_full_backups.py` + README + `sync_golden.sh`）
> git：**未提交**（lead 统一提交）；写域外文件未触碰（T10/T30 现有文件零改动）。

## 1. 交付物清单

### 1.1 shared 侧（全部新增文件，`shared/src/commonMain/.../backup/wire/`）

| 文件 | 说明 |
|---|---|
| `FullBackupWireModels.kt` | 全类目完整 wire 模型：`FullBackupContentWire`（18 字段）/`FullBackupPayloadWire`（10 字段）/`FullBackupPayloadPageWire`/`FullBackupArchiveWire`/`FullBackupRelationshipWire`/`FullBackupKnowledgeBase*Wire`；模型型类目直接复用 shared `@Serializable` 模型（零 schema 漂移） |
| `FullBackupReader.kt` | 先 T10 `BackupFileReader` 全校验，再二次解码完整字段（v2 分页 + v1 单 payload）；双解码一致性护栏（逐类目记录数 + 媒体清单） |
| `FullBackupMediaRewriter.kt` | 全类目媒体 URI 重写（mediaId 直映射；扫描域与 Android 一致：operator/session avatarUri、messages.content、settings、gifts、archives JSON、history JSON） |
| `FullBackupContentFilter.kt` | 全类目孤儿过滤（5 个 ORPHAN issue code，与 T30 同语义） |
| `BackupFullCounts.kt` | 全类目计数口径：expected（过滤后 payload，按选择门禁）/read（22 表实际计数，既有 SQLDelight 查询）/invariantViolations（vectorId 清空 + moments 计数回填） |
| `BackupFullRestoreExecutor.kt` | 全量执行器：严格按 `RestoreDatabaseExecutor.kt:30-174` 的删除/插入顺序、id 语义（保留 vs 自增映射）、引用重映射、派生字段归一；**单事务 + 提交前自检**（失配抛错回滚） |
| `BackupFullSelfCheck.kt` | 恢复后自检：payload 派生计数（23 键，补 manifest 缺的 6 项）vs DB + manifest 交叉校验 + 设置键值对账 + 字段不变量；不翻转 success |
| `BackupFullSettingsRestorer.kt` | 设置恢复 D3 语义：选中=快照+清空+全量 apply；未选中但恢复 roles/chats=仅角色局部 key 家族；回滚=快照回放+删除本次新增 key |
| `BackupFullRestoreCoordinator.kt` | 全类目编排：VALIDATING → … → RESTORING_MEDIA → RESTORING_DATABASE（单事务+提交前自检）→ REBUILDING_RUNTIME（no-op）→ COMPLETED；三层回滚（事务/媒体/设置）+ 取消语义 |

### 1.2 测试（新增；双路径共用 commonTest 抽象类）

| 文件 | 覆盖 |
|---|---|
| `commonTest/.../backup/wire/FullBackupTestSupport.kt` | 金样加载（expect/actual bytes，冻结 SHA-256 断言）+ 金样变体改写器（删条目/改 recordCount/改分页 JSON/重复路径/不安全路径/坏媒体字节）+ DB 读取 helper |
| `commonTest/.../backup/wire/FullBackupGoldenTest.kt` | 验收 #1-#16 全部 16 个用例 |
| `androidUnitTest/.../wire/FullBackupGoldenTestJvm.kt` + `FullBackupPlatformJvm.kt` | JVM 路径（JdbcSqliteDriver 内存库 + classpath 资源） |
| `iosTest/.../wire/FullBackupGoldenTestIos.kt` + `FullBackupPlatformIos.kt` | iOS 路径（NativeSqliteDriver + 独立 NSUserDefaults suite + test bundle 资源） |

### 1.3 golden

| 文件 | 说明 |
|---|---|
| `ios-port/golden/backup-full/make_full_backups.py` | 确定性生成器 8 包 + `--verify`（结构/哈希/分页/引用/媒体/类目覆盖/预期 issue/逐字节重生成） |
| `ios-port/golden/backup-full/full-all-v2.zip` 等 8 包 | v2 全类目主金样 + 分类包 + 空包 + v1 单 payload（SHA-256 冻结见 README §1） |
| `ios-port/golden/backup-full/README.md` | 格式冻结 + 与 T10/T30 关系 + 预期孤儿 issue + 维护纪律 |
| `ios-port/golden/backup-full/sync_golden.sh` | 哈希校验 + 生成器自校验 + 同步测试 resources + 双路径单测 |
| `shared/src/{commonTest,androidUnitTest}/resources/golden/backup-full/` | 已同步 8 包 + README + SHA256SUMS |

## 2. 验收证据（实测 2026-11-23）

| # | 验收项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 生成器确定性 + 8 包自校验 | ✅ 8/8 PASS | `python3 make_full_backups.py`（两次逐字节一致；`--verify` 全 PASS） |
| 2 | 8 包 T10 reader 全项 Valid | ✅ | `acceptance1_allGoldenPackagesValidate`（JVM+iOS，issue 数 0/2/3 符合预期） |
| 3 | full-all 全类目字段级往返 | ✅ | `acceptance3/4/5/6`（roles/chats、memories、social、knowledge+extras+settings 四组逐字段） |
| 4 | id 语义 + 引用重映射 | ✅ | `acceptance4/5`（保留 id 断言；评论父链/likes→新 moment/participants→新 experience/memoryItem sourceRefId→新 id） |
| 5 | 派生字段归一 + 运行表清空 | ✅ | `acceptance7`：KB pending、chunk indexedAt=0、dispatch active→paused、memoryItems L2/L3 archived、vectorId 清空、6 张运行表 + 种子向量清空 |
| 6 | 恢复后自检 | ✅ | `acceptance11`：23 键全对账 passed；删消息后直接自检检出 messages mismatch |
| 7 | 幂等 | ✅ | `acceptance8`（同包二次恢复 22 表计数/内容等价） |
| 8 | 坏包无残留（6 变体） | ✅ | `acceptance9`：缺分页条目/recordCount 错/sha 错/不安全路径/重复路径/媒体字节坏 → 均失败且 DB/媒体零残留 |
| 9 | 事务内失败三层回滚 | ✅ | `acceptance10`：注入 gift 主键重复 → DB 现网数据保留、设置回放、媒体清理 |
| 10 | 分类选择门禁 + D3 | ✅ | `acceptance12`：未选中类目零改动；roles/chats 整体替换；settings 未选中时全局设置不动、角色局部 key 随角色恢复 |
| 11 | 空包 | ✅ | `acceptance13`（清空选中类目；清空白名单设置） |
| 12 | 媒体重写全扫描域 | ✅ | `acceptance14`：头像/会话头像/消息图/礼物图/存档 JSON/历史 JSON/`user_avatar_uri`/`support_conversation` 全部重写；无旧 URI 残留 |
| 13 | ORPHAN/skip 语义 | ✅ | `acceptance15`：memories 包 2 issue / social 包 3 issue；skip 后孤儿行剔除、不 skip 时落库（Android 同款） |
| 14 | 分类包 + v1 单 payload | ✅ | `acceptance16`（4 分类包 + `full-v1-single.zip` 全类目恢复） |
| 15 | 双路径全量回归 | ✅ | JVM `:shared:testDebugUnitTest` **520 tests / 0 failed**（基线 504，T31 +16）；iOS `:shared:iosSimulatorArm64Test` **559 tests / 0 failed**（基线 532，T31 +16，其余增量为并行代理） |
| 16 | app 不回归 | ✅ | `:app:testDebugUnitTest` **345 tests / 0 failed**；`:app:assembleDebug` BUILD SUCCESSFUL |

> 金样自校验命令：`cd ios-port/golden/backup-full && python3 make_full_backups.py --verify`（8/8 PASS）。

## 3. lead 裁决落地记录（2026-11-23）

1. **D1 批准**：新增 `backup/wire/` 包 + 「先 T10 全校验、再二次解码完整字段」；T10/T30 现有文件零改动；恢复路径解码两次的性能风险记 Phase 7 合并。
2. **D2 批准**：提交前自检在事务内（payload 期望计数 + 字段不变量，失配抛错 → 单事务回滚）；提交后自检按 manifest.recordCounts ∪ payload 派生计数对账，不翻转 success（T10 口径）。
3. **D3 采纳推荐**：settings 选中 = 快照+清空+全量 apply；未选中但恢复 roles/chats = 仅 apply 角色局部 key 家族（`operator_prompt_slot_*/msg_*/dyn_*/voice_volume_*/chat_tts_*/bg_*/diary_read_at_*/last_mode_*/gbg_*`），全局设置不动。
4. **D4 批准**：iOS 自检以 payload 派生计数补足 Android `manifest.recordCounts` 缺的 6 项（memories/anchors/memoryItems/likes/participants/mahjong），不改备份格式。

## 4. 与 oracle 的差异（平台差异白名单）

1. **插入也按分类选择门禁**（T30 裁决沿用）：Android `RestoreDatabaseExecutor` 只做删除门禁、插入无条件；iOS 未选中类目不写（更保守，避免越界覆盖）。
2. **settings 选择语义**（D3）：Android 无论是否选中 settings 都 apply 全部备份设置（`BackupRestoreCoordinator.kt:124`）；iOS 按 D3 分层（选中全量 / 未选中仅角色局部 + 全局不动）。
3. **失败路径无条件清理媒体目录**（T10 P2 补强沿用）。
4. **`hidden_ids` 快照口径**：`BackupPortableSettings.apply` 对 `hidden_ids` 写纯字符串，而 `snapshot` 用 `getStringSet` 读；roundtrip 后 snapshot 会丢该键（Android/共享 SettingsRepository 同款现状）。本卡自检对 `hidden_ids` 改用 `settings.getString("hidden_ids")` 与备份值比对（可验证 apply 生效），snapshot 全量比对跳过该键。**不修改 T10/T30 文件**。
5. **调度器/向量重建 = no-op**：运行期重建（DailyContentScheduler/GroupAutoChatScheduler/向量索引重建）归 Phase 7；本卡保证恢复后为「待重建」态（KB pending、chunk indexedAt=0、vectorId 空、运行队列表清空）。

## 5. 已知限制 / 遗留

1. **恢复路径解码两次**（D1 代价）：T10 校验一次 + 完整字段一次；金样 <20KB 无感，真实 500MB 上限下多一次解码（Phase 7 可合并单解码）。
2. **自增表 id 不保留**（与 Android 一致）：memories/anchors/moments/comments/likes/diaries/memoryItems/sharedExperiences 自增；引用它们的 `memoryItems.sourceRefId`、comments.parent、likes.momentId、participants.experienceId 已映射；`stateJson/rawJson` 内嵌 moment/comment id 不重映射（Android 同款，不扩大扫描域）。
3. **媒体引用扫描域不含 moments/diaries/memories/memoryItems 内容**（Android 同款）：这些内容里的旧 `file://` URI 会残留不可渲染（与 Android 现状一致）。
4. **moments likeCount/commentCount 以实际行数回填**：若备份计数与行数不一致（历史脏数据），恢复后按行数修正。
5. **角色局部设置随角色恢复**仅覆盖恢复时选中 roles 或 chats 的场景；settings-only 恢复不 apply 角色局部 key（对应 operator 未恢复）。
6. **iOS 生产接线**：`BackupFullRestoreCoordinator(repository, settings)` 与 T10 coordinator 同形，Phase 3/7 由 `IosAppGraph` 注入即可（本卡只交付域层）。
7. **UI 未接线**：恢复入口/进度/结果展示归 Phase 3/7。

## 6. 构建/测试命令

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace
source ios-port/toolchain/env.sh
# 金样同步（改金样后必须）
bash ios-port/golden/backup-full/sync_golden.sh --sync-only
cd RhodesLink
# 全类目测试（双路径）
./gradlew --no-daemon :shared:testDebugUnitTest --tests "*FullBackupGoldenTestJvm*"
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*FullBackupGoldenTestIos*"
# 全量回归
./gradlew --no-daemon :shared:testDebugUnitTest
./gradlew --no-daemon :shared:iosSimulatorArm64Test
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug
```

## 7. 工作记录

- 2026-11-23：claim task-31；通读 T30 `impl-a5-import.md`、T10 `backup-restore-loop.md`、`minimal`/`chat-import` 规格卡、`BackupWireModels`/`BackupFileReader`/`BackupRestoreExecutor`/`BackupSelectiveRestoreExecutor`/`BackupPortableSettings`/`BackupRestoreSelfCheck`、Android `ExportPayload`/`BackupSnapshotBuilder`/`BackupPagePlanner`/`BackupFileWriter`/`RestoreDatabaseExecutor`/`BackupRestoreCoordinator`/`PortableSettings`、golden/db/schema 24 个 `.sq`（28 表；与 shared schema 逐字节 diff 一致）。
- 2026-11-23：产出规格卡（7 类目 × 22 恢复表 + 6 重置表字段矩阵 + C1-C14 契约 + 金样设计 + 验收 #1-#16 + 风险/裁决项 D1-D4）与 `golden/backup-full/` 8 包（生成器 + README + sync；8/8 自校验 PASS），send_message 报 lead。
- 2026-11-23：lead 批准规格卡 + D1-D4 裁决（见 §3）。
- 2026-11-23：实现 `backup/wire/` 10 个新文件（模型/读取/重写/过滤/计数/执行器/自检/设置/编排）+ 双路径测试 16 用例；JVM 全类目 16/16 PASS → 修 settings 自检口径/坏包变体后全绿；双路径全量 + app 回归见 §2。
- 2026-11-23：双路径实测：JVM 全量 520/0、iOS 全量 559/0、app 345/0、`:app:assembleDebug` BUILD SUCCESSFUL；T31 新增 16 用例双路径全绿。git 未提交（lead 统一提交）；任务 board complete。
