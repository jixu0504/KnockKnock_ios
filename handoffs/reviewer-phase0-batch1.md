# Phase 0 第 1 批评审报告（reviewer / task-5）

- 评审代理：reviewer（独立评审，未参与实现）
- 评审日期：2026-11-22
- 基线：`RhodesLink @ ios-port`（HEAD == `v1.14.27-android-baseline^{commit}` == `3642d2c`，工作树干净）
- 评审对象：T2 baseline-agent、T3 checklist-agent、T4 audit-agent 的全部产物 + 各自 handoff（T1 p0-toolchain 仍在跑，不在本次范围）
- 评审标准：a) 对照计划书 §9 第 1 批；b) 可复核性（每文档 ≥3 处 file:line 亲自用 read/grep 验证）；c) 与可行性报告数字口径一致；d) T4 P0 发现亲自验证；e) 写域越界检查。

## 总结论

| 任务 | Verdict |
|---|---|
| T2 baseline-agent | **PASS** |
| T3 checklist-agent | **PASS-WITH-FIXES**（1 个 P1 + 2 个 P2） |
| T4 audit-agent | **PASS-WITH-FIXES**（0 个 P1 + 7 个 P2；核心 P0 发现全部属实） |
| 写域越界（e） | **PASS**（RhodesLink 工作树 0 改动/0 未跟踪文件；ios-port 位于仓库外） |

修正清单见文末 §4；全部为文档级修正，不涉及实现代码。建议 lead 把 P1 项派回 checklist-agent，P2 项并入下一轮修订批次。

---

## 1. T2 baseline-agent —— PASS

### a) 计划书 §9 第 1 批覆盖

| 要求 | 状态 | 证据 |
|---|---|---|
| 基线冻结（tag/commit/版本） | ✅ | `git cat-file -t` = tag（annotated），`v1.14.27-android-baseline^{commit}` = `3642d2c`，tag 消息/时间与 BASELINE.md:7-8 一致（2026-11-22 20:37:19 +0800） |
| versionCode/Name/SDK | ✅ | `git show tag:app/build.gradle.kts`：versionCode 74 / versionName 1.14.27 / compileSdk 35 / minSdk 26 / targetSdk 35，与 BASELINE.md:11-15 完全一致 |
| APK | ⏳ 依赖 T1，已明确 | BASELINE.md:16-17 声明「待 T1 产出 APK…回填本文件」 |
| 数据库结构金样 | ✅ 源码级 | 24 个 .sq 与 tag 逐字节 diff 一致（本人全量 diff 验证 0 差异）；28 个 `CREATE TABLE` 实测一致；23 个 .sqm 实测一致；no-op 4/6/9（`SELECT 1;`/`SELECT 1 WHERE 0;`）与迁移 18 建 `data_repair_runs`（运行时 29 表）、迁移 11 删 `world_events` 均逐文件核对属实 |
| 完整备份/媒体样本 | ⏳ 依赖 T1 模拟器，已明确 | BASELINE.md:56-59 + handoff §3/§4 给出设备级金样采集的 6 步步骤与目录规划（golden/device/），依赖关系清晰 |
| 金样仓库 | ✅ | golden/{db,prompts,sessions,media} 四类齐全（见下） |

### b) 可复核性（抽样验证，全部属实）

- BASELINE.md:30 `libs.versions.toml` 84 行 MD5 `3948e3d8…75c3fd` —— 实测 `wc -l`=84、`md5`=`3948e3d8de2a419c24dda28ccc75c3fd`，且与 tag 内容 diff 为空 ✅
- tests.md:324 单测/39 文件、10 仪器/4 文件（3+5+1+1）—— 按文件 grep -c 复核 ✅；抽查 PromptCacheLayer=31、GroupEmotion=30、BackupFileContainer=26、VolcanoTts=18、Newspaper=31、MahjongEngine=6、ChatPerformance=3、StartupInteraction=5 全部一致 ✅
- avatars.md 全部引用精确：`AvatarImage.kt:31-34`（`num in 1..147` + `getIdentifier("avatar_$num")` 动态解析）、`:46-52` AsyncImage、`:53-64` 首字色块回退、`ProfileSettingsScreen.kt:95/:114`、`SettingsRepository.kt:853-854`、`Operator.kt:12`、`ChatSession.kt:17`、`SettingsMigration.kt:217`、`PortableSettings.kt:9` —— 逐行 sed 验证 ✅；avatar_1=36,531B≈36KB、avatar_147=445,312B≈445KB、147 张连续无缺号、nodpi 无副本 ✅
- inventory.md 抽查 8 处 file:line 精确（`PromptTemplates.kt:3-4` VERSION=39、`MemoryV2PromptTemplates.kt:3-8`、`AgentProfiles.kt:21-22` defaultId="nuan"、`PromptPlaceholderRegistry.kt:4`、`SettingsRepository.kt:1275`、`MainViewModel.kt:422`、`PromptEditorScreen.kt:73`）✅；raw/ 抽取件与源码逐字比对 2 例（HUMAN_REALISM、AgentProfiles_L30）内容忠实、dedent 正确 ✅；114 件清单行数与目录实际文件数一致 ✅
- golden/sessions 6 个 JSON 语法有效且与 `docs/` 同名文件 diff 为空 ✅（docs 另有 newspaper-live-test-1.14.23.json 未收录，handoff §2 已说明原因——报纸属二期候选，合理）

### c) 口径一致性

28 表 ✅（并解释运行时 29 表的成因）、23 迁移 ✅、324 单测 ✅、10 仪器 ✅、147 头像 ✅，均与可行性报告 §1.1 一致或已给出差异说明。计划书 :152/:216 引用位置核对无误 ✅。

### 评价

金样归档方法规范（一律 `git show tag:` 取基线，规避 T1 并行改动）、差异透明（shared 0 测试、data/PromptTemplates 空壳警示、raw 为近似归档的边界说明）。会话金样本质是项目自带验证清单而非真实对话数据集，handoff 已如实标注为「现有验证件归档」，并把完整数据集/设备级备份列为 T1 就绪后的待办——不扣分。**PASS，无需修正。**

---

## 2. T3 checklist-agent —— PASS-WITH-FIXES

### a) 计划书 §9 覆盖

