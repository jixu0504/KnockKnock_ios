# p0-toolchain 交接文档（Phase 0 · T1）

- 代理：p0-toolchain
- 完成时间：2026-11-22
- 分支：`ios-port`（基线 3642d2c → 里程碑 e509b2d、95e3166、**7cad292（Kotlin 2.3.21 最终态）**）
- 验收状态：**全部通过**（基线 APK ✓、升级后 Android 构建 ✓、iOS framework 链接 ✓）

## 1. 环境清单（全部自包含于 ios-port/toolchain/，未出工作区）

| 工具 | 版本 | 路径 |
|---|---|---|
| JDK | Temurin 21.0.13.1 LTS (aarch64) | `ios-port/toolchain/jdk21/Contents/Home` |
| Android cmdline-tools | 11076708 | `ios-port/toolchain/android-sdk/cmdline-tools/latest` |
| Android platform | android-35 | `ios-port/toolchain/android-sdk/platforms/android-35` |
| Android build-tools | 35.0.0（macOS universal 版） | `ios-port/toolchain/android-sdk/build-tools/35.0.0` |
| platform-tools | latest | `ios-port/toolchain/android-sdk/platform-tools` |
| Gradle | 8.10.2（wrapper，腾讯镜像 dist） | `ios-port/toolchain/gradle-home`（GRADLE_USER_HOME） |
| Kotlin | 2.1.21（KGP）+ K/N 2.1.21 | gradle-home 缓存；K/N 在 `ios-port/toolchain/konan`（KONAN_DATA_DIR） |
| 组合版本 | CMP 1.7.3 / SQLDelight 2.0.2 / KSP 2.1.21-2.0.1 / Ktor 3.0.3（未动）/ Koin 4.0.2（未动） | 见 `RhodesLink/gradle/libs.versions.toml` |
| Xcode | 27.2 (27B52) | 系统 |
| iOS 模拟器 runtime | **iOS 27.2 (24B8117) 已安装**（lead 侧下载完成，`xcrun simctl list runtimes` 确认） | 系统 |

环境入口：`source ios-port/toolchain/env.sh`（按脚本自身位置解析路径，任意目录可用）。
env.sh 同时导出 `ANDROID_USER_HOME`（解决 sdkmanager 缓存写 `~/.android` 被沙箱拦截的问题）。

## 2. 逐步结果

