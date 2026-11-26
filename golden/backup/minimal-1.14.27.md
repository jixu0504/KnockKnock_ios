# minimal-1.14.27.zip —— 最小金样备份包

- 生成器：`ios-port/golden/backup/make_minimal_backup.py`（可复跑、确定性输出）
- 产物：`ios-port/golden/backup/minimal-1.14.27.zip`
- 基线：RhodesLink Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`，分支 ios-port）
- 用途：Phase 0 Gate 0「Android 备份可恢复到 iOS」的最小验证载体；结构按 `BackupFileWriter` 逐字节规则构造，可被现有 `BackupFileReader` 逻辑解读（金样自校验已镜像其全部关键检查）。

## 1. 包内容

| ZIP 路径 | 说明 |
|---|---|
| `manifest.json` | 清单：format=`rhodes-backup`、formatVersion=2、scope=full、appVersion=`1.14.27`、schemaVersion=1、layout=`paged_payload`、mediaIncluded=true |
| `data/pages/roles/000000.json` | ROLES 页 0：1 个角色（`golden`）+ 媒体清单（`media` 随 index==0 的 ROLES 页携带） |
| `data/pages/chats/000000.json` | CHATS 页 0：1 个私聊会话（`session_golden`） |
| `data/pages/chats/010000.json` | CHATS 页 10000：7 条消息 |
| `media/images/8b9e940c3c9d6d39c4a1dd8c.png` | 1×1 PNG（70 字节，合法 PNG 魔数） |

数据内容：

- **角色** `golden`（金样演示员）：OperatorExport 全部 22 字段（`app/.../data/ExportPayload.kt:46-59`）。
- **会话** `session_golden`：id 规则 `"session_$operatorId"`（`shared/.../data/SessionRepository.kt:43`），SessionExport 12 字段（ExportPayload.kt:86-92）。
- **消息** 7 条（MessageExport 15 字段，ExportPayload.kt:102-109）：
  1. `id=1` 用户纯文本（type=`text`，isMe=true）
  2. `id=2` AI 回复（type=`ai_json`，OfflineModeResponse 形状：emotion/location/segments[dialogue, narration]）
  3. `id=3` 用户纯文本
  4. `id=4` 用户图片消息（type=`image`，content 为 `{"imageUri","caption","visionSummary"}` JSON，ChatViewModel.kt:1859-1863）
  5. `id=5` AI 回复（type=`ai_json`，segments 中一条 `{"type":"image","content":"[图片]","imageUri":...}` 内嵌图引用；MessageParser.kt:22 `ignoreUnknownKeys=true` 容忍未知键）
  6. `id=6` AI 纯文本（type=`text`，isMe=false）
  7. `id=7` 用户纯文本
- **媒体** 1 件：`mediaId=local-image/8b9e940c3c9d6d39c4a1dd8c`，`archivePath=media/images/8b9e940c3c9d6d39c4a1dd8c.png`，`mimeType=image/png`，`originalUri=file:///data/user/0/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png`。

## 2. 构造依据（file:line）

| 规则 | 位置 |
|---|---|
| 容器常量：FULL_FORMAT/formatVersion=2/MANIFEST_PATH/PAGES_ROOT | `app/src/main/java/com/rhodes/privatechat/data/backup/BackupFileWriter.kt:18-24` |
| 页路径 `data/pages/<category小写>/<6位序号>.json`；页内容 ≤16MB；manifest 最后写 | `BackupFileWriter.kt:49-58,96` |
| manifest 字段与顺序（format…pages） | `app/src/main/java/com/rhodes/privatechat/data/backup/BackupModels.kt:36-51` |
| 文件清单 `{path,size,sha256}`，逐条目 SHA-256（hex 小写） | `BackupModels.kt:69-73`；`BackupFileWriter.kt:56,63,139-141` |
| excludedCategories 固定四项 | `BackupFileWriter.kt:92` |
| 分页分配：operators→ROLES/0（media 随 index 0）；sessions→CHATS/0；messages→CHATS/10000 | `app/src/main/java/com/rhodes/privatechat/data/backup/BackupPagePlanner.kt:14-30` |
| 页 recordCount 公式（媒体不计入） | `BackupPagePlanner.kt` 与 `BackupFileReader.kt:123-130` |
| mediaId=`local-image/<sha256(canonicalPath) 前24hex>`；archivePath=`media/images/<stableId>.<ext>`；originalUri=file:// | `app/src/main/java/com/rhodes/privatechat/data/backup/BackupMediaCollector.kt:24-30,71-72` |
| 会话 id `session_<operatorId>` | `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/data/SessionRepository.kt:43` |
| 图片消息 content JSON `{"imageUri","caption","visionSummary"}` | `app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:1859-1863,1937-1941` |
| applicationId（originalUri 包名） | `app/build.gradle.kts:29`（`com.buzidongshouhuoji.privatechat`） |

