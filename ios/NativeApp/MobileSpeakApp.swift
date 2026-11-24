import SwiftUI
import AVFoundation
import UIKit
import PhotosUI
import ImageIO
import UniformTypeIdentifiers

enum Palette {
    static let background = Color(hex: 0x2B2D31)
    static let bottom = Color(hex: 0x292B2F)
    static let card = Color(hex: 0x36393F)
    static let selected = Color(hex: 0x3A3D42)
    static let border = Color(hex: 0x3A3E46)
    static let muted = Color(hex: 0x949BA4)
    static let text = Color(hex: 0xF2F3F5)
    static let accent = Color(hex: 0x5865F2)
    static let green = Color(hex: 0x23A559)
    static let disconnect = Color(hex: 0xC83F4A)
    static var menuDestructive: UIColor {
        // ponytail: measured for the dark iOS 26 Menu; recheck if its native palette changes.
        // Its destructive text is lighter than systemRed.
        if #available(iOS 26.0, *) { return UIColor(red: 1, green: 85 / 255, blue: 90 / 255, alpha: 1) }
        return .systemRed
    }
}

// PDF templates inherit the surrounding state color. Explicit sizes stay fixed;
// otherwise follow the body text size, as the former unconfigured symbols did.
struct AppIcon: View {
    let name: String
    var size: CGFloat? = nil
    var originalTint: UIColor?
    @ScaledMetric private var bodySize: CGFloat

    init(name: String, size: CGFloat? = nil, scaledSize: CGFloat = 17, relativeTo: Font.TextStyle = .body, originalTint: UIColor? = nil) {
        self.name = name
        self.size = size
        self.originalTint = originalTint
        _bodySize = ScaledMetric(wrappedValue: scaledSize, relativeTo: relativeTo)
    }

    private var image: Image {
        if let originalTint, let source = UIImage(named: "Icon-" + name) {
            return Image(uiImage: source.withTintColor(originalTint, renderingMode: .alwaysOriginal))
        }
        return Image(decorative: "Icon-" + name).renderingMode(.template)
    }

    var body: some View {
        image.resizable().scaledToFit()
            .frame(width: size ?? bodySize, height: size ?? bodySize)
    }
}

extension NetworkGrade {
    var packetLossColor: Color {
        switch self {
        case .good: Color(hex: 0x3DBE78)
        case .fair: Color(hex: 0xFF8000)
        case .poor: Color(hex: 0x581C87)
        }
    }
    var color: Color {
        switch self {
        case .good: Color(hex: 0x3DBE78)
        case .fair: Color(hex: 0xE9B44C)
        case .poor: Color(hex: 0xFF375F)
        }
    }
}

private struct NetworkPanelHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

// Shared core owns all sampling, grades and scale; this view only draws its snapshot.
struct NetworkQualityPanel: View {
    var quality: NetworkQuality = NetworkQuality()
    // Include language in the view's inputs even when the network snapshot stays unchanged.
    var language: AppLanguage = AppLanguage.resolve(L10n.language)
    @ScaledMetric(relativeTo: .body) private var valueSize: CGFloat = 16
    @ScaledMetric(relativeTo: .caption) private var labelSize: CGFloat = 12
    @State private var width: CGFloat = 0

    private let numbersWidth: CGFloat = 123
    private var stacked: Bool { width < numbersWidth + 116 || valueSize > 16 }
    private var numbers: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.string("network_latency")).font(.system(size: labelSize))
            (Text(quality.latencyText).foregroundColor(quality.rttGrade?.color ?? Palette.muted)
                + Text(" " + L10n.millisecondsUnit + " ± ").foregroundColor(Palette.muted)
                + Text(quality.deviationText).foregroundColor(quality.deviationGrade?.color ?? Palette.muted))
                .font(.system(size: valueSize, weight: .semibold)).monospacedDigit().fixedSize(horizontal: false, vertical: true)
            (Text(L10n.string("network_packet_loss") + " " + L10n.percentPrefix).foregroundColor(Palette.muted)
                + Text(quality.lossText).foregroundColor(quality.packetLossGrade?.packetLossColor ?? Palette.muted)
                + Text(L10n.percentSuffix).foregroundColor(Palette.muted))
                .font(.system(size: labelSize)).monospacedDigit().fixedSize(horizontal: false, vertical: true)
        }.foregroundStyle(Palette.muted)
            .accessibilityElement(children: .ignore).accessibilityLabel(quality.accessibilitySummary)
    }
    var chart: some View {
        Canvas { context, size in
            let font = Font.system(size: min(labelSize, 16)).monospacedDigit()
            let upper = context.resolve(Text(quality.axisText + " " + L10n.millisecondsUnit).font(font).foregroundColor(Palette.muted))
            let middle = context.resolve(Text(quality.midAxisText + " " + L10n.millisecondsUnit).font(font).foregroundColor(Palette.muted))
            let upperSize = upper.measure(in: size)
            let middleSize = middle.measure(in: size)
            let labelWidth = max(upperSize.width, middleSize.width)
            let left = labelWidth + 8
            let plotWidth = max(0, size.width - left)
            let top = upperSize.height / 2
            let baseline = max(top, size.height - 1)
            let height = baseline - top
            for fraction in [CGFloat(0), 0.5, 1] {
                let y = top + height * fraction
                var line = Path(); line.move(to: CGPoint(x: left, y: y)); line.addLine(to: CGPoint(x: size.width, y: y))
                context.stroke(line, with: .color(Palette.muted.opacity(0.25)), lineWidth: 0.5)
            }
            context.draw(upper, at: CGPoint(x: labelWidth, y: top), anchor: .trailing)
            context.draw(middle, at: CGPoint(x: labelWidth, y: top + height / 2), anchor: .trailing)
            let step = plotWidth / 30
            let gap = min(2, step * 0.25)
            for sample in quality.samples where sample.second <= quality.nowSecond && quality.nowSecond - sample.second < 30 {
                let index = 29 - CGFloat(quality.nowSecond - sample.second)
                let barHeight = max(1, height * CGFloat(sample.rttMs / quality.axisMaxMs))
                let rect = CGRect(x: left + index * step + gap / 2, y: baseline - barHeight, width: max(0, step - gap), height: barHeight)
                // Round only the top corners; keep the baseline flat.
                let radius = min(1.5, rect.width / 2, barHeight / 2)
                let path = Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight], cornerRadii: CGSize(width: radius, height: radius)).cgPath)
                let shading: GraphicsContext.Shading
                if let p = sample.packetLossFraction, let lossGrade = sample.packetLossGrade, p > 0 {
                    if p == 1 {
                        shading = .color(lossGrade.packetLossColor)
                    } else {
                        let t = 1 - p
                        shading = .linearGradient(Gradient(stops: [
                            .init(color: sample.grade.color, location: 0),
                            .init(color: sample.grade.color, location: t),
                            .init(color: lossGrade.packetLossColor, location: t),
                            .init(color: lossGrade.packetLossColor, location: 1)
                        ]), startPoint: CGPoint(x: rect.midX, y: rect.minY), endPoint: CGPoint(x: rect.midX, y: rect.maxY))
                    }
                } else {
                    shading = .color(sample.grade.color)
                }
                context.fill(path, with: shading)
            }
        }.accessibilityElement(children: .ignore)
            .accessibilityLabel(L10n.string("network_chart"))
            .accessibilityValue(quality.chartAccessibilitySummary)
    }
    var body: some View {
        VStack(spacing: 12) {
            if stacked {
                numbers.frame(maxWidth: .infinity, alignment: .leading)
                chart.frame(height: 64)
            } else {
                HStack(alignment: .top, spacing: 0) {
                    numbers.frame(width: numbersWidth, alignment: .leading)
                    chart.frame(maxWidth: .infinity)
                }
                .fixedSize(horizontal: false, vertical: true)
            }
        }.padding(.horizontal, 12).padding(.vertical, 12)
            .environment(\.locale, language.locale)
            .background(GeometryReader { proxy in
                Color.clear.onAppear { width = proxy.size.width - 24 }.onChange(of: proxy.size.width) { width = $0 - 24 }
                    .preference(key: NetworkPanelHeightKey.self,
                        value: width > 0 && abs(width - (proxy.size.width - 24)) < 0.5 ? proxy.size.height : 0)
            })
            .accessibilityIdentifier("network-quality")
    }
}

struct AvatarSelectionRequest: Identifiable {
    let id: Int
}

struct AvatarPicker: UIViewControllerRepresentable {
    let selection: Int
    let onDismiss: () -> Void
    let onImage: (UIImage) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(photoLibrary: .shared())
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {
        context.coordinator.parent = self
    }
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        var parent: AvatarPicker
        private var request = 0
        init(parent: AvatarPicker) { self.parent = parent }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            load(results.first?.itemProvider)
        }
        func load(_ provider: NSItemProvider?) {
            request += 1
            let request = request
            let selection = parent.selection
            guard let provider else {
                Client.shared.cancelAvatarSelection(parent.selection)
                parent.onDismiss()
                return
            }
            guard let type = provider.registeredTypeIdentifiers.first(where: { UTType($0)?.conforms(to: .image) == true }) else {
                Client.shared.avatarImageFailed(selection: parent.selection)
                parent.onDismiss()
                return
            }
            // Decode while the provider's temporary URL is valid; ImageIO bounds the allocation.
            provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                let image = url.flatMap { try? AvatarImages.load($0) }
                Task { @MainActor in
                    guard request == self.request, selection == self.parent.selection,
                          Client.shared.isCurrentAvatarSelection(selection) else { return }
                    if let image { self.parent.onImage(image) }
                    else {
                        Client.shared.avatarImageFailed(selection: selection)
                        self.parent.onDismiss()
                    }
                }
            }
        }
    }
}

struct AvatarCrop: Equatable {
    var x: CGFloat = 0.5
    var y: CGFloat = 0.5
    var zoom: CGFloat = 1

    func rect(in size: CGSize) -> CGRect {
        let side = max(1, floor(min(size.width, size.height) / min(4, max(1, zoom))))
        return CGRect(x: floor(min(size.width - side, max(0, x * size.width - side / 2))),
                      y: floor(min(size.height - side, max(0, y * size.height - side / 2))), width: side, height: side)
    }
    mutating func move(dx: CGFloat, dy: CGFloat, in size: CGSize) {
        let side = rect(in: size).width
        x = min(1 - side / (2 * size.width), max(side / (2 * size.width), x + dx * side / size.width))
        y = min(1 - side / (2 * size.height), max(side / (2 * size.height), y + dy * side / size.height))
    }
}

enum AvatarImages {
    static func load(_ url: URL) throws -> UIImage {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 2048,
                kCGImageSourceShouldCacheImmediately: true,
              ] as CFDictionary) else { throw NSError(domain: "AvatarImages", code: 1) }
        return UIImage(cgImage: thumbnail)
    }
    static func prepare(_ url: URL) throws -> (Data, [Data]) { try prepare(load(url), crop: AvatarCrop()) }
    static func prepare(_ image: UIImage, crop: AvatarCrop) throws -> (Data, [Data]) {
        guard image.imageOrientation == .up, let source = image.cgImage,
              let square = source.cropping(to: crop.rect(in: CGSize(width: source.width, height: source.height))) else {
            throw NSError(domain: "AvatarImages", code: 1)
        }
        let image = UIImage(cgImage: square)
        let preview = jpeg(image, maxSide: 640, quality: 0.9)
        let uploads = [(CGFloat(2048), CGFloat(0.9)), (768, 0.8), (256, 0.65)]
            .map { jpeg(image, maxSide: $0.0, quality: $0.1) }
        guard !preview.isEmpty, uploads.allSatisfy({ !$0.isEmpty && $0.count <= 8 * 1024 * 1024 }) else {
            throw NSError(domain: "AvatarImages", code: 2)
        }
        return (preview, uploads)
    }
    private static func jpeg(_ image: UIImage, maxSide: CGFloat, quality: CGFloat) -> Data {
        let side = max(1, floor(min(maxSide, image.size.width)))
        let size = CGSize(width: side, height: side)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).jpegData(withCompressionQuality: quality) { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            image.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}

private struct AvatarSelectionPage: View {
    let selection: Int
    let onDismiss: () -> Void
    @State private var image: UIImage?
    var body: some View {
        if let image {
            AvatarCropPage(image: image, selection: selection, onDismiss: onDismiss)
                .id(ObjectIdentifier(image))
        } else {
            AvatarPicker(selection: selection, onDismiss: onDismiss, onImage: { image = $0 })
        }
    }
}

// UIScrollView supplies constrained native pan/pinch. Only its square viewport is exported.
struct AvatarCropCanvas: UIViewRepresentable {
    let image: UIImage
    @Binding var crop: AvatarCrop
    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIView(context: Context) -> CropScroll {
        let view = CropScroll()
        view.backgroundColor = .white // Match the existing opaque JPEG compositing for transparent sources.
        view.photo.image = image
        view.photo.frame = CGRect(origin: .zero, size: image.size)
        view.addSubview(view.photo)
        view.delegate = context.coordinator
        view.showsHorizontalScrollIndicator = false
        view.showsVerticalScrollIndicator = false
        view.bounces = false
        view.bouncesZoom = false
        view.contentInsetAdjustmentBehavior = .never
        view.onLayout = { [weak view, weak coordinator = context.coordinator] in
            if let view, let coordinator { coordinator.apply(to: view) }
        }
        return view
    }
    func updateUIView(_ view: CropScroll, context: Context) {
        context.coordinator.parent = self
        if context.coordinator.reported != crop { context.coordinator.apply(to: view) }
    }
    final class CropScroll: UIScrollView {
        let photo = UIImageView()
        var onLayout: (() -> Void)?
        private var previousSize = CGSize.zero
        override func layoutSubviews() {
            super.layoutSubviews()
            if bounds.size != previousSize { previousSize = bounds.size; onLayout?() }
        }
    }
    final class Coordinator: NSObject, UIScrollViewDelegate {
        var parent: AvatarCropCanvas
        var applying = false
        var reported: AvatarCrop?
        init(parent: AvatarCropCanvas) { self.parent = parent }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { (scrollView as? CropScroll)?.photo }
        func apply(to view: CropScroll) {
            guard view.bounds.width > 0, !applying else { return }
            applying = true
            reported = parent.crop
            defer { applying = false }
            let minimum = view.bounds.width / min(parent.image.size.width, parent.image.size.height)
            let rect = parent.crop.rect(in: parent.image.size)
            view.minimumZoomScale = minimum
            view.maximumZoomScale = minimum * 4
            view.setZoomScale(view.bounds.width / rect.width, animated: false)
            view.contentSize = CGSize(width: parent.image.size.width * view.zoomScale, height: parent.image.size.height * view.zoomScale)
            view.setContentOffset(CGPoint(x: rect.minX * view.zoomScale, y: rect.minY * view.zoomScale), animated: false)
        }
        func scrollViewDidScroll(_ scrollView: UIScrollView) { changed(scrollView) }
        func scrollViewDidZoom(_ scrollView: UIScrollView) { changed(scrollView) }
        func changed(_ view: UIScrollView) {
            guard !applying, view.bounds.width > 0, view.zoomScale > 0 else { return }
            let size = parent.image.size
            var value = AvatarCrop(x: (view.contentOffset.x + view.bounds.width / 2) / view.zoomScale / size.width,
                y: (view.contentOffset.y + view.bounds.height / 2) / view.zoomScale / size.height,
                zoom: view.zoomScale / (view.bounds.width / min(size.width, size.height)))
            value.move(dx: 0, dy: 0, in: size)
            reported = value
            if value != parent.crop { parent.crop = value }
        }
    }
}