1. **env.sh** ✅（见上；踩坑：macOS JDK 布局要求 JAVA_HOME 指向 `Contents/Home`，gradlew 校验 `$JAVA_HOME/bin/java`）
2. **JDK21** ✅ 坑与解法：api.adoptium.net 连接挂死、Microsoft aka.ms 下载 100MB 后断流重启；最终用清华镜像 Adoptium（Temurin 21.0.13.1，秒下）。仓库根 `build-tools-34/` 为 Linux 版 ELF（file 确认），不可用于 mac，未复用。
3. **cmdline-tools + SDK** ✅ 坑：sdkmanager 报 `NoSuchFileException ~/.android/cache`——沙箱禁止写工作区外；设 `ANDROID_USER_HOME` 到工作区后 5 次重试全过。licenses 10 项已接受。
4. **local.properties** ✅ sdk.dir 指向工作区 SDK + KEYSTORE_PASSWORD=rhodes2026（取自 SETUP_SIMPLE.md；之前无密码导致基线构建 `:app:packageDebug` 失败）。`.gitignore` 已含 `/local.properties`，未入库。
5. **基线构建** ✅ `:app:assembleDebug` BUILD SUCCESSFUL；APK 归档 `ios-port/baseline/app-1.14.27-baseline.apk`（97.6MB）；日志 `ios-port/baseline/build-log-baseline-preupgrade.txt`。
6. **iOS runtime** ✅ 由 lead 侧 `xcodebuild -downloadPlatform iOS` 完成（本代理未并发执行），`xcrun simctl list runtimes` 显示 `iOS 27.2 (27.2 - 24B8117)`。
7. **Kotlin 升级** ✅ **最终 2.3.21**（lead 裁决选项 a 后经 task-11 完成，详见 §3/§3.1）；Android 构建不回归（日志 `ios-port/baseline/post-kotlin-2.3.21-android.txt`，BUILD SUCCESSFUL）。Gradle wrapper 保持 8.10.2：官方兼容表 KGP 2.3.20–2.3.21 支持 Gradle 7.6.3–9.3.0 / AGP 8.2.2–9.0.0，8.10.2+AGP 8.7.1 本就在范围内，无需升级。
8. **iOS target** ✅ iosArm64 + iosSimulatorArm64，`binaries.framework { baseName="Shared"; isStatic=true }`；iosMain 依赖 sqldelight-native、ktor-client-darwin（catalog 已有别名）。7 个 actual 全部落地 + 额外 3 个平台原语文件；DatabaseCompatibility（Android 旧库修复）按计划跳过。
9. **链接验证** ✅ `:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL（产物 `shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework`，静态，debug 226MB）。共 3 轮迭代：68 个平台泄漏编译错误 → 平台化修复 → `@PublishedApi` 修正，全过。
10. **git 里程碑** ✅ e509b2d「工具链升级…」+ 95e3166「iosMain actuals…」；工作区干净；local.properties 未入库。
11. **handoff** ✅（本文件）。

## 3. 升级版本决策（重要）

**结论：Kotlin 最终到 2.3.21（lead 裁决选项 a：授权最小 app 修复，task-11，commit 7cad292）。** 升级历程与证据：

| 尝试 | 结果 |
|---|---|
| 2.3.21（默认 language 2.3） | app 编译失败：`TYPE_INTERSECTION_AS_REIFIED` 由警告升级为错误（ProblemChecker.kt 5 处 `arrayOf(混合类型)`）。**无法用编译器开关降级**：`-Xwarning-level` 只接受"警告"名录（错误诊断名不在注册表，报 "does not exist"；且该机制禁止改错误级别，见 `FirSuppressedDiagnosticsCheckers` 的 `ERROR_SEVERITY_CHANGED`）；无 `-Xsuppress-diagnostic` 参数。 |
| 2.3.20/2.3.21（app 固定 languageVersion 2.2） | 交集错误消失，但 `GroupChatViewModel$sendGroupMessage$5$1.invokeSuspend` 超 JVM 64KB 方法上限（"Method too large"）。`-Xno-*-assertions` 移除断言也不够（且改变空参校验行为）。 |
| 2.2.21 | 交集仍为警告，但同一 lambda 直接超 64KB（2.1.0 基线压线通过，2.2+ 代码生成变大）。 |
| 2.1.21（task-1 中间态，commit e509b2d） | 全部通过，作为等待裁决期间的保守组合。 |
| **2.3.21 + 授权最小修复（最终，commit 7cad292）** | 双端构建均 BUILD SUCCESSFUL。修复点与等价性论证见 §3.1。 |

**最终版本链**：Kotlin 2.3.21 / KSP 2.3.14 / Compose Multiplatform 1.11.1 / SQLDelight 2.2.1 / **kotlinx-datetime 0.7.1-0.6.x-compat**（原因：CMP 1.11.1 → material3 1.9.0 强制 kotlinx-datetime 0.7.1，而 0.7.x 的 Native 版把 Clock 迁到 kotlin.time.Clock，commonMain 的 `Clock.System` 在 iOS 编译报 "Unresolved reference 'System'"（44 处，含 compareTo 级联 1 处）；0.7.1-0.6.x-compat 为官方兼容构件，保留 0.6.x API，双端语义不变）。Ktor 3.0.3 / Koin 4.0.2 未动。Gradle 保持 8.10.2（官方兼容表 KGP 2.3.20–2.3.21 支持 Gradle 7.6.3–9.3.0 / AGP 8.2.2–9.0.0）。

### 3.1 T11 最小 app 修复点与等价性论证（file:line 为修复后行号）

1. **ProblemChecker.kt — 5 处 `arrayOf` 加显式 `<Any>`**：
   - 修复后行号：1232、1233、1234、1455、1456（`arrayOf<Any>(...)`）。
   - 等价性：Kotlin 2.1 中该模式仅告警（实化类型取公共超类型 `Comparable<*> & Serializable`，运行时数组元素即按 `Any` 存储）；2.3 起该行为升级为编译错误，显式 `<Any>` 正是编译器要求的目标类型。数组内容逐字未变，`db.execSQL(sql, bindArgs)` 的绑定行为与 2.1 字节码一致（JVM 签名同为 `Array<Any>`，仅类型实参从隐式变为显式）。
2. **GroupChatViewModel.kt — 巨型挂起 lambda 拆分**（sendGroupMessage 原 964 行）：
   - 结构：`sendGroupMessage` 内 `scope.launch { executeGroupTurn(...) }`；`executeGroupTurn`（私有 suspend，29 个参数，参数同名局部 var 遮蔽以保持可重赋值）内含：
     - 4 个原局部函数上提（`turnElapsedMs`/`remainingTurnBudget`/`optionalGroupContext`/`requiredGroupContext`，闭包按引用捕获不变）；
     - `prepareTurn(): Boolean`（原 步骤2 前半：租约/互斥/会话读取/上下文构建；9 处原 `return@launch` → `return false`）；
     - 嵌套 `runModelTurn(): Boolean`（原 步骤2 后半：模型调用/流式/保存；5 处原 `return@launch` → `return false`）；
     - `try { if (!prepareTurn()) return; if (!runModelTurn()) return } catch×4 finally` —— 原 try/catch/finally 逐字保留，catch 内 6 处 `return@launch` → `return`（plain return 语义相同：都会先执行 finally）。
   - 等价性论证：原 launch lambda 的 22 处 `return@launch` 全部位于内联上下文（withTimeout/withTimeoutOrNull/run 等），转换为嵌套局部函数中的 `return false`/`return` 后：(a) 提前中止路径——`prepareTurn` 返回 false → 外层 `return` → finally 执行，与原 return@launch → finally 完全一致；(b) 异常路径——嵌套函数不捕获异常，异常穿过局部函数边界进入同一个 try/catch 链，catch 内容逐字未动；(c) 正常路径——两步顺序执行，finally 收尾逻辑（释放租约/置非 loading/清 job/解锁互斥/onResponseComplete）逐字保留；(d) `coroutineContext`（launch lambda 原经 CoroutineScope receiver 解析）改由 `import kotlin.coroutines.coroutineContext` 解析，值为同一 continuation 上下文（executeGroupTurn 在 launch 内直接调用，无新协程边界）。
   - 另：加 import 1 处；删除 1 行冗余 try 边界（两次手术拼接时遗留，已清理并复核括号平衡）。
3. 验证：`:app:assembleDebug` BUILD SUCCESSFUL（含移除 `-Xno-*-assertions` 后的纯净构建，确认拆分本身足够治本，无任何编译参数行为代价）；`:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL。日志：`ios-port/baseline/post-kotlin-2.3.21-android.txt`、`ios-port/baseline/post-kotlin-2.3.21-ios-link.txt`。

