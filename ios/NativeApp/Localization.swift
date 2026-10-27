import Combine
import Foundation

enum AppLanguage: String, CaseIterable, Identifiable {
    case system
    case simplifiedChinese = "zh-Hans"
    case traditionalChinese = "zh-Hant"
    case english = "en"
    case german = "de"
    case russian = "ru"
    case polish = "pl"
    case spanishMexico = "es-MX"
    case french = "fr"
    case portugueseBrazil = "pt-BR"
    case spanish = "es"
    case japanese = "ja"
    case korean = "ko"

    static let preferenceKey = "mobilespeak.language"
    var id: String { rawValue }

    static func stored(in defaults: UserDefaults = .standard) -> Self {
        Self(rawValue: defaults.string(forKey: preferenceKey) ?? "") ?? .system
    }

    static func resolve(_ selection: Self, systemLanguageTags: [String] = Locale.preferredLanguages) -> Self {
        guard selection == .system else { return selection }
        for tag in systemLanguageTags {
            let locale = Locale(identifier: tag)
            switch locale.languageCode {
            case "en": return .english
            case "de": return .german
            case "ru": return .russian
            case "pl": return .polish
            case "fr": return .french
            case "ja": return .japanese
            case "ko": return .korean
            case "es": return locale.regionCode == "MX" ? .spanishMexico : .spanish
            case "pt" where locale.regionCode == "BR": return .portugueseBrazil
            default: break
            }
            guard locale.languageCode == "zh" else { continue }
            if locale.scriptCode == "Hans" { return .simplifiedChinese }
            if locale.scriptCode == "Hant" { return .traditionalChinese }
            switch locale.regionCode {
            case "CN", "SG": return .simplifiedChinese
            case "TW", "HK", "MO": return .traditionalChinese
            case nil: return .simplifiedChinese
            default: continue
            }
        }
        return .english
    }

    var resolvedIdentifier: String { Self.resolve(self).rawValue }
    var locale: Locale {
        let language = Self.resolve(self)
        return Locale(identifier: language == .spanish ? "es-ES" : language.rawValue)
    }
    var titleKey: String {
        switch self {
        case .system: "language_system"
        case .simplifiedChinese: "language_simplified_chinese"
        case .traditionalChinese: "language_traditional_chinese"
        case .english: "language_english"
        case .german: "language_german"
        case .russian: "language_russian"
        case .polish: "language_polish"
        case .spanishMexico: "language_spanish_mexico"
        case .french: "language_french"
        case .portugueseBrazil: "language_portuguese_brazil"
        case .spanish: "language_spanish"
        case .japanese: "language_japanese"
        case .korean: "language_korean"
        }
    }
}

@MainActor final class LanguageSettings: ObservableObject {
    @Published private(set) var selection: AppLanguage
    @Published private(set) var systemLanguage: AppLanguage
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        selection = AppLanguage.stored(in: defaults)
        systemLanguage = AppLanguage.resolve(.system)
    }

    @discardableResult func select(_ language: AppLanguage) -> Bool {
        guard selection != language else { return false }
        if language == .system { refreshSystemLanguage() }
        defaults.set(language.rawValue, forKey: AppLanguage.preferenceKey)
        selection = language
        return true
    }

    func refreshSystemLanguage(systemLanguageTags: [String] = Locale.preferredLanguages) {
        let current = AppLanguage.resolve(.system, systemLanguageTags: systemLanguageTags)
        if systemLanguage != current { systemLanguage = current }
    }

    var locale: Locale { (selection == .system ? systemLanguage : selection).locale }
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

    static func channelMembers(count: Int) -> String {
        format("channel_members_summary", count)
    }

    static func fileSize(_ bytes: UInt64) -> String {
        Int64(clamping: bytes).formatted(.byteCount(style: .file).locale(locale))
    }

    static func fileDate(_ timestamp: Int64) -> String {
        Date(timeIntervalSince1970: Double(timestamp)).formatted(Date.FormatStyle(date: .numeric, time: .shortened).locale(locale))
    }

    private static var percentFormatter: NumberFormatter {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .percent
        formatter.usesGroupingSeparator = false
        return formatter
    }

    static func percent(_ value: Double) -> String {
        percentFormatter.string(from: NSNumber(value: value / 100)) ?? "—"
    }

    // Keep the numeric value separately colored while retaining the locale's percent spacing.
    static var percentPrefix: String { percentFormatter.positivePrefix }
    static var percentSuffix: String { percentFormatter.positiveSuffix }
    static let millisecondsUnit = "ms"

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
            "nickname_invalid", "profile_save_failed", "profile_read_failed", "profile_rename_failed",
            "receive_audio_read_failed", "receive_audio_save_failed", "receive_audio_context_changed",
        ]
        let known = code.flatMap { keys.contains($0) ? "error_\($0)" : nil }
        let message = string(known ?? "error_generic")
        let safeDetail = detail ?? (known == nil && code != "core_error" ? code : nil)
        return safeDetail.flatMap { $0.isEmpty ? nil : format("error_with_detail", message, $0) } ?? message
    }
}

enum LocalizedNotice {
    case key(String)
    case detail(String, String?)
    indirect case nested(String, LocalizedNotice)
    case core(String?, String?)
    case fileCacheError(String, String)
    case fileCacheCleared(UInt64)
    case audioFormat(Double, Int64)

    private static let sourceKey = "MobileSpeak.localizedNotice"

    func error(domain: String, code: Int) -> NSError {
        NSError(domain: domain, code: code, userInfo: [NSLocalizedDescriptionKey: text, Self.sourceKey: self])
    }
    static func source(of error: Error) -> Self? {
        (error as NSError).userInfo[sourceKey] as? Self
    }

    var text: String {
        switch self {
        case .key(let key): L10n.string(key)
        case .detail(let key, let detail): L10n.withDetail(key, detail)
        case .nested(let key, let detail): L10n.withDetail(key, detail.text)
        case .core(let code, let detail): L10n.coreError(code, detail: detail)
        case .fileCacheError(let key, let error): L10n.withDetail(key, error.hasPrefix("files_") ? L10n.string(error) : error)
        case .fileCacheCleared(let bytes): L10n.format("files_cache_cleared", L10n.fileSize(bytes))
        case .audioFormat(let rate, let channels): L10n.format("error_audio_format", rate, channels)
        }
    }
}