- 52 路由 × 入口/操作/数据变化/错误状态/验收结果：**全部 52 条路由名与 AppScreens.kt 实际路由一一比对，无遗漏**（本人提取 52 个 data object/class 与清单交叉核对 = NONE missing）；另覆盖 4 Tab、跨页面六要素（消息操作/流式/备份链/设置树/A7）、待补清单 5 项 ✅
- 覆盖口径与计划书第二章（A1-A7、排除清单、二期候选）逐项对应 ✅

### b) 可复核性（抽样验证）

- 导航拓扑结论真实：`navigator.push` 在 `navigation/` 外 0 命中（唯一 RdgPackage.kt:147 命中为内嵌 HTML 的浏览器 JS `navigator.share`，handoff 描述准确）✅
- AppScreens.kt 引用抽查 20+ 处全部精确：:142-146（EditOperator/状态/历史/存档/语音）、:175/:203/:271 Toast、:292 群成员点按、:334/:384 角色不存在自动返回、:336/:371/:411/:413/:414/:427/:432（MomentDetailRoute 含 replyToCommentId）/445/478/494/507/508/575/577/585/628/654/787/797/833/842/849/862/903 ✅
- ChatViewModel.kt:2083(recallMessage)/2173(recallSegment)/2150(retry)/2363(regenerate)/2443(continue)/1819(sendImage)/2742(resumeImage)/1796(resumePersistedReply)、GroupChatViewModel.kt:392/1818/2056/661/630/816/2745 全部精确 ✅
- 统计汇总：AlertDialog 115 处实测一致、Top 文件分布（13/9/9/6/5/4×6）一致、图标 76 种/220 处一致（含 Top14 逐项：ArrowBack 37、MenuBook 9、Check 8、Refresh 7、SendToMobile 6、Groups 6、Favorite 6、Delete 6、ContentCopy 6、Search/PhotoCamera/KeyboardArrowRight/Build 各 4）、VM 行数 3829/3659/2877/1347/1291/1030 与 12,901+5,111=18,012 全部一致 ✅

### 发现的问题（修正清单 §4）

1. **[P1] MessageList.kt 行号全部失实**：清单中 :533（图片识别失败）、:552（未送达）、:523（撤回 UI）、:506,559（复制）与实际不符——实为 :501（图片识别失败）、:520（未送达）、:420/:550（撤回菜单）、:413,417/:543,547（复制+已复制 Toast）。经查 git log（a955b5e/e0581c7/84a6934）这些行号在历史任何提交下都不成立，疑似从过期缓冲区读取。语义内容正确但定位失效，下游代理将无法用行号定位。
2. **[P2] 统计汇总.md:45「UI 总行数 ≈ 18,317」与同文档表格矛盾**：表格 33 行求和 = 21,671，与 `find ui -name '*.kt' -exec cat {} + | wc -l` 实测 21,671 完全一致；18,317 ≈ 21,671 − 3,353（settings 包），疑漏加 settings。该句的括注（"未含 galgame_full、newspaper、data"）也不成立（它们本就不在 ui/ 下）。
3. **[P2] 清单 §0.1「31 个 UI 包」**：handoff 的口径解释（30 一级 + chat/util=31；+component/model=33）可接受，建议在清单正文同步该解释（现仅 handoff 有）。

### 评价

覆盖率与绝大多数引用精度极高（20+ 处抽查仅 MessageList 一个文件系统性出错），口径透明、待补清单诚实。P1 修复后即可作为 Phase 4-6 QA 基线。

---

## 3. T4 audit-agent —— PASS-WITH-FIXES

### d) P0 发现亲自验证（评审标准核心项）

1. **commonMain 平台泄漏 —— 全部属实，影响评估成立**：
   - `shared/.../shared/data/AnchorRepository.kt:3` 确为 `import android.util.Log`，且**恰好 11 处** `Log.e(...)`（:25,36,53,93,105,146,160,167,181,188,196）✅；
   - `shared/.../shared/settings/SettingsRepository.kt:1399-1401` 确为 `java.text.SimpleDateFormat`/`java.util.Locale.CHINA`/`java.util.TimeZone`/`java.util.Date`（FQ 引用，非 import，audit 描述口径正确）✅；
   - 全量扫描证实这是 commonMain 的**仅有两处**平台符号（android.* 仅 AnchorRepository 1 文件；java.* 仅 SettingsRepository 3 行 4 符号）✅；
   - 影响评估正确：`shared/build.gradle.kts:18-24` 只有 `androidTarget`，单目标下 commonMain 并入 Android 编译故可解析；加 `iosTarget()` 后这两处立即编译失败。修复建议（expect 日志器 + kotlinx-datetime）恰当，体量极小（2 文件、12 处），不构成对 T1/整体计划的威胁，但 T1 加 iosTarget 前必须处理。
2. **密钥白名单 —— 结论属实**：通读 `PortableSettings.kt` 全部 119 行并做全仓库 key-ish 字面量扫描：白名单（14 string / 39 boolean / 74 int + 动态键）**不含任何 secret**。`api_key`(:115)/`vector_api_key`(:889)/`vision_api_key`(:947)/`asr_api_key`(:959)/`tts_api_key`(:975)/`newspaper_deepseek_key`(NewspaperService:62)/`asr|tts_provider_configurations` JSON(:1436-1450)/`group_auto_plan_token_*`(:1212-1229) 全部在白名单外 ✅；其余命中（topic_key、"token" Worker 输入键、apikey/access_key 为 TTS 错误串匹配、galgame 内嵌 HTML）均非密钥 ✅。残余风险两条（zip 明文、restore 端 apply 对恶意备份任意键的信任）披露准确。
3. **备份格式版本/校验 —— 全部属实**：`FULL_FORMAT="rhodes-backup"`、`FORMAT_VERSION=2`、`LEGACY_FORMAT_VERSION=1`、`MANIFEST_PATH/PAGES_ROOT/MAX_PAGE_BYTES=16MB`（BackupFileWriter.kt:18-25）✅；`schemaVersion=1` 两处调用点（BackupRestoreScreen.kt:124、ProblemChecker.kt:766）逐行验证 ✅；逐条目 SHA-256（Writer :56/63/139、Reader :73/215/230、OperatorPackage :94 含 size+sha256）✅。仅「formatVersion 写出 :59」一处引用错误（实为 BackupFileWriter.kt:83，BackupModels.kt:59 是 BackupPageEntry.pageIndex）→ P2。
4. **59 个 android 耦合文件** ✅：`grep -rln '^import android\.' app/src/main` = 59（报告「约 59」吻合）；7 个 androidMain actual 文件与 10 个 expect 声明（8 fun + 2 class）逐个核实 ✅。

