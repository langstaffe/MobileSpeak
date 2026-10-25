import Foundation
import Combine
import Security
import UIKit
import AVFoundation
import OSLog
#if DEBUG
import Network
#endif

struct Channel: Decodable, Identifiable, Equatable {
    enum SpacerAlignment: Equatable { case left, center, right, repeatFill }
    struct Spacer { let text: String; let alignment: SpacerAlignment }
    let id: UInt64
    let parent: UInt64
    let order: UInt64
    let name: String
    let password: Bool
    let permanent: Bool
    let key: String
    let icon: UInt32?
    let iconPath: String?
    var conversation: String { "channel:\(key)" }
    var spacer: Spacer? {
        guard permanent, parent == 0, name.first == "[", let end = name.firstIndex(of: "]") else { return nil }
        let marker = name[name.index(after: name.startIndex)..<end].lowercased()
        let alignment: SpacerAlignment
        if marker.hasPrefix("*spacer") { alignment = .repeatFill }
        else if marker.hasPrefix("cspacer") { alignment = .center }
        else if marker.hasPrefix("rspacer") { alignment = .right }
        else if marker.hasPrefix("lspacer") || marker.hasPrefix("spacer") { alignment = .left }
        else { return nil }
        return Spacer(text: String(name[name.index(after: end)...]), alignment: alignment)
    }
}
struct UserBadge: Decodable, Identifiable, Equatable {
    let id: String
    let name: String
    let description: String
    let filename: String
    let iconPath: String?
}
struct GroupIcon: Decodable, Identifiable, Equatable {
    let id: UInt64
    let name: String
    let iconId: UInt32
    let iconPath: String?
    var builtinLabel: String? {
        switch iconId {
        case 100: "C"
        case 300: "S"
        default: nil
        }
    }
    var canDisplay: Bool { iconPath != nil || builtinLabel != nil }
}
struct Member: Decodable, Identifiable, Equatable {
    let id: UInt64
    let channel: UInt64
    let uid: String?
    let name: String
    let avatarHash: String
    let avatarPath: String?
    let badges: [UserBadge]
    let serverGroupIcons: [GroupIcon]
    let channelGroupIcon: GroupIcon?
    let muted: Bool
    let deafened: Bool
    let speaking: Bool
    var volumePercent: Int? = nil
    var volumeToken: String? = nil
    var away: Bool? = nil
    var statusIcon: String? { away == true ? "afk" : deafened ? "speaker-off" : muted ? "mic-off" : nil }
    var accessibilityLabel: String {
        ([name] + [(away == true, "member_away"), (deafened, "member_listening_off"),
                   (muted, "member_muted"), (speaking, "member_speaking")]
            .compactMap { $0.0 ? L10n.string($0.1) : nil }).joined(separator: ", ")
    }
    var volumeRowId: String { volumeToken ?? "client:\(id)" }
    var conversation: String? { uid.map { "client:\($0)" } }
}
struct Snapshot: Decodable, Equatable {
    var connectionId: String?
    var status = "disconnected"
    var server: String?
    var serverId: String?
    var ownClient: UInt64?
    var canSend: Bool?
    var channels: [Channel] = []
    var clients: [Member] = []
    var networkQuality: NetworkQuality?
    func onlineMember(uid: String?) -> Member? {
        guard status == "connected", let uid, !uid.isEmpty else { return nil }
        return clients.first { $0.uid == uid }
    }
}

enum NetworkGrade: String, Decodable { case good, fair, poor }
struct NetworkSample: Decodable, Equatable {
    let second: UInt64
    let rttMs: Double
    let grade: NetworkGrade
    var packetLossPercent: Double? = nil
    var packetLossGrade: NetworkGrade? = nil

    var packetLossFraction: Double? {
        guard let packetLossPercent, packetLossPercent.isFinite, packetLossPercent >= 0, packetLossGrade != nil else { return nil }
        return min(packetLossPercent, 100) / 100
    }
}
struct NetworkQuality: Decodable, Equatable {
    var rttMs: Double?
    var deviationMs: Double?
    var packetLossPercent: Double?
    var rttGrade: NetworkGrade?
    var deviationGrade: NetworkGrade?
    var packetLossGrade: NetworkGrade?
    var iconGrade: NetworkGrade?
    var axisMaxMs: Double = 10
    var nowSecond: UInt64 = 0
    var samples: [NetworkSample] = []

    static func number(_ value: Double?, decimals: Int, maximum: Double = .infinity, locale: Locale = L10n.locale) -> String {
        guard let value, value.isFinite else { return "—" }
        let scale = decimals == 0 ? 1.0 : 10.0
        let displayed = (min(value, maximum) * scale).rounded(.toNearestOrAwayFromZero) / scale
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = false
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals
        formatter.maximumIntegerDigits = 309
        return formatter.string(from: NSNumber(value: displayed)) ?? "—"
    }
    var latencyText: String { Self.number(rttMs, decimals: 0, maximum: 999) }
    var deviationText: String { Self.number(deviationMs, decimals: 1, maximum: 999.9) }
    var lossText: String { Self.number(packetLossPercent, decimals: 1) }
    static func axisNumber(_ value: Double) -> String {
        number(value, decimals: value.rounded(.towardZero) == value ? 0 : 1)
    }
    var axisText: String { Self.axisNumber(axisMaxMs) }
    var midAxisText: String { Self.axisNumber(axisMaxMs / 2) }
    var chartAccessibilitySummary: String {
        guard !samples.isEmpty else { return L10n.string("network_unavailable") }
        let history = samples.filter { $0.second <= nowSecond && nowSecond - $0.second < 30 && $0.packetLossFraction != nil }.map {
            L10n.format("network_chart_sample_loss", Int(nowSecond - $0.second), Self.number($0.packetLossPercent, decimals: 1))
        }
        return ([L10n.format("network_chart_summary", samples.count, axisText)] + history).joined(separator: "; ")
    }
    var accessibilitySummary: String {
        rttMs == nil ? L10n.string("network_unavailable") : L10n.format("network_summary", latencyText, deviationText, lossText)
    }
}

