import Testing
@testable import PocketKeePass

/// The quick unlock expiry rule, `QuickUnlock.isPasswordDue`. The same cases as Android's
/// `PasswordDueTest`, plus the ones that come from iOS having a boot time instead of a boot count.
struct PasswordDueTests {
    private let day: Double = 86_400
    private let typed = Moment(bootTime: 1_790_000_000, uptime: 1_000)

    @Test func quickUnlockWorksUntilTheIntervalIsUp() {
        #expect(!QuickUnlock.isPasswordDue(typed: typed, now: typed, intervalDays: 14))
        #expect(!QuickUnlock.isPasswordDue(typed: typed, now: later(14 * day - 1), intervalDays: 14))
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: later(14 * day), intervalDays: 14))
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: later(2 * day), intervalDays: 1))
    }

    @Test func aRestartMakesThePasswordDue() {
        // A restart an hour later: a new boot time, and the uptime clock starts again.
        let restarted = Moment(bootTime: typed.bootTime + 3_600, uptime: 60)
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: restarted, intervalDays: 30))
        // Even once the new uptime has passed the old one, the boot time gives it away.
        let restartedLongAgo = Moment(bootTime: typed.bootTime + 3_600, uptime: typed.uptime + day)
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: restartedLongAgo, intervalDays: 30))
        // Should the boot time look the same, the uptime clock going back still does.
        let sameBootTime = Moment(bootTime: typed.bootTime, uptime: 10)
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: sameBootTime, intervalDays: 30))
    }

    @Test func theBootTimeMayDriftALittle() {
        var now = later(day)
        now.bootTime += Moment.bootTimeTolerance - 1
        #expect(!QuickUnlock.isPasswordDue(typed: typed, now: now, intervalDays: 30))
    }

    @Test func settingTheClockOnlyAsksSooner() {
        // Setting the date moves the kernel's boot time with it: that counts as a restart.
        var now = later(day)
        now.bootTime -= 30 * day
        #expect(QuickUnlock.isPasswordDue(typed: typed, now: now, intervalDays: 30))
    }

    @Test func neverTypedMeansDue() {
        #expect(QuickUnlock.isPasswordDue(typed: nil, now: typed, intervalDays: 30))
    }

    @Test func theDeviceClockReadsSensibly() {
        let now = Moment.now()
        #expect(now.bootTime > 1_000_000_000) // a date after 2001, read from the kernel
        #expect(now.uptime > 0)
        #expect(!QuickUnlock.isPasswordDue(typed: now, now: Moment.now(), intervalDays: 1))
    }

    private func later(_ seconds: Double) -> Moment {
        Moment(bootTime: typed.bootTime, uptime: typed.uptime + seconds)
    }
}