### b) 可复核性（抽样验证）

- paths.md 抽查 ~20 处全部精确：LocalAudioController :44/:135/:129、ImageUtils :15/:28/:41/:47、ChatScreen :327（bg_）、GroupDetail :71（gbg_）、SettingsRepository :124-125（bg_uri）、PortableSettings :70/:75、BackupContentFilter :56/:71-74、SettingsMigration :204、PayloadRewriter :3-4/:48-58（/data/data、/data/user/0/、/files/ 特征串）、BackupRestoreCoordinator :86/:173、ExportHelper :14/:16、BackupExportCoordinator :72/:74-75、ProblemChecker :511、MainActivity :171/:179/:194 ✅；「/storage/ 0 命中、getExternalFilesDir 仅 androidTest :31/:130/:132」实测一致 ✅
- fields.md：BackupManifest 字段表、ExportPayload/BackupPayload/BackupMediaItem/ExportModels 定义位置、SettingsRepository 1631 行、ProviderConfigs 11 供应商均属实 ✅

### 发现的问题（修正清单 §4）

5. **[P2] platform-coupling.md「154 行 import」**：实测 147（三种计数法一致；该文自身明细表求和也是 147）。59 文件结论不受影响。
6. **[P2] fields.md 白名单计数**：实测 stringKeys **14**（文称 13）、booleanKeys **39**（文称 37）、intKeys **74**（文称 80）。安全结论不受影响（已独立复核），但数字是下游设置规格卡的引用基线，必须改对。
7. **[P2] fields.md「190 属性」**：实测 `get() =` 属性 **192**（文内另写「192 getter 写法」，自相矛盾）；setter 189 无误，合计 ≈381 与「约 370」口径解释成立。
8. **[P2] paths.md 两处行漂**：imageUriPattern :82→实为 :86；BackupMediaStager 完整性校验 :22-24→实为 :26-28（:22-24 是解压行）。
9. **[P2] hardcoded.md「res 仅 1 个文本节点」**：文本节点口径成立（app_name），但漏提 `values/themes.xml` 的 CJK 样式名 `Theme.罗德岛终端`（属性值），结论「文案全在 Kotlin」应加一句限定。top30 抽查（PromptModuleDefaults 170、AgentProfiles 71、BackupFileReader 58）一致；ChatViewModel 418 vs 我粗测 425、galgame MainActivity 395 vs 399 属口径内浮动，其差异说明框架合理。

### 评价

四个文档信息密度与精度高，P0 结论全部经独立复核成立，密钥/备份校验两项安全关键结论可信。问题集中在次级统计数字与个别行号（P2），不伤主线。**PASS-WITH-FIXES。**

---

## 4. 修正清单（文件 → 问题 → 怎么改 → 优先级）

| # | 文件 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | `ios-port/checklist/功能对照清单-v1.md` | MessageList.kt 行号全错（:523/:533/:552/:506/:559；实测 撤回=:420/:550、图片识别失败=:501、未送达=:520、复制=:413,417/:543,547），任何历史提交下都不成立 | 用 `grep -n '未送达\|图片识别失败\|复制\|已复制\|撤回' ui/chat/component/MessageList.kt` 复核更正（§1.1 ChatOperator 行、§2.1 复制/撤回/重试/图片重发行、§2.2 失败重试行）；建议对清单全部 file:line 跑一次脚本化行号校验 | **P1**（下游定位失效） |
| 2 | `ios-port/checklist/统计汇总.md:45` | 「UI 总行数 ≈ 18,317」与同文档表格求和 21,671 矛盾（= wc -l 实测；18,317≈21,671−3,353 疑漏 settings 包）；括注「未含 galgame_full/newspaper/data」不成立 | 改为 21,671，或按明确口径重算并写明口径 | P2 |
| 3 | `ios-port/checklist/功能对照清单-v1.md §0.1` | 「31 个 UI 包」口径解释仅在 handoff | 把 30 一级 + chat/util = 31 的解释抄进清单正文 | P2 |
| 4 | `ios-port/audit/platform-coupling.md:7,26` | 「154 行 import」应为 147（自身明细表求和即 147） | 两处更正为 147 | P2 |
| 5 | `ios-port/audit/fields.md:52` | 白名单计数 13/37/80 应为 **14/39/74** | 程序化重数 stringKeys/booleanKeys/intKeys 后更新 | P2（下游设置规格卡引用基线） |
| 6 | `ios-port/audit/fields.md:19,22` | 「formatVersion 写出 :59」实为 BackupFileWriter.kt:83（BackupModels.kt:59 是 BackupPageEntry）；「190 属性」与同文「192 getter」矛盾（实测 192） | 引用改 BackupFileWriter.kt:83；属性数统一为 192 或解释口径 | P2 |
| 7 | `ios-port/audit/paths.md:88,31` | imageUriPattern :82→:86；BackupMediaStager 校验 :22-24→:26-28 | 更正行号 | P2 |
| 8 | `ios-port/audit/hardcoded.md:58` | 「仅 1 个文本节点」未提 themes.xml 的 CJK 样式名（属性值，非文本节点） | 补一句「另有 themes.xml 样式名 罗德岛终端（属性值）」 | P2 |

T2 无修正项。另建议（不在修正清单内，供 lead 决策）：

- 计划书/可行性报告中「shared/commonMain 零 android./java.*」的结论已被 T4 证伪（2 文件 12 处）；建议在 Phase 0 文档更新时同步修订该口径。
- 设备级金样（DB 文件/备份包/媒体样本/渲染后提示词）依赖 T1 模拟器，T1 完成后应创建后续采集任务，直接沿用 `handoffs/baseline-agent.md` §4 的 6 步方案。
- 提示词编辑器自定义覆盖 `prompt_<type>_<mode>` 不在备份白名单（Android 现状如此）；iOS 按「行为不变」原则照搬即可，但建议在设置规格卡中明示该边界。

