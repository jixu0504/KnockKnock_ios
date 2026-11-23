//
//  ChatPerformanceSettingsView.swift
//  T46「聊天表现」设置页（对齐 Android oracle `app/ui/chatsettings/ChatSettingsScreen.kt` 通用 Tab）：
//  - 温度（ai_temperature，0.00–2.00，默认 0.70，Android 页「创意程度」整数 ×100 存储）；
//  - 深度思考开关（deepseek_thinking_enabled，默认 false，仅 DeepSeek 类供应商使用）；
//  - 历史回看轮数（history_messages，0–200，默认 15，私聊/群聊共用）；
//  - 回复长度上限：台词单段最多字数 dia_max（默认 300）+ 旁白单段最多字数 nar_max（默认 300）。
//
//  全部经 Shared.SettingsBridge 读写真实 SettingsRepository（NSUserDefaults）；
//  保存即写，下一轮聊天请求重新读取 settings 后生效（无需重启）。
//
//  验证钩子：`--settings-selftest`（见 SettingsAutomation.swift）。
//

import SwiftUI

struct ChatPerformanceSettingsView: View {
    @EnvironmentObject private var environment: AppEnvironment

    @State private var temperature: Double = 0.70
    @State private var deepseekThinking = false
    @State private var historyMessages: Int = 15
    @State private var dialogueMinChars: Int = 10
    @State private var dialogueMaxChars: Int = 300
    @State private var narrationMinChars: Int = 50
    @State private var narrationMaxChars: Int = 300
    @State private var dialogueSegMin: Int = 1
    @State private var dialogueSegMax: Int = 3
    @State private var narrationSegMin: Int = 1
    @State private var narrationSegMax: Int = 3
    @State private var groupMsgMin: Int = 10
    @State private var groupMsgMax: Int = 100
    @State private var groupSpeechMin: Int = 1
    @State private var groupSpeechMax: Int = 2
    @State private var groupNarSegMin: Int = 1
    @State private var groupNarSegMax: Int = 1
    @State private var groupNarMin: Int = 20
    @State private var groupNarMax: Int = 100

    @State private var loaded = false
    @State private var saveMessage = ""
    @State private var saveOK = false
    @State private var scrollOffset: CGFloat = 0

    var body: some View {
        ZStack {
            AmbientBackground(parallax: scrollOffset, showsStandee: false)

            ScrollView {
                VStack(spacing: 14) {
                    statusCard
                    generationCard
                    contextCard
                    replyLengthCard
                    helpCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y
            } action: { _, newValue in
                scrollOffset = newValue
            }
        }
        .navigationTitle("聊天表现")
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
    }

    // MARK: - 数据

    private func reload() {
        let dto = environment.bridge.chatPerformance()
        temperature = dto.temperature
        deepseekThinking = dto.deepseekThinking
        historyMessages = dto.historyMessages
        dialogueMinChars = dto.dialogueMinChars
        dialogueMaxChars = dto.dialogueMaxChars
        narrationMinChars = dto.narrationMinChars
        narrationMaxChars = dto.narrationMaxChars
        dialogueSegMin = dto.dialogueSegMin
        dialogueSegMax = dto.dialogueSegMax
        narrationSegMin = dto.narrationSegMin
        narrationSegMax = dto.narrationSegMax
        groupMsgMin = dto.groupMsgMin
        groupMsgMax = dto.groupMsgMax
        groupSpeechMin = dto.groupSpeechMin
        groupSpeechMax = dto.groupSpeechMax
        groupNarSegMin = dto.groupNarSegMin
        groupNarSegMax = dto.groupNarSegMax
        groupNarMin = dto.groupNarMin
        groupNarMax = dto.groupNarMax
        loaded = true
        SelfTestLog.add("chat-performance load temp=\(dto.temperature) thinking=\(dto.deepseekThinking) history=\(dto.historyMessages) dia=\(dto.dialogueMinChars)-\(dto.dialogueMaxChars) nar=\(dto.narrationMinChars)-\(dto.narrationMaxChars) diaSeg=\(dto.dialogueSegMin)-\(dto.dialogueSegMax) narSeg=\(dto.narrationSegMin)-\(dto.narrationSegMax)")
    }

    private func save() {
        let result = environment.bridge.saveChatPerformance(
            temperature: temperature,
            deepseekThinking: deepseekThinking,
            historyMessages: historyMessages,
            dialogueMaxChars: dialogueMaxChars,
            narrationMaxChars: narrationMaxChars,
            dialogueMinChars: dialogueMinChars,
            narrationMinChars: narrationMinChars,
            dialogueSegMin: dialogueSegMin,
            dialogueSegMax: dialogueSegMax,
            narrationSegMin: narrationSegMin,
            narrationSegMax: narrationSegMax,
            groupMsgMin: groupMsgMin,
            groupMsgMax: groupMsgMax,
            groupSpeechMin: groupSpeechMin,
            groupSpeechMax: groupSpeechMax,
            groupNarSegMin: groupNarSegMin,
            groupNarSegMax: groupNarSegMax,
            groupNarMin: groupNarMin,
            groupNarMax: groupNarMax
        )
        let readBack = environment.bridge.chatPerformance()
        let consistent = abs(readBack.temperature - temperature) < 0.001
            && readBack.deepseekThinking == deepseekThinking
            && readBack.historyMessages == historyMessages
            && readBack.dialogueMaxChars == dialogueMaxChars
            && readBack.narrationMaxChars == narrationMaxChars
            && readBack.dialogueMinChars == dialogueMinChars
            && readBack.narrationMinChars == narrationMinChars
            && readBack.dialogueSegMin == dialogueSegMin
            && readBack.dialogueSegMax == dialogueSegMax
            && readBack.narrationSegMin == narrationSegMin
            && readBack.narrationSegMax == narrationSegMax
            && readBack.groupMsgMin == groupMsgMin
            && readBack.groupMsgMax == groupMsgMax
            && readBack.groupSpeechMin == groupSpeechMin
            && readBack.groupSpeechMax == groupSpeechMax
            && readBack.groupNarSegMin == groupNarSegMin
            && readBack.groupNarSegMax == groupNarSegMax
            && readBack.groupNarMin == groupNarMin
            && readBack.groupNarMax == groupNarMax
        saveOK = result.isEmpty && consistent
        saveMessage = result.isEmpty
            ? (consistent ? "已保存（下一轮聊天生效）" : "保存后读回不一致")
            : result
        SelfTestLog.add("chat-performance save ok=\(saveOK) result='\(result)' readBack temp=\(readBack.temperature) thinking=\(readBack.deepseekThinking) history=\(readBack.historyMessages) diaMax=\(readBack.dialogueMaxChars) narMax=\(readBack.narrationMaxChars)")
    }

    // MARK: - 状态卡

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(systemName: "slider.horizontal.3")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("聊天表现参数")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text(loaded ? "已从 shared 读取" : "读取中…")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text("保存即写入 settings（NSUserDefaults），下一次发送消息时由提示词/供应商配置重新读取，无需重启。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            if !saveMessage.isEmpty {
                Text(saveMessage)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(saveOK ? .green : .red)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 22)
    }

