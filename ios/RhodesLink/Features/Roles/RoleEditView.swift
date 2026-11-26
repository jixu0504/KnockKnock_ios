//
//  RoleEditView.swift
//  角色人设编辑（A2 / T49，新建/编辑共用）：姓名（必填）、标签、简介、人设描述、
//  初始关系（20+ 关系类型，值域取 Android RelationshipType）、头像（敲敲占位头像选择 +
//  相册 PhotosPicker → 方形裁剪 ≤1024 → MediaRepository 入库）、高级区（折叠：提示词槽位覆盖）。
//
//  数据：`OperatorBridge`（shared/iosMain，真实 SQLite：operators + settings 槽位 + 媒体目录）。
//  - 新建：OperatorBridge.create（id = custom_<uuid>，对齐 Android OperatorViewModel.saveOperator）
//  - 编辑：OperatorBridge.update（含 prompt 槽位键 operator_prompt_slot_* 覆盖）
//  - 删除：OperatorBridge.delete（对齐 Android deleteOperators：清私聊会话/记忆/关系/知识库关联）
//  保存成功后回调上层刷新列表/详情；新建可选「保存后直接进入聊天」。
//
//  P2「敲敲」改版（docs/敲敲UI改版计划书-v1.1.md 第 2.1 / 6 章）：
//  - 全部表单控件换 Knock 体系：`KnockFormCard` 面板、`KnockInputField`/`KnockTextFieldStyle`、
//    `KnockTextArea`（行底 + 1.5pt 描边）、自绘开关 `KnockSwitchToggleStyle`、
//    `KnockSegmentedBar`（槽位 1/2/3）、`KnockMenuRow`（关系选择器）、`KnockChip`（24 关系类型，黄选中）；
//  - 头像选择器接 `KnockAvatar` 占位（黑底 + 1.5pt 蓝圈 + 白色 heavy 首字）；原 147 内置头像
//    默认不展示（资源与解析兼容保留在 `RoleAvatar` 解析层），上传头像保留；
//  - 自绘 44pt 顶栏（取消 / 标题 / 黄主按钮保存），隐藏系统导航栏 + iOS 26+ 关闭滚动边缘玻璃；
//  - 保留全部字段、校验、槽位逻辑、保存/删除/入库回调与 SelfTestLog 打点。
//

import PhotosUI
import SwiftUI
import UIKit

/// RoleEditView 的 sheet(item:) 目标：`__new__` = 新建，其它 = operatorId。
struct RoleEditTarget: Identifiable, Hashable {
    static let newRoleId = "__new__"

    /// operatorId 或 `__new__`
    let id: String

    init(operatorId: String?) {
        id = operatorId ?? RoleEditTarget.newRoleId
    }

    /// nil = 新建角色
    var operatorId: String? {
        id == RoleEditTarget.newRoleId ? nil : id
    }
}

