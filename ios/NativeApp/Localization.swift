import Combine
import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
    case system
    case simplifiedChinese = "zh-Hans"
    case english = "en"

    static let preferenceKey = "mobilespeak.language"
    var id: String { rawValue }

    static func stored(in defaults: UserDefaults = .standard) -> Self {
        Self(rawValue: defaults.string(forKey: preferenceKey) ?? "") ?? .system
    }

    static func resolve(_ selection: Self, systemLanguageTags: [String] = Locale.preferredLanguages) -> Self {
        guard selection == .system else { return selection }
        guard let tag = systemLanguageTags.first else { return .english }
        let locale = Locale(identifier: tag)
        return locale.languageCode == "zh" && (locale.scriptCode == "Hans" || ["CN", "SG"].contains(locale.regionCode ?? ""))
            ? .simplifiedChinese : .english
    }

    var resolvedIdentifier: String { Self.resolve(self).rawValue }
    var locale: Locale { Locale(identifier: resolvedIdentifier) }
    var titleKey: String {
        switch self {
        case .system: "language_system"
        case .simplifiedChinese: "language_simplified_chinese"
        case .english: "language_english"
        }
    }
}

@MainActor final class LanguageSettings: ObservableObject {
    @Published private(set) var selection: AppLanguage
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        selection = AppLanguage.stored(in: defaults)
    }

    @discardableResult func select(_ language: AppLanguage) -> Bool {
        guard selection != language else { return false }
        defaults.set(language.rawValue, forKey: AppLanguage.preferenceKey)
        selection = language
        return true
    }

    var locale: Locale { selection.locale }
}

enum L10n {
    static var language: AppLanguage { AppLanguage.stored() }
    static var locale: Locale { language.locale }

    private static var bundle: Bundle {
        guard let path = Bundle.main.path(forResource: language.resolvedIdentifier, ofType: "lproj"),
              let bundle = Bundle(path: path) else { return .main }
        return bundle
    }

    static func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: "Localizable")
    }

    static func format(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: string(key), locale: locale, arguments: arguments)
    }

    static func channelMembers(_ names: String, count: Int) -> String {
        format("channel_members_summary", names, count)
    }

    static func withDetail(_ key: String, _ detail: String?) -> String {
        let message = string(key)
        return detail.flatMap { $0.isEmpty ? nil : format("error_with_detail", message, $0) } ?? message
    }

    static func coreError(_ code: String?, detail: String? = nil) -> String {
        let keys = [
            "chat_history_read_failed", "channel_subscribe_failed", "message_unconfirmed_after_restart",
            "message_unconfirmed_after_reconnect", "connection_recovering", "client_state_unavailable",
            "join_channel_failed", "update_audio_state_failed", "chat_history_save_failed", "legacy_codec",
            "disconnect_before_connect", "storage_change_while_connected", "message_send_failed",
            "message_wrong_channel", "channel_unavailable", "user_offline", "user_identity_unavailable",
            "user_connection_changed", "unsupported_message_target",
        ]
        let known = code.flatMap { keys.contains($0) ? "error_\($0)" : nil }
        let message = string(known ?? "error_generic")
        let safeDetail = detail ?? (known == nil && code != "core_error" ? code : nil)
        return safeDetail.flatMap { $0.isEmpty ? nil : format("error_with_detail", message, $0) } ?? message
    }
}
