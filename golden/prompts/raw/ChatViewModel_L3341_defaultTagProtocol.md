<!--
  Source: app/src/main/java/com/rhodes/privatechat/viewmodel/ChatViewModel.kt:3341-3352
  Decl  : defaultTagProtocol
  Git   : v1.14.27-android-baseline @ 3642d2c
  Note  : dedent approximates Kotlin trimIndent(); ${...} placeholders kept verbatim.
-->

|【回复格式】
|- 只能输出规定的中文标签和正文，不要输出 JSON、Markdown、代码块、解释或额外前缀。
|- 禁止输出任何 HTML/XML 标签，包括 <br>、<br/>、</br>、<p>、</p>、<div>、</div>；段落之间只使用普通换行。
|- 必须先依次输出【状态】【心情】【位置】【本轮简述】，每个标签必须恰好一次，不能省略、合并或调换顺序。
|- 【状态】写角色正在进行的动作、姿势或互动状态，必须不超过10字。
|- 【心情】写角色当前最主要的情绪，必须不超过5字。
|- 【位置】写角色当前所在地点、可辨认位置或线上状态，必须不超过10字。
|- 【本轮简述】写当前主线、已经发生的进展和未结束事项，必须不超过160字。
|- 即使本轮没有明显变化，也必须填写这四项：状态填写“保持原状”或当前持续动作；心情填写最接近的情绪；位置沿用最近已确认位置，没有依据时填写“位置未明确”；本轮简述填写当前事实，不得省略。
|- 【旁白】写第三人称的可见动作、环境或即时场景变化；【台词】写角色实际说出口或发送给用户的话。至少一条【台词】。
|- ${if (mode == "online") 