## 5. 写域越界检查（标准 e）

- `git -C RhodesLink status --short` = **空**；`git diff --stat` = **空**；无未跟踪文件；HEAD == tag commit。T2-T4 未在 RhodesLink 留下任何痕迹 ✅
- T1（p0-toolchain）当前亦无未提交改动可见（其写域为 RhodesLink gradle/shared 构建脚本，与本次评审无关）；若 T1 后续提交，用 `git log 3642d2c..HEAD` 对照即可。
- `ios-port/` 位于工作区根（RhodesLink 仓库外），T2-T4 产物均落在其各自声明目录（baseline/、golden/、checklist/、audit/、handoffs/），与任务板写域一致 ✅

## 6. 评审方法附录（可复现）

- 全部验证为只读：`git show/rev-parse/cat-file`、`grep/sed/wc/md5/diff`、Python 一次性脚本（行数/去重/JSON 校验），未修改任何被审文件。
- 关键命令示例：`grep -c '@Test'`（测试数）、`grep -rhoE 'Icons\.(AutoMirrored\.)?(Filled|Outlined|Default|TwoTone|Rounded|Sharp)\.[A-Za-z0-9]+'`（图标）、`grep -rln '^import android\.'`（耦合文件）、`find ... -exec cat {} + | wc -l`（行数）。

---

# 规格卡评审：backup-restore-minimal-spec（追加于 2026-11-22，评审 T10）

