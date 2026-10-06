import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct FileCacheState: Decodable, Equatable {
    var bytes: UInt64?
    var items: UInt64 = 0
    var status = "idle"
    var busy = false
    var error: String?
    var working: Bool { status == "loading" || status == "clearing" }
    var canClear: Bool { !busy && !working && bytes != nil && ((bytes ?? 0) > 0 || items > 0) }
}
struct ChannelFileEntry: Decodable, Equatable, Identifiable {
    var name: String
    var size: UInt64
    var timestamp: Int64
    var directory: Bool
    var icon: String
    var localPath: String?
    var id: String { name }
}
struct ChannelFileTransfer: Decodable, Equatable, Identifiable {
    var id: UInt64
    var server: String
    var channel: UInt64
    var path: String
    var name: String
    var upload: Bool
    var size: UInt64
    var transferred: UInt64
    var status: String
    var error: String?
    var localPath: String?
}
struct ChannelFilesState: Decodable, Equatable {
    var open = false
    var server: String?
    var channel: UInt64?
    var channelName: String?
    var path = "/"
    var sort = "name"
    var status = "idle"
    var error: String?
    var entries: [ChannelFileEntry] = []
    var transfers: [ChannelFileTransfer] = []
    func transfer(_ name: String) -> ChannelFileTransfer? { transfers.last { $0.path == path && $0.name == name } }
}
struct ChannelFileTarget {
    let server: String
    let channel: UInt64
    let path: String
    var command: [String: Any] { ["server": server, "channel": channel, "path": path, "directory": path] }
}
extension Client {
    var channelFileTarget: ChannelFileTarget? {
        guard connected, let server = channelFiles.server, let channel = channelFiles.channel else { return nil }
        return ChannelFileTarget(server: server, channel: channel, path: channelFiles.path)
    }
    @discardableResult func fileCommand(_ type: String, fields: [String: Any] = [:]) -> Bool {
        guard connected else { channelFilesLocalError = L10n.string("files_disconnected"); return false }
        channelFilesLocalError = nil
        var context = channelFileTarget?.command ?? [:]
        context["directory"] = channelFiles.path
        do { try send(context.merging(fields) { _, new in new }.merging(["type": type]) { _, new in new }); return true }
        catch { channelFilesLocalError = error.localizedDescription; return false }
    }
    func openChannelFiles() {
        guard connected, let server = state.serverId, let own = state.clients.first(where: { $0.id == state.ownClient }) else { return }
        fileCommand("files_open", fields: ["server": server, "channel": own.channel])
    }
    func downloadChannelFile(_ entry: ChannelFileEntry, to target: ChannelFileTarget?) {
        guard let target else { return }
        fileCommand("files_download", fields: target.command.merging(["name": entry.name]) { _, new in new })
    }
    func uploadChannelFile(_ url: URL, to target: ChannelFileTarget) async {
        guard !fileImporting && !fileCacheClearing && fileCache.status != "clearing" else {
            channelFilesLocalError = L10n.string("files_cache_busy"); return
        }
        fileImporting = true
        defer { fileImporting = false }
        let name = url.lastPathComponent
        guard !name.isEmpty, name != ".", name != "..", name.utf8.count <= 255,
              !name.contains(where: { $0 == "/" || $0 == "\\" || $0.unicodeScalars.contains(where: CharacterSet.controlCharacters.contains) }) else {
            channelFilesLocalError = L10n.string("files_invalid_path"); return
        }
        do {
            let source = try await Task.detached(priority: .utility) {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                    .appendingPathComponent("MobileSpeak/file-uploads", isDirectory: true)
                try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
                let staged = root.appendingPathComponent(UUID().uuidString)
                do { try FileManager.default.copyItem(at: url, to: staged) }
                catch { try? FileManager.default.removeItem(at: staged); throw error }
                return staged
            }.value
            if !fileCommand("files_upload", fields: target.command.merging(["name": name, "source": source.path]) { _, new in new }) {
                try? FileManager.default.removeItem(at: source)
            }
        } catch { channelFilesLocalError = L10n.withDetail("files_choose_failed", error.localizedDescription) }
    }
}

