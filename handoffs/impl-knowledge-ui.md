# T48 实现 handoff：知识库 UI（导入 / 切块 / 索引 / 召回测试）

> 代理：impl-knowledge-ui（task-48，P1：知识库 UI）｜规格：`ios-port/specs/knowledge-ui-spec.md`（本卡产出）
> 上游：`ios-port/specs/memory-v2-spec.md` 知识库部分（T21 契约 22-24）、`ios-port/handoffs/impl-memory.md`（T24 管线）、
> `ios-port/specs/ios-bridge-spec.md`（桥接纪律）
> 状态：**实现完成；Shared.framework 重链成功；xcodebuild BUILD SUCCEEDED；iPhone 17 / iOS 27.2 模拟器
> `--knowledge-selftest` 全链路 PASS（导入→切块→索引→召回→删除→DB 核对）+ sqlite3 直查 + 4 张截图**
> git：**不提交**（lead 统一提交）；写域见 §1。

## 1. 写域 / 交付物

| 路径 | 类型 | 说明 |
|---|---|---|
| `shared/src/iosMain/.../iosbridge/KnowledgeBaseBridge.kt` | **新增**（~640 行） | 知识库桥 + 12 个 DTO：列表 / 详情（切块预览）/ 状态查询 / 创建文本 / 文件导入（bytes + path）/ 重命名 / 删除 / planIndex / index（进度回调）/ retryFailedChunks / invalidate / 干员关联 / 召回测试；内部按真实依赖组装 `KnowledgeBaseService`（T24 域门面） |
| `shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 修改（+~14 行） | `knowledgeBridge` 实例 + `fun knowledge(): KnowledgeBaseBridge`（追加式，保留 T47 的 `MemoryBridge(chatRepo, settingsRepo, …)` 与 T49/T50/T51 桥） |
| `iosApp/RhodesLink/Bridge/KnowledgeClient.swift` | **新增**（~470 行） | 桥的 Swift 包装（`Result`/async、`KnowledgeBookItem` 等 10 个值类型、DocumentPicker 文件暂存 `KnowledgeFileStager`） |
| `iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 修改（+3 行） | `knowledgeBridge` accessor（不向上导出 Kotlin 类型） |
| `iosApp/RhodesLink/Features/Knowledge/KnowledgeListView.swift` | **新增** | 列表（名称/切块数/索引徽标/更新时间/关联数）+ 新建入口 + 左滑删除（二次确认）+ 自检卡片 + 自检后自动进入演示库详情 |
| `iosApp/RhodesLink/Features/Knowledge/KnowledgeDetailView.swift` | **新增** | 头部摘要 / 索引与重建（进度、重试失败块、清除索引、远程确认）/ 召回测试（干员+query→命中块+分数）/ 关联干员 / 切块预览（前 50）/ 重命名 / 删除 |
| `iosApp/RhodesLink/Features/Knowledge/NewKnowledgeView.swift` | **新增** | 新建 Sheet：粘贴文本 或 fileImporter（TXT/MD/UTF-8）→ 真实导入管线 |
| `iosApp/RhodesLink/Features/Knowledge/KnowledgeAutomation.swift` | **新增** | `--knowledge-selftest` 全链路自检 + 独立日志 `Documents/knowledge-selftest.log` |
| `iosApp/RhodesLink/Features/Features/FeaturesView.swift` | 修改（+~90 行） | 「数据」分区新增「知识库」入口行（`NavigationLink` 直接入栈，不改 AppRouter）；`navigationRow`/`featureRowLabel` helper；`--knowledge-selftest` 全屏展示钩子 |
| `ios-port/specs/knowledge-ui-spec.md` | **新增** | 页面结构 / 桥接契约 / 数据映射 / 验收 / 限制 |
| `ios-port/handoffs/evidence/t48-*` | **新增** | 框架链接日志、xcodebuild 日志、自检日志、DB 核对、4 张截图、运行日志 |

**未改动**：Android `app/`、`shared/commonMain`（零改动，纯 iosMain 桥 + Swift）、`ChatEngineBridge.kt`（T45）、
`Settings` 页（T46）、`Memory` 页（T47）、`AppRouter.swift` / `RouteView.swift`（避免与 T46/T47/T49/T50/T51 路由改动冲突）。

## 2. 桥接契约（摘要，完整见规格卡 §2）

