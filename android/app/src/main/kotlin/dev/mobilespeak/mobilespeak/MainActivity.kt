package dev.mobilespeak.mobilespeak

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        ClientSession.setMicrophonePermission(result[Manifest.permission.RECORD_AUDIO] == true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClientSession.initialize(applicationContext)
        ClientSession.setMicrophonePermission(
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Palette.background,
                    surface = Palette.card,
                    surfaceVariant = Palette.selected,
                    primary = Palette.accent,
                    secondary = Palette.green,
                    error = Palette.disconnect,
                    onBackground = Palette.text,
                    onPrimary = Palette.text,
                    onSurface = Palette.text,
                ),
            ) {
                Surface(Modifier.fillMaxSize(), color = Palette.background) {
                    ProvideTextStyle(androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif)) {
                        MobileSpeakApp(::requestVoicePermissions)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ClientSession.setAppActive(true)
    }

    override fun onStop() {
        ClientSession.setAppActive(false)
        super.onStop()
    }

    private fun requestVoicePermissions() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionRequest.launch(permissions.toTypedArray())
    }
}

private object Palette {
    val text = Color(0xFFF2F3F5)
    val background = Color(0xFF2B2D31)
    val bottom = Color(0xFF292B2F)
    val card = Color(0xFF36393F)
    val selected = Color(0xFF3A3D42)
    val border = Color(0xFF3A3E46)
    val muted = Color(0xFF949BA4)
    val accent = Color(0xFF5865F2)
    val green = Color(0xFF23A559)
    val disconnect = Color(0xFFC83F4A)
}

private data class ChatTarget(val conversation: String, val title: String, val channel: Channel? = null, val member: Member? = null)

