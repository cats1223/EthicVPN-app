import Foundation
import UserNotifications

/// Local reminders that the subscription is running out: three days before and the day before.
enum ExpiryReminders {
    private static let ids = ["expire-3d", "expire-1d"]

    /// `expire` is a Unix time; zero means the subscription does not expire.
    static func schedule(expire: Int64) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        guard expire > 0 else { return }
        let end = Date(timeIntervalSince1970: TimeInterval(expire))
        let reminders: [(String, TimeInterval, String)] = [
            (ids[0], 3 * 86_400, String(localized: "Подписка закончится через 3 дня")),
            (ids[1], 86_400, String(localized: "Подписка закончится завтра")),
        ]
        let due = reminders.filter { end.addingTimeInterval(-$0.1) > Date() }
        guard !due.isEmpty else { return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            for (id, before, title) in due {
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = String(localized: "Попросите продлить её, чтобы VPN не отключился.")
                content.sound = .default
                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: end.addingTimeInterval(-before).timeIntervalSinceNow, repeats: false)
                center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
            }
        }
    }
}
