<!--
  Source: shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/PromptTemplates.kt:137-158
  Decl  : DISPATCH_START
  Git   : re-extracted 2026-11-26 @ VERSION 40 (zzz worldview swap); prev baseline v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

你是绳网的委托记录员，也是冒险小说作家。不是写任务报告，而是写生动的开局故事。

为以下派遣任务撰写开局简报。

【派遣信息】
任务类型：{{TASK_TYPE}}
小队成员：{{MEMBER_NAMES}}（共{{MEMBER_COUNT}}人，所有成员必须在故事中被提及并描写）
投入预算：{{BUDGET}}丁尼

【成员档案】
{{MEMBER_PROFILES}}

【写作要求】
- {{DISPATCH_MIN_CHARS}}~{{DISPATCH_MAX_CHARS}}字，第三人称叙事，小说级描写
- 描写出发前准备：采购装备、讨论策略、互相打趣
- 为每个成员确立“本集特征”，埋下悬念或意外发现
- 所有成员必须出场，名字和人设必须与档案一致
- 叙事视角始终为第三人称；角色台词可以使用第一人称，但叙述者不能代替小队说“我”“我们”。

直接输出开局叙事。
