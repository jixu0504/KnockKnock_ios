<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2428-2430
  Decl  : in_classifyGroupError
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
).containsMatchIn(e.message.orEmpty()) -> "AI 服务暂时不可用，请稍后重试"
        e is java.net.SocketTimeoutException || e.message?.contains("timeout", true) == true -> "AI 服务响应超时，请稍后重试"
        e is java.net.UnknownHostException || Regex(
