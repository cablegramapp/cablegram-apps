import AVFoundation
import SwiftUI
import VisionKit

/// Scans the pairing QR on a TV (Android `PairingScanner`): `DataScannerViewController` where the device supports it, with an
/// `AVCaptureMetadataOutput` fallback. Asks for camera access first.
struct PairingScanner: View {
    let onCode: (String) -> Void
    @State private var status = AVCaptureDevice.authorizationStatus(for: .video)
    @State private var consumed = false

    var body: some View {
        VStack(spacing: 12) {
            switch status {
            case .authorized:
                scanner
                if consumed { Button { consumed = false } label: { Text(key: "ui_scan_again_3d0458") }.buttonStyle(AccentTextButtonStyle()) }
            case .notDetermined, .denied, .restricted:
                Text(key: "ui_allow_camera_access_to_scan_the_tv_code_you_can_also_e_1e0033").foregroundStyle(Color.phoneMuted)
                Button {
                    if status == .denied || status == .restricted {
                        // iOS asks only once; afterwards the person has to allow the camera in Settings.
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    } else {
                        Task { _ = await AVCaptureDevice.requestAccess(for: .video); status = AVCaptureDevice.authorizationStatus(for: .video) }
                    }
                } label: { Text(key: "ui_allow_camera_e18835") }.buttonStyle(PrimaryButtonStyle(minHeight: 48))
            @unknown default: EmptyView()
            }
        }
    }

    @ViewBuilder private var scanner: some View {
        let handler: (String) -> Void = { code in
            guard !consumed else { return }
            consumed = true
            onCode(code)
        }
        ZStack(alignment: .bottom) {
            if DataScannerViewController.isSupported && DataScannerViewController.isAvailable {
                DataScannerView(onCode: handler)
            } else {
                CaptureScannerView(onCode: handler)
            }
            Text(key: "ui_point_the_camera_at_the_qr_on_the_tv_f4e744").font(.system(size: 13)).foregroundStyle(.white)
                .padding(8).background(.black.opacity(0.5), in: Capsule()).padding(.bottom, 8)
        }
        .frame(height: 240).clipShape(RoundedRectangle(cornerRadius: 20))
    }
}

private struct DataScannerView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, recognizesMultipleItems: false,
                                                isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true, isGuidanceEnabled: false, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        try? scanner.startScanning()
        return scanner
    }

    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}
    func makeCoordinator() -> Coordinator { Coordinator(onCode) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (String) -> Void
        init(_ onCode: @escaping (String) -> Void) { self.onCode = onCode }
        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for case .barcode(let barcode) in addedItems { if let text = barcode.payloadStringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty { onCode(text); return } }
        }
    }
}

private struct CaptureScannerView: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        let session = AVCaptureSession()
        if let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) {
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            if session.canAddOutput(output) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(context.coordinator, queue: .main)
                output.metadataObjectTypes = [.qr]
            }
            DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        }
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        context.coordinator.session = session
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}
    static func dismantleUIView(_ view: PreviewView, coordinator: Coordinator) { coordinator.session?.stopRunning() }
    func makeCoordinator() -> Coordinator { Coordinator(onCode) }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let onCode: (String) -> Void
        var session: AVCaptureSession?
        init(_ onCode: @escaping (String) -> Void) { self.onCode = onCode }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            if let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty { onCode(text) }
        }
    }
}
