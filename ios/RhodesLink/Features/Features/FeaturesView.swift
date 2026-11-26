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
//  - 刷新时机：onAppear（Task 首次）+ 每 10 秒轮询（对齐 Android FeaturesScreen 的 LaunchedEffect 周期）。
//
//  敲敲视觉（P3）：隐藏系统导航栏 → `KnockHeader`（黄色品牌标 + 左对齐 heavy 标题）；
//  每条功能入口 = `KnockPanel` 面板 + 44pt 圆形图标位 + 15pt heavy 标题 + 13pt 灰说明 + 未读红角标；
//  页面底 = 纯黑 + 斜切迷彩纹理（`AmbientBackground`），无毛玻璃/渐变。
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
        ZStack {
            AmbientBackground(parallax: 0, showsStandee: false)

            VStack(spacing: 0) {
                KnockHeader(title: "功能", subtitle: "广场与数据", showsBrand: true)

                ScrollView {
                    VStack(alignment: .leading, spacing: KnockTokens.spacingM) {
                        KnockGroupTitle(title: "互动", systemImage: "bubble.left.and.text.bubble.right")
                        featureCard(
                            title: "动态广场",
                            subtitle: "查看所有代理人发布的动态",
                            systemImage: "bubble.left.and.text.bubble.right.fill",
                            badge: momentBadge
                        ) {
                            router.openFeature(.moments)
                        }
                        featureCard(
                            title: "代理人日记",
                            subtitle: "查看代理人们的内心独白",
                            systemImage: "book.closed.fill",
                            badge: diaryBadge
                        ) {
                            router.openFeature(.diary)
                        }
                        featureCard(
                            title: "大家的印象",
                            subtitle: "代理人对你的长期印象总结",
                            systemImage: "person.crop.circle.badge.questionmark.fill"
                        ) {
                            router.openFeature(.impressions)
                        }
                        featureCard(
                            title: "聊天排行榜",
                            subtitle: "昨日聊天数据排名",
                            systemImage: "chart.bar.fill"
                        ) {
                            router.openFeature(.ranking)
                        }

                        KnockGroupTitle(title: "数据", systemImage: "tray.full")
                            .padding(.top, KnockTokens.spacingS)
                        featureCard(
                            title: "记忆管理",
                            subtitle: "三层记忆 · 锚点 · 隐私与删除",
                            systemImage: "brain.head.profile.fill"
                        ) {
                            router.openFeature(.memoryList)
                        }
                        navigationCard(
                            title: "知识库",
                            subtitle: "导入资料 · 切块索引 · 召回测试",
                            systemImage: "books.vertical.fill"
                        ) {
                            KnowledgeListView()
                        }
                    }
                    .padding(.horizontal, KnockTokens.screenPadding)
                    .padding(.top, KnockTokens.spacingS)
                    .padding(.bottom, KnockTokens.spacingXL)
                }
                .scrollIndicators(.hidden)
                .knockScrollEdgeHidden()
            }
        }
        .knockHiddenNavigationBar()
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

    // MARK: - 入口卡片

    /// 路由型入口（`AppRouter.openFeature`）：敲敲面板卡片。
    private func featureCard(
        title: String,
        subtitle: String,
        systemImage: String,
        badge: Int = 0,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            featureCardLabel(
                title: title,
                subtitle: subtitle,
                systemImage: systemImage,
                badge: badge
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title)，\(subtitle)")
    }

    /// 直接入栈的入口（不走 AppRouter 路由表，避免与其他并行批次改 AppRouter 冲突）。
    private func navigationCard<Destination: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        badge: Int = 0,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink {
            destination()
        } label: {
            featureCardLabel(
                title: title,
                subtitle: subtitle,
                systemImage: systemImage,
                badge: badge
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(title)，\(subtitle)")
    }

    /// 卡片内容：KnockPanel + 44pt 圆形图标 + 标题/说明 + 未读角标 + chevron。
    private func featureCardLabel(
        title: String,
        subtitle: String,
        systemImage: String,
        badge: Int
    ) -> some View {
        KnockPanel(padding: KnockTokens.spacingM) {
            HStack(spacing: KnockTokens.spacingM) {
                KnockRowGlyph(systemImage: systemImage)
                VStack(alignment: .leading, spacing: KnockTokens.spacingXXS) {
                    KnockText(title, role: .rowTitle, lineLimit: 1)
                    KnockText(subtitle, role: .caption, color: KnockTokens.textSecondary, lineLimit: 1)
                }
                Spacer(minLength: KnockTokens.spacingS)
                if badge > 0 {
                    // 未读红角标（99+ 截断避免撑破行）
                    KnockBadge(text: badge > 99 ? "99+" : "\(badge)", variant: .danger)
                }
                KnockChevron()
            }
        }
    }
}