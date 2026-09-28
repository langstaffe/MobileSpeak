package dev.mobilespeak.mobilespeak

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun SettingsEntry(title: String, value: String? = null, onClickLabel: String = title, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)
        .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
        .semantics { if (value != null) stateDescription = value }
        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp)
        if (value != null) Text(value, Modifier.weight(1f), color = Palette.muted, fontSize = 17.sp, lineHeight = 22.sp, textAlign = TextAlign.End)
        Icon(UiIcons.ChevronRight, null, Modifier.size(16.dp), tint = Palette.muted)
    }
}

@Composable
internal fun SettingsDetailPage(page: String, onBack: () -> Unit, onModal: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val updates by AppUpdates.state.collectAsStateWithLifecycle()
    var installConfirmation by remember { mutableStateOf(false) }
    DisposableEffect(installConfirmation) {
        onModal(installConfirmation)
        onDispose { onModal(false) }
    }
    val title = stringResource(if (page == "language") R.string.settings_language else R.string.settings_about)
    Column(Modifier.fillMaxSize().background(Palette.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(44.dp)) {
                Icon(UiIcons.Back, stringResource(R.string.action_back), Modifier.size(24.dp), tint = Palette.text)
            }
            Text(title, Modifier.align(Alignment.Center).padding(horizontal = 52.dp, vertical = 16.dp), textAlign = TextAlign.Center,
                fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (page == "language") {
                val selected = AppLanguage.selected()
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                    AppLanguage.entries.forEachIndexed { index, option ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.border)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected == option, interactionSource = null, indication = null, role = Role.RadioButton) { AppLanguage.select(option) }
                            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(option.title), Modifier.weight(1f), fontSize = 17.sp, lineHeight = 22.sp)
                            if (selected == option) Icon(UiIcons.Check, null, Modifier.size(20.dp), tint = Palette.accent)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)
                    .clickable(interactionSource = null, indication = null, role = Role.Button, onClickLabel = stringResource(R.string.about_browser)) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/langstaffe/MobileSpeak".toUri())) }
                    }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.about_project))
                    Text("https://github.com/langstaffe/MobileSpeak", color = Palette.accent, fontSize = 15.sp, lineHeight = 20.sp)
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card).heightIn(min = 56.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.about_version), Modifier.weight(1f))
                    Text("v" + AppUpdates.installedVersion(), color = Palette.muted)
                }
                SettingsEntry(stringResource(if (updates.checking) R.string.update_checking else R.string.update_check)) { AppUpdates.check(manual = true) }
                updates.result?.let { result ->
                    Text(stringResource(result.message, result.version) + result.detail.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(), color = Palette.muted)
                    if (result.asset != null && UpdatePolicy.downloadAllowed(updates.download, updates.downloadVersion, result.version)) {
                        Button(onClick = { AppUpdates.download(result) }) { Text(stringResource(R.string.update_download)) }
                    }
                }
                when (updates.download) {
                    DownloadStatus.DOWNLOADING, DownloadStatus.VERIFYING -> Text(stringResource(if (updates.download == DownloadStatus.VERIFYING) R.string.update_verifying else R.string.update_downloading, updates.downloadVersion), color = Palette.muted)
                    DownloadStatus.FAILED -> Text(stringResource(updates.downloadError) + updates.detail.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(), color = Palette.disconnect)
                    DownloadStatus.READY -> {
                        Text(stringResource(R.string.update_downloaded, updates.downloadVersion), color = Palette.muted)
                        Button(enabled = !updates.installing, onClick = { installConfirmation = true }) { Text(stringResource(R.string.update_install)) }
                        Text(stringResource(R.string.update_install_permission), color = Palette.muted, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    DownloadStatus.NONE -> Unit
                }
            }
        }
    }
    if (installConfirmation) AlertDialog(onDismissRequest = { installConfirmation = false },
        title = { Text(stringResource(R.string.update_install)) }, text = { Text(stringResource(R.string.update_install_warning)) },
        confirmButton = { TextButton(onClick = { installConfirmation = false; AppUpdates.install(context) }) { Text(stringResource(R.string.update_install)) } },
        dismissButton = { TextButton(onClick = { installConfirmation = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
internal fun UpdatePrompt(suitable: Boolean, manualSuitable: Boolean) {
    val updates by AppUpdates.state.collectAsStateWithLifecycle()
    SideEffect { AppUpdates.suitable = suitable && !updates.installing }
    LaunchedEffect(suitable, manualSuitable, updates.installing, updates.prompt) {
        if (updates.prompt != null && (!suitable || updates.installing || (updates.manual && !manualSuitable))) AppUpdates.dismissPrompt()
    }
    val prompt = updates.prompt
    if (prompt != null && suitable && !updates.installing && (!updates.manual || manualSuitable)) {
        AlertDialog(onDismissRequest = AppUpdates::dismissPrompt,
            title = { Text(stringResource(R.string.update_available, prompt.version)) },
            text = { Text(stringResource(R.string.update_prompt_message)) },
            confirmButton = { TextButton(enabled = UpdatePolicy.downloadAllowed(updates.download, updates.downloadVersion, prompt.version), onClick = { AppUpdates.download(prompt) }) { Text(stringResource(R.string.update_download)) } },
            dismissButton = { TextButton(onClick = AppUpdates::dismissPrompt) { Text(stringResource(R.string.update_later)) } })
    }
}
