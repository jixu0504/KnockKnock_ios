# 规格卡：ios-data-base（iOS 数据底座最小闭环）

> 模板：ios-port/specs/规格卡模板-v1.md ｜ 任务：task-9（T9）｜ 状态：**已完成（Phase 0 验证版）**
> 范围：Phase 0 第 2 批——SQLDelight 建库迁移 / Ktor Darwin 流式 / Keychain / 设置 JSON 原子写 / 媒体目录。Phase 2 正式实现前的最小闭环验证。

## 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | iOS 数据底座最小闭环（DB / 网络流式 / 安全存储 / 设置持久化 / 媒体目录） |
| 所属阶段 | Phase 0（验证）；正式实现在 Phase 2 |
| 范围映射 | 全部为 Phase 0 验证件；正式件归属 Phase 2（MediaRepository / 正式 Keychain 策略 / 备份管线衔接） |
| 对应 Android 源码 | `shared/src/commonMain/sqldelight/…/db/*.sq`（23 个迁移 1..23.sqm）；`shared/src/commonMain/kotlin/…/network/HttpClient.kt`（createHttpClient、TRANSPORT_TIMEOUT_MS）；Android Keychain 无对应物（**不照搬 EncryptedSharedPreferences**，见可行性报告 §5.5）；`app/util/ImageUtils.kt:15`（filesDir/images，路径规则 oracle 见 ios-port/audit/paths.md §4） |
| 依赖模块 | shared/commonMain 的 DB 生成代码（RhodesDatabase）、ktor-client-darwin、sqldelight native-driver、kotlinx-serialization-json；媒体 ID 规则与 backup-restore-loop（T10）的 mediaId 字段对齐 |

## 输入 / 输出 / 边界

- **输入**：SQLDelight Schema（版本 23）+ 显式 db 路径；HTTP 流式响应（text/event-stream）；Keychain 键值（service + account + ByteArray）；JsonObject 设置文档；相对媒体 ID 字符串。
- **输出**：SqlDriver/RhodesDatabase 实例（fresh 冷开 28 张表、user_version=24；升级路径补迁移专属表）；流字节片段/完整文本；KeychainResult（Success/Failure+错误码）；完整 JSON 文件（无半文件）；media 目录树 + 绝对文件路径。
- **边界**：
  - 迁移：fresh 冷开不跑迁移（SQLDelight 语义）；升级路径逐段执行，18→24 段已验证。
  - 流式超时：首 token 由**业务层**超时逻辑负责（本模块仅验证传输层可取消性）；Ktor HttpTimeout 为兜底（commonMain 已设 TRANSPORT_TIMEOUT_MS）。
  - Keychain：模拟器无 entitlement 可用 generic password；真机需评估 kSecAttrAccessible 分级与锁屏重试（InteractionNotAllowed）。
  - 媒体 ID：拒绝空/`..`/绝对路径/`://`；category 前缀白名单映射子目录；未知前缀落 media/ 根。
  - 设置 JSON：读失败一律回退空对象；写失败返回 false 且不残留 tmp。

## 行为契约（逐条，每条带 Android oracle 位置）

1. **建库+迁移**：`DatabaseDriverFactory().createDriver(path)`（sqliter basePath 方案）打开：fresh 冷开建 28 张 .sq 表、user_version=24（=Schema.version，23 迁移+1）；旧库升级路径执行迁移（18→24 实测：data_repair_runs 创建、sourceType 列恢复）。Android oracle：同 Schema 在 Android 上建库行为（表集相同、版本口径相同）。
2. **DB 路径**：iOS 用 Application Support 绝对路径（≈ Android filesDir）；Android oracle：`context.filesDir` 下 `rhodes.db`。**平台差异白名单**：T1 骨架的相对路径在 iOS 会落到进程 CWD（真机不可写），必须绝对路径——已修。
3. **流式首 token**：首字节在服务器发送后即到达（loopback 无 ATS 拦截，实测豁免成立）。
4. **流中途取消**：协程 cancel 后传输立即终止（cancel+join < 1s，服务器侧见断连）。
5. **首 token 超时**：服务器 3s 才发数据、客户端 1.5s withTimeout → 客户端侧中止（非等服务器）；Android oracle：chat 深度思考预算 180s/210s（`SettingsRepository` 超时逻辑），本模块只验证机制。
6. **静默/响应阶段超时**：已回响应头但 30s 无 body、以及连响应头都不回（5s）两类场景，客户端分别按阈值中止。
7. **半包断连**：服务器发半包即关 → 客户端收到半包后正常 EOF，不挂起不崩溃。
8. **错误端点**：HTTP 500 + JSON body 原样透传（status/body 可判）。
9. **Keychain CRUD**：set（upsert）→ get 一致 → 覆盖 → delete 幂等；缺失 → NotFound（errSecItemNotFound=-25300）；错误码映射表见 KeychainError。
10. **设置 JSON 原子写**：写 tmp → replaceItemAtURL 原子替换（失败退化 remove+move）；任何时刻无半文件、无 tmp 残留；坏 JSON/截断/缺失 → 空对象。
11. **媒体目录**：Application Support/media/{images,voice,chat_images,newspapers} 幂等创建；相对 ID 校验/映射（如 `voice:x` → `voice/voice_x`；**内置头像数字 id 由 Bundle 资源解析，不落 media 目录**——按 T16 裁决口径）。

