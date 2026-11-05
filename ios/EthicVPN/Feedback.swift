import UIKit

/// Taps: a click on the button, a success buzz when the VPN connects, a softer one when it
/// disconnects, an error buzz when it fails.
@MainActor
enum Feedback {
    static func tap() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    static func connected() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func disconnected() {
        UIImpactFeedbackGenerator(style: .soft).impactOccurred()
    }

    static func failed() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
}
