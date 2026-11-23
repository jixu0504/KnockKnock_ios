# 规格卡：记忆管理页（T47，Phase 5 P1）

> 代理：impl-memory-ui（task-47）｜实现 handoff：`ios-port/handoffs/impl-memory-ui.md`
> 基线 oracle：Android `v1.14.27-android-baseline` 的 `ui/memory/GlobalMemoryScreen.kt`（统一记忆列表：筛选/状态/来源/关键词/删除二次确认）与 `ui/settings/MemorySettingsScreen.kt`（开关语义）；记忆存储/隐私语义见 `ios-port/specs/memory-v2-spec.md`（25 条契约）。
> git：不提交（lead 统一提交）。

## 1. 模块信息

| 项 | 内容 |
|---|---|
| 模块名 | 记忆管理页（列表 / 筛选 / 详情 / 编辑 / 删除 / 隐私 / 锚点） |
| 所属阶段 | Phase 5 P1（T47） |
| 范围映射 | A3 记忆域的管理 UI 面；消费 T24（`shared/domain/memory`）的存储与隐私语义、T29 `MemoryBridge` 只读面 |
| 写域 | `RhodesLink/shared/src/iosMain/.../iosbridge/MemoryBridge.kt`（仅新增方法 + 构造注入 settingsRepo）、`iosApp/Features/Memory/**`、`iosApp/Bridge/MemoryManagementClient.swift`（新增）、`AppGraphClient.swift`（1 个 `memoryBridge` 访问器）、`RhodesLinkApp.swift`（1 行自动化钩子）、`IosAppGraph.kt`（1 行构造参数）、shared commonMain 最小必要扩展（见 §6）、`ios-port/{specs,handoffs}` |
| 依赖 | `MemoryV2Repository`（memory_items 读写/删除传播）、`MemoryRepository`（旧版 memories 只读行）、`AnchorRepository`（memory_anchors 六类锚点）、`SettingsRepository`（隐私/生成开关键）、`MemoryPrivacyPolicy`（来源→可见性 + 18 敏感词） |

## 2. 数据模型（统一列表视图）

管理页把两套真实表统一成一个列表（排序/筛选/分页在桥内完成）：

| 列 | v2 行（`memory_items`） | legacy 行（旧版 `memories`） |
|---|---|---|
| `sourceTable` | `v2` | `legacy` |
| `level` | `memoryLevel` L1/L2/L3 | `type` 映射：SHORT_TERM→L1、DAILY→L2、LONG_TERM→L3 |
| `kind` | level 别名：L1→SHORT_TERM、L2→DAILY、L3→LONG_TERM | `type` 原值 |
| `memoryType` | 11 类（preference_expression…） | `legacy_<type>` |
| `sourceKind` | 六来源原值 | 固定 `PRIVATE_CHAT`（旧版只由私聊摘要写入） |
| `importance` | 0..100 | 0（旧表无该列，UI 显示「未标注」） |
| 写能力 | 编辑内容/重要度、删除（含派生失效 + 向量清理） | 只读；仅 LONG_TERM 支持删除（每角色一条语义） |

可写性由桥返回 `canEditContent/canEditImportance/canDelete`，UI 只按标志启用控件。

## 3. 桥接契约（`MemoryBridge` 新增面）

> 窄门面纪律：DTO 只用简单类型、普通 class；回调经 `mainScope`（Main），读写在 `workScope`。
> 旧 `list(onResult:)` 原样保留（AppEnvironment / `--selftest` 兼容）。

