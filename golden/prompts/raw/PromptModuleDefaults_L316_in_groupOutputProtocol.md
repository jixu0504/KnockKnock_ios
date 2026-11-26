<!--
  Source: shared/src/commonMain/kotlin/com/rhodes/privatechat/shared/prompt/PromptModuleDefaults.kt:316-333
  Decl  : in_groupOutputProtocol
  Git   : re-extracted 2026-11-26 @ VERSION 40 (zzz worldview swap); prev baseline v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->
}

        【正确格式示例】
        ${if (mode == "online") "" else "【旁白】\n场景描述\n\n"}【发言人: anby】
        【心情】担忧
        台词内容

        【发言人: nicole】
        【心情】不耐烦
        台词内容

        【不要使用的格式】
        - anby：台词
        - 安比：台词
        - 【成员1anby】台词
        - 【发言人：安比】台词
        - 历史中的“成员名：台词”仅用于理解内容，不是本轮输出格式示例；即使历史缺少群聊回合状态或 stable ID 标签，本轮仍必须按本协议输出。
