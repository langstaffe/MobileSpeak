package dev.mobilespeak.mobilespeak

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal enum class AppLanguage(val code: String, val tag: String?, @param:StringRes val title: Int) {
    SYSTEM("system", null, R.string.language_system),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-Hans", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", "zh-Hant", R.string.language_traditional_chinese),
    ENGLISH("en", "en", R.string.language_english),
    GERMAN("de", "de", R.string.language_german),
    RUSSIAN("ru", "ru", R.string.language_russian),
    POLISH("pl", "pl", R.string.language_polish),
    SPANISH_MEXICO("es-MX", "es-MX", R.string.language_spanish_mexico),
    FRENCH("fr", "fr", R.string.language_french),
    PORTUGUESE_BRAZIL("pt-BR", "pt-BR", R.string.language_portuguese_brazil),
    SPANISH("es", "es", R.string.language_spanish);

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: SYSTEM

        fun selected(): AppLanguage {
            return selected(AppCompatDelegate.getApplicationLocales().toLanguageTags().split(',').filter(String::isNotEmpty))
        }

        fun selected(context: Context): AppLanguage = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val locales = context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
            selected((0 until locales.size()).map { locales[it].toLanguageTag() })
        } else selected()

        fun selected(applicationLanguageTags: List<String>): AppLanguage =
            applicationLanguageTags.firstOrNull()?.let { supported(Locale.forLanguageTag(it)) ?: ENGLISH } ?: SYSTEM

        fun select(language: AppLanguage): Boolean {
            if (!shouldApply(selected(), language) &&
                AppCompatDelegate.getApplicationLocales().toLanguageTags() == language.tag.orEmpty()) return false
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList(),
            )
            ClientSession.refreshLanguage(resolve(language, systemLanguageTags()))
            AppUpdates.refreshLanguage()
            return true
        }

        fun resolve(selection: AppLanguage, systemLanguageTags: List<String>): AppLanguage =
            if (selection != SYSTEM) selection else systemLanguageTags.firstNotNullOfOrNull {
                supported(Locale.forLanguageTag(it))
            } ?: ENGLISH

        private fun supported(locale: Locale): AppLanguage? = when {
            locale.language == "en" -> ENGLISH
            locale.language == "de" -> GERMAN
            locale.language == "ru" -> RUSSIAN
            locale.language == "pl" -> POLISH
            locale.language == "fr" -> FRENCH
            locale.language == "es" && locale.country == "MX" -> SPANISH_MEXICO
            locale.language == "es" -> SPANISH
            locale.language == "pt" && locale.country == "BR" -> PORTUGUESE_BRAZIL
            locale.language != "zh" -> null
            locale.script.equals("Hans", ignoreCase = true) -> SIMPLIFIED_CHINESE
            locale.script.equals("Hant", ignoreCase = true) -> TRADITIONAL_CHINESE
            locale.country in setOf("CN", "SG") -> SIMPLIFIED_CHINESE
            locale.country in setOf("TW", "HK", "MO") -> TRADITIONAL_CHINESE
            locale.country.isEmpty() -> SIMPLIFIED_CHINESE
            else -> null
        }

        fun shouldApply(current: AppLanguage, requested: AppLanguage) = current != requested
    }
}

internal fun Context.languageContext(): Context {
    val language = AppLanguage.resolve(AppLanguage.selected(this), systemLanguageTags())
    return contextForLanguage(language)
}

internal fun Context.contextForLanguage(language: AppLanguage): Context {
    val configuration = android.content.res.Configuration(resources.configuration)
    ConfigurationCompat.setLocales(configuration, LocaleListCompat.forLanguageTags(language.tag!!))
    return createConfigurationContext(configuration)
}

internal fun Context.resolvedSystemLanguageContext(): Context {
    val language = AppLanguage.resolve(AppLanguage.SYSTEM, systemLanguageTags())
    return contextForLanguage(language)
}

internal fun systemLanguageTags(): List<String> = android.content.res.Resources.getSystem().configuration.locales.let { locales ->
    (0 until locales.size()).map { locales[it].toLanguageTag() }
}

internal fun Context.localized(@StringRes id: Int, vararg arguments: Any): String =
    languageContext().getString(id, *arguments)

internal fun Context.localizedQuantity(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): String =
    languageContext().resources.getQuantityString(id, quantity, *arguments)

internal fun Context.localizedFileSize(bytes: Long): String =
    android.text.format.Formatter.formatFileSize(languageContext(), bytes)

internal fun Context.localizedFileDate(timestamp: Long): String = java.text.DateFormat.getDateTimeInstance(
    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, languageContext().resources.configuration.locales[0],
).format(java.util.Date(timestamp * 1000))

