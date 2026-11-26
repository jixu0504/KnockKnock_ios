# handoffs/audit-agent.md —— 盘点方法与 P0 风险清单

盘点人：audit-agent（T4）。基线：RhodesLink @ ios-port（3642d2c，tag v1.14.27-android-baseline），全程只读（grep/read/bash 统计），未改动仓库。

## 方法

- 路径：`grep -rn --include='*.kt' -E '<pattern>' app/src shared/src` 先 `wc -l` 量级、再抽样上下文；目录级模式（filesDir/cacheDir 派生）用 `grep -E 'File\(.*(path|dir|…)'` 枚举。
- 备份字段：直接读序列化定义源码（BackupModels.kt / ExportPayload.kt / BackupFileWriter.kt / PortableSettings.kt / BackupMediaCollector.kt / BackupRestorePayloadRewriter.kt / BackupContentFilter.kt）。
- 设置项：Python 脚本按 `// === 节 ===` 分节，匹配 `var/val X` + 下一行 `get() =`，提取「类别|属性名|底层 key|行号」，共 190 属性；另计 getter/setter 写法数。
- 平台耦合：`grep -rln '^import android\.'` 分目录核对；androidMain 文件逐个读头；commonMain 用 `grep -E '\b(android|java)\.'` 抽查。
- 中文硬编码：Python 逐行匹配 `"[^"]*[\u4e00-\u9fff][^"]*"`，按行数/出现次数/去重条数三口径交叉统计。

## 与预期数字差异

| 预期（任务书/报告） | 实测 | 说明 |
|---|---|---|
| app import android.* 约 59 个文件 | **59（app/src/main）**，147 行 import；androidTest 另有 2 | ✅ 吻合 |
| SettingsRepository 约 370 访问器 | 192 个属性；getter 写法 192 + setter 写法 189 ≈ **381** | ✅ 同数量级（口径= getter+setter 方法数） |
| 中文硬编码 7,370 条 | 行口径 6,126（main+common）、出现次数 9,966、每行去重 9,455 | ⚠️ 无法精确复现（见 hardcoded.md 差异说明），数量级一致 |
| shared/androidMain 7 个 actual | **7 文件**；expect 10 个声明（Platform 1 + SettingsFactory 1 + HttpClient 1 + DatabaseDriverFactory 1 + DatabaseWrapper 1 + PlatformUtils 5）；DatabaseCompatibility 无 expect | ✅ 吻合（报告附录 B 一致） |
| commonMain 零 android.*/java.* | **不成立**：AnchorRepository.kt:3 `import android.util.Log`（11 处 Log.e）；SettingsRepository.kt:1399-1401 `java.text.SimpleDateFormat/java.util.TimeZone/java.util.Date` | ❌ 报告结论需更正为「2 文件 12 处」；当前仅因单 androidTarget 可编译（shared/build.gradle.kts:18-24） |
| 模型供应商 11 个 | ✅ ProviderConfigs.kt:5-72（deepseek/minimax/byte/google/anthropic/ali/zhipu/siliconflow/xiaomi/openai_compat/custom） | 每个供应商持久化字段 provider/model_name/custom_url/api_key（SettingsRepository.kt:1446-1450） |

## 对 iOS 移植的 P0 风险清单（附落点）

