# handoff：impl-id-media（MessageIdAllocator + MediaRepository，task-17 / T17）

> 代理：impl-id-media ｜ 任务：task-17 ｜ 状态：**完成（双路径 17/17 测试全绿）**
> 规格卡：`ios-port/specs/message-id-media-spec.md`（已批准，21 条契约 + 验收 #1-#10）
> 交付：`RhodesLink/shared/src/commonMain/.../shared/domain/{data,media}/`（新增包）、
> `shared/src/iosMain/.../shared/files/MediaStoreIos.kt`（最小扩展，不动 T9 MediaDirectory 公开行为）、
> `shared/src/commonTest + androidUnitTest + iosTest` 三源集测试（17 个用例 × 2 路径）、本文件。
> git 未提交（lead 统一提交）。

## 1. 交付物清单（实现清单 file:line）

| 路径 | 说明 |
|---|---|
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/data/MessageIdAllocator.kt:1-116` | 分配器核心：`allocate()` :65-76（oracle MessageRepository.kt:296-305 逐行等价）、`observeUsed(id)` :80-87、`rememberMaxUsedMessageId` :89-92（oracle :292-294）、`KEY_MAX_USED_MESSAGE_ID="max_used_message_id"` :96；接口 `MessageMaxIdSource` :100-110、`MaxIdStore` :113-122、`DatabaseMessageMaxIdSource` :125-127（ChatMessages.sq:40-41）、`SettingsMaxIdStore` :130-136（settings=null → 0，oracle :290） |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/media/MediaId.kt:1-37` | 媒体 ID 校验（契约 12/17 边界）：拒绝空/`..`/绝对路径/`://`；合法形态 media/\<uuid\>.\<ext\>、local-image/\<sha24\>、数字串、远程透传 |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/media/MediaSource.kt:1-24` | 来源枚举 PICKER/CAMERA/REMOTE/BUILTIN_AVATAR/BACKUP_IMPORT/VOICE（契约 13 目录映射注释） |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/media/MediaStore.kt:1-66` | 平台落盘接口（ensureDirectories/readSourceBytes/writeBytes/readBytes/exists/delete/fileUrl/deleteAbsolutePath）+ `MediaStoreProvider` 注入点 :54-66 |
| `shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/domain/media/MediaRepository.kt:1-210` | `ingest` :38-64（失败回退原引用+清残，镜像 ImageUtils.kt:24-32）、`resolve` :71-86（缺失/非法/builtinAvatar/local-image → nil）、`deleteAudio` :93-108（oracle LocalAudioController.kt:140-142）、`backupMediaIdFor` :113-115 / `backupArchivePathFor` :119-123（BackupMediaCollector.kt:26-27,71-72 公式复刻）、`isRemoteReference` :126-128（MainActivity.kt:197-198）、`isBuiltinAvatar` :131-132（AvatarImage.kt:31-34）、`ownedRoots` :135（契约 15 白名单根）、UUID v4 生成 :199-209 |
| `shared/src/iosMain/kotlin/com/rhodes/privatechat/shared/files/MediaStoreIos.kt:1-56` | 生产 MediaStore：Application Support/media 落点（复用 T9 MediaDirectory/FileIo，未改其公开行为）；writeBytes 空字节防御 :26-31（见 §4 遗留 3） |
| `shared/src/commonTest/.../domain/data/TestAllocatorEnv.kt` + `MessageIdAllocatorTest.kt:1-185` | 分配器 8 用例（T10 平台子类模式） |
| `shared/src/commonTest/.../domain/media/TestDirMediaStore.kt`、`InMemoryMediaStore.kt`、`MediaPathGoldenMatrixTest.kt:1-123`（9 行矩阵+公式）、`MediaRepositoryTest.kt:1-160`（7 用例） | 媒体测试（矩阵用内存 store 平台无关；清理/幂等/往返用真实文件系统） |
| `shared/src/androidUnitTest/.../domain/{data,media}/` | JVM 基建：JdbcSqliteDriver + InMemoryObservableSettings；`JvmTempDirMediaStore`（java.io 临时目录） |
| `shared/src/iosTest/.../domain/{data,media}/` | iOS 基建：NativeSqliteDriver + NSUserDefaults suite；`IosTempDirMediaStore`（NSTemporaryDirectory，复用 FileIo/MediaDirectory.ensureDirectory） |

## 2. 测试结果（双路径实测）

