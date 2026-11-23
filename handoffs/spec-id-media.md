# handoff: spec-id-media（T16）— MessageIdAllocator + MediaRepository 规格卡

- 代理：id-media-spec-card（T16，前一代崩溃后从零重做）
- 任务：task-16（P0-3，revision 3 认领 → 4）
- 基线：RhodesLink @ `ios-port`（tag `v1.14.27-android-baseline` @ `3642d2c`）；工作区根 `/Users/jackchen/Documents/deepseek-harness/default-workspace`
- 写域遵守：仅 `ios-port/{specs,handoffs}`；RhodesLink 只读

## 1. 产出物

| 文件 | 状态 |
|---|---|
| `ios-port/specs/message-id-media-spec.md` | ✅ 按模板 v1 填写完毕：21 条行为契约（A 分配器 11 条 + B 媒体 9 条）全带 oracle file:line；10 项可自动判定验收（含金样路径转换矩阵 9 行用例表）；风险与开放问题 7 项 |
| `ios-port/handoffs/spec-id-media.md` | ✅ 本文件 |

## 2. 方法

1. 认领 task-16（revision 3→4）后，先读模板 v1、T9 ios-data-base-spec（媒体目录契约）、T10 backup-restore-minimal-spec（对 getNextMessageId 复刻的依赖 + mediaId 直映射裁决）、audit/paths.md（P0 结论）、audit/fields.md（备份白名单）、golden/media/avatars.md（头像映射）。
2. 逐行读两个核心 oracle：`MessageRepository.kt` 全文（getNextMessageId :296-305、三入口抬内存高水位 :168/:194/:225、restoreMessage :243、persisted 防回退 doc :285-294、删除路径 :307-324）与 `BackupMediaCollector.kt` 全文（mediaId/archivePath/originalUri :24-30、stableId :71-72、ownedRoots :68-69、扫描面 :43-57）。
3. 补齐证据链：grep `getNextMessageId` 全部 44 处命中（26 处调用点 + 4 处定义/抬高 + AiSupportViewModel 独立计数器）；核对 ChatMessages.sq:40-41 getMaxId / :99-101 insertMessageIfAbsent；实测确认「`max_used_message_id` 不在 PortableSettings 白名单」（grep 0 命中）→ 恢复不清 persisted 的结论成立。
4. 清理策略用反向证据法确认：`deleteAudio` 唯一调用群 = 取消录音路径（ChatScreen.kt:383、GroupDetailScreen.kt:122、SleepModeScreen 6 处）；images/chat_images 文件无任何运行时删除点 → 「删除会话/角色不删文件、孤儿残留是 Android 现状」写入契约 18，iOS 同语义 + P2 GC 补强。
5. 媒体 ID 规则设计为「来源决定 ID 形状」：新增文件 `media/<uuid>.<ext>`、备份导入 `local-image/<sha24>`（原样保留）、内置头像数字串原样（不引入 avatar: 前缀）、远程 http(s)/data 透传 —— 与 paths.md §7 收口点、T10 裁决、T9 目录契约全部对齐。

## 3. 关键发现

1. **分配器自愈的真机制**：`getNextMessageId()` 每次分配都读三源 max（内存、DB MAX(id)+1、settings `max_used_message_id`+1）并写回 persisted（MessageRepository.kt:296-305）。恢复导入后**无需任何重置动作**：冷启动恢复靠 DB max 自愈，同进程热恢复靠内存高水位（残留值是安全方向，只会更大不会撞 id）。T10 恢复执行器完全不感知分配器。
2. **persisted 的存在理由**（MessageRepository.kt:285-289 doc comment）：删除最新行后 MAX(id)+1 会在重启后回退，新消息可能重用「reply_turns 行仍存在」的 id → 静默阻塞回复。iOS 必须复刻 persisted，否则验收 #4/#5 无法通过。
3. **idMutex 语义**：分配与插入是**两个独立锁区**（:296 vs :167），崩溃可跳号——ID 无稠密性要求，接受。iOS 接口 `allocate()` + `observeUsed(id)` 两个方法正好对应这两个锁区语义。
4. **Android 媒体删除现状**：运行时从不删图片文件（仅取消录音删 voice）；备份恢复成功不清理旧自有媒体目录、失败删 restored_media/<backupId>（BackupRestoreCoordinator.kt:108-111）。iOS 全部照搬 + 两处 P2 补强（失败清理已由 T10 采纳；孤儿 GC 默认关）。
5. **内置头像值域**：avatarUri 存数字串 `"N"`（1..147），渲染时运行时解析 drawable（AvatarImage.kt:31-34）——iOS 打包 147 个 PNG 后必须保持「编号字符串 → 资源」动态解析，无编译期资源表。
6. **task 描述与 T9 的落点口径冲突**：task-16 写「iOS Documents/media 目录」，但已批准的 T9 契约 11 是 **Application Support/media**（≈ Android filesDir，ios-data-base-spec.md 契约 2）。本卡按 T9 口径写，已列入开放问题 3 请 lead 确认。

## 4. Open questions（转 reviewer/lead；评审结论 2026-11-23 已回填）

评审结果：**PASS-WITH-FIXES（reviewer，2026-11-23）+ lead 批准（2026-11-23）**。裁决：① 落点 = Application Support/media（T9 口径胜出，Documents/media 废弃）；② 内置头像 = Bundle 资源按数字解析、不落 media 目录（具体打包形态与压缩由实现代理在 T1/T15 确认）；③ bg_* 保持 settings 动态键（不迁 DB）；④ T9 契约 11 avatar:x 示例文案由 lead 另行通知 T9 卡维护者修正。修正：[P2] 所有 `:shared:androidUnitTest` 已更正为 `:shared:testDebugUnitTest`。

遗留（非阻塞）：

1. ~~内置头像打包方案~~（已裁决：Bundle 资源按数字解析；asset catalog vs Bundle Resources 具体形态由实现代理确认）
2. ~~bg_* 存储形态~~（已裁决：保持 settings 动态键）
3. ~~落点口径~~（已裁决：Application Support/media）
4. ~~`avatar:N` 前缀~~（已裁决：不引入；T9 文案由 lead 通知维护者）
5. 孤儿媒体 GC（引用扫描）是否纳入 A5 范围、默认开还是关。

## 5. 验收命令（评审时可先跑 testDebugUnitTest 侧的对应项；commonTest 用例在实现期落地）

```bash
cd RhodesLink
./gradlew --no-daemon :shared:testDebugUnitTest   # 现有回归（实现期新增 allocator/media 用例后双跑）
./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "*MessageIdAllocatorTest*" --tests "*MediaRepositoryTest*"   # 实现期
```

金样路径矩阵 9 行用例表、并发 1000、恢复自检、目录清理全部落在规格卡验收表 #1-#10。