enum ChatStatus: String, Decodable, Equatable { case received, pending, sent, failed }
struct ChatMessage: Decodable, Identifiable, Equatable {
    let id: String
    let conversation: String
    let targetId: String
    let targetName: String
    let senderUid: String
    let senderName: String
    let senderAvatarHash: String
    let avatarPath: String?
    let own: Bool
    let text: String
    let timestamp: UInt64
    let status: ChatStatus
    let error: String?
}
struct UnreadSnapshot: Decodable, Equatable {
    var serverId: String?
    var channel: String?
    var channelCount = 0
    var privateCounts: [String: Int] = [:]
}

enum NoiseSuppressionMode: String, CaseIterable {
    case dpdfnet2, rnnoise, none
    static let preferenceKey = "mobilespeak.audio.noiseSuppression"
    static func stored(in defaults: UserDefaults) -> Self {
        let mode = Self(rawValue: defaults.string(forKey: preferenceKey) ?? "") ?? .rnnoise
        defaults.set(mode.rawValue, forKey: preferenceKey)
        defaults.removeObject(forKey: "mobilespeak.audio.vad")
        return mode
    }
    var title: String { switch self { case .dpdfnet2: return "DPDFNet2"; case .rnnoise: return "RNNoise"; case .none: return L10n.string("settings_noise_none") } }
}

enum SpeechMode: String, CaseIterable, Decodable {
    case automatic, pushToTalk = "push_to_talk"
    var title: String { L10n.string(self == .automatic ? "settings_speech_automatic" : "settings_speech_push_to_talk") }
}

struct SpeechState: Decodable, Equatable {
    var mode = SpeechMode.automatic
    var token = ""
    var available = false
    var transmitting = false
}

struct ProfileState: Decodable, Equatable {
    var nickname = "MobileSpeakUser"
    var requestId: UInt64 = 0
    var error: String?
    var detail: String?
}

struct Bookmark: Codable, Identifiable {
    var id: UUID
    var title: String
    var host: String
    var port: UInt16
    var password: String? = nil // Legacy plaintext only; new writes keep secrets in Keychain.
    var automaticallyNamed: Bool? = nil // Optional keeps existing bookmarks decodable.
    var address: String { "\(host.contains(":") ? "[\(host)]" : host):\(port)" }
    static func normalizedHost(_ host: String) -> String {
        host.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased()
    }

    private enum CodingKeys: String, CodingKey { case id, title, host, port, password, automaticallyNamed }
    init(id: UUID, title: String, host: String, port: UInt16, password: String? = nil, automaticallyNamed: Bool? = nil) {
        self.id = id; self.title = title; self.host = host; self.port = port
        self.password = password; self.automaticallyNamed = automaticallyNamed
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        title = try values.decode(String.self, forKey: .title)
        host = try values.decode(String.self, forKey: .host)
        port = try values.decode(UInt16.self, forKey: .port)
        password = try values.decodeIfPresent(String.self, forKey: .password)
        automaticallyNamed = try values.decodeIfPresent(Bool.self, forKey: .automaticallyNamed)
    }
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(title, forKey: .title)
        try values.encode(host, forKey: .host)
        try values.encode(port, forKey: .port)
        try values.encodeIfPresent(automaticallyNamed, forKey: .automaticallyNamed)
    }
}

// The Rust callback runs under its output lock; only enqueue work here.
private func coreChanged(_ context: Int) {
    DispatchQueue.main.async {
        Unmanaged<Client>.fromOpaque(UnsafeRawPointer(bitPattern: context)!).takeUnretainedValue().receive()
    }
}