internal fun localizedPercent(percent: Int, locale: Locale): String = java.text.NumberFormat.getPercentInstance(locale).apply {
    isGroupingUsed = false
    maximumFractionDigits = 0
}.format(percent / 100.0)

internal fun percentAffixes(locale: Locale): Pair<String, String> =
    (java.text.NumberFormat.getPercentInstance(locale) as java.text.DecimalFormat).let { it.positivePrefix to it.positiveSuffix }

internal fun millisecondAffixes(locale: Locale): Pair<String, String> {
    val position = java.text.FieldPosition(android.icu.text.NumberFormat.INTEGER_FIELD)
    val text = android.icu.text.MeasureFormat.getInstance(locale, android.icu.text.MeasureFormat.FormatWidth.SHORT)
        .formatMeasures(StringBuilder(), position, android.icu.util.Measure(0, android.icu.util.MeasureUnit.MILLISECOND)).toString()
    return text.substring(0, position.beginIndex) to text.substring(position.endIndex)
}

data class LocalizedMessage(
    @param:StringRes val id: Int,
    val detail: String? = null,
    val detailSource: LocalizedMessage? = null,
    val sizeBytes: Long? = null,
) {
    fun render(context: Context): String {
        if (sizeBytes != null) return context.getString(id, android.text.format.Formatter.formatFileSize(context, sizeBytes))
        val base = context.getString(id)
        val suffix = detailSource?.render(context) ?: detail
        return if (suffix.isNullOrBlank()) base else context.getString(R.string.error_with_detail, base, suffix)
    }
}

internal class LocalizedFailure(val source: LocalizedMessage, context: Context) :
    IllegalStateException(source.render(context.languageContext()))

internal fun localizedFailureSource(@StringRes id: Int, error: Throwable): LocalizedMessage =
    LocalizedMessage(id, detail = if (error is LocalizedFailure) null else error.message.orEmpty(),
        detailSource = (error as? LocalizedFailure)?.source)

internal fun Member.accessibilityLabel(context: Context): String =
    (listOf(name) + listOf(away to R.string.member_away, deafened to R.string.member_listening_off,
        muted to R.string.member_muted, speaking to R.string.member_speaking)
        .filter { it.first }.map { context.localized(it.second) }).joinToString(", ")

internal fun coreErrorSource(code: String?, detail: String? = null): LocalizedMessage {
    val resource = when (code) {
            "nickname_invalid" -> R.string.error_nickname_invalid
            "profile_save_failed" -> R.string.error_profile_save_failed
            "profile_read_failed" -> R.string.error_profile_read_failed
            "profile_rename_failed" -> R.string.error_profile_rename_failed
            "chat_history_read_failed" -> R.string.error_chat_history_read_failed
            "receive_audio_read_failed" -> R.string.error_receive_audio_read_failed
            "receive_audio_save_failed" -> R.string.error_receive_audio_save_failed
            "receive_audio_context_changed" -> R.string.error_receive_audio_context_changed
            "channel_subscribe_failed" -> R.string.error_channel_subscribe_failed
            "message_unconfirmed_after_restart" -> R.string.error_message_unconfirmed_after_restart
            "message_unconfirmed_after_reconnect" -> R.string.error_message_unconfirmed_after_reconnect
            "connection_recovering" -> R.string.error_connection_recovering
            "client_state_unavailable" -> R.string.error_client_state_unavailable
            "join_channel_failed" -> R.string.error_join_channel_failed
            "update_audio_state_failed" -> R.string.error_update_audio_state_failed
            "chat_history_save_failed" -> R.string.error_chat_history_save_failed
            "legacy_codec" -> R.string.error_legacy_codec
            "disconnect_before_connect" -> R.string.error_disconnect_before_connect
            "storage_change_while_connected" -> R.string.error_storage_change_while_connected
            "message_send_failed" -> R.string.error_message_send_failed
            "message_wrong_channel" -> R.string.error_message_wrong_channel
            "channel_unavailable" -> R.string.error_channel_unavailable
            "user_offline" -> R.string.error_user_offline
            "user_identity_unavailable" -> R.string.error_user_identity_unavailable
            "user_connection_changed" -> R.string.error_user_connection_changed
            "unsupported_message_target" -> R.string.error_unsupported_message_target
            else -> R.string.error_generic
        }
    val safeDetail = detail ?: code?.takeIf { resource == R.string.error_generic && it != "core_error" }
    return LocalizedMessage(resource, safeDetail)
}

internal fun Context.localizedCoreError(code: String?, detail: String? = null): String =
    coreErrorSource(code, detail).render(languageContext())
