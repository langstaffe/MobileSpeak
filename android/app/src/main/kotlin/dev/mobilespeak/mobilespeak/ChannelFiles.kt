package dev.mobilespeak.mobilespeak

import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

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
    else Text(ui.fileCache.bytes?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: "—", color = Palette.muted)
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
internal fun android.content.Context.fileError(value: String): String = when (value) {
    "files_cache_busy" -> localized(R.string.files_cache_busy)
    "files_permission_denied" -> localized(R.string.files_permission_denied)
    "files_invalid_offset" -> localized(R.string.files_invalid_offset)
    "files_unexpected_response" -> localized(R.string.files_unexpected_response)
    "files_disconnected" -> localized(R.string.files_disconnected)
    "files_channel_changed" -> localized(R.string.files_channel_changed)
    "files_timeout" -> localized(R.string.files_timeout)
    "files_invalid_path" -> localized(R.string.files_invalid_path)
    "files_storage_failed" -> localized(R.string.files_storage_failed)
    "files_load_first" -> localized(R.string.files_load_first)
    "files_busy" -> localized(R.string.files_busy)
    "files_changed" -> localized(R.string.files_changed)
    "files_incomplete" -> localized(R.string.files_incomplete)
    "files_exists" -> localized(R.string.files_exists)
    else -> value
}
private fun fileDate(timestamp: Long, context: android.content.Context) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, context.resources.configuration.locales[0]).format(Date(timestamp * 1000))

