# Handoff · checklist-agent（T3）

> 读者：后续所有阶段代理与 QA。本文件说明清单如何生成、哪些结论可信、哪些需要运行时验证。

## 1. 方法说明（如何定位路由/入口）

1. **路由全集**：`app/src/main/java/com/rhodes/privatechat/navigation/AppScreens.kt`（908 行）中所有 `data object X : Screen` / `data class X(...) : Screen`。共 52 条（L39 GalgameRoute → L903 StartupSettingsRoute）。唯一未覆盖的 Screen 是 `AppNavigation.kt:81` 的 `class MainScreen`（Tab 壳，不计入 52）。
2. **入口定位的关键发现**：全仓库除 `navigation/` 包外，**没有任何 `navigator.push(Route)` 调用点**（仅 `galgame_full/RdgPackage.kt:144-147` 命中，实为内嵌 HTML 字符串中的 JS，非导航）。因此入口图完全由两个文件决定：
   - `AppNavigation.kt`：4 个 Tab 内容 → 路由（聊天 Tab :175,178；联系人 :195,199,203,205；功能 :242-257；设置 :269-282；通知直达 :310,315）。
   - `AppScreens.kt`：路由 → 路由（页面内按钮回调）。每条的 `navigator.push(...)` 即该路由的全部站内入口。
   - 外部入口：`MainActivity.navigationRequest`（通知点击直达会话，`AppNavigation.kt:297-326`）。
3. **页面操作/数据变化**：读对应 Screen 的签名回调 + ViewModel 公开函数表（`grep -nE "^(    )?(fun|suspend fun)" viewmodel/*.kt`）。只写关键点，不抄代码。
4. **错误/空态**：`grep "暂无\|为空\|失败\|重试" ui/` 逐一核对行号；聊天类路由的加载失败态在 `AppScreens.kt:151-154`（ChatOperator）、:271（ChatArchive）、:203（Sleep 无角色 Toast）、:175（VoiceCall 未配置 Toast）。
5. **统计口径**：Dialog=`AlertDialog(` 按调用点计；图标=正则 `Icons\.(AutoMirrored\.)?(Filled|Outlined|Default|TwoTone|Rounded|Sharp)\.\w+` 去重计种类；行数=`wc -l`（含注释空行）。全部只读，未改仓库。

## 2. 覆盖盲区（静态分析无法完全确认，需运行时/后续任务补）

1. **动态内容生成逻辑的可见行为**：动态/日记的「生成」内部状态机（`MainViewModel.kt:2305 generateAllMoments` 等）只有金样 diff 能确认；本清单只列入口与空态。
2. **空态文案不全**：DiaryScreen、ImpressionsScreen、TokenStatsScreen、NewspaperScreen 未发现显式「暂无」文案（可能用滚动区自然空白或子组件文案），已标「待补/未定义」，需 Phase 2 UI 规格或运行时截图确认。
3. **引用回复的输入框 UI 元素**：replyTo 数据流在 `MomentDetailRoute` 参数与 `sendMessage` 中确认存在，但输入框上的「引用条」组件行号未定位，Phase 2 需补。
4. **设置项全集**：§1.1 的 370 个设置访问器未逐项列出（粒度超出本清单），建议由设置规格卡任务单独枚举；本清单只覆盖 14 个设置入口页。
5. **权限分支**：PermissionsScreen 的角色/群两 Tab 具体权限开关（`PermissionsScreen.kt:111,169`）未逐条展开。
6. **通知/WorkManager 行为**：动态定时回复、记忆维护的调度语义（A7）属 shared 层任务，本清单只在 §2.5 挂接了函数入口。
7. **Galgame 内嵌 H5**（`galgame_full/RdgPackage.kt` 大 HTML）未逐功能核对——已整体排除首版，不影响 A 类。

## 3. 与预期数字的差异说明

| 指标 | 预期（任务板/§1.1） | 实测 | 说明 |
|---|---|---|---|
| 路由数 | 52 + 4 Tab | **52 + 4 Tab** ✅ | 精确一致；AppScreens.kt 全量枚举，无遗漏 |
| UI 包 | 31 | 30 一级包 + 3 个 chat 子包 | 口径差异：一级包计 30；若把 chat/util 计入为 31（最可能口径）；chat/component、chat/model 再计入则为 33。统计汇总.md 给出完整清单，避免歧义 |
| Dialog 调用点 | 163（AlertDialog 115） | AlertDialog **115** ✅ + 自定义 Dialog 19 + DropdownMenu 14 = 148 | AlertDialog 精确一致；差 15 处可能来自：ThemedAlertDialog 等包装层定义、`Dialog(` 原生调用或报告含 Dialog 组件定义行。建议 QA 以「115 处 AlertDialog 调用点」为硬验收口径 |
| 图标 | 76 种 / 220 处 | **76 种 / 220 处** ✅ | 完全一致（含 AutoMirrored） |
| ViewModel | 约 18k 行 | 12,901 + 5,111 = 18,012 ✅ | 一致 |

## 4. 给下游的关键结论

- **导航层是最干净的移植点**：52 路由 + 4 Tab 的入口关系 100% 由 `AppNavigation.kt` + `AppScreens.kt` 决定，iOS 导航规格可直接以这两份文件为 oracle。
- **A 类验收缺口**：A7（DailyContentSettingsRoute 及其后台项）验收方式待 Phase 1 中段裁决，裁决前标「未定义」；TokenStats/Diary/Impressions 空态待运行时补拍。
- **Dialog/图标/VM 三大统计与 §1.1 对齐**，可作为 Phase 4-6 QA 的基线数字；凡后续实测偏离本清单，优先怀疑口径而非仓库变更。
- 写域：本任务只写了 `ios-port/checklist/`、`ios-port/handoffs/` 两个文件，未触碰 RhodesLink 仓库任何文件。

---

## 5. 修正记录（reviewer PASS-WITH-FIXES 评审后，2026-11-22）

| # | 级别 | 修正事项 | 最终依据（read/grep 实测行号） |
|---|---|---|---|
| 1 | P1 | 功能对照清单-v1.md 中 MessageList.kt 行号失实 → 全部更正 | 撤回菜单项 `MessageList.kt:420,550`；复制菜单项 :413,543 / Toast「已复制」:417,547；「图片识别失败，点击重试」:501；「未送达，点击重试」:520。已按 grep 实测逐一核对并替换 §1.1、§2.1、§2.2 共 6 处 |
| 2 | P2 | 统计汇总.md「UI 总行数 ≈ 18,317」→ 21,671 | `find ui -name "*.kt" -exec cat {} + \| wc -l` = **21,671**（含 3 个 chat 子包；原值疑漏加 settings 包 3,353 行），已更正并写明命令口径 |
| 3 | P2 | §0.1「31 包」口径解释补全 | 报告口径 31 = 30 一级包 + chat/util 独立计（chat/component、chat/model 并入 chat 主包）；本清单按目录全计为 33（30+3）；统计汇总.md 注同步更新 |

修正后状态：功能对照清单-v1.md 内所有 MessageList.kt 引用与源码实测行号一致（已 grep 复核无残留旧行号）；统计口径两处文档一致。
