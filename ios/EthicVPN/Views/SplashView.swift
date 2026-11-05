import SwiftUI

/// Launch animation: the logo comes out of the dark, a light sweeps across it, the name rises
/// under it, then the whole screen opens up onto the map. Calls `onFinish` when it is gone.
struct SplashView: View {
    var onFinish: () -> Void

    @State private var logoIn = false
    @State private var shine = false
    @State private var titleIn = false
    @State private var glow = false
    @State private var leaving = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // A soft halo that breathes once behind the logo.
            Circle()
                .fill(RadialGradient(colors: [.white.opacity(0.18), .clear], center: .center, startRadius: 10, endRadius: 220))
                .frame(width: 440, height: 440)
                .scaleEffect(glow ? 1.1 : 0.6)
                .opacity(glow ? 1 : 0)

            VStack(spacing: 22) {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 280)
                    .overlay(shineBand.mask(Image("Logo").resizable().scaledToFit()))
                    .scaleEffect(logoIn ? 1 : 0.72)
                    .opacity(logoIn ? 1 : 0)
                    .blur(radius: logoIn ? 0 : 14)

                Text("EthicVPN")
                    .font(.system(size: 30, weight: .bold, design: .rounded))
                    .tracking(titleIn ? 1.5 : 8)
                    .foregroundStyle(.white)
                    .opacity(titleIn ? 1 : 0)
                    .offset(y: titleIn ? 0 : 14)
            }
        }
        .scaleEffect(leaving ? 1.25 : 1)
        .opacity(leaving ? 0 : 1)
        .task { await play() }
    }

    /// A diagonal band of light that crosses the logo once.
    private var shineBand: some View {
        GeometryReader { geo in
            LinearGradient(colors: [.clear, .white.opacity(0.85), .clear], startPoint: .leading, endPoint: .trailing)
                .frame(width: geo.size.width * 0.35)
                .rotationEffect(.degrees(20))
                .offset(x: shine ? geo.size.width * 1.2 : -geo.size.width * 0.5)
        }
        .blendMode(.plusLighter)
    }

    private func play() async {
        withAnimation(.spring(response: 0.9, dampingFraction: 0.75)) { logoIn = true }
        withAnimation(.easeOut(duration: 1.2)) { glow = true }
        try? await Task.sleep(nanoseconds: 450_000_000)
        withAnimation(.easeInOut(duration: 0.9)) { shine = true }
        withAnimation(.spring(response: 0.8, dampingFraction: 0.8).delay(0.15)) { titleIn = true }
        try? await Task.sleep(nanoseconds: 1_250_000_000)
        withAnimation(.easeIn(duration: 0.45)) { leaving = true }
        try? await Task.sleep(nanoseconds: 450_000_000)
        onFinish()
    }
}
