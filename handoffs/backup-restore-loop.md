# handoff: backup-restore-loop（T10）— Android 备份 → iOS 最小恢复闭环（前半）

- 代理：backup-restore-loop（T10）
- 任务：task-10（Phase 0 第 2 批第一半：金样备份包 + 恢复管线规格卡）
- 基线：RhodesLink @ `ios-port`（tag `v1.14.27-android-baseline` @ `3642d2c`）；工作区根 `/Users/jackchen/Documents/deepseek-harness/default-workspace`（注：任务描述里的 `workspace/` 前缀与实际布局不符，实际为 `<root>/RhodesLink` 与 `<root>/ios-port`）
- 写域遵守：前半只写了 `ios-port/{golden/backup, specs, handoffs}`；**2026-11-23 后半已按批准规格卡实现** shared 层恢复管线（commit `d60cc36`，见 §7），实现阶段仅写 `shared/src/{commonMain,iosMain,commonTest,androidUnitTest,iosTest}` 的 backup 包 + `shared/build.gradle.kts` 最小 diff。

## 1. 产出物

| 文件 | 状态 |
|---|---|
| `ios-port/golden/backup/make_minimal_backup.py` | ✅ 确定性生成器 + 自校验（镜像 BackupFileReader 关键检查），可复跑 |
| `ios-port/golden/backup/minimal-1.14.27.zip` | ✅ 生成并通过全部自校验（5 条目，逐条目 SHA-256） |
| `ios-port/golden/backup/minimal-1.14.27.md` | ✅ 包内容 + 构造依据 file:line + 校验通过记录 |
| `ios-port/specs/backup-restore-minimal-spec.md` | ✅ 按模板填写完毕（13 条行为契约全带 oracle file:line；10 项可自动判定验收） |
| `ios-port/handoffs/backup-restore-loop.md` | ✅ 本文件 |

## 2. 金样包关键决策（供 reviewer 核对）

- 结构：`manifest.json` + 3 个分页文件（`data/pages/roles/000000.json`、`chats/000000.json`、`chats/010000.json`）+ `media/images/<stableId>.png`；v2 布局 `paged_payload`，无 `data/backup.json`。分页分配复刻 `BackupPagePlanner.kt:14-30`：operators→ROLES/0（media 随 index 0 页携带）、sessions→CHATS/0、messages→CHATS/10000。
- 数据：角色 `golden` + 会话 `session_golden`（`"session_$operatorId"` 规则 SessionRepository.kt:43）+ 7 条消息（text×4、image×1、ai_json×2，其中 1 条 ai_json 段内嵌 imageUri）+ 1×1 PNG（70 字节合法 PNG）。
- mediaId 细节（重要）：`originalUri=file:///data/user/0/<pkg>/files/images/golden_1x1.png`（Uri.fromFile 原样路径），而 stableId 按 **canonical** 路径 `/data/data/...` 计算（复刻真机符号链接下 BackupMediaCollector.kt:64,71-72 行为）。由此同时覆盖 BackupRestorePayloadRewriter 的精确/canonical/私有路径三层匹配分支 → 是 iOS「mediaId 直映射」改造的最小回归样本。
- 确定性：createdAt/backupId/时间戳/zip 条目时间全固定；两次运行逐字节一致（验收 #2）。
- JSON 形态：字段顺序 = data class 声明顺序，且按 `encodeDefaults=true` 全量写出（与 Android 实际输出一致）；reader 用 `ignoreUnknownKeys=true`，容忍未来字段。

## 3. 校验通过记录（截要）

```
python3 make_minimal_backup.py --verify minimal-1.14.27.zip
  [OK] 安全路径 & 无重复路径: 5
  [OK] manifest: rhodes-backup v2 full: 1
  [OK] 逐条目 size+SHA-256 校验: 4
  [OK] 分页清单/标识/recordCount 一致: 3
  [OK] 引用完整性（角色/会话/消息）: 9
  [OK] 媒体清单 vs ZIP/引用发现: 2
PASS
```
完整哈希列表见 `minimal-1.14.27.md` §3。

## 4. 验收命令

> 权威副本已内联进规格卡「验收命令（内联，可直接运行）」（reviewer 要求，handoff 属临时交接物不作稳定依据）；本节省略重复内容，如需手工复跑直接取规格卡。

