import SwiftUI
import AVFoundation
import UIKit

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
        Text(L10n.string(key))
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
    @ViewBuilder func compactPopover() -> some View {
        if #available(iOS 16.4, *) { presentationCompactAdaptation(.popover) }
        else { self }
    }
}
@main struct MobileSpeakApp: App {
    @StateObject private var client = Client.shared
    @StateObject private var language = LanguageSettings()
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            NavigationView { HomeView(client: client) }
                .navigationViewStyle(.stack)
                .environmentObject(language)
                .environment(\.locale, language.locale)
                .preferredColorScheme(.dark)
                .tint(Palette.accent)
                .onAppear { client.setAppActive(phase == .active) }
                .onChange(of: phase) { phase in
                    client.setAppActive(phase == .active)
                    if phase == .active { client.audioDidBecomeActive() }
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
    var body: some View {
        ZStack {
            Text(String(name.prefix(2)).uppercased()).font(.system(size: size * 0.36, weight: .bold))
                .frame(width: size, height: size).background(Palette.accent)
            if let path { LocalImage(path: path).frame(width: size, height: size) }
        }
            .frame(width: size, height: size).clipShape(Circle())
            .overlay(Circle().stroke(speaking ? Palette.green : .clear, lineWidth: 3))
    }
}
private struct UnreadBadge: View {
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
                    Image(systemName: "person.fill")
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
            Image(systemName: channel.password ? "lock" : "number")
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
struct HomeView: View {
    @ObservedObject var client: Client
    @EnvironmentObject private var language: LanguageSettings
    @State private var tab = 0
    @State private var showConnect = false
    @State private var editingBookmark: Bookmark?
    @State private var deletingBookmark: Bookmark?
    @State private var languageMenuPresented = false
    @State private var channelSheet = ChannelSheetState()
    @State private var lockedChannel: Channel?
    @State private var channelPassword = ""
    @State private var channelMemberListHeight: CGFloat = 52
    @State private var viewportHeight = UIScreen.main.bounds.height
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 11) {
                if let server = client.state.server {
                    Avatar(name: server)
                } else {
                    Image("AppLogo").resizable().frame(width: 34, height: 34).clipShape(RoundedRectangle(cornerRadius: 8)).accessibilityLabel("MobileSpeak")
                }
                Text(client.state.server ?? "MobileSpeak").font(.system(size: 16, weight: .heavy)).lineLimit(1)
                Spacer(minLength: 0)
                if client.connected || client.busy {
                    Button { client.disconnect() } label: {
                        Text(L10n.string("disconnect_badge")).font(.system(size: 10, weight: .heavy))
                            .frame(width: 34, height: 34).background(Palette.disconnect).clipShape(Circle())
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
            Group {
                if tab == 2 { settings }
                else if client.busy { VStack(spacing: 20) { ProgressView(); Text(L10n.string(client.reconnecting ? "status_reconnecting" : "status_connecting_server")) }.frame(maxWidth: .infinity, maxHeight: .infinity) }
                else if !client.connected { if client.bookmarks.isEmpty { empty } else { bookmarkList } }
                else if tab == 0 { channels }
                else { members }
            }.frame(maxWidth: .infinity, maxHeight: .infinity)
            voiceBar
            HStack {
                navigation(L10n.string("tab_channels"), "number", 0)
                navigation(L10n.string("tab_members"), "person.2", 1)
                navigation(L10n.string("tab_settings"), "gearshape", 2)
            }.padding(.top, 10).padding(.bottom, 8).background(Palette.bottom)
        }
        .foregroundStyle(Color(hex: 0xF2F3F5))
        .background(Palette.background.ignoresSafeArea())
        .background(GeometryReader { proxy in
            Color.clear
                .onAppear { viewportHeight = proxy.size.height }
                .onChange(of: proxy.size.height) { viewportHeight = $0 }
        })
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
                                Avatar(name: bookmark.title)
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(bookmark.title).font(.headline)
                                    Text(bookmark.address).font(.caption).foregroundStyle(Palette.muted)
                                    Text(bookmark.nickname).font(.caption).foregroundStyle(Palette.muted)
                                }
                                Spacer()
                            }.frame(minHeight: 64).contentShape(Rectangle())
                        }.buttonStyle(.plain).disabled(client.connected || client.busy)
                        Menu {
                            Button(L10n.string("action_edit"), systemImage: "pencil") { editingBookmark = bookmark }
                            Button(L10n.string("action_delete"), systemImage: "trash", role: .destructive) { deletingBookmark = bookmark }
                        } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }.accessibilityLabel(L10n.format("bookmark_manage", bookmark.title))
                    }.padding(12).background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
                }
                Button { showConnect = true } label: { Label(L10n.string("bookmark_add_server"), systemImage: "plus") }.padding(.vertical, 12)
                if client.connected { Text(L10n.string("bookmark_disconnect_first")).font(.footnote).foregroundStyle(Palette.muted) }
            }.padding(16)
        }.background(Palette.background)
    }
    private var empty: some View {
        VStack(spacing: 16) {
            Image(systemName: "headphones").font(.system(size: 52)).foregroundStyle(Palette.muted)
            Text(L10n.string("empty_title")).font(.system(size: 20, weight: .heavy))
            Text(L10n.string("empty_message")).font(.subheadline).foregroundStyle(Palette.muted).multilineTextAlignment(.center)
            Button { showConnect = true } label: { Label(L10n.string("action_connect_server"), systemImage: "plus").padding(.vertical, 5) }
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
                                        HStack(spacing: 5) {
                                            ForEach(Array(members.prefix(2))) { m in Avatar(name: m.name, path: m.avatarPath, speaking: m.speaking, size: 24) }
                                            Text(L10n.channelMembers(members.prefix(2).map(\.name).joined(separator: L10n.string("member_name_separator")), count: members.count)).font(.system(size: 11)).foregroundStyle(Palette.muted).lineLimit(1)
                                        }.padding(.leading, 30)
                                    }
                                }.contentShape(Rectangle())
                            }.buttonStyle(.plain)
                            NavigationLink(destination: ChannelChatView(client: client, channel: channel)) {
                                ZStack {
                                    Image(systemName: "bubble.left.fill").font(.system(size: 18)).offset(x: -4, y: -3)
                                    Image(systemName: "bubble.right.fill").font(.system(size: 12)).offset(x: 6, y: 6)
                                }.frame(width: 48, height: 48)
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
            Avatar(name: m.name, path: m.avatarPath, speaking: m.speaking)
                .overlay(alignment: .topTrailing) {
                    if showUnread { UnreadBadge(count: client.privateUnread(m)).offset(x: 5, y: -5) }
                }
            Text(m.name).lineLimit(1).truncationMode(.tail)
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
            Image(systemName: m.deafened ? "speaker.slash.fill" : m.muted ? "mic.slash.fill" : "mic.fill")
                .foregroundStyle(m.speaking ? Palette.green : Palette.muted)
                .frame(width: 20)
                .accessibilityLabel(L10n.string(m.deafened ? "member_listening_off" : m.muted ? "member_muted" : m.speaking ? "member_speaking" : "member_microphone_on"))
        }
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
        .contentShape(Rectangle())
    }
    private var settings: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                PageTitle(key: "tab_settings").padding(.horizontal, PageTitle.inset)
                VStack(alignment: .leading, spacing: 24) {
                    Toggle(L10n.string("settings_microphone"), isOn: Binding(get: { !client.microphoneMuted }, set: { value in Task { await client.setAudio(input: !value) } })).disabled(client.audioBusy || client.deafened)
                    Text(L10n.string("settings_microphone_help")).font(.footnote).foregroundStyle(Palette.muted)
                    Toggle(L10n.string("settings_listening"), isOn: Binding(get: { !client.deafened }, set: { value in Task { await client.setAudio(output: !value) } })).disabled(client.audioBusy)
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
                    }
                    Button { languageMenuPresented = true } label: {
                        HStack(spacing: 12) {
                            Text(L10n.string("settings_language"))
                                .lineLimit(1)
                                .layoutPriority(1)
                            Spacer(minLength: 0)
                            Text(L10n.string(language.selection.titleKey))
                                .foregroundStyle(Palette.muted)
                                .lineLimit(1)
                                .minimumScaleFactor(0.75)
                            Image(systemName: "chevron.down")
                                .font(.caption.bold())
                                .foregroundStyle(Palette.muted)
                                .accessibilityHidden(true)
                        }
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .padding(.horizontal, 16)
                        .contentShape(Rectangle())
                        .background(Palette.card)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(L10n.string("settings_language"))
                    .accessibilityValue(L10n.string(language.selection.titleKey))
                    .accessibilityHint(L10n.string("accessibility_choose_language"))
                    .popover(isPresented: $languageMenuPresented, attachmentAnchor: .point(.topTrailing), arrowEdge: .bottom) {
                        VStack(spacing: 0) {
                            ForEach(AppLanguage.allCases) { option in
                                Button {
                                    languageMenuPresented = false
                                    guard language.select(option) else { return }
                                    UISelectionFeedbackGenerator().selectionChanged()
                                } label: {
                                    HStack(spacing: 12) {
                                        Image(systemName: "checkmark")
                                            .opacity(language.selection == option ? 1 : 0)
                                            .accessibilityHidden(true)
                                        Text(L10n.string(option.titleKey))
                                        Spacer(minLength: 0)
                                    }
                                    .frame(minWidth: 170, minHeight: 44, alignment: .leading)
                                    .padding(.horizontal, 16)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityValue(L10n.string(language.selection == option ? "selection_selected" : "selection_not_selected"))
                            }
                        }
                        .padding(.vertical, 8)
                        .compactPopover()
                    }
                }.padding(.horizontal, 20)
            }.padding(.top, PageTitle.inset).padding(.bottom, 20)
        }
    }
    private var voiceBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "waveform").foregroundStyle(client.connected ? Palette.green : Palette.muted)
            Text(client.connected ? L10n.format("status_connected_to_channel", client.state.channels.first { $0.id == client.currentChannel }?.name ?? "") : client.busy ? L10n.string("status_connecting") : L10n.string("status_disconnected")).font(.system(size: 12, weight: .semibold)).lineLimit(1)
            Spacer(minLength: 0)
            Button {
                UISelectionFeedbackGenerator().selectionChanged()
                Task { await client.setAudio(input: !client.microphoneMuted) }
            } label: {
                Image(systemName: client.muted ? "mic.slash.fill" : "mic.fill").foregroundStyle(client.muted ? Palette.muted : Palette.green).frame(width: 44, height: 48)
            }.accessibilityLabel(L10n.string(client.microphoneMuted ? "voice_enable_microphone" : "voice_mute")).disabled(client.audioBusy || client.deafened)
            Button {
                UISelectionFeedbackGenerator().selectionChanged()
                Task { await client.setAudio(output: !client.deafened) }
            } label: {
                Image(systemName: client.deafened ? "speaker.slash.fill" : "speaker.wave.2.fill").frame(width: 44, height: 48)
            }.accessibilityLabel(L10n.string(client.deafened ? "voice_enable_listening" : "voice_disable_listening")).disabled(client.audioBusy)
        }.padding(.leading, 12).padding(.trailing, 8).background(Palette.bottom)
    }
    private func navigation(_ title: String, _ icon: String, _ index: Int) -> some View {
        Button { tab = index } label: {
            VStack(spacing: 5) {
                Image(systemName: icon).font(.system(size: 22))
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
            HStack(alignment: .bottom, spacing: 10) {
                TextField(L10n.string("chat_input_placeholder"), text: $text)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(send)
                Button(action: send) { Image(systemName: "paperplane.fill").frame(width: 38, height: 38) }
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
                        Avatar(name: member.name, path: currentMember?.avatarPath ?? member.avatarPath, size: 30)
                        Text(member.name).font(.headline)
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
        guard let uid = member?.uid else { return nil }
        return client.state.clients.first { $0.uid == uid }
    }
    private func send() {
        let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard available, !value.isEmpty else { return }
        guard value.utf8.count <= 8192 else { client.error = L10n.string("chat_message_too_long"); return }
        if let channel, client.sendChannelMessage(value, channel: channel) { text = "" }
        else if let member = currentMember, client.sendPrivateMessage(value, member: member) { text = "" }
    }
    @ViewBuilder private func messageRow(_ message: ChatMessage) -> some View {
        HStack(alignment: .bottom, spacing: 8) {
            if message.own { Spacer(minLength: 46) }
            else { Avatar(name: message.senderName, path: liveAvatarPath(message) ?? message.avatarPath, size: 30) }
            VStack(alignment: message.own ? .trailing : .leading, spacing: 4) {
                if channel != nil && !message.own {
                    Text(message.senderName).font(.caption).foregroundStyle(Palette.muted)
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
                            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
                            Text(message.error.map { L10n.coreError($0, detail: "") } ?? L10n.string("chat_send_failed")).foregroundStyle(.red)
                        }
                    }.font(.caption2)
                }
            }
            if message.own { Avatar(name: message.senderName, path: liveAvatarPath(message) ?? message.avatarPath, size: 30) }
            else { Spacer(minLength: 46) }
        }
    }
    private func liveAvatarPath(_ message: ChatMessage) -> String? {
        client.state.clients.first { $0.uid == message.senderUid }?.avatarPath
    }
}