@Composable
private fun MobileSpeakApp(requestPermissions: () -> Unit) {
    val ui by ClientSession.state.collectAsStateWithLifecycle()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var showNewBookmark by remember { mutableStateOf(false) }
    var deletingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var selectedChannel by remember { mutableStateOf<Channel?>(null) }
    var chat by androidx.compose.runtime.saveable.rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.listSaver<ChatTarget?, String>(
            save = { target -> target?.let { listOf(it.conversation, it.title) } ?: emptyList() },
            restore = { saved -> saved.firstOrNull()?.let { conversation ->
                ChatTarget(conversation, saved[1],
                    channel = ui.snapshot.channels.firstOrNull { it.conversation == conversation },
                    member = ui.snapshot.clients.firstOrNull { it.conversation == conversation })
            } },
        ),
    ) { mutableStateOf<ChatTarget?>(null) }

    val navigation = updateTransition(chat, label = "Chat navigation")
    val homeState = rememberSaveableStateHolder()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeChat = {
        if (chat != null) {
            focusManager.clearFocus()
            keyboard?.hide()
            chat = null
        }
    }

    BackHandler(enabled = navigation.isRunning || chat != null || selectedChannel != null || editingBookmark != null || showNewBookmark) {
        when {
            chat != null -> closeChat()
            navigation.isRunning -> Unit
            selectedChannel != null -> selectedChannel = null
            editingBookmark != null -> editingBookmark = null
            else -> showNewBookmark = false
        }
    }

    LaunchedEffect(ui.snapshot.status, ui.snapshot.ownClient?.let { id -> ui.snapshot.clients.firstOrNull { it.id == id }?.channel }) {
        if (ui.snapshot.status != "connected") {
            selectedChannel = null
            closeChat()
        } else if (chat?.channel != null && chat?.channel?.id != ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }?.channel) {
            closeChat()
        }
    }

    navigation.AnimatedContent(
        modifier = Modifier.fillMaxSize().background(Palette.background)
            .pointerInput(navigation.isRunning) {
                if (navigation.isRunning) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
        contentKey = { it?.conversation ?: "home" },
        transitionSpec = {
            val timing = tween<androidx.compose.ui.unit.IntOffset>(350, easing = FastOutSlowInEasing)
            if (targetState != null) {
                (slideInHorizontally(timing) { it } togetherWith
                    slideOutHorizontally(timing) { -(it * .28f).toInt() })
                    .apply { targetContentZIndex = 1f }.using(null)
            } else {
                (slideInHorizontally(timing) { -(it * .28f).toInt() } togetherWith
                    slideOutHorizontally(timing) { it })
                    .apply { targetContentZIndex = 0f }.using(null)
            }
        },
    ) { target ->
        Box(Modifier.fillMaxSize().then(
            if (target?.conversation != chat?.conversation) Modifier.clearAndSetSemantics {} else Modifier,
        )) {
            if (target != null) {
                ChatScreen(target, ui, active = chat?.conversation == target.conversation, onBack = closeChat)
            } else homeState.SaveableStateProvider("home") {
                Scaffold(
                    containerColor = Palette.background,
                    contentWindowInsets = WindowInsets(0),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    topBar = { Header(ui) },
                    bottomBar = {
                        Column(Modifier.background(Palette.bottom).navigationBarsPadding()) {
                            VoiceBar(ui, requestPermissions)
                            Row(Modifier.padding(top = 10.dp, bottom = 8.dp)) {
                                NavigationItem(stringResource(R.string.tab_channels), UiIcons.Number, tab == 0, ui.unread.channelCount) { tab = 0 }
                                NavigationItem(stringResource(R.string.tab_members), UiIcons.People, tab == 1, ui.unread.privateCounts.values.sum()) { tab = 1 }
                                NavigationItem(stringResource(R.string.tab_settings), UiIcons.Gear, tab == 2, 0) { tab = 2 }
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize()) {
                        ui.error?.let {
                            Row(
                                Modifier.fillMaxWidth().background(Color(0xFF542A30)).padding(start = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(it, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 16.sp)
                                TextButton(onClick = ClientSession::clearError) { Text(stringResource(R.string.action_close)) }
                            }
                        }
                        when {
                            tab == 2 -> SettingsScreen(ui, requestPermissions)
                            ui.snapshot.status in setOf("connecting", "reconnecting") -> BusyScreen(ui.snapshot.status)
                            ui.snapshot.status != "connected" -> BookmarkScreen(
                                ui,
                                onAdd = { showNewBookmark = true },
                                onEdit = { editingBookmark = it },
                                onDelete = { deletingBookmark = it },
                                onConnect = {
                                    ClientSession.connect(it)
                                    requestPermissions()
                                },
                            )
                            tab == 0 -> ChannelScreen(ui, onSelect = { selectedChannel = it }, onChat = { if (chat == null && !navigation.isRunning) chat = ChatTarget(it.conversation, it.name, channel = it) })
                            else -> MemberScreen(ui) {
                                it.conversation?.let { conversation -> if (chat == null && !navigation.isRunning) chat = ChatTarget(conversation, it.name, member = it) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewBookmark || editingBookmark != null) {
        val existing = editingBookmark
        BookmarkDialog(existing, onDismiss = {
            showNewBookmark = false
            editingBookmark = null
        }) { saved ->
            showNewBookmark = false
            editingBookmark = null
            if (existing == null) {
                ClientSession.connect(saved)
                requestPermissions()
            }
        }
    }
    deletingBookmark?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { deletingBookmark = null },
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = { Text(stringResource(R.string.bookmark_delete_message, bookmark.title)) },
            confirmButton = {
                Button(onClick = {
                    ClientSession.deleteBookmark(bookmark)
                    deletingBookmark = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingBookmark = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    selectedChannel?.let { channel ->
        ChannelDialog(
            channel,
            ui,
            onDismiss = { selectedChannel = null },

        )
    }
}

@Composable
private fun Header(ui: SessionUiState) {
    val disconnectLabel = stringResource(R.string.action_disconnect)
    Column {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        val server = ui.snapshot.server
        if (server == null) {
            Image(
                painterResource(R.mipmap.ic_launcher),
                contentDescription = "MobileSpeak",
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Avatar(server, null, false, 34)
        }
        Text(server ?: "MobileSpeak", Modifier.weight(1f), fontSize = 16.sp, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        if (ui.snapshot.status != "disconnected") {
            Box(
                Modifier.size(44.dp).semantics { contentDescription = disconnectLabel }.clickable { ClientSession.disconnect() },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.disconnect), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.disconnect_badge), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
    HorizontalDivider(color = Palette.border, thickness = .5.dp)
    }
}

@Composable
private fun BusyScreen(status: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text(stringResource(if (status == "reconnecting") R.string.status_reconnecting else R.string.status_connecting_server))
    }
}

@Composable
private fun BookmarkScreen(
    ui: SessionUiState,
    onAdd: () -> Unit,
    onEdit: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onConnect: (Bookmark) -> Unit,
) {
    if (ui.bookmarks.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(UiIcons.Headphones, null, Modifier.size(52.dp), tint = Palette.muted)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.empty_title), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.empty_message), color = Palette.muted, textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 18.sp)
            Button(onClick = onAdd, modifier = Modifier.padding(top = 8.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.text)) {
                Icon(UiIcons.Plus, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_connect_server))
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.bookmarks_title), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold) }
        items(ui.bookmarks, key = { it.id }) { bookmark ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable { onConnect(bookmark) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(bookmark.title, null, false, 34)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(bookmark.title, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(bookmark.address, fontSize = 12.sp, lineHeight = 14.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(bookmark.nickname, fontSize = 12.sp, lineHeight = 14.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(UiIcons.More, stringResource(R.string.bookmark_manage, bookmark.title), tint = Palette.text) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.card) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; onEdit(bookmark) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = Palette.disconnect) }, onClick = { menu = false; onDelete(bookmark) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onAdd).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(UiIcons.Plus, null, Modifier.size(18.dp))
                Text(stringResource(R.string.bookmark_add_server), fontSize = 17.sp, lineHeight = 20.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkDialog(existing: Bookmark?, onDismiss: () -> Unit, onSaved: (Bookmark) -> Unit) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var host by remember(existing) { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember(existing) { mutableStateOf(existing?.port?.toString() ?: "9987") }
    var nickname by remember(existing) { mutableStateOf(existing?.nickname ?: "MobileSpeakUser") }
    var password by remember(existing) { mutableStateOf(existing?.password.orEmpty()) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Palette.background,
        contentColor = Palette.text,
        modifier = Modifier.imePadding(),
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
        dragHandle = null,
        scrimColor = Color.Black.copy(alpha = .55f),
    ) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 20.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text(stringResource(R.string.action_cancel), fontSize = 17.sp, lineHeight = 20.sp)
                }
                Text(
                    stringResource(if (existing == null) R.string.bookmark_add_server else R.string.bookmark_edit_server),
                    Modifier.align(Alignment.Center),
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(stringResource(R.string.server_section), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 10.dp), color = Palette.muted, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                BookmarkField(title, { title = it }, stringResource(R.string.bookmark_name_optional))
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.border)
                BookmarkField(host, { host = it }, stringResource(R.string.server_address), KeyboardType.Uri)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.border)
                BookmarkField(port, { port = it }, stringResource(R.string.server_port), KeyboardType.Number)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Palette.border)
                BookmarkField(password, { password = it }, stringResource(R.string.server_password_optional), KeyboardType.Password, PasswordVisualTransformation())
            }
            Text(stringResource(R.string.nickname), Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp), color = Palette.muted, fontWeight = FontWeight.SemiBold)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                BookmarkField(nickname, { nickname = it }, stringResource(R.string.nickname))
            }
            Spacer(Modifier.height(28.dp))
            Surface(
                onClick = {
                    ClientSession.saveBookmark(existing?.id, title, host, port, nickname, password)?.let(onSaved)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
                color = Palette.card,
                contentColor = Palette.accent,
            ) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text(stringResource(if (existing == null) R.string.action_save_and_connect else R.string.action_save), fontSize = 17.sp, lineHeight = 20.sp)
                }
            }
        }
    }
}

@Composable
private fun BookmarkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    TextField(
        value,
        onValueChange,
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        placeholder = { Text(placeholder, color = Color(0xFF646468)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 17.sp, lineHeight = 20.sp),
        shape = RoundedCornerShape(0.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

private val pageTitleInset = 14.dp

@Composable
private fun PageTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.padding(bottom = 8.dp), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
}

@Composable
private fun ChannelScreen(ui: SessionUiState, onSelect: (Channel) -> Unit, onChat: (Channel) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val ownChannel = ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }?.channel
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(pageTitleInset), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item { PageTitle(stringResource(R.string.tab_channels)) }
        items(orderedChannels(ui.snapshot.channels), key = { it.first.id }) { (channel, depth) ->
            val spacer = channelSpacer(channel)
            val selected = channel.id == ownChannel
            val members = ui.snapshot.clients.filter { it.channel == channel.id }
            val shape = RoundedCornerShape(14.dp)
            val card = Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(4) * 10).dp).clip(shape)
                .background(if (selected) Palette.selected else Palette.card)
            if (spacer != null) {
                val marker = channel.name.substringBefore(']').lowercase()
                val text = channelSpacerDisplayText(channel, spacer)
                Text(text, card.padding(10.dp).padding(start = 4.dp).heightIn(min = 48.dp), fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textAlign = when { marker.startsWith("[c") -> TextAlign.Center; marker.startsWith("[r") -> TextAlign.End; else -> TextAlign.Start })
            } else {
                Row(card.border(if (selected) 2.dp else 1.dp, if (selected) Palette.accent else Palette.border, shape).padding(10.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onSelect(channel)
                    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            ChannelIcon(channel)
                            Text(channel.name, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (members.isNotEmpty()) Row(Modifier.padding(start = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            members.take(2).forEach { Avatar(it.name, it.avatarPath, it.speaking, 24) }
                            val names = members.take(2).joinToString(stringResource(R.string.member_name_separator)) { it.name }
                            Text(pluralStringResource(R.plurals.channel_members_summary, members.size, names, members.size), fontSize = 11.sp, lineHeight = 13.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Box {
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.bottom).clickable(enabled = selected) { onChat(channel) }, contentAlignment = Alignment.Center) {
                            Icon(UiIcons.Chat, stringResource(R.string.channel_chat_open, channel.name), Modifier.size(28.dp), tint = if (selected) Color.White else Palette.muted.copy(alpha = .45f))
                        }
                        if (selected) UnreadBadge(ui.unread.channelCount, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelDialog(channel: Channel, ui: SessionUiState, onDismiss: () -> Unit) {
    var passwordPrompt by remember(channel.id) { mutableStateOf(false) }
    var password by remember(channel.id) { mutableStateOf("") }
    val current = ui.snapshot.channels.firstOrNull { it.id == channel.id } ?: channel
    val ownChannel = ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }?.channel
    val members = ui.snapshot.clients.filter { it.channel == channel.id }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Palette.background, contentColor = Palette.text,
        modifier = Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp),
        shape = RoundedCornerShape(40.dp), dragHandle = null, scrimColor = Color.Black.copy(alpha = .55f)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val listMaximum = (maxHeight * .78f - 172.dp).coerceAtLeast(52.dp)
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChannelIcon(current, 28)
                    Spacer(Modifier.width(8.dp))
                    Text(current.name, Modifier.weight(1f), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), fontSize = 17.sp, lineHeight = 20.sp) }
                }
                Column(Modifier.fillMaxWidth().heightIn(min = 52.dp, max = listMaximum).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (members.isEmpty()) Text(stringResource(R.string.channel_no_members), Modifier.padding(vertical = 16.dp), color = Palette.muted)
                    members.forEach { MemberRow(it) }
                }
                Button(onClick = {
                    if (current.password) passwordPrompt = true
                    else { ClientSession.join(current, ""); onDismiss() }
                }, enabled = ui.snapshot.status == "connected" && ownChannel != current.id,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.text, disabledContainerColor = Color(0xFF050608), disabledContentColor = Palette.card)) {
                    Text(stringResource(R.string.channel_join), fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (passwordPrompt) AlertDialog(onDismissRequest = { passwordPrompt = false }, title = { Text(stringResource(R.string.channel_password)) }, text = {
        OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.password)) })
    }, confirmButton = { TextButton(onClick = { ClientSession.join(current, password); onDismiss() }) { Text(stringResource(R.string.action_join)) } },
        dismissButton = { TextButton(onClick = { passwordPrompt = false }) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
private fun MemberScreen(ui: SessionUiState, onChat: (Member) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pageTitleInset, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle(stringResource(R.string.tab_members), Modifier.padding(horizontal = pageTitleInset)) }
        items(ui.snapshot.clients.filter { it.id != ui.snapshot.ownClient }, key = { it.id }) { member ->
            MemberRow(member, Modifier.padding(horizontal = 16.dp).clickable(enabled = member.uid != null) { onChat(member) }, ui.unread.privateCounts[member.uid] ?: 0)
        }
    }
}

@Composable
private fun MemberRow(member: Member, modifier: Modifier = Modifier, unread: Int = 0) {
    val badges = member.badges.filter { it.iconPath != null }.take(4)
    val group = member.channelGroupIcon?.takeIf { it.iconPath != null || it.iconId in listOf(100L, 300L) }
    val groups = member.serverGroupIcons.filter { it.iconPath != null || it.iconId in listOf(100L, 300L) }.take((5 - badges.size - if (group == null) 0 else 1).coerceAtLeast(0))
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box {
            Avatar(member.name, member.avatarPath, member.speaking, 34)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
        }
        Text(member.name, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.forEach { GroupIconView(it) }
            badges.forEach { BadgeView(it) }
            group?.let { GroupIconView(it) }
        }
        Icon(if (member.deafened) UiIcons.SpeakerOff else if (member.muted) UiIcons.MicOff else UiIcons.Mic,
            stringResource(if (member.deafened) R.string.member_listening_off else if (member.muted) R.string.member_muted else if (member.speaking) R.string.member_speaking else R.string.member_microphone_on),
            Modifier.size(20.dp), tint = if (member.speaking) Palette.green else Palette.muted)
    }
}

@Composable
private fun SettingsScreen(ui: SessionUiState, requestPermissions: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val selectedLanguage = AppLanguage.selected()
    var languageMenuExpanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = pageTitleInset, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageTitle(stringResource(R.string.tab_settings), Modifier.padding(horizontal = pageTitleInset))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingSwitch(stringResource(R.string.settings_microphone), !ui.microphoneMuted, !ui.deafened) { enabled ->
                if (enabled && !ClientSession.microphonePermission) requestPermissions()
                else ClientSession.setAudio(inputMuted = !enabled)
            }
            Text(stringResource(R.string.settings_microphone_help), fontSize = 13.sp, lineHeight = 16.sp, color = Palette.muted)
            SettingSwitch(stringResource(R.string.settings_listening), !ui.deafened) { ClientSession.setAudio(deafened = !it) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_noise_suppression), fontSize = 17.sp, lineHeight = 20.sp)
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                    listOf("rnnoise" to "RNNoise", "none" to stringResource(R.string.settings_noise_none)).forEachIndexed { index, (mode, label) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.border)
                        val selected = ui.noiseSuppression == mode
                        val selectionState = stringResource(if (selected) R.string.selection_selected else R.string.selection_not_selected)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = selectionState }.selectable(selected, role = Role.RadioButton) {
                            if (!selected) {
                                haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                ClientSession.setNoiseSuppression(mode)
                            }
                        }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp)
                            SelectionCircle(selected)
                        }
                    }
                }
            }
            val languageTitle = stringResource(R.string.settings_language)
            val currentLanguage = stringResource(selectedLanguage.title)
            val chooseLanguage = stringResource(R.string.accessibility_choose_language)
            Box(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card)
                        .clickable(role = Role.Button, onClickLabel = chooseLanguage) { languageMenuExpanded = true }
                        .clearAndSetSemantics {
                            contentDescription = languageTitle
                            stateDescription = currentLanguage
                            onClick(chooseLanguage) { languageMenuExpanded = true; true }
                        }
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(languageTitle, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1)
                    Text(currentLanguage, fontSize = 17.sp, lineHeight = 20.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(UiIcons.Back, null, Modifier.size(16.dp).rotate(-90f), tint = Palette.muted)
                }
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                    Box(Modifier.size(1.dp)) {
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false },
                            offset = DpOffset(0.dp, 24.dp),
                            containerColor = Palette.card,
                        ) {
                            AppLanguage.entries.forEach { language ->
                                val selected = selectedLanguage == language
                                val selectionState = stringResource(if (selected) R.string.selection_selected else R.string.selection_not_selected)
                                DropdownMenuItem(
                                    text = { Text(stringResource(language.title)) },
                                    onClick = {
                                        languageMenuExpanded = false
                                        if (AppLanguage.select(language)) {
                                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        }
                                    },
                                    trailingIcon = {
                                        if (selected) Icon(UiIcons.Check, null, Modifier.size(18.dp), tint = Palette.accent)
                                    },
                                    modifier = Modifier.semantics { stateDescription = selectionState },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionCircle(selected: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val color = if (selected) Palette.accent else Palette.muted
        drawCircle(color, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.8.dp.toPx()))
        if (selected) drawCircle(color, radius = size.minDimension / 2 - 4.dp.toPx())
    }
}

@Composable
private fun SettingSwitch(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val position by animateFloatAsState(if (checked) 1f else 0f, label = "switch")
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).toggleable(checked, enabled = enabled, role = Role.Switch) {
        haptic.performHapticFeedback(toggleHapticType(it))
        onChange(it)
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp)
        Canvas(Modifier.size(63.dp, 28.dp)) {
            drawRoundRect(if (checked) Palette.accent else Color(0xFF63656B), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val inset = 2.dp.toPx()
            val width = 37.dp.toPx()
            drawRoundRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(inset + position * (size.width - width - inset * 2), inset),
                size = androidx.compose.ui.geometry.Size(width, size.height - inset * 2), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    }
}

private fun toggleHapticType(enabled: Boolean) = when {
    enabled -> HapticFeedbackType.ToggleOn
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackType.ToggleOff
    else -> HapticFeedbackType.ToggleOn
}

@Composable
private fun VoiceBar(ui: SessionUiState, requestPermissions: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val connected = ui.snapshot.status == "connected"
    val channel = ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }?.channel
    val muted = ui.microphoneMuted || ui.deafened
    Row(Modifier.fillMaxWidth().background(Palette.bottom).padding(start = 12.dp, end = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(UiIcons.Wave, null, Modifier.size(18.dp), tint = if (connected) Palette.green else Palette.muted)
        Text(if (connected) stringResource(R.string.status_connected_to_channel, ui.snapshot.channels.firstOrNull { it.id == channel }?.name.orEmpty()) else if (ui.snapshot.status in listOf("connecting", "reconnecting")) stringResource(R.string.status_connecting) else stringResource(R.string.status_disconnected), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = {
            haptic.performHapticFeedback(toggleHapticType(ui.microphoneMuted))
            if (ui.microphoneMuted && !ClientSession.microphonePermission) requestPermissions()
            else ClientSession.setAudio(inputMuted = !ui.microphoneMuted)
        }, enabled = !ui.deafened, modifier = Modifier.size(44.dp, 48.dp)) {
            Icon(if (muted) UiIcons.MicOff else UiIcons.Mic, stringResource(if (muted) R.string.voice_enable_microphone else R.string.voice_mute), Modifier.size(20.dp), tint = if (muted) Palette.muted else Palette.green)
        }
        IconButton(onClick = {
            haptic.performHapticFeedback(toggleHapticType(ui.deafened))
            ClientSession.setAudio(deafened = !ui.deafened)
        }, modifier = Modifier.size(44.dp, 48.dp)) {
            Icon(if (ui.deafened) UiIcons.SpeakerOff else UiIcons.Speaker, stringResource(if (ui.deafened) R.string.voice_enable_listening else R.string.voice_disable_listening), Modifier.size(22.dp), tint = Palette.text)
        }
    }
}

@Composable
private fun ChatScreen(target: ChatTarget, ui: SessionUiState, active: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val messages = ui.messages.filter { it.conversation == target.conversation }
    val token = remember(target.conversation) { UUID.randomUUID().toString() }
    var input by androidx.compose.runtime.saveable.rememberSaveable(target.conversation) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val available = ui.snapshot.status == "connected" && if (target.channel != null) {
        ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }?.channel == target.channel.id
    } else {
        target.member?.uid?.let { uid -> ui.snapshot.clients.any { it.uid == uid } } == true
    }
    val valid = active && available && input.trim().isNotEmpty() && input.toByteArray().size <= 8192
    DisposableEffect(target.conversation, active) {
        if (active) ClientSession.setChatVisible(target.conversation, token, true)
        onDispose { if (active) ClientSession.setChatVisible(target.conversation, token, false) }
    }
    val keyboardHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(messages.size, keyboardHeight) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    val peer = target.member?.let { saved -> ui.snapshot.clients.firstOrNull { it.uid == saved.uid } ?: saved }
    Column(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(44.dp)) {
                Icon(UiIcons.Back, stringResource(R.string.action_back), Modifier.size(24.dp), tint = Palette.text)
            }
            Row(Modifier.align(Alignment.Center).padding(horizontal = 52.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                peer?.let { Avatar(it.name, it.avatarPath, false, 30) }
                Text(peer?.name ?: target.title, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bubbleWidth = (maxWidth - 28.dp - 38.dp - 46.dp).coerceAtLeast(40.dp)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages, key = { it.id }) { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!message.own) {
                            Avatar(message.senderName, message.avatarPath, false, 30)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.widthIn(max = bubbleWidth), horizontalAlignment = if (message.own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (target.channel != null && !message.own) Text(message.senderName, fontSize = 12.sp, lineHeight = 14.sp, color = Palette.muted)
                            SelectionContainer {
                                Text(message.text, Modifier.clip(RoundedCornerShape(14.dp)).background(if (message.own) Palette.accent else Palette.card).padding(horizontal = 12.dp, vertical = 9.dp), fontSize = 17.sp, lineHeight = 20.sp)
                            }
                            if (message.own && message.status == "pending") CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp, color = Palette.muted)
                            if (message.own && message.status == "failed") Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(UiIcons.Error, stringResource(R.string.chat_send_failed), Modifier.size(12.dp), tint = Palette.disconnect)
                                Text(message.error?.let { context.localizedCoreError(it, "") } ?: stringResource(R.string.chat_send_failed), fontSize = 11.sp, lineHeight = 13.sp, color = Palette.disconnect)
                            }
                        }
                        if (message.own) {
                            Spacer(Modifier.width(8.dp))
                            Avatar(message.senderName, message.avatarPath, false, 30)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.border, thickness = .5.dp)
        Row(Modifier.fillMaxWidth().background(Palette.bottom).padding(10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(input, { input = it }, Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black)
                .then(if (input.toByteArray().size > 8192) Modifier.border(1.dp, Palette.disconnect, RoundedCornerShape(5.dp)) else Modifier).padding(horizontal = 7.dp, vertical = 6.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 17.sp, lineHeight = 20.sp), cursorBrush = SolidColor(Palette.accent), maxLines = 4,
                decorationBox = { field -> Box { if (input.isEmpty()) Text(stringResource(R.string.chat_input_placeholder), color = Color(0xFF646468), fontSize = 17.sp, lineHeight = 20.sp); field() } })
            IconButton(enabled = valid, onClick = {
                val sent = target.channel?.let { ClientSession.sendChannelMessage(it, input) }
                    ?: target.member?.let { ClientSession.sendPrivateMessage(it, input) } ?: false
                if (sent) input = ""
            }, modifier = Modifier.size(38.dp)) {
                Icon(UiIcons.Send, stringResource(R.string.action_send), Modifier.size(22.dp), tint = if (valid) Palette.text else Palette.muted)
            }
        }
    }
}

