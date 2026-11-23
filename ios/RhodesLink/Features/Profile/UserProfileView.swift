//
//  UserProfileView.swift
//  「我的身份」页（T57，入口：设置 → 我的身份）：
//  - 昵称 / 性别 / 个性签名编辑 + 保存（settings user_name / user_gender / user_signature，
//    与 Android ProfileSettingsScreen 同键同语义；昵称 trim 后必填）；
//  - 身份卡：首字头像 + 昵称 + 签名（Android 1.14.27 无用户 avatar 强制项，头像沿用 user_avatar_uri）；
//  - 统计卡：消息 / 记忆 / 会话 / 角色 / 关系（原版无用户 level/exp 字段 → 按任务口径展示真实统计）；
//  - 身份值进入 T45 私聊提示词（USER_NAME / USER_GENDER / USER_BIO），保存后下一轮聊天生效。
//  - 截图钩子（仅启动参数）：`--identity-demo` 由设置页自动推入本页。
//

import SwiftUI

@MainActor
struct UserProfileView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    @State private var profile: UserProfileItem?
    @State private var nickname = ""
    @State private var gender = ""
    @State private var signature = ""
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var saveError: String?
    @State private var toast: String?

    private var isDirty: Bool {
        guard let profile else { return false }
        return nickname != profile.nickname || gender != profile.gender || signature != profile.signature
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            Group {
                if isLoading && profile == nil {
                    ProgressView("读取身份信息…").padding(40)
                } else if let errorText, profile == nil {
                    EmptyStateView(
                        title: "身份读取失败",
                        message: errorText,
                        systemImage: "exclamationmark.triangle",
                        actionTitle: "重试",
                        action: { Task { await load() } }
                    )
                } else {
                    content
                }
            }
        }
        .navigationTitle("我的身份")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(isSaving ? "保存中…" : "保存") {
                    Task { await save() }
                }
                .disabled(isSaving || !isDirty || nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("保存身份")
            }
        }
        .overlay(alignment: .bottom) {
            if let toast {
                Text(toast)
                    .font(.footnote)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
                    .padding(.bottom, 18)
                    .transition(.opacity)
                    .onAppear {
                        Task {
                            try? await Task.sleep(nanoseconds: 2_400_000_000)
                            withAnimation { self.toast = nil }
                        }
                    }
            }
        }
        .alert("保存失败", isPresented: Binding(
            get: { saveError != nil },
            set: { if !$0 { saveError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(saveError ?? "")
        }
        .task { await load() }
    }

    private var content: some View {
        ScrollView {
            VStack(spacing: 14) {
                identityCard
                editCard
                statsCard
                aboutCard
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .refreshable { await load() }
    }

    // MARK: - 身份卡

    private var identityCard: some View {
        VStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(LinearGradient(
                        colors: [GlassTokens.brandOrange, Color(hex: 0xE8A33D)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ))
                    .frame(width: 72, height: 72)
                Text(displayNickname.prefix(1).uppercased())
                    .font(.title.weight(.bold))
                    .foregroundStyle(.white)
            }
            Text(displayNickname)
                .font(.title3.weight(.semibold))
            if !signature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(signature)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                Text("这个身份会出现在聊天中，也会用于干员对你的称呼。")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .cardSurface(cornerRadius: 24)
    }

    private var displayNickname: String {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "博士" : trimmed
    }

    // MARK: - 编辑卡

    private var editCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionTitle(text: "编辑身份", systemImage: "person.crop.circle")
            VStack(spacing: 0) {
                fieldRow(title: "昵称", placeholder: "必填，聊天中的称呼", text: $nickname, required: true)
                Divider().padding(.leading, 14).opacity(0.4)
                fieldRow(title: "性别", placeholder: "选填", text: $gender, required: false)
                Divider().padding(.leading, 14).opacity(0.4)
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 4) {
                        Text("个性签名")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    TextField("写一句介绍自己（选填）", text: $signature, axis: .vertical)
                        .lineLimit(1...4)
                        .font(.body)
                }
                .padding(14)
            }
            .cardSurface(cornerRadius: 18)
            Text("保存后立即写入本机设置，并在下一轮聊天中生效；昵称不能为空。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func fieldRow(title: String, placeholder: String, text: Binding<String>, required: Bool) -> some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(width: 60, alignment: .leading)
            TextField(placeholder, text: text)
                .font(.body)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled(true)
            if required && text.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text("必填")
                    .font(.system(size: 9))
                    .foregroundStyle(.red)
            }
        }
        .padding(14)
    }

    // MARK: - 统计卡

    private var statsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "chart.bar.fill")
                    .foregroundStyle(GlassTokens.brandOrange)
                Text("互动统计")
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Text("本机真实数据")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            if let profile {
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    ForEach(profile.statRows) { row in
                        VStack(spacing: 3) {
                            HStack(spacing: 4) {
                                Image(systemName: row.systemImage)
                                    .font(.system(size: 10))
                                    .foregroundStyle(.secondary)
                                Text(row.title)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Text("\(row.value)")
                                .font(.title3.weight(.semibold).monospacedDigit())
                                .foregroundStyle(.primary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 9)
                        .background(GlassTokens.bubbleSystem, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                }
            }
            Text("原版没有用户等级/经验值设定，这里展示你在本机的真实互动数据。")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    // MARK: - 说明

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(text: "关于身份", systemImage: "info.circle")
            Text("昵称、性别与签名会作为你的用户档案进入聊天提示词，影响干员如何称呼你与理解你的自述。它们只保存在本机，并随完整备份一起导出。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 18)
    }

    // MARK: - 数据

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadUserProfile() {
        case .success(let item):
            profile = item
            nickname = item.nickname
            gender = item.gender
            signature = item.signature
            errorText = nil
            SelfTestLog.add("identity load nickname=\(item.nickname) genderEmpty=\(item.gender.isEmpty) signatureEmpty=\(item.signature.isEmpty) messages=\(item.messageCount) memories=\(item.memoryCount) relations=\(item.relationCount)")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("identity load failed error=\(error.message)")
        }
    }

    private func save() async {
        let cleanName = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else {
            saveError = "昵称不能为空"
            return
        }
        isSaving = true
        defer { isSaving = false }
        saveError = nil
        switch await environment.bridge.saveUserProfile(
            nickname: cleanName,
            gender: gender.trimmingCharacters(in: .whitespacesAndNewlines),
            signature: signature.trimmingCharacters(in: .whitespacesAndNewlines)
        ) {
        case .success(let item):
            profile = item
            nickname = item.nickname
            gender = item.gender
            signature = item.signature
            toast = "身份已保存，下一轮聊天生效"
            SelfTestLog.add("identity saved nickname=\(item.nickname) gender=\(item.gender) signatureEmpty=\(item.signature.isEmpty)")
        case .failure(let error):
            saveError = error.message
            SelfTestLog.add("identity save failed error=\(error.message)")
        }
    }
}