@MainActor final class Client: ObservableObject {
    // One application-lifetime instance keeps FFI callback/audio ownership simple.
    static let shared = Client()
    private let connectionLogger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "MobileSpeak", category: "connection")
    #if DEBUG
    private let networkLogger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "MobileSpeak", category: "network")
    private let networkPathMonitor = NWPathMonitor()
    #endif
    private var connectionStarted: TimeInterval?
    let handle: UnsafeMutableRawPointer
    @Published var state = Snapshot()
    @Published var channelFiles = ChannelFilesState()
    @Published var channelFilesLocalError: String?
    @Published var fileCache = FileCacheState()
    @Published var fileImporting = false
    @Published private(set) var fileCacheClearing = false
    @Published private(set) var fileCachePending = false
    @Published private(set) var fileCacheMessage: String?
    private var fileCacheRequest: UInt64 = 0
    private var fileCacheClearRequest: UInt64?
    @Published var error: String?
    @Published private(set) var avatarPreview: UIImage?
    @Published private(set) var avatarStatus = "idle"
    @Published private(set) var avatarDetail: String?
    @Published private(set) var avatarIntent = "unset"
    @Published private(set) var avatarClearingLocally = false
    @Published private(set) var avatarCleanupDetail: String?
    private var avatarSelection = 0
    private var avatarRoot: URL?
    @Published private(set) var microphoneMuted = false
    @Published private(set) var deafened = false
    @Published private(set) var receiveAgc = true
    @Published private(set) var noiseSuppression = NoiseSuppressionMode.stored(in: .standard)
    @Published private(set) var appliedNoise = NoiseSuppressionMode.rnnoise
    @Published private(set) var audioProcessingStatus = "switching"
    @Published private(set) var audioProcessingError: String?
    @Published private(set) var speech = SpeechState()
    @Published private(set) var microphonePermission = AVAudioSession.sharedInstance().recordPermission
    private var pushToTalkToken: String?
    private var audioRequest = 1
    @Published var audioBusy = false
    @Published var messages: [ChatMessage] = []
    @Published private(set) var unread = UnreadSnapshot()
    @Published private(set) var profile = ProfileState()
    @Published private(set) var nicknameSaving = false
    @Published private(set) var nicknameSaveError: String?
    private enum NoticeSlot: Hashable { case error, fileError, fileCache, audio, nickname, avatar, avatarCleanup }
    private var notices: [NoticeSlot: (source: LocalizedNotice, text: String)] = [:]
    private func localized(_ source: LocalizedNotice, for slot: NoticeSlot) -> String {
        let text = source.text
        notices[slot] = (source, text)
        return text
    }
    private func localized(_ error: Error, for slot: NoticeSlot) -> String {
        guard let source = LocalizedNotice.source(of: error) else {
            notices.removeValue(forKey: slot)
            return error.localizedDescription
        }
        return localized(source, for: slot)
    }
    private func localizedDetail(_ key: String, error: Error, for slot: NoticeSlot) -> String {
        if let source = LocalizedNotice.source(of: error) {
            return localized(.nested(key, source), for: slot)
        }
        return localized(.detail(key, error.localizedDescription), for: slot)
    }
    private func refreshed(_ text: String?, for slot: NoticeSlot) -> String? {
        guard let text, let notice = notices[slot], notice.text == text else {
            notices.removeValue(forKey: slot)
            return text
        }
        return localized(notice.source, for: slot)
    }
    func refreshLocalizedNotices() {
        error = refreshed(error, for: .error)
        channelFilesLocalError = refreshed(channelFilesLocalError, for: .fileError)
        fileCacheMessage = refreshed(fileCacheMessage, for: .fileCache)
        audioProcessingError = refreshed(audioProcessingError, for: .audio)
        nicknameSaveError = refreshed(nicknameSaveError, for: .nickname)
        avatarDetail = refreshed(avatarDetail, for: .avatar)
        avatarCleanupDetail = refreshed(avatarCleanupDetail, for: .avatarCleanup)
    }
    func showFileError(_ source: LocalizedNotice) {
        channelFilesLocalError = localized(source, for: .fileError)
    }
    func showFileError(_ error: Error) {
        channelFilesLocalError = localized(error, for: .fileError)
    }
    func showFileError(_ key: String, error: Error) {
        channelFilesLocalError = localizedDetail(key, error: error, for: .fileError)
    }
    func showError(_ source: LocalizedNotice) {
        error = localized(source, for: .error)
    }
    func showError(_ failure: Error) {
        error = localized(failure, for: .error)
    }
    private var nicknameRequest: UInt64 = 0
    private var activeBookmark: (id: UUID, connectionId: String)?
    @Published var address = UserDefaults.standard.string(forKey: "mobilespeak.server.address") ?? ""
    lazy var audio = PhoneAudio(handle: handle)
    @Published var bookmarks: [Bookmark] = []
    private var bookmarkLoadFailed = false
    private func passwordQuery(_ id: UUID) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "MobileSpeak.Bookmarks", kSecAttrAccount as String: id.uuidString]
    }
    private func saveBookmarkPassword(_ password: String, id: UUID) throws {
        let data = Data(password.utf8)
        let status = SecItemUpdate(passwordQuery(id) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var query = passwordQuery(id)
            query[kSecValueData as String] = data
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(query as CFDictionary, nil)
            guard added == errSecSuccess else { throw bookmarkError(.detail("error_bookmark_password_save", String(added))) }
        } else if status != errSecSuccess { throw bookmarkError(.detail("error_bookmark_password_save", String(status))) }
    }
    func bookmarkPassword(_ bookmark: Bookmark) throws -> String {
        guard let current = bookmarks.first(where: { $0.id == bookmark.id }) else { return "" }
        if let password = current.password { return password }
        var query = passwordQuery(bookmark.id); query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else { throw bookmarkError(.detail("error_bookmark_password_read", String(status))) }
        return value
    }
    private func bookmarkError(_ source: LocalizedNotice) -> NSError {
        source.error(domain: "MobileSpeak.Bookmarks", code: 1)
    }
    @discardableResult func saveBookmark(id: UUID?, title: String, host: String, port: String, password: String) throws -> Bookmark {
        guard !bookmarkLoadFailed else { throw bookmarkError(.key("error_bookmark_protected")) }
        let host = Bookmark.normalizedHost(host)
        guard !host.isEmpty, host.utf8.count <= 1000, host.rangeOfCharacter(from: .whitespacesAndNewlines) == nil, !host.contains("/"), let port = UInt16(port), port > 0, password.utf8.count <= 1024 else { throw bookmarkError(.key("error_bookmark_invalid")) }
        let existing = id.flatMap { id in bookmarks.first { $0.id == id } }
        let duplicate = bookmarks.first { Bookmark.normalizedHost($0.host) == host && $0.port == port && $0.id != id }
        let endpointChanged = existing.map { Bookmark.normalizedHost($0.host) != host || $0.port != port } ?? true
        if id != nil && endpointChanged && duplicate != nil { throw bookmarkError(.key("error_bookmark_duplicate")) }
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let automaticallyNamed = title.isEmpty || (existing?.automaticallyNamed == true && title == existing?.title)
        let bookmark = Bookmark(id: id ?? duplicate?.id ?? UUID(), title: title.isEmpty ? host : title, host: host, port: port, password: nil, automaticallyNamed: automaticallyNamed)
        var next = bookmarks
        if let index = next.firstIndex(where: { $0.id == bookmark.id }) { next[index] = bookmark } else { next.append(bookmark) }
        try saveBookmarkPassword(password, id: bookmark.id)
        let encoded = try JSONEncoder().encode(next)
        UserDefaults.standard.set(encoded, forKey: "mobilespeak.server.bookmarks")
        bookmarks = next
        return bookmark
    }
    func updateAutomaticBookmarkTitle(_ serverName: String) {
        let serverName = serverName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard connected, acceptingConnection, !serverName.isEmpty,
              let activeBookmark, activeBookmark.connectionId == connectionId,
              state.connectionId == activeBookmark.connectionId,
              let index = bookmarks.firstIndex(where: { $0.automaticallyNamed == true && $0.id == activeBookmark.id }),
              bookmarks[index].title != serverName else { return }
        do {
            var next = bookmarks
            next[index].title = serverName
            UserDefaults.standard.set(try JSONEncoder().encode(next), forKey: "mobilespeak.server.bookmarks")
            bookmarks = next
        } catch { self.error = localized(error, for: .error) }
    }
    func deleteBookmark(_ bookmark: Bookmark) {
        do {
            guard !bookmarkLoadFailed else { throw bookmarkError(.key("error_bookmark_delete")) }
            let next = bookmarks.filter { $0.id != bookmark.id }
            let data = try JSONEncoder().encode(next)
            UserDefaults.standard.set(data, forKey: "mobilespeak.server.bookmarks"); bookmarks = next
            let status = SecItemDelete(passwordQuery(bookmark.id) as CFDictionary)
            if status != errSecSuccess && status != errSecItemNotFound { throw bookmarkError(.detail("error_bookmark_password_delete", String(status))) }
        } catch { self.error = localized(error, for: .error) }
    }
    func connectBookmark(_ bookmark: Bookmark) {
        let started = ProcessInfo.processInfo.systemUptime
        do {
            let password = try bookmarkPassword(bookmark)
            connectionLogger.info("Connection startup phase=bookmark_password elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
            if connect(host: bookmark.host, port: String(bookmark.port), password: password), let connectionId {
                activeBookmark = (bookmark.id, connectionId)
            }
        }
        catch { self.error = localized(error, for: .error) }
    }
    private var acceptingConnection = false
    private var connectionId: String?
    private var audioInterrupted = false
    private var intent = 0
    var connected: Bool { state.status == "connected" }
    var reconnecting: Bool { state.status == "reconnecting" }
    var busy: Bool { state.status == "connecting" || reconnecting }
    var muted: Bool { microphoneMuted || deafened }
    var currentChannel: UInt64? { state.clients.first { $0.id == state.ownClient }?.channel }
    var currentChannelUnread: Int {
        guard connected, unread.serverId == state.serverId,
              let channel = state.channels.first(where: { $0.id == currentChannel }),
              unread.channel == channel.conversation else { return 0 }
        return unread.channelCount
    }
    func privateUnread(_ member: Member) -> Int {
        guard connected, unread.serverId == state.serverId, let uid = member.uid else { return 0 }
        return unread.privateCounts[uid] ?? 0
    }
    var privateUnreadTotal: Int {
        guard connected, unread.serverId == state.serverId else { return 0 }
        return unread.privateCounts.values.reduce(0, +)
    }
    var orderedChannels: [(channel: Channel, depth: Int)] {
        var result: [(Channel, Int)] = []
        var visited = Set<UInt64>()
        func visit(_ parent: UInt64, _ depth: Int) {
            var remaining = state.channels.filter { $0.parent == parent }.sorted { $0.id < $1.id }
            var previous: UInt64 = 0
            while !remaining.isEmpty {
                let c = remaining.remove(at: remaining.firstIndex { $0.order == previous } ?? 0)
                previous = c.id
                if visited.insert(c.id).inserted { result.append((c, depth)); visit(c.id, depth + 1) }
            }
        }
        visit(0, 0)
        for c in state.channels where !visited.contains(c.id) {
            visited.insert(c.id); result.append((c, 0)); visit(c.id, 1)
        }
        return result
    }
    private init() {
        handle = ts_create()!
        if let data = UserDefaults.standard.data(forKey: "mobilespeak.server.bookmarks") {
            do {
                bookmarks = try JSONDecoder().decode([Bookmark].self, from: data)
                if bookmarks.contains(where: { $0.password != nil }) {
                    var migrated = bookmarks
                    for index in migrated.indices {
                        if let password = migrated[index].password {
                            try saveBookmarkPassword(password, id: migrated[index].id)
                            migrated[index].password = nil
                        }
                    }
                    UserDefaults.standard.set(try JSONEncoder().encode(migrated), forKey: "mobilespeak.server.bookmarks")
                    bookmarks = migrated
                }
            }
            catch { bookmarkLoadFailed = true; self.error = localizedDetail("error_bookmark_migrate", error: error, for: .error) }
        }
        ts_set_notifier(handle, coreChanged, Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
        do {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("MobileSpeak", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            avatarRoot = root
            let upload = root.appendingPathComponent("default-avatar-upload.jpg")
            let preview = root.appendingPathComponent("default-avatar-preview.jpg")
            if let directory = currentAvatarDirectory(in: root) {
                avatarPreview = UIImage(contentsOfFile: directory.appendingPathComponent("preview.jpg").path)
            } else if !FileManager.default.fileExists(atPath: root.appendingPathComponent("default-avatar-current").path), FileManager.default.fileExists(atPath: upload.path), FileManager.default.fileExists(atPath: preview.path) {
                let uploadDate = (try? upload.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                let previewDate = (try? preview.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
                avatarPreview = UIImage(contentsOfFile: (previewDate > uploadDate ? upload : preview).path)
            }
            try send(["type": "configure", "storage": root.path])
        } catch { self.error = localizedDetail("error_cache_prepare", error: error, for: .error) }
        do { try send(["type": "set_noise_suppression", "mode": noiseSuppression.rawValue, "request_id": audioRequest]) }
        catch { self.audioProcessingError = localizedDetail("error_noise_suppression", error: error, for: .audio) }
        #if DEBUG
        let logger = networkLogger
        networkPathMonitor.pathUpdateHandler = { path in
            let wallMs = Int64(Date().timeIntervalSince1970 * 1000)
            logger.info("NetworkDiag phase=path wall_ms=\(wallMs) status=\(String(describing: path.status), privacy: .public) wifi=\(path.usesInterfaceType(.wifi)) cellular=\(path.usesInterfaceType(.cellular)) expensive=\(path.isExpensive) constrained=\(path.isConstrained)")
        }
        networkPathMonitor.start(queue: DispatchQueue(label: "MobileSpeak.NetworkDiagnostics"))
        #endif
    }
    func send(_ command: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: command)
        let result = String(decoding: data, as: UTF8.self).withCString { ts_command(handle, $0) }
        if result != 0 { throw LocalizedNotice.key("error_operation_submit").error(domain: "MobileSpeak", code: Int(result)) }
    }
    @discardableResult func saveNickname(_ nickname: String) -> UInt64 {
        nicknameRequest += 1
        nicknameSaving = true; nicknameSaveError = nil
        do { try send(["type": "set_nickname", "nickname": nickname, "request_id": nicknameRequest]) }
        catch { nicknameSaving = false; nicknameSaveError = localized(error, for: .nickname) }
        return nicknameRequest
    }
    func beginAvatarSelection() -> Int {
        avatarSelection += 1
        try? send(["type": "avatar_select", "selection": avatarSelection])
        return avatarSelection
    }
    func isCurrentAvatarSelection(_ selection: Int) -> Bool { selection == avatarSelection }
    func cancelAvatarSelection(_ selection: Int) { if selection == avatarSelection { _ = beginAvatarSelection() } }
    private func currentAvatarDirectory(in root: URL) -> URL? {
        guard let id = try? String(contentsOf: root.appendingPathComponent("default-avatar-current"), encoding: .utf8),
              !id.isEmpty, id.count <= 64, id.allSatisfy({ $0.isHexDigit || $0 == "-" }) else { return nil }
        return root.appendingPathComponent("avatars/\(id)", isDirectory: true)
    }
    @discardableResult func saveAvatar(preview: Data, uploads: [Data], selection: Int) async -> Bool {
        guard selection == avatarSelection, let root = avatarRoot else { return false }
        guard uploads.count == 3, let image = UIImage(data: preview) else { avatarImageFailed(selection: selection); return false }
        let id = UUID().uuidString
        let directory = root.appendingPathComponent("avatars/\(id)", isDirectory: true)
        let prepared = await Task.detached(priority: .userInitiated) {
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                try preview.write(to: directory.appendingPathComponent("preview.jpg"), options: .atomic)
                for (index, bytes) in uploads.enumerated() {
                    try bytes.write(to: directory.appendingPathComponent("upload-\(index).jpg"), options: .atomic)
                }
                return true
            } catch { return false }
        }.value
        guard prepared, selection == avatarSelection else {
            Task.detached { try? FileManager.default.removeItem(at: directory) }
            if selection == avatarSelection { avatarImageFailed(selection: selection) }
            return false
        }
        let published = await publishAvatar(image: id, selection: selection)
        guard published else {
            Task.detached { try? FileManager.default.removeItem(at: directory) }
            avatarImageFailed(selection: selection)
            return false
        }
        // Core owns the durable reference and old-file cleanup. A stale callback
        // must not change UI, nor remove a version the core already committed.
        guard selection == avatarSelection else { return false }
        avatarPreview = image
        avatarIntent = "image"
        return true
    }
    private func publishAvatar(image: String?, selection: Int) async -> Bool {
        guard let data = try? JSONSerialization.data(withJSONObject: ["type": "avatar_set", "selection": selection, "image": image as Any? ?? NSNull()]) else { return false }
        let address = UInt(bitPattern: handle)
        return await Task.detached(priority: .userInitiated) {
            String(decoding: data, as: UTF8.self).withCString { ts_command(UnsafeMutableRawPointer(bitPattern: address), $0) == 0 }
        }.value
    }
    func clearAvatar() {
        guard !avatarClearingLocally else { return }
        let selection = beginAvatarSelection()
        avatarClearingLocally = true
        Task {
            let saved = await publishAvatar(image: nil, selection: selection)
            avatarClearingLocally = false
            guard selection == avatarSelection else { return }
            if saved { avatarPreview = nil; avatarIntent = "clear" }
            else { avatarStatus = "clear_save_failed"; avatarDetail = nil }
        }
    }
    func avatarImageFailed(selection: Int? = nil) {
        if let selection, selection != avatarSelection { return }
        avatarStatus = "failed"
        avatarDetail = localized(.key("avatar_image_error"), for: .avatar)
    }
    func receive() {
        guard let pointer = ts_poll(handle) else { return }
        defer { ts_free(pointer) }
        do {
            let data = Data(String(cString: pointer).utf8)
            let envelope = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            if let value = envelope["profile"], !(value is NSNull) {
                profile = try JSONDecoder().decode(ProfileState.self, from: JSONSerialization.data(withJSONObject: value))
                if profile.requestId == nicknameRequest {
                    nicknameSaving = false
                    nicknameSaveError = profile.error.map { localized(.core($0, profile.detail), for: .nickname) }
                }
            }
            for event in envelope["events"] as? [[String: Any]] ?? [] {
                if event.keys.contains("connectionId") &&
                    ((event["connectionId"] as? String) != connectionId || !acceptingConnection) { continue }
                if event["type"] as? String == "identity", let identity = event["value"] {
                    let started = ProcessInfo.processInfo.systemUptime
                    do { try saveIdentity(JSONSerialization.data(withJSONObject: identity)) }
                    catch { self.error = localized(error, for: .error) }
                    connectionLogger.info("Connection startup phase=identity_save elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
                } else if event["type"] as? String == "file_cache_result" {
                    let request = (event["requestId"] as? NSNumber)?.uint64Value
                    if request == fileCacheRequest {
                        fileCachePending = false
                        let clearing = request == fileCacheClearRequest
                        if clearing { fileCacheClearing = false; fileCacheClearRequest = nil }
                        if let error = event["error"] as? String {
                            fileCacheMessage = localized(.fileCacheError(clearing ? "files_cache_clear_failed" : "files_cache_read_failed", error), for: .fileCache)
                        } else if clearing {
                            let bytes = (event["removedBytes"] as? NSNumber)?.uint64Value ?? 0
                            fileCacheMessage = localized(.fileCacheCleared(bytes), for: .fileCache)
                        }
                    }
                } else if event["type"] as? String == "error" {
                    error = localized(.core(event["code"] as? String, event["detail"] as? String ?? event["message"] as? String), for: .error)
                } else if event["type"] as? String == "audio_processing",
                          let applied = event["applied"] as? [String: String],
                          let requested = event["requested"] as? [String: String] {
                    if (event["requestId"] as? Int ?? 0) < audioRequest { continue }
                    appliedNoise = NoiseSuppressionMode(rawValue: applied["noise"] ?? "") ?? .rnnoise
                    noiseSuppression = NoiseSuppressionMode(rawValue: requested["noise"] ?? "") ?? appliedNoise
                    audioProcessingStatus = event["status"] as? String ?? "failed"
                    if audioProcessingStatus != "switching" {
                        UserDefaults.standard.set(appliedNoise.rawValue, forKey: NoiseSuppressionMode.preferenceKey)
                    }
                    audioProcessingError = audioProcessingStatus == "failed" ? localized(.detail("error_noise_suppression", event["detail"] as? String), for: .audio) : nil
                } else if event["type"] as? String == "audio_muted" {
                    error = localized(.core(event["code"] as? String, event["detail"] as? String ?? event["message"] as? String), for: .error)
                    if !muted {
                        microphoneMuted = true
                        audio.stopCapture()
                        try? send(["type": "mute", "input": true, "output": deafened])
                    }
                } else if event["type"] as? String == "avatar_local" {
                    avatarIntent = event["intent"] as? String ?? "unset"
                    if avatarIntent == "clear" { avatarPreview = nil }
                    else if let path = event["preview"] as? String { avatarPreview = UIImage(contentsOfFile: path) }
                    avatarCleanupDetail = nil
                } else if event["type"] as? String == "avatar_cleanup_failed" {
                    avatarCleanupDetail = localized(.detail("avatar_cleanup_error", event["detail"] as? String), for: .avatarCleanup)
                } else if event["type"] as? String == "avatar_sync" {
                    avatarStatus = event["status"] as? String ?? "idle"
                    notices.removeValue(forKey: .avatar)
                    avatarDetail = event["detail"] as? String
                    if ["failed", "clear_failed", "clear_save_failed"].contains(avatarStatus), let phase = event["phase"] as? String,
                       ["local_read", "server_self", "upload_connection", "upload_write", "hash_command", "server_hash", "verify_connection", "verify_file", "synced", "delete_command", "clear_hash", "cleared", "previous_transfer_query", "previous_transfer_stop"].contains(phase) {
                        avatarDetail = localized(.detail("avatar_stage_\(phase)", avatarDetail), for: .avatar)
                    }
                }
            }
            if let cache = envelope["fileCache"], !(cache is NSNull) {
                fileCache = try JSONDecoder().decode(FileCacheState.self, from: JSONSerialization.data(withJSONObject: cache))
            }
            if let receive = envelope["receiveAudio"] as? [String: Any], let agc = receive["agc"] as? Bool { receiveAgc = agc }
            if let value = envelope["speech"], !(value is NSNull) {
                let updated = try JSONDecoder().decode(SpeechState.self, from: JSONSerialization.data(withJSONObject: value))
                if let pushToTalkToken, pushToTalkToken != updated.token { self.pushToTalkToken = nil; audio.resetCaptureBuffer() }
                if speech != updated { speech = updated }
            }
            let next = try JSONDecoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: envelope["snapshot"]!))
            guard next.connectionId == connectionId, acceptingConnection || next.status == "disconnected" else { return }
            if let chats = envelope["chats"], !(chats is NSNull) {
                messages = try JSONDecoder().decode([ChatMessage].self, from: JSONSerialization.data(withJSONObject: chats))
            }
            if let value = envelope["unread"], !(value is NSNull) {
                let updated = try JSONDecoder().decode(UnreadSnapshot.self, from: JSONSerialization.data(withJSONObject: value))
                if updated != unread { unread = updated }
            }
            if let files = envelope["channelFiles"], !(files is NSNull) {
                let updated = try JSONDecoder().decode(ChannelFilesState.self, from: JSONSerialization.data(withJSONObject: files))
                if updated != channelFiles { channelFiles = updated }
            }
            let wasConnected = connected
            let wasInSession = connected || reconnecting
            #if DEBUG
            if next.status != state.status || next.networkQuality != state.networkQuality {
                let wallMs = Int64(Date().timeIntervalSince1970 * 1000)
                let diagnosticLocale = Locale(identifier: "en_US_POSIX")
                let rtt = NetworkQuality.number(next.networkQuality?.rttMs, decimals: 0, maximum: 999, locale: diagnosticLocale)
                let deviation = NetworkQuality.number(next.networkQuality?.deviationMs, decimals: 1, maximum: 999.9, locale: diagnosticLocale)
                networkLogger.info("NetworkDiag phase=ui wall_ms=\(wallMs) status=\(next.status, privacy: .public) rtt_ms=\(rtt, privacy: .public) deviation_ms=\(deviation, privacy: .public)")
            }
            #endif
            if next.status != state.status || next.clients.first(where: { $0.id == next.ownClient })?.channel != currentChannel { endPushToTalk() }
            if next != state { state = next }
            if connected && !wasConnected, let started = connectionStarted {
                connectionLogger.info("Connection startup phase=connected_snapshot total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
            }
            if state.status == "disconnected", let started = connectionStarted {
                connectionLogger.info("Connection startup phase=disconnected total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
                connectionStarted = nil
            }
            if connected, let serverName = state.server { updateAutomaticBookmarkTitle(serverName) }
            if connected && !wasConnected {
                let currentIntent = intent
                let started = connectionStarted
                Task {
                    guard connected && currentIntent == intent && !audioInterrupted else { return }
                    do {
                        try await audio.start()
                        guard connected && currentIntent == intent && !audioInterrupted else { return }
                        if let started {
                            connectionLogger.info("Connection startup phase=audio_ready total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
                        }
                        audio.setListening(!deafened)
                        await setAudio()
                    } catch {
                        guard connected && currentIntent == intent && !audioInterrupted,
                              !(error is CancellationError) else { return }
                        self.error = localizedDetail("error_audio_start", error: error, for: .error)
                        try? send(["type": "mute", "input": true, "output": deafened])
                    }
                }
                connectionStarted = nil
            } else if !connected && !reconnecting && wasInSession {
                intent += 1; audioBusy = false
                audioInterrupted = false
                audio.stop()
            }
        } catch { self.error = localizedDetail("error_generic", error: error, for: .error) }
    }
    @discardableResult func connect(host: String, port: String, password: String) -> Bool {
        guard !connected && !busy else { return false }
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        guard !host.isEmpty, host.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              !host.contains("/"), let port = UInt16(port), port > 0 else {
            error = localized(.key("error_server_invalid"), for: .error); return false
        }
        let started = ProcessInfo.processInfo.systemUptime
        connectionLogger.info("Connection startup begin")
        do {
            let identity = try loadIdentity()
            connectionLogger.info("Connection startup phase=identity_load elapsed_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000)) stored=\(identity != nil)")
            address = "\(host.contains(":") ? "[\(host)]" : host):\(port)"
            error = nil
            audioInterrupted = false
            let requestId = UUID().uuidString
            try send(["type": "connect", "connectionId": requestId, "address": address, "password": password,
                      "identity": try identity.map { try JSONSerialization.jsonObject(with: $0) } ?? NSNull()])
            connectionId = requestId
            activeBookmark = nil
            connectionStarted = started
            connectionLogger.info("Connection startup phase=command_submitted total_ms=\(Int((ProcessInfo.processInfo.systemUptime - started) * 1000))")
            state.connectionId = requestId
            state.status = "connecting"
            acceptingConnection = true; intent += 1
            UserDefaults.standard.set(address, forKey: "mobilespeak.server.address")
            return true
        } catch { self.error = localized(error, for: .error); return false }
    }
    func disconnect() {
        endPushToTalk()
        do { try send(["type": "disconnect"]) } catch { self.error = localized(error, for: .error); return }
        connectionStarted = nil
        activeBookmark = nil
        acceptingConnection = false; intent += 1
        audioBusy = false
        audioInterrupted = false
        audio.stop()
        state = Snapshot()
        messages = []
        unread = UnreadSnapshot()
    }
    func refreshFileCache() {
        guard !fileCachePending && !fileCache.working && !fileImporting && !fileCache.busy else { return }
        fileCacheRequest += 1
        fileCachePending = true
        fileCacheMessage = nil
        do { try send(["type": "file_cache_inspect", "request_id": fileCacheRequest]) }
        catch { fileCachePending = false; fileCacheMessage = localizedDetail("files_cache_read_failed", error: error, for: .fileCache) }
    }
    func clearFileCache() {
        guard fileCache.canClear && !fileImporting && !fileCachePending else { return }
        fileCacheRequest += 1
        fileCacheClearRequest = fileCacheRequest
        fileCacheClearing = true
        fileCachePending = true
        fileCacheMessage = nil
        do { try send(["type": "file_cache_clear", "request_id": fileCacheRequest]) }
        catch { fileCachePending = false; fileCacheClearing = false; fileCacheClearRequest = nil; fileCacheMessage = localizedDetail("files_cache_clear_failed", error: error, for: .fileCache) }
    }
    func join(_ channel: Channel, password: String = "") {
        endPushToTalk()
        do { try send(["type": "join", "channel": channel.id, "password": password]) }
        catch { self.error = localized(error, for: .error) }
    }
    func messages(in conversation: String) -> [ChatMessage] {
        messages.filter { $0.conversation == conversation }
    }
    func setChatVisible(server: String, conversation: String, token: UUID, visible: Bool) {
        do { try send(["type": "set_chat_visible", "server": server, "conversation": conversation,
                       "token": token.uuidString, "visible": visible]) }
        catch { self.error = localized(error, for: .error) }
    }
    func setAppActive(_ active: Bool) {
        if !active { endPushToTalk() }
        do { try send(["type": "set_app_active", "active": active]) }
        catch { self.error = localized(error, for: .error) }
    }
    func setReceiveAgc(_ enabled: Bool) {
        do { try send(["type": "set_receive_agc", "enabled": enabled]) }
        catch { self.error = localizedDetail("error_generic", error: error, for: .error) }
    }
    func setUserVolume(server: String, member: Member, percent: Int) {
        guard let token = member.volumeToken else { return }
        do { try send(["type": "set_user_volume", "server": server, "client": member.id,
                       "uid": member.uid as Any? ?? NSNull(), "token": token, "percent": percent]) }
        catch { self.error = localizedDetail("error_generic", error: error, for: .error) }
    }
    func setNoiseSuppression(_ mode: NoiseSuppressionMode) {
        endPushToTalk()
        audioRequest += 1
        do {
            try send(["type": "set_noise_suppression", "mode": mode.rawValue, "request_id": audioRequest])
            noiseSuppression = mode; audioProcessingStatus = "switching"; audioProcessingError = nil
        } catch { audioProcessingError = localizedDetail("error_noise_suppression", error: error, for: .audio) }
    }
    @discardableResult func sendChannelMessage(_ text: String, channel: Channel) -> Bool {
        do {
            try send(["type": "send_channel_message", "request_id": UUID().uuidString,
                      "channel": channel.id, "message": text])
            return true
        } catch { self.error = localized(error, for: .error); return false }
    }
    @discardableResult func sendPrivateMessage(_ text: String, member: Member) -> Bool {
        guard let uid = member.uid else { error = localized(.key("error_user_identity_unavailable"), for: .error); return false }
        do {
            try send(["type": "send_private_message", "request_id": UUID().uuidString,
                      "client": member.id, "uid": uid, "message": text])
            return true
        } catch { self.error = localized(error, for: .error); return false }
    }
    func setAudio(input: Bool? = nil, output: Bool? = nil) async {
        guard !audioBusy else { return }
        let next = Self.resolveAudioState(microphoneMuted: microphoneMuted, deafened: deafened, input: input, output: output)
        if next.inputMuted { endPushToTalk(); try? send(["type": "set_capture_ready", "ready": false]) }
        guard connected else {
            microphoneMuted = next.microphoneMuted; deafened = next.deafened
            return
        }
        audioBusy = true
        let currentIntent = intent
        defer { if currentIntent == intent { audioBusy = false } }
        do {
            if audioInterrupted {
                do {
                    try send(["type": "mute", "input": true, "output": next.deafened])
                    microphoneMuted = next.microphoneMuted; deafened = next.deafened
                } catch { self.error = localized(error, for: .error) }
                return
            }
            try await audio.ensureRunning()
            guard connected && currentIntent == intent && !audioInterrupted else { return }
            if next.inputMuted { audio.stopCapture() }
            else if speech.mode == .automatic || microphonePermission == .granted {
                let allowed = speech.mode == .automatic ? await audio.permission() : microphonePermission == .granted
                microphonePermission = AVAudioSession.sharedInstance().recordPermission
                guard connected && currentIntent == intent else { return }
                if audioInterrupted {
                    do {
                        try send(["type": "mute", "input": true, "output": next.deafened])
                        microphoneMuted = next.microphoneMuted; deafened = next.deafened
                    } catch { self.error = localized(error, for: .error) }
                    return
                }
                guard allowed else { throw LocalizedNotice.key("error_microphone_permission").error(domain: "MobileSpeak", code: 1) }
                try await audio.startCapture()
                guard connected && currentIntent == intent && !audioInterrupted else { return }
            }
            try send(["type": "mute", "input": next.inputMuted, "output": next.deafened])
            audio.setListening(!next.deafened)
            microphoneMuted = next.microphoneMuted; deafened = next.deafened
        } catch {
            guard connected && currentIntent == intent && !audioInterrupted,
                  !(error is CancellationError) else { return }
            let failure = error
            do {
                if muted || speech.mode == .pushToTalk { audio.stopCapture() } else { try await audio.startCapture() }
            } catch {
                guard connected && currentIntent == intent && !audioInterrupted,
                      !(error is CancellationError) else { return }
                audio.stopCapture(); microphoneMuted = true
            }
            guard connected && currentIntent == intent && !audioInterrupted else { return }
            try? send(["type": "mute", "input": muted, "output": deafened])
            self.error = localized(failure, for: .error)
        }
    }
    static func resolveAudioState(microphoneMuted: Bool, deafened: Bool, input: Bool?, output: Bool?) -> (microphoneMuted: Bool, deafened: Bool, inputMuted: Bool) {
        let microphoneMuted = input ?? microphoneMuted
        let deafened = output ?? deafened
        return (microphoneMuted, deafened, microphoneMuted || deafened)
    }
    func setSpeechMode(_ mode: SpeechMode) {
        guard mode != speech.mode else { return }
        endPushToTalk()
        do {
            try send(["type": "set_speech_mode", "mode": mode.rawValue])
            speech.mode = mode; speech.available = false; speech.transmitting = false
            // A mode change gates transmission in Rust; an in-flight manual audio intent still owns its work.
            if connected { Task { await setAudio() } }
        } catch { self.error = localized(error, for: .error) }
    }
    @discardableResult func beginPushToTalk() -> String? {
        guard speech.mode == .pushToTalk, speech.available, !muted, !audioBusy, !audioInterrupted,
              audioProcessingStatus != "switching", microphonePermission == .granted, connected else { return nil }
        let token = speech.token
        do {
            audio.resetCaptureBuffer()
            try send(["type": "push_to_talk", "pressed": true, "token": token])
            pushToTalkToken = token
            return token
        } catch { self.error = localized(error, for: .error); return nil }
    }
    func endPushToTalk(token: String? = nil) {
        guard let held = pushToTalkToken, token == nil || token == held else { return }
        pushToTalkToken = nil
        audio.resetCaptureBuffer()
        try? send(["type": "push_to_talk", "pressed": false, "token": held])
        speech.transmitting = false
    }
    var needsMicrophonePermission: Bool { microphonePermission != .granted }
    func requestPushToTalkPermission() async {
        guard connected, !muted, !audioBusy, speech.mode == .pushToTalk else { return }
        endPushToTalk()
        if microphonePermission == .denied {
            if let url = URL(string: UIApplication.openSettingsURLString) { await UIApplication.shared.open(url) }
            return
        }
        let currentIntent = intent
        _ = await audio.permission()
        microphonePermission = AVAudioSession.sharedInstance().recordPermission
        guard currentIntent == intent, connected, speech.mode == .pushToTalk else { return }
        if microphonePermission == .granted { await setAudio() }
    }
    func audioInterruptionBegan() {
        endPushToTalk()
        guard connected || reconnecting else { return }
        audioInterrupted = true
        audio.pauseForInterruption()
        if connected { try? send(["type": "mute", "input": true, "output": deafened]) }
    }
    func audioInterruptionEnded(shouldResume: Bool) {
        guard connected || reconnecting else { return }
        guard shouldResume else { return }
        audioInterrupted = false
        recoverAudio()
    }
    func audioDidBecomeActive() {
        microphonePermission = AVAudioSession.sharedInstance().recordPermission
        guard connected || reconnecting else { return }
        audioInterrupted = false
        if connected { recoverAudio() }
    }
    func audioRouteChanged() {
        guard connected || reconnecting else { return }
        recoverAudio()
    }
    func audioConfigurationChanged() {
        guard connected || reconnecting else { return }
        recoverAudio()
    }
    private func recoverAudio() {
        guard connected && !audioInterrupted else { return }
        endPushToTalk()
        let currentIntent = intent
        audio.stopCapture()
        Task {
            guard connected && currentIntent == intent && !audioInterrupted else { return }
            do {
                try await audio.ensureRunning()
                guard connected && currentIntent == intent && !audioInterrupted else { return }
                audio.setListening(!deafened)
                if !muted && (speech.mode == .automatic || microphonePermission == .granted) {
                    let allowed = speech.mode == .automatic ? await audio.permission() : microphonePermission == .granted
                    microphonePermission = AVAudioSession.sharedInstance().recordPermission
                    guard connected && currentIntent == intent && !audioInterrupted else { return }
                    guard allowed else { throw LocalizedNotice.key("error_microphone_permission").error(domain: "MobileSpeak", code: 1) }
                    if !muted { try await audio.startCapture() }
                }
                guard connected && currentIntent == intent && !audioInterrupted else { return }
                try send(["type": "mute", "input": muted, "output": deafened])
            } catch {
                guard connected && currentIntent == intent && !audioInterrupted,
                      !(error is CancellationError) else { return }
                audio.stopCapture()
                self.error = localizedDetail("error_audio_recovery", error: error, for: .error)
                try? send(["type": "mute", "input": true, "output": deafened])
            }
        }
    }
    private var identityQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "dev.mobilespeak.mobilespeak.identity", kSecAttrAccount as String: "mobilespeak.identity"]
    }
    private func loadIdentity() throws -> Data? {
        var query = identityQuery; query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let code = SecItemCopyMatching(query as CFDictionary, &result)
        if code == errSecItemNotFound { return nil }
        guard code == errSecSuccess else { throw keychainError(code) }
        return result as? Data
    }
    private func saveIdentity(_ data: Data) throws {
        let code = SecItemUpdate(identityQuery as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if code == errSecItemNotFound {
            var query = identityQuery
            query[kSecValueData as String] = data
            query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let added = SecItemAdd(query as CFDictionary, nil)
            guard added == errSecSuccess else { throw keychainError(added) }
        } else if code != errSecSuccess { throw keychainError(code) }
    }
    private func keychainError(_ code: OSStatus) -> NSError {
        LocalizedNotice.detail("error_identity", String(code)).error(domain: NSOSStatusErrorDomain, code: Int(code))
    }
}