- 评审对象：`ios-port/specs/backup-restore-minimal-spec.md` + 配套产物 `ios-port/golden/backup/{make_minimal_backup.py, minimal-1.14.27.zip, minimal-1.14.27.md}` + `handoffs/backup-restore-loop.md`
- 基线变化说明：评审时 RhodesLink HEAD 已含 T1 的两个新提交（e509b2d 工具链升级、95e3166 iosMain actuals + commonMain 平台化）及 3 个未提交改动（ProblemChecker.kt / GroupChatViewModel.kt / libs.versions.toml，均为 Kotlin 2.x 兼容修复，属 T1 写域）。规格卡全部 oracle 锚定 tag `v1.14.27-android-baseline` @ `3642d2c`，**该 tag 不变**；且本卡引用的所有文件（data/backup/*、ExportPayload、SessionRepository.kt:43 等）在当前工作树行号仍逐一成立（已复核）。
- **结论：PASS-WITH-FIXES**（0 个 P1，4 个 P2 + 2 个 P3；核心契约、金样包、验收命令全部验证通过）。

## 1. 标准 1：13 条行为契约的 oracle file:line（抽查结果）

| 契约 | 引用的 oracle | 验证结果 |
|---|---|---|
| #1 读取/路径安全 | BackupFileReader.kt:32-55,236-244 | ✅ :34 路径 `\`→`/` 归一、:38 isSafeArchivePath、:39 重复路径拒绝、:40 目录跳过；isSafeArchivePath 定义于 :238（引用 :236-244 覆盖 companion，±1 行内） |
| #2 manifest 结构 | :57-69 | ✅ :58 缺 manifest、:61-63 format/version/scope 校验、:66-68 files 与 ZIP 严格相等，全部落在引用区间内 |
| #3 逐条目 SHA-256 | :44-49,70-74,213-232；Writer :56,63,139-141 | ✅ :44-49 digest 捕获、:70-74 比对抛「文件校验失败」、:213-231 readLimited、:139-141 sha256 helper；Writer 三处逐行核实 |
| #4 v2 分页 | :102-121；Planner :13-84,102-122；公式 :123-130 | ✅ :103-111 布局/禁单 payload/去重/集合相等、:112-121 逐页解码+标识+recordCount；:123-130 pageRecordCount 公式与「媒体不计入」属实 |
| #5 载荷标记 | :90-91 | ✅ 精确：:90 content.type=="full_backup"、:91 mediaIncluded 与 payload.media 一致性 |
| #6 引用完整性 | :132-207；Coordinator :135-155 | ✅ validateReferences 定义于 :132，5 个 ORPHAN_* 码（:196/:198/:200/:202/:205）与规格卡逐一相符，skippable 默认 true（BackupModels.kt:120）；filterIssues 于 Coordinator :135-155 |
| #7 恢复前安全备份 | Coordinator :79-82,169-188 | ✅ :81-82 CREATING_SAFETY_BACKUP→createSafetyBackup；:169-188 快照+媒体收集+写后 validate 回读+保留 5 份（drop(5)）全部属实 |
| #8 媒体 staging | BackupMediaStager.kt:9-32 | ✅ 防穿越 canonical 校验、mediaId 集合完整性检查（:26-28）属实 |
| #9 URI 重写三层 | PayloadRewriter :3-42,48-63 | ✅ 三层匹配（byOriginalUri :6-7 / canonical :11 / androidPrivateFilesKey :14,48-58）、重写范围（avatarUri、messages.content :20、user_avatar_uri :27、support_conversation :30、giftRecords :36、chatArchives :37）全部在引用区间 |
| #10 单事务删+插 | RestoreDatabaseExecutor :30-105（删 :47-55、插 :77-101） | ✅ runRestoreTransaction :30；删除顺序 :48-54（replyTurns→displayEvents→archives→historySegments→messages→sessions→groups）与「子先于父」相符；roles+chats 才删 roles（:71-74）；插入 :77（insertAllOperators）/:98（insertSession）/:100-101（insertAllMessages）精确 |
| #11 设置恢复与回滚 | Coordinator :96-111；PortableSettings :51-118 | ✅ :100-101 snapshot+clearForRestore、:103 apply、:107-110 失败→删媒体目录+回放旧设置；PortableSettings snapshot/apply/clearForRestore 定义区间 :51-118 属实 |
| #12 阶段编排 | Coordinator :58-133；枚举 :16-25 | ✅ 枚举 :16-24；运行序（VALIDATING :64→STOPPING :75→CREATING_SAFETY :81→RESTORING_MEDIA :85→RESTORING_DATABASE :95→REBUILDING :113→COMPLETED :119→FAILED :128-129）与规格卡序列一致；REBUILDING 失败不翻转结果（runCatching :115-116）属实 |
| #13 恢复后自检 | iOS 补强（无 Android oracle；计划书 line 179） | ✅ 计划书 :179 引用属实（Gate 2「Android 备份在 iOS 恢复后自检通过；导入失败不污染数据库」） |

边界区引用同步验证：Reader :16-21（validate→Invalid）、:10-13（maxEntries=1000/maxEntryBytes=100MB/maxTotalBytes=500MB）、:87/:237（单页 ≤16MB）、Coordinator :66-72（坏包删暂存输入）、:121-124（CancellationException 上抛+清理）、:125-129（FAILED）、:157-166（copyRestoreInput 失败删目标）、:58/:203（restoreMutex）、:34-40（BackupRestoreResult）、Maintenance :9-25（写围栏）、BackupModels :111-114/:124-127 —— 全部成立。

## 2. 标准 2：A5 边界覆盖

- 备份格式版本号 ✅（#2：format==rhodes-backup、formatVersion∈{1,2}、scope==full；数据与迁移含 v1 只读兼容与 v2 禁单 payload）
- 校验 ✅（#3 SHA-256 逐条目 + #4 分页 recordCount + #2 files 集合相等）
- 明确错误提示 ✅（错误文案均镜像源码原文：「缺少 manifest.json」「文件校验失败」「备份媒体文件不完整，已取消恢复」「恢复前安全备份校验失败」「分页记录数不一致」等）
- 密钥不进备份 ✅（数据与迁移列全 8 类密钥白名单外 + #11 Keychain 不动）
- 恢复失败可回滚 ✅（#10 DB 单事务、#11 设置快照回放+媒体目录删除、#12 FAILED 带 safetyBackup）
- 恢复后自检 ✅（#13 明确 iOS 补强：manifest.recordCounts vs DB 计数）

## 3. 标准 3：10 项验收可自动判定（已实跑）

| # | 实测 |
|---|---|
| 1 | `python3 make_minimal_backup.py --verify minimal-1.14.27.zip` → exit 0，6 组检查全 PASS ✅ |
| 2 | 重新生成后 `shasum -a 256` = 799b2ba0…（两次一致，逐字节相同）✅ |
| 3 | payload diff 样板（handoff §4）实跑 PASS（operators=1/sessions=1/messages=7/msg4 image/msg5 ai_json 内嵌 imageUri）✅ |
| 4 | 媒体字节翻转变体 → --verify rc=1「文件 SHA-256 不一致」✅ |
| 5 | 两变体实跑：formatVersion=3 → rc=1；删 manifest.json → rc=1（另两变体命令在 handoff，逻辑同）✅ |
| 6 | --verify 已含媒体一致性 + mediaId 检查 ✅ |
| 7-10 | `./gradlew :shared:iosSimulatorArm64Test --tests '*BackupRestoreGoldenTest*'` 等 4 条命令具体、断言明确（实现落地后可执行；规格卡已注明时序）✅ |

## 4. 标准 4：金样包与 writer/reader 源码一致性（实跑验证）

- manifest 布局与 `BackupFileWriter.kt:86-96` 逐项一致：字段顺序（format…pages）、`layout=paged_payload`、`excludedCategories` 固定四项（:92）、`files` 不含 manifest.json 自身、manifest 最后写入（ZIP 条目序末尾）✅
- 分页分配复刻 `BackupPagePlanner.kt:14-30`：operators→ROLES/0（media 随 index 0）、sessions→CHATS/0、messages→CHATS/10000 ✅；页内 payload 形状（content/displayEvents/…/media）与 BackupPayloadPage 一致，content.type=full_backup ✅
- mediaId 溯源独立复核：`sha256("/data/data/com.buzidongshouhuoji.privatechat/files/images/golden_1x1.png")[:24]` = `8b9e940c3c9d6d39c4a1dd8c`，与包内 mediaId/archivePath 完全一致（复刻 BackupMediaCollector.kt:64,71-72 的 canonical 语义）✅
- 记录字段数：OperatorExport 22 / SessionExport 12 / MessageExport 15 与 ExportPayload.kt:46-59/:86-92/:102-109 逐字段相符 ✅
- 校验逻辑镜像 Reader：路径安全/去重、manifest 三元组、files 集合相等、size+SHA-256、分页清单/标识/recordCount、引用完整性、媒体一致性（--verify 6 组输出与 Reader 检查点一一对应）✅

## 5. 标准 5：lead 三点裁决写入情况

- mediaId 直映射 ✅（#9 平台差异白名单明确「iOS 对应物为 mediaId 直映射，本条为 P0 差异」+ 风险 2 展开）
- getNextMessageId 自愈 ✅（数据与迁移「iOS 必须复刻该分配器」+ 风险 4；MessageRepository.kt:294-301 实测属实：`maxOf(内存值, DB max+1, persisted)`）
- 三层回滚 ⚠️（风险 1 以「**建议**：Phase 0 先做「事务回滚 + 媒体目录删除 + 设置回放」三层自动回滚…（列为开放项）」表述——内容正确但未记为「已裁决」，见修正 #3）

## 6. 修正清单

| # | 文件 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | `ios-port/specs/backup-restore-minimal-spec.md` 边界「磁盘满 / IO 失败」行 | oracle 映射错误：媒体落盘失败并不删除已解目录——:108-111 是 **DB 恢复失败** 的清理路径（媒体目录删除+设置回放）；媒体 staging 失败实际走外层 catch（:125-129），Android 1.14.27 只删 restoreInput、**残留部分解出的 restored_media 目录** | 按事实改写该行：「媒体 staging 失败 → FAILED + 输入暂存清理（:125-129），部分媒体残留为 Android 现状；DB 失败 → 删媒体目录+回放设置（:108-111）」；若 iOS 决定任何失败都清理部分 staging，写入「平台差异白名单」作为补强项（建议采纳，理由：沙盒无副作用，且验收 #9 的「媒体目录已删」才能无条件成立） | P2 |
| 2 | 同上，「数据与迁移」删除顺序列举 | reply_turns 在源码中先于 displayEvents（RestoreDatabaseExecutor.kt:48），规格卡排到了 sessions 之后，与 #10「同一删除/插入顺序」的承诺不一致 | 按 oracle :47-55 顺序重写：reply_turns → chat_display_events → chat_archives（+history_segments）→ chat_messages → chat_sessions（含 groups） | P2 |
| 3 | 同上，「风险与开放问题」#1 | 三层回滚仍以「建议」表述，lead 已裁决（mediaId 直映射 / 三层回滚 / getNextMessageId 自愈三点） | 新增「lead 裁决记录」小节或改写为「已裁决：Phase 0 采用三层自动回滚（事务+媒体目录+设置回放，Android 同款）」；仅将「iOS 安全备份文件保留策略（是否落盘 before_restore_*.rbackup、保留几份）」保留为开放项 | P2 |
| 4 | 同上，验收表 #3/#4/#5 | 命令为占位（「命令样板见 handoff §4」），handoff 属临时交接物，不应作为验收的稳定依据 | 把 handoff §4 的四段完整命令内联进规格卡（reviewer 已实跑 #3/#4 与 #5 的两个变体均通过，可直接粘贴） | P2 |
| 5 | 同上，验收 #6 | 「mediaId==local-image/<archivePath 文件名去扩展>」是构造恒等式（archivePath 由 stableId 派生），未验证溯源规则 | 补真正的溯源断言：`mediaId 后缀 == sha256(canonicalPath).hexdigest()[:24]`（reviewer 已验证金样成立，canonical 路径 = `/data/data/<pkg>/files/images/golden_1x1.png`） | P3 |
| 6 | `ios-port/golden/backup/minimal-1.14.27.md` §2 表 | 「BackupFileReader.kt:37-39,236-244」与「:59-63」等行号区间含 ±1 行漂移（isSafeArchivePath 定义于 :238；format/scope 校验于 :61-63） | 下次修订时按精确行号校正（不影响结论，语义全部成立） | P3 |

## 7. 给 lead 的确认项（原 handoff §5 开放问题，规格卡内已附建议，供最终裁决）

1. 三层回滚方案（事务+媒体目录+设置回放）——按「已裁决」记录即可（见修正 #3）；
2. iOS 安全备份文件保留策略——Phase 0 建议不落盘 before_restore_*.rbackup，留 A5 完整实现（规格卡风险 1）；
3. mediaId 路径派生语义在导入 Android 备份时保持 stableId 不变（不做内容哈希重算）——规格卡风险 3 建议合理，可随实现确认。

---

# Phase 0 终审（追加于 2026-11-23，Gate 0 六条闭合 + T9/T10/T12/T13 交付物）

- 评审对象：T9 ios-data-base、T10 backup-restore-loop、T12 timeline-prototype、T13 bg-task-probe 的 spec/handoff/artifact，及 T1/T6/T7/T8/T11 的 Gate 0 证据链。
- 评审方式：全部只读（read/grep/git/python3 静态验证），**未执行任何构建**；数值核验以已落盘的产物（测试源码断言、artifacts JSON、构建/回归日志、git 提交）为准。
- **总结论：Gate 0 六条全部 PASS（5 条证据闭环 + 1 条文档索引滞后需更新）；Phase 1 可以启动。** 修正清单 4 条（1×P2 + 3×P3），均为文档性，无阻塞项。

## 1. Gate 0 六条逐条核实

### Gate 1｜Android 构建不回归（2.1.21 / 2.3.21 各一次；324 单测全绿）→ PASS

- 基线 APK 已归档：`ios-port/baseline/app-1.14.27-baseline.apk`（97.6MB）+ 三段构建日志（baseline-preupgrade / post-upgrade / post-kotlin-2.3.21-android，均 BUILD SUCCESSFUL）。
- 2.1.21 = commit e509b2d；2.3.21 最终态 = commit **7cad292**（lead 裁决选项 a）。
- **324 单测回归证据已落盘**：`ios-port/baseline/regression-324-tests.txt`（`:app:testDebugUnitTest` 实跑 BUILD SUCCESSFUL、EXIT=0，非 UP-TO-DATE）；我进一步用 `app/build/test-results/testDebugUnitTest/*.xml` 交叉验证 = **39 文件 / 324 tests / 0 failures**。✅
- 说明：p0-toolchain.md §遗留第 5 条仍写「324 未跑」，属回归完成后未回写（见修正 #2）。

### Gate 2｜数据库打开并完成迁移（fresh 28 表 + 18→24 升级路径）→ PASS（附口径说明）

- T9 交付：`DatabaseMigrationTest`（3 用例，模拟器实跑，T9 handoff 声称 28/28 全绿）。我逐行读测试断言：
  - fresh 冷开：断言表集 == 28 张 .sq 表（缺一/多一均失败）、`Schema.version==24`、`PRAGMA user_version==24`、读写探针；✅
  - 升级：DROP COLUMN sourceType + `user_version=18` 模拟 v18 旧库 → 重开断言 user_version=24、`data_repair_runs` 出现（迁移 18）、sourceType 列恢复（迁移 20）。✅
- **语义自洽性独立复核**：SQLDelight 2.2.1 生成代码 `RhodesDatabaseImpl.kt:577-885` 的 migrate 判断是 `oldVersion <= N && newVersion > N` —— 18→24 升级确实会执行迁移 18（`18<=18 && 24>18`），测试期望「data_repair_runs 出现」成立，无矛盾。✅
- **口径诚实**：T9 handoff §遗留 2 明确「仅验证 18→24 段（迁移 18-23）；0→18 段（1-17，ALTER 类）需真实旧版 DB 金样再验」——PHASE0.md Gate 2 的「18→24 升级路径」表述与实测完全一致，未夸大。该 0→18 段验证建议列入 Phase 1 早期（Android 旧版 DB 文件与 iOS 通用 sqlite 格式，可直接拷贝）。

### Gate 3｜真实流式请求可返回可取消（首 token 114ms / 取消 1ms / 3 类超时）→ PASS

- `KtorDarwinStreamingTest`（8 用例 a–h）断言与 handoff 数字自洽：首 token 窗口 60–2000ms（实测 114ms，mock 100ms 发送）；取消 `cancelAndJoin<1s`（实测 1ms）且断言未收满 10 token；三类超时阈值 1.5s/2s/1.5s 与断言窗口（1200–2500 / 1500–3500 / 1200–2500ms）匹配实测 1508/2008/1506ms；半包断连断言精确等于 `data: partial-to`；500+JSON、自定义端点各 1 用例。✅
- `handoffs/mock-sse-server.py`：存在（133 行）、`py_compile` 语法通过、逻辑自洽：`/sse` 100ms 后每 150ms 发 10 token（→114ms 可解释）；`slow-first` 3s / `only-headers` 30s 静默 / `hang` 5s 无头（→三档超时分别触发）；`disconnect` 发半包即关；BrokenPipe/ConnectionReset 打日志（→取消的服务器侧证据通道）。未实跑 SSE（评审约定），仅静态+语法验证。✅
- ATS loopback 豁免为实测结论（测试即打在 127.0.0.1 上）。✅

### Gate 4｜Android 备份可恢复到 iOS（金样 zip → iOS DB 等价恢复，JVM+iOS 双路径 24 测试）→ PASS

- commit d60cc36（T10 实现）：21 文件**全部新增**于 `shared/src/{commonMain,iosMain,commonTest,androidUnitTest,iosTest}/…/shared/backup/`，0 行改动 app/ 与既有 shared 代码，写域合规 ✅。
- 「24+24」口径核实：commonTest 4 个测试文件 @Test = 5+9+5+5 = **24**；`BackupRestoreGoldenTestJvm`（JdbcSqliteDriver 内存库）与 `BackupRestoreGoldenTestIos`（NativeSqliteDriver 唯一库名 + 独立 NSUserDefaults suite）为 0-@Test 的平台子类，双路径各自执行同一批 24 用例——「JVM+iOS 各 24」表述准确 ✅。
- 规格卡此前的 6 条修正已全部落笔（评审结论节已记录 1-6 项），lead 已批准；金样包 self-verify/确定性/坏包矩阵/`mediaId=sha256(canonical)[:24]` 溯源在前次评审已实跑通过 ✅。

### Gate 5｜2,000 条消息滚动达标 → PASS（PHASE0.md 索引仍标 🔄，见修正 #1）

- `perf-result-lazyvstack.json` 与 handoff 数字逐项一致：2,000 条 × 3 轮、7,940 帧、均值 FPS **59.56**（验收线 50）、P95 FPS 59.77、P99 帧间隔 18.27ms、max 65.07ms（仅首轮物化一次）、dropped15=55（0.69%）、dropped20=7（0.09%）、phys_footprint 峰值 42.5MB（惰性实例化生效）。device=iPhone 17 / iOS 27.2 / simulator:true ✅。
- 内部一致性：7940 帧 / 133.25s = 59.6fps 与均值吻合；rounds[] 三段与总体吻合 ✅。
- 结论克制且正确：LazyVStack 通过、未触发 UICollectionView 对比（触发条件已写死：真实图片负载后均值<50fps 或 dropped20>2% 等）；模拟器≠真机、无真实图片负载等 6 条遗留如实列出 ✅。

### Gate 6｜最低 iOS 版本（18+ / 26+ 玻璃）与玻璃验收标准冻结 → PASS

- `玻璃设计规范-v1.md` 冻结：token 表含 iOS 26 原生映射 + **iOS 18 兜底映射**（`.ultraThinMaterial`/`.thinMaterial`）；玻璃分支集中 `GlassPanel`/`GlassInputBar` 的 `if #available(iOS 26.0, *)`；验收清单（一屏 ≤5 玻璃元素、玻璃上不放正文、对比度 ≥4.5:1、降透明模式禁用投影等）；T8 玻璃壳 Demo 编译通过（人类签字点仍为玻璃观感）。✅

## 2. git 仓库核查（7cad292 / d60cc36 / d8b282d）

- 分支 `ios-port`，三提交按序存在（3642d2c → e509b2d/95e3166 → 7cad292 → d60cc36 → d8b282d）；**工作树干净（git status 空）** ✅。
- **7cad292**（T11，Kotlin 2.3.21）：libs.versions.toml(10±) + ProblemChecker.kt(10±，`arrayOf<Any>` 显式化) + GroupChatViewModel.kt(220 行，`sendGroupMessage` 巨型挂起 lambda 逐字抽取为 `executeGroupTurn`，适配 JVM 64KB 方法上限；代码注释声明控制流语义一致，p0-toolchain.md §3.1 有等价性论证）——属 lead 裁决选项 a 的「最小修复」范围 ✅。
- **d60cc36**（T10）：纯新增 shared 备份包（见 Gate 4），无 Android 行为改动 ✅。
- **d8b282d**（T9）：shared/build.gradle.kts(19±：commonTest kotlin-test、deviceId="iPhone 17"、androidUnitTest 源集) + iosMain 四处新代码/骨架修订 + iosTest 28 用例 + testutil —— 全部在授权域（iosMain/iosTest/build 最小 diff）✅。
- 未发现任何 app/ 之外或越权文件；`local.properties` 未入库 ✅。

## 3. 计划书 §9 立即执行清单第 2 批对齐

| 第 2 批条目 | 状态 | 证据 |
|---|---|---|
| 跑通 iOS SQLDelight / Ktor Darwin / Keychain / 文件读写 | ✅ | T9：模拟器 28 测试（3+8+6+6+5）全绿，双 target framework 链接成功 |
| Android 备份 → iOS 最小恢复闭环 | ✅ | T10：金样 zip 已冻结（self-verify/确定性通过）+ JVM/iOS 双路径 24 测试 |
| 2,000 条消息时间线原型 | ✅ | T12：perf JSON 均值 59.56fps 达标，LazyVStack 结论落定 |
| 后台任务实验，确定 iOS 后台产品文案 | ✅ | T13：BGTaskScheduler 模拟器不可用（code=1）实证 + 深触发链 5/5 + 通知全链路；spec §3 给出可直接引用的中文文案（「不承诺准点」「打开 App 立即补上」） |
| 聊天记录导入可行性验证（≤3 代理日）→ 做/不做 | ✅ | T6：**做**，纳入一期 4.0–6.5 代理日（≤8 代理日决策线内） |
| Phase 0 评审：路线 A 确认 / A7 决策预研 / 二期候选冻结 | ✅ | 路线 A：SwiftUI 壳 Demo + LazyVStack 结论；A7：**拆分移植**（43%–65% < 80% 触发线）；二期候选冻结：清单 §1.8 + lead 裁决记录 |
| （备注）抽取第一个共享聊天用例 | ⏭ | 不在 Gate 0 六条内，属 Phase 1 首个交付物——列入下方 Phase 1 建议 |

## 4. T9 handoff 关键数据可复现性抽查

- **「23 迁移在 upgrade 路径」**：口径准确的前提是「18→24 段（迁移 18–23）已实测，0→18 段待真实旧库金样」——handoff 与 spec 均如此陈述，无夸大；生成代码 `oldVersion <= N` 语义我已从 RhodesDatabaseImpl.kt 复核，测试期望自洽 ✅。
- **首 token 114ms**：mock 服务器 100ms 发送 + 测试断言窗口 60–2000ms，逻辑自洽；`mock-sse-server.py` 语法校验通过（未实跑 SSE，按评审约定）✅。
- **T13 触发证据**：`artifacts/bgtask-log.json`（63 行 NDJSON）逐项复核：schedule-error code=1 unavailable ×10、pending count=0、7 task-start / 7 task-end / 3 expiration-fired、grantedSec 从时间戳反算（10.1/7.5/10.2/15.2/10.0/12.0/10.5s）与 handoff 表格**逐一吻合**、notification-sent err=nil + delivered count=1、provisional granted=true status=3 ✅。5/5 连触发成功率的证据链完整。
- **T12 帧率**：JSON 与 handoff 数字逐项一致（见 Gate 5）✅。

## 5. 修正清单（全部文档性，不阻塞）

| # | 文件 | 问题 | 怎么改 | 优先级 |
|---|---|---|---|---|
| 1 | `ios-port/PHASE0.md` | T12（:33）/T13（:34）与 Gate 0 第 5 条（:45）仍标 🔄，与已落盘证据不符（perf JSON 00:18、bgtask artifacts 00:40） | 三处改 ✅，Gate 5 注明「LazyVStack 均值 59.56fps（验收线 50），真机复测列 Phase 3」 | **P2**（索引文档是后续代理的第一入口） |
| 2 | `ios-port/handoffs/p0-toolchain.md` §遗留第 5 条 | 仍写「324 个单测…本轮因时间未跑」，但 `baseline/regression-324-tests.txt`（11-23 00:10）+ test-results XML（324/0）已证明回归跑完全绿 | 改为已完成并引用两个证据文件 | P3 |
| 3 | `ios-port/specs/ios-data-base-spec.md` 评审结论 | 「reviewer：待批」 | 由 lead 回填本终审结论（本代理不改 T9 文件）：Phase 0 验证件通过，开放项（settings 分工裁决、Keychain 真机、0→18 段旧库金样）转 Phase 2 | P3 |
| 4 | `ios-port/handoffs/backup-restore-loop.md` §1 表头 | 「未碰 RhodesLink/shared/src（实现留待批准）」与 §7 工作记录的「2026-11-23 实现完成」并存，表头表述过期 | 表头补一行「（2026-11-23 已按批准规格卡实现，commit d60cc36）」 | P3 |

## 6. 对 Phase 1 启动的建议（重点：领域层抽取顺序与规格卡优先级）

### 6.1 抽取顺序（依据 checklist 统计与金样/测试资产分布）

1. **第一批：提示词 + 记忆纯逻辑层**（PromptTemplates 1291 行 / PromptModuleDefaults 387 / MemoryV2PromptTemplates / MemoryV2Pipeline 1347 / SharedUtils 1030 / UnifiedMemoryContext / MemoryRanker）。理由：a) 无平台依赖（唯一 commonMain 泄漏两处已由 T1 平台化，本批零障碍）；b) 金样已冻结（golden/prompts raw 114 件 + inventory 全表），是「金样 diff 门禁」最先可执行的战场；c) 是聊天状态机与 A7 管线的地基，抽取顺序上必须先于状态机。
2. **第二批：私聊轮次状态机**（ChatViewModel 3829 行 → 共享域服务）：sendMessage/cancel/retry/regenerate/resumePersistedReply/撤回/存档/滚动摘要。理由：Gate 1 验收（提示词/记忆测试 + 共享层不依赖 Android）的核心；与 T9 已验证的流式机制、T13 的延迟任务层直接对接；`getNextMessageId()` 自愈复刻随本批落地（T10 风险 4 的依赖）。
3. **第三批：群聊状态机**（GroupChatViewModel 2877 行）：注意 T11 已把 `sendGroupMessage` 拆出 `executeGroupTurn`（64KB 上限适配），抽取时直接沿用该结构，避免二次拆分。
4. **第四批：记忆生成/召回/注入 + 知识库管线**（MemoryV2 全链 + A3）：依赖第一批的模板层；金样 diff 与限额/优先级行为测试同批交付。
5. **第五批：A7 决策实现**（拆分方案，5.0–7.5 代理日）：通用「延迟任务层」薄层 + 动态定时回复 + 记忆维护。规格蓝图已由 T13 spec §4 给出（唯一键+退避+启动 reconcile+前台 Timer 主通道/BGTask 兜底），可直接出规格卡。

### 6.2 规格卡优先级（规格卡先行纪律）

- **P0**：① 提示词模板与变量替换（oracle = PromptTemplates/MemoryV2PromptTemplates + PromptPlaceholderRegistry；验收 = golden/prompts raw diff）；② 私聊轮次状态机（oracle = ChatViewModel 行为契约清单；验收 = 超时/取消/重试/撤回 324 单测迁移）；③ 消息 ID 分配器 + MediaRepository 正式化（T9 遗留 5 与 T10 风险 4 的收口点）。
- **P1**：④ 群聊状态机（含 executeGroupTurn 结构说明）；⑤ 记忆 V2 管线（L1-L3 + 注入顺序/限额）；⑥ A7 延迟任务层（T13 spec §4 蓝图转规格卡）。
- **Gate 1 前建议补的验证项**：升级路径 0→18 段旧库金样（Android 旧版 DB 拷贝，sqlite 通用）；真机冒烟（BGTask 真实调度/provisional 升级弹窗/Keychain/帧率复测）——建议排在 Phase 1 中段，避免把真机差异全部堆到 Phase 7。
- **风险备忘**：kotlinx-datetime `0.7.1-0.6.x-compat` 的 `Clock.System` 迁移债（p0-toolchain §遗留 8）；324 单测向 commonTest 分批迁移，每批保 JVM 回归（回归基线已具备：regression-324-tests.txt）。