struct AvatarCropPage: View {
    let image: UIImage
    let selection: Int
    let onDismiss: () -> Void
    @State private var crop = AvatarCrop()
    @State private var saving = false
    @State private var failed = false
    var body: some View {
        NavigationView {
            GeometryReader { geometry in
                let side = max(100, min(420, min(geometry.size.width - 32, geometry.size.height * 0.55)))
                ScrollView {
                    VStack(spacing: 20) {
                        Text(L10n.string("avatar_crop_hint")).font(.footnote).foregroundStyle(Palette.muted)
                            .fixedSize(horizontal: false, vertical: true)
                        AvatarCropCanvas(image: image, crop: $crop)
                            .allowsHitTesting(!saving)
                            .frame(width: side, height: side).clipped()
                            .overlay(Rectangle().fill(.black.opacity(0.35)).mask(Rectangle().overlay(Circle().blendMode(.destinationOut)).compositingGroup()).allowsHitTesting(false))
                            .overlay(Rectangle().stroke(.white.opacity(0.8), lineWidth: 1).allowsHitTesting(false))
                            .overlay(Circle().stroke(.white, lineWidth: 2).allowsHitTesting(false))
                            .accessibilityElement(children: .ignore)
                            .accessibilityLabel(L10n.string("avatar_crop_area"))
                            .accessibilityHint(L10n.string("avatar_crop_hint"))
                            .accessibilityAction(named: Text(L10n.string("avatar_crop_left"))) { if !saving { crop.move(dx: -0.1, dy: 0, in: image.size) } }
                            .accessibilityAction(named: Text(L10n.string("avatar_crop_up"))) { if !saving { crop.move(dx: 0, dy: -0.1, in: image.size) } }
                            .accessibilityAction(named: Text(L10n.string("avatar_crop_down"))) { if !saving { crop.move(dx: 0, dy: 0.1, in: image.size) } }
                            .accessibilityAction(named: Text(L10n.string("avatar_crop_right"))) { if !saving { crop.move(dx: 0.1, dy: 0, in: image.size) } }
                        Text(L10n.string("avatar_crop_zoom")).frame(maxWidth: .infinity, alignment: .leading)
                        Slider(value: Binding(get: { crop.zoom }, set: { crop.zoom = $0; crop.move(dx: 0, dy: 0, in: image.size) }), in: 1...4).accessibilityLabel(L10n.string("avatar_crop_zoom"))
                        if failed { Text(L10n.string("avatar_crop_error")).foregroundStyle(Palette.disconnect) }
                        Button {
                            guard !saving else { return }
                            saving = true
                            let value = crop
                            Task {
                                let result = await Task.detached(priority: .userInitiated) { try? AvatarImages.prepare(image, crop: value) }.value
                                if let result, await Client.shared.saveAvatar(preview: result.0, uploads: result.1, selection: selection) { onDismiss() }
                                else { failed = true; saving = false }
                            }
                        } label: {
                            HStack { if saving { ProgressView() }; Text(L10n.string(saving ? "avatar_crop_saving" : "avatar_crop_use")) }
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }.buttonStyle(.borderedProminent).tint(Palette.accent)
                    }.padding(16).disabled(saving)
                }.background(Palette.background)
            }
            .navigationTitle(L10n.string("avatar_crop_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.string("action_cancel"), action: onDismiss).disabled(saving) } }
        }.navigationViewStyle(.stack).interactiveDismissDisabled(saving)
    }
}
extension Color {
    init(hex: UInt32) { self.init(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255) }
}
private struct ChannelMemberListHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 52
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
private struct ChannelMemberListPanObserver: UIViewRepresentable {
    @Binding var isPanning: Bool

    func makeUIView(context: Context) -> Observer { Observer() }
    func updateUIView(_ view: Observer, context: Context) {
        view.onPanning = { isPanning = $0 }
    }

    final class Observer: UIView {
        var onPanning: (Bool) -> Void = { _ in }
        private weak var pan: UIPanGestureRecognizer?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            pan?.removeTarget(self, action: #selector(panChanged))
            pan = nil
            guard window != nil else { return }
            var ancestor = superview
            while let view = ancestor {
                if let scroll = view as? UIScrollView {
                    pan = scroll.panGestureRecognizer
                    pan?.addTarget(self, action: #selector(panChanged))
                    return
                }
                ancestor = view.superview
            }
        }

        @objc private func panChanged(_ gesture: UIPanGestureRecognizer) {
            switch gesture.state {
            case .began, .changed:
                onPanning(true)
            case .ended, .cancelled, .failed:
                // SwiftUI can invoke the row button during the same release that ends the scroll.
                DispatchQueue.main.async { [weak self] in
                    guard let self, let pan = self.pan, pan.state != .began, pan.state != .changed else { return }
                    self.onPanning(false)
                }
            default:
                break
            }
        }
    }
}

@MainActor private final class ChannelPrivateChatTransition {
    weak var source: UIView?
    private var snapshot: UIView?

    func capture() {
        guard snapshot == nil, let window = source?.window,
              let snapshot = window.snapshotView(afterScreenUpdates: false) else { return }
        snapshot.frame = window.bounds
        snapshot.isUserInteractionEnabled = false
        snapshot.accessibilityElementsHidden = true
        window.addSubview(snapshot)
        self.snapshot = snapshot
    }

    func moveToSource() {
        guard let snapshot, let source, let window = source.window else { clear(); return }
        // The native push now carries the frozen sheet and channel list away with Home.
        snapshot.frame = source.convert(window.bounds, from: window)
        source.addSubview(snapshot)
    }

    func clear() {
        snapshot?.removeFromSuperview()
        snapshot = nil
    }
}

private struct ChannelPrivateChatSource: UIViewRepresentable {
    let transition: ChannelPrivateChatTransition

    func makeCoordinator() -> ChannelPrivateChatTransition { transition }
    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        transition.source = view
        return view
    }
    func updateUIView(_ view: UIView, context: Context) {}
    static func dismantleUIView(_ view: UIView, coordinator: ChannelPrivateChatTransition) {
        if coordinator.source === view {
            coordinator.clear()
            coordinator.source = nil
        }
    }
}

private struct ChannelPrivateChatArrival: UIViewControllerRepresentable {
    let transition: ChannelPrivateChatTransition

    func makeUIViewController(context: Context) -> Controller { Controller(transition: transition) }
    func updateUIViewController(_ controller: Controller, context: Context) {}

    final class Controller: UIViewController {
        let transition: ChannelPrivateChatTransition

        init(transition: ChannelPrivateChatTransition) {
            self.transition = transition
            super.init(nibName: nil, bundle: nil)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            transition.clear()
        }
    }
}

private struct PageTitle: View {
    static let inset: CGFloat = 14
    static let bottomSpacing: CGFloat = 8
    @EnvironmentObject private var language: LanguageSettings
    let key: String
    var body: some View {
        Text(L10n.string(key))
            .environment(\.locale, language.locale)
            .font(.system(.title3).weight(.heavy))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, Self.bottomSpacing)
    }
}
private extension View {
    @ViewBuilder func adaptiveSheetHeight(_ height: CGFloat) -> some View {
        if #available(iOS 16.0, *) { presentationDetents([.height(height)]) }
        else { self }
    }

    @ViewBuilder func channelSheetBackground() -> some View {
        if #available(iOS 16.4, *) {
            // Keep the native glass appearance while filling the entire sheet.
            presentationBackground { Palette.background }
        } else {
            background(Palette.background.ignoresSafeArea())
        }
    }
}
@main struct MobileSpeakApp: App {
    @StateObject private var client = Client.shared
    @StateObject private var language = LanguageSettings()
    @Environment(\.scenePhase) private var phase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some Scene {
        WindowGroup {
            NavigationView { HomeView(client: client) }
                .navigationViewStyle(.stack)
                .transaction { if reduceMotion { $0.disablesAnimations = true } }
                .environmentObject(language)
                .environment(\.locale, language.locale)
                .onChange(of: language.selection) { _ in client.refreshLocalizedNotices() }
                .onChange(of: language.systemLanguage) { _ in
                    if language.selection == .system { client.refreshLocalizedNotices() }
                }
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
                .onAppear { client.setAppActive(phase == .active) }
                .onChange(of: phase) { phase in
                    client.setAppActive(phase == .active)
                    if phase == .active {
                        language.refreshSystemLanguage()
                        client.audioDidBecomeActive()
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: .AVAudioEngineConfigurationChange).receive(on: RunLoop.main)) { note in
                    if let source = note.object as? AVAudioEngine {
                        Task { await client.audioConfigurationChanged(source) }
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification).receive(on: RunLoop.main)) { note in
                    guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                          let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                    switch type {
                    case .began: client.audioInterruptionBegan()
                    case .ended:
                        let shouldResume = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt)
                            .map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? true
                        client.audioInterruptionEnded(shouldResume: shouldResume)
                    @unknown default: break
                    }
                }
                .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification).receive(on: RunLoop.main)) { note in
                    if let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                       let reason = AVAudioSession.RouteChangeReason(rawValue: raw),
                       reason == .oldDeviceUnavailable || reason == .newDeviceAvailable {
                        client.audioRouteChanged()
                    }
                }
        }
    }
}
struct Avatar: View {
    let name: String
    var path: String? = nil
    var speaking = false
    var size: CGFloat = 34
    var member: Member? = nil
    private var markSize: CGFloat { size <= 24 ? 14 : size <= 30 ? 18 : 20 }
    var body: some View {
        ZStack {
            ZStack {
                Palette.accent
                if member?.statusIcon == nil {
                    Text(String(name.prefix(2)).uppercased()).font(.system(size: size * 0.36, weight: .bold))
                }
                if let path { LocalImage(path: path).frame(width: size, height: size) }
            }
            .saturation(member?.away == true ? 0 : 1)
            .overlay { if member?.away == true { Color.black.opacity(0.75) } }
            .frame(width: size, height: size).clipShape(Circle())
            if let icon = member?.statusIcon {
                AppIcon(name: icon, size: markSize)
                    .foregroundStyle(icon == "afk" ? Palette.disconnect : Color(hex: 0xF2F3F5))
            }
        }
            .frame(width: size, height: size).clipShape(Circle())
            .overlay(Circle().strokeBorder(speaking ? Palette.green : .clear, lineWidth: 3))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(member?.accessibilityLabel ?? name)
    }
}
struct ChannelMemberPreview: View {
    let members: [Member]
    let summary: String
    @Environment(\.legibilityWeight) private var legibilityWeight

    static func avatarCount(width: CGFloat, textWidth: CGFloat, memberCount: Int) -> Int {
        Int(min(CGFloat(max(0, memberCount)), floor(max(0, width - textWidth) / 29)))
    }

    static func nicknameWidth(width: CGFloat, textWidth: CGFloat, memberCount: Int,
                              nameWidth: CGFloat, minimumNameWidth: CGFloat) -> CGFloat {
        guard memberCount == 1, nameWidth > 0 else { return 0 }
        let available = width - textWidth - 34 // Avatar and two gaps; summary has priority.
        return available >= min(nameWidth, minimumNameWidth) ? min(nameWidth, available) : 0
    }

