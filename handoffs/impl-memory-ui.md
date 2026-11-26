# T47 实现 handoff：记忆管理页（Phase 5 P1）

> 代理：impl-memory-ui（task-47）｜规格：`ios-port/specs/memory-ui-spec.md`（本卡产出）
> 状态：**实现完成；Shared.framework 重链 + xcodebuild 通过；`--selftest --memory-mgmt-selftest` 真机路径（iPhone 17 / iOS 27.2）27/27 PASS；UI 截图与 sqlite3 核对完成**
> 纪律：git **不提交**（lead 统一提交）；task-47 claim→complete；不碰 Settings 页 / ChatEngineBridge；IosAppGraph 仅 1 行最小 patch。

## 1. 交付清单（写域内）

| 路径 | 类型 | 说明（关键 file:line） |
|---|---|---|
| `RhodesLink/shared/src/iosMain/.../iosbridge/MemoryBridge.kt` | **扩展（仅新增方法）** | 构造注入 `settingsRepo`；新增 DTO：`MemoryRecordDto/MemoryQueryDto/MemoryPageDto/MemoryAnchorDto/MemoryDetailDto/MemoryMutationResultDto/MemoryPrivacyDto`；新增方法：`listPage` :168、`detail` :191、`create` :221、`edit` :293、`delete` :355、`deleteBatch` :370、`privacySettings` :405、`setPrivacyFlag` :419、`isSensitive` :428；T29 `list()` :146 原样保留 |
| `.../iosbridge/IosAppGraph.kt` | 1 行 | `MemoryBridge(chatRepo, settingsRepo, workScope, mainScope)` :61（最小 patch，与 T48 共享文件，未改其他行） |
| `RhodesLink/iosApp/Features/Memory/MemoryListView.swift` | 重写 | `MemoryListView`（List + 层级分段/搜索/4 个筛选菜单/行徽标/滑动删除+二次确认/分页/空态/错误态）:25；`MemoryDetailView`（TextEditor 编辑+保存、重要度 Slider、锚点卡、隐私说明卡、删除二次确认）:498 |
| `RhodesLink/iosApp/Bridge/MemoryManagementClient.swift` | 新增 | Swift 侧模型 + `AppGraphClient` 扩展：`loadMemoryPage` :285、`loadMemoryDetail` :300、`createMemory` :316、`updateMemory` :348、`deleteMemory` :363、`deleteMemories` :378、`memoryPrivacySettings` :394、`setMemoryPrivacyFlag` :400、`isSensitiveMemoryText` :405 |
| `RhodesLink/iosApp/Bridge/AppGraphClient.swift` | 追加 3 行 | `var memoryBridge: MemoryBridge { graph.memory() }` :166（其余为 T46 修改，未触碰） |
| `RhodesLink/iosApp/Features/Memory/MemoryAutomation.swift` | 新增 | `--memory-mgmt-selftest` 自动化（27 断言，真实 SQLite）:11/:18 |
| `RhodesLink/iosApp/RhodesLinkApp.swift` | 追加 2 行 | `.task` 链中 `await MemoryAutomation.runIfNeeded(environment:)` :40（与 T45/T46 钩子并行追加，未改他人行） |
| `shared/src/commonMain/sqldelight/.../MemoryItems.sq` | 最小扩展 | `updateMemoryItemImportance`（shared 原无重要度更新语句） |
| `shared/src/commonMain/.../data/MemoryV2Repository.kt` | 最小扩展 | `updateMemoryItemImportance`（1 函数，委托 SQL） |
| `shared/src/commonMain/.../data/ChatRepository.kt` | 最小扩展 | `updateMemoryItemImportance` 1 行委托 |
| `shared/src/commonMain/.../domain/memory/MemoryPrivacyPolicy.kt` | 最小扩展 | 18 条敏感词提为 `val sensitiveWords`（`isSensitive` 行为逐字节等价），供 UI 展示 |
| `ios-port/specs/memory-ui-spec.md` | 新增 | 桥接契约 / 筛选矩阵 / UI 契约 / 验证 / 限制 |
| `ios-port/handoffs/evidence/t47-*` | 新增 | 构建日志 2 份、selftest 日志、DB 核对、14 张截图 |

**未改**：`ChatEngineBridge.kt`（T45）、Settings 页与 `SettingsBridge.kt`（T46）、`KnowledgeBaseBridge`/Knowledge 页（T48）、commonMain 业务逻辑（仅上述 3 处最小声明式扩展）。