| 路径 | 命令 | 结果 |
|---|---|---|
| JVM | `./gradlew --no-daemon :shared:testDebugUnitTest` | 156 tests，本卡 17/17 绿（MessageIdAllocatorTestJvm 8 + MediaPathGoldenMatrixTest 2 + MediaRepositoryTestJvm 7）；唯一失败为 T18 金样字节差（已通知 impl-prompt），与 T9 网络测试无关 |
| iOS | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "com.rhodes.privatechat.shared.domain.*"` | **BUILD SUCCESSFUL**，17/17 绿（MessageIdAllocatorTestIos 8 + 矩阵 2 + MediaRepositoryTestIos 7，iPhone 17 模拟器） |

验收逐项：#1 金样 9 行矩阵 ✓（`goldenMatrix_allNineRows`）；#2 备份公式 ✓（`backupFormulas_matchAndroidBackupMediaCollector`，sha256 前缀 24 + archivePath 拼接）；#3 并发 1000 ✓（`concurrent1000_allocationsAreUniqueAndStrictlyIncreasing`，set=1000、max-min=999，双路径）；#4 删除不回退 ✓（`noRewindAfterDelete_withPersistedRetainedAcrossInstances`，allocate(6) 断言）；#5 恢复自愈 ✓（冷开 1000 / 热恢复 5001 两场景）；#6 observeUsed ✓；#7 金样恢复后 allocate==8 ✓（复用 T10 BackupRestoreCoordinator + GoldenBackupFixture.zipBytes）；#8 目录清理 ✓（不可读/空源无残留、deleteAudio 消失、幂等创建，双路径真实文件系统）；#9 5 非法 ID 拒绝 ✓；#10 resolve 往返 sha256 ✓。性能：并发 1000 用例在套件内远小于 2s（未单测计时，真机数据按卡留 Phase 2）。

## 3. 与 oracle 的差异记录（全部写入白名单/裁决范围内）

1. **observeUsed 同时持久化**（:80-87）：覆盖 oracle :168/:194/:225（仅抬内存）与 :243-244（抬内存+持久化）的并集——发送路径比 Android 更早写 persisted，方向保守，不改变任何 allocate 结果。
2. **MediaId 是普通不可变类而非 value class**：@JvmInline 在 K/N 不存在、无注解值类在 JVM target 是编译 error、expect/actual 值类需写 androidMain/iosMain 的 domain/media 包（超本波写域）。语义等价（私有构造+parse 校验），后续波可迁 expect/actual 值类（MediaId.kt 注释已记）。
3. **camera 的 1920 缩放 JPEG 留在调用方**：ingest(CAMERA) 做字节级复制落 chat_images/；缩放/压缩（MainActivity.kt:178-193 语义）由 T15/A1 调用层在 ingest 前完成——本波无 common 图像解码依赖，卡面亦未要求引入。
4. **resolve 的 ID 不含 category 前缀**：契约 12 的 `media/<uuid>.<ext>` 形态与契约 13 落点分离，resolve 按扩展名优先序搜索（音频扩展先 voice/，否则 images→chat_images→voice→newspapers）；UUID 唯一性保证跨目录不撞名。`local-image/<sha24>` 经运行时 resolve 返回 nil（恢复管线 mediaId 直映射已重写为落盘 URL，契约 14/备份格式 ID 不进运行时）。
5. **deleteAudio 的绝对路径分支仅限 media 根内**（Android File.delete 无此限制）：iOS 收窄为根内删除，越界返回 false——restored_media 引用在根内不受影响。
6. **allocator 无 DatabaseDispatcher 跳转**：iOS SQLDelight 同步执行，分配器不自带线程切换（oracle :297 的 DatabaseDispatcher.execute 是 Android 线程模型）；T15 调用方自行决定 dispatcher。

## 4. 遗留（app 调用点改造清单，交后续波）

1. `MessageRepository.getNextMessageId()`（shared/data/MessageRepository.kt:296-305）**未改委托**：本波写域不含该文件，且改之牵动 Android 26 处调用点（规格卡风险 6）。T15 聊天引擎接 `MessageIdAllocator.allocate/observeUsed` 时再切，签名语义不变。
2. Android/iOS app 层调用点改造（契约 19 表 + 卡 §数据与迁移「Android 调用点改动面」）：发送侧图片消息（ChatViewModel.kt:1858-1860）、头像/背景保存（ProfileSettingsScreen/OperatorEditScreen/GroupEditScreen/GroupDetailScreen/ChatScreen）、语音写入渲染（LocalAudioController/VoiceCallScreen）、渲染侧（AvatarImage.kt:46-52、ChatScreen.kt:2749）、备份侧（BackupMediaCollector 收集、BackupContentFilter 清空）——全部经 MediaRepository 收口，禁直拼路径。
3. T9 已知问题顺带记档：`security/KeychainStore.ios.kt:218-220` 的 `ByteArray.toNSData` 对空数组 `addressOf(0)` 抛 AIOOBE（toByteArray :225 已有空保护）——本卡在 MediaStoreIos.writeBytes 加空字节防御绕过；建议 T9 卡维护者修 toNSData（写域归属 security/）。
4. iOS 平台入口接线：`MediaStoreProvider.register(MediaStoreIos())`（T1/T15 阶段）；Android 侧未来迁移同 provider 注入。
5. 孤儿媒体 GC（契约 18 P2 补强，默认关闭）与 ingest 源大小上限（>100MB 拒绝/流式，边界清单）随 A5。

## 5. open questions

1. `restored_media/<backupId>` 多备份并存时 resolve 不处理 local-image ID（见 §3.4）——若未来 UI 需要直接渲染「备份内媒体 ID」而非重写后的 URL，需给 MediaStore 加目录列举能力（本卡刻意不加，避免 interop 面扩大）。
2. ingest(BACKUP_IMPORT, ref, backupId=null) 按失败语义返回原引用；backupId 仅允许非空无 `..`/`/`——T10 的 backupId 来源若含特殊字符需在调用层先净化。
3. 分配器性能门槛（单次 <5ms、1000 次 <2s）在模拟器/JVM 主机侧套件内明显满足，未做独立基准测试；是否值得 Phase 2 建 perf gate 由 lead 裁量。

## 6. 构建纪律记录

- 全程 `--no-daemon`；每次 gradle 前 pgrep 检查；与 T18 两轮互卡（PromptClock.kt:26 `Month.number` → 建议 `monthNumber`；PromptRender.kt putIfAbsent → getOrPut）均通过 send_message 协调解决，未越写域改对方文件。
- build-fix 轮次：JVM 1 轮（JUnit void 断言）+ iOS 2 轮（@JvmInline 移除、ExperimentalForeignApi opt-in、空文件 AIOOBE 防御）= 3 轮，在 3 轮上限内完成。