    var body: some View {
        if !members.isEmpty {
            let font = UIFont.systemFont(ofSize: 11, weight: legibilityWeight == .bold ? .semibold : .regular)
            let text = ChannelMemberPreviewText(text: summary, font: font)
            let textSize = text.size
            GeometryReader { proxy in
                Group {
                    if members.count == 1, let member = members.first {
                        let name = ChannelMemberPreviewText(text: member.name, font: font)
                        let minimum = ChannelMemberPreviewText(text: String(member.name.prefix(1)) + "…", font: font).size.width
                        let nameWidth = Self.nicknameWidth(width: proxy.size.width, textWidth: textSize.width,
                            memberCount: members.count, nameWidth: name.size.width, minimumNameWidth: minimum)
                        HStack(spacing: 5) {
                            if Self.avatarCount(width: proxy.size.width, textWidth: textSize.width, memberCount: 1) == 1 {
                                Avatar(name: member.name, path: member.avatarPath, speaking: member.speaking, size: 24, member: member)
                            }
                            if nameWidth > 0 { name.frame(width: nameWidth, height: name.size.height) }
                            text.frame(width: min(textSize.width, proxy.size.width), height: textSize.height)
                        }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(member.accessibilityLabel + ", " + summary)
                    } else {
                        HStack(spacing: 5) {
                            ForEach(Array(members.prefix(Self.avatarCount(width: proxy.size.width, textWidth: textSize.width, memberCount: members.count)))) { member in
                                Avatar(name: member.name, path: member.avatarPath, speaking: member.speaking, size: 24, member: member)
                            }
                            text.frame(width: min(textSize.width, proxy.size.width), height: textSize.height)
                        }
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            }.frame(height: max(24, textSize.height))
        }
    }
}
private struct ChannelMemberPreviewText: UIViewRepresentable {
    let text: String
    let font: UIFont

    var size: CGSize {
        let label = UILabel()
        configure(label)
        return label.intrinsicContentSize
    }

    func makeUIView(context: Context) -> UILabel {
        let label = UILabel()
        configure(label)
        return label
    }

    func updateUIView(_ label: UILabel, context: Context) { configure(label) }

    private func configure(_ label: UILabel) {
        label.text = text
        label.font = font
        label.textColor = UIColor(Palette.muted)
        label.lineBreakMode = .byTruncatingTail
        // Let a text-only row stay within its width when the full summary cannot fit.
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    }
}
struct UnreadBadge: View {
    let count: Int
    var body: some View {
        if count > 0 {
            Text(count > 99 ? "99+" : String(count))
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(.white)
                .padding(.horizontal, count > 9 ? 5 : 0)
                .frame(minWidth: 18, minHeight: 18)
                .background(Palette.disconnect, in: Capsule())
                .allowsHitTesting(false)
        }
    }
}
struct LocalImage: View {
    let path: String
    @State private var image: UIImage?
    var body: some View {
        Group {
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Color.clear }
        }.task(id: path) {
            image = nil
            let loaded = await Task.detached(priority: .utility) {
                let image = UIImage(contentsOfFile: path)
                if image == nil { try? FileManager.default.removeItem(atPath: path) }
                return image
            }.value
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}
struct GroupIconImage: View {
    let icon: GroupIcon
    var body: some View {
        Group {
            if let path = icon.iconPath {
                LocalImage(path: path)
            } else if let label = icon.builtinLabel {
                ZStack(alignment: .bottomTrailing) {
                    Image("Icon-avatar-placeholder").renderingMode(.template)
                        .resizable().scaledToFit().foregroundStyle(Palette.muted)
                        .frame(width: 17, height: 17)
                    Text(label).font(.system(size: 9, weight: .black))
                        .foregroundStyle(Palette.disconnect).offset(x: 1, y: 1)
                }
            }
        }
        .frame(width: 18, height: 18)
        .clipShape(RoundedRectangle(cornerRadius: 3))
        .accessibilityLabel(icon.name)
    }
}
struct ChannelIcon: View {
    let channel: Channel
    var size: CGFloat = 21
    var body: some View {
        ZStack {
            AppIcon(name: channel.password ? "lock" : "channels", size: size)
            if let path = channel.iconPath { LocalImage(path: path) }
        }.frame(width: size, height: size).clipShape(RoundedRectangle(cornerRadius: 4))
    }
}
struct ChannelSheetState {
    enum AfterDismissal {
        case channel(Channel)
        case privateChat(Member, ServerChatTarget)
    }

    var selected: Channel?
    private var afterDismissal: AfterDismissal?
    // The binding can become nil before the dismissal animation finishes.
    private(set) var active = false

    mutating func select(_ channel: Channel) {
        if case .privateChat = afterDismissal { return }
        if active { afterDismissal = .channel(channel) }
        else { active = true; selected = channel }
    }

    @discardableResult
    mutating func dismissForPrivateChat(_ member: Member, channelID: UInt64, snapshot: Snapshot) -> Bool {
        guard selected?.id == channelID, let connection = snapshot.serverChatTarget,
              let peer = snapshot.privateChatPeer(matching: member) else { return false }
        afterDismissal = .privateChat(peer, connection)
        selected = nil
        return true
    }

    mutating func cancelAfterDismissal() { afterDismissal = nil }

    mutating func didDismiss(snapshot: Snapshot) -> AfterDismissal? {
        active = false
        selected = nil
        defer { afterDismissal = nil }
        if case .privateChat(let member, let connection) = afterDismissal {
            guard snapshot.serverChatTarget == connection, let peer = snapshot.privateChatPeer(matching: member) else { return nil }
            return .privateChat(peer, connection)
        }
        return afterDismissal
    }
}
extension View {
    func avatarSelectionSheet(selection: Binding<AvatarSelectionRequest?>) -> some View {
        // The item supplies its ID at presentation time; a separate Boolean sheet
        // can capture the initial selection number on the very first opening.
        sheet(item: selection) { request in
            AvatarSelectionPage(selection: request.id, onDismiss: {
                guard selection.wrappedValue?.id == request.id else { return }
                Client.shared.cancelAvatarSelection(request.id)
                selection.wrappedValue = nil
            })
            .id(request.id)
            .onDisappear { Client.shared.cancelAvatarSelection(request.id) }
        }
    }
    func avatarClearConfirmation(isPresented: Binding<Bool>, connected: Bool, clear: @escaping () -> Void) -> some View {
        alert(L10n.string("avatar_remove"), isPresented: isPresented) {
            Button(L10n.string("action_cancel"), role: .cancel) {}
            Button(L10n.string("avatar_remove"), role: .destructive, action: clear)
        } message: {
            Text(L10n.string(connected ? "avatar_clear_confirm_connected" : "avatar_clear_confirm_offline"))
        }
    }
}

private struct VoiceAudioButtonStyle: ButtonStyle {
    let dragging: Bool
    let reduceMotion: Bool
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        let pressed = enabled && configuration.isPressed && !dragging
        return configuration.label.opacity(enabled ? 1 : 0.5).background {
            ZStack {
                if pressed {
                    Circle()
                        .fill(Color(hex: 0xF2F3F5).opacity(0.1))
                        .frame(width: 44, height: 44)
                        .transition(.asymmetric(insertion: .scale(scale: 0.4).combined(with: .opacity), removal: .opacity))
                }
            }
            .animation(reduceMotion || dragging || !enabled ? nil : .easeOut(duration: pressed ? 0.16 : 0.2), value: pressed)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

// UIKit owns the pan and spring so a new press can stop at the presentation frame on iOS 15.
private struct VoiceDrawerHost: UIViewControllerRepresentable {
    let content: AnyView
    let connected: Bool
    let expanded: Bool
    let travel: CGFloat
    let reduceMotion: Bool
    let onExpanded: (Bool) -> Void
    let onTouch: (Bool) -> Void

    func makeUIViewController(context: Context) -> VoiceDrawerController { VoiceDrawerController() }
    func updateUIViewController(_ controller: VoiceDrawerController, context: Context) {
        controller.configure(content: content, connected: connected, expanded: expanded, travel: travel,
                             reduceMotion: reduceMotion, onExpanded: onExpanded, onTouch: onTouch)
    }
    static func dismantleUIViewController(_ controller: VoiceDrawerController, coordinator: ()) { controller.cancelMotion() }
}

private final class DrawerHitView: UIView {
    weak var pane: UIView?
    var currentFrame: (() -> CGRect)?
    var onPress: (() -> Void)?
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, alpha > 0.01, bounds.contains(point),
              let pane, (currentFrame?() ?? pane.frame).contains(point) else { return nil }
        if event?.allTouches?.contains(where: { $0.phase == .began }) == true { onPress?() }
        // The model frame already holds the spring's destination; route taps to the visible header.
        let frame = currentFrame?() ?? pane.frame
        return pane.hitTest(CGPoint(x: point.x - frame.minX, y: point.y - frame.minY), with: event) ?? self
    }
}

private final class DrawerPanRecognizer: UIPanGestureRecognizer {
    var onTapEnd: (() -> Void)?
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesMoved(touches, with: event)
        if state == .failed { onTapEnd?() }
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesEnded(touches, with: event)
        if state == .failed { onTapEnd?() }
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        super.touchesCancelled(touches, with: event)
        if state == .failed { onTapEnd?() }
    }
}

final class VoiceDrawerController: UIViewController, UIGestureRecognizerDelegate {
    private let host = UIHostingController(rootView: AnyView(EmptyView()))
    private let pane = UIView()
    private var animator: UIViewPropertyAnimator?
    private var springOrigin = CGRect.zero
    private var connected = false
    private var expanded = false
    private var automaticallyClosing = false
    private var travel: CGFloat = 0
    private var reduceMotion = false
    private var dragOrigin: CGFloat = 0
    private var dragTravel: CGFloat = 0
    private var gestureValid = false
    private var lastSize = CGSize.zero
    private var onExpanded: (Bool) -> Void = { _ in }
    private var onTouch: (Bool) -> Void = { _ in }
    private lazy var pan: DrawerPanRecognizer = {
        let recognizer = DrawerPanRecognizer(target: self, action: #selector(drag(_:)))
        recognizer.delegate = self
        recognizer.cancelsTouchesInView = true
        recognizer.onTapEnd = { [weak self] in
            guard let self, self.connected, !self.automaticallyClosing else { return }
            self.settle(velocity: 0)
        }
        return recognizer
    }()
    override func loadView() { view = DrawerHitView() }
    override func viewDidLoad() {
        super.viewDidLoad()
        view.clipsToBounds = true
        view.addSubview(pane)
        pane.backgroundColor = UIColor(Palette.bottom)
        pane.accessibilityIdentifier = "voice-drawer"
        pane.clipsToBounds = true
        addChild(host); pane.addSubview(host.view); host.didMove(toParent: self)
        host.view.autoresizingMask = []
        host.view.backgroundColor = UIColor(Palette.bottom)
        host.view.clipsToBounds = true
        let hitView = view as! DrawerHitView
        hitView.pane = pane
        hitView.currentFrame = { [weak self] in self?.presentationFrame ?? .zero }
        hitView.onPress = { [weak self] in
            guard let self, self.connected, !self.automaticallyClosing,
                  self.pan.state != .began, self.pan.state != .changed else { return }
            self.onTouch(false)
            self.stopSpring()
        }
        view.addGestureRecognizer(pan)
    }
    func configure(content: AnyView, connected: Bool, expanded: Bool, travel: CGFloat, reduceMotion: Bool,
                   onExpanded: @escaping (Bool) -> Void, onTouch: @escaping (Bool) -> Void) {
        loadViewIfNeeded()
        let connectionChanged = self.connected != connected
        let resized = self.travel != travel
        let changed = self.expanded != (connected && expanded)
        let continueClosing = automaticallyClosing && !(connected && expanded)
        let stopAnimation = reduceMotion && !self.reduceMotion
        self.connected = connected; self.expanded = connected && expanded
        self.travel = travel; self.reduceMotion = reduceMotion
        self.onExpanded = onExpanded; self.onTouch = onTouch
        host.rootView = content
        if connectionChanged || resized {
            cancelMotion()
            pan.isEnabled = connected && travel > 0
            if (connectionChanged && !connected) || continueClosing {
                automaticallyClosing = true
                self.expanded = false
                settle(velocity: 0)
            } else { render(self.expanded ? travel : 0) }
        } else if changed || stopAnimation {
            if changed { automaticallyClosing = false }
            settle(velocity: 0)
        }
    }
    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard lastSize != view.bounds.size else { return }
        lastSize = view.bounds.size
        let continueClosing = automaticallyClosing
        cancelMotion()
        pan.isEnabled = connected && travel > 0
        if continueClosing {
            automaticallyClosing = true
            settle(velocity: 0)
        } else { render(expanded && connected ? travel : 0) }
    }
    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        cancelMotion()
        pan.isEnabled = connected && travel > 0
        render(expanded && connected ? travel : 0)
    }
    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        cancelMotion()
        render(expanded && connected ? travel : 0)
    }
    func cancelMotion() {
        gestureValid = false
        automaticallyClosing = false
        pan.isEnabled = false
        stopSpring()
        releaseClickBlock()
    }
    private var presentationFrame: CGRect {
        animator == nil ? pane.frame : (pane.layer.presentation()?.frame ?? springOrigin)
    }
    private var offset: CGFloat {
        let frame = presentationFrame
        let collapsed = min(64, view.bounds.height)
        // Keep the visible drawer height when an error banner or window change resizes the container.
        return frame.height > collapsed ? frame.height - collapsed : view.bounds.height - frame.minY - collapsed
    }
    private var resistanceLimit: CGFloat { min(16, max(0, view.bounds.height - 64 - travel)) }
    private func releaseClickBlock() {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.gestureValid else { return }
            self.onTouch(false)
        }
    }
    private func render(_ offset: CGFloat) {
        let collapsed = min(64, view.bounds.height)
        let height = min(view.bounds.height, collapsed + max(0, offset))
        // Keep SwiftUI's content laid out at its expanded size; only reveal it as the pane rises.
        host.view.frame = CGRect(x: 0, y: 0, width: view.bounds.width, height: min(view.bounds.height, collapsed + travel))
        pane.frame = CGRect(x: 0, y: view.bounds.height - height - min(0, offset), width: view.bounds.width, height: height)
    }
    private func stopSpring() {
        guard let animator else { return }
        let current = offset
        animator.stopAnimation(true)
        self.animator = nil
        render(current)
    }
    private func settle(velocity: CGFloat) {
        stopSpring()
        let target: CGFloat = connected && expanded ? travel : 0
        let distance = target - offset
        guard !reduceMotion, UIView.areAnimationsEnabled, abs(distance) > 0.5 else {
            automaticallyClosing = false
            render(target)
            return
        }
        // UIKit takes velocity relative to the remaining distance; pan velocity is downward pt/s.
        let relativeVelocity = min(8, max(-8, -velocity / distance))
        let timing = UISpringTimingParameters(dampingRatio: 1, initialVelocity: CGVector(dx: 0, dy: relativeVelocity))
        let animation = UIViewPropertyAnimator(duration: 0.45, timingParameters: timing)
        animation.addAnimations { [weak self] in self?.render(target) }
        animation.addCompletion { [weak self, weak animation] _ in
            guard let self, self.animator === animation else { return }
            self.animator = nil
            self.automaticallyClosing = false
            self.render(self.connected && self.expanded ? self.travel : 0)
        }
        springOrigin = pane.frame
        animator = animation
        animation.startAnimation()
    }
    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        let velocity = pan.velocity(in: view)
        var touched = host.view.hitTest(pan.location(in: host.view), with: nil)
        while let current = touched {
            if current is UIScrollView { return false }
            touched = current.superview
        }
        return connected && travel > 0 && abs(velocity.y) > abs(velocity.x)
    }
    @objc private func drag(_ recognizer: UIPanGestureRecognizer) {
        guard connected, travel > 0 else { return }
        switch recognizer.state {
        case .began:
            let closing = automaticallyClosing
            automaticallyClosing = false
            stopSpring()
            // Closing may retain a taller pane after a resize; resume dragging without jumping to the new anchor.
            dragTravel = closing || offset > travel + resistanceLimit ? max(travel, offset) : travel
            dragOrigin = HomeView.drawerRawOffset(offset, travel: dragTravel, limit: resistanceLimit)
            gestureValid = true; onTouch(true)
            fallthrough
        case .changed:
            guard gestureValid else { return }
            render(HomeView.drawerOffset(dragOrigin - recognizer.translation(in: view).y, travel: dragTravel, limit: resistanceLimit))
        case .ended:
            guard gestureValid else { return }
            gestureValid = false
            let velocity = recognizer.velocity(in: view).y
            expanded = HomeView.drawerShouldExpand(height: offset, travel: travel, velocity: velocity)
            onExpanded(expanded)
            settle(velocity: velocity)
            releaseClickBlock()
        case .cancelled, .failed:
            guard gestureValid else { return }
            gestureValid = false
            settle(velocity: 0)
            releaseClickBlock()
        default: break
        }
    }
}

enum DrawerToolPage { case home, volumes, files }

// Once a touch leaves or its context changes, only a new touch may acquire a token.
struct PushToTalkPress {
    private(set) var tracking = false
    private(set) var token: String?
    mutating func begin(token: String?) {
        guard !tracking else { return }
        tracking = true; self.token = token
    }
    mutating func cancel() -> String? {
        defer { token = nil }
        return token
    }
    mutating func release() -> String? {
        tracking = false
        return cancel()
    }
}

struct PushToTalkButton: View {
    @Environment(\.sizeCategory) private var sizeCategory
    let available: Bool
    let transmitting: Bool
    let token: String
    let begin: () -> String?
    let end: (String) -> Void
    var body: some View {
        let title = L10n.string(transmitting ? "voice_push_to_talk_active" : "voice_push_to_talk_hold")
        let visibleTitle = !transmitting && sizeCategory.isAccessibilityCategory ? L10n.string("voice_hold_compact") : title
        Button {} label: {
            HStack(spacing: 10) {
                AppIcon(name: "mic-on", size: 20).accessibilityHidden(true)
                Text(visibleTitle)
                    .fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.center)
            }
            .font(.headline).foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 56)
        }
        // Match Join Channel's complete native style, including its added label insets.
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .tint(available ? (transmitting ? Palette.green : Palette.accent) : Palette.muted)
        .frame(maxWidth: .infinity)
        .fixedSize(horizontal: false, vertical: true)
        .disabled(!available)
        .background(PushToTalkTouchObserver(available: available, token: token, begin: begin, end: end))
        .accessibilityIdentifier("push-to-talk")
        .accessibilityLabel(title)
        .accessibilityHint(L10n.string("voice_push_to_talk_hint"))
    }
}

private struct PushToTalkTouchObserver: UIViewRepresentable {
    let available: Bool
    let token: String
    let begin: () -> String?
    let end: (String) -> Void
    func makeUIView(context: Context) -> PushToTalkTouchView { PushToTalkTouchView() }
    func updateUIView(_ view: PushToTalkTouchView, context: Context) {
        view.configure(available: available, token: token, begin: begin, end: end)
    }
    static func dismantleUIView(_ view: PushToTalkTouchView, coordinator: ()) { view.detach() }
}

// Observe the native button's touches without taking its hit test or changing its press feedback.
final class PushToTalkTouchView: UIView {
    fileprivate var available = false
    private var token = ""
    fileprivate var begin: () -> String? = { nil }
    fileprivate var end: (String) -> Void = { _ in }
    private weak var observedWindow: UIWindow?
    private(set) lazy var observer = PushToTalkTouchRecognizer(anchor: self)
    init() {
        super.init(frame: .zero)
        isUserInteractionEnabled = false
        accessibilityElementsHidden = true
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    func configure(available: Bool, token: String, begin: @escaping () -> String?, end: @escaping (String) -> Void) {
        if token != self.token || !available { observer.cancelPress(deferCallback: true) }
        self.available = available; self.token = token; self.begin = begin; self.end = end
    }
    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard window !== observedWindow else { return }
        detach()
        if let window { observedWindow = window; window.addGestureRecognizer(observer) }
    }
    func detach() {
        observer.releasePress()
        observedWindow?.removeGestureRecognizer(observer)
        observedWindow = nil
    }
    fileprivate func accepts(_ touch: UITouch) -> Bool {
        guard let touched = touch.view else { return false }
        return acceptsHit(touched, location: touch.location(in: self))
    }
    func containsLocation(_ location: CGPoint) -> Bool {
        guard let window, bounds.contains(location) else { return false }
        var ancestor: UIView? = self
        while let view = ancestor, view !== window {
            guard !view.isHidden, view.alpha > 0.01,
                  !view.clipsToBounds || view.bounds.contains(convert(location, to: view)) else { return false }
            ancestor = view.superview
        }
        return true
    }
    func acceptsHit(_ touched: UIView, location: CGPoint) -> Bool {
        guard let window, touched.window === window, containsLocation(location) else { return false }
        // SwiftUI can insert a non-view responder between its hosting view and controller.
        var responder: UIResponder? = self
        while let current = responder {
            if let controller = current as? UIViewController {
                // Presented sheets and other hosting controllers must never activate the button underneath.
                return touched === controller.view || touched.isDescendant(of: controller.view)
            }
            responder = current.next
        }
        return false
    }
}

final class PushToTalkTouchRecognizer: UIGestureRecognizer, UIGestureRecognizerDelegate {
    private weak var anchor: PushToTalkTouchView?
    private weak var trackedTouch: UITouch?
    private(set) var press = PushToTalkPress()
    init(anchor: PushToTalkTouchView) {
        self.anchor = anchor
        super.init(target: nil, action: nil)
        name = "Push to talk raw touch observer"
        delegate = self
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        trackedTouch == nil && anchor?.accepts(touch) == true
    }
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool { true }
    override func canPrevent(_ preventedGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func canBePrevented(by preventingGestureRecognizer: UIGestureRecognizer) -> Bool { false }
    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        guard trackedTouch == nil, let touch = touches.first, let anchor, anchor.accepts(touch) else { return }
        trackedTouch = touch
        press.begin(token: anchor.available ? anchor.begin() : nil)
        state = .began
    }
    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let touch = trackedTouch, let anchor else { return }
        // SwiftUI may detach the original hit view while updating native press feedback.
        if !anchor.containsLocation(touch.location(in: anchor)) { cancelPress() }
        state = .changed
    }
    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let trackedTouch, touches.contains(trackedTouch) else { return }
        releasePress(); state = .ended
    }
    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent) {
        guard let trackedTouch, touches.contains(trackedTouch) else { return }
        releasePress(); state = .cancelled
    }
    func cancelPress(deferCallback: Bool = false) {
        guard let token = press.cancel(), let end = anchor?.end else { return }
        // SwiftUI updates the bridge view during rendering; its callbacks may publish view state.
        if deferCallback { DispatchQueue.main.async { end(token) } } else { end(token) }
    }
    func releasePress() {
        trackedTouch = nil
        if let token = press.release() { anchor?.end(token) }
    }
    override func reset() { releasePress(); super.reset() }
}

struct PushToTalkBar: View {
    @Environment(\.sizeCategory) private var sizeCategory
    @ObservedObject var client: Client
    var showsTarget = false
    var body: some View {
        if client.speech.mode == .pushToTalk {
            VStack(spacing: sizeCategory.isAccessibilityCategory ? 4 : 8) {
                if showsTarget {
                    Text(L10n.format("voice_push_to_talk_channel", client.state.channels.first { $0.id == client.currentChannel }?.name ?? L10n.string("status_disconnected")))
                        .font(.caption).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("push-to-talk-channel")
                }
                PushToTalkButton(available: client.speech.available && client.audioProcessingStatus != "switching" && !client.muted && !client.audioBusy && !client.needsMicrophonePermission,
                    transmitting: client.speech.transmitting, token: client.speech.token,
                    begin: client.beginPushToTalk, end: { client.endPushToTalk(token: $0) })
                if client.connected && !client.muted && client.needsMicrophonePermission {
                    Button {
                        Task { await client.requestPushToTalkPermission() }
                    } label: {
                        Text(L10n.string(client.microphonePermission == .denied ? "voice_microphone_settings" : "voice_microphone_allow"))
                            .fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.center)
                    }
                    .font(.footnote).frame(minHeight: 44).disabled(client.audioBusy)
                    .accessibilityIdentifier("push-to-talk-permission")
                }
            }
            .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 8)
            .fixedSize(horizontal: false, vertical: true)
            .background(Palette.bottom)
        }
    }
}

// A local system navigation stack keeps the drawer's fixed chrome and vertical pan outside the push.
private struct DrawerNavigationHost: UIViewControllerRepresentable {
    let page: DrawerToolPage
    let home: AnyView
    let detail: AnyView
    let reduceMotion: Bool

    func makeUIViewController(context: Context) -> DrawerNavigationController { DrawerNavigationController() }
    func updateUIViewController(_ controller: DrawerNavigationController, context: Context) {
        controller.configure(page: page, home: home, detail: detail, reduceMotion: reduceMotion)
    }
}