| 方法 | 入参 | 回调 | 语义 |
|---|---|---|---|
| `listPage(offset, limit, query)` | offset≥0、limit 1..200（越界裁剪） | `(MemoryPageDto?, String?)` | 两表合并 → 筛选 → 排序 → 分页；`total` 为筛选后总数，`hasMore` 供加载更多 |
| `detail(id, sourceTable)` | `sourceTable` = `v2`/`legacy`（其它值按 v2） | `(MemoryDetailDto?, String?)` | 记录 + 来源锚点（恒非空）+ 六类锚点 + 隐私说明 + 可写标志 |
| `create(ownerType, ownerId, content, level, importance, explicitPrivate, memoryType)` | content ≤500 字；level 空→L2 | `(MemoryMutationResultDto?, String?)` | 写 `memory_items`（sourceKind=MANUAL_MEMORY，sourceRefId=`manual:<now>`）；隐私由 `MemoryPrivacyPolicy.forSource` 判定（命中敏感词或 `explicitPrivate` → private） |
| `edit(id, sourceTable, content, importance)` | importance 0..100 | `(MemoryMutationResultDto?, String?)` | 内容变更走仓库 `updateMemoryItemContent`（派生认知失效 + 旧向量删除）；重要度走新增 `updateMemoryItemImportance`；只支持 v2 |
| `delete(id, sourceTable)` | — | `(MemoryMutationResultDto?, String?)` | v2 → `MemoryV2Repository.deleteMemoryItem`（删除传播：派生归档 + 向量清理）；legacy LONG_TERM → `deleteLongTermByOperator`（每角色一条） |
| `deleteBatch(ids, sourceTables)` | 两数组同序 | `(MemoryMutationResultDto?, String?)` | 逐条按上表语义删除，聚合成功/失败 |
| `privacySettings()` | — | 同步返回 `MemoryPrivacyDto` | 键：`memory_v2_enabled`、`distinguish_private_memory`、`source_aware_memory_enabled`、`global_public_memory_enabled`；附 18 敏感词与 6 行策略说明 |
| `setPrivacyFlag(key, value)` | key ∈ 上述 4 键 | 同步返回 Bool（false=未知键） | 写 NSUserDefaults |
| `isSensitive(text)` | — | 同步 Bool | `MemoryPrivacyPolicy.isSensitive`（18 条） |

### 3.1 筛选矩阵（`MemoryQueryDto`）

| 字段 | 值域 | 语义 |
|---|---|---|
| `level` | `""`/L1/L2/L3（也接受 SHORT_TERM/DAILY/LONG_TERM 别名） | 层级筛选 |
| `kind` | `""`/SHORT_TERM/DAILY/LONG_TERM | `level` 的别名（写反也匹配） |
| `sourceKind` | `""`/PRIVATE_CHAT/GROUP_CHAT/MOMENT/MOMENT_COMMENT/DIARY/MANUAL_MEMORY | 来源筛选（大小写不敏感） |
| `keyword` | 任意 | 内容 / `sourceActor` / 归属名 子串（忽略大小写） |
| `fromMillis`/`toMillis` | 0 = 不限；闭区间 | `createdAt` 时间范围 |
| `status` | `""`/`all`/`active`/`archived`/`expired` | 状态筛选 |
| `ownerType`/`ownerId` | `""` = 不限 | 归属过滤（预留，UI 暂只用层级/来源/状态/时间） |
| `sort` | `newest`（默认）/`oldest`/`importance` | 排序 |

## 4. UI 契约

### 4.1 列表（`MemoryListView`，Features Tab → 功能 → 记忆管理）

- 头部玻璃卡：`记忆管理 · MemoryBridge`、真实 SQLite 条数（筛选时显示 `筛选后 x/total`）、三层说明。
- 层级分段 `Picker(.segmented)`：全部 / 短期（L1）/ 日常（L2）/ 长期（L3）。
- 搜索框：内容 / 说话人，300ms 防抖；右侧清空按钮。
- 筛选胶囊 Menu：来源（7 项）、状态（正在使用/已归档/已过期/全部）、时间（全部/近 7 天/近 30 天）、排序（最新/最早/重要度）。
- 行：层级徽标（L1 蓝 / L2 橙 / L3 品牌橙）+ 类型标签 + 旧版标记 + 时间 + 内容摘要（3 行）+ `来源 · 归属` + `重要度 x` + 隐私标签。
- 交互：点击进详情（`NavigationLink`）；左滑「删除」+ 长按 contextMenu 删除 → **二次确认弹窗**（原版文案：删除后派生认知停止使用、不可撤销）；下拉刷新；触底自动加载更多（30/页）。
- 空态：无记忆（说明 V2 管线来源与开关）vs 筛选无结果（提示清空筛选）；错误态：读取失败 + 重试按钮；操作错误横幅（可关闭）。

### 4.2 详情（`MemoryDetailView`）