@Composable
private fun RowScope.NavigationItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, unread: Int, onClick: () -> Unit) {
    val color = if (selected) Palette.accent else Palette.muted
    Column(Modifier.weight(1f).heightIn(min = 44.dp).selectable(selected, role = Role.Tab, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box {
            Icon(icon, null, Modifier.size(22.dp), tint = color)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-7).dp))
        }
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
private fun Avatar(name: String, path: String?, speaking: Boolean, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(Palette.accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(2).uppercase(), fontWeight = FontWeight.Bold, fontSize = (size * .36).sp)
        LocalImage(path, Modifier.fillMaxSize().clip(CircleShape))
        if (speaking) Box(Modifier.matchParentSize().border(3.dp, Palette.green, CircleShape))
    }
}

@Composable
private fun ChannelIcon(channel: Channel, size: Int = 21) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (channel.password) UiIcons.Lock else UiIcons.Number, null, Modifier.fillMaxSize(), tint = Palette.text)
        LocalImage(channel.iconPath, Modifier.fillMaxSize())
    }
}

@Composable
private fun GroupIconView(icon: GroupIcon) {
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        val label = when (icon.iconId) { 100L -> "C"; 300L -> "S"; else -> null }
        if (label != null && icon.iconPath == null) {
            Icon(UiIcons.Person, icon.name, Modifier.size(17.dp), tint = Palette.muted)
            Text(label, Modifier.align(Alignment.BottomEnd), fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Black, color = Palette.disconnect)
        }
        LocalImage(icon.iconPath, Modifier.fillMaxSize())
    }
}

