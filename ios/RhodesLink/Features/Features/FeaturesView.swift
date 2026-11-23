//
//  FeaturesView.swift
//  功能 Tab（与 Android 原版 FeaturesScreen 对齐）：
//  - 动态广场（含未读/新动态角标）
//  - 干员日记（含未读角标）
//  - 大家的印象（干员对用户的长期印象总结）
//  - 聊天排行榜（昨日聊天数据排名）
//  - 记忆管理（T24 记忆管线已就绪，行为与数据与 Android 对齐）
//  - 知识库（T48：导入/切块/索引/召回测试，shared KnowledgeBaseBridge 真实数据）
//
//  T52 修复：`momentBadge` / `diaryBadge` 原先写死 0 → 现接入 shared 真实计数：
//  - 动态角标 = `MomentsBridge.badges()`.unreadMoments（新动态）+ unreadComments（未读评论）；
//  - 日记角标 = `DiaryBridge.unreadCount()`（未读干员数，与 Android `getUnreadDiaryCount` 同源）；
//  - 刷新时机：onAppear（Task 首次）+ 每 10 秒轮询（对齐 Android FeaturesScreen 的 LaunchedEffect 周期）；
//  - 样式：未读红 #FA5151（GlassTokens.unreadBadge），复用行尾胶囊 badge。
//
//  说明：泰拉见闻报（报纸）按 v1.1 范围裁决属二期候选，首版不移植（入口保留注释）。
//

import SwiftUI

struct FeaturesView: View {
    @EnvironmentObject private var router: AppRouter
    @EnvironmentObject private var environment: AppEnvironment

    @State private var momentBadge: Int = 0
    @State private var diaryBadge: Int = 0
    /// 角标刷新次数（T52 自检日志/周期刷新断言用）
    @State private var badgeRefreshCount = 0
    /// T48 自检：`--knowledge-selftest` 时自动全屏展示知识库页并跑端到端自检（截图证据）
    @State private var showKnowledgeSelfTest = false

    var body: some View {
        List {
            Section {
                featureRow(
                    title: "动态广场",
                    subtitle: "查看所有干员发布的动态",
                    systemImage: "bubble.left.and.text.bubble.right.fill",
                    tint: Color(hexIfPossible: 0x07C160),
                    badge: momentBadge
                ) {
                    router.openFeature(.moments)
                }

                featureRow(
                    title: "干员日记",
                    subtitle: "查看干员们的内心独白",
                    systemImage: "book.closed.fill",
                    tint: Color(hexIfPossible: 0x1989FA),
                    badge: diaryBadge
                ) {
                    router.openFeature(.diary)
                }

                featureRow(
                    title: "大家的印象",
                    subtitle: "干员对你的长期印象总结",
                    systemImage: "person.crop.circle.badge.questionmark.fill",
                    tint: Color(hexIfPossible: 0xFF9500)
                ) {
                    router.openFeature(.impressions)
                }

                featureRow(
                    title: "聊天排行榜",
                    subtitle: "昨日聊天数据排名",
                    systemImage: "chart.bar.fill",
                    tint: Color(hexIfPossible: 0xFFB300)
                ) {
                    router.openFeature(.ranking)
                }
            }

            Section {
                featureRow(
                    title: "记忆管理",
                    subtitle: "三层记忆 · 锚点 · 隐私与删除",
                    systemImage: "brain.head.profile.fill",
                    tint: GlassTokens.brandOrange
                ) {
                    router.openFeature(.memoryList)
                }

                navigationRow(
                    title: "知识库",
                    subtitle: "导入资料 · 切块索引 · 召回测试",
                    systemImage: "books.vertical.fill",
                    tint: Color(hexIfPossible: 0x5856D6)
                ) {
                    KnowledgeListView()
                }
            } header: {
                Text("数据")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("功能")
        .task {
            // T52 自检夹具：`--features-badge-selftest` 时确保日记未读夹具存在（幂等）
            await FeaturesBadgeAutomation.prepareFixtureIfNeeded(environment: environment)
            // onAppear 首刷（与 Android FeaturesScreen 进入即拉取未读一致）
            await refreshBadges()
            if KnowledgeAutomation.isEnabled {
                showKnowledgeSelfTest = true
            }
            // T52 角标自检（真实 shared 桥 + 真实 DB；断言后停在功能 Tab 供截图）
            await FeaturesBadgeAutomation.runIfNeeded(environment: environment)
        }
        .task {
            // T52：每 10 秒刷新一次动态/日记角标（进入后台/离开页面时随 Task 取消，不泄漏）
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                if Task.isCancelled { break }
                await refreshBadges()
            }
        }
        .fullScreenCover(isPresented: $showKnowledgeSelfTest) {
            NavigationStack {
                KnowledgeListView()
            }
            .environmentObject(environment)
            .environmentObject(router)
        }
    }

    // MARK: - T52 未读角标数据

    /// 从 shared 读取真实未读数并更新界面角标（与 Android FeaturesScreen 同源）：
    /// 动态 = 新动态数 + 未读评论数；日记 = 未读干员数。读取失败保持上一次值（首帧 0），不阻塞 UI。
    private func refreshBadges() async {
        let badge = await environment.bridge.loadMomentBadges()
        let diary = await environment.bridge.diaryUnreadCount()
        let moments = badge.map { max(0, $0.unreadMoments + $0.unreadComments) } ?? momentBadge
        let diaryCount = diary >= 0 ? diary : diaryBadge
        momentBadge = moments
        diaryBadge = diaryCount
        badgeRefreshCount += 1
        PolishSelfTestLog.add(
            "features.badge refresh#\(badgeRefreshCount) " +
            "unreadMoments=\(badge?.unreadMoments ?? -1) unreadComments=\(badge?.unreadComments ?? -1) " +
            "momentBadge=\(moments) diaryUnread=\(diary) diaryBadge=\(diaryCount)"
        )
    }

    @ViewBuilder
    private func featureRow(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        badge: Int = 0,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            featureRowLabel(
                title: title,
                subtitle: subtitle,
                systemImage: systemImage,
                tint: tint,
                badge: badge
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title)，\(subtitle)")
    }

    /// 直接入栈的行（不走 AppRouter 路由表，避免与其他并行批次改 AppRouter 冲突）
    @ViewBuilder
    private func navigationRow<Destination: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        badge: Int = 0,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink {
            destination()
        } label: {
            featureRowLabel(
                title: title,
                subtitle: subtitle,
                systemImage: systemImage,
                tint: tint,
                badge: badge,
                showsChevron: false
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title)，\(subtitle)")
    }

    @ViewBuilder
    private func featureRowLabel(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        badge: Int,
        showsChevron: Bool = true
    ) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 9, style: .continuous).fill(tint))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 8)

            if badge > 0 {
                // T52：复用未读红（#FA5151）；99+ 截断避免撑破行
                Text(badge > 99 ? "99+" : "\(badge)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Capsule().fill(GlassTokens.unreadBadge))
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

private extension Color {
    /// 0xRRGGBB → Color；非法值回退品牌橙
    init(hexIfPossible: UInt32) {
        self.init(hex: hexIfPossible)
    }
}
