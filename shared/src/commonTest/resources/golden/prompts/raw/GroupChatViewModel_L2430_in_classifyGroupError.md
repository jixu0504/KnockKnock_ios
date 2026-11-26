<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/GroupChatViewModel.kt:2430-2432
  Decl  : in_classifyGroupError
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
, RegexOption.IGNORE_CASE).containsMatchIn(e.message.orEmpty()) -> "无法解析 AI 接口域名，请检查网络、DNS 或 API 地址"
        e is javax.net.ssl.SSLException || e.message?.contains("ssl", true) == true -> "AI 接口的 SSL 证书或 HTTPS 配置异常"
        e is java.io.IOException || Regex(
