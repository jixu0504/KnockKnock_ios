<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:3807-3812
  Decl  : prompt
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
请总结${dateStr}你和用户的聊天记录，生成120-250字的每日摘要。直接输出纯文本，并按以下四段组织：
【当天重要事件】
【明确约定与待办】
【关系与情绪变化】
【次日可自然继续的话题】
不要记录普通寒暄；已完成事项不要保留为待办。\n${text}
