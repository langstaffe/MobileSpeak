package dev.mobilespeak.mobilespeak

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class FileCacheState(val bytes: Long? = null, val status: String = "idle", val busy: Boolean = false, val error: String? = null, val items: Long = 0) {
    val working get() = status == "loading" || status == "clearing"
    val canClear get() = !busy && !working && bytes != null && (bytes > 0L || items > 0L)
}
internal fun JSONObject.fileCache() = FileCacheState(
    if (isNull("bytes")) null else getLong("bytes"), getString("status"), getBoolean("busy"), stringOrNull("error"), getLong("items"),
)

@Composable internal fun FileCacheSettings(ui: SessionUiState) {
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(ui.fileCache.busy, ui.fileImporting) {
        if (!ui.fileCache.busy && !ui.fileImporting) ClientSession.refreshFileCache()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.files_cache_storage), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)
            .clickable(role = Role.Button, enabled = ui.fileCache.canClear && !ui.fileImporting && !ui.fileCachePending) { confirming = true }
            .testTag("clear-file-cache").padding(14.dp).heightIn(min = 48.dp),
            verticalArrangement = Arrangement.Center) {
            if (LocalDensity.current.fontScale > 1.4f) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { FileCacheLabel(ui); FileCacheSize(ui) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FileCacheLabel(ui, Modifier.weight(1f)); FileCacheSize(ui)
            }
        }
        ui.fileCacheMessage?.let { Text(it, Modifier.testTag("file-cache-result"), color = if (ui.fileCache.error == null) Palette.muted else Palette.disconnect, style = MaterialTheme.typography.bodySmall) }
        if (ui.fileCache.status == "failed" || (ui.fileCache.bytes == null && !ui.fileCache.working)) {
            TextButton(onClick = ClientSession::refreshFileCache) { Text(stringResource(R.string.files_cache_reload)) }
        }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text(stringResource(R.string.files_cache_confirm_title)) },
        text = { Text(stringResource(R.string.files_cache_confirm_message)) },
        confirmButton = { TextButton(enabled = ui.fileCache.canClear && !ui.fileImporting && !ui.fileCachePending, onClick = { confirming = false; ClientSession.clearFileCache() }) { Text(stringResource(R.string.files_cache_confirm_action), color = Palette.disconnect) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = Palette.card, titleContentColor = Palette.text, textContentColor = Palette.text)
}
@Composable private fun FileCacheLabel(ui: SessionUiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.files_cache_clear), color = Palette.text)
        Text(stringResource(if (ui.fileImporting || ui.fileCache.busy) R.string.files_cache_waiting else R.string.files_cache_subtitle),
            color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun FileCacheSize(ui: SessionUiState) {
    val context = LocalContext.current
    if (ui.fileCache.working || ui.fileCachePending) CircularProgressIndicator(Modifier.size(20.dp))
    else Text(ui.fileCache.bytes?.let(context::localizedFileSize) ?: "—", color = Palette.muted)
}
data class ChannelFileEntry(val name: String, val size: Long, val timestamp: Long, val directory: Boolean, val icon: String, val localPath: String?)
data class ChannelFileTransfer(val id: Long, val server: String, val channel: Long, val path: String, val name: String,
    val upload: Boolean, val size: Long, val transferred: Long, val status: String, val error: String?, val localPath: String?)
data class ChannelFilesState(val open: Boolean = false, val server: String? = null, val channel: Long? = null,
    val channelName: String? = null, val path: String = "/", val sort: String = "name", val status: String = "idle",
    val error: String? = null, val entries: List<ChannelFileEntry> = emptyList(), val transfers: List<ChannelFileTransfer> = emptyList()) {
    fun transfer(name: String) = transfers.lastOrNull { it.path == path && it.name == name }
}
data class ChannelFileTarget(val server: String, val channel: Long, val path: String) {
    fun command() = JSONObject().put("server", server).put("channel", channel).put("path", path).put("directory", path)
}
internal fun JSONObject.channelFiles() = ChannelFilesState(
    getBoolean("open"), stringOrNull("server"), if (isNull("channel")) null else getLong("channel"), stringOrNull("channelName"),
    getString("path"), getString("sort"), getString("status"), stringOrNull("error"),
    getJSONArray("entries").objects().map { ChannelFileEntry(it.getString("name"), it.getLong("size"), it.getLong("timestamp"), it.getBoolean("directory"), it.getString("icon"), it.stringOrNull("localPath")) },
    getJSONArray("transfers").objects().map { ChannelFileTransfer(it.getLong("id"), it.getString("server"), it.getLong("channel"), it.getString("path"), it.getString("name"), it.getBoolean("upload"), it.getLong("size"), it.getLong("transferred"), it.getString("status"), it.stringOrNull("error"), it.stringOrNull("localPath")) },
)

@Composable private fun fileIcon(name: String): ImageVector = when(name) {
    "folder" -> UiIcons.Folder
    "file-text" -> UiIcons.FileText
    "file-image" -> UiIcons.FileImage
    "file-audio" -> UiIcons.FileAudio
    "file-archive" -> UiIcons.FileArchive
    else -> UiIcons.File
}
internal fun fileErrorSource(value: String): LocalizedMessage? = when (value) {
    "files_cache_busy" -> LocalizedMessage(R.string.files_cache_busy)
    "files_permission_denied" -> LocalizedMessage(R.string.files_permission_denied)
    "files_invalid_offset" -> LocalizedMessage(R.string.files_invalid_offset)
    "files_unexpected_response" -> LocalizedMessage(R.string.files_unexpected_response)
    "files_disconnected" -> LocalizedMessage(R.string.files_disconnected)
    "files_channel_changed" -> LocalizedMessage(R.string.files_channel_changed)
    "files_timeout" -> LocalizedMessage(R.string.files_timeout)
    "files_invalid_path" -> LocalizedMessage(R.string.files_invalid_path)
    "files_storage_failed" -> LocalizedMessage(R.string.files_storage_failed)
    "files_load_first" -> LocalizedMessage(R.string.files_load_first)
    "files_busy" -> LocalizedMessage(R.string.files_busy)
    "files_changed" -> LocalizedMessage(R.string.files_changed)
    "files_incomplete" -> LocalizedMessage(R.string.files_incomplete)
    "files_exists" -> LocalizedMessage(R.string.files_exists)
    else -> null
}
internal fun android.content.Context.fileError(value: String): String =
    fileErrorSource(value)?.render(languageContext()) ?: value

@Composable private fun DrawerPageHeader(title: String, subtitle: String, onBack: () -> Unit,
                                        onUpload: (() -> Unit)? = null, canUpload: Boolean = false) {
    val density = LocalDensity.current
    val stacked = density.fontScale >= 1.5f
    val titleSize = minOf(17f * density.fontScale, 22f)
    val subtitleSize = minOf(12f * density.fontScale, 16f)
    val actionSize = minOf(14f * density.fontScale, 20f)
    val actionStyle = androidx.compose.ui.text.TextStyle(fontSize = actionSize.sp, lineHeight = (actionSize * 17f / 14f).sp,
        fontWeight = FontWeight.Normal, fontFamily = FontFamily.SansSerif, letterSpacing = 0.sp)
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f),
        LocalTextStyle provides actionStyle) {
        Layout(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("drawer-page-header"), content = {
            PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                TextButton(interactionSource = source, modifier = feedback.widthIn(min = 64.dp).testTag("drawer-page-back"),
                    onClick = onBack, contentPadding = PaddingValues(2.dp)) {
                    Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp).scale(-1f, 1f))
                    Spacer(Modifier.width(2.dp))
                    Text(stringResource(R.string.action_back), style = actionStyle, maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, Modifier.fillMaxWidth().testTag("drawer-page-title"), color = Palette.text, fontSize = titleSize.sp,
                    lineHeight = (titleSize * 20f / 17f).sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp, textAlign = TextAlign.Center)
                Text(subtitle, Modifier.fillMaxWidth().testTag("drawer-page-subtitle"), color = Palette.muted, fontSize = subtitleSize.sp,
                    lineHeight = (subtitleSize * 14f / 12f).sp, fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            if (onUpload == null) Spacer(Modifier.size(64.dp, 44.dp))
            else PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                TextButton(interactionSource = source, modifier = feedback.widthIn(min = 64.dp).testTag("files-upload"),
                    onClick = onUpload, enabled = canUpload, contentPadding = PaddingValues(4.dp)) {
                    Icon(UiIcons.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.files_upload), style = actionStyle, maxLines = 1)
                }
            }
        }) { children, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val back = children[0].measure(loose)
            val upload = children[2].measure(loose)
            val gap = 4.dp.roundToPx()
            val leading = if (onUpload == null) maxOf(back.width, upload.width) else back.width
            val trailing = if (onUpload == null) leading else upload.width
            val titleWidth = if (stacked) constraints.maxWidth else (constraints.maxWidth - leading - trailing - 2 * gap).coerceAtLeast(0)
            val heading = children[1].measure(loose.copy(minWidth = titleWidth, maxWidth = titleWidth))
            val actionsHeight = maxOf(back.height, upload.height)
            val height = if (stacked) actionsHeight + gap + heading.height else maxOf(actionsHeight, heading.height)
            val rowHeight = if (stacked) actionsHeight else height
            layout(constraints.maxWidth, height) {
                back.placeRelative(0, (rowHeight - back.height) / 2)
                heading.placeRelative(if (stacked) 0 else leading + gap, if (stacked) actionsHeight + gap else (height - heading.height) / 2)
                upload.placeRelative(constraints.maxWidth - upload.width, (rowHeight - upload.height) / 2)
            }
        }
    }
}