final class DrawerNavigationController: UINavigationController, UINavigationControllerDelegate {
    private let home = UIHostingController(rootView: AnyView(EmptyView()))
    private var detail: UIHostingController<AnyView>?
    private var requestedPage = DrawerToolPage.home
    private var detailContent = AnyView(EmptyView())
    private var reduceMotion = false
    private(set) var page = DrawerToolPage.home
    private(set) var switching = false

    init() { super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        delegate = self
        setNavigationBarHidden(true, animated: false)
        interactivePopGestureRecognizer?.isEnabled = false
        if #available(iOS 26.0, *) { interactiveContentPopGestureRecognizer?.isEnabled = false }
        view.backgroundColor = UIColor(Palette.bottom)
        view.clipsToBounds = true
        home.view.backgroundColor = UIColor(Palette.bottom)
        setViewControllers([home], animated: false)
    }

    func configure(page: DrawerToolPage, home: AnyView, detail: AnyView, reduceMotion: Bool) {
        loadViewIfNeeded()
        requestedPage = page
        detailContent = detail
        self.reduceMotion = reduceMotion
        self.home.rootView = home
        // Do not feed a closed/cleared core snapshot into the outgoing file page.
        if page != .home && page == self.page { self.detail?.rootView = detail }
        synchronize()
    }

    private func synchronize() {
        guard !switching, requestedPage != page else { return }
        let animated = view.window != nil && !reduceMotion && !UIAccessibility.isReduceMotionEnabled && UIView.areAnimationsEnabled
        if page != .home {
            page = .home
            switching = animated
            setViewControllers([home], animated: animated)
        } else {
            page = requestedPage
            let controller = UIHostingController(rootView: detailContent)
            controller.view.backgroundColor = UIColor(Palette.bottom)
            detail = controller
            switching = animated
            setViewControllers([home, controller], animated: animated)
        }
        if !animated { synchronize() }
    }

    func navigationController(_ navigationController: UINavigationController, willShow viewController: UIViewController, animated: Bool) {
        for controller in [home, detail].compactMap({ $0 }) {
            controller.view.isUserInteractionEnabled = !animated && controller === viewController
            controller.view.accessibilityElementsHidden = animated || controller !== viewController
        }
    }

    func navigationController(_ navigationController: UINavigationController, didShow viewController: UIViewController, animated: Bool) {
        switching = false
        for controller in [home, detail].compactMap({ $0 }) {
            controller.view.isUserInteractionEnabled = controller === viewController
            controller.view.accessibilityElementsHidden = controller !== viewController
        }
        if viewController === home { detail = nil }
        synchronize()
    }
}

