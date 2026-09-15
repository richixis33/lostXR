import SwiftUI
import UIKit
import AVFoundation
import Vision

struct HandTrackingView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack(alignment: .topTrailing) {
            HandCameraView()
            Button { dismiss() } label: {
                Image(systemName: "xmark.circle.fill").font(.system(size: 34))
                    .symbolRenderingMode(.palette).foregroundStyle(.white, .black.opacity(0.6))
            }
            .padding(20)
        }
    }
}

private struct HandCameraView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> HandCameraController { HandCameraController() }
    func updateUIViewController(_ uiViewController: HandCameraController, context: Context) {}
}

private final class HandCameraController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let session = AVCaptureSession()
    private let preview = AVCaptureVideoPreviewLayer()
    private let dots = CAShapeLayer()
    private let queue = DispatchQueue(label: "PhoneXR.Vision", qos: .userInitiated)
    private var lastFrame = CFAbsoluteTimeGetCurrent()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        preview.session = session
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)
        dots.fillColor = UIColor.systemOrange.cgColor
        view.layer.addSublayer(dots)
        configureCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview.frame = view.bounds
        dots.frame = view.bounds
    }

    private func configureCamera() {
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else { return }
        session.beginConfiguration()
        session.sessionPreset = .vga640x480
        session.addInput(input)
        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(output) { session.addOutput(output) }
        session.commitConfiguration()
        queue.async { self.session.startRunning() }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CFAbsoluteTimeGetCurrent()
        guard now - lastFrame > 0.05 else { return }
        lastFrame = now
        let request = VNDetectHumanHandPoseRequest()
        request.maximumHandCount = 2
        let handler = VNImageRequestHandler(cmSampleBuffer: sampleBuffer, orientation: .right)
        guard (try? handler.perform([request])) != nil else { return }
        let points = request.results?.flatMap { observation -> [CGPoint] in
            guard let recognized = try? observation.recognizedPoints(.all) else { return [] }
            return recognized.values.filter { $0.confidence > 0.35 }.map { CGPoint(x: $0.location.x, y: 1 - $0.location.y) }
        } ?? []
        DispatchQueue.main.async { self.draw(points) }
    }

    private func draw(_ points: [CGPoint]) {
        let path = UIBezierPath()
        for point in points {
            let p = CGPoint(x: point.x * view.bounds.width, y: point.y * view.bounds.height)
            path.append(UIBezierPath(ovalIn: CGRect(x: p.x - 5, y: p.y - 5, width: 10, height: 10)))
        }
        dots.path = path.cgPath
    }

    deinit { session.stopRunning() }
}
