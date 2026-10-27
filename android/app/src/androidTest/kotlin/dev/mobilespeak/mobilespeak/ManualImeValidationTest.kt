package dev.mobilespeak.mobilespeak

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Opt-in host for manual, real-keyboard checks; a completed test is not an IME or delivery assertion. */
class ManualImeValidationTest {
    @Test fun actualChatEditorRemainsAvailableForManualImeInput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual fixture is opt-in", arguments.getString("manualIme") == "1")
        val context = instrumentation.targetContext
        val marker = File(context.getExternalFilesDir(null), "manual-ime-chat.request")
        assumeTrue("Create the manual-ime-chat.request marker before launching", marker.isFile)
        ClientSession.initialize(context)
        assumeTrue("Never replace or send through a real connection", !ClientSession.shouldRunService() &&
            ClientSession.state.value.snapshot.status == "disconnected")
        val language = AppLanguage.fromCode(arguments.getString("manualImeLanguage") ?: "ja")
        assumeTrue("Use the Japanese or Korean localization", language in listOf(AppLanguage.JAPANESE, AppLanguage.KOREAN))
        val duration = arguments.getString("manualImeSeconds")?.toLongOrNull()?.coerceIn(15, 3600) ?: 600
        val log = File(context.getExternalFilesDir(null), "manual-ime-chat.log")
        fun record(event: String, text: String? = null) {
            val row = JSONObject().put("elapsedRealtime", SystemClock.elapsedRealtime()).put("event", event)
                .put("language", language.code).put("coreStatus", ClientSession.state.value.snapshot.status)
            text?.let { row.put("visibleEditorText", it).put("utf8Bytes", it.toByteArray(Charsets.UTF_8).size) }
            log.appendText("$row\n")
        }
        val finished = AtomicBoolean(false)
        val channel = Channel(9001, 0, 0, "Offline IME fixture / 日本語 / 한국어", false, true, "manual-ime", null)
        val own = Member(9001, channel.id, "manual-ime", "Local fixture", null, emptyList(), emptyList(), null, true, false, false)
        val fixture = SessionUiState(snapshot = Snapshot(status = "connected", ownClient = own.id,
            channels = listOf(channel), clients = listOf(own)))
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val localized = context.contextForLanguage(language)
                scenario.onActivity { activity ->
                    activity.setContent {
                        CompositionLocalProvider(LocalContext provides localized,
                            LocalConfiguration provides localized.resources.configuration, LocalResources provides localized.resources) {
                            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent,
                                background = Palette.background, surface = Palette.card, onSurface = Palette.text)) {
                                ChatScreen(ChatTarget(channel.conversation, channel.name, channel = channel), fixture, active = true) {
                                    finished.set(true)
                                }
                            }
                        }
                    }
                }
                record("READY: use real Gboard keys; remove marker or press Back to finish; no server is connected")
                val deadline = SystemClock.elapsedRealtime() + duration * 1000
                var previous: String? = null
                while (marker.isFile && !finished.get() && SystemClock.elapsedRealtime() < deadline) {
                    var visible: String? = null
                    scenario.onActivity { activity ->
                        fun root(view: View): androidx.compose.ui.node.RootForTest? = (view as? androidx.compose.ui.node.RootForTest)
                            ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
                        fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
                        visible = root(activity.window.decorView)?.let { collect(it.semanticsOwner.unmergedRootSemanticsNode) }
                            ?.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }
                    }
                    if (visible != null && visible != previous) {
                        record("EDITOR_TEXT (may include live composition)", visible)
                        if (visible!!.isEmpty() && !previous.isNullOrEmpty()) {
                            record("EDITOR_CLEARED_AFTER_NONEMPTY_INPUT: correlate with the manual Send tap; this is not delivery evidence", previous)
                        }
                        previous = visible
                    }
                    assertEquals("The real core must stay offline", "disconnected", ClientSession.state.value.snapshot.status)
                    SystemClock.sleep(200)
                }
            }
            record("FINISHED: manual observations and screenshots must be reviewed separately")
        } finally {
            marker.delete()
        }
    }
}