struct HomeView: View {
    @ObservedObject var client: Client
    @EnvironmentObject private var language: LanguageSettings
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State var tab = 0
    @Environment(\.sizeCategory) private var sizeCategory
    @State private var userVolumesOpen = false
    @State private var drawerExpanded = false
    @State private var networkPanelHeight: CGFloat = 0
    @State private var drawerBlocksClick = false
    @State private var showConnect = false
    @State private var editingBookmark: Bookmark?
    @State private var deletingBookmark: Bookmark?
    @State private var nicknamePresented = false
    @State private var avatarClearPresented = false
    @State private var avatarSelection: AvatarSelectionRequest?
    @State private var channelSheet = ChannelSheetState()
    @State private var privateChatMember: Member?
    @State private var channelPrivateChatTransition = ChannelPrivateChatTransition()
    @State private var editingChannel: ChannelEditSelection?
    @State private var rootChannelCreation: ChannelCreation?
    @State private var childChannelCreation: ChannelCreation?
    @State private var movingChannel: ChannelEditSelection?
    @State private var deletingChannel: ChannelEditSelection?
    @State private var channelDeleteRequest: String?
    @State private var channelDeleteError: String?
    @State private var channelToReveal: UInt64?
    @State private var lockedChannel: Channel?
    @State private var channelPassword = ""
    @State private var channelMemberListPanning = false
    @State private var channelMemberListHeight: CGFloat = 52
    @State private var viewportHeight = UIScreen.main.bounds.height
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                if let server = client.state.server, let target = client.state.serverChatTarget {
                    NavigationLink(destination: ChatView(client: client, title: L10n.string("server_chat_title"),
                                                         conversation: target.conversation, serverChat: target)) {
                        HStack(spacing: 11) {
                            AppIcon(name: "channel-chat", size: 28).foregroundStyle(.white)
                                .frame(width: 34, height: 34)
                                .overlay(alignment: .topTrailing) {
                                    UnreadBadge(count: client.serverUnread).offset(x: 5, y: -5)
                                }
                            Text(server).font(.system(size: 16, weight: .heavy)).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.format("server_chat_open", server))
                    .accessibilityValue(client.serverUnread > 0 ? L10n.format("server_chat_unread", client.serverUnread) : "")
                    .id(target)
                } else {
                    if let server = client.state.server {
                        Avatar(name: server).accessibilityHidden(true)
                    } else {
                        Image("AppLogo").resizable().frame(width: 34, height: 34).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityLabel("MobileSpeak")
                    }
                    Text(client.state.server ?? "MobileSpeak").font(.system(size: 16, weight: .heavy)).lineLimit(1)
                    Spacer(minLength: 0)
                }
                if client.connected || client.busy {
                    Button { client.disconnect() } label: {
                        Image("Icon-off").renderingMode(.original).resizable().scaledToFit()
                            .frame(width: 34, height: 34)
                            .frame(width: 44, height: 44)
                    }.buttonStyle(.plain).accessibilityLabel(L10n.string("action_disconnect"))
                }
            }.padding(.horizontal, 14).frame(height: 64)
            Divider()
            if let error = client.error {
                HStack {
                    Text(error).font(.footnote).frame(maxWidth: .infinity, alignment: .leading)
                    Button { client.error = nil } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }.accessibilityLabel(L10n.string("accessibility_close_error"))
                }.padding(.leading, 16).background(Color(hex: 0x542A30))
            }
            GeometryReader { proxy in
                ZStack(alignment: .bottom) {
                    ZStack {
                        Group {
                            if client.busy { VStack(spacing: 20) { ProgressView(); Text(L10n.string(client.reconnecting ? "status_reconnecting" : "status_connecting_server")) }.frame(maxWidth: .infinity, maxHeight: .infinity) }
                            else if !client.connected { if client.bookmarks.isEmpty { empty } else { bookmarkList } }
                            else {
                                ZStack {
                                    channels
                                        .opacity(tab == 0 ? 1 : 0)
                                        .allowsHitTesting(tab == 0)
                                        .accessibilityHidden(tab != 0)
                                    members
                                        .opacity(tab == 1 ? 1 : 0)
                                        .allowsHitTesting(tab == 1)
                                        .accessibilityHidden(tab != 1)
                                }
                            }
                        }
                        .opacity(tab == 2 ? 0 : 1)
                        .allowsHitTesting(tab != 2)
                        .accessibilityHidden(tab == 2)
                        settings
                            .opacity(tab == 2 ? 1 : 0)
                            .allowsHitTesting(tab == 2)
                            .accessibilityHidden(tab != 2)
                    }.frame(maxWidth: .infinity, maxHeight: .infinity).padding(.bottom, 64)
                    voiceDrawer(availableHeight: proxy.size.height)
                }.frame(maxWidth: .infinity, maxHeight: .infinity).contentShape(Rectangle()).clipped()
            }
            PushToTalkBar(client: client)
            HStack {
                navigation(L10n.string("tab_channels"), "channels", 0)
                navigation(L10n.string("tab_members"), "members", 1)
                navigation(L10n.string("tab_settings"), "settings", 2)
            }.padding(.top, 10).padding(.bottom, 8).background(Palette.bottom.ignoresSafeArea(edges: .bottom))
        }
        .foregroundStyle(Color(hex: 0xF2F3F5))
        .background(Palette.background.ignoresSafeArea())
        .background(privateChatLink)
        .overlay {
            ChannelPrivateChatSource(transition: channelPrivateChatTransition)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .background(GeometryReader { proxy in
            Color.clear
                .onAppear { viewportHeight = proxy.size.height }
                .onChange(of: proxy.size.height) { viewportHeight = $0 }
        })
        .onChange(of: client.connected) { connected in
            if !connected {
                drawerExpanded = false; drawerBlocksClick = false; userVolumesOpen = false
                editingChannel = nil; channelSheet.selected = nil
                channelSheet.cancelAfterDismissal()
                rootChannelCreation = nil; childChannelCreation = nil; movingChannel = nil
                deletingChannel = nil; channelDeleteRequest = nil; channelDeleteError = nil; channelToReveal = nil
            }
        }
        .navigationTitle(tab == 2 ? L10n.string("tab_settings") : "")
        .onChange(of: tab) { _ in client.endPushToTalk() }
        .onChange(of: showConnect) { _ in client.endPushToTalk() }
        .onChange(of: editingBookmark?.id) { _ in client.endPushToTalk() }
        .onChange(of: channelSheet.selected?.id) { _ in client.endPushToTalk() }
        .onChange(of: rootChannelCreation?.id) { _ in client.endPushToTalk() }
        .onChange(of: nicknamePresented) { _ in client.endPushToTalk() }
        .onChange(of: avatarSelection?.id) { _ in client.endPushToTalk() }
        .onChange(of: avatarClearPresented) { _ in client.endPushToTalk() }
        .navigationBarHidden(true)
        .sheet(isPresented: $showConnect) { ConnectView(client: client) }
        .sheet(item: $editingBookmark) { bookmark in ConnectView(client: client, bookmark: bookmark) }
        .confirmationDialog(deletingBookmark.map { L10n.format("bookmark_delete_message", $0.title) } ?? L10n.string("bookmark_delete_title"), isPresented: Binding(get: { deletingBookmark != nil }, set: { if !$0 { deletingBookmark = nil } }), titleVisibility: .visible) {
            Button(L10n.string("bookmark_delete_title"), role: .destructive) { if let bookmark = deletingBookmark { client.deleteBookmark(bookmark) }; deletingBookmark = nil }
        }
        .sheet(item: $channelSheet.selected, onDismiss: {
            let next = channelSheet.didDismiss(snapshot: client.state)
            guard client.connected, tab == 0 else { channelPrivateChatTransition.clear(); return }
            switch next {
            case .channel(let channel):
                channelPrivateChatTransition.clear()
                showChannel(channel)
            case .privateChat(let member, _):
                channelPrivateChatTransition.moveToSource()
                privateChatMember = member
            case nil:
                channelPrivateChatTransition.clear()
            }
        }) { channel in
            let current = client.state.channels.first { $0.id == channel.id } ?? channel
            let members = client.state.clients.filter { $0.channel == channel.id }
            let connection = client.state.serverChatTarget
            let layout = Self.channelSheetLayout(memberHeight: channelMemberListHeight, viewportHeight: viewportHeight)
            VStack(spacing: 16) {
                HStack {
                    ChannelIcon(channel: current, size: 28)
                    Text(current.name).font(.title3.bold()).lineLimit(1)
                    Spacer(minLength: 0)
                    if channelDeleteRequest != nil {
                        ProgressView().frame(width: 44, height: 44)
                    } else if let target = client.state.serverChatTarget {
                        let menuLanguage = language.selection == .system ? language.systemLanguage : language.selection
                        ChannelManagementMenu(items: .init(target: target, channelID: channel.id,
                            canEdit: current.canEdit != false, canDelete: current.canDelete != false, language: menuLanguage)) {
                            manageChannel($0, id: channel.id, target: target)
                        }.frame(width: 44, height: 44)
                    }
                    Button(L10n.string("action_close")) { channelSheet.selected = nil }
                        .disabled(channelDeleteRequest != nil)
                }.frame(height: 44)
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        if let description = current.description, !description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            Text(description).font(.subheadline).foregroundStyle(Palette.muted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        if members.isEmpty {
                            Text(L10n.string("channel_no_members")).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, minHeight: 52)
                        } else {
                            ForEach(members) { member in
                                if let connection, member.id != client.state.ownClient,
                                   member.conversation != nil, channelDeleteRequest == nil {
                                    Button {
                                        guard !channelMemberListPanning else { return }
                                        let snapshot = client.state
                                        guard snapshot.serverChatTarget == connection else { return }
                                        var next = channelSheet
                                        guard next.dismissForPrivateChat(member, channelID: channel.id, snapshot: snapshot) else { return }
                                        channelPrivateChatTransition.capture()
                                        var transaction = Transaction(animation: nil)
                                        transaction.disablesAnimations = true
                                        withTransaction(transaction) { channelSheet = next }
                                    } label: {
                                        memberRow(member)
                                    }
                                    .buttonStyle(.plain)
                                } else {
                                    memberRow(member)
                                }
                            }
                        }
                    }
                    .background(ChannelMemberListPanObserver(isPanning: $channelMemberListPanning)
                        .allowsHitTesting(false).accessibilityHidden(true))
                    .background(GeometryReader { proxy in
                        Color.clear.preference(key: ChannelMemberListHeightKey.self, value: proxy.size.height)
                    })
                }
                .frame(height: layout.list)
                Button { channelSheet.selected = nil; join(current) } label: {
                    Text(L10n.string("channel_join")).font(.headline).frame(maxWidth: .infinity, minHeight: 56)
                }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .frame(maxWidth: .infinity)
                    .disabled(!client.connected || client.currentChannel == channel.id || channelDeleteRequest != nil)
            }
            .padding(20)
            .channelSheetBackground()
            .onPreferenceChange(ChannelMemberListHeightKey.self) { height in
                guard channelSheet.selected?.id == channel.id, abs(height - channelMemberListHeight) > 0.5 else { return }
                withAnimation(.easeInOut(duration: 0.2)) { channelMemberListHeight = height }
            }
            .task(id: current.description == nil) {
                if current.description == nil, let target = client.state.serverChatTarget {
                    try? client.channelCommand("description", target: target, channel: current.id, request: UUID().uuidString)
                }
            }
            .onChange(of: client.state.channels.contains { $0.id == channel.id }) { present in
                if !present {
                    editingChannel = nil; childChannelCreation = nil; movingChannel = nil
                    channelDeleteRequest = nil; channelSheet.selected = nil
                }
            }
            .onChange(of: client.channelCommandResults["delete"]) { result in
                guard let result, result.requestId == channelDeleteRequest else { return }
                channelDeleteRequest = nil
                if let error = result.error { channelDeleteError = L10n.coreError(error, detail: result.detail) }
                else { channelSheet.selected = nil }
            }
            .alert(L10n.string("channel_delete_title"), isPresented: Binding(
                get: { deletingChannel != nil }, set: { if !$0 { deletingChannel = nil } }
            ), presenting: deletingChannel) { selection in
                Button(L10n.string("action_delete"), role: .destructive) { deleteChannel(selection) }
                Button(L10n.string("action_cancel"), role: .cancel) {}
            } message: { selection in
                Text(L10n.format("channel_delete_message", selection.channel.name))
            }
            .alert(L10n.string("error_channel_delete_failed"), isPresented: Binding(
                get: { channelDeleteError != nil }, set: { if !$0 { channelDeleteError = nil } }
            )) {
                Button(L10n.string("action_close"), role: .cancel) {}
            } message: { Text(channelDeleteError ?? "") }
            .sheet(item: $editingChannel) { selection in
                ChannelEditView(client: client, selection: selection)
            }
            .sheet(item: $childChannelCreation) { selection in
                ChannelCreateView(client: client, selection: selection) { id in
                    childChannelCreation = nil; channelSheet.selected = nil; channelToReveal = id
                }
            }
            .sheet(item: $movingChannel) { selection in
                ChannelMoveView(client: client, selection: selection)
            }
            .interactiveDismissDisabled(channelDeleteRequest != nil)
            .adaptiveSheetHeight(layout.sheet)
        }
        .sheet(item: $rootChannelCreation) { selection in
            ChannelCreateView(client: client, selection: selection) { id in
                rootChannelCreation = nil; channelToReveal = id
            }
        }
        .alert(L10n.string("channel_password"), isPresented: Binding(get: { lockedChannel != nil }, set: { if !$0 { lockedChannel = nil } })) {
            SecureField(L10n.string("password"), text: $channelPassword)
            Button(L10n.string("action_join")) { if let channel = lockedChannel { client.join(channel, password: channelPassword) }; channelPassword = ""; lockedChannel = nil }
            Button(L10n.string("action_cancel"), role: .cancel) { channelPassword = ""; lockedChannel = nil }
        }
    }
    private var bookmarkList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) {
                PageTitle(key: "bookmarks_title").padding(.horizontal, PageTitle.inset)
                ForEach(client.bookmarks) { bookmark in
                    HStack {
                        Button {
                            client.connectBookmark(bookmark)
                        } label: {
                            HStack(spacing: 12) {
                                Avatar(name: bookmark.title).accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(bookmark.title).font(.headline)
                                    Text(bookmark.address).font(.caption).foregroundStyle(Palette.muted)
                                }
                                Spacer()
                            }.frame(minHeight: 64).contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(client.connected || client.busy)
                        Menu {
                            Button { editingBookmark = bookmark } label: {
                                Label { Text(L10n.string("action_edit")) } icon: { AppIcon(name: "pencil", scaledSize: 24) }
                            }
                            Button(role: .destructive) { deletingBookmark = bookmark } label: {
                                Label { Text(L10n.string("action_delete")) } icon: { AppIcon(name: "trash", scaledSize: 24, originalTint: Palette.menuDestructive) }
                            }
                        } label: { AppIcon(name: "more", scaledSize: 24).frame(width: 44, height: 44) }.accessibilityLabel(L10n.format("bookmark_manage", bookmark.title))
                    }.padding(12).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                        .padding(.horizontal, 16)
                }
                Button { showConnect = true } label: { Label { Text(L10n.string("bookmark_add_server")) } icon: { AppIcon(name: "plus", scaledSize: 18) } }
                    .padding(.vertical, 12).padding(.horizontal, 16)
                if client.connected {
                    Text(L10n.string("bookmark_disconnect_first")).font(.footnote).foregroundStyle(Palette.muted)
                        .padding(.horizontal, 16)
                }
            }.padding(.top, PageTitle.inset).padding(.bottom, 16)
        }.background(Palette.background)
    }
    private var empty: some View {
        VStack(spacing: 16) {
            AppIcon(name: "headphones", size: 52).foregroundStyle(Palette.muted)
            Text(L10n.string("empty_title")).font(.system(size: 20, weight: .heavy))
            Text(L10n.string("empty_message")).font(.subheadline).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Button { showConnect = true } label: { Label { Text(L10n.string("action_connect_server")) } icon: { AppIcon(name: "plus", scaledSize: 18) }.padding(.vertical, 5) }
                .buttonStyle(.borderedProminent).clipShape(Capsule()).padding(.top, 8)
        }.padding(24).frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private var channels: some View {
        ScrollViewReader { reader in
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 9) {
                HStack {
                    PageTitle(key: "tab_channels")
                        .padding(.top, PageTitle.inset)
                        // Align the button with the text inside the title's asymmetric padding.
                        .alignmentGuide(VerticalAlignment.center) { dimensions in
                            dimensions[VerticalAlignment.center] + (PageTitle.inset - PageTitle.bottomSpacing) / 2
                        }
                    Spacer(minLength: 0)
                    if let target = client.state.serverChatTarget {
                        Button { rootChannelCreation = ChannelCreation(target: target, parent: nil) } label: {
                            Label { Text(L10n.string("channel_create_title")) } icon: { AppIcon(name: "plus", scaledSize: 18) }
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                        }.foregroundStyle(Palette.accent)
                    }
                }
                ForEach(client.orderedChannels, id: \.channel.id) { entry in
                    let channel = entry.channel
                    if let spacer = channel.spacer {
                        let text = spacer.alignment == .repeatFill && !spacer.text.isEmpty
                            ? String(repeating: spacer.text, count: max(1, 48 / spacer.text.count)) : spacer.text
                        let alignment: Alignment = spacer.alignment == .center ? .center : spacer.alignment == .right ? .trailing : .leading
                        Text(text).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                            .frame(maxWidth: .infinity, minHeight: 48, alignment: alignment)
                            .padding(10).padding(.leading, 4)
                            .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                            .padding(.leading, CGFloat(min(entry.depth, 4)) * 10)
                            .accessibilityLabel(spacer.text).accessibilityHidden(spacer.text.isEmpty)
                            .allowsHitTesting(false)
                    } else {
                        let members = client.state.clients.filter { $0.channel == channel.id }
                        let selected = client.currentChannel == channel.id
                        HStack(spacing: 8) {
                            Button {
                                UIImpactFeedbackGenerator(style: .light).impactOccurred()
                                showChannel(channel)
                            } label: {
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack(spacing: 11) {
                                        ChannelIcon(channel: channel)
                                        Text(channel.name).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                                        Spacer(minLength: 0)
                                    }.frame(minHeight: 48)
                                    if !members.isEmpty {
                                        ChannelMemberPreview(members: members, summary: L10n.channelMembers(count: members.count)).padding(.leading, 30)
                                    }
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            NavigationLink(destination: ChannelChatView(client: client, channel: channel)) {
                                AppIcon(name: "channel-chat", size: 28).frame(width: 48, height: 48)
                                    .background(Palette.bottom).clipShape(RoundedRectangle(cornerRadius: 10))
                                    .foregroundStyle(selected ? Color.white : Palette.muted.opacity(0.45))
                                    .overlay(alignment: .topTrailing) {
                                        if selected { UnreadBadge(count: client.currentChannelUnread).offset(x: 5, y: -5) }
                                    }
                            }.disabled(!selected).accessibilityLabel(L10n.format("channel_chat_open", channel.name))
                        }.padding(10).padding(.leading, 4)
                            .background(selected ? Palette.selected : Palette.card)
                            .clipShape(RoundedRectangle(cornerRadius: 14))
                            .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Palette.accent : Palette.border, lineWidth: selected ? 2 : 1))
                            .padding(.leading, CGFloat(min(entry.depth, 4)) * 10)
                    }
                }
            }.padding(.horizontal, PageTitle.inset).padding(.bottom, PageTitle.inset)
        }
        .task(id: channelToReveal) {
            guard let id = channelToReveal else { return }
            await Task.yield()
            guard !Task.isCancelled else { return }
            reader.scrollTo(id, anchor: .center)
            channelToReveal = nil
        }
        }
    }
    private var privateChatLink: some View {
        NavigationLink(isActive: Binding(
            get: { privateChatMember != nil },
            set: {
                if !$0 {
                    privateChatMember = nil
                    channelPrivateChatTransition.clear()
                }
            }
        )) {
            if let privateChatMember {
                PrivateChatView(client: client, member: privateChatMember)
                    .background {
                        ChannelPrivateChatArrival(transition: channelPrivateChatTransition)
                            .allowsHitTesting(false)
                            .accessibilityHidden(true)
                    }
            }
        } label: {
            EmptyView()
        }
        .accessibilityHidden(true)
    }
    private var members: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                PageTitle(key: "tab_members").padding(.horizontal, PageTitle.inset)
                ForEach(client.state.clients) { member in
                    let isSelf = member.id == client.state.ownClient
                    if !isSelf && member.conversation != nil {
                        NavigationLink(destination: PrivateChatView(client: client, member: member)) { memberRow(member, showUnread: true) }.buttonStyle(.plain).padding(.horizontal, 16)
                    } else { memberRow(member, isSelf: isSelf).padding(.horizontal, 16) }
                }
            }.padding(.top, PageTitle.inset).padding(.bottom, 16)
        }
    }
    private func memberRow(_ m: Member, showUnread: Bool = false, isSelf: Bool = false) -> some View {
        // ponytail: show the first four; use a width-aware strip if people routinely select more.
        let visibleBadges = m.badges.filter { $0.iconPath != nil }.prefix(4)
        let channelGroupIcon = m.channelGroupIcon?.canDisplay == true ? m.channelGroupIcon : nil
        let visibleGroupIcons = m.serverGroupIcons.filter(\.canDisplay)
            .prefix(max(0, 5 - visibleBadges.count - (channelGroupIcon == nil ? 0 : 1)))
        return HStack(spacing: 12) {
            Avatar(name: m.name, path: m.avatarPath, speaking: m.speaking, member: m)
                .accessibilityHidden(true)
                .overlay(alignment: .topTrailing) {
                    if showUnread { UnreadBadge(count: client.privateUnread(m)).offset(x: 5, y: -5) }
                }
            if isSelf {
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text(m.name).lineLimit(1).truncationMode(.tail)
                    Text(L10n.string("member_self_suffix")).foregroundStyle(Palette.muted)
                        .lineLimit(1).truncationMode(.tail).layoutPriority(1)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(m.accessibilityLabel + L10n.string("member_self_suffix"))
            } else {
                Text(m.name).lineLimit(1).truncationMode(.tail).accessibilityLabel(m.accessibilityLabel)
            }
            Spacer(minLength: 8)
            if !visibleBadges.isEmpty || !visibleGroupIcons.isEmpty || channelGroupIcon != nil {
                HStack(spacing: 3) {
                    ForEach(visibleGroupIcons) { group in
                        GroupIconImage(icon: group)
                    }
                    ForEach(visibleBadges) { badge in
                        if let path = badge.iconPath {
                            LocalImage(path: path)
                                .frame(width: 18, height: 18)
                                .clipShape(RoundedRectangle(cornerRadius: 3))
                                .accessibilityLabel(badge.name)
                        }
                    }
                    if let channelGroupIcon { GroupIconImage(icon: channelGroupIcon) }
                }.fixedSize()
            }
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
    private var settings: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageTitle(key: "tab_settings").padding(.horizontal, PageTitle.inset)
                VStack(alignment: .leading, spacing: 24) {
                    VStack(alignment: .leading, spacing: 10) {
                        Text(L10n.string("profile_title")).font(.footnote).foregroundStyle(Palette.muted)
                        ProfileCard(nickname: client.profile.nickname, preview: client.avatarPreview,
                                    subtitle: L10n.string(client.avatarIntent == "clear" ? "avatar_cleared_local" : (client.avatarPreview == nil ? "avatar_none" : "avatar_local"))) {
                            UISelectionFeedbackGenerator().selectionChanged()
                            nicknamePresented = true
                        }
                        HStack {
                            Button(role: .destructive) {
                                UISelectionFeedbackGenerator().selectionChanged()
                                avatarClearPresented = true
                            } label: {
                                Text(L10n.string("avatar_remove"))
                                    .font(.body)
                                    .frame(minWidth: 48, minHeight: 48, alignment: .leading)
                                    .contentShape(Rectangle())
                            }
                                .buttonStyle(.plain)
                                .foregroundStyle(Palette.disconnect)
                                .opacity(client.avatarClearingLocally || client.avatarStatus == "clearing" ? 0.38 : 1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .disabled(client.avatarClearingLocally || client.avatarStatus == "clearing")
                                .avatarClearConfirmation(isPresented: $avatarClearPresented, connected: client.connected, clear: client.clearAvatar)
                            Spacer(minLength: 12)
                            Button(L10n.string(client.avatarPreview == nil ? "avatar_choose" : "avatar_change")) {
                                UISelectionFeedbackGenerator().selectionChanged()
                                avatarSelection = AvatarSelectionRequest(id: client.beginAvatarSelection())
                            }
                            .buttonStyle(.plain).foregroundStyle(Palette.accent)
                            .frame(minWidth: 48, minHeight: 48, alignment: .trailing)
                            .accessibilityHint(L10n.string("avatar_choose_hint"))
                        }
                        if let detail = client.avatarCleanupDetail { Text(detail).font(.footnote).foregroundStyle(Palette.disconnect) }
                        if ["checking", "uploading", "clearing", "failed", "clear_failed", "clear_save_failed"].contains(client.avatarStatus) {
                            Text(L10n.string("avatar_\(client.avatarStatus)")
                                 + (["failed", "clear_failed", "clear_save_failed"].contains(client.avatarStatus) ? client.avatarDetail.map { ": \($0)" } ?? "" : ""))
                                .font(.footnote)
                                .foregroundStyle(["failed", "clear_failed", "clear_save_failed"].contains(client.avatarStatus) ? Palette.disconnect : Palette.muted)
                        }
                    }
                    .avatarSelectionSheet(selection: $avatarSelection)
                    .sheet(isPresented: $nicknamePresented) { NicknameEditor(client: client) }
                    Toggle(L10n.string("settings_microphone"), isOn: Binding(get: { !client.microphoneMuted }, set: { value in Task { await client.setAudio(input: !value) } })).disabled(client.audioBusy || client.deafened)
                    Toggle(L10n.string("settings_listening"), isOn: Binding(get: { !client.deafened }, set: { value in Task { await client.setAudio(output: !value) } })).disabled(client.audioBusy)
                    VStack(alignment: .leading, spacing: 4) {
                        Toggle(L10n.string("settings_receive_agc"), isOn: Binding(get: { client.receiveAgc }, set: client.setReceiveAgc))
                            .accessibilityIdentifier("receive-agc")
                        Text(L10n.string("settings_receive_agc_help")).font(.footnote).foregroundStyle(Palette.muted)
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L10n.string("settings_speech_mode"))
                        VStack(spacing: 0) {
                            ForEach(SpeechMode.allCases, id: \.self) { mode in
                                if mode != SpeechMode.allCases.first {
                                    Rectangle().fill(Palette.border).frame(height: 1).padding(.leading, 16)
                                }
                                Button {
                                    guard mode != client.speech.mode else { return }
                                    UISelectionFeedbackGenerator().selectionChanged()
                                    client.setSpeechMode(mode)
                                } label: {
                                    HStack {
                                        Text(mode.title)
                                        Spacer()
                                        Image(systemName: client.speech.mode == mode ? "largecircle.fill.circle" : "circle")
                                            .font(.system(size: 22))
                                            .foregroundStyle(client.speech.mode == mode ? Palette.accent : Palette.muted)
                                            .accessibilityHidden(true)
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 56).padding(.horizontal, 16).contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityValue(L10n.string(client.speech.mode == mode ? "selection_selected" : "selection_not_selected"))
                                .accessibilityIdentifier("speech-mode-\(mode.rawValue)")
                            }
                        }
                        .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L10n.string("settings_noise_suppression"))
                        VStack(spacing: 0) {
                            ForEach(NoiseSuppressionMode.allCases, id: \.self) { mode in
                                if mode != NoiseSuppressionMode.allCases.first {
                                    Rectangle().fill(Palette.border).frame(height: 1).padding(.leading, 16)
                                }
                                Button {
                                    guard mode != client.noiseSuppression else { return }
                                    UISelectionFeedbackGenerator().selectionChanged()
                                    client.setNoiseSuppression(mode)
                                } label: {
                                    HStack {
                                        Text(mode.title)
                                        Spacer()
                                        Image(systemName: client.noiseSuppression == mode ? "largecircle.fill.circle" : "circle")
                                            .font(.system(size: 22))
                                            .foregroundStyle(client.noiseSuppression == mode ? Palette.accent : Palette.muted)
                                            .accessibilityHidden(true)
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 56)
                                    .padding(.horizontal, 16)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityValue(L10n.string(client.noiseSuppression == mode ? "selection_selected" : "selection_not_selected"))
                            }
                        }
                        .background(Palette.card)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                        if client.audioProcessingStatus == "switching" { Text(L10n.string("settings_audio_switching")).foregroundStyle(Palette.muted) }
                        if let error = client.audioProcessingError { Text(error).font(.footnote).foregroundStyle(Palette.disconnect) }
                    }
                    NavigationLink(destination: SettingsDetailPage(page: .language)) {
                        SettingsEntry(title: L10n.string("settings_language"), value: L10n.string(language.selection.titleKey))
                    }.buttonStyle(.plain)
                        .accessibilityLabel(L10n.string("settings_language"))
                        .accessibilityValue(L10n.string(language.selection.titleKey))
                        .accessibilityHint(L10n.string("accessibility_choose_language"))
                    FileCacheSettings(client: client)
                    NavigationLink(destination: SettingsDetailPage(page: .about)) {
                        SettingsEntry(title: L10n.string("settings_about"))
                    }.buttonStyle(.plain)

                }.padding(.horizontal, 20)
            }.padding(.top, PageTitle.inset).padding(.bottom, 20)
        }
    }
    private func voiceDrawer(availableHeight: CGFloat) -> some View {
        let travel = Self.drawerTravel(availableHeight: availableHeight)
        return VoiceDrawerHost(
            content: AnyView(VStack(spacing: 0) {
                VStack(spacing: 0) {
                    Color.clear.frame(height: 16)
                    voiceBar
                }
                .overlay(alignment: .top) {
                    Button {
                        if !drawerBlocksClick { setDrawerExpanded(!drawerExpanded) }
                    } label: {
                        Image(decorative: "Icon-drawer-handle").renderingMode(.template).resizable().scaledToFit()
                            .frame(width: 44, height: 4).foregroundStyle(client.connected ? Color(hex: 0xF2F3F5) : Palette.muted)
                            .padding(.top, 10).frame(width: 44, height: 44, alignment: .top).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).disabled(!client.connected || travel == 0)
                    .accessibilityLabel(L10n.string(drawerExpanded && client.connected ? "voice_drawer_collapse" : "voice_drawer_expand"))
                    .accessibilityValue(L10n.string(drawerExpanded && client.connected ? "voice_drawer_expanded" : "voice_drawer_collapsed"))
                }
                DrawerNavigationHost(
                    page: !client.connected ? .home : userVolumesOpen ? .volumes : client.channelFiles.open ? .files : .home,
                    home: drawerPage(drawerTools),
                    detail: drawerPage(Group {
                        if userVolumesOpen { UserVolumesDrawer(client: client, presentation: client.state, onBack: { userVolumesOpen = false }) }
                        else { ChannelFilesDrawer(client: client, presentation: client.channelFiles, presentationError: client.channelFilesLocalError) }
                    }),
                    reduceMotion: reduceMotion)
                    .frame(maxWidth: .infinity, minHeight: 0, maxHeight: .infinity, alignment: .top).clipped()
                    .accessibilityHidden(!drawerExpanded || !client.connected)
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .foregroundStyle(Color(hex: 0xF2F3F5)).background(Palette.bottom)
                .environment(\.sizeCategory, sizeCategory).environment(\.locale, language.locale)
                .tint(Palette.accent).ignoresSafeArea(.container)),
            connected: client.connected, expanded: drawerExpanded, travel: travel, reduceMotion: reduceMotion,
            onExpanded: setDrawerExpanded, onTouch: { drawerBlocksClick = $0 })
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
    private func drawerPage<Content: View>(_ content: Content) -> AnyView {
        AnyView(content.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .foregroundStyle(Color(hex: 0xF2F3F5)).background(Palette.bottom)
            .environment(\.sizeCategory, sizeCategory).environment(\.locale, language.locale)
            .tint(Palette.accent).ignoresSafeArea(.container))
    }
    private var drawerTools: some View {
        GeometryReader { viewport in
            Group {
                if networkPanelHeight > 0 && viewport.size.height - networkPanelHeight < 96 {
                    ScrollView {
                        VStack(spacing: 0) {
                            NetworkQualityPanel(quality: client.state.networkQuality ?? NetworkQuality()).fixedSize(horizontal: false, vertical: true)
                            drawerToolCards
                        }
                    }
                } else {
                    VStack(spacing: 0) {
                        NetworkQualityPanel(quality: client.state.networkQuality ?? NetworkQuality()).fixedSize(horizontal: false, vertical: true)
                        ScrollView { drawerToolCards }
                    }
                }
            }
            .onPreferenceChange(NetworkPanelHeightKey.self) { height in
                if height > 0 && abs(networkPanelHeight - height) > 0.5 { networkPanelHeight = height }
            }
        }
    }
    private var drawerToolCards: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.string("volume_tools")).font(.caption).foregroundStyle(Palette.muted)
            Button { userVolumesOpen = true } label: {
                HStack(spacing: 14) {
                    AppIcon(name: "speaker-on", scaledSize: 26).foregroundStyle(Palette.accent)
                    Text(L10n.string("volume_title")).foregroundStyle(.white).frame(maxWidth: .infinity, alignment: .leading)
                    AppIcon(name: "chevron-right", size: 18).foregroundStyle(Palette.muted)
                }.padding(16).frame(minHeight: 72).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
            }.buttonStyle(.plain).disabled(!client.connected || client.state.ownClient == nil)
            .accessibilityIdentifier("user-volumes-tool")
            Text(L10n.string("files_tools")).font(.caption).foregroundStyle(Palette.muted)
            Button { client.openChannelFiles() } label: {
                HStack(spacing: 14) {
                    AppIcon(name: "folder", scaledSize: 26).foregroundStyle(Palette.accent)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(L10n.string("files_title")).foregroundStyle(.white)
                        Text(L10n.string("files_subtitle")).font(.caption).foregroundStyle(Palette.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    AppIcon(name: "chevron-right", size: 18).foregroundStyle(Palette.muted)
                }.padding(16).frame(minHeight: 72).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
            }.buttonStyle(.plain).disabled(!client.connected || client.state.ownClient == nil)
            .accessibilityIdentifier("channel-files-tool")
        }.padding(16).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
    private func setDrawerExpanded(_ expanded: Bool) {
        guard client.connected, expanded != drawerExpanded else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        drawerExpanded = expanded
    }
    static func drawerTravel(availableHeight: CGFloat) -> CGFloat {
        // Reserve a usable scroll area below the header, including native container insets.
        max(0, min(availableHeight, max(availableHeight * 7 / 10, 64 + 48)) - 64)
    }
    static func drawerShouldExpand(height: CGFloat, travel: CGFloat, velocity: CGFloat) -> Bool {
        travel > 0 && min(travel, max(0, height)) - velocity * 0.2 > travel / 2
    }
    static func drawerOffset(_ raw: CGFloat, travel: CGFloat, limit: CGFloat = 16) -> CGFloat {
        let clamped = min(travel, max(0, raw))
        let overflow = raw - clamped
        return limit > 0 ? clamped + overflow * 0.35 / (1 + abs(overflow) * 0.35 / limit) : clamped
    }
    static func drawerRawOffset(_ offset: CGFloat, travel: CGFloat, limit: CGFloat) -> CGFloat {
        let clamped = min(travel, max(0, offset))
        let overflow = offset - clamped
        return limit > 0 ? clamped + overflow / (0.35 * (1 - min(0.999, abs(overflow) / limit))) : clamped
    }
    private var voiceBar: some View {
        let speaking = client.connected && client.state.clients.first { $0.id == client.state.ownClient }?.speaking == true
        return HStack(spacing: 8) {
            HStack(spacing: 8) {
                AppIcon(name: "waveform", scaledSize: 18).foregroundStyle(client.connected ? client.state.networkQuality?.iconGrade?.color ?? Palette.muted : Palette.muted)
                Text(client.connected ? L10n.format("status_connected_to_channel", client.state.channels.first { $0.id == client.currentChannel }?.name ?? "") : client.busy ? L10n.string("status_connecting") : L10n.string("status_disconnected")).font(.system(size: 12, weight: .semibold)).lineLimit(1)
                Spacer(minLength: 0)
            }.padding(.leading, 12).frame(maxWidth: .infinity).frame(height: 48)
            Button {
                guard !drawerBlocksClick else { return }
                UISelectionFeedbackGenerator().selectionChanged()
                Task { await client.setAudio(input: !client.microphoneMuted) }
            } label: {
                AppIcon(name: client.muted ? "mic-off" : "mic-on", scaledSize: 20).foregroundStyle(client.muted ? Palette.muted : speaking ? Palette.green : Color(hex: 0xF2F3F5)).frame(width: 44, height: 48).contentShape(Rectangle())
            }.accessibilityLabel(L10n.string(client.microphoneMuted ? "voice_enable_microphone" : "voice_mute")).disabled(client.audioBusy || client.deafened)
            Button {
                guard !drawerBlocksClick else { return }
                UISelectionFeedbackGenerator().selectionChanged()
                Task { await client.setAudio(output: !client.deafened) }
            } label: {
                AppIcon(name: client.deafened ? "speaker-off" : "speaker-on", scaledSize: 22).foregroundStyle(client.deafened ? Palette.muted : Color(hex: 0xF2F3F5)).frame(width: 44, height: 48).contentShape(Rectangle())
            }.accessibilityLabel(L10n.string(client.deafened ? "voice_enable_listening" : "voice_disable_listening")).disabled(client.audioBusy)
        }.padding(.trailing, 8).background(Palette.bottom)
            .buttonStyle(VoiceAudioButtonStyle(dragging: drawerBlocksClick, reduceMotion: reduceMotion))
    }
    private func navigation(_ title: String, _ icon: String, _ index: Int) -> some View {
        Button { tab = index } label: {
            VStack(spacing: 5) {
                AppIcon(name: icon, size: 22)
                    .overlay(alignment: .topTrailing) {
                        UnreadBadge(count: index == 0 ? client.currentChannelUnread : index == 1 ? client.privateUnreadTotal : 0)
                            .offset(x: 8, y: -7)
                    }
                Text(title).font(.system(size: 12, weight: .bold))
            }
                .frame(maxWidth: .infinity).frame(minHeight: 44).foregroundStyle(tab == index ? Palette.accent : Palette.muted)
        }
    }
    private func join(_ channel: Channel) {
        guard channel.id != client.currentChannel else { return }
        if channel.password { channelPassword = ""; lockedChannel = channel } else { client.join(channel) }
    }

    private func manageChannel(_ action: ChannelManagementMenu.Action, id: UInt64, target: ServerChatTarget) {
        guard channelSheet.selected?.id == id, client.state.serverChatTarget == target,
              let channel = client.state.channels.first(where: { $0.id == id }) else { return }
        let selection = ChannelEditSelection(channel: channel, target: target)
        switch action {
        case .createChild:
            childChannelCreation = ChannelCreation(target: target, parent: channel)
        case .edit:
            if channel.canEdit != false { editingChannel = selection }
        case .move:
            movingChannel = selection
        case .delete:
            guard channel.canDelete != false else { return }
            if channelHasMembersOrChildren(id) {
                channelDeleteError = L10n.string("error_channel_delete_not_empty")
            } else { deletingChannel = selection }
        }
    }

    private func channelHasMembersOrChildren(_ id: UInt64) -> Bool {
        client.state.clients.contains { $0.channel == id } || client.state.channels.contains { $0.parent == id }
    }

    private func deleteChannel(_ selection: ChannelEditSelection) {
        let id = selection.channel.id
        guard !channelHasMembersOrChildren(id) else {
            channelDeleteError = L10n.string("error_channel_delete_not_empty")
            return
        }
        let request = UUID().uuidString
        do {
            try client.channelCommand("delete", target: selection.target, channel: id, request: request)
            channelDeleteRequest = request
        } catch {
            channelDeleteError = LocalizedNotice.source(of: error)?.text ?? error.localizedDescription
        }
    }
    private func showChannel(_ channel: Channel) {
        if !channelSheet.active {
            channelMemberListHeight = 52
            channelMemberListPanning = false
        }
        channelSheet.select(channel)
    }
    static func channelSheetLayout(memberHeight: CGFloat, viewportHeight: CGFloat) -> (sheet: CGFloat, list: CGFloat) {
        let chrome: CGFloat = 172 // 44pt header + 56pt action + padding and spacing.
        let maximum = max(chrome + 52, viewportHeight * 0.78)
        let list = min(max(52, memberHeight), maximum - chrome)
        return (chrome + list, list)
    }
}

