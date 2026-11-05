import AVFoundation
import SwiftUI

/// Accepts a vless:// or trojan:// key, several keys, or a subscription URL.
struct AddView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var scanning = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                Text("Вставьте ключ vless:// или trojan://, несколько ключей или ссылку на подписку. Можно отсканировать QR-код.")
                    .font(.footnote)
                    .foregroundStyle(Brand.muted)

                TextEditor(text: $text)
                    .font(.system(.footnote, design: .monospaced))
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .frame(minHeight: 120, maxHeight: 180)
                    .background(Brand.surfaceHigh, in: RoundedRectangle(cornerRadius: 12))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                HStack(spacing: 10) {
                    Button {
                        if let s = UIPasteboard.general.string { text = s }
                    } label: {
                        Label("Вставить", systemImage: "doc.on.clipboard").frame(maxWidth: .infinity)
                    }
                    Button {
                        scanning = true
                    } label: {
                        Label("QR-код", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                    }
                }
                .buttonStyle(.bordered)
                .tint(Brand.cyan)

                Button {
                    let input = text
                    dismiss()
                    Task { await model.addInput(input) }
                } label: {
                    Text("Сохранить")
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .background(Brand.cyan, in: RoundedRectangle(cornerRadius: 14))
                        .foregroundStyle(Brand.background)
                }
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                Spacer()
            }
            .padding(20)
            .background(Brand.surface.ignoresSafeArea())
            .navigationTitle("Добавить ключ")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Отмена") { dismiss() } }
            }
            .fullScreenCover(isPresented: $scanning) {
                QRScannerView { code in
                    scanning = false
                    text = code
                }
                .ignoresSafeArea()
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Camera preview that reports the first QR code it sees.
struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var reported = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input)
            else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]

            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.addSublayer(preview)

            let close = UIButton(type: .close, primaryAction: UIAction { [weak self] _ in
                self?.dismiss(animated: true)
            })
            close.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(close)
            NSLayoutConstraint.activate([
                close.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
                close.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            ])
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            view.layer.sublayers?.compactMap { $0 as? AVCaptureVideoPreviewLayer }.forEach { $0.frame = view.bounds }
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            session.stopRunning()
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !reported,
                  let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue
            else { return }
            reported = true
            onCode?(code)
        }
    }
}
