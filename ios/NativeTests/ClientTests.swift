import XCTest
import UIKit
import ImageIO
import SwiftUI
import PhotosUI
import Combine
@testable import MobileSpeak

final class ClientTests: XCTestCase {
    func testChannelPreviewOnlyIncludesCompleteAvatarsAndReservesSummary() {
        let count = ChannelMemberPreview.avatarCount
        XCTAssertEqual(count(1_000, 70, 0), 0)
        XCTAssertEqual(count(99, 70, 1), 1)
        XCTAssertEqual(count(215, 70, 5), 5)
        XCTAssertEqual(count(186, 70, 5), 4)
        XCTAssertEqual(count(CGFloat(186).nextDown, 70, 5), 3)
        XCTAssertEqual(count(98, 70, 5), 0)
        XCTAssertEqual(count(69, 70, 5), 0)
        XCTAssertEqual(count(1_000, 70, 1), 1)
    }
    func testChannelPreviewNicknameOnlyUsesSingleMemberAndRemainingSpace() {
        let width = ChannelMemberPreview.nicknameWidth
        XCTAssertEqual(width(1_000, 70, 0, 100, 20), 0)
        XCTAssertEqual(width(1_000, 70, 2, 100, 20), 0)
        XCTAssertEqual(width(133, 70, 5, 100, 20), 0)
        XCTAssertEqual(width(1_000, 70, 1, 0, 20), 0)
        XCTAssertEqual(width(1_000, 70, 1, 100, 20), 100)
        XCTAssertEqual(width(124, 70, 1, 100, 20), 20)
        XCTAssertEqual(width(CGFloat(124).nextDown, 70, 1, 100, 20), 0)
        XCTAssertEqual(width(98, 70, 1, 100, 20), 0)
        XCTAssertEqual(width(109, 70, 1, 5, 20), 5)
    }
    @MainActor func testChannelCardsAdaptToWidthLanguageAndMembership() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        let saved = client.state, savedImporting = client.fileImporting
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        client.fileImporting = true
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let container = UIViewController()
        window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view); host.didMove(toParent: container)
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true; client.state = saved; client.fileImporting = savedImporting
            if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        try await Task.sleep(nanoseconds: 200_000_000)
        let names = ["Current channel", "Ordinary channel", "Empty channel", "Empty child", "Deep channel"]
        let channels: [Channel] = (1...5).map { (id: Int) -> Channel in
            let parent: UInt64 = id == 1 ? 0 : UInt64(id - 1)
            return Channel(id: UInt64(id), parent: parent, order: 0, name: names[id - 1],
                password: false, permanent: true, key: "fixture-\(id)", icon: nil, iconPath: nil)
        }
        func labels(_ view: UIView) -> [UILabel] { (view as? UILabel).map { [$0] } ?? view.subviews.flatMap(labels) }
        for language in ["en", "zh-Hans"] {
            UserDefaults.standard.set(language, forKey: AppLanguage.preferenceKey)
            for (width, category, weight) in [(CGFloat(320), ContentSizeCategory.large, LegibilityWeight.regular),
                                               (280, .large, .regular), (390, .accessibilityLarge, .bold)] {
                host.view.frame = CGRect(x: 0, y: 0, width: width, height: 844)
                host.rootView = AnyView(NavigationView { HomeView(client: client) }.navigationViewStyle(.stack)
                    .environmentObject(LanguageSettings()).environment(\.sizeCategory, category).environment(\.legibilityWeight, weight)
                    .preferredColorScheme(.dark).tint(Palette.accent))
                let cases = [(5, "A0 User"), (9, "A0 User"), (10, "A0 User"), (100, "A0 User"),
                             (0, "A0 User"), (1, "Solo User"), (2, "Solo User"), (1, "王小明"),
                             (1, "👩🏽‍💻é用户 " + String(repeating: "long nickname ", count: 12)),
                             (1, ""), (1, "Server renamed user"), (2, "Server renamed user"), (1, "Solo User"), (0, ""), (5, "A0 User")]
                for (caseIndex, fixture) in cases.enumerated() {
                    let (total, nickname) = fixture
                    let members = [(1, 1), (2, total), (5, 10)].flatMap { (channel, count) in
                        (0..<count).map { Member(id: UInt64(channel * 1000 + $0), channel: UInt64(channel), uid: "fixture-\(channel)-\($0)",
                            name: channel == 2 && count == 1 ? nickname : "A\($0) User", avatarHash: "", avatarPath: nil, badges: [], serverGroupIcons: [], channelGroupIcon: nil,
                            muted: caseIndex == 7, deafened: caseIndex == 8, speaking: $0 == 0, away: caseIndex == 10) }
                    }
                    client.state = Snapshot(status: "connected", server: "Preview fixture", ownClient: 1000, channels: channels, clients: members)
                    try await Task.sleep(nanoseconds: 150_000_000)
                    host.view.layoutIfNeeded()
                    let summaries = labels(host.view).filter { $0.text?.hasPrefix("· ") == true }.sorted {
                        $0.convert($0.bounds, to: host.view).minY < $1.convert($1.bounds, to: host.view).minY
                    }
                    XCTAssertEqual(summaries.count, total == 0 ? 2 : 3, "Empty channels have no preview")
                    let format = UIGraphicsImageRendererFormat(); format.scale = 1
                    let image = UIGraphicsImageRenderer(size: host.view.bounds.size, format: format).image { _ in
                        host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
                    }
                    for (index, label) in summaries.enumerated() {
                        let populated = total == 0 ? [(1, 1), (5, 10)] : [(1, 1), (2, total), (5, 10)]
                        let (channel, count) = populated[index]
                        XCTAssertEqual(label.text, L10n.channelMembers(count: count))
                        let rect = label.convert(label.bounds, to: host.view)
                        let start = 58 + CGFloat(channel - 1) * 10
                        let available = width - 138 - CGFloat(channel - 1) * 10
                        let expected = ChannelMemberPreview.avatarCount(width: available, textWidth: label.intrinsicContentSize.width, memberCount: count)
                        let member = members.first { $0.channel == UInt64(channel) }!
                        let nameMeasure = UILabel(); nameMeasure.font = label.font; nameMeasure.text = member.name
                        let minimum = UILabel(); minimum.font = label.font; minimum.text = String(member.name.prefix(1)) + "…"
                        let nameWidth = ChannelMemberPreview.nicknameWidth(width: available, textWidth: label.intrinsicContentSize.width,
                            memberCount: count, nameWidth: member.name.isEmpty ? 0 : nameMeasure.intrinsicContentSize.width,
                            minimumNameWidth: minimum.intrinsicContentSize.width)
                        let namesHere = labels(host.view).filter {
                            $0.text == member.name && abs($0.convert($0.bounds, to: host.view).midY - rect.midY) < 1
                        }
                        XCTAssertEqual(namesHere.count, nameWidth > 0 ? 1 : 0)
                        if let name = namesHere.first {
                            let nameRect = name.convert(name.bounds, to: host.view)
                            XCTAssertEqual(nameRect.minX, start + 29, accuracy: 1)
                            XCTAssertEqual(nameRect.width, nameWidth, accuracy: 1)
                            XCTAssertEqual(name.font.pointSize, 11); XCTAssertEqual(name.font, label.font)
                            XCTAssertEqual(name.numberOfLines, 1); XCTAssertEqual(name.lineBreakMode, .byTruncatingTail)
                        }
                        XCTAssertEqual(rect.minX, start + CGFloat(expected) * 29 + (nameWidth > 0 ? nameWidth + 5 : 0), accuracy: 1)
                        XCTAssertLessThanOrEqual(rect.maxX, start + available + 1)
                    }
                    if total == 5 || total == 1 || total == 2 || total == 0 {
                        let name = "channel-preview-\(language)-\(Int(width))-\(category)-\(weight)-case-\(caseIndex)"
                        try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(name + ".png"))
                        let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
                    }
                }
            }
        }
    }
    func testUserVolumeBridgeDefaultsAndIdentityToken() throws {
        let old = #"{"id":7,"channel":9,"uid":"stable","name":"User","avatarHash":"","avatarPath":null,"badges":[],"serverGroupIcons":[],"channelGroupIcon":null,"muted":false,"deafened":false,"speaking":false}"#
        let member = try JSONDecoder().decode(Member.self,from:Data(old.utf8))
        XCTAssertEqual(member.volumePercent ?? 100,100); XCTAssertNil(member.volumeToken)
        let current = String(old.dropLast()) + #", "volumePercent":143,"volumeToken":"instance-7"}"#
        let saved = try JSONDecoder().decode(Member.self,from:Data(current.utf8))
        XCTAssertEqual(saved.volumePercent,143); XCTAssertEqual(saved.volumeRowId,"instance-7")
        let maximum = try JSONDecoder().decode(Member.self,from:Data(current.replacingOccurrences(of:"\"volumePercent\":143",with:"\"volumePercent\":300").utf8))
        XCTAssertEqual(maximum.volumePercent,300)
        XCTAssertEqual(saved.uid,member.uid)
    }
    @MainActor func testUserVolumesLongListAndEmptyStateAtLargeFonts() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        let saved = client.state, savedError = client.error
        let language = UserDefaults.standard.string(forKey: AppLanguage.preferenceKey)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene:scene)
        let container = UIViewController()
        window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view)
        host.view.frame = CGRect(x:0,y:0,width:320,height:568); host.didMove(toParent:container)
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true; client.state = saved; client.error = savedError
            if let language { UserDefaults.standard.set(language,forKey:AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey:AppLanguage.preferenceKey) }
        }
        try await Task.sleep(nanoseconds:200_000_000)
        let members: [Member] = (1...41).map { (id: Int) -> Member in
            let channel: UInt64 = id % 2 == 0 ? 9 : 7
            let name = id == 1 ? "SELF MUST BE EXCLUDED" : "User \(id)"
            return Member(id:UInt64(id),channel:channel,uid:"fixture-\(id)",name:name,avatarHash:"",avatarPath:nil,badges:[],serverGroupIcons:[],channelGroupIcon:nil,muted:false,deafened:false,speaking:false,volumePercent:id == 2 ? 300 : 100,volumeToken:"instance-\(id)")
        }
        func scrolls(_ v:UIView)->[UIScrollView] { (v as? UIScrollView).map { [$0] } ?? v.subviews.flatMap(scrolls) }
        for lang in ["en","zh-Hans"] {
            UserDefaults.standard.set(lang,forKey:AppLanguage.preferenceKey)
            for category in [ContentSizeCategory.large,.accessibilityLarge,.accessibilityExtraExtraExtraLarge] {
                client.state = Snapshot(status:"connected",server:"Fixture server",ownClient:1,clients:members)
                host.rootView = AnyView(UserVolumesDrawer(client:client,onBack:{}).frame(height:300).foregroundStyle(Color(hex:0xF2F3F5)).background(Palette.bottom).tint(Palette.accent).environment(\.sizeCategory,category))
                try await Task.sleep(nanoseconds:200_000_000)
                host.view.layoutIfNeeded()
                let scroll = try XCTUnwrap(scrolls(host.view).first)
                XCTAssertGreaterThan(scroll.contentSize.height,scroll.bounds.height)
                let frame = host.view.frame
                scroll.setContentOffset(CGPoint(x:0,y:100),animated:false)
                XCTAssertEqual(host.view.frame,frame); XCTAssertGreaterThan(scroll.contentOffset.y,0)
                scroll.setContentOffset(.zero,animated:false)
                let image = UIGraphicsImageRenderer(size:host.view.bounds.size).image { _ in host.view.drawHierarchy(in:host.view.bounds,afterScreenUpdates:true) }
                let name = "user-volumes-\(lang)-\(category)"
                try image.pngData()?.write(to:FileManager.default.temporaryDirectory.appendingPathComponent(name+".png"))
                let attachment = XCTAttachment(image:image); attachment.name=name; attachment.lifetime = .keepAlways; add(attachment)
                client.state.clients = Array(members.prefix(1))
                try await Task.sleep(nanoseconds:100_000_000)
                XCTAssertLessThanOrEqual(scroll.contentSize.height,scroll.bounds.height+1)
            }
        }
    }

    @MainActor func testFileCacheSettingsLayoutsAtLargeTextSizes() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        let savedCache = client.fileCache
        let savedImporting = client.fileImporting
        let savedLanguage = UserDefaults.standard.string(forKey: AppLanguage.preferenceKey)
        client.fileImporting = true // A layout fixture must never query or delete actual app files.
        defer {
            client.fileCache = savedCache; client.fileImporting = savedImporting
            if let savedLanguage { UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        try await Task.sleep(nanoseconds: 200_000_000)
        for language in ["en", "zh-Hans"] {
            UserDefaults.standard.set(language, forKey: AppLanguage.preferenceKey)
            for category in [ContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                client.fileCache = FileCacheState(bytes: 16_454_096, items: 2, status: "ready")
                let host = UIHostingController(rootView: ScrollView {
                    FileCacheSettings(client: client).padding(20)
                }.foregroundStyle(.white).background(Palette.background).environment(\.sizeCategory, category))
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
                window.rootViewController = host; window.makeKeyAndVisible()
                defer { window.isHidden = true }
                try await Task.sleep(nanoseconds: 150_000_000)
                host.view.layoutIfNeeded()
                let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                let name = "file-cache-\(language)-\(category)"
                try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(name + ".png"))
                let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
                XCTAssertFalse(client.fileCachePending)
                XCTAssertEqual(client.fileCache.bytes, 16_454_096)
            }
        }
    }

    func testFileCacheRequiresKnownIdleSizeAndAllowsZeroByteFiles() throws {
        XCTAssertFalse(FileCacheState().canClear)
        var cache = try JSONDecoder().decode(FileCacheState.self, from: Data(#"{"bytes":0,"items":1,"status":"ready","busy":false,"error":null}"#.utf8))
        XCTAssertTrue(cache.canClear)
        cache.busy = true; XCTAssertFalse(cache.canClear); cache.busy = false
        for status in ["loading", "clearing"] { cache.status = status; XCTAssertFalse(cache.canClear) }
        cache.status = "failed"; cache.error = "partial cleanup"; XCTAssertTrue(cache.canClear)
        cache.items = 0; XCTAssertFalse(cache.canClear)
        cache.bytes = 6; XCTAssertTrue(cache.canClear)
        cache.bytes = nil; XCTAssertFalse(cache.canClear)
    }

    func testNetworkQualityBridgeAndNumberFormatting() throws {
        let data = Data(#"{"status":"connected","channels":[],"clients":[],"networkQuality":{"rttMs":42.5,"deviationMs":6.05,"packetLossPercent":4.0,"rttGrade":"good","deviationGrade":"good","packetLossGrade":"poor","iconGrade":"poor","axisMaxMs":2000,"nowSecond":33,"samples":[{"second":29,"rttMs":1234,"grade":"poor"},{"second":31,"rttMs":50,"grade":"good","packetLossPercent":null,"packetLossGrade":null},{"second":33,"rttMs":42.5,"grade":"good","packetLossPercent":2.004,"packetLossGrade":"fair"}]}}"#.utf8)
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: data)
        let q = try XCTUnwrap(snapshot.networkQuality)
        XCTAssertEqual(q.latencyText, "43"); XCTAssertEqual(q.deviationText, "6.1"); XCTAssertEqual(q.lossText, "4.0")
        XCTAssertEqual(q.iconGrade, .poor); XCTAssertEqual(q.rttGrade, .good)
        XCTAssertEqual(q.samples.map(\.second), [29, 31, 33]); XCTAssertEqual(q.axisMaxMs, 2000)
        XCTAssertNil(q.samples[0].packetLossFraction); XCTAssertNil(q.samples[1].packetLossFraction)
        XCTAssertEqual(q.samples[2].packetLossPercent, 2.004); XCTAssertEqual(q.samples[2].packetLossGrade, .fair)
        XCTAssertEqual(try XCTUnwrap(q.samples[2].packetLossFraction), 0.02004, accuracy: 1e-12)
        XCTAssertTrue(q.chartAccessibilitySummary.contains("2.0"))
        for fields in [#","packetLossPercent":2"#, #","packetLossGrade":"fair""#, #","packetLossPercent":null,"packetLossGrade":"fair""#, #","packetLossPercent":2,"packetLossGrade":null"#] {
            let sample = try JSONDecoder().decode(NetworkSample.self, from: Data((#"{"second":0,"rttMs":50,"grade":"good""# + fields + "}").utf8))
            XCTAssertNil(sample.packetLossFraction)
        }
        XCTAssertEqual(NetworkQuality.number(0, decimals: 1), "0.0")
        XCTAssertEqual(NetworkQuality.number(1234, decimals: 0), "1234")
        XCTAssertEqual(NetworkQuality.number(123.4, decimals: 1), "123.4")
        XCTAssertEqual(NetworkQuality.number(1e22, decimals: 0), "10000000000000000000000")
        XCTAssertEqual(NetworkQuality().latencyText, "—")
        XCTAssertEqual(NetworkQuality.number(.nan, decimals: 1), "—")
        for (value, expected) in [(998.49, "998"), (998.5, "999"), (999.5, "999"), (1234.0, "999"), (1e22, "999")] {
            XCTAssertEqual(NetworkQuality(rttMs: value).latencyText, expected)
        }
        for (value, expected) in [(999.84, "999.8"), (999.85, "999.9"), (999.95, "999.9"), (1234.0, "999.9"), (1e22, "999.9")] {
            XCTAssertEqual(NetworkQuality(deviationMs: value).deviationText, expected)
        }
        XCTAssertEqual(NetworkQuality(rttMs: .infinity, deviationMs: .nan).latencyText, "—")
        XCTAssertEqual(NetworkQuality(rttMs: .infinity, deviationMs: .nan).deviationText, "—")
        XCTAssertEqual(NetworkQuality().axisMaxMs, 10)
        for (axis, middle) in [(10.0, "5"), (50.0, "25"), (100.0, "50"), (750.0, "375"), (1359.0, "679.5")] {
            let quality = NetworkQuality(axisMaxMs: axis)
            XCTAssertEqual(quality.axisText, NetworkQuality.number(axis, decimals: 0))
            XCTAssertEqual(quality.midAxisText, middle)
        }
        XCTAssertNil(try JSONDecoder().decode(Snapshot.self, from: Data(#"{"status":"disconnected","channels":[],"clients":[],"networkQuality":null}"#.utf8)).networkQuality)
    }

    func testNetworkLossPaletteAndRawDrawingFractions() throws {
        for (grade, loss, network) in [(NetworkGrade.good, 0x3DBE78, 0x3DBE78), (.fair, 0xFF8000, 0xE9B44C), (.poor, 0x581C87, 0xFF375F)] {
            XCTAssertEqual(UIColor(grade.packetLossColor), UIColor(Color(hex: UInt32(loss))))
            XCTAssertEqual(UIColor(grade.color), UIColor(Color(hex: UInt32(network))))
        }
        for (percent, fraction) in [(0.0, 0.0), (2.004, 0.02004), (10, 0.1), (100, 1), (120, 1), (0.0001, 0.000001)] {
            let sample = NetworkSample(second: 0, rttMs: 50, grade: .good, packetLossPercent: percent, packetLossGrade: .poor)
            XCTAssertEqual(try XCTUnwrap(sample.packetLossFraction), fraction, accuracy: 1e-12)
            XCTAssertEqual(sample.packetLossPercent, percent)
        }
        for percent: Double? in [nil, .nan, .infinity, -1] {
            XCTAssertNil(NetworkSample(second: 0, rttMs: 50, grade: .good, packetLossPercent: percent, packetLossGrade: .poor).packetLossFraction)
        }
        XCTAssertNil(NetworkSample(second: 0, rttMs: 50, grade: .good, packetLossPercent: 2).packetLossFraction)
    }

    @MainActor func testNetworkHistoryHardEdgesAndNativeStateChanges() async throws {
        var quality = NetworkQuality(axisMaxMs: 200, nowSecond: 29)
        quality.samples = (0..<30).filter { $0 != 14 }.map { second in
            let percent: Double? = [0: 0, 1: 2, 2: 10, 3: 100, 4: 2, 5: 120, 7: -1,
                8: .nan, 9: .infinity, 10: 2, 11: 0.0001, 13: 100, 15: 50][second] ?? (second == 6 ? nil : 10)
            return NetworkSample(second: UInt64(second), rttMs: second == 4 ? 150 : second == 13 ? 0 : second == 15 ? 2 : 50,
                grade: second == 4 ? .fair : .good, packetLossPercent: percent,
                packetLossGrade: second == 6 || second == 10 ? nil : second == 1 || second == 4 ? .fair : .poor)
        }
        let colors = [[61,190,120], [233,180,76], [255,55,95], [255,128,0], [88,28,135]]
        let host = UIHostingController(rootView: NetworkQualityPanel(quality: quality).chart.frame(width: 366, height: 240).background(Palette.bottom).ignoresSafeArea())
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 366, height: 240))
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        func render(_ name: String) async throws -> (UIImage, [UInt8], Int, Int) {
            host.rootView = NetworkQualityPanel(quality: quality).chart.frame(width: 366, height: 240).background(Palette.bottom).ignoresSafeArea()
            try await Task.sleep(nanoseconds: 200_000_000)
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
            try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(name + ".png"))
            let cg = try XCTUnwrap(image.cgImage)
            let context = try XCTUnwrap(CGContext(data: nil, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
            let bytes = Array(UnsafeBufferPointer(start: try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self), count: cg.width * cg.height * 4))
            return (image, bytes, cg.width, cg.height)
        }
        func matches(_ bytes: [UInt8], _ width: Int, _ x: Int, _ y: Int, _ color: [Int]) -> Bool {
            (0..<3).allSatisfy { abs(Int(bytes[(y * width + x) * 4 + $0]) - color[$0]) <= 4 }
        }
        func bars(_ bytes: [UInt8], _ width: Int, _ height: Int, _ scale: CGFloat) -> [ClosedRange<Int>] {
            let y = height - Int(scale) - 2
            var ranges: [ClosedRange<Int>] = []
            var start: Int?
            for x in 0...width {
                let filled = x < width && colors.contains { matches(bytes, width, x, y, $0) }
                if filled && start == nil { start = x }
                if !filled, let left = start { ranges.append(left...(x - 1)); start = nil }
            }
            return ranges
        }
        let (image, bytes, width, height) = try await render("loss-history-normal")
        let ranges = bars(bytes, width, height, image.scale)
        XCTAssertEqual(ranges.count, quality.samples.count)
        guard ranges.count == quality.samples.count else { return }
        for (sample, range) in zip(quality.samples, ranges) {
            let x = (range.lowerBound + range.upperBound) / 2
            let latency = sample.grade == .fair ? colors[1] : colors[0]
            let loss = sample.packetLossGrade == .fair ? colors[3] : colors[4]
            let rows = (0..<height).filter { y in colors.contains { matches(bytes, width, x, y, $0) } }
            let first = try XCTUnwrap(rows.first), last = try XCTUnwrap(rows.last)
            let p = sample.packetLossFraction ?? 0
            let count = last - first + 1
            if p == 0 {
                XCTAssertTrue(rows.allSatisfy { matches(bytes, width, x, $0, latency) }, "Fallback/zero sample \(sample.second)")
            } else if p == 1 {
                XCTAssertTrue(rows.allSatisfy { matches(bytes, width, x, $0, loss) }, "Full-loss sample \(sample.second)")
            } else {
                let boundary = Double(last + 1) - Double(count) * p
                for y in first...last where abs(Double(y) + 0.5 - boundary) > 1.5 {
                    XCTAssertTrue(matches(bytes, width, x, y, Double(y) + 0.5 < boundary ? latency : loss), "Hard edge sample \(sample.second), y=\(y), boundary=\(boundary)")
                }
                let lossRows = rows.filter { matches(bytes, width, x, $0, loss) }.count
                XCTAssertEqual(Double(lossRows), Double(count) * p, accuracy: 1.5, "Raw ratio sample \(sample.second)")
            }
            if sample.second == 3 {
                XCTAssertFalse(matches(bytes, width, range.lowerBound, first, loss), "Rounded top corner")
                XCTAssertTrue(matches(bytes, width, range.lowerBound, last - 1, loss), "Flat baseline")
            }
            if sample.second == 13 { XCTAssertLessThanOrEqual(count, Int(image.scale) + 1) }
        }
        let firstCenter = (ranges[0].lowerBound + ranges[0].upperBound) / 2
        let secondCenter = (ranges[1].lowerBound + ranges[1].upperBound) / 2
        let gapCenter = secondCenter + (secondCenter - firstCenter) * 13
        XCTAssertFalse(colors.contains { matches(bytes, width, gapCenter, height - Int(image.scale) - 2, $0) }, "Missing second stays empty")
        quality.nowSecond = 30
        let (advancedImage, advancedBytes, advancedWidth, advancedHeight) = try await render("loss-history-advanced")
        let advancedRanges = bars(advancedBytes, advancedWidth, advancedHeight, advancedImage.scale)
        XCTAssertEqual(advancedRanges.count, ranges.count - 1)
        let advancedLast = try XCTUnwrap(advancedRanges.last), originalLast = try XCTUnwrap(ranges.last)
        XCTAssertEqual(Double(advancedLast.lowerBound + advancedLast.upperBound) / 2,
            Double(originalLast.lowerBound + originalLast.upperBound) / 2 - Double(secondCenter - firstCenter), accuracy: 2)
        quality.axisMaxMs = 500
        let (_, scaledBytes, scaledWidth, scaledHeight) = try await render("loss-history-axis")
        let scaledRanges = bars(scaledBytes, scaledWidth, scaledHeight, image.scale)
        let scaledLast = try XCTUnwrap(scaledRanges.last)
        let scaledX = (scaledLast.lowerBound + scaledLast.upperBound) / 2
        let scaledRows = (0..<scaledHeight).filter { y in colors.contains { matches(scaledBytes, scaledWidth, scaledX, y, $0) } }
        XCTAssertLessThan(scaledRows.count, Int(Double(height) * 0.12))
        XCTAssertEqual(Double(scaledRows.filter { matches(scaledBytes, scaledWidth, scaledX, $0, colors[4]) }.count), Double(scaledRows.count) * 0.1, accuracy: 1.5)
        quality.samples = []
        let (emptyImage, emptyBytes, emptyWidth, emptyHeight) = try await render("loss-history-disconnected")
        XCTAssertTrue(bars(emptyBytes, emptyWidth, emptyHeight, emptyImage.scale).isEmpty)
        quality = NetworkQuality(axisMaxMs: 100, nowSecond: 0, samples: [NetworkSample(second: 0, rttMs: 50, grade: .good, packetLossPercent: 100, packetLossGrade: .poor)])
        let (restoredImage, restoredBytes, restoredWidth, restoredHeight) = try await render("loss-history-reconnected")
        XCTAssertEqual(bars(restoredBytes, restoredWidth, restoredHeight, restoredImage.scale).count, 1)
    }

    @MainActor func testNetworkQualityNativeLayoutsAndColors() async throws {
        let originalLanguage = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let originalLanguage { UserDefaults.standard.set(originalLanguage, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        for language in [AppLanguage.english, .simplifiedChinese] {
            UserDefaults.standard.set(language.rawValue, forKey: AppLanguage.preferenceKey)
            for (width, category, long) in [(CGFloat(390), ContentSizeCategory.large, false), (320, .large, true), (240, .large, true), (320, .accessibilityExtraExtraExtraLarge, true)] {
                var q = NetworkQuality(rttMs: long ? 1234 : 42, deviationMs: long ? 1234.5 : 6, packetLossPercent: 4,
                    rttGrade: long ? .poor : .good, deviationGrade: long ? .poor : .good, packetLossGrade: .poor, iconGrade: .poor)
                q.axisMaxMs = 2000; q.nowSecond = 29
                q.samples = (0..<30).filter { $0 != 24 }.map { second -> NetworkSample in
                    let rtt: Double = second == 20 ? 1234 : second == 25 ? 150 : 42
                    let grade: NetworkGrade = second == 20 ? .poor : second == 25 ? .fair : .good
                    return NetworkSample(second: UInt64(second), rttMs: rtt, grade: grade, packetLossPercent: second == 25 ? 2 : 10, packetLossGrade: second == 25 ? .fair : .poor)
                }
                let view = VStack(spacing: 0) { NetworkQualityPanel(quality: q); Spacer(minLength: 0) }
                    .frame(height: 333, alignment: .top).clipped().frame(maxHeight: .infinity, alignment: .top)
                    .background(Palette.bottom).environment(\.sizeCategory, category).environment(\.locale, language.locale)
                let host = UIHostingController(rootView: view)
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 568))
                window.rootViewController = host; window.makeKeyAndVisible()
                defer { window.isHidden = true }
                try await Task.sleep(nanoseconds: 200_000_000)
                host.view.layoutIfNeeded()
                let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                let name = "network-\(language.rawValue)-\(Int(width))-\(category)"
                try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(name + ".png"))
                let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
                // Every grade appears in the chart; verify actual native rendered pixels.
                let cg = try XCTUnwrap(image.cgImage)
                let context = try XCTUnwrap(CGContext(data: nil, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: cg.width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
                context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
                let bytes = try XCTUnwrap(context.data).assumingMemoryBound(to: UInt8.self)
                for color in [[61,190,120], [233,180,76], [255,55,95]] {
                    XCTAssertTrue((0..<cg.width * cg.height).contains { offset in (0..<3).allSatisfy { abs(Int(bytes[offset * 4 + $0]) - color[$0]) < 3 } }, "Missing network color \(color), \(name)")
                }
            }
        }
    }
    @MainActor func testHomeTabsKeepIndependentNativeScrollOffsetsAcrossNavigationAndConnectionChanges() async throws {
        #if targetEnvironment(simulator)
        // XCTest's unit-test host does not enable the SwiftUI accessibility tree itself.
        let library = try XCTUnwrap(dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW))
        let automation = unsafeBitCast(try XCTUnwrap(dlsym(library, "_AXSSetAutomationEnabled")), to: (@convention(c) (Bool) -> Void).self)
        let enabled = unsafeBitCast(try XCTUnwrap(dlsym(library, "_AXSAutomationEnabled")), to: (@convention(c) () -> Bool).self)()
        automation(true)
        defer { automation(enabled); dlclose(library) }
        #else
        throw XCTSkip("Native accessibility automation fixture runs in the simulator")
        #endif
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        // No serverId: chat navigation exercises the UI without sending core commands.
        let original = client.state
        // Keep this synthetic connection fixture isolated from local cache notifications.
        let originalImporting = client.fileImporting
        client.fileImporting = true
        defer { client.fileImporting = originalImporting }
        let channels = (1...160).map { Channel(id: UInt64($0), parent: 0, order: UInt64($0 - 1), name: "Scroll channel \($0)", password: false, permanent: true, key: "\($0)", icon: nil, iconPath: nil) }
        let members = (1...160).map { Member(id: UInt64($0), channel: 20, uid: "scroll-\($0)", name: "Scroll member \($0)", avatarHash: "", avatarPath: nil, badges: [], serverGroupIcons: [], channelGroupIcon: nil, muted: false, deafened: false, speaking: false) }
        let quality = NetworkQuality(rttMs: 96, deviationMs: 35, packetLossPercent: 0.8,
            rttGrade: .good, deviationGrade: .good, packetLossGrade: .good, iconGrade: .good,
            axisMaxMs: 200, nowSecond: 29, samples: (0..<30).map { NetworkSample(second: UInt64($0), rttMs: 96, grade: .good) })
        let snapshot = Snapshot(status: "connected", server: "Scroll fixture", ownClient: 1, channels: channels, clients: members, networkQuality: quality)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let host = UIHostingController(rootView: NavigationView { HomeView(client: client) }
            .navigationViewStyle(.stack).environmentObject(LanguageSettings())
            .preferredColorScheme(.dark).tint(Palette.accent))
        let window = UIWindow(windowScene: scene)
        let container = UIViewController()
        window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view)
        host.view.frame = CGRect(x: 0, y: 0, width: 320, height: 568); host.didMove(toParent: container)
        window.makeKeyAndVisible()
        let savedFiles = client.channelFiles
        defer { window.isHidden = true; client.state = original; client.channelFiles = savedFiles }
        try await Task.sleep(nanoseconds: 200_000_000) // Let initial core configuration settle.
        client.state = snapshot
        try await Task.sleep(nanoseconds: 150_000_000)

        func scrolls(_ view: UIView) -> [UIScrollView] {
            (view as? UIScrollView).map { [$0] } ?? view.subviews.flatMap(scrolls)
        }
        func navigation(_ controller: UIViewController) -> UINavigationController? {
            if let stack = controller as? UINavigationController { return stack }
            return controller.children.lazy.compactMap(navigation).first
        }
        func accessibleElements() -> [NSObject] {
            var visited = Set<ObjectIdentifier>()
            func collect(_ object: NSObject) -> [NSObject] {
                guard visited.insert(ObjectIdentifier(object)).inserted, !object.accessibilityElementsHidden else { return [] }
                if let view = object as? UIView, view.isHidden || view.alpha < 0.01 { return [] }
                let elements = object.accessibilityElements as? [NSObject]
                let count = object.accessibilityElementCount()
                let children = elements ?? (count > 0 && count < 1_000 ? (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject } : [])
                if elements == nil, count == NSNotFound, !object.isAccessibilityElement {
                    return ((object as? UIView)?.subviews ?? []).flatMap(collect)
                }
                return (object.isAccessibilityElement ? [object] : []) + children.flatMap(collect)
            }
            return collect(host.view)
        }
        func activate(_ label: String) throws {
            let buttons = accessibleElements().filter { $0.accessibilityTraits.contains(.button) }
            let element = try XCTUnwrap(buttons.first { $0.accessibilityLabel == label } ?? buttons.first { $0.accessibilityLabel?.contains(label) == true }, label + ": " + accessibleElements().compactMap(\.accessibilityLabel).joined(separator: " | "))
            XCTAssertTrue(element.accessibilityActivate(), label)
        }
        func drawer(_ view: UIView) -> UIView? {
            if view.accessibilityIdentifier == "voice-drawer" { return view }
            return view.subviews.lazy.compactMap(drawer).first
        }
        func checkDrawerHeader(expanded: Bool, screenshot: String) throws {
            let pane = try XCTUnwrap(drawer(host.view))
            let frame = UIAccessibility.convertToScreenCoordinates(pane.bounds, in: pane)
            let elements = accessibleElements()
            let status = L10n.format("status_connected_to_channel", "Scroll channel 20")
            let headerLabels = [status,
                L10n.string(client.microphoneMuted ? "voice_enable_microphone" : "voice_mute"),
                L10n.string(client.deafened ? "voice_enable_listening" : "voice_disable_listening"),
                L10n.string(expanded ? "voice_drawer_collapse" : "voice_drawer_expand")]
            for label in headerLabels {
                let element = try XCTUnwrap(elements.first { $0.accessibilityLabel == label }, label)
                XCTAssertGreaterThanOrEqual(element.accessibilityFrame.minY, frame.minY - 1, label)
                XCTAssertLessThanOrEqual(element.accessibilityFrame.maxY, frame.minY + 65, label)
                XCTAssertFalse(element.accessibilityFrame.isEmpty, label)
            }
            let chart = elements.first { $0.accessibilityLabel == L10n.string("network_chart") }
            if expanded {
                let chart = try XCTUnwrap(chart)
                XCTAssertGreaterThanOrEqual(chart.accessibilityFrame.minY, frame.minY + 64)
                XCTAssertLessThanOrEqual(chart.accessibilityFrame.maxY, frame.maxY + 1)
                let numbers = try XCTUnwrap(elements.first { $0.accessibilityLabel == client.state.networkQuality?.accessibilitySummary })
                XCTAssertEqual(chart.accessibilityFrame.minY, numbers.accessibilityFrame.minY, accuracy: 1)
                XCTAssertEqual(chart.accessibilityFrame.maxY, numbers.accessibilityFrame.maxY, accuracy: 1)
                XCTAssertEqual(chart.accessibilityFrame.minX - numbers.accessibilityFrame.minX, 123, accuracy: 1)
            } else {
                XCTAssertEqual(pane.bounds.height, 64, accuracy: 0.5)
                XCTAssertNil(chart, "Collapsed drawer must not expose network details")
            }
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: image); attachment.name = screenshot; attachment.lifetime = .keepAlways; add(attachment)
            try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(screenshot + ".png"))
        }
        try checkDrawerHeader(expanded: false, screenshot: "drawer-network-collapsed")
        let collapsedDrawerPane = try XCTUnwrap(drawer(host.view))
        let homeScrolls = scrolls(host.view).filter { !$0.isDescendant(of: collapsedDrawerPane) }
        XCTAssertEqual(homeScrolls.count, 3)
        guard homeScrolls.count == 3 else { return }
        let stack = try XCTUnwrap(navigation(host))
        let home = try XCTUnwrap(stack.topViewController)
        let offsets: [CGFloat] = [1_400, 600, 220]
        for (scroll, offset) in zip(homeScrolls, offsets) {
            XCTAssertGreaterThan(scroll.contentSize.height - scroll.bounds.height, offset)
            scroll.setContentOffset(CGPoint(x: 0, y: offset), animated: false)
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        let viewportSizes = homeScrolls.map { $0.bounds.size }
        func checkOffsets() {
            for (index, scroll) in homeScrolls.enumerated() {
                let offset = offsets[index]
                XCTAssertEqual(scroll.contentOffset.y, offset, accuracy: 0.5)
                XCTAssertEqual(scroll.bounds.size, viewportSizes[index])
                XCTAssertTrue(scrolls(host.view).contains { $0 === scroll })
            }
        }
        try activate(L10n.string("voice_drawer_expand"))
        try await Task.sleep(nanoseconds: 500_000_000)
        try checkDrawerHeader(expanded: true, screenshot: "drawer-network-expanded")
        let volumePane = try XCTUnwrap(drawer(host.view))
        let volumeFrame = volumePane.frame
        try activate(L10n.string("volume_title"))
        try await Task.sleep(nanoseconds:150_000_000)
        XCTAssertEqual(volumePane.frame,volumeFrame,"Entering volumes preserves the drawer position")
        let volumeScroll = try XCTUnwrap(scrolls(volumePane).first)
        volumeScroll.setContentOffset(CGPoint(x:0,y:100),animated:false)
        XCTAssertEqual(volumePane.frame,volumeFrame,"Volume content scrolls inside the existing drawer")
        try activate(L10n.string("action_back"))
        try await Task.sleep(nanoseconds:150_000_000)
        XCTAssertEqual(volumePane.frame,volumeFrame,"Returning preserves the drawer position")
        let originalFiles = client.channelFiles
        client.channelFiles = ChannelFilesState(open: true, server: "fixture", channel: 20, channelName: "Scroll channel 20", path: "/", status: "ready",
            entries: (0..<40).map { ChannelFileEntry(name: "频道文件 \($0).pdf", size: 0, timestamp: 1700000000, directory: false, icon: "file-text") })
        try await Task.sleep(nanoseconds: 150_000_000)
        let fileScroll = try XCTUnwrap(scrolls(host.view).first { scroll in !homeScrolls.contains(where: { $0 === scroll }) })
        let filesPane = try XCTUnwrap(drawer(host.view))
        XCTAssertGreaterThanOrEqual(fileScroll.convert(fileScroll.bounds, to: filesPane).minY, 64)
        XCTAssertLessThanOrEqual(fileScroll.convert(fileScroll.bounds, to: filesPane).maxY, filesPane.bounds.maxY + 1)
        fileScroll.setContentOffset(CGPoint(x: 0, y: 100), animated: false)
        XCTAssertEqual(fileScroll.contentOffset.y, 100, accuracy: 0.5)
        let filesImage = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
        let filesAttachment = XCTAttachment(image: filesImage); filesAttachment.name = "channel-files-in-existing-drawer"; filesAttachment.lifetime = .keepAlways; add(filesAttachment)
        let local = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MobileSpeak/channel-files/ui-test/20/file/native-share.txt")
        try FileManager.default.createDirectory(at: local.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("Shared file".utf8).write(to: local)
        defer { try? FileManager.default.removeItem(at: local) }
        client.channelFiles.entries[0].localPath = local.path
        fileScroll.setContentOffset(.zero, animated: false)
        try await Task.sleep(nanoseconds: 150_000_000)
        func presented(_ controller: UIViewController, name: String) -> UIViewController? {
            if String(describing: type(of: controller)).contains(name) { return controller }
            if let child = controller.presentedViewController, let result = presented(child, name: name) { return result }
            return controller.children.lazy.compactMap { presented($0, name: name) }.first
        }
        try activate(L10n.string("files_share"))
        try await Task.sleep(nanoseconds: 400_000_000)
        let sharing = try XCTUnwrap(presented(container, name: "UIActivityViewController") as? UIActivityViewController)
        sharing.completionWithItemsHandler?(nil, false, nil, nil)
        sharing.dismiss(animated: false)
        try await Task.sleep(nanoseconds: 500_000_000)
        try activate(L10n.string("files_open"))
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertTrue(presented(container, name: "ChannelFileOpenController") != nil || client.channelFilesLocalError != nil,
            "Open must present the native document menu or report unavailable handlers in the drawer")
        container.presentedViewController?.dismiss(animated: false)
        host.presentedViewController?.dismiss(animated: false)
        client.channelFilesLocalError = nil
        client.channelFiles = originalFiles
        try await Task.sleep(nanoseconds: 150_000_000)

        for (deviation, grade, name) in [(70.0, NetworkGrade.fair, "mobile"), (120.0, NetworkGrade.poor, "poor")] {
            client.state.networkQuality = NetworkQuality(rttMs: 30, deviationMs: deviation, packetLossPercent: 0,
                rttGrade: .good, deviationGrade: grade, packetLossGrade: .good, iconGrade: grade,
                axisMaxMs: 50, nowSecond: 29, samples: (0..<30).map { NetworkSample(second: UInt64($0), rttMs: 30, grade: .good) })
            try await Task.sleep(nanoseconds: 100_000_000)
            try checkDrawerHeader(expanded: true, screenshot: "drawer-network-\(name)-variation")
        }
        client.state.networkQuality = NetworkQuality(rttMs: 26, deviationMs: 8.6, packetLossPercent: 0,
            rttGrade: .good, deviationGrade: .good, packetLossGrade: .good, iconGrade: .good,
            axisMaxMs: 50, nowSecond: 29, samples: (0..<30).map { NetworkSample(second: UInt64($0), rttMs: 26, grade: .good) })
        try await Task.sleep(nanoseconds: 100_000_000)
        try checkDrawerHeader(expanded: true, screenshot: "drawer-network-axis-50")
        client.state.networkQuality = NetworkQuality(rttMs: 1234, deviationMs: 1234.5, packetLossPercent: 4,
            rttGrade: .poor, deviationGrade: .poor, packetLossGrade: .poor, iconGrade: .poor,
            axisMaxMs: 2000, nowSecond: 29, samples: (0..<30).map { NetworkSample(second: UInt64($0), rttMs: 1234, grade: .poor) })
        try await Task.sleep(nanoseconds: 100_000_000)
        try checkDrawerHeader(expanded: true, screenshot: "drawer-network-capped-values")
        XCTAssertEqual(client.state.networkQuality?.rttMs, 1234)
        XCTAssertEqual(client.state.networkQuality?.deviationMs, 1234.5)
        checkOffsets()
        let started = Date()
        for _ in 0..<8 {
            for (tab, title) in ["tab_members", "tab_settings", "tab_channels"].enumerated() {
                try activate(L10n.string(title))
                try await Task.sleep(nanoseconds: 50_000_000)
                host.view.layoutIfNeeded()
                checkOffsets()
                let active = homeScrolls[[1, 2, 0][tab]]
                let point = active.convert(CGPoint(x: 8, y: active.bounds.minY + 20), to: host.view)
                let hit = try XCTUnwrap(host.view.hitTest(point, with: nil))
                XCTAssertTrue(hit === active || hit.isDescendant(of: active), "Only the visible tab receives touches")
                let covered = active.convert(CGPoint(x: 8, y: active.bounds.maxY - 8), to: host.view)
                let drawerHit = try XCTUnwrap(host.view.hitTest(covered, with: nil))
                XCTAssertFalse(drawerHit === active || drawerHit.isDescendant(of: active), "The drawer blocks touches to the covered list")
                let labels = accessibleElements().compactMap(\.accessibilityLabel).joined(separator: " ")
                if title == "tab_settings" { XCTAssertTrue(labels.contains(L10n.string("settings_noise_suppression"))); XCTAssertFalse(labels.contains("Scroll channel 21")); XCTAssertFalse(labels.contains("Scroll member 12")) }
                // Direct UIKit enumeration exposes both retained lazy containers;
                // their visibility to VoiceOver needs a real assistive traversal.
                if title == "tab_channels" { XCTAssertTrue(labels.contains("Scroll channel 21")); XCTAssertFalse(labels.contains(L10n.string("settings_noise_suppression"))) }
                if title == "tab_members" { XCTAssertTrue(labels.contains("Scroll member 12")); XCTAssertFalse(labels.contains(L10n.string("settings_noise_suppression"))) }
            }
            // Hidden lists also receive a normal client update, retaining native offsets.
            client.objectWillChange.send()
            try await Task.sleep(nanoseconds: 20_000_000)
            checkOffsets()
        }
        let timingText = "160 channels + 160 members: 24 tab switches and 8 client updates in \(Date().timeIntervalSince(started)) seconds (includes 1360 ms waits)"
        print(timingText)
        let timing = XCTAttachment(string: timingText)
        timing.lifetime = .keepAlways; add(timing)
        try activate(L10n.string("voice_drawer_collapse"))
        try await Task.sleep(nanoseconds: 500_000_000)
        client.state.networkQuality?.rttMs = 1234
        client.state.networkQuality?.deviationMs = 123.4
        try await Task.sleep(nanoseconds: 100_000_000)
        try checkDrawerHeader(expanded: false, screenshot: "drawer-network-collapsed-refreshed")
        checkOffsets()

        for (tab, label) in [("tab_channels", L10n.format("channel_chat_open", "Scroll channel 20")), ("tab_members", "Scroll member 12"), ("tab_settings", L10n.string("settings_about"))] {
            try activate(L10n.string(tab))
            try await Task.sleep(nanoseconds: 30_000_000)
            try activate(label)
            try await Task.sleep(nanoseconds: 350_000_000)
            XCTAssertEqual(stack.viewControllers.count, 2, label)
            stack.popViewController(animated: false)
            try await Task.sleep(nanoseconds: 100_000_000)
            XCTAssertTrue(stack.topViewController === home)
            checkOffsets()
        }
        try activate(L10n.string("tab_settings"))
        try await Task.sleep(nanoseconds: 30_000_000)
        let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
        let attachment = XCTAttachment(image: image); attachment.name = "Settings hides both retained lists"; attachment.lifetime = .keepAlways; add(attachment)
        try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("home-scroll-settings.png"))
        // Shorten the original lists while their retained offsets are still nonzero.
        checkOffsets()
        client.state.channels = Array(channels.prefix(3))
        client.state.clients = Array(members.prefix(3))
        for title in ["tab_channels", "tab_members"] {
            try activate(L10n.string(title))
            try await Task.sleep(nanoseconds: 100_000_000)
            for scroll in homeScrolls.prefix(2) {
                XCTAssertTrue(scrolls(host.view).contains { $0 === scroll })
                XCTAssertGreaterThanOrEqual(scroll.contentOffset.y, -scroll.adjustedContentInset.top - 0.5)
                XCTAssertLessThanOrEqual(scroll.contentOffset.y, max(-scroll.adjustedContentInset.top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom) + 0.5)
            }
        }
        try activate(L10n.string("tab_settings"))
        try await Task.sleep(nanoseconds: 30_000_000)
        func drawerPane(_ view: UIView) -> UIView? {
            if view.accessibilityIdentifier == "voice-drawer" { return view }
            return view.subviews.lazy.compactMap(drawerPane).first
        }
        let pane = try XCTUnwrap(drawerPane(host.view))
        let collapsedTop = pane.frame.minY
        func drawerTop() -> CGFloat { pane.layer.presentation()?.frame.minY ?? pane.frame.minY }
        let animated = !UIAccessibility.isReduceMotionEnabled && UIView.areAnimationsEnabled
        try activate(L10n.string("voice_drawer_expand"))
        try await Task.sleep(nanoseconds: 100_000_000) // Disconnect while the spring is still running.
        let openingTop = drawerTop()
        client.state = Snapshot(status: "disconnected")
        try await Task.sleep(nanoseconds: 40_000_000)
        let closingTop = drawerTop()
        if animated {
            // Allow one display frame of opening before SwiftUI observes the disconnect.
            XCTAssertLessThan(abs(closingTop - openingTop), abs(collapsedTop - openingTop) * 0.75)
            XCTAssertLessThan(closingTop, collapsedTop - 0.5, "Disconnect must animate from the visible position")
        }
        let disabledHandle = try XCTUnwrap(accessibleElements().first { $0.accessibilityLabel == L10n.string("voice_drawer_expand") })
        XCTAssertTrue(disabledHandle.accessibilityTraits.contains(.notEnabled))
        let parent = try XCTUnwrap(pane.superview)
        let audioPoint = parent.convert(CGPoint(x: pane.bounds.maxX - 22, y: drawerTop() + 40), to: host.view)
        let audioHit = try XCTUnwrap(host.view.hitTest(audioPoint, with: nil))
        XCTAssertTrue(audioHit === pane || audioHit.isDescendant(of: pane), "The visible audio header remains touchable during closing")
        client.state = snapshot // Reconnect before automatic closing completes.
        try await Task.sleep(nanoseconds: 40_000_000)
        XCTAssertGreaterThanOrEqual(drawerTop(), closingTop - 0.5)
        let reconnectedHandle = try XCTUnwrap(accessibleElements().first { $0.accessibilityLabel == L10n.string("voice_drawer_expand") })
        XCTAssertFalse(reconnectedHandle.accessibilityTraits.contains(.notEnabled))
        XCTAssertEqual(reconnectedHandle.accessibilityValue, L10n.string("voice_drawer_collapsed"))
        try activate(L10n.string("voice_drawer_expand"))
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertLessThan(drawerTop(), collapsedTop - 0.5, "A new user action supersedes the old close animation")
        for status in ["disconnected", "connecting", "reconnecting", "connected"] {
            client.state = status == "connected" ? snapshot : Snapshot(status: status)
            try await Task.sleep(nanoseconds: 80_000_000)
            XCTAssertTrue(scrolls(host.view).contains { $0 === homeScrolls[2] })
            XCTAssertEqual(homeScrolls[2].contentOffset.y, offsets[2], accuracy: 0.5)
            let labels = accessibleElements().compactMap(\.accessibilityLabel).joined(separator: " ")
            let handle = try XCTUnwrap(accessibleElements().first { $0.accessibilityLabel == L10n.string("voice_drawer_expand") })
            XCTAssertEqual(handle.accessibilityTraits.contains(.notEnabled), status != "connected")
            XCTAssertEqual(handle.accessibilityValue, L10n.string("voice_drawer_collapsed"))
            XCTAssertFalse(labels.contains("Scroll channel 21")); XCTAssertFalse(labels.contains("Scroll member 12"))
            XCTAssertFalse(labels.contains(L10n.string("status_connecting_server")))
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: image); attachment.name = "Retained settings while \(status)"; attachment.lifetime = .keepAlways; add(attachment)
            try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("home-scroll-settings-\(status).png"))
        }
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertEqual(drawerTop(), collapsedTop, accuracy: 0.5)
        XCTAssertEqual(homeScrolls[2].contentOffset.y, offsets[2], accuracy: 0.5)

        client.state = snapshot
        try await Task.sleep(nanoseconds: 150_000_000)
        try activate(L10n.string("voice_drawer_expand"))
        try await Task.sleep(nanoseconds: 500_000_000)
        for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
            container.setOverrideTraitCollection(UITraitCollection(preferredContentSizeCategory: category), forChild: host)
            client.channelFiles = ChannelFilesState(open: true, server: "fixture", channel: 20, channelName: "Scroll channel 20", path: "/", status: "ready")
            try await Task.sleep(nanoseconds: 200_000_000)
            let pane = try XCTUnwrap(drawer(host.view))
            let content = try XCTUnwrap(scrolls(pane).first)
            let contentTop = content.convert(content.bounds, to: window).minY
            let uploads = accessibleElements().filter { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string("files_upload") }
            XCTAssertEqual(uploads.count, 1, "Empty directories only offer the header upload")
            XCTAssertTrue(accessibleElements().contains { $0.accessibilityLabel == L10n.string("files_empty") })
            for key in ["action_back", "files_upload", "files_reload", "files_sort_name"] {
                let button = try XCTUnwrap(accessibleElements().first { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string(key) })
                XCTAssertGreaterThanOrEqual(button.accessibilityFrame.height, 44)
                XCTAssertLessThanOrEqual(button.accessibilityFrame.maxY, contentTop + 1, "File controls stay above the scroll region")
            }
            let image = UIGraphicsImageRenderer(size: pane.bounds.size).image { _ in pane.drawHierarchy(in: pane.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: image); attachment.name = "drawer-files-empty-\(category.rawValue)"; attachment.lifetime = .keepAlways; add(attachment)
            try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("drawer-files-empty-\(category.rawValue).png"))
        }
        container.setOverrideTraitCollection(nil, forChild: host)
    }

    @MainActor func testChannelFilesDecodeAndRenderScrollableRowsAtLargeTextSizes() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        let original = client.state
        let originalFiles = client.channelFiles
        let originalLanguage = UserDefaults.standard.string(forKey: AppLanguage.preferenceKey)
        UserDefaults.standard.set("zh-Hans", forKey: AppLanguage.preferenceKey)
        defer {
            client.state = original; client.channelFiles = originalFiles
            if let originalLanguage { UserDefaults.standard.set(originalLanguage, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        let data = Data(#"{"open":true,"server":"fixture","channel":7,"channelName":"当前实际加入的频道","path":"/文档","sort":"name","status":"ready","error":null,"entries":[{"name":"很长的中文文件名称用于确认扩展名仍然保留.pdf","size":0,"timestamp":1700000000,"directory":false,"icon":"file-text","localPath":null}],"transfers":[]}"#.utf8)
        let decoded = try JSONDecoder().decode(ChannelFilesState.self, from: data)
        XCTAssertEqual(decoded.entries.first?.size, 0)
        XCTAssertEqual(decoded.channel, 7)
        let view = ChannelFilesDrawer(client: client).frame(height: 340).foregroundStyle(Color(hex:0xF2F3F5))
            .background(Palette.bottom).tint(Palette.accent).environment(\.locale, Locale(identifier: "zh-Hans"))
        let host = UIHostingController(rootView: AnyView(view))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 200_000_000)
        client.state = Snapshot(status: "connected", server: "Fixture", ownClient: 1,
            channels: [Channel(id: 7, parent: 0, order: 0, name: "当前实际加入的频道", password: false, permanent: true, key: "7", icon: nil, iconPath: nil)],
            clients: [Member(id: 1, channel: 7, uid: "self", name: "Self", avatarHash: "", avatarPath: nil, badges: [], serverGroupIcons: [], channelGroupIcon: nil, muted: false, deafened: false, speaking: false)])
        for category in [ContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
            client.channelFiles = decoded
            client.channelFiles.entries += (0..<40).map { ChannelFileEntry(name: "文件 \($0).txt", size: 42, timestamp: 1700000000, directory: false, icon: "file-text") }
            host.rootView = AnyView(view.environment(\.sizeCategory, category))
            try await Task.sleep(nanoseconds: 200_000_000)
            host.view.layoutIfNeeded()
            func scrolls(_ view: UIView) -> [UIScrollView] { (view as? UIScrollView).map { [$0] } ?? view.subviews.flatMap(scrolls) }
            let scroll = try XCTUnwrap(scrolls(host.view).first)
            XCTAssertGreaterThan(scroll.contentSize.height, scroll.bounds.height)
            let old = scroll.contentOffset.y
            scroll.setContentOffset(CGPoint(x: 0, y: old + 100), animated: false)
            XCTAssertGreaterThan(scroll.contentOffset.y, old)
            scroll.setContentOffset(.zero, animated: false)
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let name = "channel-files-\(category)"
            try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent(name + ".png"))
            let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
        }
    }

    @MainActor func testChannelFilesRefreshAndSortControlsKeepLayoutAndRetryState() async throws {
        #if targetEnvironment(simulator)
        let library = try XCTUnwrap(dlopen("/usr/lib/libAccessibility.dylib", RTLD_NOW))
        let automation = unsafeBitCast(try XCTUnwrap(dlsym(library, "_AXSSetAutomationEnabled")), to: (@convention(c) (Bool) -> Void).self)
        let enabled = unsafeBitCast(try XCTUnwrap(dlsym(library, "_AXSAutomationEnabled")), to: (@convention(c) () -> Bool).self)()
        automation(true)
        defer { automation(enabled); dlclose(library) }
        #else
        throw XCTSkip("Native accessibility fixture requires the simulator")
        #endif
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test profile") }
        let savedState = client.state, savedFiles = client.channelFiles
        let savedLanguage = UserDefaults.standard.string(forKey: AppLanguage.preferenceKey)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 568))
        window.rootViewController = host; window.makeKeyAndVisible()
        defer {
            window.isHidden = true; client.state = savedState; client.channelFiles = savedFiles
            if let savedLanguage { UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        func elements(_ object: NSObject) -> [NSObject] {
            var visited = Set<ObjectIdentifier>()
            func collect(_ object: NSObject) -> [NSObject] {
                guard visited.insert(ObjectIdentifier(object)).inserted, !object.accessibilityElementsHidden else { return [] }
                if let view = object as? UIView, view.isHidden || view.alpha < 0.01 { return [] }
                let elements = object.accessibilityElements as? [NSObject]
                let count = object.accessibilityElementCount()
                let children = elements ?? (count > 0 && count < 1_000 ? (0..<count).compactMap { object.accessibilityElement(at: $0) as? NSObject } : [])
                if elements == nil, count == NSNotFound, !object.isAccessibilityElement { return ((object as? UIView)?.subviews ?? []).flatMap(collect) }
                return (object.isAccessibilityElement ? [object] : []) + children.flatMap(collect)
            }
            return collect(object)
        }
        for language in ["en", "zh-Hans"] {
            UserDefaults.standard.set(language, forKey: AppLanguage.preferenceKey)
            XCTAssertEqual(L10n.string("files_reload"), language == "en" ? "Refresh" : "刷新")
            XCTAssertEqual(L10n.string("files_sort_name"), language == "en" ? "Name" : "名称")
            XCTAssertEqual(L10n.string("files_sort_newest"), language == "en" ? "Time" : "时间")
            for category in [ContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                for (index, fixture) in [("/", "ready", "name", true), ("/文档/a very long folder path/子目录", "ready", "newest", true),
                    ("/empty", "ready", "name", true), ("/文档", "loading", "newest", true), ("/文档", "failed", "newest", true), ("/文档", "failed", "name", false)].enumerated() {
                    let (path, status, sort, connected) = fixture
                    client.state = Snapshot(status: connected ? "connected" : "disconnected")
                    client.channelFiles = ChannelFilesState(open: true, server: "fixture", channel: 7, channelName: "频道 Channel", path: path, sort: sort, status: status)
                    if index < 2 { client.channelFiles.entries = [ChannelFileEntry(name: "folder", size: 0, timestamp: 0, directory: true, icon: "folder")] }
                    host.rootView = AnyView(ChannelFilesDrawer(client: client).foregroundStyle(.white).background(Palette.bottom)
                        .environment(\.sizeCategory, category).environment(\.locale, Locale(identifier: language)))
                    try await Task.sleep(nanoseconds: 200_000_000)
                    host.view.layoutIfNeeded()
                    let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                    let attachment = XCTAttachment(image: image); attachment.name = "files-controls-\(language)-\(category)-\(index)"; attachment.lifetime = .keepAlways; add(attachment)
                    let all = elements(host.view)
                    let uploads = all.filter { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string("files_upload") }
                    XCTAssertEqual(uploads.count, 1, "Upload remains only in the header")
                    XCTAssertEqual(uploads.first?.accessibilityTraits.contains(.notEnabled), !connected || status != "ready")
                    if index == 2 { XCTAssertTrue(all.contains { $0.accessibilityLabel == L10n.string("files_empty") }) }
                    let refresh = try XCTUnwrap(all.first { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string("files_reload") })
                    let sorting = try XCTUnwrap(all.first { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string(sort == "newest" ? "files_sort_newest" : "files_sort_name") })
                    XCTAssertEqual(refresh.accessibilityLabel, L10n.string("files_reload"))
                    XCTAssertEqual(sorting.accessibilityLabel, L10n.string(sort == "newest" ? "files_sort_newest" : "files_sort_name"))
                    XCTAssertEqual(refresh.accessibilityTraits.contains(.notEnabled), !connected || status == "loading")
                    for button in [refresh, sorting] {
                        XCTAssertGreaterThanOrEqual(button.accessibilityFrame.height, 44)
                        XCTAssertGreaterThanOrEqual(button.accessibilityFrame.minX, window.frame.minX)
                        XCTAssertLessThanOrEqual(button.accessibilityFrame.maxX, window.frame.maxX)
                    }
                    XCTAssertLessThanOrEqual(refresh.accessibilityFrame.maxX, sorting.accessibilityFrame.minX)
                    let pathElement = try XCTUnwrap(all.first { $0.accessibilityLabel == path })
                    XCTAssertLessThanOrEqual(pathElement.accessibilityFrame.maxX, refresh.accessibilityFrame.minX)
                    if connected {
                        let command = try XCTUnwrap(client.channelFileTarget).command
                        XCTAssertEqual(command["server"] as? String, "fixture"); XCTAssertEqual(command["channel"] as? UInt64, 7)
                        XCTAssertEqual(command["path"] as? String, path); XCTAssertEqual(command["directory"] as? String, path)
                    }
                    if status == "failed" {
                        let retry = try XCTUnwrap(all.last { $0.accessibilityTraits.contains(.button) && $0.accessibilityLabel == L10n.string("files_reload") })
                        XCTAssertFalse(retry === refresh)
                        XCTAssertEqual(retry.accessibilityTraits.contains(.notEnabled), !connected)
                    }
                }
            }
        }
    }

    @MainActor func testIconAssetsKeepTemplateTintAndOffOriginalColors() throws {
        let names = "afk avatar-placeholder channel-chat channels checkmark chevron-right error headphones lock members mic-off mic-on more off pencil plus trash send settings speaker-off speaker-on waveform folder file file-text file-image file-audio file-archive upload download sort refresh info share".split(separator: " ")
        for name in names {
            let image = try XCTUnwrap(UIImage(named: "Icon-" + name))
            let off = name == "off"
            let side: CGFloat = off ? 34 : 24
            XCTAssertEqual(image.size, CGSize(width: side, height: side), String(name))
            XCTAssertEqual(image.renderingMode, off ? .alwaysOriginal : .alwaysTemplate, String(name))
            let format = UIGraphicsImageRendererFormat()
            format.scale = 4
            let rendered = UIGraphicsImageRenderer(size: image.size, format: format).image { _ in
                // Explicit original rendering exercises the PDF geometry and its source colors.
                image.withRenderingMode(.alwaysOriginal).draw(at: .zero)
            }
            let cgImage = try XCTUnwrap(rendered.cgImage)
            let width = cgImage.width, height = cgImage.height
            var bytes = [UInt8](repeating: 0, count: width * height * 4)
            let context = try XCTUnwrap(CGContext(data: &bytes, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            let solid = stride(from: 0, to: bytes.count, by: 4).filter { bytes[$0 + 3] == 255 }
            XCTAssertFalse(solid.isEmpty, String(name))
            XCTAssertEqual(bytes[3], 0, String(name))
            if off {
                XCTAssertTrue(solid.contains { bytes[$0] == 200 && bytes[$0 + 1] == 63 && bytes[$0 + 2] == 74 })
                XCTAssertTrue(solid.contains { bytes[$0] == 242 && bytes[$0 + 1] == 243 && bytes[$0 + 2] == 245 })
            } else {
                XCTAssertTrue(solid.allSatisfy { bytes[$0] == 0 && bytes[$0 + 1] == 0 && bytes[$0 + 2] == 0 }, String(name))
                for x in 0..<width {
                    XCTAssertEqual(bytes[x * 4 + 3], 0, String(name))
                    XCTAssertEqual(bytes[((height - 1) * width + x) * 4 + 3], 0, String(name))
                }
                for y in 0..<height {
                    XCTAssertEqual(bytes[y * width * 4 + 3], 0, String(name))
                    XCTAssertEqual(bytes[(y * width + width - 1) * 4 + 3], 0, String(name))
                }
            }
        }
    }

    @MainActor func testAuthorizedSettingsPagesKeepLiveConnectionForOneMinute() async throws {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MobileSpeak")
        let marker = root.appendingPathComponent("settings-live-acceptance-authorized")
        guard FileManager.default.fileExists(atPath: marker.path) else { throw XCTSkip("Explicit existing test server/identity required") }
        defer { try? FileManager.default.removeItem(at: marker) }
        let scope = try String(contentsOf: marker, encoding: .utf8).split(separator: "\n").map(String.init)
        XCTAssertEqual(scope.count, 2)
        let client = Client.shared
        guard !client.connected, scope.count == 2 else { throw XCTSkip("Do not replace an existing voice connection") }
        guard let bookmark = client.bookmarks.first(where: { $0.address == scope[0] && client.profile.nickname == scope[1] }) else { throw XCTSkip("The authorized existing bookmark is unavailable; never create another test identity") }
        let original = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        let language = LanguageSettings()
        defer {
            client.disconnect()
            if let original { UserDefaults.standard.set(original, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        client.connectBookmark(bookmark)
        try await waitForAvatar { client.connected }
        let handle = client.handle
        let audio = client.audio
        let muted = client.microphoneMuted
        let deafened = client.deafened
        let trigger = PassthroughSubject<SettingsPage?, Never>()
        let host = UIHostingController(rootView: SettingsNavigationFixture(trigger: trigger).environmentObject(language))
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        try await Task.sleep(nanoseconds: 100_000_000)
        func navigation(_ controller: UIViewController) -> UINavigationController? {
            if let navigation = controller as? UINavigationController { return navigation }
            return controller.children.lazy.compactMap { navigation($0) }.first
        }
        let stack = try XCTUnwrap(navigation(host))
        trigger.send(.language)
        try await Task.sleep(nanoseconds: 500_000_000)
        let page = stack.topViewController
        language.select(.english); language.select(.simplifiedChinese)
        XCTAssertTrue(stack.topViewController === page)
        for _ in 0..<6 {
            try await Task.sleep(nanoseconds: 10_000_000_000)
            XCTAssertTrue(client.connected)
            XCTAssertTrue(client.audio === audio)
            let running = await audio.isRunning
            XCTAssertTrue(running)
            XCTAssertEqual(client.handle, handle)
            XCTAssertEqual(client.microphoneMuted, muted)
            XCTAssertEqual(client.deafened, deafened)
        }
        trigger.send(nil)
        try await Task.sleep(nanoseconds: 500_000_000)
        trigger.send(.about)
        try await Task.sleep(nanoseconds: 500_000_000)
        let about = stack.topViewController
        client.disconnect()
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(stack.topViewController === about)
        XCTAssertEqual(stack.viewControllers.count, 2)
    }

    @MainActor func testNativeSettingsNavigationKeepsPageWhenLanguageChangesAndCanReturn() async throws {
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        let language = LanguageSettings()
        let trigger = PassthroughSubject<SettingsPage?, Never>()
        let host = UIHostingController(rootView: SettingsNavigationFixture(trigger: trigger).environmentObject(language))
        let window = UIWindow(windowScene: scene)
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        func navigation(_ controller: UIViewController) -> UINavigationController? {
            if let navigation = controller as? UINavigationController { return navigation }
            return controller.children.lazy.compactMap { navigation($0) }.first
        }
        try await Task.sleep(nanoseconds: 100_000_000)
        let stack = try XCTUnwrap(navigation(host))
        let home = try XCTUnwrap(stack.topViewController)
        trigger.send(.language)
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertEqual(stack.viewControllers.count, 2)
        let page = try XCTUnwrap(stack.topViewController)
        language.select(.english)
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(stack.topViewController === page)
        XCTAssertEqual(stack.viewControllers.count, 2)
        language.select(.simplifiedChinese)
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(stack.topViewController === page)
        stack.popViewController(animated: false) // The same native pop used by Back/swipe.
        try await Task.sleep(nanoseconds: 100_000_000)
        XCTAssertTrue(stack.topViewController === home)
        trigger.send(.about)
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertEqual(stack.viewControllers.count, 2)
        trigger.send(nil)
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertTrue(stack.topViewController === home)
    }

    @MainActor func testSettingsDetailPagesUseInstalledVersionAndKeepClientAcrossLanguageChanges() async throws {
        let installed = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String)
        XCTAssertEqual(SettingsDetailPage.installedVersion, installed)
        XCTAssertNotNil(installed.range(of: #"^\d+\.\d+\.\d+$"#, options: .regularExpression))
        let client = Client.shared
        let handle = client.handle
        let audio = client.audio
        let muted = client.microphoneMuted
        let deafened = client.deafened
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        defer {
            if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        for option in [AppLanguage.english, .simplifiedChinese] {
            let language = LanguageSettings()
            language.select(option)
            for page in [SettingsPage.language, .about] {
                for size in [CGSize(width: 393, height: 852), CGSize(width: 320, height: 568), CGSize(width: 844, height: 390)] {
                    let root = NavigationView { SettingsDetailPage(page: page) }.navigationViewStyle(.stack)
                        .environmentObject(language).environment(\.locale, language.locale)
                        .dynamicTypeSize(.accessibility3).preferredColorScheme(.dark).tint(Palette.accent)
                    let host = UIHostingController(rootView: root)
                    let window = UIWindow(windowScene: scene)
                    let container = UIViewController()
                    window.rootViewController = container
                    container.addChild(host); container.view.addSubview(host.view)
                    host.view.frame = CGRect(origin: .zero, size: size); host.didMove(toParent: container)
                    window.isHidden = false
                    defer { window.isHidden = true }
                    try await Task.sleep(nanoseconds: 100_000_000)
                    host.view.layoutIfNeeded()
                    let image = UIGraphicsImageRenderer(size: size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
                    let attachment = XCTAttachment(image: image)
                    attachment.name = "Settings \(page) \(option.rawValue) \(Int(size.width))x\(Int(size.height)) large text"
                    attachment.lifetime = .keepAlways; add(attachment)
                    try image.pngData()?.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("secondary-\(page)-\(option.rawValue)-\(Int(size.width)).png"))
                    XCTAssertEqual(host.view.bounds.size, size)
                    XCTAssertFalse(language.select(option))
                }
            }
        }
        XCTAssertEqual(client.handle, handle)
        XCTAssertTrue(client.audio === audio)
        XCTAssertEqual(client.microphoneMuted, muted)
        XCTAssertEqual(client.deafened, deafened)
    }

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
        let running = await client.audio.isRunning
        XCTAssertTrue(running)
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
        XCTAssertEqual(L10n.channelMembers(count: 1), "· 1 member online")
        XCTAssertEqual(L10n.channelMembers(count: 2), "· 2 members online")
        XCTAssertEqual(L10n.channelMembers(count: 100), "· 100 members online")
        XCTAssertEqual(L10n.coreError("join_channel_failed"), "Couldn’t join the channel.")
        XCTAssertEqual(L10n.coreError("future_code", detail: "safe detail"), "Something went wrong. safe detail")
        UserDefaults.standard.set("zh-Hans", forKey: AppLanguage.preferenceKey)
        XCTAssertEqual(L10n.channelMembers(count: 1), "· 1 人在线")
        XCTAssertEqual(L10n.channelMembers(count: 5), "· 5 人在线")
        XCTAssertEqual(L10n.channelMembers(count: 100), "· 100 人在线")
    }
    @MainActor func testNoiseSuppressionDefaultsPersistsAndSwitches() async throws {
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
        try await waitForAvatar { client.audioProcessingStatus == "ready" }
        XCTAssertEqual(client.noiseSuppression, .none)
        XCTAssertEqual(UserDefaults.standard.string(forKey: NoiseSuppressionMode.preferenceKey), "none")
        client.setNoiseSuppression(.rnnoise)
        try await waitForAvatar { client.audioProcessingStatus == "ready" }
        XCTAssertEqual(client.noiseSuppression, .rnnoise)
        XCTAssertEqual(UserDefaults.standard.string(forKey: NoiseSuppressionMode.preferenceKey), "rnnoise")
    }
    @MainActor func testBookmarkPersistenceDeduplicationAndDeletion() throws {
        let client = Client.shared
        let host = "test-" + UUID().uuidString.lowercased() + ".invalid"
        let first = try client.saveBookmark(id: nil, title: "Test", host: host, port: "9987", password: "secret")
        defer { if let existing = client.bookmarks.first(where: { $0.id == first.id }) { client.deleteBookmark(existing) } }
        XCTAssertEqual(try client.bookmarkPassword(first), "secret")
        let updated = try client.saveBookmark(id: nil, title: "Updated", host: host.uppercased(), port: "9987", password: "new")
        XCTAssertEqual(updated.id, first.id)
        XCTAssertEqual(try client.bookmarkPassword(updated), "new")
        let saved = try XCTUnwrap(UserDefaults.standard.data(forKey: "mobilespeak.server.bookmarks"))
        let restored = try JSONDecoder().decode([Bookmark].self, from: saved)
        XCTAssertEqual(restored.first { $0.id == first.id }?.title, "Updated")
        XCTAssertNil(restored.first { $0.id == first.id }?.password)
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("new"))
        XCTAssertFalse(String(decoding: saved, as: UTF8.self).contains("\"password\""))
        let automaticHost = "automatic-" + UUID().uuidString.lowercased() + ".invalid"
        let automatic = try client.saveBookmark(id: nil, title: " ", host: automaticHost, port: "9987", password: "")
        defer { if let existing = client.bookmarks.first(where: { $0.id == automatic.id }) { client.deleteBookmark(existing) } }
        XCTAssertEqual(automatic.title, automaticHost)
        XCTAssertEqual(automatic.automaticallyNamed, true)
        let originalAddress = client.address
        defer { client.address = originalAddress }
        client.address = automatic.address
        client.updateAutomaticBookmarkTitle("Stale server")
        XCTAssertEqual(client.bookmarks.first { $0.id == automatic.id }?.title, automaticHost)
        XCTAssertThrowsError(try client.saveBookmark(id: first.id, title: "Bad", host: "bad/path", port: "0", password: ""))
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
        try await audio.start()
        let voiceProcessing = await audio.isVoiceProcessingEnabled
        XCTAssertTrue(voiceProcessing)
        let inputFormat = await audio.inputFormat
        let format = try XCTUnwrap(inputFormat)
        XCTAssertGreaterThan(format.sampleRate, 0)
        XCTAssertGreaterThan(format.channelCount, 0)
        try await audio.ensureRunning()
        // No connection: PCM is never transmitted or saved by this hardware check.
        try await audio.startCapture()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertGreaterThan(audio.frameCounts.rendered, 0)
        XCTAssertGreaterThan(audio.frameCounts.captured, 0)
        let beforeResume = audio.frameCounts
        audio.pauseForInterruption()
        try await audio.ensureRunning()
        try await audio.startCapture()
        try await Task.sleep(nanoseconds: 500_000_000)
        XCTAssertGreaterThan(audio.frameCounts.rendered, beforeResume.rendered)
        XCTAssertGreaterThan(audio.frameCounts.captured, beforeResume.captured)
        audio.stopCapture()
        audio.stop()
        let running = await audio.isRunning
        XCTAssertFalse(running)
        #endif
    }
    @MainActor func testDisconnectRemainsResponsiveDuringAudioInitializationAndCancelsLateWork() async throws {
        let client = Client.shared
        guard !client.connected && !client.busy else { throw XCTSkip("Do not replace an existing connection") }
        let original = client.state
        defer { client.state = original }
        let entered = expectation(description: "Hardware work runs off the main thread")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal() }
        let work = Task {
            try await client.audio.perform {
                XCTAssertFalse(Thread.isMainThread)
                entered.fulfill()
                _ = release.wait(timeout: .now() + 5)
            }
        }
        await fulfillment(of: [entered], timeout: 2)
        client.state.status = "connecting"
        let started = ProcessInfo.processInfo.systemUptime
        client.disconnect()
        XCTAssertEqual(client.state.status, "disconnected")
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - started, 0.5)
        release.signal()
        do {
            try await work.value
            XCTFail("Stopped audio work must not complete as a successful startup")
        } catch { XCTAssertTrue(error is CancellationError) }
        // The same queue remains reusable after the canceled operation and stop.
        try await client.audio.perform { XCTAssertFalse(Thread.isMainThread) }
        let running = await client.audio.isRunning
        let capturing = await client.audio.isCapturing
        XCTAssertFalse(running); XCTAssertFalse(capturing)
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
        client.connect(host: "invalid/address", port: "9987", password: "")
        XCTAssertFalse(client.busy)
        XCTAssertNotNil(client.error)
        client.error = nil
    }
    func testSnapshotAndChatModelsDecodeSharedBridgePayload() throws {
        let snapshot = try JSONDecoder().decode(Snapshot.self, from: Data(#"{"status":"connected","server":"Test","serverId":"server-uid","ownClient":1,"canSend":true,"channels":[{"id":2,"parent":0,"order":0,"name":"Lobby","password":false,"permanent":true,"key":"id:2","icon":42,"iconPath":"/cache/icon.img"}],"clients":[{"id":1,"channel":2,"uid":"client-uid","name":"Me","avatarHash":"hash","avatarPath":"/cache/avatar.img","badges":[{"id":"badge-id","name":"Badge","description":"Known badge","filename":"badge","iconPath":"/cache/badge.png"}],"serverGroupIcons":[{"id":6,"name":"Server Admin","iconId":300,"iconPath":null},{"id":14,"name":"DJ","iconId":166401896,"iconPath":"/cache/dj.img"}],"channelGroupIcon":{"id":5,"name":"Channel Admin","iconId":100,"iconPath":null},"muted":true,"deafened":false,"away":true,"speaking":false}]}"#.utf8))
        XCTAssertEqual(snapshot.serverId, "server-uid")
        XCTAssertEqual(snapshot.clients.first?.away, true)
        XCTAssertEqual(snapshot.clients.first?.statusIcon, "afk")
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
    func testVoiceDrawerGeometryResistanceAndDistanceVelocitySettling() {
        let travel = HomeView.drawerTravel(availableHeight: 650)
        XCTAssertEqual(travel, 391, accuracy: 0.001)
        XCTAssertEqual((64 + travel) / (650 - 64 - travel), 7.0 / 3, accuracy: 0.001)
        XCTAssertEqual(HomeView.drawerTravel(availableHeight: 320), 320 * 7.0 / 10 - 64, accuracy: 0.001)
        for height in [CGFloat(0), 64, 90] { XCTAssertEqual(HomeView.drawerTravel(availableHeight: height), 0) }
        XCTAssertFalse(HomeView.drawerShouldExpand(height: 99, travel: 200, velocity: 0))
        XCTAssertFalse(HomeView.drawerShouldExpand(height: 100, travel: 200, velocity: 0))
        XCTAssertTrue(HomeView.drawerShouldExpand(height: 101, travel: 200, velocity: 0))
        XCTAssertTrue(HomeView.drawerShouldExpand(height: 20, travel: 200, velocity: -500))
        XCTAssertFalse(HomeView.drawerShouldExpand(height: 180, travel: 200, velocity: 500))
        XCTAssertFalse(HomeView.drawerShouldExpand(height: 600, travel: 200, velocity: 600))
        XCTAssertTrue(HomeView.drawerShouldExpand(height: -400, travel: 200, velocity: -600))
        XCTAssertFalse(HomeView.drawerShouldExpand(height: 0, travel: 0, velocity: -500))
        for raw in [CGFloat(-10_000), -40, 0, 100, 200, 240, 10_000] {
            let offset = HomeView.drawerOffset(raw, travel: 200, limit: 16)
            XCTAssertGreaterThan(offset, -16)
            XCTAssertLessThan(offset, 216)
            XCTAssertEqual(HomeView.drawerRawOffset(offset, travel: 200, limit: 16), raw, accuracy: 0.001)
        }
        XCTAssertEqual(HomeView.drawerOffset(-40, travel: 200, limit: 0), 0)
        XCTAssertEqual(HomeView.drawerOffset(240, travel: 200, limit: 0), 200)
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

private struct SettingsNavigationFixture: View {
    let trigger: PassthroughSubject<SettingsPage?, Never>
    @EnvironmentObject private var language: LanguageSettings
    @State private var page: SettingsPage?
    var body: some View {
        NavigationView {
            ScrollView {
                VStack {
                    NavigationLink(destination: SettingsDetailPage(page: .language), isActive: Binding(get: { page == .language }, set: { if !$0 { page = nil } })) {
                        SettingsEntry(title: L10n.string("settings_language"), value: L10n.string(language.selection.titleKey))
                    }
                    NavigationLink(destination: SettingsDetailPage(page: .about), isActive: Binding(get: { page == .about }, set: { if !$0 { page = nil } })) {
                        SettingsEntry(title: L10n.string("settings_about"))
                    }
                }.padding(20)
            }.navigationTitle(L10n.string("tab_settings")).navigationBarHidden(true)
                .onReceive(trigger) { page = $0 }
        }.navigationViewStyle(.stack)
    }
}

extension ClientTests {
    func testAudioPreferencesMigrateAndPersistWithoutTouchingOtherSettings() throws {
        let suite = "MobileSpeakAudioMigration.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        for (legacy, expected) in [("dpdfnet8", NoiseSuppressionMode.rnnoise), ("dpdfnet2", .dpdfnet2), ("rnnoise", .rnnoise), ("none", .none), ("unknown", .rnnoise)] {
            defaults.set(legacy, forKey: NoiseSuppressionMode.preferenceKey)
            defaults.set("silero", forKey: "mobilespeak.audio.vad")
            defaults.set("preserved", forKey: "mobilespeak.server.address")
            XCTAssertEqual(NoiseSuppressionMode.stored(in: defaults), expected)
            XCTAssertNil(defaults.object(forKey: "mobilespeak.audio.vad"))
            XCTAssertEqual(defaults.string(forKey: NoiseSuppressionMode.preferenceKey), expected.rawValue)
            XCTAssertEqual(NoiseSuppressionMode.stored(in: defaults), expected)
            XCTAssertEqual(defaults.string(forKey: "mobilespeak.server.address"), "preserved")
        }
        XCTAssertEqual(NoiseSuppressionMode.allCases, [.dpdfnet2, .rnnoise, .none])
    }
}
extension ClientTests {
    private func statusMember(_ id: UInt64 = 7, path: String? = nil, muted: Bool = false, deafened: Bool = false, away: Bool = false, speaking: Bool = false) -> Member {
        Member(id: id, channel: 9, uid: "uid-\(id)", name: "User \(id)", avatarHash: "", avatarPath: path,
               badges: [], serverGroupIcons: [], channelGroupIcon: nil, muted: muted, deafened: deafened,
               speaking: speaking, volumeToken: "token-\(id)", away: away)
    }
    func testAvatarStatusPriorityAndLiveIdentity() throws {
        for away in [false, true] { for deafened in [false, true] { for muted in [false, true] {
            let member = statusMember(muted: muted, deafened: deafened, away: away)
            XCTAssertEqual(member.statusIcon, away ? "afk" : deafened ? "speaker-off" : muted ? "mic-off" : nil)
        } } }
        let saved = statusMember(muted: true, deafened: true, away: true)
        var snapshot = Snapshot(status: "connected", clients: [saved])
        XCTAssertEqual(snapshot.onlineMember(uid: saved.uid), saved)
        snapshot.clients = [statusMember()]
        XCTAssertNil(snapshot.onlineMember(uid: saved.uid)?.statusIcon)
        for uid in [nil, "", "unknown"] { XCTAssertNil(snapshot.onlineMember(uid: uid)) }
        snapshot.clients = []; XCTAssertNil(snapshot.onlineMember(uid: saved.uid))
        snapshot.clients = [saved]; snapshot.status = "reconnecting"; XCTAssertNil(snapshot.onlineMember(uid: saved.uid))
        snapshot.status = "disconnected"; XCTAssertNil(snapshot.onlineMember(uid: saved.uid))
        let old = #"{"id":7,"channel":9,"uid":"uid-7","name":"User 7","avatarHash":"","avatarPath":null,"badges":[],"serverGroupIcons":[],"channelGroupIcon":null,"muted":false,"deafened":false,"speaking":false}"#
        XCTAssertNil(try JSONDecoder().decode(Member.self, from: Data(old.utf8)).away)
        let current = String(old.dropLast()) + #", "away":true}"#
        XCTAssertEqual(try JSONDecoder().decode(Member.self, from: Data(current.utf8)).statusIcon, "afk")
    }
    @MainActor func testStatusAvatarsRenderAllSizesAndRestoreBase() async throws {
        guard !Client.shared.connected, !Client.shared.busy else { throw XCTSkip("Requires an offline fixture profile") }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let container = UIViewController(); window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view); host.didMove(toParent: container)
        host.view.frame = CGRect(x: 0, y: 0, width: 390, height: 760); window.makeKeyAndVisible()
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        defer { window.isHidden = true; if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) } else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) } }
        let root = FileManager.default.temporaryDirectory
        var paths: [String?] = [nil]
        for (index, color) in [UIColor(red: 1, green: 0.82, blue: 0.35, alpha: 1), UIColor(red: 0.12, green: 0.17, blue: 0.25, alpha: 1), UIColor.white].enumerated() {
            let image = UIGraphicsImageRenderer(size: CGSize(width: 96, height: 96)).image { context in
                color.setFill(); context.fill(CGRect(x: 0, y: 0, width: 96, height: 96))
                if index == 2 { for y in 0..<8 { for x in 0..<8 {
                    [UIColor.systemBlue, .systemRed, .white, .systemGreen][(x + y) % 4].setFill()
                    context.fill(CGRect(x: x * 12, y: y * 12, width: 12, height: 12))
                } } }
            }
            let url = root.appendingPathComponent("status-source-\(index).png"); try image.pngData()?.write(to: url); paths.append(url.path)
        }
        let originals = try paths.compactMap { try $0.map { try Data(contentsOf: URL(fileURLWithPath: $0)) } }
        for language in ["en", "zh-Hans"] {
            UserDefaults.standard.set(language, forKey: AppLanguage.preferenceKey)
            let rows: [(CGFloat, String?)] = [CGFloat(24), 30, 34].flatMap { size in paths.map { (size, $0) } }
            host.rootView = AnyView(VStack(alignment: .leading, spacing: 12) {
                Text("Normal / Mic off / Listening off / AFK").font(.system(size: 14))
                ForEach(rows.indices, id: \.self) { index in
                    let (size, path) = rows[index]
                    HStack(spacing: 30) {
                        Text("\(Int(size))").font(.system(size: 12)).frame(width: 24)
                        ForEach(0..<4) { state in
                            let member = self.statusMember(path: path, muted: state == 1 || state == 3, deafened: state == 2 || state == 3, away: state == 3, speaking: state == 1)
                            Avatar(name: member.name, path: path, speaking: member.speaking, size: size, member: member)
                                .overlay(alignment: .topTrailing) { if state == 3 { UnreadBadge(count: 99).offset(x: 5, y: -5) } }
                        }
                    }
                }
            }.padding(20).frame(width: 390, height: 760, alignment: .topLeading).foregroundStyle(Color(hex: 0xF2F3F5)).background(Palette.background))
            try await Task.sleep(nanoseconds: 500_000_000)
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let name = "user-status-grid-\(language)"
            try image.pngData()?.write(to: root.appendingPathComponent(name + ".png"))
            let attachment = XCTAttachment(image: image); attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
            XCTAssertEqual(statusMember(muted: true, deafened: true, away: true, speaking: true).accessibilityLabel,
                           language == "en" ? "User 7, Away, Listening Off, Muted, Speaking" : "User 7, 离开, 关闭收听, 已静音, 正在说话")
        }
        // Render the same live view through all states, then compare its restored pixels.
        let live = Client.shared, previous = live.state
        defer { live.state = previous }
        let member = statusMember(path: paths[1])
        host.rootView = AnyView(StatusAvatarFixture(client: live, member: member))
        func shot() -> Data? { UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }.pngData() }
        live.state = Snapshot(status: "connected", clients: [member]); try await Task.sleep(nanoseconds: 200_000_000)
        let normal = shot()
        for state in [statusMember(path: paths[1], muted: true), statusMember(path: paths[1], deafened: true), statusMember(path: paths[1], away: true)] {
            live.state.clients = [state]; try await Task.sleep(nanoseconds: 100_000_000); XCTAssertNotEqual(shot(), normal)
        }
        live.state.clients = [member]; try await Task.sleep(nanoseconds: 100_000_000); XCTAssertEqual(shot(), normal)
        live.state.clients = []; try await Task.sleep(nanoseconds: 100_000_000); XCTAssertEqual(shot(), normal)
        for (index, path) in paths.compactMap({ $0 }).enumerated() { XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: path)), originals[index]) }
    }
}
private struct StatusAvatarFixture: View {
    @ObservedObject var client: Client
    let member: Member
    var body: some View {
        let current = client.state.onlineMember(uid: member.uid)
        return Avatar(name: member.name, path: current?.avatarPath ?? member.avatarPath, speaking: current?.speaking ?? false, member: current)
            .frame(width: 390, height: 760).foregroundStyle(Color(hex: 0xF2F3F5)).background(Palette.background)
    }
}
extension ClientTests {
    @MainActor func testUserStatusPagesBadgesUnreadAndChatLiveUpdates() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline fixture profile") }
        let saved = client.state, messages = client.messages, importing = client.fileImporting
        let stored = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        client.fileImporting = true
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let container = UIViewController(); window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view); host.didMove(toParent: container)
        host.view.frame = CGRect(x: 0, y: 0, width: 390, height: 844); window.makeKeyAndVisible()
        defer {
            window.isHidden = true; client.state = saved; client.messages = messages; client.fileImporting = importing
            if let stored { UserDefaults.standard.set(stored, forKey: AppLanguage.preferenceKey) } else { UserDefaults.standard.removeObject(forKey: AppLanguage.preferenceKey) }
        }
        let root = FileManager.default.temporaryDirectory
        let source = root.appendingPathComponent("status-logo.png")
        try XCTUnwrap(UIImage(named: "AppLogo")?.pngData()).write(to: source)
        let channel = Channel(id: 9, parent: 0, order: 0, name: "Lobby", password: false, permanent: true, key: "lobby", icon: nil, iconPath: nil)
        var peer = statusMember(path: source.path, muted: true, deafened: true, away: true)
        peer = Member(id: peer.id, channel: peer.channel, uid: peer.uid, name: "Long username 完整用户名不能因离开而变暗", avatarHash: "", avatarPath: source.path,
                      badges: [UserBadge(id: "badge", name: "Badge", description: "Badge", filename: "badge", iconPath: source.path)],
                      serverGroupIcons: [GroupIcon(id: 1, name: "Server Admin", iconId: 300, iconPath: nil)],
                      channelGroupIcon: GroupIcon(id: 2, name: "Channel Admin", iconId: 100, iconPath: nil), muted: true, deafened: true, speaking: false, volumeToken: "token-7", away: true)
        let members = [statusMember(1, away: true), statusMember(2), statusMember(3, muted: true), statusMember(4, deafened: true), peer]
        let base = Snapshot(status: "connected", server: "Status fixture", serverId: "fixture", ownClient: 1, channels: [channel], clients: members)
        client.state = base
        func message(_ own: Bool, conversation: String) -> ChatMessage {
            ChatMessage(id: own ? "own" : "peer", conversation: conversation, targetId: "uid-7", targetName: peer.name,
                        senderUid: own ? "uid-1" : "uid-7", senderName: own ? "Me" : peer.name, senderAvatarHash: "", avatarPath: source.path,
                        own: own, text: "Hello", timestamp: 1, status: own ? .sent : .received, error: nil)
        }
        func capture(_ name: String) throws {
            host.view.layoutIfNeeded()
            let image = UIGraphicsImageRenderer(size: host.view.bounds.size).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            try image.pngData()?.write(to: root.appendingPathComponent("user-status-" + name + ".png"))
            let attachment = XCTAttachment(image: image); attachment.name = "user-status-" + name; attachment.lifetime = .keepAlways; add(attachment)
        }
        for language in ["en", "zh-Hans"] {
            UserDefaults.standard.set(language, forKey: AppLanguage.preferenceKey)
            for category in [ContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                for page in 0..<5 {
                    client.state = base
                    let view: AnyView
                    switch page {
                    case 0: view = AnyView(NavigationView { HomeView(client: client, tab: 1) }.navigationViewStyle(.stack))
                    case 1: view = AnyView(NavigationView { HomeView(client: client) }.navigationViewStyle(.stack))
                    case 2: view = AnyView(UserVolumesDrawer(client: client, onBack: {}))
                    case 3:
                        client.messages = [message(false, conversation: "client:uid-7"), message(true, conversation: "client:uid-7")]
                        view = AnyView(NavigationView { PrivateChatView(client: client, member: peer) }.navigationViewStyle(.stack))
                    default:
                        client.messages = [message(false, conversation: "channel:lobby"), message(true, conversation: "channel:lobby")]
                        view = AnyView(NavigationView { ChannelChatView(client: client, channel: channel) }.navigationViewStyle(.stack))
                    }
                    host.rootView = AnyView(view.id("\(language)-\(category)-\(page)").environmentObject(LanguageSettings()).environment(\.sizeCategory, category).preferredColorScheme(.dark).tint(Palette.accent))
                    try await Task.sleep(nanoseconds: 250_000_000)
                    try capture("page-\(page)-\(language)-\(category)")
                    XCTAssertEqual(client.state.onlineMember(uid: peer.uid)?.statusIcon, "afk")
                    if page == 3 {
                        for state in [statusMember(muted: true), statusMember(deafened: true), statusMember()] {
                            client.state.clients = [statusMember(1), state]
                            try await Task.sleep(nanoseconds: 100_000_000)
                            XCTAssertEqual(client.state.onlineMember(uid: peer.uid)?.statusIcon, state.statusIcon)
                            try capture("chat-\(state.statusIcon ?? "normal")-\(language)-\(category)")
                        }
                        client.state.clients = []
                        try await Task.sleep(nanoseconds: 100_000_000)
                        XCTAssertNil(client.state.onlineMember(uid: peer.uid))
                        try capture("chat-offline-\(language)-\(category)")
                    }
                }
            }
        }
    }
}