struct ChannelManagementMenu: UIViewRepresentable {
    enum Action: Equatable { case createChild, edit, move, delete }

    struct Items: Equatable {
        let target: ServerChatTarget
        let channelID: UInt64
        let canEdit: Bool
        let canDelete: Bool
        let language: AppLanguage
    }

    let items: Items
    let onAction: (Action) -> Void
    @ScaledMetric private var iconSize: CGFloat = 24

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> UIButton {
        let button = UIButton(type: .system)
        button.tintColor = UIColor(Palette.accent)
        button.showsMenuAsPrimaryAction = true
        return button
    }

    func updateUIView(_ button: UIButton, context: Context) {
        context.coordinator.update(button, items: items, iconSize: iconSize, onAction: onAction)
    }

    @MainActor final class Coordinator {
        private var displayed: Items?
        private var displayedIconSize: CGFloat?
        private var onAction: ((Action) -> Void)?

        func update(_ button: UIButton, items: Items, iconSize: CGFloat, onAction: @escaping (Action) -> Void) {
            self.onAction = onAction
            if displayedIconSize != iconSize, let image = UIImage(named: "Icon-more") {
                let size = CGSize(width: iconSize, height: iconSize)
                let scaled = UIGraphicsImageRenderer(size: size).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
                button.setImage(scaled.withRenderingMode(.alwaysTemplate), for: .normal)
                displayedIconSize = iconSize
            }

            // Reassigning the system menu while it is visible can animate its rows again.
            guard displayed != items else { return }
            displayed = items
            button.accessibilityLabel = L10n.string("channel_manage")
            func action(_ kind: Action, _ title: String, _ icon: String, destructive: Bool = false) -> UIAction {
                let tint = destructive ? Palette.menuDestructive : UIColor(Palette.accent)
                let image = UIImage(named: "Icon-" + icon)?.withTintColor(tint, renderingMode: .alwaysOriginal)
                return UIAction(title: L10n.string(title), image: image, attributes: destructive ? .destructive : []) { [weak self] _ in
                    if let self, self.displayed == items {
                        self.onAction?(kind)
                    }
                }
            }
            var actions = [action(.createChild, "channel_create_child", "plus")]
            if items.canEdit { actions.append(action(.edit, "channel_edit_title", "pencil")) }
            actions.append(action(.move, "channel_move_title", "sort"))
            if items.canDelete { actions.append(action(.delete, "channel_delete_title", "trash", destructive: true)) }
            button.menu = UIMenu(children: actions)
        }
    }
}

struct ChannelEditSelection: Identifiable {
    let channel: Channel
    let target: ServerChatTarget
    var id: UInt64 { channel.id }
}

struct ChannelCreation: Identifiable {
    let target: ServerChatTarget
    let parent: Channel?
    let id = UUID()
}

private struct ChannelFormChoice: View {
    let title: String
    let prefix: String
    @Binding var selection: String
    let options: [String]

    private static let checkmark = UIImage(named: "Icon-checkmark").map { source in
        let size = CGSize(width: 18, height: 18)
        return UIGraphicsImageRenderer(size: size).image { _ in
            source.withRenderingMode(.alwaysOriginal).draw(in: CGRect(origin: .zero, size: size))
        }
    }

    var body: some View {
        Menu {
            ForEach(options, id: \.self) { option in
                let label = L10n.string(prefix + option)
                let selected = option == selection
                Button { selection = option } label: {
                    Label { Text(label) } icon: {
                        if let image = Self.checkmark {
                            Image(uiImage: image.withTintColor(selected ? UIColor(Palette.text) : .clear,
                                                               renderingMode: .alwaysOriginal))
                        }
                    }
                }.accessibilityAddTraits(selected ? .isSelected : [])
            }
        } label: {
            HStack(spacing: 6) {
                Text(L10n.string(title)).foregroundStyle(Palette.text)
                Spacer(minLength: 12)
                Text(L10n.string(prefix + selection)).multilineTextAlignment(.trailing)
                AppIcon(name: "chevrons-up-down", size: 14).accessibilityHidden(true)
            }
            .foregroundStyle(Palette.accent)
            .frame(minHeight: 22)
            .contentShape(Rectangle())
        }
        .menuIndicator(.hidden)
        .accessibilityLabel(L10n.string(title))
        .accessibilityValue(L10n.string(prefix + selection))
    }
}

struct ChannelCreateView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var client: Client
    let selection: ChannelCreation
    let onCreated: (UInt64) -> Void
    @State private var name = ""
    @State private var password = ""
    @State private var type = "temporary"
    @State private var position = "bottom"
    @State private var request: String?
    @State private var error: String?

    private var nameValid: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField(L10n.string("channel_edit_name"), text: $name)
                        .accessibilityLabel(L10n.string("channel_edit_name"))
                    SecureField(L10n.string("channel_password"), text: $password)
                        .textContentType(.oneTimeCode).autocorrectionDisabled()
                    ChannelFormChoice(title: "channel_edit_type", prefix: "channel_edit_", selection: $type,
                                      options: ["temporary", "semi_permanent", "permanent"])
                    if selection.parent == nil {
                        ChannelFormChoice(title: "channel_position", prefix: "channel_position_", selection: $position,
                                          options: ["top", "bottom"])
                    }
                } header: {
                    if let parent = selection.parent { Text(L10n.format("channel_create_under", parent.name)) }
                }.disabled(request != nil)
                Section {
                    if let error { Text(error).foregroundStyle(Palette.disconnect) }
                    Button { save() } label: {
                        if request != nil { ProgressView().accessibilityLabel(L10n.string("channel_create_action")) }
                        else { Text(L10n.string("channel_create_action")) }
                    }.disabled(request != nil || !nameValid)
                }
            }
            .navigationTitle(L10n.string(selection.parent == nil ? "channel_create_title" : "channel_create_child"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("action_cancel")) { dismiss() }.disabled(request != nil)
                }
            }
        }.navigationViewStyle(.stack)
            .interactiveDismissDisabled(request != nil)
            .onChange(of: client.channelCommandResults["create"]) { result in
                guard let result, result.requestId == request else { return }
                request = nil
                if let id = result.channel, result.error == nil { onCreated(id) }
                else { error = L10n.coreError(result.error ?? "channel_create_failed", detail: result.detail) }
            }
    }

    private func save() {
        var changes = ["name": name, "password": password, "channel_type": type]
        if selection.parent == nil { changes["position"] = position }
        let id = UUID().uuidString
        do {
            try client.channelCommand("create", target: selection.target, channel: selection.parent?.id ?? 0,
                                      request: id, changes: changes)
            request = id; error = nil
        } catch {
            self.error = LocalizedNotice.source(of: error)?.text ?? error.localizedDescription
        }
    }
}