    // MARK: - 生成风格

    private var generationCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "生成风格", systemImage: "sparkles")

            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("温度（创意程度）")
                        .font(.footnote.weight(.semibold))
                    Spacer()
                    Text(String(format: "%.2f", temperature))
                        .font(.caption.monospaced())
                        .foregroundStyle(GlassTokens.brandOrange)
                }
                Slider(value: $temperature, in: 0...2, step: 0.05)
                    .tint(GlassTokens.brandOrange)
                Text("键 ai_temperature（默认 0.70）。建议 0.60–0.90：太低说话死板重复，太高容易跑偏。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            Divider().opacity(0.4)

            Toggle(isOn: $deepseekThinking) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("深度思考")
                        .font(.footnote.weight(.semibold))
                    Text("键 deepseek_thinking_enabled（默认关闭）。DeepSeek 思考模式更慢更贵；推理内容不会展示或保存。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(GlassTokens.brandOrange)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: - 上下文

    private var contextCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionTitle(text: "上下文", systemImage: "clock.arrow.circlepath")

            HStack {
                Text("历史回看轮数")
                    .font(.footnote.weight(.semibold))
                Spacer()
                Text("\(historyMessages)")
                    .font(.caption.monospaced())
                    .foregroundStyle(GlassTokens.brandOrange)
            }
            Slider(
                value: Binding(
                    get: { Double(historyMessages) },
                    set: { historyMessages = Int($0.rounded()) }
                ),
                in: 0...200,
                step: 1
            )
            .tint(GlassTokens.brandOrange)
            Text("键 history_messages（默认 15）。私聊与群聊共用；手动调整只影响本轮请求带入的近期互动，不改变滚动摘要/记忆提取。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: - 回复长度上限

    private var replyLengthCard: some View {
        VStack(spacing: 14) {
            dialogueBudgetCard
            narrationBudgetCard
        }
    }

    /// 台词输出预算（Android ChatSettingsScreen「台词段数/台词字数」）
    private var dialogueBudgetCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "台词输出", systemImage: "bubble.left.and.bubble.right")

            intRangeRow(
                title: "台词段数范围",
                minValue: $dialogueSegMin,
                maxValue: $dialogueSegMax,
                range: 0...10,
                minKey: "dia_seg_min", maxKey: "dia_seg_max",
                hint: "每轮输出多少段台词（默认 1–3 段），线上/线下/导演模式都生效。"
            )

            Divider().opacity(0.4)

            intRangeRow(
                title: "台词单段字数",
                minValue: $dialogueMinChars,
                maxValue: $dialogueMaxChars,
                range: 1...1000,
                minKey: "dia_min", maxKey: "dia_max",
                hint: "每段台词的字符上下限（默认 10–300 字），太低会一问一答，太高回复变啰嗦。",
                step: 5
            )
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    /// 旁白输出预算（Android ChatSettingsScreen「旁白段数/旁白字数」）
    private var narrationBudgetCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "旁白输出", systemImage: "text.bubble")

            intRangeRow(
                title: "旁白段数范围",
                minValue: $narrationSegMin,
                maxValue: $narrationSegMax,
                range: 1...10,
                minKey: "nar_seg_min", maxKey: "nar_seg_max",
                hint: "每轮输出多少段【旁白】（默认 1–3 段）。仅线下/导演模式生效，线上模式禁止旁白。"
            )

            Divider().opacity(0.4)

            intRangeRow(
                title: "旁白单段字数",
                minValue: $narrationMinChars,
                maxValue: $narrationMaxChars,
                range: 0...1000,
                minKey: "nar_min", maxKey: "nar_max",
                hint: "每段旁白的字符上下限（默认 50–300 字）。仅线下/导演模式生效。",
                step: 5
            )
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    /// 群聊输出预算（Android 群聊 Tab：每位成员发言段数/字数 + 旁白段数/字数）
    private var groupBudgetCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "群聊输出", systemImage: "person.3")
            Text("每位成员的发言段数与每条消息字数，以及群聊旁白的段数与字数（旁白仅线下/导演模式）。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            intRangeRow(
                title: "每位成员发言段数",
                minValue: $groupSpeechMin,
                maxValue: $groupSpeechMax,
                range: 1...20,
                minKey: "group_speech_min", maxKey: "group_speech_max",
                hint: "每位当前成员本轮必须发言的段数（默认 1–2 段）。"
            )

            Divider().opacity(0.4)

            intRangeRow(
                title: "每条消息字数",
                minValue: $groupMsgMin,
                maxValue: $groupMsgMax,
                range: 5...200,
                minKey: "group_msg_min", maxKey: "group_msg_max",
                hint: "每位成员每条消息的字符范围（默认 10–100 字）。",
                step: 5
            )

            Divider().opacity(0.4)

            intRangeRow(
                title: "群聊旁白段数",
                minValue: $groupNarSegMin,
                maxValue: $groupNarSegMax,
                range: 1...20,
                minKey: "group_nar_seg_min", maxKey: "group_nar_seg_max",
                hint: "每轮群聊旁白段数（默认 1–1 段），仅线下/导演模式。"
            )

            Divider().opacity(0.4)

            intRangeRow(
                title: "群聊旁白字数",
                minValue: $groupNarMin,
                maxValue: $groupNarMax,
                range: 0...300,
                minKey: "group_nar_min", maxKey: "group_nar_max",
                hint: "每段群聊旁白的字符范围（默认 20–100 字）。",
                step: 5
            )
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    /// 通用「最小值–最大值」滑杆行（min 不能超过 max，保存时由 shared 再收敛）
    private func intRangeRow(
        title: String,
        minValue: Binding<Int>,
        maxValue: Binding<Int>,
        range: ClosedRange<Int>,
        minKey: String,
        maxKey: String,
        hint: String,
        step: Int = 1
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.footnote.weight(.semibold))
                Spacer()
                Text("\(minValue.wrappedValue) – \(maxValue.wrappedValue)")
                    .font(.caption.monospaced())
                    .foregroundStyle(GlassTokens.brandOrange)
            }
            HStack(spacing: 10) {
                Text("最少")
                    .font(.caption2).foregroundStyle(.secondary)
                Slider(value: Binding(
                    get: { Double(minValue.wrappedValue) },
                    set: { newValue in
                        let v = Int(newValue.rounded())
                        minValue.wrappedValue = min(v, maxValue.wrappedValue)
                    }
                ), in: Double(range.lowerBound)...Double(range.upperBound), step: Double(step))
                .tint(GlassTokens.brandOrange)
            }
            HStack(spacing: 10) {
                Text("最多")
                    .font(.caption2).foregroundStyle(.secondary)
                Slider(value: Binding(
                    get: { Double(maxValue.wrappedValue) },
                    set: { newValue in
                        let v = Int(newValue.rounded())
                        maxValue.wrappedValue = max(v, minValue.wrappedValue)
                    }
                ), in: Double(range.lowerBound)...Double(range.upperBound), step: Double(step))
                .tint(GlassTokens.brandOrange)
            }
            Text("键 \(minKey) / \(maxKey)。\(hint)")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - 保存

    private var helpCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: save) {
                Label("保存聊天表现", systemImage: "checkmark.circle.fill")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(GlassTokens.brandOrange.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .disabled(!loaded)

            Text("数值范围与 Android 原版一致（段数与字数分别限定）；shared SettingsRepository 内部会再做一次范围收敛（min 不超过 max）。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }
}