struct FileCacheSettings: View {
    @Environment(\.sizeCategory) private var sizeCategory
    @ObservedObject var client: Client
    @State private var confirming = false
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L10n.string("files_cache_storage")).font(.subheadline)
            Button { confirming = true } label: {
                Group {
                    if sizeCategory.isAccessibilityCategory {
                        VStack(alignment: .leading, spacing: 8) { cacheLabel; cacheSize }
                    } else {
                        HStack(spacing: 12) { cacheLabel; cacheSize }
                    }
                }.frame(minHeight: 48).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                    .background(Palette.card).clipShape(RoundedRectangle(cornerRadius: 14))
            }.buttonStyle(.plain).disabled(!client.fileCache.canClear || client.fileImporting || client.fileCachePending)
                .accessibilityIdentifier("clear-file-cache")
            if let message = client.fileCacheMessage {
                Text(message).font(.footnote).foregroundStyle(client.fileCache.error == nil ? Palette.muted : Palette.disconnect)
                    .accessibilityIdentifier("file-cache-result")
            }
            if client.fileCache.status == "failed" || (client.fileCache.bytes == nil && !client.fileCache.working) {
                Button(L10n.string("files_cache_reload")) { client.refreshFileCache() }.frame(minHeight: 44)
            }
        }
        .onAppear { client.refreshFileCache() }
        .onChange(of: client.fileCache.busy) { busy in if !busy { client.refreshFileCache() } }
        .onChange(of: client.fileImporting) { importing in if !importing { client.refreshFileCache() } }
        .alert(L10n.string("files_cache_confirm_title"), isPresented: $confirming) {
            Button(L10n.string("action_cancel"), role: .cancel) {}
            Button(L10n.string("files_cache_confirm_action"), role: .destructive) { client.clearFileCache() }
                .disabled(!client.fileCache.canClear || client.fileImporting || client.fileCachePending)
        } message: { Text(L10n.string("files_cache_confirm_message")) }
    }
    private var cacheLabel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L10n.string("files_cache_clear"))
            Text(L10n.string(client.fileImporting || client.fileCache.busy ? "files_cache_waiting" : "files_cache_subtitle"))
                .font(.footnote).foregroundStyle(Palette.muted)
        }.frame(maxWidth: .infinity, alignment: .leading).fixedSize(horizontal: false, vertical: true)
    }
    @ViewBuilder private var cacheSize: some View {
        if client.fileCache.working || client.fileCachePending { ProgressView() }
        else { Text(client.fileCache.bytes.map { fileSize($0) } ?? "—").font(.callout).foregroundStyle(Palette.muted).fixedSize() }
    }
}

