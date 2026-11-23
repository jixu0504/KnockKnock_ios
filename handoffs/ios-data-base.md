# handoff：ios-data-base（iOS 数据底座最小闭环，task-9 / T9）

> 代理：ios-data-base ｜ 任务：task-9 ｜ 状态：**完成（28/28 测试通过，双 target framework 链接成功）**
> 交付：`RhodesLink/shared/src/iosMain/...`（db/security/settings/files 四处新代码 + 3 处骨架修订）、
> `RhodesLink/shared/src/iosTest/...`（28 个测试）、`ios-port/specs/ios-data-base-spec.md`、
> `ios-port/handoffs/mock-sse-server.py`（测试资产）、本文件。

## 1. 交付物清单

| 路径 | 说明 |
|---|---|
| `shared/src/iosMain/kotlin/.../db/DatabaseLocations.ios.kt` | 数据库位置 + 驱动构造（sqliter basePath 方案，见 §3） |
| `shared/src/iosMain/kotlin/.../db/DatabaseDriverFactory.ios.kt` / `DatabaseWrapper.ios.kt` | 修订：走 DatabaseLocations（修 T1 骨架的相对路径 bug） |
| `shared/src/iosMain/kotlin/.../security/KeychainStore.ios.kt` | kSecClassGenericPassword CRUD + KeychainError 映射 |
| `shared/src/iosMain/kotlin/.../settings/JsonSettingsStore.ios.kt` | JSON 原子写（tmp→replaceItemAtURL→remove+move 退化） |
| `shared/src/iosMain/kotlin/.../files/MediaDirectory.ios.kt` + `FileIo` | 媒体目录初始化 + 相对 ID 规则占位 + 二进制读写 |
| `shared/src/iosMain/kotlin/.../backup/BackupMediaSinkIos.kt`（backup-restore-loop 的） | 最小修正为新 interop（已通知 T10） |
| `shared/src/iosTest/kotlin/.../{db,network,security,settings,files}/*Test.kt` + `testutil/TestPaths.kt` | 28 个测试（详见 §4 实测数据） |
| `shared/build.gradle.kts` | + commonTest kotlin("test")；+ `iosSimulatorArm64().testRuns { deviceId = "iPhone 17" }` |
| `ios-port/handoffs/mock-sse-server.py` | 本地 SSE mock（127.0.0.1:18765，8 个端点，事件日志打 stdout） |

## 2. 验收结果（模拟器真实运行，非静态核对）

`./gradlew --no-daemon :shared:iosSimulatorArm64Test --tests "com.rhodes.privatechat.shared.{db,network,security,settings,files}.*"`
→ **28 tests completed, 0 failed**（iPhone 17 / iOS 27.2 模拟器，K/N standalone 运行器，simctl spawn 实证）。

| 模块 | 用例 | 关键实测数据 |
|---|---|---|
| SQLDelight | 3 | cold-open-and-create **0ms**（<1ms 粒度截断），db-file=4096 bytes；upgrade-18→24 **0ms**；warm-reopen 0ms。fresh 冷开 28 表 + user_version=24（=Schema.version，23 迁移+1）；升级路径迁移 18/20/22/23 实跑生效（data_repair_runs 出现、sourceType 列恢复、user_version 18→24） |
| Ktor Darwin | 8 | first-token **114ms**（服务器 100ms 发送，符合）；cancel-and-join **1ms**、收到 2 token 后取消（服务器侧 BrokenPipeError 证据见 mock 日志）；slow-first 超时中止 **1508ms**（阈值 1.5s）；silent 超时 **2008ms**（阈值 2s）；response 阶段超时 **1506ms**；半包断连 4ms 完整收到 `data: partial-to`；500+JSON 透传；自定义端点 OK。ATS 对 127.0.0.1 豁免**实测成立** |
| Keychain | 6 | standalone 运行器无 Keychain：errSecNotAvailable（**-25291**）经真实 OSStatus 往返映射为 `NotAvailable`；CRUD 用例在无 Keychain 环境显式降级为记录型通过，Keychain 可用时（宿主 app/真机）同批断言全量执行。错误码映射表 8 项静态核对通过 |
| JsonSettingsStore | 6 | 往返一致（嵌套+Unicode）；坏/截断 JSON 回退空对象不崩；50 次连续原子写无半文件、无 tmp 残留；stale tmp 不影响读取 |
| MediaDirectory/FileIo | 5 | Application Support/media/{images,voice,chat_images,newspapers} 幂等创建；相对 ID 校验/映射；媒体文件写入读回删除；wipe 重建。standalone 下 Application Support 解析为 `/Users/.../CoreSimulator/Devices/<UDID>/data/Library/Application Support`（与真机 app 沙箱路径形态一致） |