- 线程：回调一律 Main；DB/向量在 `Dispatchers.Default`。
- 错误：`onResult` 第二参数中文文案（桥内 `errorText(t, prefix)`）。
- 关键语义：
  - `createText` / `importFile` → `KnowledgeBaseService.saveText/importFile`（切块同步入库；`vectorProviderMode=local` 时导入后自动后台索引，与 Android 一致）。
  - `index` → 全量 `indexBook(rebuildAll=true)`，进度 `(done,total)` 回调；`retryFailedChunks` 只补 `indexedAt<=0/indexError` 块。
  - `invalidate` → 清向量分区 + 切块索引标记，状态回 pending（保留正文/切块）。
  - `recallTest` → `recallForPrivateChat`（与聊天注入同链路：3 条 / 1200 字 / 4.5s 预算）；`reason` 原因码映射中文。
  - 删除 → `indexService.cancelAndRemove` + `repository.delete`（单事务删 book/chunks/assignments/vector_memories）。
  - `setAssignment` → `replaceAssignmentsForKnowledgeBase`（召回前置，Android 角色知识库绑定同语义）。
- 文件导入：核心 `importFile(fileName, bytes)`；Swift DocumentPicker 走 `importFileFromPath(fileName, path)`
  （Kotlin `ByteArray` 不直接导出 Swift，路径版内部读 bytes 后走同一管线）。

## 3. 验证证据（`ios-port/handoffs/evidence/`）

### 3.1 构建

| 文件 | 结果 |
|---|---|
| `t48-framework-link.log` | `:shared:compileKotlinIosSimulatorArm64 --rerun-tasks` + `:shared:linkDebugFrameworkIosSimulatorArm64` **BUILD SUCCESSFUL**；Shared.h 导出 `knowledge()` / `KnowledgeBaseBridge` / 全部 `Knowledge*Dto`（grep 已核） |
| `t48-build.log` | `xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData-t48 CODE_SIGNING_ALLOWED=NO` → `** BUILD SUCCEEDED **` |

### 3.2 `--knowledge-selftest`（`t48-selftest.log`）

启动：`xcrun simctl launch $DEV com.rhodeslink.app --tab features --knowledge-selftest`
（最终证据跑在隔离的 iPhone Air / iOS 27.2 模拟器 `D7C7AC73-…`：iPhone 17 当时被其它并行任务占用；
在同一 App 二进制上，12:26 首轮 iPhone 17（`A63CD17A-…`）也全 PASS，两次结果一致。自检幂等：开跑前先清理上次遗留的
`T48 自检知识库*` 与演示书，因此可重复执行。）

```
12:38:05 === knowledge selftest start ===
12:38:05 cleanup previous book id=kb-7136260… name=罗德岛通讯协议（演示） error=-
12:38:05 baseline books=0
12:38:05 PASS createText id=kb-8626456849… chunks=2 status=pending
12:38:05 PASS importFile(bytes) id=kb-5625768017… chunks=4 format=md status=pending
12:38:05 PASS planIndex chunks=2 remote=false confirm=false
12:38:05 PASS planIndex.localMode requiresUserConfirmation=false
12:38:05 PASS index total=2 succeeded=2 failed=0 after=已索引
12:38:05 PASS index.progress lastDone=2
12:38:05 PASS status indexed=2/2 status=已索引
12:38:05 PASS listOperators count=147 picked=amiya(阿米娅)
12:38:05 PASS assignOperator assigned=["amiya"]
12:38:05 PASS recall hits=2 topScore=0.3953 reason=ok
12:38:05 recall hit[0] … score=0.3953 injected=true
12:38:05 recall hit[1] … score=0.2165 injected=true
12:38:05 PASS delete error=-
12:38:05 PASS delete.detailGone
12:38:05 PASS delete.fileBook error=-
12:38:05 PASS delete.countBackToBaseline count=0 baseline=0 ids=[]
12:38:05 demo book created id=kb-5584930314… chunks=4
12:38:05 demo book index succeeded=4 failed=0 status=已索引
12:38:05 demo book recall hits=3 reasonText=命中 3 条相关块
12:38:05 === knowledge selftest done (passed=true) ===
```

### 3.3 DB 核对（`t48-db-check.txt`，运行中容器 `Library/Application Support/rhodes.db`）

```
books|1  chunks|4  assignments|1  vectors|4
罗德岛通讯协议（演示）  ready  rawLen=670
chunks: #1 罗德岛通讯协议 / 第七紧急通讯频道 (indexed=1) … #4 新人登记流程 (indexed=1)
operator_knowledge_bases: amiya → kb-…(enabled=1)
```

（自检创建的 2 本测试书已在自检内删除，列表回基线 0 → 演示库 1 本为 UI 截图用。）

### 3.4 截图

