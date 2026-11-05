import SwiftUI

/// Liquid Glass on iOS 26, frosted material before it. Built with an older Xcode the glass
/// calls are compiled out and every iOS gets the material.
extension View {
    @ViewBuilder
    func liquidGlass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(makeGlass(tint: tint, interactive: interactive), in: shape)
        } else {
            self.background(.ultraThinMaterial, in: shape)
        }
        #else
        self.background(.ultraThinMaterial, in: shape)
        #endif
    }

    /// A prominent glass button tinted with `color` on iOS 26, a filled capsule before it.
    @ViewBuilder
    func glassProminentButton(_ color: Color) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.buttonStyle(.glassProminent).tint(color)
        } else {
            self.buttonStyle(FilledCapsuleStyle(color: color))
        }
        #else
        self.buttonStyle(FilledCapsuleStyle(color: color))
        #endif
    }
}

#if compiler(>=6.2)
@available(iOS 26.0, *)
private func makeGlass(tint: Color?, interactive: Bool) -> Glass {
    var glass = Glass.regular
    if let tint { glass = glass.tint(tint) }
    if interactive { glass = glass.interactive() }
    return glass
}
#endif

extension View {
    /// The SF Symbol pulses while `active` (iOS 17+); earlier systems show it still.
    @ViewBuilder
    func symbolPulse(_ active: Bool) -> some View {
        if #available(iOS 17.0, *) {
            self.symbolEffect(.pulse, isActive: active)
        } else {
            self
        }
    }
}

private struct FilledCapsuleStyle: ButtonStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(color.gradient, in: Capsule())
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}
