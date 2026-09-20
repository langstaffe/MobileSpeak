import Foundation
import Combine
import Security

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
    var conversation: String? { uid.map { "client:\($0)" } }
}
struct Snapshot: Decodable, Equatable {
    var status = "disconnected"
    var server: String?
    var serverId: String?
    var ownClient: UInt64?
    var canSend: Bool?
    var channels: [Channel] = []
    var clients: [Member] = []
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
    case rnnoise, none
    static let preferenceKey = "mobilespeak.audio.noiseSuppression"
    static func stored(in defaults: UserDefaults) -> Self {
        Self(rawValue: defaults.string(forKey: preferenceKey) ?? "") ?? .rnnoise
    }
    var title: String { self == .rnnoise ? "RNNoise" : "无" }
}

struct Bookmark: Codable, Identifiable {
    var id: UUID
    var title: String
    var host: String
    var port: UInt16
    var nickname: String
    var password: String? = nil // Legacy plaintext only; new writes keep secrets in Keychain.
    var automaticallyNamed: Bool? = nil // Optional keeps existing bookmarks decodable.
    var address: String { "\(host.contains(":") ? "[\(host)]" : host):\(port)" }

    private enum CodingKeys: String, CodingKey { case id, title, host, port, nickname, password, automaticallyNamed }
    init(id: UUID, title: String, host: String, port: UInt16, nickname: String, password: String? = nil, automaticallyNamed: Bool? = nil) {
        self.id = id; self.title = title; self.host = host; self.port = port; self.nickname = nickname
        self.password = password; self.automaticallyNamed = automaticallyNamed
    }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        id = try values.decode(UUID.self, forKey: .id)
        title = try values.decode(String.self, forKey: .title)
        host = try values.decode(String.self, forKey: .host)
        port = try values.decode(UInt16.self, forKey: .port)
        nickname = try values.decode(String.self, forKey: .nickname)
        password = try values.decodeIfPresent(String.self, forKey: .password)
        automaticallyNamed = try values.decodeIfPresent(Bool.self, forKey: .automaticallyNamed)
    }
    func encode(to encoder: Encoder) throws {
        var values = encoder.container(keyedBy: CodingKeys.self)
        try values.encode(id, forKey: .id)
        try values.encode(title, forKey: .title)
        try values.encode(host, forKey: .host)
        try values.encode(port, forKey: .port)
        try values.encode(nickname, forKey: .nickname)
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
    let handle: UnsafeMutableRawPointer
    @Published var state = Snapshot()
    @Published var error: String?
    @Published private(set) var microphoneMuted = false
    @Published private(set) var deafened = false
    @Published private(set) var noiseSuppression = NoiseSuppressionMode.stored(in: .standard)
    @Published var audioBusy = false
    @Published var messages: [ChatMessage] = []
    @Published private(set) var unread = UnreadSnapshot()
    @Published var name = UserDefaults.standard.string(forKey: "mobilespeak.client.name") ?? "MobileSpeakUser"
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
            guard added == errSecSuccess else { throw bookmarkError("无法安全保存书签密码（\(added)）") }
        } else if status != errSecSuccess { throw bookmarkError("无法安全保存书签密码（\(status)）") }
    }
    func bookmarkPassword(_ bookmark: Bookmark) throws -> String {
        guard let current = bookmarks.first(where: { $0.id == bookmark.id }) else { return "" }
        if let password = current.password { return password }
        var query = passwordQuery(bookmark.id); query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess, let data = result as? Data, let value = String(data: data, encoding: .utf8) else { throw bookmarkError("无法读取书签密码（\(status)）") }
        return value
    }
    private func bookmarkError(_ message: String) -> NSError {
        NSError(domain: "MobileSpeak.Bookmarks", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
    @discardableResult func saveBookmark(id: UUID?, title: String, host: String, port: String, nickname: String, password: String) throws -> Bookmark {
        guard !bookmarkLoadFailed else { throw bookmarkError("书签数据读取失败，已停止写入以保护原有数据") }
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "[]")).lowercased()
        let nickname = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, host.utf8.count <= 1000, host.rangeOfCharacter(from: .whitespacesAndNewlines) == nil, !host.contains("/"), let port = UInt16(port), port > 0, !nickname.isEmpty, nickname.utf8.count <= 128, password.utf8.count <= 1024 else { throw bookmarkError("请填写有效的地址、端口、昵称和密码") }
        let duplicate = bookmarks.first { $0.host == host && $0.port == port && $0.nickname == nickname && $0.id != id }
        if id != nil && duplicate != nil { throw bookmarkError("已有相同地址、端口和昵称的书签") }
        let title = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let existing = id.flatMap { id in bookmarks.first { $0.id == id } }
        let automaticallyNamed = title.isEmpty || (existing?.automaticallyNamed == true && title == existing?.title)
        let bookmark = Bookmark(id: id ?? duplicate?.id ?? UUID(), title: title.isEmpty ? host : title, host: host, port: port, nickname: nickname, password: nil, automaticallyNamed: automaticallyNamed)
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
        guard !serverName.isEmpty,
              let index = bookmarks.firstIndex(where: { $0.automaticallyNamed == true && $0.address == address && $0.nickname == name }),
              bookmarks[index].title != serverName else { return }
        do {
            var next = bookmarks
            next[index].title = serverName
            UserDefaults.standard.set(try JSONEncoder().encode(next), forKey: "mobilespeak.server.bookmarks")
            bookmarks = next
        } catch { self.error = error.localizedDescription }
    }
    func deleteBookmark(_ bookmark: Bookmark) {
        do {
            guard !bookmarkLoadFailed else { throw bookmarkError("书签读取失败，无法删除") }
            let next = bookmarks.filter { $0.id != bookmark.id }
            let data = try JSONEncoder().encode(next)
            UserDefaults.standard.set(data, forKey: "mobilespeak.server.bookmarks"); bookmarks = next
            let status = SecItemDelete(passwordQuery(bookmark.id) as CFDictionary)
            if status != errSecSuccess && status != errSecItemNotFound { throw bookmarkError("书签已删除，但密码清理失败（\(status)）") }
        } catch { self.error = error.localizedDescription }
    }
    func connectBookmark(_ bookmark: Bookmark) {
        do { connect(host: bookmark.host, port: String(bookmark.port), nickname: bookmark.nickname, password: try bookmarkPassword(bookmark)) }
        catch { self.error = error.localizedDescription }
    }
    private var acceptingConnection = false
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
            catch { bookmarkLoadFailed = true; self.error = "无法读取或安全迁移已有书签" }
        }
        ts_set_notifier(handle, coreChanged, Int(bitPattern: Unmanaged.passUnretained(self).toOpaque()))
        do {
            let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("MobileSpeak", isDirectory: true)
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try send(["type": "configure", "storage": root.path])
        } catch { self.error = "无法准备本地头像和聊天缓存：\(error.localizedDescription)" }
        do { try send(["type": "set_noise_suppression", "mode": noiseSuppression.rawValue]) }
        catch { self.error = "无法设置噪音抑制：\(error.localizedDescription)" }
    }
    func send(_ command: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: command)
        let result = String(decoding: data, as: UTF8.self).withCString { ts_command(handle, $0) }
        if result != 0 { throw NSError(domain: "MobileSpeak", code: Int(result), userInfo: [NSLocalizedDescriptionKey: "操作未能提交"]) }
    }
    func receive() {
        guard let pointer = ts_poll(handle) else { return }
        defer { ts_free(pointer) }
        do {
            let data = Data(String(cString: pointer).utf8)
            let envelope = try JSONSerialization.jsonObject(with: data) as! [String: Any]
            for event in envelope["events"] as? [[String: Any]] ?? [] {
                if event["type"] as? String == "identity", let identity = event["value"] {
                    do { try saveIdentity(JSONSerialization.data(withJSONObject: identity)) }
                    catch { self.error = error.localizedDescription }
                } else if event["type"] as? String == "error" {
                    error = event["message"] as? String
                } else if event["type"] as? String == "audio_muted" {
                    error = event["message"] as? String
                    if !muted {
                        microphoneMuted = true
                        audio.stopCapture()
                        try? send(["type": "mute", "input": true, "output": deafened])
                    }
                }
            }
            if let chats = envelope["chats"], !(chats is NSNull) {
                messages = try JSONDecoder().decode([ChatMessage].self, from: JSONSerialization.data(withJSONObject: chats))
            }
            let next = try JSONDecoder().decode(Snapshot.self, from: JSONSerialization.data(withJSONObject: envelope["snapshot"]!))
            guard acceptingConnection || next.status == "disconnected" else { return }
            if let value = envelope["unread"], !(value is NSNull) {
                let updated = try JSONDecoder().decode(UnreadSnapshot.self, from: JSONSerialization.data(withJSONObject: value))
                if updated != unread { unread = updated }
            }
            let wasConnected = connected
            let wasInSession = connected || reconnecting
            if next != state { state = next }
            if connected, let serverName = state.server { updateAutomaticBookmarkTitle(serverName) }
            if connected && !wasConnected {
                do {
                    try audio.start()
                    audio.listening = !deafened
                    Task { await setAudio() }
                } catch {
                    self.error = "音频启动失败：\(error.localizedDescription)"
                    try? send(["type": "mute", "input": true, "output": deafened])
                }
            } else if !connected && !reconnecting && wasInSession {
                audioInterrupted = false
                audio.stop()
            }
        } catch { self.error = error.localizedDescription }
    }
    func connect(host: String, port: String, nickname: String, password: String) {
        guard !connected && !busy else { return }
        let host = host.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        let nickname = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !host.isEmpty, host.rangeOfCharacter(from: .whitespacesAndNewlines) == nil,
              !host.contains("/"), let port = UInt16(port), port > 0, !nickname.isEmpty else {
            error = "请填写有效的服务器地址、端口和昵称"; return
        }
        do {
            let identity = try loadIdentity()
            address = "\(host.contains(":") ? "[\(host)]" : host):\(port)"
            name = nickname
            error = nil
            audioInterrupted = false
            try send(["type": "connect", "address": address, "name": name, "password": password,
                      "identity": try identity.map { try JSONSerialization.jsonObject(with: $0) } ?? NSNull()])
            state.status = "connecting"
            acceptingConnection = true; intent += 1
            UserDefaults.standard.set(address, forKey: "mobilespeak.server.address")
            UserDefaults.standard.set(name, forKey: "mobilespeak.client.name")
        } catch { self.error = error.localizedDescription }
    }
    func disconnect() {
        acceptingConnection = false; intent += 1
        audioInterrupted = false
        audio.stop()
        do { try send(["type": "disconnect"]) } catch { self.error = error.localizedDescription }
        state = Snapshot()
        messages = []
        unread = UnreadSnapshot()
    }
    func join(_ channel: Channel, password: String = "") {
        do { try send(["type": "join", "channel": channel.id, "password": password]) }
        catch { self.error = error.localizedDescription }
    }
    func messages(in conversation: String) -> [ChatMessage] {
        messages.filter { $0.conversation == conversation }
    }
    func setChatVisible(server: String, conversation: String, token: UUID, visible: Bool) {
        do { try send(["type": "set_chat_visible", "server": server, "conversation": conversation,
                       "token": token.uuidString, "visible": visible]) }
        catch { self.error = error.localizedDescription }
    }
    func setAppActive(_ active: Bool) {
        do { try send(["type": "set_app_active", "active": active]) }
        catch { self.error = error.localizedDescription }
    }
    func setNoiseSuppression(_ mode: NoiseSuppressionMode) {
        guard mode != noiseSuppression else { return }
        do {
            try send(["type": "set_noise_suppression", "mode": mode.rawValue])
            UserDefaults.standard.set(mode.rawValue, forKey: NoiseSuppressionMode.preferenceKey)
            noiseSuppression = mode
        } catch { self.error = "无法设置噪音抑制：\(error.localizedDescription)" }
    }
    @discardableResult func sendChannelMessage(_ text: String, channel: Channel) -> Bool {
        do {
            try send(["type": "send_channel_message", "request_id": UUID().uuidString,
                      "channel": channel.id, "message": text])
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
    @discardableResult func sendPrivateMessage(_ text: String, member: Member) -> Bool {
        guard let uid = member.uid else { error = "该用户没有可用的 TeamSpeak UID"; return false }
        do {
            try send(["type": "send_private_message", "request_id": UUID().uuidString,
                      "client": member.id, "uid": uid, "message": text])
            return true
        } catch { self.error = error.localizedDescription; return false }
    }
    func setAudio(input: Bool? = nil, output: Bool? = nil) async {
        guard !audioBusy else { return }
        let next = Self.resolveAudioState(microphoneMuted: microphoneMuted, deafened: deafened, input: input, output: output)
        guard connected else {
            microphoneMuted = next.microphoneMuted; deafened = next.deafened
            return
        }
        audioBusy = true
        defer { audioBusy = false }
        let currentIntent = intent
        do {
            if audioInterrupted {
                do {
                    try send(["type": "mute", "input": true, "output": next.deafened])
                    microphoneMuted = next.microphoneMuted; deafened = next.deafened
                } catch { self.error = error.localizedDescription }
                return
            }
            try audio.ensureRunning()
            if next.inputMuted { audio.stopCapture() }
            else {
                let allowed = await audio.permission()
                guard connected && currentIntent == intent else { return }
                if audioInterrupted {
                    do {
                        try send(["type": "mute", "input": true, "output": next.deafened])
                        microphoneMuted = next.microphoneMuted; deafened = next.deafened
                    } catch { self.error = error.localizedDescription }
                    return
                }
                guard allowed else { throw NSError(domain: "MobileSpeak", code: 1, userInfo: [NSLocalizedDescriptionKey: "请在设置中允许麦克风权限"]) }
                try audio.startCapture()
            }
            try send(["type": "mute", "input": next.inputMuted, "output": next.deafened])
            audio.listening = !next.deafened
            microphoneMuted = next.microphoneMuted; deafened = next.deafened
        } catch {
            let failure = error
            do {
                if muted { audio.stopCapture() } else { try audio.startCapture() }
            } catch {
                audio.stopCapture(); microphoneMuted = true
            }
            try? send(["type": "mute", "input": muted, "output": deafened])
            self.error = failure.localizedDescription
        }
    }
    static func resolveAudioState(microphoneMuted: Bool, deafened: Bool, input: Bool?, output: Bool?) -> (microphoneMuted: Bool, deafened: Bool, inputMuted: Bool) {
        let microphoneMuted = input ?? microphoneMuted
        let deafened = output ?? deafened
        return (microphoneMuted, deafened, microphoneMuted || deafened)
    }
    func audioInterruptionBegan() {
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
        guard connected || reconnecting else { return }
        audioInterrupted = false
        if connected && (!audio.isRunning || (!muted && !audio.isCapturing)) { recoverAudio() }
    }
    func audioRouteChanged() {
        guard connected || reconnecting else { return }
        audio.stopCapture()
        recoverAudio()
    }
    func audioConfigurationChanged() {
        guard connected || reconnecting else { return }
        audio.stopCapture()
        recoverAudio()
    }
    private func recoverAudio() {
        guard connected && !audioInterrupted else { return }
        do {
            audio.stopCapture()
            try audio.ensureRunning()
            audio.listening = !deafened
            if !muted { try audio.startCapture() }
            try send(["type": "mute", "input": muted, "output": deafened])
        } catch {
            audio.stopCapture()
            self.error = "音频恢复失败：\(error.localizedDescription)"
            try? send(["type": "mute", "input": true, "output": deafened])
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
        NSError(domain: NSOSStatusErrorDomain, code: Int(code), userInfo: [NSLocalizedDescriptionKey: "无法安全读取或保存 TS 身份（\(code)）"])
    }
}
