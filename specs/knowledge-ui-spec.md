# T48 知识库 UI 规格卡（导入 / 切块 / 索引 / 召回测试）

> 作者：impl-knowledge-ui（task-48）｜日期：2026-11-23｜分支 ios-port（Kotlin 2.3.21 / Xcode 27.2 / iPhone 17 iOS 27.2）
> 上游依据：`ios-port/specs/memory-v2-spec.md` 知识库部分（契约 22-24：导入 / 索引 / 召回）、
> `ios-port/handoffs/impl-memory.md`（T24 记忆 V2 + 知识库管线）、`ios-port/specs/ios-bridge-spec.md`（桥接纪律）。
> Android oracle：`shared/src/commonMain/.../knowledge/*.kt`（5 文件，KMP 已共享）、
> `KimKnowledgeScreen` / `KnowledgeDetailScreen`（Android 原版 UI，1.14.27）、
> `KnowledgeBaseService` 域门面（T24 抽取，`shared/domain/knowledge/KnowledgeBaseService.kt`）。
> 结论先行：**iOS 知识库全链路已落地并模拟器实跑**：列表 / 新建（粘贴文本 + TXT/MD 文件导入）/
> 重命名 / 删除（含切块+关联+向量）/ 切块预览 / 索引（plan + index + 进度）/ 重试失败块 / 清除索引 /
> 召回测试（query → 相关块 + 分数）/ 干员关联 / 状态查询。**不做**：切块编辑与启停、知识库分页管理
> （Android 大列表）、导入 PDF/DOCX（shared 层明确拒绝二进制格式）、群聊专属召回测试（桥只暴露私聊同链路）。

## 1. 页面结构

### 1.1 功能 Tab 入口（`Features/Features/FeaturesView.swift`）

在「数据」分区「记忆管理」下新增 `知识库` 行（图标 `books.vertical.fill`，副标题「导入资料 · 切块索引 · 召回测试」）。
导航用 `NavigationLink { KnowledgeListView() }` 直接入栈——**不新增 `AppRouter.Route` 路由 case**，
避免与同批并行代理（T46 设置路由）改 `AppRouter.swift` / `RouteView.swift` 冲突；深链入口本卡不提供。

`--knowledge-selftest` 时功能 Tab 的 `.task` 自动 `fullScreenCover` 展示 `NavigationStack { KnowledgeListView }`，
页面内自动跑全链路自检并随后自动 push 演示知识库详情页（无人工点击，便于 `xcrun simctl` 截图）。

### 1.2 知识库列表（`Features/Knowledge/KnowledgeListView.swift`）

| 区块 | 内容 | 数据来源 |
|---|---|---|
| 行 | 名称（加粗）/ 右侧索引状态徽标（颜色：绿=已索引、橙=索引中、红=失败、灰=待索引）/ 切块数（已索引比例）/ 关联干员数 / 更新时间 | `KnowledgeBaseBridge.list`（`knowledge_bases` + `knowledge_base_chunks` 计数） |
| 新建 | 右上 `+` → `NewKnowledgeView`：分段选择「粘贴文本 / 文件导入」；文本模式 = 名称 + TextEditor；文件模式 = `fileImporter`（TXT/MD/UTF-8 ≤5MB）→ 桥 `importFileFromPath`（内部读 bytes → `importFile(bytes)` 核心管线） | `createText` / `importFile` |
| 删除 | 左滑「删除」→ `confirmationDialog` 二次确认（文案含「含切块与关联，不可恢复」）→ 桥 `delete` | `KnowledgeBaseRepository.delete`（单事务删 book + chunks + assignments + vector_memories） |
| 空态 | 「还没有知识库」+ 新建按钮 | — |
| 刷新 | `.refreshable` + 从详情返回 `onAppear` 静默刷新 | — |

### 1.3 知识库详情（`Features/Knowledge/KnowledgeDetailView.swift`）

自上而下 6 张卡片（`cardSurface` 非玻璃卡片，遵循玻璃 ≤5 原则）：

| 卡片 | 内容 | 动作 |
|---|---|---|
| 头部 | 名称 / 状态徽标 / 切块数 + 已索引数 + 关联干员数 / 正文摘要（前 160 字）/ 格式 + 正文字数 + 更新时间 | 导航栏「…」菜单：重命名（alert 文本框）/ 刷新 |
| 索引与重建 | 当前状态 + 已索引进度；planIndex 提示（本地 = 「预计 N 块，离线免费」；远程 = 「预计 N 次 API 请求，会产生费用，需确认」） | 「开始索引 / 重建索引」（全量 indexBook，进度回调 `done/total` → ProgressView + 文案）；「重试失败块（N）」（仅失败块 >0 显示，走 retryFailedChunks）；「清除索引」（invalidate：清向量 + 切块索引标记 → pending，保留正文与切块） |
| 召回测试 | 干员菜单（operators 真实名册）+ 查询输入框 + 「召回测试」按钮 | 走 `KnowledgeBaseService.recallForPrivateChat`（与聊天注入同链路），结果列表展示命中书、分数（3 位小数）、片段前 4 行；未命中显示原因文案（no_assignment / index_not_ready / no_match / embedding_unavailable） |
| 关联干员 | 全部干员 + 勾选状态（勾选 = 该干员私聊/群聊召回这本知识库）；说明「语义与 Android 角色知识库绑定一致」 | 行点击切换关联（`replaceAssignmentsForKnowledgeBase`，回调返回更新后 id 列表） |
| 切块预览 | 前 50 块（`#序号` / 章节标题 / 正文 5 行 / 已索引标记 / 索引错误）；超过 50 块时标题显示 `前 50/N 块` | — |
| 危险操作 | 「删除这本知识库」+ 二次确认 | delete → dismiss |

