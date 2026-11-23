<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:3446-3450
  Decl  : in_reportHistory
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

【输出要求】
第一行必须是【状态】。先依次输出【状态】【心情】【位置】【私聊回合状态】【当前主线】【用户本轮作用】【本轮承接】【本轮新增推进】【主线状态】【未收束事项】，字段只写短词组，不要写成句子。
完成上述字段后，才输出${if (mode == "online") "【台词】" else "【旁白】和【台词】"}。
