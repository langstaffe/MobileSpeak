package dev.mobilespeak.mobilespeak

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One physical press owns one core token; leaving the hit rectangle ends it permanently.
internal class PushToTalkHold(private val submit: (Boolean, String) -> Unit) {
    private var token: String? = null
    fun down(value: String, enabled: Boolean): Boolean {
        if (!enabled || value.isEmpty() || token != null) return false
        token = value
        submit(true, value)
        return true
    }
    fun stop() {
        val value = token ?: return
        token = null
        submit(false, value)
    }
}

@Composable
internal fun PushToTalkBar(
    ui: SessionUiState,
    requestPermission: () -> Unit,
    active: Boolean = true,
    showTarget: Boolean = false,
    onTransmit: (Boolean, String) -> Unit = ClientSession::pushToTalk,
) {
    if (ui.speech.mode != "push_to_talk") return
    Column(Modifier.fillMaxWidth().background(Palette.bottom).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showTarget) {
            val own = ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }
            val channel = ui.snapshot.channels.firstOrNull { it.id == own?.channel }?.name
            Text(if (ui.snapshot.status == "connected" && channel != null) stringResource(R.string.voice_target_channel, channel)
                else stringResource(R.string.voice_target_unavailable), fontSize = 13.sp, lineHeight = 16.sp,
                color = Palette.muted, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
        }
        PushToTalkButton(ui, active, onTransmit)
        if (!ui.microphonePermission && ui.snapshot.status == "connected" && !ui.microphoneMuted && !ui.deafened) TextButton(enabled = active, onClick = requestPermission,
            modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text(stringResource(R.string.voice_grant_microphone), fontSize = 13.sp)
        }
    }
}

@Composable
internal fun PushToTalkButton(
    ui: SessionUiState,
    active: Boolean = true,
    onTransmit: (Boolean, String) -> Unit = ClientSession::pushToTalk,
) {
    val enabled = active && ui.speech.available && ui.microphonePermission && !ui.microphoneMuted && !ui.deafened && ui.audioProcessingStatus != "switching"
    val latestSubmit by rememberUpdatedState(onTransmit)
    val hold = remember { PushToTalkHold { pressed, token -> latestSubmit(pressed, token) } }
    var pressed by remember { mutableStateOf(false) }
    val stop = { hold.stop(); pressed = false }
    DisposableEffect(hold) { onDispose { hold.stop() } }
    val title = stringResource(if (ui.speech.transmitting) R.string.voice_transmitting else R.string.voice_hold_to_talk)
    val help = stringResource(R.string.voice_hold_help)
    // iOS native recording shows approximately 18% white brightening while pressed.
    // shortcut: 80/200ms press/recovery durations remain approximations; remeasure native timing when possible.
    val shade by animateFloatAsState(if (pressed) .18f else 0f,
        tween(if (pressed) 80 else 200), label = "Push to talk feedback")
    val background = if (!enabled) Color(0xFF424448) else if (ui.speech.transmitting) Palette.green else Palette.accent
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(14.dp)).background(background)
        .drawWithContent { drawContent(); if (shade > 0f) drawRect(Color.White.copy(alpha = shade)) }
        .testTag("push-to-talk")
        .clearAndSetSemantics {
            role = Role.Button
            contentDescription = title
            stateDescription = help
            if (!enabled) disabled()
            onClick(label = help) { false }
        }
        // Keep native disabled/focus/accessibility semantics; clicks never latch transmission.
        .clickable(enabled = enabled, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, role = Role.Button, onClickLabel = help, onClick = {})
        .onFocusChanged { if (!it.isFocused) stop() }
        .onPreviewKeyEvent { event ->
            if (event.key !in listOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)) false
            else {
                if (event.type == KeyEventType.KeyDown) {
                    if (event.nativeKeyEvent.repeatCount == 0 && hold.down(ui.speech.token, enabled)) pressed = true
                } else if (event.type == KeyEventType.KeyUp) stop()
                true
            }
        }
        .pointerInput(enabled, ui.speech.token) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true, pass = PointerEventPass.Initial)
                    if (hold.down(ui.speech.token, enabled)) {
                        pressed = true
                        down.consume()
                        var held = true
                        while (held) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed || change.isConsumed ||
                                change.position.x < 0f || change.position.y < 0f ||
                                change.position.x >= size.width || change.position.y >= size.height) {
                                stop()
                                held = false
                            }
                            change?.consume()
                        }
                    }
                }
            } finally { stop() }
        }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(UiIcons.Mic, null, Modifier.size(20.dp), tint = if (enabled) Color.White else Color(0xFFD0D0D1))
        Spacer(Modifier.width(10.dp))
        Text(title, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
            color = if (enabled) Color.White else Color(0xFFD0D0D1), textAlign = TextAlign.Center, modifier = Modifier.clearAndSetSemantics {})
    }
}
