# 规格卡：备份恢复最小闭环（Phase 0）

> 模板：`ios-port/specs/规格卡模板-v1.md`。黄金法则：Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`）是行为 oracle；每条行为契约必须指向 Android 源码 file:line。
> 本卡范围 = Phase 0 Gate 0「Android 备份可恢复到 iOS」的最小闭环：**读取+校验 → 媒体落盘+URI 重写 → 单事务恢复 → 失败回滚**。实现代码在 lead 批准本卡前不写入 `RhodesLink/shared/src`。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 备份恢复最小闭环（Phase 0）：Android 1.14.27 完整备份 → iOS 恢复 |
| 所属阶段 | Phase 0（规格卡先行；实现归 A5「数据、备份与迁移」，计划书 §三/Phase 2 交付物） |
| 范围映射 | A5（修订）：“Android 1.14.27 完整备份可在 iOS 导入并正确恢复（100% 金样通过）；备份格式版本号、校验、明确错误提示；密钥不进备份；恢复失败可回滚；恢复后自检”（计划书 line 66-68） |
| 对应 Android 源码 | `app/src/main/java/com/rhodes/privatechat/data/backup/`：BackupFileWriter.kt、BackupFileReader.kt、BackupPagePlanner.kt、BackupModels.kt、BackupMediaStager.kt、BackupMediaCollector.kt、BackupRestorePayloadRewriter.kt、BackupRestoreCoordinator.kt、RestoreDatabaseExecutor.kt、BackupContentFilter.kt、PortableSettings.kt、BackupRestoreMaintenance.kt；`app/.../data/ExportPayload.kt` |
| 依赖模块 | shared 层：`ChatRepository`（runRestoreTransaction 注入点）、`SettingsRepository`、`shared/model/{Operator,ChatSession,ChatMessage}`、SQLDelight queries（operators/chat_sessions/chat_messages 等）；上游：T1 的 iosMain actuals 骨架（framework 可链接）、Kotlin 2.3.21 升级 |

## 输入 / 输出 / 边界

- 输入：
  - 备份包 `InputStream`（ZIP，扩展名 `.rbackup`，格式 `rhodes-backup` v2/v1）。iOS 侧来自文件选择器（document picker）或沙盒导入的 `Data`。
  - 恢复选项 `BackupRestoreOptions`：`skipIssueCodes`、`contentSelection`（BackupModels.kt:111-114）。Phase 0 最小闭环固定使用 `BackupContentSelection.All`。
  - 金样：`ios-port/golden/backup/minimal-1.14.27.zip`（本卡配套产物）。
- 输出：
  - 校验结果 `BackupValidationResult.Valid(manifest, issues)/Invalid(reason)`（BackupModels.kt:124-127）。
  - 恢复结果 `BackupRestoreResult(success, stage, manifest, safetyBackup, reason)`（BackupRestoreCoordinator.kt:34-40）。
  - 副作用：DB 表重放写入（见「数据与迁移」）；媒体文件落入恢复目录；恢复前安全备份文件落盘（Android 行为；iOS Phase 0 不落盘，见风险 1 裁决）；`BackupRestoreProgress` 阶段回调。
- 边界（逐项行为，全部带 oracle）：
  - **坏 zip**（CRC 错/截断/非 zip）：`ZipInputStream` 读取异常 → `validate` 返回 `Invalid`（BackupFileReader.kt:16-21）；`restore` 阶段失败且删除暂存输入（BackupRestoreCoordinator.kt:66-72,125-129）。
  - **缺 manifest**：抛 `BackupFormatException("缺少 manifest.json")`（BackupFileReader.kt:57-58）。
  - **版本不匹配**：format≠`rhodes-backup`、formatVersion∉{1,2}、scope≠full → 各自拒绝（BackupFileReader.kt:61-63）。
  - **缺媒体 / 媒体缺失**：清单与 ZIP `media/` 条目不一致 → 拒绝（BackupFileReader.kt:93-98）；解包后 mediaId 集合不完整 → 取消恢复（BackupMediaStager.kt:28-30）。
  - **重复导入**：恢复语义是「删 + 插」（全量替换，非增量合并），同一包重复导入结果幂等（RestoreDatabaseExecutor.kt:30-74,77-101）。
  - **条目/体积上限**：maxEntries=1000、maxEntryBytes=100MB、maxTotalBytes=500MB（BackupFileReader.kt:10-13）、单页 ≤16MB（:87,237）。
  - **磁盘满 / IO 失败**：暂存拷贝失败即删除目标文件（BackupRestoreCoordinator.kt:157-166）；媒体 staging 失败 → FAILED + 输入暂存清理（:125-129），**部分解出的 `restored_media/<backupId>` 残留为 Android 1.14.27 现状**（外层 catch 只删 restoreInput）；DB 恢复失败 → 删除媒体目录 + 回放设置快照（:108-111）。iOS 需增加写入前剩余空间预检（Android 依赖 StatFs 诊断，paths.md §4）；iOS 补强（见平台差异白名单）：**任何失败路径都清理部分解出的媒体目录**。
  - **并发/重入**：`restoreMutex` 串行化恢复（BackupRestoreCoordinator.kt:58,203）；写入围栏 `BackupRestoreMaintenance`（active 标志，Maintenance.kt:9-25）阻止恢复期间自动回复落库。iOS 对应 actor 串行 + 同款写围栏。
  - **取消**：CancellationException 直接上抛、清理输入文件、不返回失败结果（BackupRestoreCoordinator.kt:121-124）。

## 行为契约（逐条，每条带 Android oracle 位置）

1. **读取与解析**：按流读 ZIP；路径一律 `\`→`/` 归一，`isSafeArchivePath`（非空、非 `/` 开头、无 `:/`、无空段/`.`/`..`）否则拒绝；重复路径拒绝；目录条目跳过。oracle：BackupFileReader.kt:32-55,236-244。
2. **manifest 结构校验**：manifest.json 必须存在且可解析；`format=="rhodes-backup"`、`formatVersion∈{1,2}`、`scope=="full"`；`files` 路径去重且**必须与 ZIP 条目集（去掉 manifest.json 自身）严格相等**。oracle：BackupFileReader.kt:57-69。
3. **逐条目 SHA-256**：对 `files` 中每个条目比对解压后字节数与 SHA-256（小写 hex），任一不符即 `文件校验失败`。oracle：BackupFileReader.kt:44-49,70-74,213-232；写入侧 BackupFileWriter.kt:56,63,139-141。平台差异白名单：无（纯字节逻辑，iOS 用 CommonCrypto/自实现 SHA-256 等价）。
4. **v2 分页解析**：`layout=="paged_payload"`；v2 禁止 `data/backup.json`；`pages` 非空、路径去重、同 category 下 pageIndex 去重；`pages.path` 集合与 ZIP 分页文件集合严格相等；按 (category.name, pageIndex) 排序解码，逐页校验 category/pageIndex 标识与 recordCount 公式（媒体不计入），最后 `BackupPagePlanner.merge` 合并为单一 payload。oracle：BackupFileReader.kt:102-121；分页规则 BackupPagePlanner.kt:13-84,102-122；recordCount 公式 BackupFileReader.kt:123-130。
5. **载荷标记**：合并后 `content.type=="full_backup"`；`mediaIncluded` 与 `payload.media` 非空一致。oracle：BackupFileReader.kt:90-91。
6. **引用完整性**：角色/会话/消息 id 唯一；私聊会话 `operatorId` 必须存在（`group_` 前缀走成员校验）；消息 `sessionId` 必须存在；知识库/关系/群成员/动态引用同理；孤儿记忆/锚点/评论/点赞降级为 `BackupIssue`（code：ORPHAN_MEMORY / ORPHAN_ANCHOR / ORPHAN_MOMENT_COMMENT / ORPHAN_MOMENT_LIKE / ORPHAN_SHARED_EXPERIENCE_PARTICIPANT，skippable=true），可由 `skipIssueCodes` 过滤。oracle：BackupFileReader.kt:132-207；过滤 BackupRestoreCoordinator.kt:135-155。
7. **恢复前安全备份**：恢复开始前用当前数据快照 + 媒体收集生成 `restore-safety-backups/before_restore_<ts>.rbackup`，且写后立即 `validate` 回读校验，校验失败则拒绝恢复；目录保留最近 5 份。oracle：BackupRestoreCoordinator.kt:79-82,169-188。
8. **媒体落盘（staging）**：只解 `payload.media` 声明的 `media/` 条目到 `restored_media/<backupId>/<archivePath>`；目标 canonical 化后必须在 staging 根内（防穿越）；全部 mediaId 解出成功才继续，否则抛 `备份媒体文件不完整，已取消恢复`。oracle：BackupMediaStager.kt:9-32。
9. **URI 重写（相对化落盘）**：将 payload 中所有 file:// 引用替换为 staging 后新路径：按 originalUri 精确匹配 → canonical 路径匹配 → Android 私有路径特征串（`/data/data/<pkg>/files/` 或 `/data/user/0/<pkg>/files/`）归一匹配，三层都失败则保留原值；重写范围为 operators/sessions 的 avatarUri、messages.content 字符串替换、settings 的 `user_avatar_uri`（`s:` 前缀保持）与 `support_conversation`、giftRecords.imageUri、chatArchives/chatHistorySegments 的 messagesJson/stateJson。oracle：BackupRestorePayloadRewriter.kt:3-42,48-63。
   - **平台差异白名单（iOS 必须替换）**：第 3 层 `androidPrivateFilesKey` 硬编码 Android 路径特征串，iOS 上无意义 → iOS 对应物为 **mediaId 直映射**（`payload.media[mediaId]→本地文件 URL`），扫描对象不变。依据 paths.md §7 结论；本条为 P0 差异，理由：iOS 沙盒路径无 `/data/data`、无 canonical 符号链接语义，且运行时媒体已 ID 化。
10. **单事务删+插恢复**：`repository.runRestoreTransaction` 内执行；删除顺序「子先于父」（reply_turns → chat_display_events → chat_archives+history_segments → chat_messages → chat_sessions 依次，RestoreDatabaseExecutor.kt:47-55），角色仅在 roles+chats 都选中时删除；随后按 角色→知识库→关系→会话→消息→… 顺序重放插入；任何一步抛错 → 事务整体回滚（DB 无残留）。oracle：RestoreDatabaseExecutor.kt:30-105（删 :47-55，插 :77-101）。iOS：SQLDelight 同表结构（golden/db/schema）下同一删除/插入顺序，包在 SQLite 单事务里。
11. **设置恢复与回滚**：设置（Preferences）在 DB 事务外：先 `PortableSettings.snapshot` + `clearForRestore`，再 `apply` 备份内 settings；DB 恢复失败 → 删除媒体目录 + 回放旧设置快照；前缀编码 `s:/b:/i:/l:`，`msg_*` 按显式权限回填。oracle：BackupRestoreCoordinator.kt:96-111；PortableSettings.kt:51-118。iOS 对应物：NSUserDefaults 快照 + Keychain 不动（密钥不进备份，fields.md §a3）。
12. **阶段编排与失败语义**：VALIDATING → STOPPING_BACKGROUND_WORK → CREATING_SAFETY_BACKUP → RESTORING_MEDIA → RESTORING_DATABASE → REBUILDING_RUNTIME → COMPLETED；异常 → FAILED（返回 success=false + safetyBackup 供用户自救）；重建运行期（scheduler）失败**不**翻转已提交的恢复结果。oracle：BackupRestoreCoordinator.kt:58-133（阶段枚举 :16-25）。
13. **恢复后自检（iOS 侧补强项）**：Android 无显式自检步骤（Gate 2 要求由 iOS 补：恢复后比对 manifest.recordCounts 与 DB 计数）。计划书 line 179「Android 备份在 iOS 恢复后自检通过；导入失败不污染数据库」。

## 数据与迁移

- 涉及表（最小闭环：roles+chats 类别）：
  - 写：`operators`、`chat_sessions`、`chat_messages`（对照 `ios-port/golden/db/schema/Operators.sq`、`ChatSessions.sq`、`ChatMessages.sq`；插入语句分别对应 `insertAllOperators`、`insertSession`、`insertAllMessages`，均为 `INSERT OR REPLACE` 语义）。
  - 删（chats 类别，子先于父，严格按 RestoreDatabaseExecutor.kt:47-55 顺序）：`reply_turns` → `chat_display_events` → `chat_archives`（archives + history_segments 两 query）→ `chat_messages` → `chat_sessions`（含 groups）。
  - 删（roles 类别，roles+chats 同时选中时）：`relationships`、`operators`（:71-74）。
- 迁移版本号：无新增迁移。v1 备份只读兼容（formatVersion=1 → `data/backup.json` 单 payload 路径，BackupFileReader.kt:76-81）保留在读取器；iOS 最小闭环先实现 v2 读、v1 读列为 Phase 2 附项。
- 备份/恢复中的表现：
  - 字段进备份：Operator 全 22 字段（含 privatePrompt/groupPrompt/memoryInjection/avatarUri）、Session 12 字段、Message 15 字段（含 content 全文、type、isMe）、settings 白名单（PortableSettings.kt:8-49）、媒体清单（fields.md §a2）。
  - 密钥不进备份：api_key/vector_api_key/vision_api_key/asr_api_key/tts_api_key/newspaper_deepseek_key/asr|tts_provider_configurations/group_auto_plan_token_* 均在白名单外（fields.md §a3 检查清单）。
  - 消息 id 与分配器：恢复**原样**插入消息 id（RestoreDatabaseExecutor.kt:99-101）；`max_used_message_id` 不进备份也不回填，Android 靠 `getNextMessageId()` 取 `maxOf(内存值, DB max+1, persisted)` 自愈（MessageRepository.kt:294-301）。iOS 必须复刻该分配器，否则恢复后新消息可能撞 id（风险见下）。
  - 失败回滚：DB 单事务回滚 + 媒体目录递归删除 + 旧设置快照回放（BackupRestoreCoordinator.kt:107-111）；Android 另留存安全备份供手动恢复，iOS Phase 0 不落盘（lead 裁决 b，风险 1），保留策略随 A5。

## 验收（必须可自动判定）

金样：`ios-port/golden/backup/minimal-1.14.27.zip`（1 角色 + 1 会话 + 7 消息 + 1 媒体；含文本/图片/ai_json/ai_json 内嵌图引用）。1-6 项**今天即可自动判定**（reviewer 已实跑）；7-10 项在实现落地后运行（命令已给出）。

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | 金样包可被 BackupFileReader 逻辑解读（结构/哈希/分页/引用/媒体全通过） | 生成器内置自校验（镜像 reader 全部关键检查），退出码 0 | 内联命令 #1 |
| 2 | 金样包确定性可复现 | 重新生成后逐字节相同 | 内联命令 #2（两次结果一致） |
| 3 | 恢复前后数据等价（payload 层） | 解包合并后 diff 金样期望（1/1/7 条记录与关键字段） | 内联命令 #3 |
| 4 | 坏包（翻转媒体字节）被拒且无残留语义可判定 | 破坏一个媒体字节后 --verify 必须非 0 | 内联命令 #4 |
| 5 | 坏包（删条目/篡改 manifest 版本/插入 `../` 路径）逐项被拒 | 同一 --verify 对 4 类变体断言非 0 | 内联命令 #5（含全部 4 个变体） |
| 6 | 媒体清单 vs ZIP 一致性与 mediaId 溯源规则 | --verify 已含清单一致性；另断言 mediaId 后缀 == `sha256(canonicalPath)[:24]`（canonical=`/data/data/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png`，镜像 BackupMediaCollector.kt:71-72） | 内联命令 #6 |
| 7 | iOS 恢复：金样 → DB 记录等价 | commonTest：恢复后查 operators/chat_sessions/chat_messages，逐字段 diff 金样期望 | `./gradlew :shared:iosSimulatorArm64Test --tests '*BackupRestoreGoldenTest*'`（用例在实现阶段新增） |
| 8 | iOS 恢复：媒体落盘 + URI 重写 | commonTest：断言 mediaId→文件存在且字节 sha256 一致；msg4/msg5 内容中的 file:// 被替换为落盘 URL | 同上用例（断言函数随实现落） |
| 9 | iOS 恢复失败无残留 | commonTest：注入 DB 插入失败（例如消息引用校验前破坏 payload）→ 断言 chat_messages/chat_sessions 计数与恢复前一致、媒体目录已删、设置回放 | 同上用例 |
| 10 | 重复导入幂等 | 连续恢复同一金样两次，断言第二次后 DB 内容与第一次等价（全量替换语义） | 同上用例 |

### 验收命令（内联，可直接运行；reviewer 已实跑 #3/#4 及 #5 的两个变体）

```bash
cd ios-port/golden/backup
# ---- #1 金样包全项自校验 ----
python3 make_minimal_backup.py --verify minimal-1.14.27.zip   # exit 0，6 组检查全 PASS

