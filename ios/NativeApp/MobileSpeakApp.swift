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

// Shared core owns all sampling, grades and scale; this view only draws its snapshot.
struct NetworkQualityPanel: View {
    var quality: NetworkQuality = NetworkQuality()
    @ScaledMetric(relativeTo: .body) private var valueSize: CGFloat = 16
    @ScaledMetric(relativeTo: .caption) private var labelSize: CGFloat = 12
    @State private var width: CGFloat = 0

    private let numbersWidth: CGFloat = 123
    private var stacked: Bool { width < numbersWidth + 116 || valueSize > 16 }
    private var numbers: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L10n.string("network_latency")).font(.system(size: labelSize))
            (Text(quality.latencyText).foregroundColor(quality.rttGrade?.color ?? Palette.muted)
                + Text(" ms ± ").foregroundColor(Palette.muted)
                + Text(quality.deviationText).foregroundColor(quality.deviationGrade?.color ?? Palette.muted))
                .font(.system(size: valueSize, weight: .semibold)).monospacedDigit().fixedSize(horizontal: false, vertical: true)
            (Text(L10n.string("network_packet_loss") + " ").foregroundColor(Palette.muted)
                + Text(quality.lossText).foregroundColor(quality.packetLossGrade?.packetLossColor ?? Palette.muted)
                + Text("%").foregroundColor(Palette.muted))
                .font(.system(size: labelSize)).monospacedDigit().fixedSize(horizontal: false, vertical: true)
        }.foregroundStyle(Palette.muted)
            .accessibilityElement(children: .ignore).accessibilityLabel(quality.accessibilitySummary)
    }
    var chart: some View {
        Canvas { context, size in
            let font = Font.system(size: min(labelSize, 16)).monospacedDigit()
            let upper = context.resolve(Text(quality.axisText + " ms").font(font).foregroundColor(Palette.muted))
            let middle = context.resolve(Text(quality.midAxisText + " ms").font(font).foregroundColor(Palette.muted))
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
            .background(GeometryReader { proxy in Color.clear.onAppear { width = proxy.size.width - 24 }.onChange(of: proxy.size.width) { width = $0 - 24 } })
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
private struct PageTitle: View {
    static let inset: CGFloat = 14
    let key: String
    var body: some View {
        Text(LocalizedStringKey(key))
            .font(.system(.title3).weight(.heavy))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, 8)
    }
}
private extension View {
    @ViewBuilder func adaptiveSheetHeight(_ height: CGFloat) -> some View {
        if #available(iOS 16.0, *) { presentationDetents([.height(height)]) }
        else { self }
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
                .onReceive(NotificationCenter.default.publisher(for: .AVAudioEngineConfigurationChange).receive(on: RunLoop.main)) { _ in
                    client.audioConfigurationChanged()
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
    var selected: Channel?
    private(set) var queued: Channel?
    // The binding can become nil before the dismissal animation finishes.
    private(set) var active = false

    mutating func select(_ channel: Channel) {
        if active { queued = channel }
        else { active = true; selected = channel }
    }

    mutating func didDismiss() -> Channel? {
        active = false
        selected = nil
        defer { queued = nil }
        return queued
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
    let available: Bool
    let transmitting: Bool
    let token: String
    let begin: () -> String?
    let end: (String) -> Void
    var body: some View {
        Button {} label: {
            HStack(spacing: 10) {
                AppIcon(name: "mic-on", scaledSize: 20).accessibilityHidden(true)
                Text(L10n.string(transmitting ? "voice_push_to_talk_active" : "voice_push_to_talk_hold"))
            }
            .font(.headline).foregroundStyle(.white)
            .frame(maxWidth: .infinity, minHeight: 56)
        }
        // Match Join Channel's complete native style, including its added label insets.
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .tint(available ? (transmitting ? Palette.green : Palette.accent) : Palette.muted)
        .frame(maxWidth: .infinity)
        .disabled(!available)
        .background(PushToTalkTouchObserver(available: available, token: token, begin: begin, end: end))
        .accessibilityIdentifier("push-to-talk")
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
    @ObservedObject var client: Client
    var showsTarget = false
    var body: some View {
        if client.speech.mode == .pushToTalk {
            VStack(spacing: 8) {
                if showsTarget {
                    Text(L10n.format("voice_push_to_talk_channel", client.state.channels.first { $0.id == client.currentChannel }?.name ?? L10n.string("status_disconnected")))
                        .font(.caption).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("push-to-talk-channel")
                }
                PushToTalkButton(available: client.speech.available && client.audioProcessingStatus != "switching" && !client.muted && !client.audioBusy && !client.needsMicrophonePermission,
                    transmitting: client.speech.transmitting, token: client.speech.token,
                    begin: client.beginPushToTalk, end: { client.endPushToTalk(token: $0) })
                if client.connected && !client.muted && client.needsMicrophonePermission {
                    Button(L10n.string(client.microphonePermission == .denied ? "voice_microphone_settings" : "voice_microphone_allow")) {
                        Task { await client.requestPushToTalkPermission() }
                    }
                    .font(.footnote).frame(minHeight: 44).disabled(client.audioBusy)
                    .accessibilityIdentifier("push-to-talk-permission")
                }
            }
            .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 8)
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
    @State private var drawerBlocksClick = false
    @State private var showConnect = false
    @State private var editingBookmark: Bookmark?
    @State private var deletingBookmark: Bookmark?
    @State private var nicknamePresented = false
    @State private var avatarClearPresented = false
    @State private var avatarSelection: AvatarSelectionRequest?
    @State private var channelSheet = ChannelSheetState()
    @State private var lockedChannel: Channel?
    @State private var channelPassword = ""
    @State private var channelMemberListHeight: CGFloat = 52
    @State private var viewportHeight = UIScreen.main.bounds.height
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                if let server = client.state.server {
                    Avatar(name: server).accessibilityHidden(true)
                } else {
                    Image("AppLogo").resizable().frame(width: 34, height: 34).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityLabel("MobileSpeak")
                }
                Text(client.state.server ?? "MobileSpeak").font(.system(size: 16, weight: .heavy)).lineLimit(1)
                Spacer(minLength: 0)
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
        .background(GeometryReader { proxy in
            Color.clear
                .onAppear { viewportHeight = proxy.size.height }
                .onChange(of: proxy.size.height) { viewportHeight = $0 }
        })
        .onChange(of: client.connected) { connected in
            if !connected { drawerExpanded = false; drawerBlocksClick = false; userVolumesOpen = false }
        }
        .navigationTitle(tab == 2 ? L10n.string("tab_settings") : "")
        .onChange(of: tab) { _ in client.endPushToTalk() }
        .onChange(of: showConnect) { _ in client.endPushToTalk() }
        .onChange(of: editingBookmark?.id) { _ in client.endPushToTalk() }
        .onChange(of: channelSheet.selected?.id) { _ in client.endPushToTalk() }
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
            if let next = channelSheet.didDismiss(), client.connected, tab == 0 { showChannel(next) }
        }) { channel in
            let current = client.state.channels.first { $0.id == channel.id } ?? channel
            let members = client.state.clients.filter { $0.channel == channel.id }
            let layout = Self.channelSheetLayout(memberHeight: channelMemberListHeight, viewportHeight: viewportHeight)
            VStack(spacing: 16) {
                HStack { ChannelIcon(channel: current, size: 28); Text(current.name).font(.title3.bold()).lineLimit(1); Spacer(); Button(L10n.string("action_close")) { channelSheet.selected = nil } }
                    .frame(height: 44)
                ScrollView {
                    VStack(spacing: 16) {
                        if members.isEmpty {
                            Text(L10n.string("channel_no_members")).foregroundStyle(Palette.muted).frame(maxWidth: .infinity, minHeight: 52)
                        } else {
                            ForEach(members) { memberRow($0) }
                        }
                    }
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
                    .disabled(!client.connected || client.currentChannel == channel.id)
            }
            .padding(20)
            .background(Palette.background.ignoresSafeArea())
            .onPreferenceChange(ChannelMemberListHeightKey.self) { height in
                guard channelSheet.selected?.id == channel.id, abs(height - channelMemberListHeight) > 0.5 else { return }
                withAnimation(.easeInOut(duration: 0.2)) { channelMemberListHeight = height }
            }
            .adaptiveSheetHeight(layout.sheet)
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
                Text(L10n.string("bookmarks_title")).font(.title3.bold())
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
                }
                Button { showConnect = true } label: { Label { Text(L10n.string("bookmark_add_server")) } icon: { AppIcon(name: "plus", scaledSize: 18) } }.padding(.vertical, 12)
                if client.connected { Text(L10n.string("bookmark_disconnect_first")).font(.footnote).foregroundStyle(Palette.muted) }
            }.padding(16)
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
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 9) {
                PageTitle(key: "tab_channels")
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
            }.padding(PageTitle.inset)
        }
    }
    private var members: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 16) {
                PageTitle(key: "tab_members").padding(.horizontal, PageTitle.inset)
                ForEach(client.state.clients.filter { $0.id != client.state.ownClient }) { member in
                    if member.uid != nil {
                        NavigationLink(destination: PrivateChatView(client: client, member: member)) { memberRow(member, showUnread: true) }.buttonStyle(.plain).padding(.horizontal, 16)
                    } else { memberRow(member).padding(.horizontal, 16) }
                }
            }.padding(.top, PageTitle.inset).padding(.bottom, 16)
        }
    }
    private func memberRow(_ m: Member, showUnread: Bool = false) -> some View {
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
            Text(m.name).lineLimit(1).truncationMode(.tail).accessibilityLabel(m.accessibilityLabel)
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
        VStack(spacing: 0) {
            NetworkQualityPanel(quality: client.state.networkQuality ?? NetworkQuality()).fixedSize(horizontal: false, vertical: true)
            ScrollView {
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
        }
    }
    private func setDrawerExpanded(_ expanded: Bool) {
        guard client.connected, expanded != drawerExpanded else { return }
        UISelectionFeedbackGenerator().selectionChanged()
        drawerExpanded = expanded
    }
    static func drawerTravel(availableHeight: CGFloat) -> CGFloat {
        max(0, min(availableHeight, availableHeight * 7 / 10) - 64)
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
    private func showChannel(_ channel: Channel) {
        if !channelSheet.active { channelMemberListHeight = 52 }
        channelSheet.select(channel)
    }
    static func channelSheetLayout(memberHeight: CGFloat, viewportHeight: CGFloat) -> (sheet: CGFloat, list: CGFloat) {
        let chrome: CGFloat = 172 // 44pt header + 56pt action + padding and spacing.
        let maximum = max(chrome + 52, viewportHeight * 0.78)
        let list = min(max(52, memberHeight), maximum - chrome)
        return (chrome + list, list)
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
    @State private var text = ""
    @State private var chatToken = UUID()
    @State private var openedServer: String?
    @Environment(\.dismiss) private var dismiss
    private var messages: [ChatMessage] { client.messages(in: conversation) }
    private var available: Bool {
        guard client.connected else { return false }
        if let channel { return client.currentChannel == channel.id }
        if let uid = member?.uid { return client.state.clients.contains { $0.uid == uid } }
        return false
    }
    var body: some View {
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
                    if let id = value.last?.id { withAnimation { proxy.scrollTo(id, anchor: .bottom) } }
                }
                .onAppear { if let id = messages.last?.id { proxy.scrollTo(id, anchor: .bottom) } }
            }
            Divider()
            PushToTalkBar(client: client, showsTarget: true)
            HStack(alignment: .bottom, spacing: 10) {
                TextField(L10n.string("chat_input_placeholder"), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                Button(action: send) { AppIcon(name: "send", size: 22).frame(width: 38, height: 38) }
                    .disabled(!available || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityLabel(L10n.string("action_send"))
            }.padding(10).background(Palette.bottom)
        }
        .background(Palette.background.ignoresSafeArea())
        .foregroundStyle(Color(hex: 0xF2F3F5))
        .navigationBarHidden(false)
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle("")
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
            if channel != nil && !available { dismiss(); return }
            guard available, let server = client.state.serverId else { return }
            openedServer = server
            client.setChatVisible(server: server, conversation: conversation, token: chatToken, visible: true)
        }
        .onDisappear {
            if let openedServer {
                client.setChatVisible(server: openedServer, conversation: conversation, token: chatToken, visible: false)
            }
        }
        .onChange(of: client.connected) { connected in if !connected { dismiss() } }
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
        if let channel, client.sendChannelMessage(value, channel: channel) { text = "" }
        else if let member = currentMember, client.sendPrivateMessage(value, member: member) { text = "" }
    }
    @ViewBuilder private func messageRow(_ message: ChatMessage) -> some View {
        let sender = client.state.onlineMember(uid: message.senderUid)
        HStack(alignment: .bottom, spacing: 8) {
            if message.own { Spacer(minLength: 46) }
            else {
                Avatar(name: message.senderName, path: sender?.avatarPath ?? message.avatarPath, speaking: sender?.speaking ?? false, size: 30, member: sender)
                    .accessibilityHidden(channel != nil)
            }
            VStack(alignment: message.own ? .trailing : .leading, spacing: 4) {
                if channel != nil && !message.own {
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
                            AppIcon(name: "error", scaledSize: 12, relativeTo: .caption2).foregroundStyle(.red)
                            Text(message.error.map { L10n.coreError($0, detail: "") } ?? L10n.string("chat_send_failed")).foregroundStyle(.red)
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
                    HStack { Text(L10n.string("about_version")); Spacer(); Text("v" + Self.installedVersion).foregroundStyle(Palette.muted) }
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
                Text("\(Int(value))%").foregroundStyle(Palette.muted).monospacedDigit().fixedSize()
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
                .accessibilityValue("\(Int(value))%")
                .accessibilityIdentifier("user-volume-\(member.id)")
        }.padding(.horizontal, 16).padding(.vertical, 4)
            .onChange(of: member.volumePercent) { percent in if !editing { value = Double(percent ?? 100) } }
    }
}
