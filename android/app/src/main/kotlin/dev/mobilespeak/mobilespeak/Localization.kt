package dev.mobilespeak.mobilespeak

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

internal enum class AppLanguage(val code: String, val tag: String?) {
    SYSTEM("system", null),
    SIMPLIFIED_CHINESE("zh-Hans", "zh-Hans"),
    ENGLISH("en", "en");

    companion object {
        fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: SYSTEM

        fun selected(): AppLanguage {
            val locale = AppCompatDelegate.getApplicationLocales()[0] ?: return SYSTEM
            return when {
                locale.language == "zh" && (locale.script == "Hans" || locale.country in setOf("CN", "SG")) -> SIMPLIFIED_CHINESE
                locale.language == "en" -> ENGLISH
                else -> SYSTEM
            }
        }

        fun select(language: AppLanguage): Boolean {
            if (!shouldApply(selected(), language)) return false
            AppCompatDelegate.setApplicationLocales(
                language.tag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList(),
            )
            ClientSession.refreshLanguage()
            return true
        }

        fun resolve(selection: AppLanguage, systemLanguageTags: List<String>): AppLanguage {
            if (selection != SYSTEM) return selection
            val locale = systemLanguageTags.firstOrNull()?.let(Locale::forLanguageTag)
            return if (locale?.language == "zh" &&
                (locale.script == "Hans" || locale.country in setOf("CN", "SG"))
            ) SIMPLIFIED_CHINESE else ENGLISH
        }

        fun shouldApply(current: AppLanguage, requested: AppLanguage) = current != requested
    }
}

private fun Context.languageContext(): Context {
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return ContextCompat.getContextForLanguage(this)
    val configuration = android.content.res.Configuration(resources.configuration)
    ConfigurationCompat.setLocales(configuration, locales)
    return createConfigurationContext(configuration)
}

internal fun Context.localized(@StringRes id: Int, vararg arguments: Any): String =
    languageContext().getString(id, *arguments)

internal fun Context.localizedQuantity(@PluralsRes id: Int, quantity: Int, vararg arguments: Any): String =
    languageContext().resources.getQuantityString(id, quantity, *arguments)

internal fun Context.localizedCoreError(code: String?, detail: String? = null): String {
    val resource = when (code) {
            "chat_history_read_failed" -> R.string.error_chat_history_read_failed
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
    val message = localized(resource)
    val safeDetail = detail ?: code?.takeIf { resource == R.string.error_generic && it != "core_error" }
    return if (safeDetail.isNullOrBlank()) message else localized(R.string.error_with_detail, message, safeDetail)
}
