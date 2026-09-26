package dev.mobilespeak.mobilespeak

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarCropDialog(uri: String, selection: Long, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var x by rememberSaveable(uri) { mutableFloatStateOf(.5f) }
    var y by rememberSaveable(uri) { mutableFloatStateOf(.5f) }
    var zoom by rememberSaveable(uri) { mutableFloatStateOf(1f) }
    val loaded by produceState<Result<android.graphics.Bitmap>?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { AvatarImages.load(context, Uri.parse(uri)) } }
    }
    val save by ClientSession.avatarSave.collectAsStateWithLifecycle()
    val saving = save?.let { it.first == selection && it.second == null } == true
    val failed = loaded?.isFailure == true || save?.let { it.first == selection && it.second == false } == true
    LaunchedEffect(save, selection) {
        if (!ClientSession.isCurrentAvatarSelection(selection) || save?.let { it.first == selection && it.second == true } == true) onDismiss()
    }
    val image = loaded?.getOrNull()
    fun adjust(viewport: Float, panX: Float = 0f, panY: Float = 0f, factor: Float = 1f, focus: Offset = Offset(viewport / 2, viewport / 2)) {
        if (image == null || saving) return
        val value = AvatarCrop(x, y, zoom).transform(image.width, image.height, viewport, panX, panY, factor, focus.x, focus.y)
        x = value.x; y = value.y; zoom = value.zoom
    }
    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !saving, dismissOnClickOutside = !saving,
    )) {
        Surface(Modifier.fillMaxSize(), color = Palette.background, contentColor = Palette.text) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                // Reserve the same measured width on both sides, including when labels wrap.
                Layout(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), content = {
                    OutlinedButton(onClick = onDismiss, enabled = !saving,
                        modifier = Modifier.heightIn(min = 44.dp), shape = CircleShape,
                        border = BorderStroke(1.dp, Palette.border), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text)) {
                        Text(stringResource(R.string.action_cancel), fontSize = 17.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
                    }
                    Text(stringResource(R.string.avatar_crop_title), fontSize = 17.sp, lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }) { measurables, constraints ->
                    val cancel = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = constraints.maxWidth / 3))
                    val title = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0,
                        maxWidth = (constraints.maxWidth - 2 * (cancel.width + 8.dp.roundToPx())).coerceAtLeast(0)))
                    val height = maxOf(cancel.height, title.height)
                    layout(constraints.maxWidth, height) {
                        cancel.placeRelative(0, (height - cancel.height) / 2)
                        title.placeRelative((constraints.maxWidth - title.width) / 2, (height - title.height) / 2)
                    }
                }
                BoxWithConstraints(Modifier.weight(1f)) {
                    val side = minOf(420.dp, maxWidth - 32.dp, maxHeight * .55f).coerceAtLeast(100.dp)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(stringResource(R.string.avatar_crop_hint), Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally), fontSize = 13.sp, lineHeight = 18.sp, color = Palette.muted)
                        val description = stringResource(R.string.avatar_crop_area)
                        val directions = listOf(Triple(R.string.avatar_crop_left, -.1f, 0f), Triple(R.string.avatar_crop_up, 0f, -.1f),
                            Triple(R.string.avatar_crop_down, 0f, .1f), Triple(R.string.avatar_crop_right, .1f, 0f))
                            .map { (label, dx, dy) -> CustomAccessibilityAction(context.localized(label)) {
                                if (saving) false else { adjust(1f, -dx, -dy); true }
                            } }
                        if (image != null) {
                            val bitmap = remember(image) { image.asImageBitmap() }
                            // Draw the identical integer pixel square used by the JPEG exporter.
                            Canvas(Modifier.size(side).align(androidx.compose.ui.Alignment.CenterHorizontally)
                                .semantics { contentDescription = description; customActions = if (saving) emptyList() else directions }
                                .pointerInput(image, saving) {
                                    detectTransformGestures { focus, pan, factor, _ -> adjust(size.width.toFloat(), pan.x, pan.y, factor, focus) }
                                }) {
                                    val rect = AvatarCrop(x, y, zoom).rect(image.width, image.height)
                                    drawRect(Color.White) // Same background as the existing JPEG encoder.
                                drawImage(bitmap, srcOffset = IntOffset(rect.left, rect.top), srcSize = IntSize(rect.width(), rect.height()),
                                    dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                                val shade = Path().apply {
                                    fillType = PathFillType.EvenOdd
                                    addRect(Rect(Offset.Zero, size)); addOval(Rect(Offset.Zero, size))
                                }
                                drawPath(shade, Color.Black.copy(alpha = .35f))
                                drawRect(Color.White.copy(alpha = .8f), style = Stroke(1.dp.toPx()))
                                drawCircle(Color.White, style = Stroke(2.dp.toPx()))
                            }
                            Text(stringResource(R.string.avatar_crop_zoom), fontSize = 17.sp, lineHeight = 22.sp)
                            Slider(value = zoom, onValueChange = { adjust(1f, factor = it / zoom) }, valueRange = 1f..4f,
                                enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .semantics { contentDescription = context.localized(R.string.avatar_crop_zoom) },
                                thumb = { Box(Modifier.size(width = 36.dp, height = 24.dp)
                                    .background(Color.White.copy(alpha = if (saving) .38f else 1f), CircleShape)) },
                                track = { state ->
                                    SliderDefaults.Track(sliderState = state, modifier = Modifier.height(6.dp), enabled = !saving,
                                        colors = SliderDefaults.colors(activeTrackColor = Palette.accent,
                                            inactiveTrackColor = Color.White.copy(alpha = .12f)),
                                        drawStopIndicator = null, thumbTrackGapSize = 0.dp, trackInsideCornerSize = 3.dp)
                                })
                        } else if (loaded == null) CircularProgressIndicator()
                        if (failed) Text(stringResource(R.string.avatar_crop_error), color = Palette.disconnect)
                        Button(onClick = { if (image != null) ClientSession.saveAvatar(image, AvatarCrop(x, y, zoom), selection) },
                            enabled = image != null && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                            shape = CircleShape, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.text)) {
                            if (saving) CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp))
                            Text(stringResource(if (saving) R.string.avatar_crop_saving else R.string.avatar_crop_use), fontSize = 17.sp, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