final class BridgeContractTests: XCTestCase {
    func testSharedRustOutputAndOptionalConnectionFields() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "bridge", withExtension: "json"))
        let samples = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        XCTAssertEqual(samples.count, 5)
        for (index, envelope) in samples.enumerated() {
            let snapshot = try XCTUnwrap(envelope["snapshot"] as? [String: Any])
            let decoded = try JSONDecoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: snapshot))
            XCTAssertEqual(decoded.status, ["disconnected", "connecting", "connected", "reconnecting", "disconnected"][index])
            XCTAssertEqual(snapshot["connectionId"] as? String, index == 0 ? nil : "fixture-connection")
            if index > 0 {
                let profile = try XCTUnwrap(envelope["profile"])
                let decoded = try JSONDecoder().decode(ProfileState.self, from: JSONSerialization.data(withJSONObject: profile))
                XCTAssertEqual(decoded.nickname, "MobileSpeakUser")
                XCTAssertEqual(decoded.requestId, 0)
                XCTAssertNil(decoded.error)
            }
            if let chats = envelope["chats"], !(chats is NSNull) {
                let messages = try JSONDecoder().decode([ChatMessage].self, from: JSONSerialization.data(withJSONObject: chats))
                XCTAssertEqual(messages.count, index == 2 ? 1 : 0)
                if index == 2 { XCTAssertEqual(messages.first?.status, .sent) }
            }
            for event in envelope["events"] as? [[String: Any]] ?? [] {
                if event["type"] as? String == "error" {
                    XCTAssertEqual(event["connectionId"] as? String, "fixture-connection")
                    XCTAssertEqual(event["detail"] as? String, "fixture failure")
                } else if event["type"] as? String == "audio_processing" {
                    XCTAssertNil(event["connectionId"])
                    XCTAssertEqual((event["requested"] as? [String: String])?["noise"], "dpdfnet2")
                }
            }
        }
    }
}

