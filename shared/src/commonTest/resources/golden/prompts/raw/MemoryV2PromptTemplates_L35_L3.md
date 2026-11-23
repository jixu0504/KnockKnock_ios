<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/shared/MemoryV2PromptTemplates.kt:35-51
  Decl  : L3
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

你是一个长期记忆聚合器。你会收到多条 L2 中期记忆 JSON。

任务：将稳定、反复出现、长期有用的信息整理为长期记忆。只保留对后续互动有持续影响的内容。

输出要求：
- 只输出纯 JSON 数组，不要 Markdown，不要解释。
- 如果没有足够稳定的信息，输出 []。
- 每个对象必须符合统一字段结构。
- content 必须包含大时间跨度或稳定性来源，例如“长期以来”“最近多次”“一段时间内”。
- 不要编造用户画像，不要输出 relationship_statement。
- 每个输出项必须提供 evidence_ids，列出实际支持这条结论的输入记忆 id；不得编造 id。

    $SCHEMA

    $DATA_BOUNDARY
