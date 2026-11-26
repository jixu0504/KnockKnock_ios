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
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（返回胶囊 + 右侧黄主按钮「保存」）；
//  身份头像走 `KnockAvatar`（黑底 + 蓝圈 + 白首字）；表单 = 面板 + 胶囊输入框；
//  统计 = 面板内灰阶计数格；Toast = 实色胶囊；保存失败改页面内错误面板（不再用系统 alert）。
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

    private var canSave: Bool {
        !isSaving && isDirty && !nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "我的身份", showsBack: true, onBack: { dismiss() }, trailing: {
                    Button {
                        Task { await save() }
                    } label: {
                        Text(isSaving ? "保存中…" : "保存")
                    }
                    .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 34))
                    .opacity(canSave ? 1 : 0.35)
                    .disabled(!canSave)
                    .accessibilityLabel("保存身份")
                })

                Group {
                    if isLoading && profile == nil {
                        KnockLoadingPanel("读取身份信息…")
                            .padding(.horizontal, KnockTokens.screenPadding)
                            .padding(.top, KnockTokens.spacingL)
                    } else if let errorText, profile == nil {
                        KnockErrorPanel(
                            title: "身份读取失败",
                            message: errorText
                        ) {
                            Task { await load() }
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.top, KnockTokens.spacingL)
                    } else {
                        content
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            }

            if let toast {
                VStack {
                    KnockToast(text: toast)
                        .padding(.top, KnockTokens.spacingS)
                        .transition(.opacity)
                    Spacer()
                }
            }
        }
        .knockHiddenNavigationBar()
        .knockScrollEdgeHidden()
        .task { await load() }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                identityCard
                if let saveError {
                    KnockErrorPanel(title: "保存失败", message: saveError, retryTitle: nil)
                }
                editCard
                statsCard
                aboutCard
            }
            .padding(.horizontal, KnockTokens.screenPadding)
            .padding(.top, KnockTokens.spacingS)
            .padding(.bottom, KnockTokens.spacingXL)
        }
        .scrollIndicators(.hidden)
        .refreshable { await load() }
    }

    // MARK: - 身份卡

    private var identityCard: some View {
        KnockPanel {
            VStack(spacing: KnockTokens.spacingM) {
                KnockAvatar(
                    avatarId: profile?.avatarId ?? "",
                    name: displayNickname,
                    size: 84
                )
                KnockText(displayNickname, role: .pageTitle, alignment: .center)
                    .frame(maxWidth: .infinity)
                if !signature.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    KnockText(
                        signature,
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineSpacing: 3,
                        alignment: .center
                    )
                    .frame(maxWidth: .infinity)
                } else {
                    KnockText(
                        "这个身份会出现在聊天中，也会用于代理人对你的称呼。",
                        role: .caption,
                        color: KnockTokens.textTertiary,
                        lineSpacing: 3,
                        alignment: .center
                    )
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var displayNickname: String {
        let trimmed = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "绳匠" : trimmed
    }

    // MARK: - 编辑卡

    private var editCard: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockGroupTitle(title: "编辑身份", systemImage: "person.crop.circle")

                KnockInputField(
                    title: "昵称",
                    placeholder: "必填，聊天中的称呼",
                    text: $nickname
                )
                if nickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    KnockBadge(text: "昵称不能为空", variant: .danger)
                }
                KnockInputField(
                    title: "性别",
                    placeholder: "选填",
                    text: $gender
                )

                VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                    KnockText("个性签名", role: .captionStrong, color: KnockTokens.textSecondary)
                    TextField("写一句介绍自己（选填）", text: $signature, axis: .vertical)
                        .lineLimit(1...4)
                        .textFieldStyle(KnockTextFieldStyle())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                KnockText(
                    "保存后立即写入本机设置，并在下一轮聊天中生效；昵称不能为空。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 3
                )
            }
        }
    }

    // MARK: - 统计卡

    private var statsCard: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(spacing: KnockTokens.spacingS) {
                    Image(systemName: "chart.bar.fill")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textSecondary)
                        .accessibilityHidden(true)
                    KnockText("互动统计", role: .sectionTitle, lineLimit: 1)
                    Spacer(minLength: KnockTokens.spacingS)
                    KnockText("本机真实数据", role: .caption, color: KnockTokens.textTertiary, lineLimit: 1)
                }
                if let profile {
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: KnockTokens.spacingS),
                            GridItem(.flexible(), spacing: KnockTokens.spacingS),
                            GridItem(.flexible(), spacing: KnockTokens.spacingS),
                        ],
                        spacing: KnockTokens.spacingS
                    ) {
                        ForEach(profile.statRows) { row in
                            VStack(spacing: KnockTokens.spacingXXS) {
                                HStack(spacing: 4) {
                                    Image(systemName: row.systemImage)
                                        .font(.system(size: 10, weight: .heavy))
                                        .foregroundStyle(KnockTokens.textSecondary)
                                        .accessibilityHidden(true)
                                    KnockText(row.title, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                                }
                                KnockText("\(row.value)", role: .pageTitle, lineLimit: 1)
                                    .monospacedDigit()
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, KnockTokens.spacingS)
                            .background(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .fill(KnockTokens.rowIconWell)
                            )
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("\(row.title) \(row.value)")
                        }
                    }
                }
                KnockText(
                    "原版没有用户等级/经验值设定，这里展示你在本机的真实互动数据。",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineSpacing: 3
                )
            }
        }
    }

    // MARK: - 说明

    private var aboutCard: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockGroupTitle(title: "关于身份", systemImage: "info.circle")
                KnockText(
                    "昵称、性别与签名会作为你的用户档案进入聊天提示词，影响代理人如何称呼你与理解你的自述。它们只保存在本机，并随完整备份一起导出。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 3
                )
            }
        }
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