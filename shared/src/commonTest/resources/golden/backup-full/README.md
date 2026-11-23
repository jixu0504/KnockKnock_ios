# golden/backup-full — 备份 v2「全类目」金样（T31）

> 规格卡：`ios-port/specs/backup-full-restore-spec.md`（T31，A5 全类目字段级重放）。
> 行为 oracle：Android 1.14.27（tag `v1.14.27-android-baseline` @ `3642d2c`）—— 本目录只读引用其导出/读取规则，不改 Android 源码。
> 与 T10 `golden/backup/minimal-1.14.27.zip`、T30 `golden/chat-import/` 的关系见 §4。

## 1. 包清单与冻结 SHA-256（勿手改；改金样必须同改本表 + `sync_golden.sh` + 规格卡）

| 包 | 规模 | 覆盖点 | SHA-256 |
|---|---|---|---|
| `full-all-v2.zip` | 2 角色/3 会话/12 消息/4 displayEvents/2 存档/1 历史/2 关系/3 记忆/3 锚点/3 memoryItems/2 书 3 块 2 关联/2 动态/3 评论/2 赞/2 日记/2 礼物/2 派遣/1 麻将/2 共同经历 3 参与者/全量 settings/3 媒体 | **7 类目全字段主金样** | `d9ed1a9513b6b72939d08a0c354a76c72352801b90c413f4e16d12c13a06bbb7` |
| `full-memories-v2.zip` | 3 记忆（3 层 + 全局 daily）/6 锚点（6 类型）/4 memoryItems（L1/L2/L3 + MOMENT/MOMENT_COMMENT 源）/2 角色 2 会话 4 消息/1 孤儿记忆 + 1 孤儿锚点 | memories 三层 + 锚点 + 向量重置 + ORPHAN 路径 | `2fe8d76fdece9915e0b47b9f8569d9982b104625ba5ba4f57516a52019388143` |
| `full-social-v2.zip` | 1 角色/2 动态/4 评论（含嵌套 + 1 孤儿）/3 赞（1 孤儿）/2 日记/2 共同经历 + 3 参与者（1 孤儿） | social + 引用重映射 + ORPHAN 路径 | `561954730942fb9a411f3f77bb36379a6f554b03c4b1e31d98c5acdbb4094f4e` |
| `full-knowledge-v2.zip` | 2 角色/1 书/2 切块/2 关联 | KB 三表 + pending 归一 | `a99207f63442c54320471df5d10e49952de42209746257bbaf6a342c8c41ba6a` |
| `full-extras-v2.zip` | 1 角色/1 会话/2 消息/2 displayEvents/1 存档/1 历史/2 礼物/2 派遣（active+finished）/1 麻将 | extras + chats 附属 + dispatch status 归一 | `5de57cc882eec680262ffffa9aead0c43f15292a04ae1416a3d4d5918314e551` |
| `full-media-v2.zip` | 1 角色（头像媒体）/1 会话（头像媒体）/2 消息（image + ai_json 内嵌图）/1 礼物（图）/1 存档 + 1 历史（内嵌图 URI）/settings `user_avatar_uri`+`support_conversation`/4 媒体 | 媒体重写全扫描域 | `e4bdfc060f824ab9689fc258b73686d0f25a137979b011417953e090821c507d` |
| `full-empty-v2.zip` | 0 记录（`settings={}`，仅 SETTINGS/0 页） | 空包 = 清空选中类目 | `6cfc6553cf60c85affa07eb91b6b7b4d63fd7a0a8cdae16476ec61aa581a7180` |
| `full-v1-single.zip` | 全类目各 1-2 条；`formatVersion=1`、`layout="single_payload"`、`data/backup.json` | v1 单 payload 全类目兼容恢复 | `d1cf1f70c239c5804f121146eaae30d395be25e3a899ecac0cd47eafcc5e6c32` |

## 2. 生成与自校验

```bash
cd ios-port/golden/backup-full
python3 make_full_backups.py                 # 重新生成 8 包 + 逐包自校验 + 打印 SHA-256
python3 make_full_backups.py --verify full-all-v2.zip   # 单包校验
python3 make_full_backups.py --verify                    # 8 包全校验
```