双 target framework：`:shared:linkDebugFrameworkIosArm64` + `:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL**。

## 3. K/N 2.3.21 新 interop 关键事实（Phase 2/3 必读，全部踩坑实证）

1. **NSString ≠ Kotlin String**：新 platform libs 不再自动映射。桥接须显式 `NSString.create(string=...)` / `NSString.create(data=, encoding=)`（返回 `NSString?`）/ `.toString()`；`(str as NSString)` 编译不保证，禁用。
2. **NSFileManager 方法变成员**：`fileExistsAtPath`/`createDirectoryAtPath`/`removeItemAtPath`/`moveItemAtPath`/`replaceItemAtURL`/`contentsOfDirectoryAtPath` 等为类成员（不再需要 `import platform.Foundation.xxx`，该导入在 2.3 下直接 Unresolved）；而 NSData/NSString 工厂仍为扩展（`import platform.Foundation.create` / `dataUsingEncoding` / `writeToFile` / `dataWithContentsOfFile` / `length` 必须显式导入）。
3. **CFBridgingRetain/Release 在 platform.Foundation**（非 CoreFoundation）；`CFTypeRefVar` 别名不可直接 import——`SecItemCopyMatching` 的 out 参数真实类型是 `CValuesRef<CPointerVarOf<CFTypeRef>>?`，用 `memScoped { alloc<CPointerVarOf<CFTypeRef>>() }` + `import kotlinx.cinterop.value` 取 `.value`。
4. **`Pinned.addressOf` 是扩展**：必须 `import kotlinx.cinterop.addressOf`（不是数组的成员；`ByteArray.addressOf` 不存在）。
5. **kotlinx-serialization 1.7.3**：`JsonElementSerializer`/`JsonObjectSerializer` 为 internal，`Json.encodeToString(JsonElement)` 扩展不存在——编码 JsonObject 用 `data.toString()`（与 parseToJsonElement 互逆）。
6. **SQLDelight 2.2.1 运行时语义**：
   - `NativeSqliteDriver` 的 `name` **禁止路径分隔符**（sqliter 抛 "contains a path separator"）——目录必须经 `onConfiguration { cfg -> cfg.copy(extendedConfig = cfg.extendedConfig.copy(basePath = dir)) }` 传入（sqliter Extended.basePath）。
   - fresh 冷开只走 `Schema.create()`（28 张 .sq 表），**不执行迁移**；迁移只在旧库升级路径执行（`oldVersion <= N && newVersion > N`）。
   - `Schema.version = 迁移数 + 1 = 24`（23 个 .sqm）；fresh 冷开 user_version=24。
   - `executeQuery` 的 mapper 必须返回 `QueryResult.Value(...)`（`app.cash.sqldelight.db.QueryResult`），`cursor.next()` 返回 `QueryResult<Boolean>`（`.value` 解包），SqlCursor 不是 AutoCloseable（不要 `cursor.use{}`）。
   - `driver.execute(...)` 的 identifier 参数别传 null（生成代码都传非 null 编号）。
7. **Ktor 3.0.3**：`HttpClient` 实现的是 kotlinx.io.Closeable，不是 java.io.AutoCloseable——**不能 `client.use{}`**，用 try/finally close；流读取用 `readAvailable`（-1=EOF，0=暂无数据）+ `awaitContent()`（false=关闭）模式，取消语义经协程传播实测可靠。
8. **KGP 2.3.21**：`testRuns` 不在 `KotlinNativeTarget` 基类上（在 `KotlinNativeTargetWithSimulatorTests` 上），`deviceId` 属性名确认；`standalone` 模式是唯一模式（**无宿主 app 模式**→ Keychain 不可用的根源）。

## 4. 遗留问题 / 待 Phase 2

1. **Keychain CRUD 待宿主 app/真机验证**（模拟器 standalone 无 keychain，-25291）。建议 Phase 2：宿主 app 集成测试（XCUITest 或 K/N test 挂宿主 app bundle——KGP 2.3 无此模式，需自建）或真机验证；同时落实 kSecAttrAccessible 分级（默认 whenUnlocked）、锁屏重试（InteractionNotAllowed）、access group。
2. **升级路径未全链路验证**：仅验证了 18→24 段（19/20/21/22/23 含 18）。0→18 段（ALTER 类迁移）需构造真实旧版 DB 金样再验（可留 Phase 2 用 Android 旧版 DB 文件拷贝验证——注意 Android 与 iOS sqlite 文件格式通用）。
3. **DB 性能数据为模拟器主机侧值**（0ms 量级）；真机冷启动数据 Phase 2 补。
4. **设置 JSON 与 NSUserDefaults 的分工**待 lead 裁决：当前骨架 = 小键值 NSUserDefaults + 整文档 JsonSettingsStore；敏感数据只进 Keychain。
5. **媒体 ID 规则为占位**（`<category>:<rest>` → `<subdir>/<category>_<rest>`）；Phase 2 MediaRepository 需与备份管线 mediaId 字段、paths.md §6 的 bg_*/gbg_* 键值域对齐。
6. mock-sse-server.py 生命周期：测试前置手动启动（见验收命令）；CI 化时建议进 gradle task 或 XCTest 前置脚本。

## 5. 构建纪律记录

- 全部构建经 pgrep 轮询排队；与 backup-restore-loop 有一次重叠（其 BackupRestoreGoldenTest 运行期间我误发起一次构建，13s 返回且结果为对方任务的 UP-TO-DATE 复用，无失败，已按纪律重跑）。未出现超 3 轮未收敛的 build-fix 迭代；新 interop API 适配共 10 次编译迭代（每类问题 ≤3 轮内收敛），已全量收敛。
- 结束时确认无残留 gradle 进程、mock 服务器已停。

## 6. Phase 2 正式实现建议（按优先级）

1. `DatabaseLocations` 方案固化：basePath 模式 + WAL 默认；升级路径用 Android 旧版 DB 金样全链路验证 0→24。
2. Keychain 宿主 app/真机验证 + kSecAttrAccessible 分级 + 错误重试策略（InteractionNotAllowed/NotAvailable 的用户提示路径）。
3. MediaRepository 正式化（ID 规则、去重、清理、备份白名单根 images/ 语义）。
4. JsonSettingsStore 接入备份元数据场景，确认原子写与备份管线互不干扰。
5. 测试运行器：考虑预置一台固定模拟器（deviceId 已固定 iPhone 17）；文档化 standalone 模式的 Keychain/Application Support 行为差异。