| 文件 | 内容 |
|---|---|
| `t48-features-entry.png` | 功能 Tab「数据」分区「知识库」入口行 |
| `t48-knowledge-list.png` | 知识库列表（自检 PASS 卡片 + 演示库行：已索引徽标 / 4 块 / 更新时间） |
| `t48-knowledge-detail.png` | 详情页（头部摘要 + 当前状态 + 本地哈希嵌入计划 + 索引按钮） |
| `t48-knowledge-recall.png` | 召回测试结果：阿米娅 + query → 命中 3 条相关块与分数 0.485 / 0.208 / 0.181 |
| （详情中段） | 「关联干员」全量名册与切块预览卡（147 位干员，见 §5 限制 7） |

运行环境：最终证据跑 iPhone Air / iOS 27.2 模拟器（`D7C7AC73-8A9A-4714-A3D6-231A4B605AE6`，iPhone 17 被并行任务占用）；
12:26 首轮 iPhone 17（`A63CD17A-…`）同二进制全 PASS。`t48-run.log` 记录安装/启动/截图/取容器全过程。

## 4. 复跑方式

```bash
source ios-port/toolchain/env.sh
cd RhodesLink
./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64
cd iosApp
xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath DerivedData-t48 CODE_SIGNING_ALLOWED=NO
xcrun simctl install <DEV> DerivedData-t48/Build/Products/Debug-iphonesimulator/RhodesLink.app
xcrun simctl launch <DEV> com.rhodeslink.app --tab features --knowledge-selftest
CONTAINER=$(xcrun simctl get_app_container <DEV> com.rhodeslink.app data)
cat "$CONTAINER/Documents/knowledge-selftest.log"
sqlite3 "$CONTAINER/Library/Application Support/rhodes.db" "SELECT count(*) FROM knowledge_bases;"
```

## 5. 限制 / 已知问题

1. **深链与 AppRouter 路由未接**：入口仅功能 Tab 行（直接 `NavigationLink`），未新增 `Route.knowledge*`，
   避免与并行任务（T46 设置路由 / T49-T51 新页面）改 `AppRouter.swift`/`RouteView.swift` 冲突。
2. **切块编辑/启停未做**：Android 详情页有单块编辑与启用开关，本波只读预览（桥可后续扩展 `updateChunk`/`toggleChunk`）。
3. **文件格式**：仅 UTF-8 TXT / MD；PDF/DOCX 被 shared `KnowledgeBaseTextProcessor` 明确拒绝（文案提示先转文本）。
4. **远程向量模式**：确认弹窗已接（预计请求数/费用）；进行中索引无「取消」按钮（`KnowledgeBaseIndexService.cancelActiveIndex` 有能力，UI 未接）。
5. **召回测试**：仅私聊同链路（`recallForPrivateChat`）；群聊 `recallForGroupChat` 未做独立测试入口。
6. **角色详情页的知识库分区**：Android 角色详情有知识库绑定区；本卡把绑定放在知识库详情页「关联干员」，未改角色页（T49 写域）。
7. **关联干员长列表**：详情页直接展示 operators 全量（本环境 147 位），页面较长；后续可加搜索或只显示「已关联 + 常用」，
   本轮未做（召回本身只按已关联过滤，功能正确）。
8. **环境事件（影响所有并行代理，供 lead 参考）**：
   - 12:00-12:15 期间共享 Gradle 缓存（`ios-port/toolchain/gradle-home/caches/8.10.2`）被中断的并行构建损坏，
     表现为 `Unable to start the daemon process` / `EOFException: Detect premature EOF` / `Could not generate … LibrariesForLibs`。
     处理：清理 `caches/8.10.2` + `caches/jars-9` + `caches/journal-1`（保留 modules-2 离线依赖）与项目 `.gradle/configuration-cache` 后恢复正常（重生成约 2-3 分钟）。
   - 多个代理并发 `gradlew`/`xcodebuild` 时会互相破坏 `shared/build` 输出（曾出现 klib 缺失、framework 头/二进制不一致导致的链接 undefined symbol）；
     建议 lead 后续安排构建串行（当前各任务各自排队仍会撞车）。本卡最终验证是在 framework 重链成功、其它写域稳定后完成的。

## 6. 结论

知识库 P1 功能范围（列表/创建/重命名/删除/文本与文件导入/切块预览/plan+index/重试失败块/invalidate/召回测试/状态查询）
全部落地并经真实 DB + 模拟器端到端验证；与 T45 的聊天知识库注入共用同一 `KnowledgeBaseService`/`recallForPrivateChat` 链路，
本卡未改 `ChatEngineBridge.kt`。