final class ProfileTests: XCTestCase {
    private func poll(_ handle: UnsafeMutableRawPointer) throws -> [String: Any] {
        let pointer = try XCTUnwrap(ts_poll(handle)); defer { ts_free(pointer) }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(String(cString: pointer).utf8)) as? [String: Any])
    }
    private func send(_ handle: UnsafeMutableRawPointer, _ command: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: command)
        XCTAssertEqual(String(decoding: data, as: UTF8.self).withCString { ts_command(handle, $0) }, 0)
    }
    private func wait(_ handle: UnsafeMutableRawPointer, request: UInt64) throws -> ProfileState {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline {
            let envelope = try poll(handle)
            if let value = envelope["profile"], !(value is NSNull) {
                let profile = try JSONDecoder().decode(ProfileState.self, from: JSONSerialization.data(withJSONObject: value))
                if profile.requestId == request { return profile }
            }
            Thread.sleep(forTimeInterval: 0.02)
        }
        throw NSError(domain: "ProfileTests", code: 1)
    }
    func testCAbiProfileDefaultsSaveRestartAndWriteFailure() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("profile-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let first = try XCTUnwrap(ts_create()); defer { ts_destroy(first) }
        try send(first, ["type":"configure", "storage":root.path])
        XCTAssertEqual(try wait(first, request: 0).nickname, "MobileSpeakUser")
        try send(first, ["type":"set_nickname", "nickname":" 用户😀 ", "request_id":1])
        XCTAssertEqual(try wait(first, request: 1).nickname, "用户😀")
        let second = try XCTUnwrap(ts_create()); defer { ts_destroy(second) }
        try send(second, ["type":"configure", "storage":root.path])
        let deadline = Date().addingTimeInterval(5)
        var restored = try wait(second, request: 0)
        while restored.nickname != "用户😀" && Date() < deadline { Thread.sleep(forTimeInterval: 0.02); restored = try wait(second, request: 0) }
        XCTAssertEqual(restored.nickname, "用户😀")
        try FileManager.default.createDirectory(at: root.appendingPathComponent("profile.tmp"), withIntermediateDirectories: true)
        try send(second, ["type":"set_nickname", "nickname":"Failed", "request_id":1])
        let failed = try wait(second, request: 1)
        XCTAssertEqual(failed.nickname, "用户😀"); XCTAssertEqual(failed.error, "profile_save_failed")
        try send(second, ["type":"set_nickname", "nickname":String(repeating:"😀", count:33), "request_id":2])
        XCTAssertEqual(try wait(second, request: 2).error, "nickname_invalid")
    }
    func testOldBookmarkNicknameIsIgnoredAndDuplicateIdsSurvive() throws {
        let id1 = UUID(), id2 = UUID()
        let data = Data("""
        [{"id":"\(id1)","title":"One","host":"EXAMPLE.com","port":9987,"nickname":"Old one","password":"secret"},
         {"id":"\(id2)","title":"Two","host":"example.com","port":9987,"nickname":{"ignored":true}}]
        """.utf8)
        let bookmarks = try JSONDecoder().decode([Bookmark].self, from:data)
        XCTAssertEqual(bookmarks.map(\.id), [id1,id2]); XCTAssertEqual(bookmarks[0].password,"secret")
        let encoded = try JSONEncoder().encode(bookmarks)
        XCTAssertFalse(String(decoding:encoded,as:UTF8.self).contains("nickname"))
        XCTAssertEqual(Bookmark.normalizedHost(bookmarks[0].host), Bookmark.normalizedHost(bookmarks[1].host))
    }
    @MainActor func testDuplicateEditsAndAutomaticTitleUseConnectionIdAfterProfileChange() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires offline test installation") }
        let original = client.bookmarks
        let disk = UserDefaults.standard.object(forKey:"mobilespeak.server.bookmarks")
        let nickname = client.profile.nickname
        let first = Bookmark(id:UUID(), title:"First", host:"127.0.0.1", port:1, automaticallyNamed:true)
        let second = Bookmark(id:UUID(), title:"Second", host:"127.0.0.1", port:1, automaticallyNamed:true)
        client.bookmarks = [first,second]
        defer {
            client.disconnect()
            client.deleteBookmark(first); client.deleteBookmark(second)
            client.bookmarks=original
            if let disk { UserDefaults.standard.set(disk,forKey:"mobilespeak.server.bookmarks") }
            else { UserDefaults.standard.removeObject(forKey:"mobilespeak.server.bookmarks") }
            client.saveNickname(nickname)
        }
        let edited = try client.saveBookmark(id:second.id,title:"Updated",host:"127.0.0.1",port:"1",password:"test password")
        XCTAssertEqual(client.bookmarks.count,2); XCTAssertEqual(edited.id,second.id)
        XCTAssertEqual(try client.bookmarkPassword(edited),"test password")
        client.connectBookmark(first)
        let id = try XCTUnwrap(client.state.connectionId)
        client.state=Snapshot(connectionId:id,status:"connected")
        client.saveNickname("Changed global profile")
        client.updateAutomaticBookmarkTitle("Confirmed server")
        XCTAssertEqual(client.bookmarks.first { $0.id==first.id }?.title,"Confirmed server")
        XCTAssertEqual(client.bookmarks.first { $0.id==second.id }?.title,"Updated")
        client.state=Snapshot(connectionId:"late old connection",status:"connected")
        client.updateAutomaticBookmarkTitle("Stale server")
        XCTAssertEqual(client.bookmarks.first { $0.id==first.id }?.title,"Confirmed server")
    }
    @MainActor func testFullSettingsReflectsSavedProfileAndPreservesAvatar() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires offline test installation") }
        let original = client.profile.nickname
        let avatar = client.avatarPreview
        let language = UserDefaults.standard.object(forKey: AppLanguage.preferenceKey)
        UserDefaults.standard.set("en", forKey: AppLanguage.preferenceKey)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: NavigationView { HomeView(client:client, tab:2) }.navigationViewStyle(.stack)
            .environmentObject(LanguageSettings()).preferredColorScheme(.dark).tint(Palette.accent))
        window.rootViewController = host; window.makeKeyAndVisible()
        defer {
            window.isHidden = true; client.saveNickname(original)
            if let language { UserDefaults.standard.set(language,forKey:AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey:AppLanguage.preferenceKey) }
        }
        func capture(_ name:String) throws {
            let image=UIGraphicsImageRenderer(size:window.bounds.size).image { _ in window.drawHierarchy(in:window.bounds,afterScreenUpdates:true) }
            try image.pngData()?.write(to:FileManager.default.temporaryDirectory.appendingPathComponent("profile-"+name+".png"))
            let attachment=XCTAttachment(image:image); attachment.name="profile-"+name; attachment.lifetime = .keepAlways; add(attachment)
        }
        func save(_ value:String) async throws {
            let request=client.saveNickname(value)
            let deadline=Date().addingTimeInterval(5)
            while client.profile.requestId != request && Date() < deadline { try await Task.sleep(nanoseconds:20_000_000) }
            XCTAssertEqual(client.profile.requestId,request); XCTAssertNil(client.nicknameSaveError)
            try await Task.sleep(nanoseconds:300_000_000)
        }
        try await save("MobileSpeakUser"); try capture("settings-full-default")
        try await save(String(repeating:"用户😀",count:12)); try capture("settings-full-long")
        XCTAssertTrue(client.avatarPreview === avatar)
        try await save(original)
    }
    @MainActor func testNativeProfileCardAndEditorLayouts() async throws {
        let client = Client.shared
        guard !client.connected, !client.busy else { throw XCTSkip("Requires an offline test installation") }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.first as? UIWindowScene)
        let window = UIWindow(windowScene: scene)
        let host = UIHostingController(rootView: AnyView(EmptyView()))
        let container = UIViewController(); window.rootViewController = container
        container.addChild(host); container.view.addSubview(host.view); host.didMove(toParent:container)
        host.view.frame = CGRect(x:0,y:0,width:390,height:844); window.makeKeyAndVisible()
        let language = UserDefaults.standard.object(forKey:AppLanguage.preferenceKey)
        defer {
            window.isHidden=true
            if let language { UserDefaults.standard.set(language,forKey:AppLanguage.preferenceKey) }
            else { UserDefaults.standard.removeObject(forKey:AppLanguage.preferenceKey) }
        }
        func capture(_ name:String) throws {
            host.view.layoutIfNeeded()
            let image=UIGraphicsImageRenderer(size:host.view.bounds.size).image { _ in host.view.drawHierarchy(in:host.view.bounds,afterScreenUpdates:true) }
            try image.pngData()?.write(to:FileManager.default.temporaryDirectory.appendingPathComponent("profile-"+name+".png"))
            let attachment=XCTAttachment(image:image); attachment.name="profile-"+name; attachment.lifetime = .keepAlways; add(attachment)
        }
        for language in ["en","zh-Hans"] {
            UserDefaults.standard.set(language,forKey:AppLanguage.preferenceKey)
            for category in [ContentSizeCategory.large,.accessibilityExtraExtraExtraLarge] {
                for state in 0..<4 {
                    let nickname = state==2 ? String(repeating:"用户😀",count:12) : "MobileSpeakUser"
                    let card = ProfileCard(nickname:nickname,preview:state==1 ? UIImage(named:"AppLogo") : nil,
                        subtitle:L10n.string(state==1 ? "avatar_local" : "avatar_none"),edit:{})
                    let profile = VStack(alignment: HorizontalAlignment.leading, spacing: 10) {
                        card
                        if state == 3 { Text(L10n.string("avatar_uploading")).font(.footnote).foregroundStyle(Color(hex: 0x949BA4)) }
                        Spacer()
                    }
                    let sized = profile.padding(20).frame(width: 390, height: 844, alignment: Alignment.topLeading)
                    let colored = sized.foregroundStyle(Color(hex: 0xF2F3F5)).background(Color(hex: 0x2B2D31))
                    host.rootView = AnyView(colored.environment(\.sizeCategory, category).preferredColorScheme(.dark))
                    try await Task.sleep(nanoseconds:150_000_000)
                    try capture("card-\(state)-\(language)-\(category)")
                }
                host.rootView=AnyView(NicknameEditor(client:client).environment(\.sizeCategory,category).preferredColorScheme(.dark).tint(Palette.accent))
                try await Task.sleep(nanoseconds:250_000_000)
                try capture("editor-\(language)-\(category)")
            }
        }
    }
}