struct ConnectView: View {
    @ObservedObject var client: Client
    var bookmark: Bookmark? = nil
    @State private var title = ""
    @State private var formError: String?
    @State private var passwordLoaded = false
    @Environment(\.dismiss) private var dismiss
    @State private var host = ""
    @State private var port = "9987"
    @State private var name = ""
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
                Section(L10n.string("nickname")) { TextField(L10n.string("nickname"), text: $name).autocorrectionDisabled() }
                Section {
                    Button(L10n.string(bookmark != nil || client.connected || client.busy ? "bookmark_save" : "action_save_and_connect")) {
                        do {
                            let saved = try client.saveBookmark(id: bookmark?.id, title: title, host: host, port: port, nickname: name, password: password)
                            if bookmark == nil && !client.connected && !client.busy { client.connectBookmark(saved) }
                            dismiss()
                        } catch { formError = error.localizedDescription }
                    }.disabled(!passwordLoaded)
                    if let error = formError { Text(error).foregroundStyle(.red) }
                }
            }.navigationTitle(L10n.string(bookmark == nil ? "bookmark_add_server" : "bookmark_edit_server")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L10n.string("action_cancel")) { dismiss() } } }
        }.onAppear {
            let url = URLComponents(string: "ts://" + client.address)
            host = bookmark?.host ?? url?.host ?? ""; port = String(bookmark?.port ?? UInt16(url?.port ?? 9987)); name = bookmark?.nickname ?? client.name
            title = bookmark?.title ?? ""
            do { password = try bookmark.map { try client.bookmarkPassword($0) } ?? ""; passwordLoaded = true }
            catch { formError = error.localizedDescription }
        }
    }
}
