import XCTest
import UIKit
import ImageIO
import SwiftUI
import PhotosUI
import Combine
@testable import MobileSpeak

final class ClientTests: XCTestCase {
    @MainActor func testFirstAvatarPickerPresentationLoadsCropAndCanCancelThenReopen() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let trigger = PassthroughSubject<Void, Never>()
        let client = Client.shared
        let preview = client.avatarPreview?.pngData()
        let intent = client.avatarIntent
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: FirstAvatarPickerFixture(trigger: trigger))
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer { host.dismiss(animated: false); window.isHidden = true }
        try await Task.sleep(nanoseconds: 100_000_000)
        func picker(in controller: UIViewController) -> PHPickerViewController? {
            if let picker = controller as? PHPickerViewController { return picker }
            for child in controller.children { if let picker = picker(in: child) { return picker } }
            if let presented = controller.presentedViewController { return picker(in: presented) }
            return nil
        }
        func canvas(in view: UIView) -> AvatarCropCanvas.CropScroll? {
            if let canvas = view as? AvatarCropCanvas.CropScroll { return canvas }
            for child in view.subviews { if let canvas = canvas(in: child) { return canvas } }
            return nil
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try XCTUnwrap(cropFixture(CGSize(width: 800, height: 600)).jpegData(compressionQuality: 0.9)).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        for attempt in 0..<2 {
            trigger.send(())
            var shown: PHPickerViewController?
            for _ in 0..<60 {
                shown = picker(in: host)
                if shown != nil { break }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            let picker = try XCTUnwrap(shown)
            let coordinator = try XCTUnwrap(picker.delegate as? AvatarPicker.Coordinator)
            let selection = coordinator.parent.selection
            XCTAssertTrue(client.isCurrentAvatarSelection(selection), "Sheet captured stale selection \(selection)")
            if attempt == 0 {
                let provider = NSItemProvider()
                provider.registerFileRepresentation(forTypeIdentifier: "public.jpeg", fileOptions: [], visibility: .all) { completion in
                    completion(url, false, nil)
                    return nil
                }
                coordinator.load(provider) // Same asynchronous file loader as didFinishPicking.
                var crop: AvatarCropCanvas.CropScroll?
                for _ in 0..<60 {
                    crop = host.presentedViewController.flatMap { canvas(in: $0.view) }
                    if crop != nil { break }
                    try await Task.sleep(nanoseconds: 50_000_000)
                }
                XCTAssertNotNil(crop, "First selected image must open the real crop page")
                XCTAssertTrue(client.isCurrentAvatarSelection(selection), "Picker-to-crop transition must not cancel selection")
                host.dismiss(animated: false) // System closing the sheet cancels, never saves.
            } else {
                coordinator.picker(picker, didFinishPicking: []) // Native picker cancellation.
            }
            for _ in 0..<60 {
                if !client.isCurrentAvatarSelection(selection) && host.presentedViewController == nil { break }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            XCTAssertFalse(client.isCurrentAvatarSelection(selection))
            XCTAssertNil(host.presentedViewController)
            XCTAssertEqual(client.avatarPreview?.pngData(), preview)
            XCTAssertEqual(client.avatarIntent, intent)
        }
    }

    // Opt-in destructive acceptance: the marker contains the authorized bookmark UUID.
    // Never runs in the ordinary suite.
    @MainActor func testAuthorizedServerAvatarClearAndReconnect() async throws {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MobileSpeak")
        let marker = root.appendingPathComponent("avatar-clear-acceptance-authorized")
        guard FileManager.default.fileExists(atPath: marker.path) else { throw XCTSkip("Explicit server deletion authorization marker required") }
        defer { try? FileManager.default.removeItem(at: marker) }
        let client = Client.shared
        let bookmark = try authorizedAvatarBookmark(marker: marker, client: client)
        let handle = client.handle
        let audio = client.audio
        defer { client.disconnect() }
        client.connectBookmark(bookmark)
        try await waitForAvatar { client.connected }
        let own = try XCTUnwrap(client.state.clients.first { $0.id == client.state.ownClient })
        XCTAssertFalse(own.avatarHash.isEmpty, "Test must start with the authorized identity's server avatar")
        client.clearAvatar()
        try await waitForAvatar { !client.avatarClearingLocally && client.avatarStatus == "cleared" }
        XCTAssertNil(client.avatarPreview)
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("default-avatar-current"), encoding: .utf8), "clear")
        XCTAssertEqual(client.state.clients.first { $0.id == client.state.ownClient }?.avatarHash, "")
        XCTAssertTrue(client.connected)
        XCTAssertTrue(client.audio.isRunning)
        XCTAssertEqual(client.handle, handle); XCTAssertTrue(client.audio === audio)
        print("AvatarClearAcceptance: authorized server command and empty own avatar confirmed")
        try await Task.sleep(nanoseconds: 30_000_000_000)
        try await Task.sleep(nanoseconds: 30_000_000_000)
        XCTAssertTrue(client.connected)
        client.disconnect()
        try await Task.sleep(nanoseconds: 2_500_000_000)
        client.connectBookmark(bookmark)
        try await waitForAvatar { client.connected && client.avatarStatus == "cleared" }
        XCTAssertEqual(client.state.clients.first { $0.id == client.state.ownClient }?.avatarHash, "")
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("default-avatar-current"), encoding: .utf8), "clear")
        print("AvatarClearAcceptance: 60 second connection observation and reconnect empty-avatar confirmation passed")
    }
    @MainActor private func waitForAvatar(_ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(35)
        while !condition() && Date() < deadline { try await Task.sleep(nanoseconds: 50_000_000) }
        XCTAssertTrue(condition(), "Avatar acceptance deadline exceeded: \(Client.shared.avatarStatus) \(Client.shared.avatarDetail ?? "")")
        if !condition() { throw NSError(domain: "AvatarAcceptance", code: 1) }
    }

    @MainActor private func authorizedAvatarBookmark(marker: URL, client: Client) throws -> Bookmark {
        let value = try String(contentsOf: marker, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        let id = try XCTUnwrap(UUID(uuidString: value), "Authorization marker must contain the target bookmark UUID")
        return try XCTUnwrap(client.bookmarks.first { $0.id == id }, "Authorized bookmark was not found")
    }

    @MainActor func testClearConfirmationRendersLocalizedScopeAndClosingDoesNotConfirm() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let defaults = UserDefaults.standard
        let language = defaults.object(forKey: AppLanguage.preferenceKey)
        let client = Client.shared
        let before = client.avatarPreview?.pngData()
        let intent = client.avatarIntent
        defer {
            if let language { defaults.set(language, forKey: AppLanguage.preferenceKey) }
            else { defaults.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        for language in [AppLanguage.english, .simplifiedChinese] {
            defaults.set(language.rawValue, forKey: AppLanguage.preferenceKey)
            for connected in [false, true] {
                var confirmed = 0
                let model = ClearAlertFixtureModel()
                let window = UIWindow(windowScene: scene)
                let root = ClearAlertFixture(model: model, connected: connected, clear: { confirmed += 1 })
                let host = UIHostingController(rootView: root.environment(\.dynamicTypeSize, .accessibility3))
                window.overrideUserInterfaceStyle = .dark
                window.rootViewController = host
                window.makeKeyAndVisible()
                try await Task.sleep(nanoseconds: 100_000_000)
                model.presented = true
                for _ in 0..<20 {
                    if host.presentedViewController != nil { break }
                    try await Task.sleep(nanoseconds: 50_000_000)
                }
                let alert = try XCTUnwrap(host.presentedViewController as? UIAlertController)
                try await Task.sleep(nanoseconds: 350_000_000) // Capture the completed native presentation animation.
                XCTAssertEqual(alert.title, L10n.string("avatar_remove"))
                XCTAssertEqual(alert.message, L10n.string(connected ? "avatar_clear_confirm_connected" : "avatar_clear_confirm_offline"))
                XCTAssertEqual(alert.actions.first { $0.style == .cancel }?.title, L10n.string("action_cancel"))
                XCTAssertEqual(alert.actions.first { $0.style == .destructive }?.title, L10n.string("avatar_remove"))
                let shot = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
                let attachment = XCTAttachment(image: shot); attachment.name = "Clear confirmation \(language.rawValue) connected=\(connected)"; attachment.lifetime = .keepAlways; add(attachment)
                host.dismiss(animated: false)
                try await Task.sleep(nanoseconds: 50_000_000)
                XCTAssertEqual(confirmed, 0)
                XCTAssertEqual(client.avatarPreview?.pngData(), before)
                XCTAssertEqual(client.avatarIntent, intent)
                window.isHidden = true
            }
        }
    }

    @MainActor func testAuthorizedClearIntentAfterProcessRestartDoesNotDeleteAgain() async throws {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MobileSpeak")
        let marker = root.appendingPathComponent("avatar-clear-acceptance-authorized")
        guard FileManager.default.fileExists(atPath: marker.path) else { throw XCTSkip("Explicit server acceptance marker required") }
        defer { try? FileManager.default.removeItem(at: marker) }
        XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("default-avatar-current"), encoding: .utf8), "clear")
        let client = Client.shared
        let bookmark = try authorizedAvatarBookmark(marker: marker, client: client)
        defer { client.disconnect() }
        client.connectBookmark(bookmark)
        try await waitForAvatar { client.connected && client.avatarStatus == "cleared" }
        XCTAssertEqual(client.state.clients.first { $0.id == client.state.ownClient }?.avatarHash, "")
        XCTAssertNil(client.avatarPreview)
        XCTAssertEqual(client.avatarIntent, "clear")
        print("AvatarClearAcceptance: fresh process restored global clear intent; authoritative own avatar empty; no repeated delete needed")
    }

    func testAvatarImageKeepsClearPreviewAndUsesActualEncodedSizes() throws {
        let width = 1800
        let height = 1800
        var pixels = [UInt8](repeating: 255, count: width * height * 4)
        var seed: UInt32 = 42
        for index in stride(from: 0, to: pixels.count, by: 4) {
            for channel in 0..<3 {
                seed = seed &* 1664525 &+ 1013904223
                pixels[index + channel] = UInt8(truncatingIfNeeded: seed >> 24)
            }
        }
        let provider = CGDataProvider(data: Data(pixels) as CFData)!
        let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
        let original = try XCTUnwrap(UIImage(cgImage: image).jpegData(compressionQuality: 1))
        XCTAssertGreaterThan(original.count, 5_000_000)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
        try original.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let (preview, uploads) = try AvatarImages.prepare(url)
        XCTAssertEqual(uploads.count, 3)
        XCTAssertGreaterThan(uploads[0].count, uploads[1].count)
        XCTAssertGreaterThan(uploads[1].count, uploads[2].count)
        XCTAssertLessThanOrEqual(try XCTUnwrap(UIImage(data: uploads[0])).size.width, 2048)
        XCTAssertLessThanOrEqual(try XCTUnwrap(UIImage(data: preview)).size.width, 640)
    }
    private func cropFixture(_ size: CGSize, quadrants: Bool = false) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.darkGray.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
            if quadrants {
                for (color, x, y) in [(UIColor.red, 0, 0), (.green, 1, 0), (.blue, 0, 1), (.yellow, 1, 1)] {
                    color.setFill(); ctx.fill(CGRect(x: CGFloat(x) * size.width / 2, y: CGFloat(y) * size.height / 2, width: size.width / 2, height: size.height / 2))
                }
            } else {
                UIColor.red.setFill()
                ctx.cgContext.fillEllipse(in: CGRect(x: size.width / 2 - 100, y: size.height / 2 - 100, width: 200, height: 200))
                UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 60, height: size.height))
                UIColor.green.setFill(); ctx.fill(CGRect(x: size.width - 60, y: 0, width: 60, height: size.height))
            }
        }
    }
    private func pixel(_ image: UIImage, _ x: Int, _ y: Int) -> [UInt8] {
        let part = image.cgImage!.cropping(to: CGRect(x: x, y: y, width: 1, height: 1))!
        var bytes = [UInt8](repeating: 0, count: 4)
        bytes.withUnsafeMutableBytes { memory in
            let ctx = CGContext(data: memory.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(part, in: CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        return bytes
    }
    func testSquareCropGeometryAndAllCandidatesKeepTheSameUnstretchedCircle() throws {
        for size in [CGSize(width: 800, height: 600), CGSize(width: 600, height: 800), CGSize(width: 600, height: 600)] {
            let image = cropFixture(size)
            let rect = AvatarCrop().rect(in: size)
            XCTAssertEqual(rect.width, min(size.width, size.height)); XCTAssertEqual(rect.width, rect.height)
            XCTAssertEqual(rect.midX, size.width / 2); XCTAssertEqual(rect.midY, size.height / 2)
            let result = try AvatarImages.prepare(image, crop: AvatarCrop())
            for data in [result.0] + result.1 {
                let decoded = try XCTUnwrap(UIImage(data: data))
                XCTAssertEqual(decoded.size.width, decoded.size.height)
                XCTAssertLessThanOrEqual(decoded.size.width, min(size.width, size.height))
                let side = Int(decoded.size.width), center = side / 2
                let red = (0..<side).filter { let p = pixel(decoded, $0, center); return p[0] > 160 && p[1] < 100 }
                let vertical = (0..<side).filter { let p = pixel(decoded, center, $0); return p[0] > 160 && p[1] < 100 }
                XCTAssertLessThanOrEqual(abs(red.count - vertical.count), 4)
                XCTAssertEqual(Double(red.count) / Double(side), 200 / Double(min(size.width, size.height)), accuracy: 0.025)
                let corner = pixel(decoded, side / 4, 8)
                XCTAssertLessThan(corner[0], 180) // No circular mask or white corner was exported.
            }
        }
        let size = CGSize(width: 800, height: 600)
        var crop = AvatarCrop(zoom: 2)
        crop.move(dx: -100, dy: 100, in: size)
        XCTAssertEqual(crop.rect(in: size), CGRect(x: 0, y: 300, width: 300, height: 300))
        let image = try XCTUnwrap(UIImage(data: AvatarImages.prepare(cropFixture(size), crop: crop).1[0]))
        let corner = pixel(image, 10, 10)
        XCTAssertGreaterThan(corner[2], 180); XCTAssertLessThan(corner[0], 80)
        let format = UIGraphicsImageRendererFormat.default(); format.scale = 1
        let transparent = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 64), format: format).image { context in
            UIColor.red.setFill(); context.cgContext.fillEllipse(in: CGRect(x: 12, y: 12, width: 40, height: 40))
        }
        let output = try AvatarImages.prepare(transparent, crop: AvatarCrop())
        for data in [output.0] + output.1 {
            let decoded = try XCTUnwrap(UIImage(data: data))
            XCTAssertEqual(decoded.size, CGSize(width: 64, height: 64))
            XCTAssertTrue(pixel(decoded, 2, 2).prefix(3).allSatisfy { $0 > 240 })
        }
    }
    func testExifRotationAndMirrorsAreNormalizedBeforeCropping() throws {
        let image = cropFixture(CGSize(width: 420, height: 300), quadrants: true)
        let expected = [[255, 0, 0], [0, 255, 0], [255, 255, 0], [0, 0, 255], [255, 0, 0], [0, 0, 255], [255, 255, 0], [0, 255, 0]]
        for orientation in 1...8 {
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
            defer { try? FileManager.default.removeItem(at: url) }
            let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil)!
            let metadata: [CFString: Any] = [kCGImagePropertyOrientation: orientation,
                kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 1.0, kCGImagePropertyGPSLatitudeRef: "N",
                    kCGImagePropertyGPSLongitude: 2.0, kCGImagePropertyGPSLongitudeRef: "E"]]
            CGImageDestinationAddImage(destination, image.cgImage!, metadata as CFDictionary)
            XCTAssertTrue(CGImageDestinationFinalize(destination))
            let upright = try AvatarImages.load(url)
            XCTAssertEqual(upright.imageOrientation, .up)
            XCTAssertEqual(upright.size.width, orientation >= 5 ? 300 : 420)
            let result = try AvatarImages.prepare(upright, crop: AvatarCrop())
            let decoded = try XCTUnwrap(UIImage(data: result.1[0]))
            let color = pixel(decoded, 20, 20)
            for channel in 0..<3 { XCTAssertLessThanOrEqual(abs(Int(color[channel]) - expected[orientation - 1][channel]), 15) }
            let source = CGImageSourceCreateWithData(result.1[0] as CFData, nil)!
            let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as! [CFString: Any]
            XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
            XCTAssertTrue(properties[kCGImagePropertyOrientation] == nil || (properties[kCGImagePropertyOrientation] as? Int) == 1)
        }
    }
    @MainActor func testCancelledAndInvalidCropCannotReplaceCurrentAvatar() async {
        let client = Client.shared
        let before = client.avatarPreview?.pngData()
        let handle = client.handle, audio = client.audio
        let selection = client.beginAvatarSelection()
        client.cancelAvatarSelection(selection)
        let result = await client.saveAvatar(preview: Data(), uploads: [], selection: selection)
        XCTAssertFalse(result); XCTAssertEqual(client.avatarPreview?.pngData(), before)
        let invalid = client.beginAvatarSelection()
        let failed = await client.saveAvatar(preview: Data("broken".utf8), uploads: [Data(), Data(), Data()], selection: invalid)
        XCTAssertFalse(failed); XCTAssertEqual(client.avatarPreview?.pngData(), before)
        XCTAssertEqual(client.handle, handle); XCTAssertTrue(client.audio === audio)
        client.cancelAvatarSelection(invalid)
    }
    @MainActor func testNativeCropScrollKeepsExportCoordinatesThroughPanZoomAndViewportResize() {
        for size in [CGSize(width: 800, height: 600), CGSize(width: 600, height: 800), CGSize(width: 600, height: 600)] {
            var crop = AvatarCrop()
            let parent = AvatarCropCanvas(image: cropFixture(size), crop: Binding(get: { crop }, set: { crop = $0 }))
            let coordinator = AvatarCropCanvas.Coordinator(parent: parent)
            let view = AvatarCropCanvas.CropScroll(frame: CGRect(x: 0, y: 0, width: 300, height: 300))
            view.photo.image = parent.image
            view.photo.frame = CGRect(origin: .zero, size: size)
            view.addSubview(view.photo)
            view.delegate = coordinator
            coordinator.apply(to: view)
            XCTAssertEqual(view.zoomScale, 300 / min(size.width, size.height), accuracy: 0.0001)
            for zoom in [CGFloat(1), 2, 4] {
                view.setZoomScale(view.minimumZoomScale * zoom, animated: false)
                view.setContentOffset(CGPoint(x: max(0, view.contentSize.width - 300), y: max(0, view.contentSize.height - 300)), animated: false)
                coordinator.changed(view)
                let rect = crop.rect(in: size)
                XCTAssertEqual(rect.minX, view.contentOffset.x / view.zoomScale, accuracy: 1)
                XCTAssertEqual(rect.minY, view.contentOffset.y / view.zoomScale, accuracy: 1)
                XCTAssertEqual(rect.width, 300 / view.zoomScale, accuracy: 1)
                XCTAssertLessThanOrEqual(rect.maxX, size.width)
                XCTAssertLessThanOrEqual(rect.maxY, size.height)
                XCTAssertGreaterThanOrEqual(view.contentSize.width, 300)
                XCTAssertGreaterThanOrEqual(view.contentSize.height, 300)
            }
            let previous = crop.rect(in: size)
            view.frame.size = CGSize(width: 180, height: 180)
            coordinator.apply(to: view)
            XCTAssertEqual(crop.rect(in: size), previous)
            XCTAssertEqual(view.contentOffset.x / view.zoomScale, previous.minX, accuracy: 1)
            XCTAssertEqual(view.contentOffset.y / view.zoomScale, previous.minY, accuracy: 1)
        }
    }
    @MainActor func testCropPageRendersSquareViewportAtSmallAndLandscapeSizesWithLargeText() async throws {
        let before = Client.shared.avatarPreview?.pngData()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let defaults = UserDefaults.standard
        let originalLanguage = defaults.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let originalLanguage { defaults.set(originalLanguage, forKey: AppLanguage.preferenceKey) }
            else { defaults.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        for language in [AppLanguage.english, .simplifiedChinese] {
            defaults.set(language.rawValue, forKey: AppLanguage.preferenceKey)
            for (size, textSize) in [(CGSize(width: 393, height: 852), DynamicTypeSize.large),
                                     (CGSize(width: 393, height: 852), .accessibility3),
                                     (CGSize(width: 320, height: 568), .accessibility3),
                                     (CGSize(width: 844, height: 390), .accessibility3)] {
                var dismissed = false
                let page = AvatarCropPage(image: cropFixture(CGSize(width: 800, height: 600)), selection: -1, onDismiss: { dismissed = true })
                    .dynamicTypeSize(textSize).preferredColorScheme(.dark)
                let host = UIHostingController(rootView: page)
                let window = UIWindow(windowScene: scene)
                let container = UIViewController()
                window.rootViewController = container
                container.addChild(host)
                container.view.addSubview(host.view)
                host.view.frame = CGRect(origin: .zero, size: size)
                host.didMove(toParent: container)
                window.isHidden = false
                defer { window.isHidden = true }
                try await Task.sleep(nanoseconds: 100_000_000)
                host.view.layoutIfNeeded()
                func canvas(_ view: UIView) -> AvatarCropCanvas.CropScroll? {
                    if let scroll = view as? AvatarCropCanvas.CropScroll { return scroll }
                    return view.subviews.lazy.compactMap { canvas($0) }.first
                }
                let viewport = try XCTUnwrap(canvas(host.view))
                XCTAssertGreaterThan(viewport.bounds.width, 0)
                XCTAssertEqual(viewport.bounds.width, viewport.bounds.height)
                XCTAssertEqual(viewport.backgroundColor, .white)
                XCTAssertEqual(viewport.convert(viewport.bounds, to: host.view).midX, host.view.bounds.midX, accuracy: 2)
                XCTAssertGreaterThanOrEqual(viewport.contentSize.width, viewport.bounds.width)
                XCTAssertGreaterThanOrEqual(viewport.contentSize.height, viewport.bounds.height)
                let image = UIGraphicsImageRenderer(size: size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("crop-baseline-\(language.rawValue)-\(Int(size.width))-\(textSize).png"))
                let attachment = XCTAttachment(image: image)
                attachment.name = "\(language.rawValue) Crop page \(Int(size.width))x\(Int(size.height)) large text"
                attachment.lifetime = .keepAlways
                add(attachment)
                func overflowingScroll(_ view: UIView) -> UIScrollView? {
                    if let scroll = view as? UIScrollView, scroll !== viewport,
                       scroll.contentSize.height > scroll.bounds.height { return scroll }
                    return view.subviews.lazy.compactMap { overflowingScroll($0) }.first
                }
                if let scroll = overflowingScroll(host.view) {
                    scroll.setContentOffset(CGPoint(x: 0, y: scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom), animated: false)
                    try await Task.sleep(nanoseconds: 100_000_000)
                    let bottom = UIGraphicsImageRenderer(size: size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                    try bottom.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("crop-bottom-\(language.rawValue)-\(Int(size.width))-\(textSize).png"))
                    let attachment = XCTAttachment(image: bottom); attachment.name = "Scrolled crop controls \(language.rawValue) \(size)"; attachment.lifetime = .keepAlways; add(attachment)
                }
                XCTAssertFalse(dismissed)
                XCTAssertEqual(Client.shared.avatarPreview?.pngData(), before)
            }
        }
    }
    @MainActor func testOfflineAvatarSettingsSnapshots() async throws {
        let client = Client.shared
        guard !client.connected, client.avatarPreview == nil else { throw XCTSkip("Requires an empty offline test profile") }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let defaults = UserDefaults.standard
        let original = defaults.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let original { defaults.set(original, forKey: AppLanguage.preferenceKey) }
            else { defaults.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        client.clearAvatar()
        for _ in 0..<100 {
            if !client.avatarClearingLocally { break }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTAssertEqual(client.avatarIntent, "clear")
        for language in [AppLanguage.english, .simplifiedChinese] {
            defaults.set(language.rawValue, forKey: AppLanguage.preferenceKey)
            let root = HomeView(client: client, tab: 2).environmentObject(LanguageSettings())
                .dynamicTypeSize(.large).preferredColorScheme(.dark)
                .tint(Palette.accent)
            let host = UIHostingController(rootView: root)
            let window = UIWindow(windowScene: scene)
            window.rootViewController = host
            window.makeKeyAndVisible()
            defer { window.isHidden = true }
            try await Task.sleep(nanoseconds: 250_000_000)
            let shot = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
            try shot.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("settings-baseline-\(language.rawValue).png"))
            let attachment = XCTAttachment(image: shot)
            attachment.name = "Offline cleared settings \(language.rawValue)"; attachment.lifetime = .keepAlways; add(attachment)
        }
    }
    func testLanguageResolutionUsesSupportedLocalesAndEnglishFallback() {
        XCTAssertEqual(AppLanguage.allCases, [.system, .simplifiedChinese, .english])
        XCTAssertEqual(AppLanguage.stored(in: UserDefaults(suiteName: "missing-\(UUID())")!), .system)
        XCTAssertEqual(AppLanguage.resolve(.system, systemLanguageTags: ["zh-Hans-CN"]), .simplifiedChinese)
        XCTAssertEqual(AppLanguage.resolve(.system, systemLanguageTags: ["zh-CN"]), .simplifiedChinese)
        XCTAssertEqual(AppLanguage.resolve(.system, systemLanguageTags: ["en-US"]), .english)
        XCTAssertEqual(AppLanguage.resolve(.system, systemLanguageTags: ["fr-FR"]), .english)
        XCTAssertEqual(AppLanguage.resolve(.simplifiedChinese, systemLanguageTags: ["en-US"]), .simplifiedChinese)
        XCTAssertEqual(AppLanguage.resolve(.english, systemLanguageTags: ["zh-CN"]), .english)
    }
    @MainActor func testLanguageSelectionPersistsAndSameSelectionDoesNothing() throws {
        let suite = "MobileSpeakLanguageTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("old-value", forKey: AppLanguage.preferenceKey)
        let settings = LanguageSettings(defaults: defaults)
        XCTAssertEqual(settings.selection, .system)
        XCTAssertTrue(settings.select(.english))
        XCTAssertEqual(defaults.string(forKey: AppLanguage.preferenceKey), "en")
        XCTAssertFalse(settings.select(.english))
        XCTAssertEqual(LanguageSettings(defaults: defaults).selection, .english)
    }
    @MainActor func testLanguageChangeDoesNotRecreateClientCoreAudioOrState() throws {
        let suite = "MobileSpeakLanguageStateTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = LanguageSettings(defaults: defaults)
        let client = Client.shared
        let handle = client.handle
        let audio = client.audio
        let state = client.state
        XCTAssertTrue(settings.select(.simplifiedChinese))
        XCTAssertEqual(client.handle, handle)
        XCTAssertTrue(client.audio === audio)
        XCTAssertEqual(client.state, state)
    }
    @MainActor func testLocalizedPluralAndCoreErrorFallbacks() {
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        UserDefaults.standard.set("en", forKey: AppLanguage.preferenceKey)
        XCTAssertEqual(L10n.channelMembers("Alice", count: 1), "Alice · 1 member online")
        XCTAssertEqual(L10n.channelMembers("Alice, Bob", count: 2), "Alice, Bob · 2 members online")
        XCTAssertEqual(L10n.coreError("join_channel_failed"), "Couldn’t join the channel.")
        XCTAssertEqual(L10n.coreError("future_code", detail: "safe detail"), "Something went wrong. safe detail")
    }
    @MainActor func testNoiseSuppressionDefaultsPersistsAndSwitches() throws {
        let suite = "MobileSpeakTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        XCTAssertEqual(NoiseSuppressionMode.stored(in: defaults), .rnnoise)
        defaults.set("none", forKey: NoiseSuppressionMode.preferenceKey)
        XCTAssertEqual(NoiseSuppressionMode.stored(in: defaults), .none)
        let client = Client.shared
        let original = client.noiseSuppression
        let stored = UserDefaults.standard.object(forKey: NoiseSuppressionMode.preferenceKey)
        defer {
            client.setNoiseSuppression(original)
            if let stored { UserDefaults.standard.set(stored, forKey: NoiseSuppressionMode.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: NoiseSuppressionMode.preferenceKey) }
        }
        client.setNoiseSuppression(.none)
        XCTAssertEqual(client.noiseSuppression, .none)
        XCTAssertEqual(UserDefaults.standard.string(forKey: NoiseSuppressionMode.preferenceKey), "none")
        client.setNoiseSuppression(.rnnoise)
        XCTAssertEqual(client.noiseSuppression, .rnnoise)
        XCTAssertEqual(UserDefaults.standard.string(forKey: NoiseSuppressionMode.preferenceKey), "rnnoise")
    }
    @MainActor func testBookmarkPersistenceDeduplicationAndDeletion() throws {
        let client = Client.shared
        let host = "test-" + UUID().uuidString.lowercased() + ".invalid"
        let first = try client.saveBookmark(id: nil, title: "Test", host: host, port: "9987", nickname: "Tester", password: "secret")
        defer { if let existing = client.bookmarks.first(where: { $0.id == first.id }) { client.deleteBookmark(existing) } }
        XCTAssertEqual(try client.bookmarkPassword(first), "secret")
        let updated = try client.saveBookmark(id: nil, title: "Updated", host: host.uppercased(), port: "9987", nickname: "Tester", password: "new")
        XCTAssertEqual(updated.id, first.id)
        XCTAssertEqual(try client.bookmarkPassword(updated), "new")
        let saved = try XCTUnwrap(UserDefaults.standard.data(forKey: "mobilespeak.server.bookmarks"))
        let restored = try JSONDecoder().decode([Bookmark].self, from: saved)
        XCTAssertEqual(restored.first { $0.id == first.id }?.title, "Updated")
        XCTAssertNil(restored.first { $0.id == first.id }?.password)
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("new"))
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("\"password\""))
        let automaticHost = "automatic-" + UUID().uuidString.lowercased() + ".invalid"
        let automatic = try client.saveBookmark(id: nil, title: " ", host: automaticHost, port: "9987", nickname: "Auto", password: "")
        defer { if let existing = client.bookmarks.first(where: { $0.id == automatic.id }) { client.deleteBookmark(existing) } }
        XCTAssertEqual(automatic.title, automaticHost)
        XCTAssertEqual(automatic.automaticallyNamed, true)
        let originalAddress = client.address, originalName = client.name
        defer { client.address = originalAddress; client.name = originalName }
        client.address = automatic.address; client.name = automatic.nickname
        client.updateAutomaticBookmarkTitle("TeamSpeak Server")
        XCTAssertEqual(client.bookmarks.first { $0.id == automatic.id }?.title, "TeamSpeak Server")
        XCTAssertThrowsError(try client.saveBookmark(id: first.id, title: "Bad", host: "bad/path", port: "0", nickname: "", password: ""))
        client.deleteBookmark(updated)
        XCTAssertFalse(client.bookmarks.contains { $0.id == first.id })
        XCTAssertEqual(try client.bookmarkPassword(updated), "")
    }
    @MainActor func testPhysicalAudioInputIsReadyBeforeUnmuting() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Requires a physical iPhone audio input")
        #else
        let audio = PhoneAudio(handle: Client.shared.handle)
        defer { audio.stop() }
        try audio.start()
        XCTAssertTrue(audio.isVoiceProcessingEnabled)
        let format = try XCTUnwrap(audio.inputFormat)
        XCTAssertGreaterThan(format.sampleRate, 0)
        XCTAssertGreaterThan(format.channelCount, 0)
        try audio.ensureRunning()
        // No connection: PCM is never transmitted or saved by this hardware check.
        try audio.startCapture()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertGreaterThan(audio.frameCounts.rendered, 0)
        XCTAssertGreaterThan(audio.frameCounts.captured, 0)
        let beforeResume = audio.frameCounts
        audio.pauseForInterruption()
        try audio.ensureRunning()
        try audio.startCapture()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertGreaterThan(audio.frameCounts.rendered, beforeResume.rendered)
        XCTAssertGreaterThan(audio.frameCounts.captured, beforeResume.captured)
        audio.stopCapture()
        #endif
    }
    @MainActor func testChannelOrderingHandlesPredecessorsAndCycles() throws {
        let client = Client.shared
        let original = client.state
        defer { client.state = original }
        client.state.channels = [
            Channel(id: 2, parent: 0, order: 1, name: "Second", password: false, permanent: true, key: "2", icon: nil, iconPath: nil),
            Channel(id: 3, parent: 1, order: 0, name: "Child", password: false, permanent: true, key: "3", icon: nil, iconPath: nil),
            Channel(id: 1, parent: 0, order: 0, name: "First", password: false, permanent: true, key: "1", icon: nil, iconPath: nil),
            Channel(id: 4, parent: 4, order: 0, name: "Cycle", password: false, permanent: true, key: "4", icon: nil, iconPath: nil)
        ]
        XCTAssertEqual(client.orderedChannels.map { $0.channel.id }, [1, 3, 2, 4])
        XCTAssertEqual(client.orderedChannels.map { $0.depth }, [0, 1, 0, 0])
        client.connect(host: "invalid/address", port: "9987", nickname: "Test", password: "")
        XCTAssertFalse(client.busy)
        XCTAssertNotNil(client.error)
        client.error = nil
    }
    func testSnapshotAndChatModelsDecodeSharedBridgePayload() throws {
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(#"{"status":"connected","server":"Test","serverId":"server-uid","ownClient":1,"canSend":true,"channels":[{"id":2,"parent":0,"order":0,"name":"Lobby","password":false,"permanent":true,"key":"id:2","icon":42,"iconPath":"/cache/icon.img"}],"clients":[{"id":1,"channel":2,"uid":"client-uid","name":"Me","avatarHash":"hash","avatarPath":"/cache/avatar.img","badges":[{"id":"badge-id","name":"Badge","description":"Known badge","filename":"badge","iconPath":"/cache/badge.png"}],"serverGroupIcons":[{"id":6,"name":"Server Admin","iconId":300,"iconPath":null},{"id":14,"name":"DJ","iconId":166401896,"iconPath":"/cache/dj.img"}],"channelGroupIcon":{"id":5,"name":"Channel Admin","iconId":100,"iconPath":null},"muted":true,"deafened":false,"speaking":false}]}"#.utf8))
        XCTAssertEqual(snapshot.serverId, "server-uid")
        XCTAssertEqual(snapshot.channels.first?.conversation, "channel:id:2")
        XCTAssertEqual(snapshot.clients.first?.conversation, "client:client-uid")
        XCTAssertEqual(snapshot.clients.first?.badges.first?.name, "Badge")
        XCTAssertEqual(snapshot.clients.first?.serverGroupIcons.first?.builtinLabel, "S")
        XCTAssertEqual(snapshot.clients.first?.serverGroupIcons.last?.iconPath, "/cache/dj.img")
        XCTAssertEqual(snapshot.clients.first?.channelGroupIcon?.builtinLabel, "C")
        XCTAssertTrue(try XCTUnwrap(snapshot.clients.first?.channelGroupIcon?.canDisplay))
        XCTAssertFalse(GroupIcon(id: 1, name: "Unknown", iconId: 42, iconPath: nil).canDisplay)
        let messages = try JSONDecoder().decode([ChatMessage].self, from: Data(#"[{"id":"1","conversation":"client:client-uid","targetId":"client-uid","targetName":"Me","senderUid":"client-uid","senderName":"Me","senderAvatarHash":"hash","avatarPath":null,"own":true,"text":"hello","timestamp":1,"status":"sent","error":null}]"#.utf8))
        XCTAssertEqual(messages.first?.status, .sent)
        let unread = try JSONDecoder().decode(UnreadSnapshot.self, from: Data(#"{"serverId":"server-uid","channel":"channel:id:2","channelCount":3,"privateCounts":{"client-uid":2}}"#.utf8))
        XCTAssertEqual(unread.channelCount, 3)
        XCTAssertEqual(unread.privateCounts["client-uid"], 2)
    }
    @MainActor func testTemporaryReconnectIsBusyWithoutPretendingToBeConnected() {
        let client = Client.shared
        let previous = client.state
        defer { client.state = previous }
        client.state = Snapshot(status: "reconnecting")
        XCTAssertTrue(client.busy)
        XCTAssertFalse(client.connected)
    }
    func testSpacerRecognitionUsesOfficialEligibilityAndMarkers() throws {
        func channel(_ name: String, parent: UInt64 = 0, permanent: Bool = true) -> Channel {
            Channel(id: 1, parent: parent, order: 0, name: name, password: false, permanent: permanent, key: "1", icon: nil, iconPath: nil)
        }
        XCTAssertEqual(try XCTUnwrap(channel("[spacer0]").spacer).text, "")
        let centered = try XCTUnwrap(channel("[cspacerHeading]公告").spacer)
        XCTAssertEqual(centered.text, "公告")
        XCTAssertEqual(centered.alignment, .center)
        XCTAssertEqual(channel("[*spacer0]-").spacer?.alignment, .repeatFill)
        XCTAssertNil(channel("[spacer0]", parent: 1).spacer)
        XCTAssertNil(channel("[spacer0]", permanent: false).spacer)
        XCTAssertNil(channel("[not-a-spacer]").spacer)
    }
    @MainActor func testSpeakerMutePreservesIndependentMicrophoneIntent() {
        let deafenedWhileLive = Client.resolveAudioState(microphoneMuted: false, deafened: false, input: nil, output: true)
        XCTAssertFalse(deafenedWhileLive.microphoneMuted)
        XCTAssertTrue(deafenedWhileLive.inputMuted)
        let restoredLive = Client.resolveAudioState(microphoneMuted: deafenedWhileLive.microphoneMuted, deafened: deafenedWhileLive.deafened, input: nil, output: false)
        XCTAssertFalse(restoredLive.inputMuted)

        let deafenedWhileMuted = Client.resolveAudioState(microphoneMuted: true, deafened: false, input: nil, output: true)
        let restoredMuted = Client.resolveAudioState(microphoneMuted: deafenedWhileMuted.microphoneMuted, deafened: deafenedWhileMuted.deafened, input: nil, output: false)
        XCTAssertTrue(restoredMuted.microphoneMuted)
        XCTAssertTrue(restoredMuted.inputMuted)
    }
    @MainActor func testAudioIntentCanChangeWhileDisconnected() async {
        let client = Client.shared
        let originalState = client.state
        let originalMicrophoneMuted = client.microphoneMuted
        let originalDeafened = client.deafened
        defer { client.state = originalState }
        client.state = Snapshot()
        await client.setAudio(input: true, output: true)
        XCTAssertTrue(client.microphoneMuted)
        XCTAssertTrue(client.deafened)
        await client.setAudio(input: false, output: false)
        XCTAssertFalse(client.microphoneMuted)
        XCTAssertFalse(client.deafened)
        await client.setAudio(input: originalMicrophoneMuted, output: originalDeafened)
    }
    func testChannelSheetGrowsWithMeasuredContentAndCapsAtViewport() {
        let empty = HomeView.channelSheetLayout(memberHeight: 52, viewportHeight: 800)
        let few = HomeView.channelSheetLayout(memberHeight: 220, viewportHeight: 800)
        let many = HomeView.channelSheetLayout(memberHeight: 2_000, viewportHeight: 800)
        let landscape = HomeView.channelSheetLayout(memberHeight: 2_000, viewportHeight: 320)

        XCTAssertEqual(empty.sheet, 224)
        XCTAssertGreaterThan(few.sheet, empty.sheet)
        XCTAssertEqual(many.sheet, 624)
        XCTAssertEqual(many.list, many.sheet - 172)
        XCTAssertLessThan(landscape.sheet, many.sheet)
    }
    func testChannelSheetWaitsForDismissalBeforePresentingAnotherChannel() {
        func channel(_ id: UInt64) -> Channel {
            Channel(id: id, parent: 0, order: 0, name: "Channel \(id)", password: false, permanent: true, key: "\(id)", icon: nil, iconPath: nil)
        }
        var sheet = ChannelSheetState()
        sheet.select(channel(1))
        sheet.select(channel(2))
        XCTAssertEqual(sheet.selected?.id, 1)
        sheet.selected = nil // SwiftUI begins the interactive dismissal.
        sheet.select(channel(3))
        XCTAssertNil(sheet.selected)
        XCTAssertEqual(sheet.queued?.id, 3)
        let next = sheet.didDismiss()
        XCTAssertFalse(sheet.active)
        XCTAssertNil(sheet.queued)
        XCTAssertEqual(next?.id, 3)
        if let next { sheet.select(next) }
        XCTAssertEqual(sheet.selected?.id, 3)
        XCTAssertNil(sheet.didDismiss())
    }
}

@MainActor private final class ClearAlertFixtureModel: ObservableObject {
    @Published var presented = false
}
private struct ClearAlertFixture: View {
    @ObservedObject var model: ClearAlertFixtureModel
    let connected: Bool
    let clear: () -> Void
    var body: some View {
        Color(uiColor: .systemBackground).avatarClearConfirmation(isPresented: $model.presented, connected: connected, clear: clear)
    }
}

private struct FirstAvatarPickerFixture: View {
    let trigger: PassthroughSubject<Void, Never>
    @State private var selection: AvatarSelectionRequest?
    var body: some View {
        Color.clear
            .onReceive(trigger) { selection = AvatarSelectionRequest(id: Client.shared.beginAvatarSelection()) }
            .avatarSelectionSheet(selection: $selection)
    }
}