### 3.2 K/N 2.3.21 与 Xcode 27.2

- **K/N 2.3.21 + Xcode 27.2 实测可用**：`linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL，链接日志无任何 Xcode 版本警告（`kotlin.apple.xcodeCompatibility.nowarn=true` 已开，且 2.3.21 官方支持 Xcode 26+，27.2 超出测试矩阵但无告警、无失败）。
- K/N 首次运行下载了 LLVM/sysroot 等依赖（~1GB，KONAN_DATA_DIR 内），后续构建无需重复。

## 5. 平台化改造清单（Android 行为不变承诺）

- `platformSynchronized`（expect inline/actual）：Android=JVM `synchronized`（内联，非局部 return 语义不变）；iOS=全局 NSRecursiveLock（Phase 0 粗粒度，可重入）。替换范围：DatabaseDispatcher（4）、SettingsRepository（14+8）、MemoryVectorService（1）。
- `ioDispatcher`：Android=Dispatchers.IO；iOS=Dispatchers.Default（K/N 的 IO 为 internal）。替换范围：DatabaseDispatcher（limitedParallelism(1)）、OperatorRepository（2）、SessionRepository（5）。
- `System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`（11 个文件；JVM 上即 UTC 墙钟毫秒，语义一致）。
- `logError`（expect/actual）：Android=Log.e（级别/文案/tag 不变）；iOS=NSLog。替换 AnchorRepository 11 处。
- `javaClass.simpleName` → `::class.simpleName`（2 个语音网关，3 处；注意 `::class.simpleName` 可空，Aliyun 处已 `.orEmpty()`）。
- SettingsRepository 日期：`SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)` + Asia/Shanghai → kotlinx-datetime `Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Shanghai")).date`（ISO 输出逐字符一致）。
- MemoryVectorService 嵌入缓存：JVM 匿名 LinkedHashMap（access-order+removeEldestEntry，容量 32）→ 公共 `AccessOrderLruMap`（访问序、超容逐出最旧，语义等价）。
- 这些改动全部经过 `:app:assembleDebug` 回归验证（日志为证）。