## 2. 功能对照（Android oracle ↔ iOS）

| Android `GlobalMemoryScreen.kt`（oracle） | iOS 实现 | 语义 |
|---|---|---|
| `getAllMemoryItems()` 全量 + 客户端过滤 | `MemoryBridge.listPage`（桥内合并 v2 + legacy 两表后过滤/排序/分页） | ✓ 同「全量后过滤」策略；额外把旧版 `memories` 表只读纳入 |
| 关键词（内容/说话人）+ 等级 + 状态 + 来源筛选 | 搜索框（300ms 防抖）+ 层级分段 + 来源/状态/时间/排序菜单 | ✓ 覆盖全部筛选维度（时间与排序为增强） |
| 「删除」按钮 + AlertDialog 二次确认（删除派生认知停止使用文案） | 左滑 + contextMenu 删除 → `confirmationDialog`（文案：删除传播/不可撤销） | ✓ 二次确认 |
| 无详情页（列表即全部） | 新增 `MemoryDetailView`：编辑内容/重要度、锚点、隐私说明、删除 | iOS 增强（task-47 交付） |
| 批量清除当前筛选 | 桥 `deleteBatch`（selftest 覆盖）；UI 暂未挂批量清除按钮（P2 可选） | ⚠️ 桥已就绪，UI 未放入口 |
| 「重建索引」 | 未实现（Android 有；需 `MemoryVectorService` 注入，见限制） | 未纳入本卡 |
| 隐私：`MemorySettingsScreen` 的注入/生开关 | 详情页隐私说明区：策略 6 行 + 18 敏感词 + `distinguish_private_memory` 开关（读回） | ✓ 只读/单键可写，不复制 Settings 页 |

## 3. 桥接契约摘要（详见 spec §3）

- **列表**：`listPage(offset, limit, query)` → `MemoryPageDto(items,total,offset,limit,hasMore)`；`query` 支持 `level`（L1/L2/L3，接受 SHORT_TERM/DAILY/LONG_TERM 别名）、`kind`（level 别名）、`sourceKind`、`keyword`（内容/说话人/归属名）、`fromMillis/toMillis`（createdAt 闭区间）、`status`、`ownerType/ownerId`、`sort`（newest/oldest/importance）；limit 裁剪 1..200。
- **详情**：`detail(id, sourceTable)` → 记录 + 来源锚点（归属/来源/层级/类型/时间/会话/来源引用，恒非空）+ 旧版六类锚点（`memory_anchors`，按 ownerId/sessionId 过滤）+ 隐私策略行 + `canEditContent/canEditImportance/canDelete`。
- **编辑**：`edit(id, sourceTable, content, importance)`；内容变更复用仓库 `updateMemoryItemContent`（派生认知失效 + 旧向量删除），重要度走新增 `updateMemoryItemImportance`；旧版行返回明确错误（无逐条更新 API）。
- **删除**：v2 → `MemoryV2Repository.deleteMemoryItem`（删除传播：BFS 派生归档 + 向量清理）；legacy LONG_TERM → `deleteLongTermByOperator`（每角色一条长期印象语义）；`deleteBatch` 聚合成功/失败。
- **隐私**：`privacySettings()` 同步读 4 键（`memory_v2_enabled` / `distinguish_private_memory` / `source_aware_memory_enabled` / `global_public_memory_enabled`）+ 18 敏感词 + 6 行策略；`setPrivacyFlag(key,value)` 同步写（未知键返回 false）；`isSensitive(text)`。

## 4. 构建

```bash
cd /Users/jackchen/Documents/deepseek-harness/default-workspace && source ios-port/toolchain/env.sh
cd RhodesLink && ./gradlew --no-daemon :shared:linkDebugFrameworkIosSimulatorArm64      # BUILD SUCCESSFUL in 1m 54s（evidence/t47-framework-link.log）
cd iosApp && TMPDIR="$PWD/tmp" xcodebuild build -project RhodesLink.xcodeproj -scheme RhodesLink \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath "$PWD/DerivedData-t47" CODE_SIGNING_ALLOWED=NO
# ** BUILD SUCCEEDED **（evidence/t47-build.log；最终 Swift 树包含双行筛选布局修复）
```

构建纪律：gradle/xcodebuild 前 `pgrep`；期间与 T45/T46/T48 的构建排队（framework link 与其它任务串行；xcodebuild 独立 DerivedData-t47）。**T45 反馈的 MemoryAutomation 可选链编译错误已修复**（`page.items` 非可选，去掉多余 `?.`）；修复后 xcodebuild 全绿，已 send_message 告知 T45。