已实测通过：`--verify`（#1）、确定性哈希（#2，zip sha256=799b2ba06b4167d8245ba888c3db586bde8e24707f2a5fb7dd218c66ef5b5209）、payload diff（#3）、坏媒体字节翻转拒绝（#4）、formatVersion=3 / 缺 manifest 拒绝（#5 两变体）。

## 5. Open questions（2026-11-22 lead 已裁决，记录于规格卡「风险与开放问题」与「评审结论」）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | iOS「恢复前安全备份」目录语义 | ✅ Phase 0 只做三层自动回滚（单事务+媒体目录删除+设置快照回放）；restore-safety-backups 文件策略随 A5 完整实现 |
| 2 | iOS 媒体 URI 重写方案 | ✅ mediaId 直映射，替代 Android 路径特征串重写；记入平台差异白名单（金样已覆盖 /data/user/0 与 /data/data 两种形态作回归样本） |
| 3 | mediaId 路径派生 vs 内容派生 | ✅ 原样保留路径派生语义；导入不重算 stableId |
| 4 | 恢复后消息 id 分配器 | ✅ 复刻 getNextMessageId() 的 max(内存, DB max+1, persisted) 自愈（MessageRepository.kt:294-301），列为实现清单项 |
| 5 | 设置回滚非原子（快照回放） | 与 Android 一致，待 reviewer 确认（已写入规格卡风险 #5） |

## 6. 实现阶段步骤建议（lead 批准规格卡后）

> 开工前提（2026-11-22 lead 通知）：(i) reviewer 通过规格卡；(ii) 并行 Kotlin 2.3.21 升级代理完成（构建串行纪律）。收到 reviewer 修正意见后先改规格卡再实现。

1. 在 `shared/src/commonMain` 复刻纯逻辑层（无平台依赖）：`BackupModels`/`BackupFileReader`（ZipInputStream→kotlinx 解码 + 校验；Kotlin 2.3.21 下 zip 流读取用 iosMain actual）+ `BackupPagePlanner.merge` + `BackupRestorePayloadRewriter`（mediaId 直映射版）。Android 侧不动，新代码放 iOS 专用命名空间（如 `iosImport`），避免与 app 层同名冲突。
2. iosMain actuals：文件流（document picker `Data` → 内存/临时文件）、SHA-256（CommonCrypto 或 kotlin 纯实现，与 reader 字节语义一致）、媒体落盘（Application Support/restored_media/<backupId>/）。
3. 恢复执行器：SQLDelight 单事务 `runRestoreTransaction` 等价物 + RestoreDatabaseExecutor 的删除/插入顺序（子先于父）。
4. 编排：actor 串行 + 写围栏 + 阶段回调 + 失败路径（删媒体目录、回放设置、返回 safetyBackup 引用）。
5. 测试：金样包驱动 commonTest（验收 #7-#10）；坏包矩阵（#4/#5 变体）搬进测试夹具；恢复后自检 = manifest.recordCounts vs DB 计数。
6. 回归：确认未触碰 Android app/ 源码；`./gradlew :shared:iosSimulatorArm64Test` 与本阶段新增用例通过后回报 lead。

## 7. 工作记录