## 2. 桥接契约（`KnowledgeBaseBridge.kt`，新增 iosMain 文件）

> 纪律：普通 class DTO + 简单类型 + List，不导出 Flow/sealed；回调在 Main 队列；错误经 `onResult` 第二参数（中文文案）。
> 依赖组装：`KnowledgeBaseRepository`（`chatRepo.knowledgeBases`）+ `MemoryVectorService(settings, LocalVectorStoreGateway(dbWrapper))`
> + `KnowledgeBaseIndexService` + `KnowledgeBaseContextBuilder` + `KnowledgeBaseImportService` →
> `shared.domain.knowledge.KnowledgeBaseService`（T24 域门面，本卡不新增域逻辑）。

| 方法 | 回调 | 说明 |
|---|---|---|
| `list(onResult)` | `(List<KnowledgeBaseDto>, String?)` | `getAll()` + 每本 `getChunks` 计数（切块数/已索引/失败） |
| `detail(id, chunkLimit, onResult)` | `(KnowledgeBaseDetailDto?, String?)` | 书 + 前 N 块 + totalChunks + 已关联干员 id |
| `status(id, onResult)` | `(KnowledgeBaseStatusDto?, String?)` | 轮询用：原始状态串 + 中文标签 + 计数 |
| `createText(name, content, onResult)` | `(KnowledgeBaseDto?, String?)` | `saveText` → 同步切块入库；local 模式自动后台索引（同 Android） |
| `importFile(fileName, bytes, onResult)` | 同上 | 核心 bytes 导入（UTF-8 TXT/MD，5MB / 60 万字 / 1800 段上限由 `KnowledgeBaseTextProcessor` 校验） |
| `importFileFromPath(fileName, path, onResult)` | 同上 | Swift DocumentPicker 暂存路径版（Kotlin `ByteArray` 不直接导出 Swift） |
| `rename(id, name, onResult)` | `(KnowledgeBaseDto?, String?)` | `repository.rename`，不重切块、不使索引失效 |
| `delete(id, onResult)` | `(String?)` | `indexService.cancelAndRemove` + `repository.delete` |
| `planIndex(id, onResult)` | `(KnowledgeIndexPlanDto?, String?)` | 切块数 / 是否远程嵌入 / 是否需用户确认 |
| `index(id, onProgress, onResult)` | `(Int,Int)`→进度；`(KnowledgeIndexResultDto?, String?)` | 全量 `indexBook`（重建） |
| `retryFailedChunks(id, onProgress, onResult)` | 同上 | 只处理 `indexedAt<=0` 或 `indexError` 非空的块 |
| `invalidate(id, onResult)` | `(String?)` | 清向量分区 + 切块索引标记，状态 pending |
| `listOperators(onResult)` | `(List<KnowledgeOperatorDto>, String?)` | `operators` 真实名册（召回关联用） |
| `setAssignment(id, operatorId, enabled, onResult)` | `(List<String>?, String?)` | 读当前关联 → 增/删 → `replaceAssignmentsForKnowledgeBase` |
| `recallTest(operatorId, query, bookIds, onResult)` | `(KnowledgeRecallDto?, String?)` | `recallForPrivateChat`；bookIds 空 = 该干员全部关联；返回 hits（book/snippet/score/injected）+ reason/reasonText/assignedBooks |

**入口**：`IosAppGraph` 新增 `private val knowledgeBridge` + `fun knowledge(): KnowledgeBaseBridge`（最小 patch，2 段插入）。
Swift 侧 `AppGraphClient` 仅加 1 个 `knowledgeBridge` accessor（一行），全部包装放独立文件
`Bridge/KnowledgeClient.swift`（T43/T47 同模式），降低与并行代理改 `AppGraphClient.swift` 的冲突面。

## 3. 数据映射（真实表）