@Composable
private fun BadgeView(badge: Badge) {
    LocalImage(badge.iconPath, Modifier.size(18.dp), badge.name)
}

@Composable
private fun LocalImage(path: String?, modifier: Modifier, description: String? = null) {
    if (path == null) return
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path)?.asImageBitmap().also {
                if (it == null) runCatching { File(path).delete() }
            }
        }
    }
    bitmap?.let { Image(it, description, modifier, contentScale = ContentScale.Crop) }
}

@Composable
private fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).clip(CircleShape).background(Palette.disconnect)
            .padding(horizontal = if (count > 9) 5.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun channelSpacer(channel: Channel): String? {
    if (!channel.permanent || channel.parent != 0L || !channel.name.startsWith('[')) return null
    val end = channel.name.indexOf(']')
    if (end < 0) return null
    val marker = channel.name.substring(1, end).lowercase()
    if (!marker.startsWith("spacer") && !marker.startsWith("cspacer") &&
        !marker.startsWith("lspacer") && !marker.startsWith("rspacer") && !marker.startsWith("*spacer")) return null
    return channel.name.substring(end + 1)
}

internal fun channelSpacerDisplayText(channel: Channel, text: String): String =
    if (channel.name.startsWith("[*") && text.isNotEmpty()) text.repeat((48 / text.length).coerceAtLeast(1)) else text
