import SwiftUI

/// Three slides on the first launch: what the app does, how to add a subscription, and that
/// Russian sites go past the VPN.
struct OnboardingView: View {
    var onFinish: () -> Void
    @State private var page = 0

    private struct Slide {
        let icon: String
        let color: Color
        let title: LocalizedStringKey
        let text: LocalizedStringKey
    }

    private let slides = [
        Slide(icon: "checkmark.shield.fill", color: Brand.cyan, title: "Добро пожаловать в TechTeamVPN",
              text: "Быстрый и защищённый доступ к интернету. Подключение — одним касанием."),
        Slide(icon: "link.badge.plus", color: Brand.green, title: "Добавьте подписку",
              text: "Скопируйте ссылку подписки, которую вам прислали, и откройте приложение — оно само предложит её добавить. Можно и вставить ключ или отсканировать QR-код."),
        Slide(icon: "arrow.triangle.branch", color: .orange, title: "Российские сайты — напрямую",
              text: "Банки, Госуслуги и другие российские сайты открываются без VPN, с вашего обычного адреса. Это и другое — в настройках."),
    ]

    var body: some View {
        VStack(spacing: 0) {
            TabView(selection: $page) {
                ForEach(slides.indices, id: \.self) { i in
                    VStack(spacing: 24) {
                        Spacer()
                        Image(systemName: slides[i].icon)
                            .font(.system(size: 72, weight: .semibold))
                            .foregroundStyle(slides[i].color)
                            .frame(width: 150, height: 150)
                            .background(slides[i].color.opacity(0.15), in: Circle())
                        Text(slides[i].title)
                            .font(.title.bold())
                            .multilineTextAlignment(.center)
                        Text(slides[i].text)
                            .font(.body)
                            .foregroundStyle(Brand.muted)
                            .multilineTextAlignment(.center)
                        Spacer()
                    }
                    .padding(.horizontal, 32)
                    .tag(i)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .always))
            .indexViewStyle(.page(backgroundDisplayMode: .always))

            Button {
                if page < slides.count - 1 {
                    withAnimation { page += 1 }
                } else {
                    onFinish()
                }
            } label: {
                Text(page < slides.count - 1 ? "Далее" : "Начать")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                    .background(Brand.cyan, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .foregroundStyle(.black)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .background(Brand.background.ignoresSafeArea())
    }
}