| UI | 表 / 字段 | 说明 |
|---|---|---|
| 列表行 | `knowledge_bases.name / indexStatus / updatedAt` + `COUNT(knowledge_base_chunks)` | `getAllKnowledgeBases` 按 updatedAt DESC |
| 状态徽标 | `indexStatus`：`pending` / `pending_confirm` / `processing` / `indexing:x/y` / `partial_indexing` / `ready` / `partial_failed` / `partial_pending_confirm` / `failed` | 中文标签桥内映射（`indexStatusText`） |
| 关联干员 | `operator_knowledge_bases(operatorId, knowledgeBaseId, enabled, sortOrder)` | 召回前置过滤（`forOperatorRecall` 只查 enabled 关联） |
| 召回命中分数 | `vector_memories.similarity`（本地哈希嵌入余弦/L2 归一化） | `KnowledgeBaseContextBuilder.Hit.score` 原样透出 |
| 切块索引 | `knowledge_base_chunks.indexedAt / indexError` | 已索引 = `indexedAt>0 && indexError==""` |

## 4. 验收（可自动判定）

1. **桥/管线**：`:shared:linkDebugFrameworkIosSimulatorArm64` BUILD SUCCESSFUL；Shared.h 导出 `knowledge()` +
   `KnowledgeBaseBridge` + 全部 DTO（grep 校验）。
2. **端到端自检**：`--knowledge-selftest`（iPhone 17 / iOS 27.2 模拟器）→ `Documents/knowledge-selftest.log`
   全 PASS：粘贴文本创建（chunk>0）→ MD 文件导入（chunk>0）→ planIndex（本地模式无需确认）→ index
   （succeeded>0、failed=0、ready）→ status（已索引==切块）→ 关联干员 → 召回（hits>0、0<topScore≤1）
   → 删除（详情失败 + 列表数量回基线）→ 演示库播撒。
3. **DB 核对**：`sqlite3 <container>/Library/Application Support/rhodes.db` 直查
   `knowledge_bases` / `knowledge_base_chunks` / `operator_knowledge_bases` 行数与索引标记。
4. **截图**：`t48-knowledge-list.png`（列表 + 自检卡片）、`t48-knowledge-detail.png`（详情 + 索引卡）、
   `t48-knowledge-recall.png`（召回测试命中 + 分数）、`t48-features-entry.png`（功能 Tab 入口行）。
5. **零回归**：本卡只新增 iosMain 桥 + Swift 文件，`shared/commonMain` 与 Android `app/` 零改动；
   `:shared:testDebugUnitTest` 不受影响（历史结论 524/0）。

## 5. 限制与差异白名单

1. **不提供深链 / AppRouter 路由**：入口仅功能 Tab 行（避免与 T46 路由改动冲突）；后续波次可加
   `rhodes://knowledge/{id}`。
2. **切块管理缺失**：不实现单块编辑 / 启停 / 新增块（Android 详情页有）；桥已具备 repository 能力，
   留后续卡片。
3. **文件格式**：仅 UTF-8 TXT / MD（shared `KnowledgeBaseTextProcessor` 明确拒绝 PDF/DOCX 等二进制，
   文案要求先转文本）；未接 iCloud Drive 批量导入。
4. **远程向量模式**：默认 local（本地哈希嵌入，离线免费）；如用户在设置切到 third_party，
   索引前会弹确认（预计请求次数/费用），未实现取消进行中的索引（`KnowledgeBaseIndexService` 有 cancel
   能力，UI 本波未接）。
5. **召回测试限定私聊同链路**：`recallForPrivateChat`（3 条 / 1200 字 / 4.5s 预算）；群聊召回
   （`recallForGroupChat`）未做独立测试入口（同一 ContextBuilder，UI 无必要）。
6. **知识库与角色详情页的绑定入口**：本卡提供详情页「关联干员」；角色详情页的「知识库」分区
   （Android RoleDetail 有）未改（属 T47/角色页写域外）。
7. **重命名不触发重建**：与 Android `renameKnowledgeBase` 一致（只改名）。

## 6. 文件清单

| 路径 | 类型 |
|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/KnowledgeBaseBridge.kt` | 新增（桥 + 12 DTO） |
| `RhodesLink/shared/src/iosMain/.../iosbridge/IosAppGraph.kt` | 修改（+~14 行：bridge 实例 + `knowledge()`） |
| `RhodesLink/iosApp/RhodesLink/Bridge/KnowledgeClient.swift` | 新增（Swift 包装 + 文件暂存） |
| `RhodesLink/iosApp/RhodesLink/Bridge/AppGraphClient.swift` | 修改（+3 行 accessor） |
| `RhodesLink/iosApp/RhodesLink/Features/Knowledge/KnowledgeListView.swift` | 新增 |
| `RhodesLink/iosApp/RhodesLink/Features/Knowledge/KnowledgeDetailView.swift` | 新增 |
| `RhodesLink/iosApp/RhodesLink/Features/Knowledge/NewKnowledgeView.swift` | 新增 |
| `RhodesLink/iosApp/RhodesLink/Features/Knowledge/KnowledgeAutomation.swift` | 新增（`--knowledge-selftest`） |
| `RhodesLink/iosApp/RhodesLink/Features/Features/FeaturesView.swift` | 修改（入口行 + 自检展示 + `navigationRow` helper） |
| `ios-port/handoffs/evidence/t48-*` | 新增（截图 / 构建日志 / 自检日志 / DB 核对） |