## 6. 遗留问题

1. **（已解决）Kotlin 2.3.21 升级**：lead 裁决选项 a，最小 app 修复已完成并双端验证（commit 7cad292，§3.1）。e509b2d（2.1.21）为中间态 commit，保留在历史中。
2. **iOS 侧全局锁**：platformSynchronized 在 iOS 上是单一全局 NSRecursiveLock，锁粒度粗；Phase 1 若出现锁争用（聊天管道高频写 settings）再按锁对象细化。
3. **PlatformUtils iOS 为骨架**：showToast/shareText 为 TODO（需 UIKit VC 上下文），剪贴板已可用；Phase 3 壳层落地时补齐。
4. **DatabaseCompatibility 未移植**：Android 旧库修复/兜底逻辑 iOS 不需要；但"Android 备份 → iOS 恢复"场景需要单独处理（恢复包场景在 Phase 0 第 2 批 / Phase 2）。
5. **构建资源纪律**：本机为单机共享，gradle 全部 `--no-daemon` 串行执行；K/N 编译会占大量 CPU，与其他代理的构建任务要错峰。曾出现一次 Android 构建在 `:app:packageDebugResources` 挂起 30 分钟（原因未定位，疑为与其他代理构建竞争），重跑即过。
6. **代理类网络抖动**：出现过一次 Gradle 解析依赖时被引到 127.0.0.1:7897（代理）导致失败，重跑即好；若再现，检查本机代理软件/系统代理状态后重试即可，勿大改配置。
7. **磁盘**：约 37G 空闲（K/N 依赖 + 构建产物 + iOS 27.2 runtime）。
8. **kotlinx-datetime 0.7.1-0.6.x-compat 为兼容构件**：Phase 1 如升级 CMP/material3 或 datetime 本身，需重验 `Clock.System`（届时可迁移到 kotlin.time.Clock 新 API 并删除 compat 后缀）。

## 7. 下一步建议（Phase 0 第 2 批，可并行派发）

1. **SQLDelight 打开库验证**（依赖本任务产物）：iOS 真机/模拟器上 `DatabaseWrapper.database` 打开 + 23 个迁移跑通（可用 ios-port 模拟器 runtime + 一个最小 Swift 宿主，或直接 commonTest）。
2. **Ktor Darwin 流式实测**：真实供应商 SSE 首 token/取消/超时（240s）/半包回归（计划书 Gate 0 要求）。
3. **备份恢复最小闭环**：Android 备份包在 iOS 恢复（媒体 ID 化 + 自检），确认 DatabaseCompatibility 之外还需什么。
4. **2,000 条消息时间线原型**：ChatRepository 全量查询 + 分页/滚动方案（UICollectionView 决策）。
5. **夜间批量回归**：✅ 已完成——324 个单测在 Kotlin 2.3.21 下全量跑通（`:app:testDebugUnitTest` BUILD SUCCESSFUL，39 文件 / 324 tests / 0 failures），证据：`ios-port/baseline/regression-324-tests.txt` + `app/build/test-results/testDebugUnitTest/*.xml`（reviewer Phase 0 终审核实）。
6. **SwiftUI 玻璃壳 Demo** 与 **后台任务/本地通知实验**（计划书 Phase 0 其余项）。

## 附录：关键日志/产物位置

- `ios-port/baseline/app-1.14.27-baseline.apk`（97.6MB）
- `ios-port/baseline/build-log-baseline-preupgrade.txt`（基线构建日志，BUILD SUCCESSFUL）
- `ios-port/baseline/build-log-post-upgrade.txt`（升级后构建日志，BUILD SUCCESSFUL）
- `ios-port/baseline/build-log-ios-link.txt`（iOS framework 链接日志，BUILD SUCCESSFUL）
- `shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework`（链接产物）
- git：e509b2d（工具链升级）、95e3166（iosMain actuals）