## 数据与迁移

- 表/字段：28 张 .sq 表（字段口径与 golden/db/schema 一致，未做 iOS 侧字段变更）。
- 迁移版本：1..23.sqm 全部保留，无 iOS 专属迁移。**SQLDelight 语义（生成代码实证）**：fresh 冷开走 `Schema.create()`（只建 28 张当前表，不执行迁移），user_version 直接盖 Schema.version=**24**（23 迁移+1）；迁移只在旧库升级路径执行。`data_repair_runs` 仅由迁移 18 在 ≤v18 旧库升级时创建（fresh 安装无此表，Android 同语义）——升级 18→24 实测通过（data_repair_runs 出现、sourceType 列恢复、user_version 18→24）。
- 备份/恢复：媒体 ID 化规则与 paths.md §0 P0 结论一致（file:// 绝对路径 → 相对 ID）；T10 备份管线的 mediaId 与此对齐；Keychain 数据不进备份，Phase 2 需定义导出策略。

## 验收（全部自动判定，28/28 通过）

| # | 验收项 | 判定方式 | 判定脚本或命令 |
|---|---|---|---|
| 1 | fresh 冷开 28 表 + user_version=24（23 迁移+1 版本口径） | iosSimulatorArm64Test `DatabaseMigrationTest.coldOpenCreatesCurrentSchemaAndStampsVersion24` | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "com.rhodes.privatechat.shared.db.*"` |
| 2 | 升级路径迁移实跑（18→24：data_repair_runs、sourceType、user_version） | `DatabaseMigrationTest.upgradePathRunsPendingMigrationsAndCreatesMigrationOnlyTable` | 同上 |
| 3 | 冷启动建库耗时记录 | 测试打印 `IOS-DB cold-open-and-create: 0ms, db-file=4096 bytes`（模拟器主机侧，<1ms） | 同上 |
| 4 | 流式 a-h 全场景（首 token 114ms / 取消 1ms+服务器 BrokenPipe / 超时 1508/2008/1506ms / 半包断连 / 500 / 自定义端点） | `KtorDarwinStreamingTest` 8 用例 | `./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "com.rhodes.privatechat.shared.network.*"`（前置：`python3 ios-port/handoffs/mock-sse-server.py`） |
| 5 | Keychain 错误码映射 + plumbing（真实 OSStatus 往返；CRUD 受 standalone 运行器限制降级记录型通过） | `KeychainStoreTest` 6 用例 | `--tests "com.rhodes.privatechat.shared.security.*"` |
| 6 | 设置 JSON 往返/坏数据回退/原子写 | `JsonSettingsStoreTest` 6 用例 | `--tests "com.rhodes.privatechat.shared.settings.*"` |
| 7 | 媒体目录创建/ID 规则/清理 | `MediaDirectoryTest` 5 用例 | `--tests "com.rhodes.privatechat.shared.files.*"` |
| 8 | framework 链接（iosArm64 + iosSimulatorArm64） | 两 target linkDebugFramework BUILD SUCCESSFUL | `./gradlew --no-daemon :shared:linkDebugFrameworkIosArm64 :shared:linkDebugFrameworkIosSimulatorArm64` |

- 金样：无（Phase 0 验证件）；表集以 golden/db/schema 静态核对（28 张 .sq 表名）。
- 性能：模拟器主机侧值（0ms 量级），真机数据 Phase 2 补；取消 join 1ms <1s；首 token 114ms（服务器 100ms 发送）；三个超时场景阈值误差 ≤8ms。
- 边界用例清单：空数据（空 JSON/缺失文件）、坏 JSON、截断 JSON、tmp 残留、重复删除、64KB 二进制、50 次连续原子写、`..`/绝对路径/file:// 拒绝。

## 风险与开放问题

1. **K/N 2.3.21 新 interop 断裂风险**：NSString 不再自动映射 Kotlin String、NSFileManager 方法变成员、`CFTypeRefVar`/`JsonElementSerializer` 不可引用、`Pinned.addressOf` 需显式导入——已全部适配并写入代码注释；升级 K/N 版本时需复测。
2. **Keychain 真机行为未验证**：模拟器通过 ≠ 真机通过（entitlement、锁屏、kSecAttrAccessible 分级待 Phase 2）。
3. **模拟器测试运行器**：KGP 默认设备类型不存在时需显式 `deviceId`（已配置 "iPhone 17"）；standalone 模式对 Application Support 目录解析与真机 app 沙箱不完全一致（测试打印实际路径供核对）。
4. **备份代理的 iosMain 文件**：BackupMediaSinkIos.kt 按新 interop 修正（成员调用 + security.toNSData），需 T10 确认语义未变。
5. 待 lead 裁决：设置 JSON 的默认路径（Application Support/settings.json vs NSUserDefaults 混合策略）、Keychain service 常量命名。

## 评审结论

- reviewer：通过（Phase 0 终审：Gate 2/3 验证件成立；开放项——settings 分工裁决、Keychain 真机验证、0→18 段旧库金样——转 Phase 2）
- lead：批准（2026-11-23，Phase 0 终审随附）
