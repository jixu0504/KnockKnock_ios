//
//  RoleEditView.swift
//  角色人设编辑（A2 / T49，新建/编辑共用）：姓名（必填）、标签、简介、人设描述、
//  初始关系（20+ 关系类型，值域取 Android RelationshipType）、头像（内置 147 网格 +
//  相册 PhotosPicker → 方形裁剪 ≤1024 → MediaRepository 入库）、高级区（折叠：提示词槽位覆盖）。
//
//  数据：`OperatorBridge`（shared/iosMain，真实 SQLite：operators + settings 槽位 + 媒体目录）。
//  - 新建：OperatorBridge.create（id = custom_<uuid>，对齐 Android OperatorViewModel.saveOperator）
//  - 编辑：OperatorBridge.update（含 prompt 槽位键 operator_prompt_slot_* 覆盖）
//  - 删除：OperatorBridge.delete（对齐 Android deleteOperators：清私聊会话/记忆/关系/知识库关联）
//  保存成功后回调上层刷新列表/详情；新建可选「保存后直接进入聊天」。
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
    @State private var showBuiltinAvatarPicker = false
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

                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(spacing: 12) {
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
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 28)
                    }
                    .scrollIndicators(.hidden)
                    .onChange(of: scrollToAdvanced) { _, target in
                        guard target else { return }
                        withAnimation(.easeInOut(duration: 0.35)) {
                            proxy.scrollTo("advanced", anchor: .top)
                        }
                    }
                }

                if isLoading {
                    ProgressView("读取角色资料…")
                        .padding(24)
                        .cardSurface(cornerRadius: 16)
                }
            }
            .navigationTitle(isNew ? "新建角色" : "编辑角色")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }
                        .accessibilityLabel("取消编辑")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    if isSaving {
                        ProgressView()
                    } else {
                        Button("保存") {
                            Task { await save() }
                        }
                        .fontWeight(.semibold)
                        .accessibilityLabel("保存角色")
                    }
                }
            }
            .task { await load() }
            .sheet(isPresented: $showBuiltinAvatarPicker) { builtinAvatarSheet }
            .onChange(of: photoItem) { _, newItem in
                guard let newItem else { return }
                Task { await loadPickedPhoto(newItem) }
            }
            .alert("删除角色", isPresented: $showDeleteConfirm) {
                Button("取消", role: .cancel) {}
                Button("确认删除", role: .destructive) {
                    Task { await deleteRole() }
                }
            } message: {
                Text("确定要删除「\(name)」吗？删除后该角色的私聊会话、记忆、关系与知识库关联将永久丢失（聊天消息文件保留）。")
            }
        }
    }

    // MARK: - 卡片

    private var avatarCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("头像")
            HStack(alignment: .center, spacing: 16) {
                avatarPreview
                VStack(alignment: .leading, spacing: 8) {
                    Button {
                        showBuiltinAvatarPicker = true
                    } label: {
                        Label("内置头像（1-147）", systemImage: "square.grid.3x3.fill")
                            .font(.subheadline.weight(.medium))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(GlassTokens.brandOrange)
                    .accessibilityLabel("选择内置头像")

                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label("从相册上传", systemImage: "photo.on.rectangle")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(GlassTokens.brandOrange)
                    }
                    .accessibilityLabel("从相册上传头像")

                    Text(avatarHintText)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private var basicCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("基础信息")
            labeledField("姓名（必填）") {
                textField($name, placeholder: "如：新干员")
            }
            labeledField("标签（逗号分隔）") {
                textField($tags, placeholder: "如：罗德岛干员,医疗,温和")
            }
            labeledField("简介") {
                textField($intro, placeholder: "一句话介绍（留空自动生成）", axis: .vertical)
            }
            labeledField("性别（选填）") {
                textField($gender, placeholder: "如：女 / 男 / 无性别")
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private var personaCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                sectionTitle("人设描述")
                Spacer(minLength: 4)
                Text("私聊槽位 \(privateActiveSlot)")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(GlassTokens.brandOrange)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(GlassTokens.bubbleSystem, in: Capsule())
            }
            TextEditor(text: $persona)
                .font(.subheadline)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 150)
                .padding(8)
                .background(
                    GlassTokens.bubbleSystem,
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
                .accessibilityLabel("私聊人设描述")
            Text("角色的人格设定 / 说话风格 / 背景。保存后写入当前私聊槽位（operators.privatePrompt）。")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private var relationCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            sectionTitle("初始关系")
            HStack(spacing: 10) {
                Menu {
                    Button("未设定") { relationType = "" }
                    Divider()
                    ForEach(relationOptions) { option in
                        Button(option.label) { relationType = option.label }
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "link")
                            .font(.caption)
                        Text(relationDisplayLabel)
                            .font(.subheadline.weight(.medium))
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2)
                    }
                    .foregroundStyle(.primary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(
                        GlassTokens.bubbleSystem,
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )
                }
                .accessibilityLabel("选择初始关系")
            }
            if !relationOptions.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(relationOptions) { option in
                            Button(option.label) {
                                relationType = option.label
                            }
                            .font(.caption.weight(.medium))
                            .foregroundStyle(relationType == option.label ? .white : .secondary)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 5)
                            .background(
                                relationType == option.label
                                    ? GlassTokens.brandOrange
                                    : GlassTokens.bubbleSystem,
                                in: Capsule()
                            )
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            Text("关系类型值域与 Android RelationshipType 枚举一致（24 种）。写入 operators.userRelation。")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private var advancedCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            DisclosureGroup(isExpanded: $showAdvanced) {
                VStack(alignment: .leading, spacing: 14) {
                    promptSlotEditor(
                        title: "私聊人设槽位",
                        activeSlot: privateActiveSlot,
                        slots: slotValues(private: true),
                        text: $persona,
                        onSelectSlot: selectPrivateSlot
                    )
                    Divider().opacity(0.3)
                    promptSlotEditor(
                        title: "群聊人设槽位",
                        activeSlot: groupActiveSlot,
                        slots: slotValues(private: false),
                        text: $groupPersona,
                        onSelectSlot: selectGroupSlot
                    )
                    Text("槽位键：operator_prompt_slot_<id>_{private,group}_{1..3}；保存时 DB 记录当前槽位内容。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 8)
            } label: {
                Label("高级：提示词槽位覆盖", systemImage: "slider.horizontal.3")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: 20)
    }

    private func promptSlotEditor(
        title: String,
        activeSlot: Int,
        slots: [String],
        text: Binding<String>,
        onSelectSlot: @escaping (Int) -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            HStack(spacing: 6) {
                ForEach(1...3, id: \.self) { slot in
                    Button("\(slot)号") { onSelectSlot(slot) }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(activeSlot == slot ? .white : .secondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(
                            activeSlot == slot ? GlassTokens.brandOrange : GlassTokens.bubbleSystem,
                            in: Capsule()
                        )
                        .buttonStyle(.plain)
                }
                Spacer(minLength: 0)
                Text(slots[activeSlot - 1].isEmpty ? "空槽位" : "已有内容")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            TextEditor(text: text)
                .font(.subheadline)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 110)
                .padding(8)
                .background(
                    GlassTokens.bubbleSystem,
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
        }
    }

    private var enterChatCard: some View {
        Toggle(isOn: $enterChatAfterSave) {
            VStack(alignment: .leading, spacing: 2) {
                Text("保存后直接进入聊天")
                    .font(.subheadline.weight(.medium))
                Text("创建角色后立即打开与「\(name.isEmpty ? "新角色" : name)」的会话")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .tint(GlassTokens.brandOrange)
        .padding(14)
        .cardSurface(cornerRadius: 20)
    }

    private var deleteCard: some View {
        Button {
            showDeleteConfirm = true
        } label: {
            Label("删除角色", systemImage: "trash")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("删除角色")
    }

    // MARK: - 子视图

    private func sectionTitle(_ text: String) -> some View {
        Text(text)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
    }

    private func labeledField<Content: View>(
        _ title: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            content()
        }
    }

    private func textField(
        _ binding: Binding<String>,
        placeholder: String,
        axis: Axis = .horizontal
    ) -> some View {
        TextField(placeholder, text: binding, axis: axis)
            .font(.subheadline)
            .lineLimit(axis == .vertical ? 3...6 : 1...1)
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                GlassTokens.bubbleSystem,
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
    }

    @ViewBuilder
    private var avatarPreview: some View {
        if let image = currentAvatarImage {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 84, height: 84)
                .clipShape(Circle())
                .overlay(Circle().strokeBorder(GlassTokens.glassStroke, lineWidth: 1))
        } else {
            AvatarBadge(name: name.isEmpty ? "新" : name, size: 84)
        }
    }

    /// 内置 147 网格头像选择 Sheet（对齐 Android OperatorAvatarImage 内置头像值域）。
    private var builtinAvatarSheet: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(
                    columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6),
                    spacing: 8
                ) {
                    ForEach(1...147, id: \.self) { number in
                        Button {
                            avatarId = "\(number)"
                            pendingAvatarFileURL = nil
                            pendingAvatarImage = nil
                            showBuiltinAvatarPicker = false
                            SelfTestLog.add("persona edit builtin avatar selected=\(number)")
                        } label: {
                            ZStack(alignment: .bottomTrailing) {
                                if let image = UIImage(named: "avatar_\(number)") {
                                    Image(uiImage: image)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(width: 48, height: 48)
                                        .clipShape(Circle())
                                } else {
                                    AvatarBadge(name: "\(number)", size: 48)
                                }
                                if avatarId == "\(number)" {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.caption)
                                        .foregroundStyle(GlassTokens.brandOrange)
                                        .background(Circle().fill(.white).padding(1))
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("内置头像 \(number)")
                    }
                }
                .padding(14)
            }
            .scrollIndicators(.hidden)
            .navigationTitle("内置头像 1-147")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { showBuiltinAvatarPicker = false }
                }
            }
        }
    }

    private func errorBanner(_ text: String) -> some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.footnote)
            .foregroundStyle(.red)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .cardSurface(cornerRadius: 14)
    }

    // MARK: - 头像解析

    private var currentAvatarImage: UIImage? {
        if let pendingAvatarImage { return pendingAvatarImage }
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        if let number = Int(trimmed), (1...147).contains(number) {
            return UIImage(named: "avatar_\(number)")
        }
        if trimmed.hasPrefix("media/") {
            let path = environment.bridge.operatorMediaPath(mediaId: trimmed)
            guard !path.isEmpty else { return nil }
            let file = path.hasPrefix("file://") ? String(path.dropFirst(7)) : path
            return UIImage(contentsOfFile: file)
        }
        return nil
    }

    private var avatarHintText: String {
        if pendingAvatarFileURL != nil { return "已选择相册图片（方形裁剪 ≤1024，保存时入库）" }
        let trimmed = avatarId.trimmingCharacters(in: .whitespacesAndNewlines)
        if let number = Int(trimmed), (1...147).contains(number) { return "当前：内置头像 \(number)" }
        if trimmed.hasPrefix("media/") { return "当前：上传头像 \(trimmed)" }
        return "默认占位头像（首字色块）"
    }

    private var relationDisplayLabel: String {
        relationType.isEmpty ? "未设定" : relationType
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
    /// `--persona-avatar-sheet` 自动打开内置头像网格、`--persona-advanced` 自动展开高级槽位区。
    private func applyLaunchOverrides() {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("--persona-advanced") {
            showAdvanced = true
            Task {
                try? await Task.sleep(nanoseconds: 700_000_000)
                scrollToAdvanced = true
            }
        }
        if args.contains("--persona-avatar-sheet") { showBuiltinAvatarPicker = true }
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
            errorText = "请输入干员名称"
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