- 2026-11-22：claim task-10（rev 2）；通读 8 个 backup 包源文件 + ExportPayload + 3 个 shared model + SessionRepository id 规则 + ChatViewModel 图片消息形状 + MessageParser JSON 容忍度 + PortableSettings 白名单 + audit/{fields,paths}.md + 规格卡模板；产出全部 5 个文件并完成自校验。
- 2026-11-22（续）：lead 裁决 a/b/c 三项（mediaId 直映射 / 三层自动回滚 / mediaId 语义保留 + id 分配器自愈），已回写规格卡「风险与开放问题」「评审结论」与本 handoff §5；实现等待 reviewer 通过 + Kotlin 升级完成，期间不动手写实现代码。
- 2026-11-22（再续）：reviewer **PASS-WITH-FIXES**（4×P2 + 2×P3，非阻塞）。6 条修正全部落笔：规格卡边界「磁盘满/IO」按事实改写（staging 失败仅删 restoreInput、部分媒体残留为 Android 现状；DB 失败才删媒体目录+回放设置）、删除顺序严格按 RestoreDatabaseExecutor.kt:47-55（reply_turns 最先）、风险 1 改「已裁决」表述、验收 #2-#6 完整命令内联进规格卡、#6 补 mediaId 溯源断言、golden md 行号精确化；另按 reviewer 建议采纳「任何失败都清理部分解出媒体目录」为 P2 平台差异补强。lead 已批准规格卡；实现前提 = 修正落笔（已完成）+ T11 Kotlin 升级完成。
- 2026-11-23：**实现完成（T11 后开工）**。shared/src 新包 `com.rhodes.privatechat.shared.backup`：
  - commonMain：`BackupCrypto`（纯 Kotlin SHA-256/CRC32，NIST 向量自校验）、`BackupInflate`（raw deflate 解码：stored/fixed/dynamic）、`BackupZip`（最小 ZIP 读取器，逐条目 CRC 校验，镜像 ZipInputStream 语义）、`BackupWireModels`（wire 镜像，ignoreUnknownKeys 容忍）、`BackupFileReader`（oracle 全校验移植：路径安全/去重/manifest 三元组/files 集合相等/逐条目 SHA-256/v2 分页+recordCount+merge/v1 只读/引用完整性 5 类 ORPHAN/媒体清单一致）、`BackupPagePlanner.merge`、`BackupRestorePayloadRewriter`（mediaId 直映射，lead 裁决 a）、`BackupContentFilter`、`BackupPortableSettings`（白名单三组 key 逐字对齐 app）、`BackupRestoreExecutor`（roles+chats 单事务删+插，顺序严格按 oracle :47-55/77-104）、`BackupRestoreCoordinator`（阶段编排 + 三层回滚 + P2 staging 清理补强，lead 裁决 b）。
  - iosMain：`BackupMediaSinkIos`（NSFileManager 落盘 Documents/restored_media/<backupId>/，防穿越）。
  - commonTest 24 个：金样包 base64 内嵌（sha256 与磁盘金样 799b2ba0… 绑定断言）、SHA-256/inflate 向量、坏包矩阵（缺 manifest/版本 3/不安全路径/重复路径/files 缺媒体/字节损坏/非 zip）、#7-#10 全部 DB 验收。
  - 平台测试子类：`BackupRestoreGoldenTestJvm`（JdbcSqliteDriver 内存库 + 手写 InMemoryObservableSettings，因 settings 1.2.0 无 MapSettings）、`BackupRestoreGoldenTestIos`（NativeSqliteDriver 唯一库名 + 独立 NSUserDefaults suite，隔离跨测试污染）。
  - build.gradle.kts：仅新增 androidUnitTest `sqldelight-sqlite` 块（lead 批准）；commonTest kotlin-test 与 deviceId="iPhone 17" 由 T9 已加，未重复。
- 2026-11-23 测试结果（两条路径均真跑）：
  - `:shared:testDebugUnitTest --tests 'com.rhodes.privatechat.shared.backup.*'` → **24 tests, 0 failures, BUILD SUCCESSFUL**（JVM 内存驱动）。
  - `:shared:iosSimulatorArm64Test --tests 'com.rhodes.privatechat.shared.backup.*'` → **24 tests, 0 failures, BUILD SUCCESSFUL**（iOS 27.2 模拟器 iPhone 17，NativeSqliteDriver）。
  - 规格卡 #1-#6（Python 自校验）此前已全 PASS；#7-#10 现由 commonTest 双路径覆盖。
- 协调记录：iOS 路径曾被 T9（ios-data-base）WIP 文件阻塞（JsonSettingsStore.ios.kt / KtorDarwinStreamingTest.kt / DatabaseMigrationTest.kt），已通过 lead 与 T9 直接消息协调，未越界改其文件；修复后我重跑通过。首轮 iOS DB 测试失败为跨测试共享库文件污染（同 pid 同名 db），已用唯一库名 + 独立 suite 修复。T9 后续对 BackupMediaSinkIos.kt 做了 K/N 2.3 interop 最小修正（NSFileManager 成员方法、security.toNSData 可空保护），复跑双路径 24/24 无回归。
- 遗留/下一步（A5 完整实现时）：v1 备份只读已随 reader 移植（`data/backup.json` 路径）；消息 id 分配器自愈需在 iOS 生产侧复刻 getNextMessageId()（风险 4）；SwiftUI 侧接线（document picker Data → coordinator）与恢复后自检（recordCounts vs DB）待 Phase 2；Android app/ 零改动（shared android 编译多次通过，:app:assembleDebug 回归归 T11 Gate 把关）。
- 只读约束：全程未改 app/；所有 oracle 以 file:line 引用形式固化在脚本注释、金样 md、规格卡与实现代码注释中。