struct RoleEditView: View {
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dismiss) private var dismiss

    /// nil = 新建角色；非 nil = operatorId（编辑）。
    let roleId: String?
    var onSaved: ((OperatorEditItem) -> Void)?
    var onDeleted: ((String) -> Void)?

    // 表单状态
    @State private var name = ""
    @State private var tags = ""
    @State private var persona = ""
    @State private var intro = ""
    @State private var gender = ""
    @State private var relationType = ""
    @State private var groupPersona = ""
    @State private var avatarId = ""
    @State private var privateSlots: [String] = ["", "", ""]
    @State private var groupSlots: [String] = ["", "", ""]
    @State private var privateActiveSlot = 1
    @State private var groupActiveSlot = 1
    @State private var relationOptions: [OperatorRelationType] = []

    // UI 状态
    @State private var isLoading = false
    @State private var isSaving = false
    @State private var errorText: String?
    @State private var showAdvanced = false
    /// 敲敲头像选择 Sheet（原内置 147 头像网格已停用展示）
    @State private var showKnockAvatarPicker = false
    @State private var photoItem: PhotosPickerItem?
    @State private var pendingAvatarFileURL: URL?
    @State private var pendingAvatarImage: UIImage?
    @State private var enterChatAfterSave = true
    @State private var showDeleteConfirm = false
    @State private var loadedItem: OperatorEditItem?
    /// 截图辅助：--persona-advanced 展开后滚动到高级区
    @State private var scrollToAdvanced = false

    private var isNew: Bool { roleId == nil }

    var body: some View {
        NavigationStack {
            ZStack {
                AmbientBackground(parallax: 0)

                VStack(spacing: 0) {
                    header

                    ScrollViewReader { proxy in
                        ScrollView {
                            VStack(spacing: KnockTokens.spacingM) {
                                if let errorText {
                                    errorBanner(errorText)
                                }
                                avatarCard
                                basicCard
                                personaCard
                                relationCard
                                advancedCard.id("advanced")
                                if isNew {
                                    enterChatCard
                                } else {
                                    deleteCard
                                }
                            }
                            .padding(.horizontal, KnockTokens.screenPadding)
                            .padding(.top, KnockTokens.spacingS)
                            .padding(.bottom, KnockTokens.spacingXXL)
                        }
                        .scrollIndicators(.hidden)
                        .onChange(of: scrollToAdvanced) { _, target in
                            guard target else { return }
                            withAnimation(.easeInOut(duration: 0.35)) {
                                proxy.scrollTo("advanced", anchor: .top)
                            }
                        }
                    }
                }

                if isLoading {
                    loadingOverlay
                }
            }
            .knockHideSystemChrome()
            .task { await load() }
            .fullScreenCover(isPresented: $showKnockAvatarPicker) { knockAvatarSheet }
            .onChange(of: photoItem) { _, newItem in
                guard let newItem else { return }
                Task { await loadPickedPhoto(newItem) }
            }
            .knockConfirmDialog(
                isPresented: $showDeleteConfirm,
                title: "删除角色",
                message: "确定要删除「\(name)」吗？删除后该角色的私聊会话、记忆、关系与知识库关联将永久丢失（聊天消息文件保留）。",
                confirmTitle: "确认删除",
                cancelTitle: "取消",
                isDestructive: true
            ) {
                Task { await deleteRole() }
            }
        }
    }

    // MARK: - 顶栏（隐藏系统导航栏后的自绘 44pt 顶栏）

    private var header: some View {
        HStack(spacing: KnockTokens.spacingM) {
            Button("取消") { dismiss() }
                .buttonStyle(KnockSecondaryButtonStyle(fullWidth: false, height: 38))
                .accessibilityLabel("取消编辑")

            KnockText(isNew ? "新建角色" : "编辑角色", role: .pageTitle, lineLimit: 1)

            Spacer(minLength: KnockTokens.spacingS)

            if isSaving {
                ProgressView()
                    .tint(KnockTokens.electric)
                    .frame(width: 56, height: 38)
            } else {
                Button("保存") {
                    Task { await save() }
                }
                .buttonStyle(KnockPrimaryButtonStyle(fullWidth: false, height: 38))
                .accessibilityLabel("保存角色")
            }
        }
        .padding(.horizontal, KnockTokens.screenPadding)
        .frame(minHeight: KnockTokens.headerHeight)
        .background(KnockTokens.canvas)
    }

    private var loadingOverlay: some View {
        KnockPanel(padding: KnockTokens.spacingL) {
            HStack(spacing: KnockTokens.spacingM) {
                ProgressView()
                    .tint(KnockTokens.electric)
                KnockText("读取角色资料…", role: .caption, color: KnockTokens.textSecondary)
                Spacer(minLength: 0)
            }
        }
        .padding(.horizontal, KnockTokens.spacingXXL)
    }

    // MARK: - 头像

    private var avatarCard: some View {
        KnockFormCard(title: "头像", systemImage: "person.crop.circle") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                HStack(alignment: .center, spacing: KnockTokens.spacingL) {
                    avatarPreview
                    VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                        KnockText(avatarPreviewTitle, role: .rowTitle, lineLimit: 1)
                        KnockText(avatarHintText, role: .caption, color: KnockTokens.textSecondary, lineSpacing: 2, lineLimit: 3)
                    }
                    Spacer(minLength: 0)
                }

                KnockDashedButton(
                    title: "选择敲敲头像",
                    systemImage: "square.grid.3x3.fill"
                ) {
                    showKnockAvatarPicker = true
                }
                .accessibilityLabel("选择敲敲头像")

                PhotosPicker(selection: $photoItem, matching: .images) {
                    HStack(spacing: KnockTokens.spacingS) {
                        Image(systemName: "photo.on.rectangle")
                            .font(.system(size: 13, weight: .heavy))
                        KnockText("从相册上传", role: .sectionTitle, lineLimit: 1)
                        Spacer(minLength: 0)
                    }
                    .foregroundStyle(KnockTokens.textPrimary)
                    .padding(.horizontal, KnockTokens.spacingL)
                    .frame(maxWidth: .infinity, minHeight: KnockTokens.buttonHeight)
                    .overlay {
                        Capsule(style: .continuous)
                            .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
                    }
                    .contentShape(Capsule(style: .continuous))
                }
                .accessibilityLabel("从相册上传头像")

                if !avatarId.isEmpty && pendingAvatarFileURL == nil {
                    Button {
                        avatarId = ""
                        pendingAvatarFileURL = nil
                        pendingAvatarImage = nil
                        SelfTestLog.add("persona edit avatar reset to placeholder")
                    } label: {
                        HStack(spacing: KnockTokens.spacingXS) {
                            Image(systemName: "arrow.uturn.backward")
                                .font(.system(size: 11, weight: .heavy))
                            KnockText("恢复首字占位", role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                        }
                        .frame(minHeight: 32)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("恢复首字占位")
                }
            }
        }
    }

    @ViewBuilder
    private var avatarPreview: some View {
        if let image = pendingAvatarImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 84, height: 84)
                .clipShape(Circle())
                .overlay {
                    Circle().strokeBorder(KnockTokens.electricRing, lineWidth: KnockTokens.strokeWidth)
                }
                .accessibilityHidden(true)
        } else {
            RoleAvatar(avatarId: avatarId, name: name.isEmpty ? "新" : name, size: 84)
        }
    }

    // MARK: - 基础信息

    private var basicCard: some View {
        KnockFormCard(title: "基础信息", systemImage: "person.text.rectangle") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockInputField(title: "姓名（必填）", placeholder: "如：新代理人", text: $name)
                KnockInputField(title: "标签（逗号分隔）", placeholder: "如：新艾利都代理人,支援,温和", text: $tags)
                VStack(alignment: .leading, spacing: KnockTokens.spacingXS) {
                    KnockText("简介", role: .captionStrong, color: KnockTokens.textSecondary)
                    KnockTextArea("一句话介绍（留空自动生成）", text: $intro, minHeight: 88, accessibilityLabel: "简介")
                }
                KnockInputField(title: "性别（选填）", placeholder: "如：女 / 男 / 无性别", text: $gender)
            }
        }
    }

    // MARK: - 人设描述

    private var personaCard: some View {
        KnockFormCard(
            title: "人设描述",
            systemImage: "text.quote",
            accessory: "私聊槽位 \(privateActiveSlot)"
        ) {
            VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
                KnockTextArea(
                    "角色的人格设定 / 说话风格 / 背景…",
                    text: $persona,
                    minHeight: 150,
                    accessibilityLabel: "私聊人设描述"
                )
                KnockText(
                    "角色的人格设定 / 说话风格 / 背景。保存后写入当前私聊槽位（operators.privatePrompt）。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - 初始关系

    private var relationCard: some View {
        KnockFormCard(title: "初始关系", systemImage: "link") {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                KnockMenuRow(
                    systemImage: "link",
                    valueText: relationDisplayLabel,
                    accessibilityLabel: "选择初始关系",
                    actions: relationMenuActions
                )

                if !relationOptions.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: KnockTokens.spacingXS) {
                            ForEach(relationOptions) { option in
                                KnockChip(
                                    title: option.label,
                                    isSelected: relationType == option.label
                                ) {
                                    relationType = option.label
                                }
                            }
                        }
                        .padding(.vertical, KnockTokens.spacingXXS)
                    }
                    .accessibilityLabel("关系类型快捷选择")
                }

                KnockText(
                    "关系类型值域与 Android RelationshipType 枚举一致（24 种）。写入 operators.userRelation。",
                    role: .caption,
                    color: KnockTokens.textSecondary,
                    lineSpacing: 2
                )
                .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: - 高级（提示词槽位覆盖）

    private var advancedCard: some View {
        KnockPanel {
            VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        showAdvanced.toggle()
                    }
                } label: {
                    HStack(spacing: KnockTokens.spacingS) {
                        Image(systemName: "slider.horizontal.3")
                            .font(.system(size: 13, weight: .heavy))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .accessibilityHidden(true)
                        KnockText("高级：提示词槽位覆盖", role: .sectionTitle, lineLimit: 1)
                        Spacer(minLength: KnockTokens.spacingS)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 11, weight: .heavy))
                            .foregroundStyle(KnockTokens.textSecondary)
                            .rotationEffect(.degrees(showAdvanced ? 180 : 0))
                            .accessibilityHidden(true)
                    }
                    .frame(minHeight: 32)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("高级：提示词槽位覆盖")
                .accessibilityValue(showAdvanced ? "已展开" : "已折叠")

                if showAdvanced {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingL) {
                        promptSlotEditor(
                            title: "私聊人设槽位",
                            activeSlot: privateActiveSlot,
                            slots: slotValues(private: true),
                            text: $persona,
                            onSelectSlot: selectPrivateSlot
                        )
                        KnockDivider()
                        promptSlotEditor(
                            title: "群聊人设槽位",
                            activeSlot: groupActiveSlot,
                            slots: slotValues(private: false),
                            text: $groupPersona,
                            onSelectSlot: selectGroupSlot
                        )
                        KnockText(
                            "槽位键：operator_prompt_slot_<id>_{private,group}_{1..3}；保存时 DB 记录当前槽位内容。",
                            role: .caption,
                            color: KnockTokens.textSecondary,
                            lineSpacing: 2
                        )
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.top, KnockTokens.spacingXS)
                }
            }
        }
    }

    private func promptSlotEditor(
        title: String,
        activeSlot: Int,
        slots: [String],
        text: Binding<String>,
        onSelectSlot: @escaping (Int) -> Void
    ) -> some View {
        let slotItems = (1...3).map { KnockSegmentItem(value: $0, title: "\($0)号") }
        let slotSelection = Binding<Int>(
            get: { activeSlot },
            set: { onSelectSlot($0) }
        )
        return VStack(alignment: .leading, spacing: KnockTokens.spacingS) {
            HStack(spacing: KnockTokens.spacingS) {
                KnockText(title, role: .captionStrong, color: KnockTokens.textSecondary, lineLimit: 1)
                Spacer(minLength: KnockTokens.spacingS)
                KnockText(
                    slots[activeSlot - 1].isEmpty ? "空槽位" : "已有内容",
                    role: .caption,
                    color: KnockTokens.textTertiary,
                    lineLimit: 1
                )
            }

            KnockSegmentedBar(
                items: slotItems,
                selection: slotSelection,
                height: KnockTokens.segmentedSmallHeight,
                showsIcons: false
            )
            .accessibilityLabel("\(title)选择")

            KnockTextArea(
                "槽位内容…",
                text: text,
                minHeight: 110,
                accessibilityLabel: title
            )
        }
    }

    // MARK: - 新建：保存后进入聊天

    private var enterChatCard: some View {
        KnockPanel {
            Toggle(isOn: $enterChatAfterSave) {
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText("保存后直接进入聊天", role: .rowTitle, lineLimit: 1)
                    KnockText(
                        "创建角色后立即打开与「\(name.isEmpty ? "新角色" : name)」的会话",
                        role: .caption,
                        color: KnockTokens.textSecondary,
                        lineLimit: 2
                    )
                }
            }
            .toggleStyle(KnockSwitchToggleStyle())
            .accessibilityLabel("保存后直接进入聊天")
        }
    }

    // MARK: - 编辑：删除

    private var deleteCard: some View {
        Button {
            showDeleteConfirm = true
        } label: {
            HStack(spacing: KnockTokens.spacingS) {
                Image(systemName: "trash")
                    .font(.system(size: 14, weight: .heavy))
                Text("删除角色")
            }
        }
        .buttonStyle(KnockDangerButtonStyle())
        .accessibilityLabel("删除角色")
    }

    private func errorBanner(_ text: String) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(alignment: .top, spacing: KnockTokens.spacingS) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(KnockTokens.dangerBright)
                    .accessibilityHidden(true)
                KnockText(text, role: .caption, color: KnockTokens.dangerBright, lineLimit: 4)
                Spacer(minLength: 0)
            }
        }
    }

    // MARK: - 敲敲头像选择 Sheet（原 147 内置头像默认不展示）

    private var knockAvatarSheet: some View {
        NavigationStack {
            ZStack {
                AmbientBackground(parallax: 0, showsStandee: false)

                VStack(spacing: 0) {
                    HStack(spacing: KnockTokens.spacingM) {
                        KnockText("敲敲头像", role: .pageTitle, lineLimit: 1)
                        Spacer(minLength: KnockTokens.spacingS)
                        KnockCloseButton(label: "关闭头像选择") {
                            showKnockAvatarPicker = false
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .frame(minHeight: KnockTokens.headerHeight)
                    .background(KnockTokens.canvas)

                    ScrollView {
                        VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                            defaultAvatarOption
                            KnockGroupTitle(title: "敲敲头像素材", systemImage: "sparkles")
                                .padding(.top, KnockTokens.spacingS)

                            LazyVGrid(
                                columns: Array(repeating: GridItem(.flexible(), spacing: KnockTokens.spacingM), count: 4),
                                spacing: KnockTokens.spacingM
                            ) {
                                ForEach(1...16, id: \.self) { number in
                                    knockAvatarCell(number: number)
                                }
                            }

                            KnockText(
                                "素材放入 Resources/AvatarsKnock/knock_<id>.png 后自动生效；缺失时显示首字占位。旧内置头像（1–147）资源已移除，仅保留值域解析兼容（渲染走首字占位）。",
                                role: .caption,
                                color: KnockTokens.textSecondary,
                                lineSpacing: 2
                            )
                            .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.horizontal, KnockTokens.screenPadding)
                        .padding(.vertical, KnockTokens.spacingM)
                    }
                    .scrollIndicators(.hidden)
                }
            }
            .knockHideSystemChrome()
        }
    }

    private var defaultAvatarOption: some View {
        Button {
            avatarId = ""
            pendingAvatarFileURL = nil
            pendingAvatarImage = nil
            showKnockAvatarPicker = false
            SelfTestLog.add("persona edit avatar selected=placeholder")
        } label: {
            HStack(spacing: KnockTokens.spacingM) {
                KnockAvatar(name: previewName, size: KnockTokens.rowIconSize)
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText("首字占位（默认）", role: .rowTitle, lineLimit: 1)
                    KnockText("黑底 + 1.5pt 浅蓝圈 + 白色 heavy 首字", role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                }
                Spacer(minLength: KnockTokens.spacingS)
                if avatarId.isEmpty {
                    Image(systemName: "checkmark")
                        .font(.system(size: 13, weight: .heavy))
                        .foregroundStyle(KnockTokens.textPrimary)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, KnockTokens.rowPaddingLeading)
            .padding(.trailing, KnockTokens.rowPaddingTrailing - KnockTokens.rowPaddingLeading)
            .frame(minHeight: KnockTokens.rowHeight)
            .background(Capsule(style: .continuous).fill(KnockTokens.row))
            .overlay {
                Capsule(style: .continuous)
                    .strokeBorder(KnockTokens.strokeSecondary, lineWidth: KnockTokens.strokeWidth)
            }
            .contentShape(Capsule(style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("首字占位头像")
        .accessibilityAddTraits(avatarId.isEmpty ? [.isSelected] : [])
    }

    private func knockAvatarCell(number: Int) -> some View {
        let knockId = String(format: "knock:%02d", number)
        let isSelected = avatarId == knockId
        return Button {
            avatarId = knockId
            pendingAvatarFileURL = nil
            pendingAvatarImage = nil
            showKnockAvatarPicker = false
            SelfTestLog.add("persona edit knock avatar selected=\(knockId)")
        } label: {
            VStack(spacing: KnockTokens.spacingXS) {
                KnockAvatar(avatarId: knockId, name: previewName, size: 56)
                KnockText(
                    String(format: "%02d", number),
                    role: .caption,
                    color: isSelected ? KnockTokens.textPrimary : KnockTokens.textTertiary,
                    lineLimit: 1
                )
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, KnockTokens.spacingS)
            .background {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                    .fill(KnockTokens.row)
            }
            .overlay {
                RoundedRectangle(cornerRadius: KnockTokens.panelRadiusSmall, style: .continuous)
                    .strokeBorder(
                        isSelected ? KnockTokens.electric : KnockTokens.strokeSecondary,
                        lineWidth: KnockTokens.strokeWidth
                    )
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("敲敲头像 \(number)\(isSelected ? "，已选中" : "")")
    }

    /// 头像预览用的名字（占位首字取首个字符）。
    private var previewName: String {
        name.isEmpty ? "新" : name
    }

    // MARK: - 头像解析

    private var avatarPreviewTitle: String {
        if pendingAvatarFileURL != nil { return "相册图片（保存后入库）" }
        switch RoleAvatarResolver.source(for: avatarId) {
        case .knock: return "敲敲头像"
        case .builtin: return "首字占位（旧内置头像资源已移除）"
        case .media: return "上传头像"
        case .placeholder: return "首字占位"
        }
    }

    private var avatarHintText: String {
        if pendingAvatarFileURL != nil { return "已选择相册图片（方形裁剪 ≤1024，保存时入库）" }
        switch RoleAvatarResolver.source(for: avatarId) {
        case .knock(let id):
            return "当前：敲敲头像 knock:\(id)"
        case .builtin(let number):
            return "当前：旧内置头像 \(number)（资源已移除，显示首字占位；保存不变更该值）"
        case .media(let id):
            return "当前：上传头像 \(id)"
        case .placeholder:
            return "默认占位：黑底 + 1.5pt 浅蓝圈 + 白色首字"
        }
    }

    private var relationDisplayLabel: String {
        relationType.isEmpty ? "未设定" : relationType
    }

    /// 初始关系菜单动作：未设定 + 24 种关系类型（当前值打勾）。
    /// 原本是系统 `Menu`（iOS 26 液态玻璃），改走 `KnockContextMenu` 自绘面板；
    /// 选中语义与原实现一致（写回 `relationType`）。
    private var relationMenuActions: [KnockMenuAction] {
        var actions: [KnockMenuAction] = [
            KnockMenuAction("未设定", systemImage: relationType.isEmpty ? "checkmark" : nil) {
                relationType = ""
            }
        ]
        actions += relationOptions.map { option in
            KnockMenuAction(
                option.label,
                systemImage: relationType == option.label ? "checkmark" : nil
            ) {
                relationType = option.label
            }
        }
        return actions
    }

    // MARK: - 数据

    private func load() async {
        relationOptions = environment.bridge.operatorRelationTypes()
        applyLaunchOverrides()
        guard let roleId else {
            SelfTestLog.add("persona edit new form opened relationTypes=\(relationOptions.count)")
            return
        }
        isLoading = true
        defer { isLoading = false }
        switch await environment.bridge.loadOperatorEdit(id: roleId) {
        case .success(let item):
            apply(item)
            SelfTestLog.add("persona edit loaded id=\(item.id) name=\(item.name) avatar=\(item.avatarId) slots=\(item.privateActiveSlot)/\(item.privateSlots.count)")
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("persona edit load failed id=\(roleId) error=\(error.message)")
        }
    }

    /// 截图/自动化用启动参数（仅模拟器验证，不影响正常路径）：
    /// `--persona-avatar-sheet` 自动打开敲敲头像选择、`--persona-advanced` 自动展开高级槽位区。
    private func applyLaunchOverrides() {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--persona-advanced") {
            showAdvanced = true
            Task {
                try? await Task.sleep(nanoseconds: 700_000_000)
                scrollToAdvanced = true
            }
        }
        if args.contains("--persona-avatar-sheet") { showKnockAvatarPicker = true }
    }

    private func apply(_ item: OperatorEditItem) {
        loadedItem = item
        name = item.name
        tags = item.tags
        persona = item.persona
        intro = item.intro
        gender = item.gender
        relationType = item.relationType
        groupPersona = item.groupPersona
        avatarId = item.avatarId
        privateSlots = normalizedSlots(item.privateSlots)
        groupSlots = normalizedSlots(item.groupSlots)
        privateActiveSlot = min(max(item.privateActiveSlot, 1), 3)
        groupActiveSlot = min(max(item.groupActiveSlot, 1), 3)
    }

    private func normalizedSlots(_ slots: [String]) -> [String] {
        var result = Array(slots.prefix(3))
        while result.count < 3 { result.append("") }
        return result
    }

    // MARK: - 槽位切换（保存当前编辑内容 → 载入新槽位内容）

    private func slotValues(private isPrivate: Bool) -> [String] {
        var stored = isPrivate ? privateSlots : groupSlots
        let active = isPrivate ? privateActiveSlot : groupActiveSlot
        let current = isPrivate ? persona : groupPersona
        if active >= 1 && active <= 3 { stored[active - 1] = current }
        return stored
    }

    private func selectPrivateSlot(_ slot: Int) {
        privateSlots[privateActiveSlot - 1] = persona
        privateActiveSlot = slot
        persona = privateSlots[slot - 1]
    }

    private func selectGroupSlot(_ slot: Int) {
        groupSlots[groupActiveSlot - 1] = groupPersona
        groupActiveSlot = slot
        groupPersona = groupSlots[slot - 1]
    }

    // MARK: - 相册头像处理

    private func loadPickedPhoto(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                errorText = "无法读取所选图片"
                return
            }
            guard let jpeg = AvatarImageProcessor.squareJPEG(data: data, maxSide: 1024) else {
                errorText = "图片裁剪失败，请换一张 JPG/PNG 图片"
                return
            }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("t49-avatar-\(UUID().uuidString).jpg")
            try jpeg.write(to: url, options: .atomic)
            pendingAvatarFileURL = url
            pendingAvatarImage = UIImage(data: jpeg)
            errorText = nil
            SelfTestLog.add("persona edit picked photo bytes=\(jpeg.count) path=\(url.lastPathComponent)")
        } catch {
            errorText = "读取相册图片失败：\(error.localizedDescription)"
            SelfTestLog.add("persona edit photo failed error=\(error.localizedDescription)")
        }
    }

    private func cleanupPendingAvatarFile() {
        guard let url = pendingAvatarFileURL else { return }
        try? FileManager.default.removeItem(at: url)
        pendingAvatarFileURL = nil
        pendingAvatarImage = nil
    }

    // MARK: - 保存

    private func save() async {
        guard !isSaving else { return }
        errorText = nil

        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else {
            errorText = "请输入代理人名称"
            return
        }

        isSaving = true
        defer { isSaving = false }

        privateSlots[privateActiveSlot - 1] = persona
        groupSlots[groupActiveSlot - 1] = groupPersona

        var draft = OperatorDraft()
        draft.id = roleId ?? ""
        draft.name = cleanName
        draft.tags = tags.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.intro = intro.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.gender = gender.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.relationType = relationType.trimmingCharacters(in: .whitespacesAndNewlines)
        draft.persona = persona
        draft.groupPersona = groupPersona
        draft.privateSlots = privateSlots
        draft.groupSlots = groupSlots
        draft.privateActiveSlot = privateActiveSlot
        draft.groupActiveSlot = groupActiveSlot
        // 有相册待入库图片时先不写 avatarId（保留旧头像），保存成功后单独入库
        draft.avatarId = pendingAvatarFileURL == nil ? avatarId : ""

        let saveResult: Result<OperatorEditItem, BridgeError>
        if isNew {
            saveResult = await environment.bridge.createOperator(draft)
        } else {
            saveResult = await environment.bridge.updateOperator(draft)
        }

        switch saveResult {
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("persona edit save failed new=\(isNew) error=\(error.message)")
        case .success(var item):
            guard !item.id.isEmpty else {
                errorText = "保存后未返回角色编号"
                return
            }
            // 相册头像：保存后按真实 operatorId 入库
            if let file = pendingAvatarFileURL {
                switch await environment.bridge.setOperatorAvatarFromFile(id: item.id, path: file.path) {
                case .success(let mediaId):
                    item = (try? await reloadItem(id: item.id)) ?? item
                    SelfTestLog.add("persona edit avatar ingested id=\(item.id) media=\(mediaId)")
                    avatarId = mediaId
                case .failure(let error):
                    errorText = "角色已保存，但头像入库失败：\(error.message)"
                    SelfTestLog.add("persona edit avatar ingest failed id=\(item.id) error=\(error.message)")
                }
                cleanupPendingAvatarFile()
            }
            let wantChat = isNew && enterChatAfterSave
            SelfTestLog.add("persona edit saved id=\(item.id) new=\(isNew) name=\(item.name) avatar=\(item.avatarId) personaLen=\(item.persona.count) tags=\(item.tags) relation=\(item.relationType)")
            onSaved?(item)
            dismiss()
            if wantChat {
                switch await environment.bridge.startChat(operatorId: item.id) {
                case .success(let sessionId):
                    SelfTestLog.add("persona edit enter chat id=\(item.id) session=\(sessionId)")
                    router.openChat(sessionId: sessionId)
                case .failure(let error):
                    SelfTestLog.add("persona edit enter chat failed id=\(item.id) error=\(error.message)")
                }
            }
        }
    }

    private func reloadItem(id: String) async throws -> OperatorEditItem? {
        switch await environment.bridge.loadOperatorEdit(id: id) {
        case .success(let item): return item
        case .failure: return nil
        }
    }

    // MARK: - 删除

    private func deleteRole() async {
        guard let roleId else { return }
        isSaving = true
        defer { isSaving = false }
        switch await environment.bridge.deleteOperator(id: roleId) {
        case .success(let deletedId):
            SelfTestLog.add("persona edit deleted id=\(deletedId)")
            onDeleted?(deletedId)
            dismiss()
        case .failure(let error):
            errorText = error.message
            SelfTestLog.add("persona edit delete failed id=\(roleId) error=\(error.message)")
        }
    }
}

// MARK: - 头像方形裁剪（镜像 Android ImageCropperDialog：1:1，≤1024 边）

enum AvatarImageProcessor {
    /// 居中方形裁剪 + 缩放（最长边 ≤ maxSide 像素）+ JPEG 0.9。
    static func squareJPEG(data: Data, maxSide: CGFloat = 1024) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        return squareJPEG(image: image, maxSide: maxSide)
    }

    static func squareJPEG(image: UIImage, maxSide: CGFloat = 1024) -> Data? {
        guard let cgImage = image.cgImage else {
            // 无 CGImage（极少见）：退化为整体绘制缩放
            let side = min(image.size.width, image.size.height)
            guard side > 0 else { return nil }
            let target = min(side, maxSide)
            let renderer = UIGraphicsImageRenderer(size: CGSize(width: target, height: target))
            let output = renderer.image { _ in
                image.draw(in: CGRect(x: 0, y: 0, width: target, height: target))
            }
            return output.jpegData(compressionQuality: 0.9)
        }
        let pixelSide = min(cgImage.width, cgImage.height)
        guard pixelSide > 0 else { return nil }
        let cropRect = CGRect(
            x: CGFloat(cgImage.width - pixelSide) / 2,
            y: CGFloat(cgImage.height - pixelSide) / 2,
            width: CGFloat(pixelSide),
            height: CGFloat(pixelSide)
        )
        guard let cropped = cgImage.cropping(to: cropRect) else { return nil }
        let target = min(CGFloat(pixelSide), maxSide)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: target, height: target))
        let output = renderer.image { _ in
            UIImage(cgImage: cropped, scale: 1, orientation: image.imageOrientation)
                .draw(in: CGRect(x: 0, y: 0, width: target, height: target))
        }
        return output.jpegData(compressionQuality: 0.9)
    }
}