private func fileError(_ value: String) -> String {
    value.hasPrefix("files_") ? L10n.string(value) : value
}
private func fileSize(_ size: UInt64) -> String { ByteCountFormatter.string(fromByteCount: Int64(clamping: size), countStyle: .file) }
private func fileDate(_ timestamp: Int64) -> String { Date(timeIntervalSince1970: Double(timestamp)).formatted(Date.FormatStyle(date: .numeric, time: .shortened).locale(L10n.locale)) }
private struct ChannelFileName: View {
    let name: String
    var body: some View {
        if let dot = name.lastIndex(of: "."), dot != name.startIndex, name[dot...].count <= 9 {
            HStack(spacing: 0) {
                Text(String(name[..<dot])).lineLimit(1).truncationMode(.tail)
                Text(String(name[dot...])).fixedSize(horizontal: true, vertical: false)
            }.font(.body).accessibilityElement(children: .ignore).accessibilityLabel(name)
        } else { Text(name).font(.body).lineLimit(1).truncationMode(.middle) }
    }
}
private struct FileIcon: View {
    var name: String
    @ScaledMetric private var size: CGFloat = 24
    var body: some View { AppIcon(name: name, size: min(size, 32)) }
}
private struct ChannelFileAction: Identifiable {
    let url: URL
    let share: Bool
    var id: String { url.path + (share ? ":share" : ":open") }
}
struct ChannelFilesDrawer: View {
    @ObservedObject var client: Client
    @State private var info: ChannelFileEntry?
    @State private var choosing = false
    @State private var uploadTarget: ChannelFileTarget?
    @State private var preparing = false
    @State private var fileAction: ChannelFileAction?
    @ScaledMetric(relativeTo: .headline) private var titleSize: CGFloat = 17
    @ScaledMetric(relativeTo: .callout) private var actionSize: CGFloat = 14
    @ScaledMetric(relativeTo: .caption) private var chromeSize: CGFloat = 12
    private var files: ChannelFilesState { client.channelFiles }
    var body: some View {
        let target = client.channelFileTarget
        let canRefresh = target != nil && files.status != "loading" && client.connected
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button {
                    if info != nil { info = nil } else { client.fileCommand("files_back", fields: target?.command ?? [:]) }
                } label: {
                    HStack(spacing: 2) { AppIcon(name: "chevron-right", size: 18).scaleEffect(x: -1, y: 1); Text(L10n.string("action_back")) }
                        .frame(minWidth: 64, minHeight: 44)
                        .foregroundStyle(Palette.accent)
                }
                .accessibilityIdentifier("files-back")
                VStack(spacing: 2) {
                    Text(L10n.string("files_title")).font(.system(size: min(titleSize, 22), weight: .semibold))
                    Text(files.channelName ?? "").font(.system(size: min(chromeSize, 16))).foregroundStyle(Palette.muted).lineLimit(1)
                }.frame(maxWidth: .infinity)
                Button { uploadTarget = target; choosing = uploadTarget != nil } label: {
                    HStack(spacing: 4) { AppIcon(name: "upload", size: 18); Text(L10n.string("files_upload")) }.frame(minWidth: 64, minHeight: 44).foregroundStyle(Palette.accent)
                }.disabled(files.status != "ready" || preparing || !client.connected)
                .accessibilityIdentifier("files-upload")
            }.font(.system(size: min(actionSize, 20))).padding(.horizontal, 8)
            HStack(spacing: 6) {
                AppIcon(name: "folder", size: 18).foregroundStyle(Palette.muted)
                Text(files.path).font(.system(size: min(chromeSize, 18))).lineLimit(1).truncationMode(.middle).frame(maxWidth: .infinity, alignment: .leading)
                Button { client.fileCommand("files_list", fields: target?.command ?? [:]) } label: {
                    HStack(spacing: 5) { AppIcon(name: "refresh", size: 18); Text(L10n.string("files_reload")).font(.system(size: min(chromeSize, 18))) }
                        .frame(minWidth: 44, minHeight: 44).padding(.horizontal, 4).foregroundStyle(Palette.accent)
                }.fixedSize(horizontal: true, vertical: false).disabled(!canRefresh)
                .accessibilityIdentifier("files-refresh")
                Menu {
                    Button(L10n.string("files_sort_name")) { client.fileCommand("files_sort", fields: (target?.command ?? [:]).merging(["newest": false]) { _, new in new }) }
                    Button(L10n.string("files_sort_newest")) { client.fileCommand("files_sort", fields: (target?.command ?? [:]).merging(["newest": true]) { _, new in new }) }
                } label: {
                    HStack(spacing: 5) { AppIcon(name: "sort", size: 18); Text(L10n.string(files.sort == "newest" ? "files_sort_newest" : "files_sort_name")).font(.system(size: min(chromeSize, 18))) }.frame(minHeight: 44).foregroundStyle(Palette.accent)
                }.fixedSize(horizontal: true, vertical: false)
                .accessibilityIdentifier("files-sort")
            }.padding(.horizontal, 14)
            Divider().overlay(Palette.border)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if preparing { ProgressView(L10n.string("files_staging")).padding() }
                    if let error = client.channelFilesLocalError ?? files.error {
                        Text(fileError(error)).font(.callout).foregroundStyle(Palette.disconnect).frame(maxWidth: .infinity, alignment: .leading).padding()
                    }
                    if let info { information(info, target: target) }
                    else {
                        ForEach(files.transfers.filter { task in task.path == files.path && task.upload && (files.status != "ready" || !files.entries.contains(where: { e in e.name == task.name })) }) { task in
                            VStack(alignment: .leading, spacing: 5) {
                                HStack { FileIcon(name: "upload"); Text(task.name).lineLimit(1).truncationMode(.middle); Spacer() }
                                transferStatus(task)
                            }.padding(14)
                        }
                        if files.status == "loading" { ProgressView(L10n.string("files_loading")).padding(24) }
                        else if files.status == "failed" {
                            AppIcon(name: "error", size: 32).foregroundStyle(Palette.disconnect).padding(.top, 24)
                            Text(L10n.string("files_load_failed")).padding(8)
                            Button(L10n.string("files_reload")) { client.fileCommand("files_list", fields: target?.command ?? [:]) }.frame(minHeight: 44).disabled(!canRefresh)
                                .accessibilityIdentifier("files-refresh-retry")
                        } else if files.status == "ready" && files.entries.isEmpty {
                            AppIcon(name: "folder", size: 40).foregroundStyle(Palette.muted).padding(.top, 24)
                            Text(L10n.string("files_empty")).foregroundStyle(Palette.muted).padding(8)
                            Button(L10n.string("files_upload")) { uploadTarget = target; choosing = uploadTarget != nil }.frame(minHeight: 44).disabled(preparing)
                        }
                        ForEach(files.entries) { entry in row(entry, target: target) }
                    }
                }
            }.accessibilityIdentifier("channel-files-list")
        }
        .onChange(of: files.path) { _ in info = nil }
        .onChange(of: files.channel) { _ in info = nil }
        .onChange(of: files.server) { _ in info = nil }
        .onChange(of: files.open) { _ in info = nil }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.item], allowsMultipleSelection: false) { result in
            guard let target = uploadTarget else { return }
            switch result {
            case .success(let urls): if let url = urls.first { preparing = true; Task { await client.uploadChannelFile(url, to: target); preparing = false } }
            case .failure(let error): client.channelFilesLocalError = L10n.withDetail("files_choose_failed", error.localizedDescription)
            }
        }
        .sheet(item: $fileAction) { action in
            if action.share { ChannelFileShare(url: action.url, onClose: { fileAction = nil }) }
            else { ChannelFileOpen(url: action.url, onFailure: { client.channelFilesLocalError = L10n.string("files_choose_failed") }, onClose: { fileAction = nil }) }
        }
    }
    @ViewBuilder private func row(_ entry: ChannelFileEntry, target: ChannelFileTarget?) -> some View {
        HStack(spacing: 12) {
            Button {
                if entry.directory {
                    client.fileCommand("files_list", fields: (target?.command ?? [:]).merging(["path": target?.path == "/" ? "/\(entry.name)" : "\(target?.path ?? "/")/\(entry.name)"]) { _, new in new })
                } else { info = entry }
            } label: {
                HStack(spacing: 12) {
                    FileIcon(name: entry.icon).foregroundStyle(Palette.muted)
                    VStack(alignment: .leading, spacing: 5) {
                        ChannelFileName(name: entry.name).foregroundStyle(.white)
                        Text("\(fileSize(entry.size)) · \(fileDate(entry.timestamp))").font(.caption).foregroundStyle(Palette.muted)
                    }.frame(maxWidth: .infinity, alignment: .leading)
                    if entry.directory { AppIcon(name: "chevron-right", size: 18).foregroundStyle(Palette.muted) }
                }.frame(minHeight: 64).contentShape(Rectangle())
            }.buttonStyle(.plain).accessibilityLabel((entry.directory ? L10n.string("files_folder") + ": " : "") + entry.name)
            if !entry.directory {
                Menu {
                    Button { client.downloadChannelFile(entry, to: target) } label: { Label { Text(L10n.string("files_download")) } icon: { AppIcon(name: "download", size: 20) } }
                    Button { info = entry } label: { Label { Text(L10n.string("files_info")) } icon: { AppIcon(name: "info", size: 20) } }
                } label: { AppIcon(name: "more", size: 22).frame(width: 44, height: 44) }
                .accessibilityLabel(L10n.string("files_info") + ": " + entry.name)
            }
        }.padding(.horizontal, 14)
        .overlay(alignment: .bottom) { Divider().overlay(Palette.border) }
        if let task = files.transfer(entry.name) { transferStatus(task).padding(.horizontal, 14).padding(.vertical, 5) }
        else if let local = entry.localPath { downloaded(local).padding(.horizontal, 14) }
    }
    @ViewBuilder private func transferStatus(_ task: ChannelFileTransfer) -> some View {
        if task.status == "running" {
            VStack(alignment: .leading, spacing: 4) {
                ProgressView(value: task.size > 0 ? Double(task.transferred) / Double(task.size) : 0)
                Text("\(L10n.string(task.upload ? "files_upload" : "files_download")) · \(fileSize(task.transferred)) / \(fileSize(task.size))").font(.caption).foregroundStyle(Palette.muted)
            }
        } else if task.status == "failed" {
            VStack(alignment: .leading) {
                Label { Text(fileError(task.error ?? "files_failed")).font(.caption) } icon: { AppIcon(name: "error", size: 16) }.foregroundStyle(Palette.disconnect)
                Button(L10n.string("files_retry")) { client.fileCommand("files_retry", fields: ["id": task.id]) }.frame(minHeight: 44).disabled(!client.connected)
            }
        } else if !task.upload, let path = task.localPath { downloaded(path) }
        else { Label { Text(L10n.string("files_complete")).font(.caption) } icon: { AppIcon(name: "checkmark", size: 16) }.foregroundStyle(Palette.green) }
    }
    private func downloaded(_ path: String) -> some View {
        HStack {
            Label { Text(L10n.string("files_downloaded")).font(.caption) } icon: { AppIcon(name: "checkmark", size: 16) }.foregroundStyle(Palette.green)
            Spacer()
            Button(L10n.string("files_open")) { fileAction = ChannelFileAction(url: URL(fileURLWithPath: path), share: false) }.frame(minWidth: 44, minHeight: 44)
            Button { fileAction = ChannelFileAction(url: URL(fileURLWithPath: path), share: true) } label: { AppIcon(name: "share", size: 20).frame(width: 44, height: 44) }.accessibilityLabel(L10n.string("files_share"))
        }
    }
    private func information(_ entry: ChannelFileEntry, target: ChannelFileTarget?) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            FileIcon(name: entry.icon).foregroundStyle(Palette.muted)
            Text(entry.name).font(.headline).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            metadata("files_type", URL(fileURLWithPath: entry.name).pathExtension.isEmpty ? L10n.string("files_file") : URL(fileURLWithPath: entry.name).pathExtension.uppercased())
            metadata("files_size", fileSize(entry.size))
            metadata("files_time", fileDate(entry.timestamp))
            metadata("files_location", files.path)
            Button { client.downloadChannelFile(entry, to: target) } label: {
                HStack { AppIcon(name: "download", size: 20); Text(L10n.string("files_download")) }.frame(minHeight: 44)
            }.disabled(files.transfer(entry.name)?.status == "running")
            if let task = files.transfer(entry.name) { transferStatus(task) }
            else if let local = files.entries.first(where: { $0.name == entry.name })?.localPath { downloaded(local) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
    }
    private func metadata(_ key: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) { Text(L10n.string(key)).font(.caption).foregroundStyle(Palette.muted); Text(value).font(.callout).textSelection(.enabled) }
    }
}
private struct ChannelFileShare: UIViewControllerRepresentable {
    let url: URL
    let onClose: () -> Void
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in onClose() }
        return controller
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
// The system document menu offers installed apps; the client has no embedded preview.
private struct ChannelFileOpen: UIViewControllerRepresentable {
    let url: URL
    let onFailure: () -> Void
    let onClose: () -> Void
    func makeUIViewController(context: Context) -> ChannelFileOpenController { ChannelFileOpenController(url: url, onFailure: onFailure, onClose: onClose) }
    func updateUIViewController(_ controller: ChannelFileOpenController, context: Context) {}
}
private final class ChannelFileOpenController: UIViewController, UIDocumentInteractionControllerDelegate {
    private let document: UIDocumentInteractionController
    private var presented = false
    private let onFailure: () -> Void
    private let onClose: () -> Void
    init(url: URL, onFailure: @escaping () -> Void, onClose: @escaping () -> Void) {
        document = UIDocumentInteractionController(url: url); self.onFailure = onFailure; self.onClose = onClose
        super.init(nibName: nil, bundle: nil); document.delegate = self
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !presented else { return }; presented = true
        if !document.presentOpenInMenu(from: view.bounds, in: view, animated: true) { onFailure(); onClose() }
    }
    func documentInteractionControllerDidDismissOpenInMenu(_ controller: UIDocumentInteractionController) { onClose() }
}