@Composable internal fun ChannelFileTools(ui: SessionUiState) {
    if (ui.channelFiles.open) { ChannelFilesDrawer(ui); return }
    Column {
        NetworkQualityPanel(ui.snapshot.networkQuality ?: NetworkQuality(), Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true))
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.4f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.files_tools), color = Palette.muted, style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth().background(Palette.card, RoundedCornerShape(14.dp))
                    .clickable(enabled = ui.snapshot.status == "connected" && ui.snapshot.ownClient != null) { ClientSession.openChannelFiles() }
                    .heightIn(min = 72.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(UiIcons.Folder, null, Modifier.size(26.dp), tint = Palette.accent)
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
@Composable internal fun ChannelFilesDrawer(ui: SessionUiState) {
    val files = ui.channelFiles
    val context = LocalContext.current
    val target = if (files.server != null && files.channel != null) ChannelFileTarget(files.server, files.channel, files.path) else null
    val canRefresh = target != null && files.status != "loading" && ui.snapshot.status == "connected"
    fun command(type: String, fields: JSONObject = JSONObject()) {
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
        files.server?.let { server -> files.channel?.let { channel ->
            uploadTarget = ChannelFileTarget(server, channel, files.path); picker.launch(arrayOf("*/*"))
        } }
        Unit
    }
    val back: () -> Unit = { if (info != null) info = null else command("files_back") }
    BackHandler { back() }
    Column(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, minOf(density.fontScale, 1.4f))) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = back, contentPadding = PaddingValues(2.dp)) {
                    Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp).scale(-1f, 1f)); Text(stringResource(R.string.action_back))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.files_title), color = Palette.text, style = MaterialTheme.typography.titleMedium)
                    Text(files.channelName.orEmpty(), color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = upload, enabled = files.status == "ready" && !preparing && ui.snapshot.status == "connected", contentPadding = PaddingValues(4.dp)) {
                    Icon(UiIcons.Upload, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.files_upload))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.Folder, null, Modifier.size(18.dp), tint = Palette.muted)
                Text(files.path, Modifier.weight(1f).padding(horizontal = 6.dp), color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { command("files_list") }, enabled = canRefresh,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("files-refresh"),
                    colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent), contentPadding = PaddingValues(8.dp)) {
                    Icon(UiIcons.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.files_reload), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("files-sort"),
                        colors = ButtonDefaults.textButtonColors(contentColor = Palette.accent), contentPadding = PaddingValues(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
                        Icon(UiIcons.Sort, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (files.sort == "newest") R.string.files_sort_newest else R.string.files_sort_name), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        for ((key, newest) in listOf(R.string.files_sort_name to false, R.string.files_sort_newest to true)) {
                            DropdownMenuItem(text = { Text(stringResource(key)) }, onClick = { sortMenu = false; command("files_sort", JSONObject().put("newest", newest)) })
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.border)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (preparing) item { Text(stringResource(R.string.files_staging), Modifier.padding(16.dp), color = Palette.muted) }
            (ui.channelFilesLocalError ?: files.error)?.let { error -> item { Text(context.fileError(error), Modifier.padding(16.dp), color = Palette.disconnect) } }
            val selected = info
            if (selected != null) item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(fileIcon(selected.icon), null, Modifier.size(28.dp), tint = Palette.muted)
                    Text(selected.name, color = Palette.text, style = MaterialTheme.typography.titleMedium)
                    FileMetadata(R.string.files_type, selected.name.substringAfterLast('.', "").takeIf { selected.name.lastIndexOf('.') > 0 && it.isNotEmpty() }?.uppercase() ?: context.localized(R.string.files_file))
                    FileMetadata(R.string.files_size, android.text.format.Formatter.formatFileSize(context, selected.size))
                    FileMetadata(R.string.files_time, fileDate(selected.timestamp, context))
                    FileMetadata(R.string.files_location, files.path)
                    TextButton(onClick = { target?.let { ClientSession.downloadChannelFile(selected.name, it) } }, enabled = files.transfer(selected.name)?.status != "running") {
                        Icon(UiIcons.Download, null, Modifier.size(20.dp)); Text(stringResource(R.string.files_download))
                    }
                    val task = files.transfer(selected.name)
                    if (task != null) FileTransferStatus(task) else files.entries.firstOrNull { it.name == selected.name }?.localPath?.let { DownloadedFile(it) }
                }
            } else {
                items(files.transfers.filter { it.path == files.path && it.upload && (files.status != "ready" || files.entries.none { e -> e.name == it.name }) }, key = { "transfer:${it.id}" }) { task ->
                    Column(Modifier.padding(14.dp)) { Text(task.name, color = Palette.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis); FileTransferStatus(task) }
                }
                when(files.status) {
                    "loading" -> item { Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Text(stringResource(R.string.files_loading), Modifier.padding(start = 12.dp), color = Palette.muted) } }
                    "failed" -> item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(UiIcons.Error, null, Modifier.size(32.dp), tint = Palette.disconnect)
                        Text(stringResource(R.string.files_load_failed), color = Palette.text)
                        TextButton(onClick = { command("files_list") }, enabled = canRefresh, modifier = Modifier.testTag("files-refresh-retry")) { Text(stringResource(R.string.files_reload)) }
                    } }
                    "ready" -> if (files.entries.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(UiIcons.Folder, null, Modifier.size(40.dp), tint = Palette.muted)
                        Text(stringResource(R.string.files_empty), Modifier.padding(8.dp), color = Palette.muted)
                        TextButton(onClick = upload, enabled = !preparing) { Text(stringResource(R.string.files_upload)) }
                    } }
                }
                items(files.entries, key = { "entry:${it.name}" }) { entry ->
                    var menu by remember { mutableStateOf(false) }
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clickable {
                                if (entry.directory) command("files_list", JSONObject().put("path", if (files.path == "/") "/${entry.name}" else "${files.path}/${entry.name}")) else info = entry
                            }.heightIn(min = 68.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(fileIcon(entry.icon), null, Modifier.size(24.dp), tint = Palette.muted)
                                Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    ChannelFileName(entry.name)
                                    Text("${android.text.format.Formatter.formatFileSize(context, entry.size)} · ${fileDate(entry.timestamp, context)}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (entry.directory) Icon(UiIcons.ChevronRight, null, Modifier.size(18.dp), tint = Palette.muted)
                            }
                            if (!entry.directory) Box {
                                IconButton(onClick = { menu = true }) { Icon(UiIcons.More, stringResource(R.string.files_info) + ": " + entry.name, tint = Palette.text) }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.files_download)) }, leadingIcon = { Icon(UiIcons.Download, null) }, onClick = { menu = false; target?.let { ClientSession.downloadChannelFile(entry.name, it) } })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.files_info)) }, leadingIcon = { Icon(UiIcons.Info, null) }, onClick = { menu = false; info = entry })
                                }
                            }
                        }
                        val task = files.transfer(entry.name)
                        Column(Modifier.padding(horizontal = 14.dp)) { if (task != null) FileTransferStatus(task) else entry.localPath?.let { DownloadedFile(it) } }
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
@Composable private fun FileTransferStatus(task: ChannelFileTransfer) {
    val context = LocalContext.current
    when(task.status) {
        "running" -> Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(progress = { if (task.size > 0) (task.transferred.toDouble()/task.size).toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
            Text("${stringResource(if (task.upload) R.string.files_upload else R.string.files_download)} · ${android.text.format.Formatter.formatFileSize(context, task.transferred)} / ${android.text.format.Formatter.formatFileSize(context, task.size)}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        "failed" -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(UiIcons.Error, null, Modifier.size(16.dp), tint = Palette.disconnect); Text(context.fileError(task.error ?: context.localized(R.string.files_failed)), Modifier.padding(4.dp), color = Palette.disconnect, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { ClientSession.fileCommand("files_retry", JSONObject().put("id", task.id)) }) { Text(stringResource(R.string.files_retry)) }
        }
        "complete" -> if (!task.upload && task.localPath != null) DownloadedFile(task.localPath) else Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(UiIcons.Check, null, Modifier.size(16.dp), tint = Palette.green); Text(stringResource(R.string.files_complete), Modifier.padding(4.dp), color = Palette.green, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun DownloadedFile(path: String) {
    val context = LocalContext.current
    fun launch(share: Boolean) {
        runCatching {
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("", uri)
            context.startActivity(Intent.createChooser(intent, context.localized(if (share) R.string.files_share else R.string.files_open)))
        }.onFailure { ClientSession.fileLocalError(context.localized(R.string.files_choose_failed)) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(UiIcons.Check, null, Modifier.size(16.dp), tint = Palette.green)
        Text(stringResource(R.string.files_downloaded), Modifier.weight(1f).padding(4.dp), color = Palette.green, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { launch(false) }) { Text(stringResource(R.string.files_open)) }
        IconButton(onClick = { launch(true) }) { Icon(UiIcons.Share, stringResource(R.string.files_share), tint = Palette.accent) }
    }
}