## 5. 验证证据（`ios-port/handoffs/evidence/`）

### 5.1 Bridge 自动化（iPhone 17 A63CD17A，全新安装；`--selftest --memory-mgmt-selftest`）

`t47-selftest.log`：**passed=27 failed=0，PASS=true**（节选原文）：

```
memory selftest owner=mon3tr(M3)
memory selftest create id=1 ok=true message=已新建手动记忆（privacy=shared）
memory selftest createSensitive id=2 message=已新建手动记忆（privacy=private）
memory selftest PASS sensitive→private :: privacy=private
memory selftest PASS list.pageSize1 :: total=3 count=1
memory selftest PASS list.containsCreated :: found=v2-1
memory selftest PASS filter.level=L2 :: count=1
memory selftest PASS filter.level=L1.excludes :: count=1
memory selftest PASS filter.keyword :: count=2
memory selftest PASS filter.source=MANUAL_MEMORY :: count=2
memory selftest PASS filter.time=last1h :: count=2
memory selftest PASS filter.time=beforeYesterday.excludes :: count=0
memory selftest PASS sort=importance :: count=3
memory selftest PASS detail.record :: level=L2 importance=42
memory selftest PASS detail.sourceAnchors :: types=["归属","来源","层级","类型","等级别名","记录时间","会话","来源引用"]
memory selftest PASS detail.canEdit :: canEdit=true/true canDelete=true
memory selftest PASS detail.privacyLines :: lines=6
memory selftest PASS edit.content :: content=T47自检记忆1795404732已编辑
memory selftest PASS edit.importance :: importance=88
memory selftest PASS edit.updatedAt :: created=1795404732507 updated=1795404732571
memory selftest privacy keys memory_v2=true distinguish=true sourceAware=true globalPublic=true sensitiveWords=18 policyLines=6
memory selftest PASS privacy.toggleReadback :: before=true after=false
memory selftest PASS privacy.unknownKeyRejected :: unknown key accepted
memory selftest PASS privacy.sensitiveWords18 :: count=18
memory selftest PASS privacy.isSensitive :: 命中判定
memory selftest PASS delete.singleGone :: remaining=0
memory selftest PASS delete.detailNil :: detail 仍可读取
memory selftest PASS batchCreate :: ids=[3, 4]
memory selftest PASS delete.batch.ok :: 已删除 2 条记忆
memory selftest PASS delete.batchGone :: remaining=0
memory selftest PASS delete.missingRejected :: 记忆不存在（id=999999999）
=== memory mgmt selftest done PASS=true passed=27 failed=0 ===
```

覆盖：预置（手动记忆 + 敏感词隐私判定）→ 分页 → 筛选矩阵（层级正/反、关键词、来源、近 1h 正/昨天前反、排序）→ 详情（字段/锚点/可写标志/隐私行）→ 编辑（内容+重要度+updatedAt）→ 隐私（键读回/未知键拒绝/18 词/isSensitive）→ 单条删除（列表消失+详情 nil）→ 批量删除（2 条）→ 非法 id 删除容错。

### 5.2 UI 实跑（独立模拟器 `T47-sim` = iPhone 17 Pro / iOS 27.2，取证后已删除该设备）

| 文件 | 内容 |
|---|---|
| `t47-list.png` | 列表：头部卡（`真实 SQLite · 筛选后 6/6 条`）、层级分段、搜索框、双行筛选菜单、L1/L2/L3 徽标 + 类型标签 + 旧版标记 + 重要度 + 隐私标签 |
| `t47-filter-short.png` / `t47-filter-daily.png` / `t47-filter-long.png` | 分段筛选：短期=2 条、日常=2 条、长期=2 条（AX 树核对「共 2 条记忆」，全部=6 条） |
| `t47-search.png` | 搜索「Scout」→ 1/1 条命中（AX 树核对） |
| `t47-empty.png` | 搜索无结果 → 空态「没有符合条件的记忆」 |
| `t47-detail.png` | 详情：内容编辑区（24/500 字）、重要度 Slider、保存按钮、锚点列表（6 个来源锚点） |
| `t47-detail-edit.png` / `t47-detail-saved.png` | 编辑态/保存成功态（模拟器中文键盘把 ASCII 输入转成中文拼音候选，DB 核对证明落库，见 §5.3） |
| `t47-privacy.png` | 隐私说明区：来源策略 6 行 + 18 敏感词 + `区分私密记忆` 开关（distinguish_private_memory）+ 「删除这条记忆」按钮 |
| `t47-swipe.png` | 左滑显示「删除」动作 |
| `t47-delete-confirm.png` | 二次确认弹窗「删除这条记忆？」+ 删除传播/不可撤销文案 |
| `t47-list-after-edit.png` | 保存返回后列表内容已更新（列表刷新链路） |