struct ChannelMoveView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var client: Client
    let selection: ChannelEditSelection
    @State private var original: Channel
    @State private var parent: UInt64
    @State private var siblings: [Channel]
    @State private var preview: [Channel]
    @State private var rowFrames: [UInt64: CGRect] = [:]
    @GestureState private var gestureActive = false
    @State private var drag: ChannelMoveDrag?
    @State private var choosingParent = false
    @State private var request: String?
    @State private var error: String?

    init(client: Client, selection: ChannelEditSelection) {
        self.client = client; self.selection = selection
        let original = selection.channel
        let rows = client.orderedChannels.map(\.channel).filter { $0.parent == original.parent }
        _original = State(initialValue: original)
        _parent = State(initialValue: original.parent)
        _siblings = State(initialValue: rows.filter { $0.id != original.id })
        _preview = State(initialValue: rows)
    }

    private var index: Int? { preview.firstIndex { $0.id == original.id } }
    private var order: UInt64 {
        guard let index, index > 0 else { return 0 }
        return preview[index - 1].id
    }
    private var canSave: Bool {
        request == nil && drag == nil && index != nil && (parent != original.parent || order != original.order)
    }
    private var reorderAnimation: Animation? {
        reduceMotion ? nil : .timingCurve(0, 0, 0.2, 1, duration: 0.18)
    }
    private var forbiddenParents: Set<UInt64> {
        let descendants = Dictionary(grouping: client.state.channels, by: \.parent)
        var visited: Set<UInt64> = []
        var pending = [original.id]
        while let channel = pending.popLast() {
            if visited.insert(channel).inserted { pending.append(contentsOf: (descendants[channel] ?? []).map(\.id)) }
        }
        return visited
    }

    var body: some View {
        NavigationView {
            Group {
                if choosingParent {
                    List {
                        parentEntry(id: 0, name: L10n.string("channel_root_level"), depth: 0)
                        let excluded = forbiddenParents
                        ForEach(client.orderedChannels, id: \.channel.id) { entry in
                            if !excluded.contains(entry.channel.id), entry.channel.spacer == nil {
                                parentEntry(id: entry.channel.id, name: entry.channel.name, depth: entry.depth)
                            }
                        }
                    }
                } else {
                    ScrollViewReader { reader in
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                moveTarget
                                Text(L10n.string("channel_move_drag"))
                                    .font(.callout.weight(.semibold))
                                    .foregroundStyle(Palette.muted)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 24)
                                    .accessibilityAddTraits(.isHeader)
                                LazyVStack(spacing: 30) {
                                    ForEach(preview) { row in
                                        positionedRow(row)
                                    }
                                }
                                .padding(.horizontal, 16)
                            }
                            .padding(.horizontal, 16)
                            .padding(.top, 32)
                            .padding(.bottom, 16)
                        }
                            .background(Color(uiColor: .systemGroupedBackground))
                            .overlay { dragOverlay(reader) }
                            .onPreferenceChange(ChannelMoveFrames.self) { rowFrames = $0 }
                            .onChange(of: gestureActive) { active in
                                if !active, let startingRows = drag?.startingRows {
                                    withAnimation(reorderAnimation) { preview = startingRows }
                                    drag = nil
                                }
                            }
                            .safeAreaInset(edge: .bottom, spacing: 0) { saveBar }
                            .task(id: parent) {
                                guard index != nil else { return }
                                await Task.yield()
                                guard !Task.isCancelled else { return }
                                reader.scrollTo(original.id)
                            }
                    }
                }
            }
            .navigationTitle(L10n.string(choosingParent ? "channel_choose_parent" : "channel_move_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("action_cancel")) {
                        if choosingParent { choosingParent = false } else { dismiss() }
                    }.disabled(request != nil)
                }
            }
        }.navigationViewStyle(.stack)
            .interactiveDismissDisabled(request != nil)
            .onChange(of: client.channelCommandResults["move"]) { result in
                guard let result, result.requestId == request else { return }
                request = nil
                if let code = result.error {
                    error = L10n.coreError(code, detail: result.detail)
                    if code == "channel_position_changed", let latest = client.state.channels.first(where: { $0.id == original.id }) {
                        original = latest
                        setParent(client.state.channels.contains { $0.id == parent } || parent == 0 ? parent : latest.parent)
                    }
                } else { dismiss() }
            }
    }

    private var moveTarget: some View {
        Button { choosingParent = true } label: {
            HStack {
                Text(L10n.string("channel_move_target")).foregroundStyle(Palette.text)
                Spacer()
                Text(parent == 0 ? L10n.string("channel_as_root") :
                    L10n.format("channel_as_child", client.state.channels.first { $0.id == parent }?.name ?? ""))
                    .multilineTextAlignment(.trailing)
                AppIcon(name: "chevron-right", scaledSize: 14).accessibilityHidden(true)
            }.foregroundStyle(Palette.accent)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(minHeight: 72)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24))
        }.buttonStyle(.plain)
            .disabled(request != nil || drag != nil)
            .accessibilityIdentifier("channel-move-target")
    }

    @ViewBuilder private func positionedRow(_ row: Channel) -> some View {
        let selected = row.id == original.id
        let card = moveRow(row)
            .opacity(selected && drag != nil ? 0 : 1)
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: ChannelMoveFrames.self,
                        value: [row.id: geometry.frame(in: .global)])
                }
            }
        if selected && request == nil && drag == nil {
            card.accessibilityElement(children: .combine)
                .accessibilityAction(named: Text(L10n.string("channel_move_up"))) {
                    if let index, index > 0 { move(preview[index - 1].id, after: false) }
                }
                .accessibilityAction(named: Text(L10n.string("channel_move_down"))) {
                    if let index, index + 1 < preview.count { move(preview[index + 1].id, after: true) }
                }
        } else { card }
    }

    private func moveRow(_ row: Channel) -> some View {
        let selected = row.id == original.id
        return HStack(spacing: 0) {
            HStack(spacing: 10) {
                ChannelIcon(channel: row)
                Text(row.spacer.flatMap { $0.text.isEmpty ? nil : $0.text } ?? row.name)
                    .foregroundStyle(Palette.text)
                    .lineLimit(2)
                Spacer(minLength: 0)
            }.padding(.horizontal, 12).frame(minHeight: 48)
                .background(selected ? Palette.selected : Palette.card)
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? Palette.accent : Palette.border))
            if selected && request == nil {
                AppIcon(name: "channel-reorder-handle", size: 22)
                    .foregroundStyle(Color(hex: 0x49494B))
                    .frame(width: 32)
                    .accessibilityHidden(true)
            }
        }
    }

    private func dragOverlay(_ reader: ScrollViewProxy) -> some View {
        GeometryReader { geometry in
            let bounds = geometry.frame(in: .global)
            if let frame = drag?.frame ?? rowFrames[original.id], request == nil, drag != nil || frame.intersects(bounds) {
                if let drag {
                    moveRow(original).frame(width: frame.width, height: frame.height)
                        .position(x: frame.midX - bounds.minX, y: drag.centerY - bounds.minY)
                        .transaction { $0.animation = nil }
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                // Keep the gesture attached to the viewport when the source row scrolls offscreen.
                Color.clear.contentShape(Rectangle()).frame(width: 44, height: frame.height)
                    .position(x: frame.maxX - 22 - bounds.minX, y: frame.midY - bounds.minY)
                    .gesture(DragGesture(minimumDistance: 4, coordinateSpace: .global)
                        .updating($gestureActive) { _, active, _ in active = true }
                        .onChanged { value in
                            var current = drag ?? ChannelMoveDrag(frame: frame, startingRows: preview)
                            current.translation = value.translation.height
                            drag = current
                            place(at: current.centerY, within: bounds)
                        }
                        .onEnded { value in
                            if let drag { place(at: drag.frame.midY + value.translation.height, within: bounds) }
                            drag = nil
                        })
                    .accessibilityHidden(true)
                    .task(id: drag != nil) {
                        while let drag, !Task.isCancelled {
                            let rows = visibleRows(in: bounds)
                            let scroll = edgeScroll(at: drag.centerY - bounds.minY, height: bounds.height,
                                                    first: rows.first?.id, last: rows.last?.id)
                            if let scroll {
                                withAnimation(reduceMotion ? nil : .linear(duration: 0.16)) {
                                    reader.scrollTo(scroll.id, anchor: scroll.down ? .bottom : .top)
                                }
                            }
                            try? await Task.sleep(nanoseconds: 180_000_000)
                            if scroll != nil, !Task.isCancelled, let current = self.drag {
                                place(at: current.centerY, within: bounds)
                            }
                        }
                    }
            }
        }.clipped()
    }

    private func visibleRows(in bounds: CGRect) -> [(id: UInt64, frame: CGRect)] {
        preview.compactMap { row in
            guard let frame = rowFrames[row.id], frame.intersects(bounds) else { return nil }
            return (row.id, frame)
        }
    }

    private func place(at y: CGFloat, within bounds: CGRect) {
        guard y >= bounds.minY, y <= bounds.maxY else { return }
        let siblings = visibleRows(in: bounds).filter { $0.id != original.id }
        if let next = siblings.first(where: { y < $0.frame.midY }) {
            move(next.id, after: false)
        } else if let last = siblings.last {
            move(last.id, after: true)
        }
    }

    private func edgeScroll(at y: CGFloat, height: CGFloat, first: UInt64?, last: UInt64?) -> (id: UInt64, down: Bool)? {
        if y >= 0, y < 50, let first, let index = preview.firstIndex(where: { $0.id == first }), index > 0 {
            return (preview[index - 1].id, false)
        }
        if y <= height, y > height - 50, let last, let index = preview.firstIndex(where: { $0.id == last }), index + 1 < preview.count {
            return (preview[index + 1].id, true)
        }
        return nil
    }

    private func move(_ sibling: UInt64, after: Bool) {
        guard request == nil, let index, let target = preview.firstIndex(where: { $0.id == sibling }) else { return }
        let insertion = target + (after ? 1 : 0)
        guard insertion != index, insertion != index + 1 else { return }
        withAnimation(reorderAnimation) {
            preview.move(fromOffsets: IndexSet(integer: index), toOffset: insertion)
        }
    }

    private var saveBar: some View {
        let submitting = request != nil
        return VStack(alignment: .leading, spacing: 10) {
            if let error { Text(error).foregroundStyle(Palette.disconnect) }
            Button { save() } label: {
                ZStack {
                    Text(L10n.string("channel_move_save")).opacity(submitting ? 0 : 1)
                    if submitting { ProgressView().tint(Palette.text).accessibilityHidden(true) }
                }
            }.buttonStyle(ChannelMoveSaveStyle(submitting: submitting))
                .accessibilityLabel(L10n.string("channel_move_save"))
                .disabled(!canSave)
        }.padding(.horizontal, 16).padding(.vertical, 8)
            .background(Color(uiColor: .systemGroupedBackground))
    }

    private func parentEntry(id: UInt64, name: String, depth: Int) -> some View {
        Button { setParent(id); choosingParent = false } label: {
            HStack {
                Text(name).foregroundStyle(Palette.text)
                Spacer()
                if id == parent { AppIcon(name: "checkmark", scaledSize: 18).accessibilityHidden(true) }
            }.padding(.leading, CGFloat(min(depth, 4)) * 10).frame(minHeight: 40)
        }.accessibilityAddTraits(id == parent ? .isSelected : [])
            .accessibilityIdentifier("channel-parent-\(id)")
    }

    private func setParent(_ id: UInt64) {
        if id == parent, !preview.isEmpty, choosingParent { return }
        parent = id
        let rows = client.orderedChannels.map(\.channel).filter { $0.parent == id }
        siblings = rows.filter { $0.id != original.id }
        preview = id == original.parent ? rows : siblings + [original]
    }

    private func save() {
        let id = UUID().uuidString
        let changes: [String: Any] = [
            "parent": parent, "order": order, "original_parent": original.parent, "original_order": original.order,
            "siblings": siblings.map { ["id": $0.id, "order": $0.order] },
        ]
        do {
            try client.channelCommand("move", target: selection.target, channel: original.id, request: id, changes: changes)
            request = id; error = nil
        } catch {
            self.error = LocalizedNotice.source(of: error)?.text ?? error.localizedDescription
        }
    }
}

private struct ChannelMoveFrames: PreferenceKey {
    static let defaultValue: [UInt64: CGRect] = [:]
    static func reduce(value: inout [UInt64: CGRect], nextValue: () -> [UInt64: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { _, newest in newest })
    }
}

private struct ChannelMoveDrag {
    let frame: CGRect
    let startingRows: [Channel]
    var translation: CGFloat = 0
    var centerY: CGFloat { frame.midY + translation }
}

private struct ChannelMoveSaveStyle: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let submitting: Bool

    func makeBody(configuration: Configuration) -> some View {
        let emphasized = enabled || submitting
        let recovery = Animation.timingCurve(0, 0, 0.2, 1, duration: 0.35)
        configuration.label.font(.headline).frame(maxWidth: .infinity, minHeight: 68)
            .foregroundStyle(emphasized ? Palette.text : Palette.muted)
            .background(emphasized ? Palette.accent : Palette.card, in: RoundedRectangle(cornerRadius: 14))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .animation(configuration.isPressed || reduceMotion ? nil : recovery, value: configuration.isPressed)
    }
}

struct ChannelEditView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var client: Client
    let selection: ChannelEditSelection
    @State private var name: String
    @State private var topic: String
    @State private var description: String
    @State private var originalDescription: String?
    @State private var passwordMode = "keep"
    @State private var password = ""
    @State private var limitMode: String
    @State private var limit: String
    @State private var type: String
    @State private var request: String?
    @State private var error: String?

    init(client: Client, selection: ChannelEditSelection) {
        self.client = client
        self.selection = selection
        let channel = selection.channel
        _name = State(initialValue: channel.name)
        _topic = State(initialValue: channel.topic ?? "")
        _description = State(initialValue: channel.description ?? "")
        _originalDescription = State(initialValue: channel.description)
        _limitMode = State(initialValue: channel.maxClients?.mode ?? "unknown")
        _limit = State(initialValue: channel.maxClients?.value.map(String.init) ?? "")
        _type = State(initialValue: channel.channelType ?? "unknown")
    }

    private var latestDescription: String? {
        client.state.channels.first { $0.id == selection.channel.id }?.description
    }

    private var changes: [String: Any] {
        let channel = selection.channel
        var values: [String: Any] = [:]
        if name != channel.name { values["name"] = name }
        if topic != (channel.topic ?? "") { values["topic"] = topic }
        if let originalDescription, description != originalDescription { values["description"] = description }
        if passwordMode == "set" { values["password"] = ["mode": "set", "value": password] }
        if passwordMode == "remove" { values["password"] = ["mode": "remove"] }
        if limitMode == "unlimited", channel.maxClients?.mode != "unlimited" {
            values["max_clients"] = ["mode": "unlimited"]
        }
        if limitMode == "limited", let count = UInt16(limit),
           channel.maxClients?.mode != "limited" || Int(count) != channel.maxClients?.value {
            values["max_clients"] = ["mode": "limited", "value": count] as [String: Any]
        }
        if type != "unknown", type != channel.channelType { values["channel_type"] = type }
        return values
    }

    private var valid: Bool {
        !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (limitMode != "limited" || UInt16(limit) != nil)
            && (passwordMode != "set" || !password.isEmpty)
    }

    var body: some View {
        NavigationView {
            Form {
                Section(selection.channel.name) {
                    field("channel_edit_name") {
                        TextField("", text: $name).accessibilityLabel(L10n.string("channel_edit_name"))
                    }
                    field("channel_edit_topic") {
                        TextField("", text: $topic).accessibilityLabel(L10n.string("channel_edit_topic"))
                    }
                    field("channel_edit_description") {
                        if originalDescription != nil {
                            TextEditor(text: $description)
                                .frame(minHeight: 96)
                                .accessibilityLabel(L10n.string("channel_edit_description"))
                        } else if client.channelCommandResults["description"]?.error != nil {
                            Text(L10n.string("error_channel_description_failed")).font(.footnote).foregroundStyle(Palette.muted)
                        } else {
                            ProgressView()
                        }
                    }
                }.disabled(request != nil)
                Section {
                    ChannelFormChoice(title: "channel_password", prefix: "channel_edit_password_", selection: $passwordMode,
                                      options: ["keep", "set", "remove"])
                    if passwordMode == "set" {
                        SecureField(L10n.string("channel_edit_password_set"), text: $password)
                            .textContentType(.oneTimeCode).autocorrectionDisabled()
                    }
                    ChannelFormChoice(title: "channel_edit_max_clients", prefix: "channel_edit_", selection: $limitMode,
                                      options: (selection.channel.maxClients == nil ? ["unknown"] : []) + ["unlimited", "limited"])
                    if limitMode == "limited" {
                        TextField(L10n.string("channel_edit_max_clients"), text: $limit).keyboardType(.numberPad)
                    }
                    ChannelFormChoice(title: "channel_edit_type", prefix: "channel_edit_", selection: $type,
                                      options: (selection.channel.channelType == nil ? ["unknown"] : []) + ["temporary", "semi_permanent", "permanent"])
                }.disabled(request != nil)
                Section {
                    if let error { Text(error).foregroundStyle(Palette.disconnect) }
                    Button { save() } label: {
                        if request != nil { ProgressView().accessibilityLabel(L10n.string("action_save")) }
                        else { Text(L10n.string("action_save")) }
                    }.disabled(request != nil || !valid || changes.isEmpty)
                }
            }
            .navigationTitle(L10n.string("channel_edit_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.string("action_cancel")) { dismiss() }.disabled(request != nil)
                }
            }
        }
        .navigationViewStyle(.stack)
        .interactiveDismissDisabled(request != nil)
        .onChange(of: passwordMode) { if $0 != "set" { password = "" } }
        .onAppear { loadDescription(latestDescription) }
        .onChange(of: latestDescription, perform: loadDescription)
        .onChange(of: client.channelCommandResults["edit"]) { result in
            guard let result, result.requestId == request else { return }
            request = nil
            if let code = result.error { error = L10n.coreError(code, detail: result.detail) }
            else { dismiss() }
        }
    }

    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L10n.string(label)).font(.subheadline).foregroundStyle(Palette.muted)
            content()
        }
    }

    private func loadDescription(_ value: String?) {
        guard originalDescription == nil, let value else { return }
        originalDescription = value; description = value
    }

    private func save() {
        let id = UUID().uuidString
        do {
            try client.channelCommand("edit", target: selection.target, channel: selection.channel.id,
                                      request: id, changes: changes)
            error = nil; request = id
        } catch {
            self.error = LocalizedNotice.source(of: error)?.text ?? error.localizedDescription
        }
    }
}