1. **commonMain 平台泄漏（编译阻断）**：`AnchorRepository.kt:3`（android.util.Log ×11 用点 :25,36,53,93,105…）与 `SettingsRepository.kt:1399-1401`（java.text/java.util）——加 iosTarget 即失败。落点：Phase 0 第一步引入 expect 日志器 + kotlinx-datetime。
2. **媒体 ID 化落点**：运行时所有媒体引用是 `file://` 绝对路径字符串（Operator.avatarUri `Operator.kt:12`、Session.avatarUri `ChatSession.kt:17`、user_avatar_uri `SettingsRepository.kt:853`、bg_/gbg_ `ChatScreen.kt:327`、`GroupDetailScreen.kt:71`、voice 路径 `LocalAudioController.kt:129,141,146`、消息内嵌 imageUri `BackupMediaCollector.kt:52-58`）。备份层已有 mediaId/archivePath 雏形（`BackupMediaCollector.kt:24-30`），但恢复靠 Android 私有路径重写（`BackupRestorePayloadRewriter.kt:48-58` 的 `/data/data|/data/user/0/files/` 特征串）——iOS 需把运行时引用统一改为相对媒体 ID，恢复改为 ID 直映射。落点：shared 增加 MediaRepository（Documents 下 images/chat_images/voice/restored_media 四根，路径清单见 paths.md §4），SettingsRepository/模型字段值域切换。
3. **密钥防护落点**：5 个 API key 设置项（`api_key` :114、`vector_api_key` :888、`vision_api_key` :946、`asr_api_key` :958、`tts_api_key` :974）+ `newspaper_deepseek_key`（NewspaperService.kt:53）+ `asr/tts_provider_configurations` JSON 内嵌 key（SettingsRepository.kt:1436-1450）。当前备份白名单（PortableSettings.kt:6-66）已正确排除全部 secret，但 Android 存储是 EncryptedSharedPreferences（SettingsFactory.android.kt:40-49）。iOS 落点：SettingsFactory.ios 用 NSUserDefaults（multiplatform-settings）+ Keychain 密钥层，并保持「密钥不进备份」检查清单（fields.md 末尾）。
4. **设置存储重设计落点**：`SettingsFactory.android.kt` 的 legacy 迁移/降级/明文回退诊断开关（:28-90）为 Android 专有；iOS 新装无需，恢复包场景按附录 B「DatabaseCompatibility 单独处理」。DatabaseCompatibility（androidMain，无 expect）同理不入 iOS。
5. **备份明文无加密**（无 zip crypto，字段含 privatePrompt/groupPrompt/memoryInjection/知识库全文）——iOS 版是否加应用层加密需产品决策；至少保持「密钥不进备份」与 sha256 校验、安全路径校验（BackupFileReader.isSafeArchivePath、media/ 前缀约束 BackupFileWriter.kt:41-46）。
6. **平台子系统替换**（非阻断编译但阻断功能）：WorkManager 9 个 automation 文件、NotificationCenter(8 import)、MediaPlayer/AudioManager、ActivityResult 相册/裁剪（ui 21 文件）、WebView galgame_full 子系统。

## 交付物

- ios-port/audit/paths.md —— 路径模式盘点（file:line + iOS 评估 + 优先级）
- ios-port/audit/fields.md —— 备份包字段/版本/校验 + SettingsRepository 190 属性分类 + 密钥清单
- ios-port/audit/platform-coupling.md —— 59 文件 android import 清单 + 7 actual 职责 + commonMain 泄漏
- ios-port/audit/hardcoded.md —— 中文硬编码 top 30 + 口径差异

## 评审修正记录（reviewer PASS-WITH-FIXES，2026-11-22）

以下 5 条 P2 均已按评审意见核实源码后修正，并同步更新本 handoff 的数字：

1. `ios-port/audit/platform-coupling.md`：「154 行 import」→ **147**（原值误把 androidTest/test 的 7 行计入；`app/src/main` 实测 147）。
2. `ios-port/audit/fields.md`：PortableSettings 白名单计数 stringKeys 13→**14**、booleanKeys 37→**39**、intKeys 80→**74**（重跑 setOf 键提取脚本核实）。
3. `ios-port/audit/fields.md`：`formatVersion` 写出位置 `:59` → **BackupFileWriter.kt:83**；SettingsRepository 属性数 190→**192**（补齐 historyMessages :203、supportPersistConversation :242 两个多行声明属性，聊天设置类别 22→24）。
4. `ios-port/audit/paths.md`：`imageUriPattern` 正则行号 `:82`→**:86**；`BackupMediaStager` 媒体完成性校验行号（位于 fields.md）`:22-24`→**:26-28**。
5. `ios-port/audit/hardcoded.md`：补充 `res/values/themes.xml:4` 存在 CJK 样式标识符 `<style name="Theme.罗德岛终端">` 的说明（资源 name 非用户可见文案，不计入文案统计）。