# ---- #2 确定性复现 ----
python3 make_minimal_backup.py
shasum -a 256 minimal-1.14.27.zip   # 期望 = 799b2ba06b4167d8245ba888c3db586bde8e24707f2a5fb7dd218c66ef5b5209（两次一致）

# ---- #3 恢复前后数据等价（payload 层）----
python3 - <<'EOF'
import zipfile, json
z = zipfile.ZipFile("minimal-1.14.27.zip")
def pages():
    m = json.loads(z.read("manifest.json"))
    return [json.loads(z.read(p["path"])) for p in m["pages"]]
ops=[o for p in pages() for o in (p["payload"]["content"]["operators"] or [])]
sess=[s for p in pages() for s in (p["payload"]["content"]["sessions"] or [])]
msgs=[m for p in pages() for m in (p["payload"]["content"]["messages"] or [])]
assert len(ops)==1 and len(sess)==1 and len(msgs)==7
assert sess[0]["id"]=="session_golden" and sess[0]["operatorId"]=="golden"
assert msgs[3]["type"]=="image" and "imageUri" in msgs[3]["content"]
assert msgs[4]["type"]=="ai_json" and "imageUri" in msgs[4]["content"]
print("payload diff PASS")
EOF

# ---- #4 坏包（媒体字节翻转）被拒 ----
python3 - <<'EOF'
import shutil, subprocess, zipfile
shutil.copy("minimal-1.14.27.zip", "/tmp/bad.zip")
zin = zipfile.ZipFile("/tmp/bad.zip")
entries = {n: zin.read(n) for n in zin.namelist()}
zin.close()
media = [n for n in entries if n.startswith("media/")][0]
b = bytearray(entries[media]); b[1] ^= 0xFF
entries[media] = bytes(b)
with zipfile.ZipFile("/tmp/bad.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for n, d in entries.items():
        z.writestr(n, d)
r = subprocess.run(["python3", "make_minimal_backup.py", "--verify", "/tmp/bad.zip"], capture_output=True)
assert r.returncode != 0, "坏包应被拒绝"
print("bad-media rejection PASS")
EOF

# ---- #5 坏包 4 变体 ----
python3 - <<'EOF'
import json, shutil, subprocess, zipfile
def rewrite(entries, out="/tmp/bad.zip"):
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for n, d in entries.items():
            z.writestr(n, d)
def load():
    zin = zipfile.ZipFile("minimal-1.14.27.zip")
    e = {n: zin.read(n) for n in zin.namelist()}
    zin.close()
    return e
def check(name):
    r = subprocess.run(["python3", "make_minimal_backup.py", "--verify", "/tmp/bad.zip"], capture_output=True)
    assert r.returncode != 0, name + " 应被拒绝"
    print(name + " rejection PASS")
# a) 缺 manifest.json
e = load(); del e["manifest.json"]; rewrite(e); check("missing-manifest")
# b) formatVersion 篡改为 3
e = load(); m = json.loads(e["manifest.json"].decode()); m["formatVersion"] = 3
e["manifest.json"] = json.dumps(m, ensure_ascii=False, separators=(",", ":")).encode(); rewrite(e); check("formatVersion=3")
# c) 不安全路径 ../ 条目
e = load(); e["data/pages/../evil.json"] = b"{}"; rewrite(e); check("unsafe-path")
# d) manifest.files 缺媒体项（清单与 ZIP 不一致）
e = load(); m = json.loads(e["manifest.json"].decode())
m["files"] = [f for f in m["files"] if not f["path"].startswith("media/")]
e["manifest.json"] = json.dumps(m, ensure_ascii=False, separators=(",", ":")).encode(); rewrite(e); check("files-missing-media")
EOF

# ---- #6 mediaId 溯源（镜像 BackupMediaCollector.kt:71-72）----
python3 - <<'EOF'
import hashlib, json, zipfile
z = zipfile.ZipFile("minimal-1.14.27.zip")
m = json.loads(z.read("manifest.json"))
media = [x for p in m["pages"] for x in json.loads(z.read(p["path"]))["payload"]["media"]][0]
canonical = "/data/data/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png"
assert media["mediaId"] == "local-image/" + hashlib.sha256(canonical.encode()).hexdigest()[:24]
assert media["archivePath"] == "media/images/" + hashlib.sha256(canonical.encode()).hexdigest()[:24] + ".png"
print("mediaId derivation PASS")
EOF
```

- 金样 diff 允许差异白名单：`createdAt/backupId/timestamp` 固定值 vs 真实导出的时间值（金样为确定性固定值）；`manifest.recordCounts` 只作展示不做校验（reader 不校验其值，仅 page recordCount 校验）；zip 压缩级别/条目时间戳不参与校验。
- 边界用例清单：空数据备份（0 角色/0 会话，媒体 0）、坏 zip（见 #4/#5）、缺媒体、版本不匹配（v1/v3）、磁盘满（实现期用可注入的空间检查模拟）、重复导入、中断恢复（恢复中途取消 → DB 无残留 + 输入暂存清理）。

## 风险与开放问题

1. **iOS 无 Android 的「恢复前安全备份」目录语义**（任务所称 oldBackup 语义）：Android 用 `filesDir/restore-safety-backups/before_restore_<ts>.rbackup`，保留 5 份，且 UI 可列出并再次导入（BackupRestoreCoordinator.kt:173-188；BackupRestoreScreen.kt:283-451）。**【lead 已裁决 2026-11-22】三层回滚方案已定：Phase 0 采用三层自动回滚（单事务回滚 + 媒体目录删除 + 设置快照回放，Android 同款语义）。仅存开放项：iOS 安全备份文件保留策略——Phase 0 不落盘 `before_restore_*.rbackup`，保留策略随 A5 完整实现。**
2. **绝对路径重写器在 iOS 无对应物**：`BackupRestorePayloadRewriter.androidPrivateFilesKey`（:48-58）依赖 `/data/data|/data/user/0/<pkg>/files/` 特征串与 `java.io.File` canonical 解析；iOS 不可用。**【lead 已裁决 2026-11-22】批准 iOS 采用 mediaId 直映射**：扫描对象不变（avatarUri / message content / settings / gift / archive JSON），替换表由 `payload.media[mediaId] → 本地 URL` 构建，不做路径特征串匹配；此差异记入平台差异白名单（行为契约 #9）。本金样已同时覆盖 `/data/user/0` 原样 URI 与 `/data/data` canonical 两种形态，作为 iOS 直映射的回归样本。
3. **mediaId 由路径派生而非内容派生**（BackupMediaCollector.kt:26,71-72）：同一图片在两个路径会重复进包。**【lead 已裁决 2026-11-22】mediaId 路径派生语义原样保留**：导入时 archivePath 文件名 = stableId 不变，落盘按 mediaId 建目录，不改 Android 语义。
4. **恢复后消息 id 分配器**：`max_used_message_id` 不进备份；**【lead 已裁决 2026-11-22】iOS 必须复刻 `getNextMessageId()` 的 max(内存, DB max+1, persisted) 自愈**（MessageRepository.kt:294-301），否则恢复后新消息撞 id。列为实现清单项。
5. **设置与 DB 不在同一事务**：Android 设置回滚是「快照回放」而非原子（BackupRestoreCoordinator.kt:99-111）。iOS 若用 NSUserDefaults 同款即可，但需在规格卡评审时确认接受该非原子性（与 Android 一致）。
6. **备份明文无加密**（fields.md §a3）：iOS 维持同语义（不加密 zip），密钥类设置不进备份白名单；iOS 侧密钥存 Keychain。
7. **平台差异白名单汇总**（实现时以此为准）：路径特征串重写 → mediaId 直映射（P0）；`java.io.File` canonical 解析 → 去掉（P0）；WorkManager 取消 → 无对应物，改为停自动化 actor（P1）；`StatFs` 磁盘诊断 → `URLResourceKey.volumeAvailableCapacityForImportantUsageKey` 预检（P2）；`DailyContentScheduler/GroupAutoChatScheduler` 重建 → Phase 0 最小闭环 no-op（P2）；**失败清理补强（P2，已采纳）**：任何失败（含媒体 staging 失败）都递归清理部分解出的 `restored_media/<backupId>`（Android 现状只删 restoreInput，Coordinator :125-129）——采纳理由：iOS 沙盒无副作用，且验收 #9「媒体目录已删」无条件成立。

## 评审结论

- reviewer：**PASS-WITH-FIXES（2026-11-22）**——0 个 P1，4 个 P2 + 2 个 P3，非阻塞；13 条契约 oracle 逐条复核通过，金样包与验收命令实跑通过。修正清单（`ios-port/handoffs/reviewer-phase0-batch1.md` §6）已全部落笔：
  1. [P2] 边界「磁盘满 / IO 失败」按事实改写（staging 失败 → :125-129 只删 restoreInput、残留为 Android 现状；DB 失败 → :108-111 删媒体目录+回放设置）；
  2. [P2] 数据与迁移 + 契约 #10 删除顺序改为严格按 :47-55（reply_turns → display_events → archives+history_segments → messages → sessions）；
  3. [P2] 风险 1 改为「已裁决」表述，仅保留「iOS 安全备份文件保留策略」为开放项；
  4. [P2] 验收 #2-#6 完整命令内联进规格卡（不再引用 handoff §4）；
  5. [P3] 验收 #6 补 mediaId 溯源断言（sha256(canonicalPath)[:24]）；
  6. [P3] golden md §3 行号按精确值校正（:36-40,238-244；:59-60,61-63）。
- lead：**批准（2026-11-22）**——裁决 a) mediaId 直映射替代 Android 路径特征串重写，记入平台差异白名单；b) Phase 0 三层自动回滚（单事务 + 媒体目录删除 + 设置快照回放），restore-safety-backups 文件策略随 A5；c) mediaId 路径派生语义原样保留 + 消息 id 分配器复刻 getNextMessageId() 自愈。实现前提：本修正落笔 + T11（Kotlin 2.3.21 升级）完成。
