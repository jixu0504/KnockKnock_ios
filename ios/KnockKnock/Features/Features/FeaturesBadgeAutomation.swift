//
//  FeaturesBadgeAutomation.swift
//  T52 功能 Tab 未读角标自检（仅 `--features-badge-selftest` 启动参数启用，对用户路径零影响）：
//  1) 预置未读夹具：日记（`--features-badge-selftest` 启动时调 `DiaryBridge.debugEnsureFixture`
//     把 read_at 归零 → 未读干员数 ≥1）；动态/评论未读由外部 sqlite 预置（见 handoff 脚本）；
//  2) 断言 shared 真实计数（与 FeaturesView 角标同源桥）：
//     - `MomentsBridge.badges()`：unreadMoments（新动态）≥1、unreadComments（未读评论）≥1；
//     - `DiaryBridge.unreadCount()`：未读干员数 ≥1；
//  3) 断言 FeaturesView 显示的聚合值 = 桥值（momentBadge = 新动态+未读评论，diaryBadge = 未读干员数）；
//  4) 断言每 10 秒周期刷新：等待 11s 后日志中至少 2 次 `features.badge refresh`；
//  5) 结束时停在功能 Tab 并保留角标（截图证据）。
//
//  证据：Documents/t52-features-badge-selftest.log + NSLog；DB 预置状态用 sqlite3 复核。
//

import Foundation

@MainActor
enum FeaturesBadgeAutomation {
    static let isEnabled = ProcessInfo.processInfo.arguments.contains("--features-badge-selftest")
    private static var didPrepare = false
    private static var didRun = false

    /// 幂等预置日记未读夹具（仅自检启动时执行一次）。动态/评论夹具由外部 sqlite 脚本预置。
    static func prepareFixtureIfNeeded(environment: AppEnvironment) async {
        guard isEnabled, !didPrepare else { return }
        didPrepare = true
        PolishSelfTestLog.add("=== features badge selftest fixture ===")
        let line = await environment.bridge.ensureDiaryFixture()
        PolishSelfTestLog.add("fixture.diary \(line)")
    }

    /// 从日志中解析 FeaturesView 最近一次刷新后实际显示的角标值。
    private static func latestDisplayedBadges() -> (moment: Int, diary: Int)? {
        for line in PolishSelfTestLog.snapshot().reversed() where line.contains("features.badge refresh") {
            var moment: Int?
            var diary: Int?
            for token in line.split(separator: " ") {
                if token.hasPrefix("momentBadge=") { moment = Int(token.dropFirst("momentBadge=".count)) }
                if token.hasPrefix("diaryBadge=") { diary = Int(token.dropFirst("diaryBadge=".count)) }
            }
            if let moment, let diary { return (moment, diary) }
        }
        return nil
    }

    /// 桥 + View 双层断言；结束后落盘日志（调用方已先刷新一次角标）。
    static func runIfNeeded(environment: AppEnvironment) async {
        guard isEnabled, !didRun else { return }
        didRun = true

        var passCount = 0
        var failCount = 0
        func check(_ name: String, _ ok: Bool, _ detail: String = "") {
            if ok { passCount += 1 } else { failCount += 1 }
            PolishSelfTestLog.add("\(ok ? "PASS" : "FAIL") \(name)\(detail.isEmpty ? "" : " \(detail)")")
        }

        let bridge = environment.bridge
        PolishSelfTestLog.add("=== features badge selftest start ===")

        // 1) shared 桥真实计数（与 FeaturesView 角标同源）
        let badge = await bridge.loadMomentBadges()
        let diary = await bridge.diaryUnreadCount()
        let unreadMoments = badge?.unreadMoments ?? -1
        let unreadComments = badge?.unreadComments ?? -1
        check(
            "bridge.momentsBadge",
            badge != nil && unreadMoments >= 1,
            "unreadMoments=\(unreadMoments) unreadComments=\(unreadComments)"
        )
        check(
            "bridge.unreadComments",
            badge != nil && unreadComments >= 1,
            "unreadComments=\(unreadComments)"
        )
        check("bridge.diaryUnreadCount", diary >= 1, "diaryUnread=\(diary)")

        // 2) 等待 FeaturesView 完成 onAppear 首刷
        var displayed = latestDisplayedBadges()
        var waitCount = 0
        while displayed == nil && waitCount < 50 {
            try? await Task.sleep(nanoseconds: 100_000_000)
            waitCount += 1
            displayed = latestDisplayedBadges()
        }
        let expectedMoment = max(0, unreadMoments) + max(0, unreadComments)
        let expectedDiary = max(0, diary)
        let shown = displayed ?? (moment: -1, diary: -1)
        check(
            "view.momentBadge",
            shown.moment == expectedMoment,
            "displayed=\(shown.moment) expected=\(expectedMoment) (newMoment+unreadComment)"
        )
        check("view.diaryBadge", shown.diary == expectedDiary, "displayed=\(shown.diary) expected=\(expectedDiary)")

        // 3) 周期刷新：等 11s（10s 轮询点）后日志应有 ≥2 次 refresh
        let before = PolishSelfTestLog.count(containing: "features.badge refresh")
        try? await Task.sleep(nanoseconds: 11_000_000_000)
        let after = PolishSelfTestLog.count(containing: "features.badge refresh")
        check("view.periodicRefresh10s", after >= max(2, before + 1), "refreshCount=\(before)→\(after)")

        // 4) 刷新后角标仍为真实值（未被周期刷新覆盖成 0）
        let shownAfter = latestDisplayedBadges() ?? (moment: -1, diary: -1)
        check(
            "view.badgeStableAfterRefresh",
            shownAfter.moment == expectedMoment && shownAfter.diary == expectedDiary,
            "moment=\(shownAfter.moment) diary=\(shownAfter.diary)"
        )

        let allPass = failCount == 0
        PolishSelfTestLog.add("=== features badge selftest done allPASS=\(allPass) PASS=\(passCount) FAIL=\(failCount) ===")
        PolishSelfTestLog.flushToDisk(
            fileName: "t52-features-badge-selftest.log",
            summary: "T52 功能 Tab 角标：新动态=\(unreadMoments) 未读评论=\(unreadComments) 日记未读=\(diary) " +
                "显示 momentBadge=\(shown.moment) diaryBadge=\(shown.diary) PASS=\(passCount) FAIL=\(failCount)"
        )
    }
}