构造细节约定（有意为之，均不影响 BackupFileReader 解读）：

- JSON 字段顺序与各 data class 声明顺序一致，且按 `encodeDefaults=true`（BackupFileWriter.kt:27、BackupPagePlanner.kt:11）全量写出默认/空字段——与 Android 实际输出形态一致。
- `createdAt`/`timestamp`/`backupId`/zip 条目时间为固定值（确定性输出）；reader 不校验时间值，仅校验结构、大小与哈希。
- `originalUri` 使用 `file:///data/user/0/...` 形式（`Uri.fromFile` 原样路径），而 `mediaId` 的 stableId 按**canonical** 路径 `/data/data/...` 计算——复刻真机上 `/data/user/0 → /data/data` 符号链接下 `BackupMediaCollector.kt:64,71-72` 的实际行为。这同时覆盖了 `BackupRestorePayloadRewriter.kt:10-15,48-58` 的 canonical/Android 私有路径双形态匹配分支，是 iOS 路径重写讨论（见规格卡「风险与开放问题」）的最小复现载体。

## 3. 校验通过记录

生成器内置自校验（`make_minimal_backup.py --verify minimal-1.14.27.zip`），镜像 `BackupFileReader.kt` 关键检查：

| 检查 | 对应 oracle | 结果 |
|---|---|---|
| 安全路径（无 `..`/绝对/`:/`/空段）且无重复路径 | BackupFileReader.kt:36-40,238-244 | PASS（5 条目） |
| manifest 可解析；format=`rhodes-backup`、formatVersion=2、scope=full | BackupFileReader.kt:59-60,61-63 | PASS |
| manifest.files 与 ZIP 内容严格一致（除去 manifest.json 自身） | BackupFileReader.kt:64-74 | PASS |
| 逐条目 size + SHA-256 全匹配 | BackupFileReader.kt:70-74 | PASS（4 文件） |
| layout=`paged_payload`；无 `data/backup.json`；分页清单/路径/索引/recordCount 全一致 | BackupFileReader.kt:102-121 | PASS（3 页） |
| 每页 payload 均为 `type=full_backup` | BackupFileReader.kt:90,102-121 | PASS |
| 角色/会话/消息 id 唯一；私聊会话与消息引用完整 | BackupFileReader.kt:143-171 | PASS（1+1+7） |
| mediaIncluded 与媒体清单一致；archivePath/mediaId 唯一且与 ZIP `media/` 条目一致；mediaId 与 archivePath 文件名一致 | BackupFileReader.kt:91-98；BackupMediaStager.kt:26-28 | PASS（1 件） |
| 消息内 `"imageUri"` 引用可被收集器正则发现 | BackupMediaCollector.kt:86 | PASS（1 个不同 URI，2 处出现） |

SHA-256（生成时记录，可用 `unzip -p … | shasum -a 256` 复核）：

```
data/pages/roles/000000.json           size=1349  sha256=b4e12fba1f2cd2146bb7c6e7c27fd89eb5e17d771d37619602dea75865cf9f25
data/pages/chats/000000.json           size=866   sha256=0e2aeea8084009898bdb8f3243090d2d65499e0226dbe17f390ca4be86b77b04
data/pages/chats/010000.json           size=3383  sha256=71cb513309de5f0d7151d2216e95bc4329f88ea57149dbd2c76a6586aafd9133
media/images/8b9e940c3c9d6d39c4a1dd8c.png  size=70    sha256=c414cd0e204de974f73753c7e28d7638e7b3691bb8b1a2bab6b25bb7fed7ce77
manifest.json                          size=1417  sha256=3a70784363830e6a839503aa911f288914ca0423ca870ad39086eb926a0dbbf0
```

## 4. 复跑

```bash
cd ios-port/golden/backup
python3 make_minimal_backup.py                          # 重新生成 + 自校验（确定性，逐字节相同）
python3 make_minimal_backup.py --verify minimal-1.14.27.zip   # 仅校验
```

注意：本脚本只写 `ios-port/golden/backup/`；不读取也不修改 RhodesLink 仓库任何文件（所有 oracle 均为引用式 line 注释）。