- 确定性：固定 `createdAt/backupId/timestamp/zip 条目时间`；两次运行逐字节一致；`--verify` 会把磁盘包与内存重生成字节逐字节比较（防手改/生成器漂移）。
- 自校验镜像 Android `BackupFileReader`：路径安全/唯一、manifest 三元组、`files` 集合 == ZIP−manifest、逐条目 size+sha256、v2 分页清单/标识/recordCount 公式（`BackupFileReader.kt:123-130`）、引用完整性（`:132-207`）、媒体清单 == ZIP `media/`、mediaId 路径派生、`recordCounts` 17 键、类目覆盖、预期孤儿 issue 数。
- 构造依据（全部只读）：容器/写入序/recordCounts `BackupFileWriter.kt:46-98,101-131`；分页规则 `BackupPagePlanner.kt:14-83`；manifest/payload 模型 `BackupModels.kt:36-98`；载荷字段序 `ExportPayload.kt:22-43`；便携设置白名单 `PortableSettings.kt:8-77`；mediaId 路径派生 `BackupMediaCollector.kt:24-30,71-72`。

## 3. 格式冻结值

- 容器：`manifest.json` + `data/pages/<category>/<pageIndex:06d>.json` + `media/images/<sha256(canonicalPath)[:24]>.png`；manifest 最后写。
- manifest：`format="rhodes-backup"`、`formatVersion=2`（`full-v1-single.zip` 为 1）、`scope="full"`、`appVersion="1.14.27"`、`schemaVersion=1`、`layout="paged_payload"`、`excludedCategories=["credentials","vector_index","worker_state","temporary_files"]`。
- 分页 pageIndex 基址（镜像 `BackupPagePlanner.kt`）：ROLES 0/10000(/90000 仅媒体无角色时)；CHATS 0/10000/20000/30000/40000；MEMORIES 0/10000/20000；SOCIAL 0/10000/20000/30000/40000/50000；KNOWLEDGE_BASES 0/10000/20000；EXTRAS 0/10000/20000；SETTINGS 0。
- JSON：键序 = data class 声明序；`encodeDefaults=true` 全量写出（含 null）；紧凑分隔符。
- 媒体：`mediaId="local-image/"+sha256(canonicalPath)[:24]`、`archivePath="media/images/<same24>.png"`、`originalUri=file:///data/user/0/<pkg>/files/images/<name>`、canonical=`/data/data/<pkg>/files/images/<name>`（T10 裁决 3 路径派生语义保留）。
- 每包预期孤儿 issue（读取器 5 类，T31 规格卡 C10）：
  - `full-memories-v2.zip`：`ORPHAN_MEMORY:1`、`ORPHAN_ANCHOR:1`；
  - `full-social-v2.zip`：`ORPHAN_MOMENT_COMMENT:1`、`ORPHAN_MOMENT_LIKE:1`、`ORPHAN_SHARED_EXPERIENCE_PARTICIPANT:1`；
  - 其余包：无孤儿。

## 4. 与 T10 / T30 的关系

| 目录 | 容器 | 覆盖 | 关系 |
|---|---|---|---|
| `golden/backup/`（T10） | `rhodes-backup` v2 分页 | ROLES/CHATS 最小页（1 角色/1 会话/7 消息/1 媒体） | 同容器；本目录补齐 MEMORIES/SOCIAL/KNOWLEDGE_BASES/EXTRAS/SETTINGS 全部页型 + v1 单 payload 全类目包 |
| `golden/chat-import/`（T30） | `rhodes-chat` v1 单 payload | 单会话聊天导出 | 独立容器（内容派生 mediaId），与本目录互不替代；T10 reader 对聊天包拒绝（T30 验收 #6） |
| `golden/backup-full/`（T31） | `rhodes-backup` v2 分页 + 1 个 v1 | 7 类目 22 恢复表 + 6 重置表的全字段 | 本目录；同 v2 容器规则、同 mediaId 路径派生、同分页 pageIndex 规则 |

## 5. 测试资源同步

```bash
bash ios-port/golden/backup-full/sync_golden.sh              # 校验冻结哈希 + 生成器自校验 + 同步 + 跑双路径单测
bash ios-port/golden/backup-full/sync_golden.sh --sync-only  # 只校验 + 同步
```

同步目标：`RhodesLink/shared/src/commonTest/resources/golden/backup-full/`（JVM classpath 与 iOS test bundle 共用）与 `RhodesLink/shared/src/androidUnitTest/resources/golden/backup-full/`（JVM classpath 兜底）；同时写入 `SHA256SUMS` 与 README 副本。测试侧 `FullBackupGoldens.FROZEN_SHA256` 与 §1 表一致，加载时断言。

## 6. 维护纪律

1. 改金样数据/格式 → 必须重跑生成器 → 更新 §1 表 + `sync_golden.sh` 的 `EXPECTED_HASHES` + 测试冻结表 + 规格卡对应条目。
2. 不允许手改 ZIP；`--verify` 的确定性逐字节比较会失败。
3. 生成器/zip 库版本变化可能导致压缩字节漂移（同一 python3/zlib 环境下稳定）；如确需升级，整表重冻结并说明。
