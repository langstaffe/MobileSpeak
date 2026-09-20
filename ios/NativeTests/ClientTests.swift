import XCTest
@testable import MobileSpeak

final class ClientTests: XCTestCase {
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