private enum class DrawerPage { Home, Volumes, Files }

@Composable internal fun ChannelFileTools(ui: SessionUiState, fileHeaderModifier: Modifier = Modifier) {
    val page = when {
        ui.snapshot.status != "connected" -> DrawerPage.Home
        ui.userVolumesOpen -> DrawerPage.Volumes
        ui.channelFiles.open -> DrawerPage.Files
        else -> DrawerPage.Home
    }
    val navigation = updateTransition(page, label = "Drawer navigation")
    val transitioning = navigation.isRunning || navigation.currentState != page
    val homeScroll = rememberScrollState()
    var networkHeight by remember { mutableIntStateOf(0) }
    BackHandler(enabled = transitioning) { /* A second back must not collapse the drawer during a pop. */ }
    navigation.AnimatedContent(
        modifier = Modifier.fillMaxSize().clipToBounds().background(Palette.bottom)
            .pointerInput(transitioning) {
                if (transitioning) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }.onPreviewKeyEvent { transitioning },
        transitionSpec = { detailNavigationTransition(targetState != DrawerPage.Home).using(null) },
    ) { target ->
        val active = target == page
        // The core clears files immediately on root back; only the departing UI keeps its last frame.
        var retained by remember { mutableStateOf(ui) }
        if (active) retained = ui
        val contentUi = if (active) ui else retained
        val header = if (active) fileHeaderModifier else Modifier
        Box(Modifier.fillMaxSize().background(Palette.bottom).testTag("drawer-page-${target.name.lowercase()}").then(
            if (!active || transitioning) Modifier.clearAndSetSemantics {} else Modifier,
        )) {
            when (target) {
                DrawerPage.Volumes -> UserVolumesDrawer(contentUi, header, active)
                DrawerPage.Files -> ChannelFilesDrawer(contentUi, header, active)
                DrawerPage.Home -> {
                    // Small drawers scroll the whole body so long network labels cannot hide the tools.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val toolsHeight = (maxHeight - with(density) { networkHeight.toDp() }).coerceAtLeast(0.dp)
                        val scrollAll = toolsHeight < 96.dp
                        val body = if (scrollAll) Modifier.height(maxHeight).verticalScroll(homeScroll).testTag("drawer-home-scroll")
                            else Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)
                        Column(body) {
                            if (scrollAll) Spacer(header.fillMaxWidth().height(0.dp))
                            NetworkQualityPanel(contentUi.snapshot.networkQuality ?: NetworkQuality(),
                                (if (scrollAll) Modifier else header).onSizeChanged { networkHeight = it.height })
                            CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.4f))) {
                                Column((if (scrollAll) Modifier else Modifier.height(toolsHeight).verticalScroll(homeScroll))
                                    .testTag("drawer-tools-list").padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(stringResource(R.string.volume_tools), color = Palette.muted, fontSize = 12.sp, lineHeight = 14.sp,
                                        fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
                                    Row(Modifier.fillMaxWidth()
                                        .clickable(interactionSource = null, indication = RowPressFeedback, enabled = ui.snapshot.status == "connected" && ui.snapshot.ownClient != null) { if (active && !transitioning) ClientSession.openUserVolumes() }
                                        .background(Palette.card, RoundedCornerShape(14.dp)).testTag("user-volumes-tool")
                                        .heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Icon(UiIcons.Speaker, null, Modifier.size(26.dp), tint = Palette.accent)
                                        Text(stringResource(R.string.volume_title), Modifier.weight(1f), color = Palette.text)
                                        Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Palette.muted)
                                    }
                                    Text(stringResource(R.string.files_tools), color = Palette.muted, fontSize = 12.sp, lineHeight = 14.sp,
                                        fontWeight = FontWeight.Normal, letterSpacing = 0.sp)
                                    Row(Modifier.fillMaxWidth()
                                        .clickable(interactionSource = null, indication = RowPressFeedback, enabled = ui.snapshot.status == "connected" && ui.snapshot.ownClient != null) { if (active && !transitioning) ClientSession.openChannelFiles() }
                                        .background(Palette.card, RoundedCornerShape(14.dp))
                                        .testTag("channel-files-tool").heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Icon(UiIcons.Folder, null, Modifier.size(26.dp).testTag("channel-files-tool-icon"), tint = Palette.accent)
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(stringResource(R.string.files_title), color = Palette.text)
                                            Text(stringResource(R.string.files_subtitle), color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Palette.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable internal fun ChannelFilesDrawer(ui: SessionUiState, headerModifier: Modifier = Modifier, active: Boolean = true) {
    val files = ui.channelFiles
    val context = LocalContext.current
    val target = if (files.server != null && files.channel != null) ChannelFileTarget(files.server, files.channel, files.path) else null
    val canRefresh = target != null && files.status != "loading" && ui.snapshot.status == "connected"
    fun command(type: String, fields: JSONObject = JSONObject()) {
        if (!active) return
        val bound = target?.command() ?: JSONObject()
        fields.keys().forEach { key -> bound.put(key, fields.get(key)) }
        ClientSession.fileCommand(type, bound)
    }
    val scope = rememberCoroutineScope()
    var info by remember(files.server, files.channel, files.path, files.open) { mutableStateOf<ChannelFileEntry?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var uploadTarget by remember { mutableStateOf<ChannelFileTarget?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = uploadTarget
        if (uri != null && target != null) scope.launch { preparing = true; ClientSession.uploadChannelFile(uri, target); preparing = false }
    }
    val upload = {
        if (active) files.server?.let { server -> files.channel?.let { channel ->
            uploadTarget = ChannelFileTarget(server, channel, files.path); picker.launch(arrayOf("*/*"))
        } }
        Unit
    }
    val back: () -> Unit = { if (active) { if (info != null) info = null else command("files_back") } }
    BackHandler(enabled = active) { back() }
    Column(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Keep the measured drag boundary inside the unbounded header, and the lazy list bounded.
        Column(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).then(headerModifier).testTag("channel-files-header")) {
            DrawerPageHeader(stringResource(R.string.files_title), files.channelName.orEmpty(), back, upload,
                files.status == "ready" && !preparing && ui.snapshot.status == "connected")
            CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.4f))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(UiIcons.Folder, null, Modifier.size(18.dp), tint = Palette.muted)
                    Text(files.path, Modifier.weight(1f).padding(horizontal = 6.dp), color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                        TextButton(interactionSource = source, onClick = { command("files_list") }, enabled = canRefresh,
                            modifier = Modifier.heightIn(min = 48.dp).testTag("files-refresh").then(feedback),
                            colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent), contentPadding = PaddingValues(8.dp)) {
                            Icon(UiIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.files_reload), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Box {
                        PressFeedback(null) { source, _, _ ->
                            TextButton(interactionSource = source, onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("files-sort"),
                                colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent), contentPadding = PaddingValues(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                Icon(UiIcons.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                Text(stringResource(if (files.sort == "newest") R.string.files_sort_newest else R.string.files_sort_name), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        DropdownMenu(expanded = sortMenu && active, onDismissRequest = { sortMenu = false }) {
                            for ((key, newest) in listOf(R.string.files_sort_name to false, R.string.files_sort_newest to true)) {
                                DropdownMenuItem(text = { Text(stringResource(key)) }, onClick = { sortMenu = false; command("files_sort", JSONObject().put("newest", newest)) })
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = Palette.border)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("channel-files-list")) {
            if (preparing) item { Text(stringResource(R.string.files_staging), Modifier.padding(16.dp), color = Palette.muted) }
            (ui.channelFilesLocalError ?: files.error?.let(context::fileError))?.let { error ->
                item { Text(error, Modifier.padding(16.dp), color = Palette.disconnect) }
            }
            val selected = info
            if (selected != null) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(fileIcon(selected.icon), null, Modifier.size(28.dp), tint = Palette.muted)
                    Text(selected.name, color = Palette.text, style = MaterialTheme.typography.titleMedium)
                    FileMetadata(R.string.files_type, selected.name.substringAfterLast('.', "").takeIf { selected.name.lastIndexOf('.') > 0 && it.isNotEmpty() }?.uppercase() ?: context.localized(R.string.files_file))
                    FileMetadata(R.string.files_size, context.localizedFileSize(selected.size))
                    FileMetadata(R.string.files_time, context.localizedFileDate(selected.timestamp))
                    FileMetadata(R.string.files_location, files.path)
                    TextButton(onClick = { if (active) target?.let { ClientSession.downloadChannelFile(selected.name, it) } }, enabled = files.transfer(selected.name)?.status != "running") {
                        Icon(UiIcons.Download, null, Modifier.size(20.dp)); Text(stringResource(R.string.files_download))
                    }
                    val task = files.transfer(selected.name)
                    if (task != null) FileTransferStatus(task, active) else files.entries.firstOrNull { it.name == selected.name }?.localPath?.let { DownloadedFile(it, active) }
                }
            } else {
                items(files.transfers.filter { it.path == files.path && it.upload && (files.status != "ready" || files.entries.none { e -> e.name == it.name }) }, key = { "transfer:${it.id}" }) { task ->
                    Column(Modifier.padding(14.dp)) { Text(task.name, color = Palette.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis); FileTransferStatus(task, active) }
                }
                when(files.status) {
                    "loading" -> item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(stringResource(R.string.files_loading), color = Palette.text)
                    } }
                    "failed" -> item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(UiIcons.Error, null, Modifier.size(32.dp), tint = Palette.disconnect)
                        Text(stringResource(R.string.files_load_failed), color = Palette.text)
                        TextButton(onClick = { command("files_list") }, enabled = canRefresh, modifier = Modifier.testTag("files-refresh-retry")) { Text(stringResource(R.string.files_reload)) }
                    } }
                    "ready" -> if (files.entries.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(UiIcons.Folder, null, Modifier.size(40.dp), tint = Palette.muted)
                        Text(stringResource(R.string.files_empty), Modifier.padding(8.dp), color = Palette.muted)
                    } }
                }
                items(files.entries, key = { "entry:${it.name}" }) { entry ->
                    var menu by remember { mutableStateOf(false) }
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable(interactionSource = null, indication = RowPressFeedback) {
                                if (entry.directory) command("files_list", JSONObject().put("path", if (files.path == "/") "/${entry.name}" else "${files.path}/${entry.name}")) else info = entry
                            }.heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(fileIcon(entry.icon), null, Modifier.size(24.dp), tint = Palette.muted)
                                Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    ChannelFileName(entry.name)
                                    Text("${context.localizedFileSize(entry.size)} · ${context.localizedFileDate(entry.timestamp)}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (entry.directory) Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Palette.muted)
                            }
                            if (!entry.directory) Box {
                                PressFeedback(null) { source, _, _ ->
                                    IconButton(interactionSource = source, onClick = { menu = true }) { Icon(UiIcons.More, stringResource(R.string.files_info) + ": " + entry.name, tint = Palette.text) }
                                }
                                DropdownMenu(menu && active, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.files_download)) }, leadingIcon = { Icon(UiIcons.Download, null) }, onClick = { menu = false; if (active) target?.let { ClientSession.downloadChannelFile(entry.name, it) } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.files_info)) }, leadingIcon = { Icon(UiIcons.Info, null) }, onClick = { menu = false; info = entry })
                                }
                            }
                        }
                        val task = files.transfer(entry.name)
                        Column(Modifier.padding(horizontal = 14.dp)) { if (task != null) FileTransferStatus(task, active) else entry.localPath?.let { DownloadedFile(it, active) } }
                        HorizontalDivider(color = Palette.border)
                    }
                }
            }
        }
    }
}
@Composable private fun ChannelFileName(name: String) {
    val dot = name.lastIndexOf('.')
    if (dot > 0 && name.length - dot <= 9) Row(Modifier.clearAndSetSemantics { contentDescription = name }) {
        Text(name.substring(0, dot), Modifier.weight(1f, fill = false), color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(name.substring(dot), color = Palette.text, maxLines = 1)
    } else Text(name, color = Palette.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
}
@Composable private fun FileMetadata(key: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(stringResource(key), color = Palette.muted, style = MaterialTheme.typography.bodySmall); Text(value, color = Palette.text) }
}
@Composable private fun FileTransferStatus(task: ChannelFileTransfer, active: Boolean) {
    val context = LocalContext.current
    when(task.status) {
        "running" -> Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { if (task.size > 0) (task.transferred.toDouble()/task.size).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
            Text("${stringResource(if (task.upload) R.string.files_upload else R.string.files_download)} · ${context.localizedFileSize(task.transferred)} / ${context.localizedFileSize(task.size)}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        "failed" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(UiIcons.Error, null, Modifier.size(16.dp), tint = Palette.disconnect); Text(context.fileError(task.error ?: context.localized(R.string.files_failed)), Modifier.padding(4.dp), color = Palette.disconnect, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { if (active) ClientSession.fileCommand("files_retry", JSONObject().put("id", task.id)) }) { Text(stringResource(R.string.files_retry)) }
        }
        "complete" -> if (!task.upload && task.localPath != null) DownloadedFile(task.localPath, active) else Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(UiIcons.Check, null, Modifier.size(16.dp), tint = Palette.green); Text(stringResource(R.string.files_complete), Modifier.padding(4.dp), color = Palette.green, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun DownloadedFile(path: String, active: Boolean) {
    val context = LocalContext.current
    fun launch(share: Boolean) {
        if (!active) return
        runCatching {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(java.util.Locale.ROOT)) ?: "application/octet-stream"
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("", uri)
            context.startActivity(Intent.createChooser(intent, context.localized(if (share) R.string.files_share else R.string.files_open)))
        }.onFailure { ClientSession.fileLocalError(LocalizedMessage(R.string.files_choose_failed)) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(UiIcons.Check, null, Modifier.size(16.dp), tint = Palette.green)
        Text(stringResource(R.string.files_downloaded), Modifier.weight(1f).padding(4.dp), color = Palette.green, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { launch(false) }) { Text(stringResource(R.string.files_open)) }
        IconButton(onClick = { launch(true) }) { Icon(UiIcons.Share, stringResource(R.string.files_share), tint = Palette.accent) }
    }
}

@Composable internal fun UserVolumesDrawer(ui: SessionUiState, headerModifier: Modifier = Modifier, active: Boolean = true) {
    BackHandler(enabled = active) { ClientSession.closeUserVolumes() }
    Column(Modifier.fillMaxSize().testTag("user-volumes-page")) {
        Column(Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true).then(headerModifier)) {
            DrawerPageHeader(stringResource(R.string.volume_title), ui.snapshot.server.orEmpty(), { if (active) ClientSession.closeUserVolumes() })
            HorizontalDivider(color = Palette.border)
        }
        val members = ui.snapshot.clients.filter { it.id != ui.snapshot.ownClient }.sortedBy { it.id }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("user-volumes-list"), contentPadding = PaddingValues(top = if (members.isEmpty()) 0.dp else 12.dp)) {
            if (members.isEmpty()) item { Text(stringResource(R.string.volume_empty), Modifier.fillMaxWidth().padding(24.dp), color = Palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            items(members, key = { it.volumeToken ?: "client:${it.id}" }) { member ->
                UserVolumeRow(ui.snapshot, member, active)
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun UserVolumeRow(snapshot: Snapshot, member: Member, active: Boolean) {
    var value by remember(snapshot.serverId, member.volumeToken) { mutableFloatStateOf(member.volumePercent.toFloat()) }
    var editing by remember(snapshot.serverId, member.volumeToken) { mutableStateOf(false) }
    LaunchedEffect(member.volumePercent, editing) { if (!editing) value = member.volumePercent.toFloat() }
    val label = stringResource(R.string.volume_accessibility, member.name)
    val percent = localizedPercent(value.toInt(), androidx.compose.ui.platform.LocalResources.current.configuration.locales[0])
    val enabled = snapshot.status == "connected" && member.volumeToken != null
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                Avatar(member.name, member.avatarPath, member.speaking, 34, member, accessible = false)
            }
            val description = member.accessibilityLabel(LocalContext.current)
            Text(member.name, Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }, color = Palette.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(percent, color = Palette.muted, maxLines = 1)
        }
        Slider(value, onValueChange = { next ->
            if (active) {
                editing = true; value = kotlin.math.round(next)
                ClientSession.setUserVolume(snapshot.serverId.orEmpty(), member, value.toInt())
            }
        }, onValueChangeFinished = {
            if (active) ClientSession.setUserVolume(snapshot.serverId.orEmpty(), member, value.toInt())
            editing = false
        }, valueRange = 0f..300f, steps = 299, enabled = enabled, interactionSource = interactions,
            thumb = { Box(Modifier.size(36.dp, 24.dp).background(Color.White.copy(alpha = if (enabled) 1f else .38f), CircleShape)
                .then(if (focused && enabled) Modifier.border(2.dp, Palette.accent, CircleShape) else Modifier)) },
            track = { state ->
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    // Slider reserves half the thumb at each end; paint through that inset like UISlider.
                    val halfThumb = 18.dp.toPx()
                    val radius = size.height / 2
                    val left = Offset(-halfThumb + radius, radius)
                    val right = Offset(size.width + halfThumb - radius, radius)
                    drawLine(Color.White.copy(alpha = .12f), left, right, size.height, StrokeCap.Round)
                    val fraction = ((state.value - state.valueRange.start) / (state.valueRange.endInclusive - state.valueRange.start)).coerceIn(0f, 1f)
                    val end = Offset(size.width * if (rtl) 1f - fraction else fraction, radius)
                    drawLine(Palette.accent.copy(alpha = if (enabled) 1f else .38f), if (rtl) right else left, end, size.height, StrokeCap.Round)
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("user-volume-${member.id}").semantics { contentDescription = label; stateDescription = percent })
    }
}