### 5.3 DB 核对（`t47-db-check.txt`，sqlite3 直查容器内 `rhodes.db`）

```
1|L1|PRIVATE_CHAT|55|shared|博士说周末想去训练场看新到的装备，想让我一起去。T47…（iOSmemory-ui）
2|L2|GROUP_CHAT|78|public|阿米娅答应了博士：今晚九点一起复盘行动方案，不能迟到。
3|L3|PRIVATE_CHAT|92|private|博士最信任的搭档是阿米娅；两人约定无论多晚都留一盏灯。
4|L1|DIARY|40|private|今天有点焦虑，但不想让别人知道这份不安，只能写进日记。
5|L2|MANUAL_MEMORY|66|shared|博士认为 Scout 在侦查任务中的判断非常可靠，值得信任。
memory_items=5  memories=1  operators=147
```

→ UI 编辑保存后 `memory_items.id=1` 的 content 已更新（`更新时间 2026-11-23 11:53:37`），证明 TextEditor→保存→DB 落库链路；删除确认弹窗取消后行数不变（未误删）。

### 5.4 与 T29/T30 的兼容

- 旧 `MemoryBridge.list()` 保留 → `AppEnvironment.memoryCount`、`--selftest` 的 `memories count=…` 不受影响（本轮 selftest 日志中仍输出）。
- 深链 `rhodes://memory/{id}`（`Route.memoryDetail` / `RouteView` 未改）仍可用：无表信息时先 v2 后 legacy 解析；列表内点击改用 `NavigationLink` 显式携带 `sourceTable`，避免两表 id 冲突。

## 6. 写域交叉与协作

1. **shared commonMain 最小扩展**（3 处，见 §1）：`updateMemoryItemImportance`（SQL+2 委托）与 `MemoryPrivacyPolicy.sensitiveWords` 提为 val。均为新增/行为等价重构，reviewer 可单独复核；不改动任何既有调用语义（重要度更新不触碰向量索引）。
2. `IosAppGraph.kt`：仅 `MemoryBridge(...)` 构造参数 1 行（T48 写域文件），其余行未动。
3. `AppGraphClient.swift` / `RhodesLinkApp.swift`：仅追加自己的访问器/钩子（T45/T46 并行追加段互不影响），最终工作树 xcodebuild 通过。
4. **模拟器争用**：T45/T46/T48 并行占用 iPhone 17/17e 并出现 `simctl shutdown/delete` 清理；本卡最终使用自建 `T47-sim`（iPhone 17 Pro）完成截图取证后删除，iPhone 17 保持 booted 归还 T45。期间 axe 工具缓存被清理，已重新下载 AXe v2.1.1（`/tmp/axedl/axe`，未入仓）恢复 UI 自动化。
5. `git status` 中 `AppRouter/RouteView/FeaturesView/Settings*/Knowledge*` 等改动属 T45/T46/T48，非本卡；本卡文件见 §1。

## 7. 已知限制（建议后续 P2）

1. **重要度编辑不重建向量**：`vector_memories.importance` 为建索引时快照；编辑后召回评分的重要度项在重新索引后才更新（本桥无 `MemoryVectorService` 注入点，未写向量）。
2. **旧版 `memories` 表只读**：shared 无逐条更新 API；仅 LONG_TERM 支持删除（`deleteLongTermByOperator` = 每角色一条语义）。旧版短期/日常行删除返回明确错误提示。
3. **锚点语义**：详情主锚点为记录字段派生（恒非空）；旧版六类锚点按 `ownerId`（+sessionId 过滤）展示，`memory_items` 与 `memory_anchors` 无外键，不做跨表推断。
4. **实时筛选为桥内全表内存过滤**（演示规模，与 Android `getAllMemoryItems()` 后过滤一致）；大数据量应下推 SQL。
5. UI 未挂「批量删除当前筛选」「重建索引」入口（桥 `deleteBatch` 已就绪；重建需向量服务）。
6. 模拟器中文键盘会把 `axe type` 的 ASCII 输入转成拼音候选（`t47-detail-edit` 截图可见）；这是输入法行为，不影响保存/落库链路（DB 核对为证）。