- 编辑卡：层级/类型/ID、归属/来源/记录时间、`TextEditor` 内容编辑（≤500 字，实时字数）、重要度 Slider 0..100、保存按钮（保存中禁用）、保存结果消息；旧版行只读并注明。
- 锚点卡：来源锚点列表（归属/来源/层级/类型/记录时间/更新时间/过期时间/会话/来源引用）+ 旧版六类锚点（`memory_anchors`，含 knownFrom/强度/私密）。
- 隐私说明卡：`MemoryPrivacyPolicy` 六行来源策略 + 18 敏感词 + 本条是否命中敏感词 + 「区分私密记忆」开关（键 `distinguish_private_memory`，即时读回）。
- 危险操作卡：「删除这条记忆」按钮 → 二次确认 → 成功 `dismiss` 返回列表；列表经 `NotificationCenter .memoryStoreDidChange` 刷新。
- 深链入口 `rhodes://memory/{id}`（`Route.memoryDetail` / `RouteView` 不变）：无表信息时先 v2 后 legacy 解析。

## 5. 验证

- `--memory-mgmt-selftest`（`Features/Memory/MemoryAutomation.swift`，真实 SQLite）：
  1. 取第一位 operator 为归属；2. 新建手动记忆（L2/42/agreement_commitment）与敏感词记忆（L1，「秘密」→ 断言 private）；
  3. 列表 `limit=1` 分页 + 包含新建行；4. 筛选矩阵 level=L2 命中 / L1 排除 / keyword / source=MANUAL_MEMORY / 近 1h 命中 / 昨天前排除 / sort=importance；
  5. 详情（记录字段、来源锚点非空、可写标志、隐私行）；6. 编辑内容+重要度 88 → 详情复核（含 updatedAt）；
  7. 隐私键读回 + 非法键拒绝 + 敏感词 18 条 + isSensitive 正反例；8. 单条删除 → 列表消失 + 详情 nil；
  9. 新建 2 条 → deleteBatch → 全消失 + 不存在 id 删除返回失败不崩溃；10. 清理敏感记忆；日志落盘 `Documents/ios-shell-selftest.log`。
- 证据（`ios-port/handoffs/evidence/t47-*`）：framework link 日志、xcodebuild 日志、selftest 日志、sqlite3 DB 核对、列表/筛选/详情/编辑/滑动删除确认/隐私区/空态截图。

## 6. 与 shared 层的最小必要扩展（跨写域说明）

写域纪律为「`MemoryBridge.kt` 仅扩展」，但两条既有 API 缺口必须补最小语句，否则需求无法落地：

1. `MemoryItems.sq`：新增 `updateMemoryItemImportance`（`UPDATE memory_items SET importance=? , updatedAt=? WHERE id=?`）——shared 原本只有内容更新，无重要度更新语句。
2. `MemoryV2Repository.updateMemoryItemImportance` + `ChatRepository.updateMemoryItemImportance`（各 1 行委托）——桥侧调用入口。
3. `MemoryPrivacyPolicy.sensitiveWords`：把原本内联在 `isSensitive` 里的 18 条词提为 `val`（行为等价），供 UI 展示；`isSensitive` 改为复用它。

以上均不改变既有行为（important 更新不触碰向量索引；敏感词判定逐字节等价），reviewer 可单独复核。

## 7. 已知限制

1. **重要度编辑不重建向量**：`vector_memories.importance` 是建索引时的快照，编辑重要度只改 `memory_items`（召回评分中重要度项会在重新索引后更新）。手动记忆在 Android oracle 中会补向量，iOS 桥无 `MemoryVectorService` 注入点，本卡不写向量。
2. **旧版 `memories` 表只读**：无单条内容/重要度更新 API（只有整表/按角色删除），UI 明确标注只读；LONG_TERM 删除为「每角色一条长期印象」语义。
3. **锚点**：详情主锚点是记录字段派生的来源锚点；旧版六类锚点按 ownerId(sessionId 过滤) 展示，memory_items 与 memory_anchors 之间无直接外键，不做跨表推断。
4. 实时筛选在桥内全表内存过滤（演示规模；Android oracle 亦为 `getAllMemoryItems()` 全量后过滤）。数据量大时应下推 SQL（后续任务）。
5. 本页不提供「新建手动记忆」入口（桥 `create` 已就绪供手动记忆入口/自动化使用；Android 记忆管理页同样无新建入口）。
