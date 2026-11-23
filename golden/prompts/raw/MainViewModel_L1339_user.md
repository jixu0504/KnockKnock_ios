<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/MainViewModel.kt:1339-1348
  Decl  : user
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

你上一条发的是：$previousText

【当前信息】
时间：${sharedUtils.beijingPromptTime()}
关系：${op.userRelation.ifBlank { "未知" }}
最近聊天进展：${repository.getShortTermMemory(session.id)?.content ?: "无"}

请补一句。