struct PrivateChatView: View {
    @ObservedObject var client: Client
    let member: Member
    var body: some View {
        ChatView(client: client, title: member.name, conversation: member.conversation ?? "", member: member)
    }
}

struct ChannelChatView: View {
    @ObservedObject var client: Client
    let channel: Channel
    var body: some View {
        ChatView(client: client, title: channel.name, conversation: channel.conversation, channel: channel)
    }
}

struct ChatView: View {
    @ObservedObject var client: Client
    let title: String
    let conversation: String
    var member: Member? = nil
    var channel: Channel? = nil
    var serverChat: ServerChatTarget? = nil
    @State private var text = ""
    @State private var chatToken = UUID()
    @State private var openedServer: String?
    @State private var serverMessageRequests: Set<String> = []
    @State private var showServerPermissionAlert = false
    @Environment(\.dismiss) private var dismiss
    private var messages: [ChatMessage] { client.messages(in: conversation) }
    private var showsSenderName: Bool { channel != nil || serverChat != nil }
    private var available: Bool {
        guard client.connected else { return false }
        if let serverChat { return client.state.serverChatTarget == serverChat }
        if let channel { return client.currentChannel == channel.id }
        if let uid = member?.uid { return client.state.clients.contains { $0.uid == uid } }
        return false
    }
    var body: some View {
        let sendEnabled = available && !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(messages) { message in
                            messageRow(message).id(message.id)
                        }
                    }.padding(14)
                }
                .onChange(of: messages) { value in
                    if available && !serverMessageRequests.isEmpty {
                        let finished = value.filter { $0.status != .pending && serverMessageRequests.contains($0.id) }
                        serverMessageRequests.subtract(finished.map(\.id))
                        if finished.contains(where: \.serverPermissionDenied) { showServerPermissionAlert = true }
                    }
                    if let id = value.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                }
                .onAppear { if let id = messages.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
            }
            Divider()
            PushToTalkBar(client: client, showsTarget: true)
            HStack(alignment: .bottom, spacing: 10) {
                TextField(L10n.string(serverChat == nil ? "chat_input_placeholder" : "server_chat_input_placeholder"), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                Button(action: send) {
                    AppIcon(name: "send", size: 22).frame(width: 38, height: 38)
                        .foregroundStyle(sendEnabled ? Color(hex: 0xF2F3F5) : Palette.muted)
                }
                .disabled(!sendEnabled)
                .accessibilityLabel(L10n.string("action_send"))
            }.padding(10).background(Palette.bottom)
        }
        .background(Palette.background.ignoresSafeArea())
        .foregroundStyle(Color(hex: 0xF2F3F5))
        .navigationBarHidden(false)
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle("")
        .alert(L10n.string("chat_send_failed"), isPresented: $showServerPermissionAlert) {
            Button(L10n.string("action_close"), role: .cancel) {}
        } message: {
            Text(L10n.string("error_server_message_permission_denied"))
        }
        .toolbar {
            ToolbarItem(placement: .principal) {
                if let member {
                    HStack(spacing: 8) {
                        Avatar(name: currentMember?.name ?? member.name, path: currentMember?.avatarPath ?? member.avatarPath,
                               speaking: currentMember?.speaking ?? false, size: 30, member: currentMember).accessibilityHidden(true)
                        Text(currentMember?.name ?? member.name).font(.headline).accessibilityLabel(currentMember?.accessibilityLabel ?? member.name)
                    }
                } else {
                    Text(title).font(.headline)
                }
            }
        }
        .onAppear {
            if showsSenderName && !available { dismiss(); return }
            guard available, let server = client.state.serverId else { return }
            openedServer = server
            client.setChatVisible(server: server, conversation: conversation, token: chatToken, visible: true, connectionId: serverChat?.connectionId)
        }
        .onDisappear {
            serverMessageRequests.removeAll()
            showServerPermissionAlert = false
            if let openedServer {
                client.setChatVisible(server: openedServer, conversation: conversation, token: chatToken, visible: false, connectionId: serverChat?.connectionId)
            }
        }
        .onChange(of: client.connected) { connected in if !connected { dismiss() } }
        .onChange(of: client.state.serverChatTarget) { target in
            if let serverChat, target != serverChat { dismiss() }
        }
        .onChange(of: client.currentChannel) { current in
            if let channel, current != channel.id { dismiss() }
        }
    }
    private var currentMember: Member? {
        client.state.onlineMember(uid: member?.uid)
    }
    private func send() {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard available, !value.isEmpty else { return }
        guard value.utf8.count <= 8192 else { client.showError(.key("chat_message_too_long")); return }
        if let serverChat {
            if let requestId = client.sendServerMessage(value, target: serverChat) {
                serverMessageRequests.insert(requestId)
                text = ""
            }
        } else if let channel {
            if client.sendChannelMessage(value, channel: channel) { text = "" }
        } else if let member = currentMember, client.sendPrivateMessage(value, member: member) { text = "" }
    }
    @ViewBuilder private func messageRow(_ message: ChatMessage) -> some View {
        let sender = client.state.onlineMember(uid: message.senderUid)
        HStack(alignment: .bottom, spacing: 8) {
            if message.own { Spacer(minLength: 46) }
            else {
                Avatar(name: message.senderName, path: sender?.avatarPath ?? message.avatarPath, speaking: sender?.speaking ?? false, size: 30, member: sender)
                    .accessibilityHidden(showsSenderName)
            }
            VStack(alignment: message.own ? .trailing : .leading, spacing: 4) {
                if showsSenderName && !message.own {
                    Text(message.senderName).font(.caption).foregroundStyle(Palette.muted).accessibilityLabel(sender?.accessibilityLabel ?? message.senderName)
                }
                Text(message.text)
                    .padding(.horizontal, 12).padding(.vertical, 9)
                    .background(message.own ? Palette.accent : Palette.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                    .textSelection(.enabled)
                if message.own {
                    HStack(spacing: 4) {
                        if message.status == .pending { ProgressView().scaleEffect(0.65) }
                        if message.status == .failed {
                            let error = message.serverPermissionDenied ? "message_send_failed" : message.error
                            AppIcon(name: "error", scaledSize: 12, relativeTo: .caption2).foregroundStyle(.red)
                            Text(error.map { L10n.coreError($0, detail: "") } ?? L10n.string("chat_send_failed")).foregroundStyle(.red)
                        }
                    }.font(.caption2)
                }
            }
            if message.own { Avatar(name: message.senderName, path: sender?.avatarPath ?? message.avatarPath, speaking: sender?.speaking ?? false, size: 30, member: sender) }
            else { Spacer(minLength: 46) }
        }
    }
}

struct ProfileCard: View {
    let nickname: String
    let preview: UIImage?
    let subtitle: String
    let edit: () -> Void
    var body: some View {
        HStack(spacing: 14) {
            Group {
                if let preview { Image(uiImage: preview).resizable().scaledToFill() }
                else { AppIcon(name: "avatar-placeholder", size: 28).foregroundStyle(Palette.muted) }
            }
            .frame(width: 64, height: 64).background(Palette.selected).clipShape(Circle())
            .accessibilityLabel(L10n.string("avatar_preview"))
            Button(action: edit) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 10) {
                        Text(nickname).font(.body.weight(.semibold)).lineLimit(1).truncationMode(.tail)
                        AppIcon(name: "pencil", size: 17).foregroundStyle(Palette.accent).fixedSize().layoutPriority(1)
                    }
                    Text(subtitle).font(.footnote).foregroundStyle(Palette.muted)
                }
                .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.format("profile_edit_nickname", nickname))
            .accessibilityValue(subtitle)
            .accessibilityIdentifier("profile-nickname")
        }
        .padding(16).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

struct NicknameEditor: View {
    @ObservedObject var client: Client
    @Environment(\.dismiss) private var dismiss
    @State private var draft: String
    @State private var submitted: UInt64?
    init(client: Client) {
        self.client = client
        _draft = State(initialValue: client.profile.nickname)
    }
    var body: some View {
        NavigationView {
            Form {
                TextField(L10n.string("nickname"), text: $draft)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("nickname-input")
                if submitted != nil, let error = client.nicknameSaveError {
                    Text(error).font(.footnote).foregroundStyle(Palette.disconnect)
                }
            }
            .navigationTitle(L10n.string("profile_nickname_edit")).navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L10n.string("action_cancel")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.string("action_save")) { submitted = client.saveNickname(draft) }
                        .disabled(client.nicknameSaving).accessibilityIdentifier("nickname-save")
                }
            }
        }
        .onChange(of: client.profile) { profile in
            if profile.requestId == submitted && !client.nicknameSaving && client.nicknameSaveError == nil { dismiss() }
        }
    }
}

struct ConnectView: View {
    @ObservedObject var client: Client
    var bookmark: Bookmark? = nil
    @State private var title = ""
    @State private var formError: Error?
    @State private var passwordLoaded = false
    @Environment(\.dismiss) private var dismiss
    @State private var host = ""
    @State private var port = "9987"
    @State private var password = ""
    var body: some View {
        NavigationView {
            Form {
                Section(L10n.string("server_section")) {
                    TextField(L10n.string("bookmark_name_optional"), text: $title)
                    TextField(L10n.string("server_address"), text: $host).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    TextField(L10n.string("server_port"), text: $port).keyboardType(.numberPad)
                    SecureField(L10n.string("server_password_optional"), text: $password)
                        .textContentType(.oneTimeCode)
                        .autocorrectionDisabled()
                }
                Section {
                    Button(L10n.string(bookmark != nil || client.connected || client.busy ? "bookmark_save" : "action_save_and_connect")) {
                        do {
                            let saved = try client.saveBookmark(id: bookmark?.id, title: title, host: host, port: port, password: password)
                            if bookmark == nil && !client.connected && !client.busy { client.connectBookmark(saved) }
                            dismiss()
                        } catch { formError = error }
                    }.disabled(!passwordLoaded)
                    if let error = formError { Text(LocalizedNotice.source(of: error)?.text ?? error.localizedDescription).foregroundStyle(.red) }
                }
            }.navigationTitle(L10n.string(bookmark == nil ? "bookmark_add_server" : "bookmark_edit_server")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.string("action_cancel")) { dismiss() } } }
        }.onAppear {
            let url = URLComponents(string: "ts://" + client.address)
            host = bookmark?.host ?? url?.host ?? ""; port = String(bookmark?.port ?? UInt16(url?.port ?? 9987))
            title = bookmark?.title ?? ""
            do { password = try bookmark.map { try client.bookmarkPassword($0) } ?? ""; passwordLoaded = true }
            catch { formError = error }
        }
    }
}

// Native NavigationView keeps the home scroll view alive, just as it does for chat.
enum SettingsPage { case language, about }

struct SettingsEntry: View {
    let title: String
    var value: String? = nil
    var body: some View {
        HStack(spacing: 12) {
            Text(title).fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
            if let value { Text(value).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true) }
            AppIcon(name: "chevron-right", scaledSize: 16, relativeTo: .caption).foregroundStyle(Palette.muted).accessibilityHidden(true)
        }.frame(maxWidth: .infinity, minHeight: 56).padding(.horizontal, 16).padding(.vertical, 4)
            .contentShape(Rectangle()).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

struct SettingsDetailPage: View {
    let page: SettingsPage
    @EnvironmentObject private var language: LanguageSettings
    @Environment(\.sizeCategory) private var sizeCategory
    static var installedVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—" }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if page == .language {
                    VStack(spacing: 0) {
                        ForEach(AppLanguage.allCases) { option in
                            if option != AppLanguage.allCases.first { Divider().padding(.leading, 16) }
                            Button {
                                if language.select(option) { UISelectionFeedbackGenerator().selectionChanged() }
                            } label: {
                                HStack {
                                    Text(L10n.string(option.titleKey))
                                    Spacer()
                                    if language.selection == option { AppIcon(name: "checkmark", scaledSize: 20).foregroundStyle(Palette.accent).accessibilityHidden(true) }
                                }.frame(maxWidth: .infinity, minHeight: 56).padding(.horizontal, 16).padding(.vertical, 4).contentShape(Rectangle())
                            }.buttonStyle(.plain)
                                .accessibilityValue(L10n.string(language.selection == option ? "selection_selected" : "selection_not_selected"))
                                .accessibilityAddTraits(language.selection == option ? .isSelected : [])
                        }
                    }.background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(L10n.string("about_project"))
                        Link("https://github.com/langstaffe/MobileSpeak", destination: URL(string: "https://github.com/langstaffe/MobileSpeak")!)
                            .font(.callout).fixedSize(horizontal: false, vertical: true)
                            .accessibilityHint(L10n.string("about_browser"))
                    }.padding(16).frame(maxWidth: .infinity, alignment: .leading).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                    Group {
                        if sizeCategory.isAccessibilityCategory {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(L10n.string("about_version")).fixedSize(horizontal: false, vertical: true)
                                Text("v" + Self.installedVersion).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, alignment: .trailing)
                            }.padding(.vertical, 12)
                        } else {
                            HStack { Text(L10n.string("about_version")); Spacer(); Text("v" + Self.installedVersion).foregroundStyle(Palette.muted) }
                        }
                    }
                        .frame(minHeight: 56).padding(.horizontal, 16).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }.padding(20)
        }.foregroundStyle(Color(hex: 0xF2F3F5)).background(Palette.background.ignoresSafeArea())
            .navigationBarHidden(false).navigationBarTitleDisplayMode(.inline)
            .navigationTitle(L10n.string(page == .language ? "settings_language" : "settings_about"))
    }
}

struct UserVolumesDrawer: View {
    @ObservedObject var client: Client
    var presentation: Snapshot? = nil
    var onBack: () -> Void
    @ScaledMetric(relativeTo: .headline) private var titleSize: CGFloat = 17
    @ScaledMetric(relativeTo: .callout) private var actionSize: CGFloat = 14
    @ScaledMetric(relativeTo: .caption) private var chromeSize: CGFloat = 12
    private var snapshot: Snapshot { presentation ?? client.state }
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onBack) {
                    HStack(spacing: 2) { AppIcon(name: "chevron-right", size: 18).scaleEffect(x: -1, y: 1); Text(L10n.string("action_back")) }
                        .frame(minWidth: 64, minHeight: 44).foregroundStyle(Palette.accent)
                }.accessibilityIdentifier("volumes-back")
                VStack(spacing: 2) {
                    Text(L10n.string("volume_title")).font(.system(size: min(titleSize, 22), weight: .semibold))
                    Text(snapshot.server ?? "").font(.system(size: min(chromeSize, 16))).foregroundStyle(Palette.muted).lineLimit(1)
                }.frame(maxWidth: .infinity)
                Color.clear.frame(width: 64, height: 44).accessibilityHidden(true)
            }.font(.system(size: min(actionSize, 20))).padding(.horizontal, 8)
            Divider().overlay(Palette.border)
            ScrollView {
                let members = snapshot.clients.filter { $0.id != snapshot.ownClient }.sorted { $0.id < $1.id }
                LazyVStack(spacing: 0) {
                    if members.isEmpty { Text(L10n.string("volume_empty")).foregroundStyle(Palette.muted).padding(24) }
                    ForEach(members, id: \.volumeRowId) { member in
                        UserVolumeRow(client: client, server: snapshot.serverId ?? "", member: member)
                    }
                }.padding(.top, members.isEmpty ? 0 : 12)
            }.accessibilityIdentifier("user-volumes-list")
        }.accessibilityIdentifier("user-volumes-page")
    }
}
private struct UserVolumeRow: View {
    @ObservedObject var client: Client
    let server: String
    let member: Member
    @State private var value: Double
    @State private var editing = false
    init(client: Client, server: String, member: Member) {
        self.client = client; self.server = server; self.member = member
        _value = State(initialValue: Double(member.volumePercent ?? 100))
    }
    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 12) {
                Avatar(name: member.name, path: member.avatarPath, speaking: member.speaking, member: member).accessibilityHidden(true)
                Text(member.name).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading).accessibilityLabel(member.accessibilityLabel)
                Text(L10n.percent(value)).foregroundStyle(Palette.muted).monospacedDigit().fixedSize()
            }
            Slider(value: Binding(get: { value }, set: { next in
                value = next
                client.setUserVolume(server: server, member: member, percent: Int(next))
            }), in: 0...300, step: 1, onEditingChanged: { active in
                editing = active
                if !active {
                    client.setUserVolume(server: server, member: member, percent: Int(value))
                    value = Double(member.volumePercent ?? 100)
                }
            }).frame(minHeight: 48).disabled(!client.connected || member.volumeToken == nil)
                .accessibilityLabel(L10n.format("volume_accessibility", member.name))
                .accessibilityValue(L10n.percent(value))
                .accessibilityIdentifier("user-volume-\(member.id)")
        }.padding(.horizontal, 16).padding(.vertical, 4)
            .onChange(of: member.volumePercent) { percent in if !editing { value = Double(percent ?? 100) } }
    }
}
