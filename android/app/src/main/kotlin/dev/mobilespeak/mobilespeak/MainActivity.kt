package dev.mobilespeak.mobilespeak

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase.resolvedSystemLanguageContext())
    }

    private var openAbout by mutableStateOf(false)
    private var permissionEnablesMicrophone = true
    private var permissionSettingsPending = false

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("show_about", false)) openAbout = true
    }

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.RECORD_AUDIO] == true
        ClientSession.setMicrophonePermission(granted, enableMicrophone = false)
        if (granted && permissionEnablesMicrophone) ClientSession.setAudio(inputMuted = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openAbout = savedInstanceState == null && intent.getBooleanExtra("show_about", false)
        AppUpdates.initialize(applicationContext)
        ClientSession.initialize(applicationContext)
        ClientSession.refreshLanguage()
        AppUpdates.refreshLanguage()
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
                        MobileSpeakApp({ requestVoicePermissions() }, openAbout, requestPushToTalkPermission = { requestVoicePermissions(false) }) { openAbout = false; intent.removeExtra("show_about") }
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

    override fun onResume() {
        super.onResume()
        AppUpdates.onForeground()
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ClientSession.setMicrophonePermission(granted, enableMicrophone = false)
        if (permissionSettingsPending) {
            permissionSettingsPending = false
            if (granted && permissionEnablesMicrophone) ClientSession.setAudio(inputMuted = false)
        }
    }

    override fun onPause() {
        AppUpdates.onBackground()
        ClientSession.cancelPushToTalk()
        super.onPause()
    }

    private fun requestVoicePermissions(enableMicrophone: Boolean = true) {
        permissionEnablesMicrophone = enableMicrophone
        val preferences = getSharedPreferences("mobilespeak.permissions", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
            preferences.getBoolean("microphone_requested", false) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            permissionSettingsPending = true
            startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", packageName, null)))
            return
        }
        preferences.edit().putBoolean("microphone_requested", true).apply()
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionRequest.launch(permissions.toTypedArray())
    }
}

internal object Palette {
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

private data class PageTarget(val chat: ChatTarget? = null, val settings: String? = null) {
    val key: String get() = chat?.conversation ?: settings ?: "home"
}

internal data class ChatTarget(val conversation: String, val title: String, val channel: Channel? = null, val member: Member? = null)

@Composable
internal fun MobileSpeakApp(requestPermissions: () -> Unit, openAbout: Boolean = false, requestPushToTalkPermission: () -> Unit = requestPermissions, consumedAbout: () -> Unit = {}) {
    val ui by ClientSession.state.collectAsStateWithLifecycle()
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    var drawerExpanded by rememberSaveable { mutableStateOf(false) }
    val connected = ui.snapshot.status == "connected"
    val drawerHaptic = LocalHapticFeedback.current
    val setDrawerExpanded: (Boolean) -> Unit = { expanded ->
        if (connected && expanded != drawerExpanded) {
            drawerHaptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
            drawerExpanded = expanded
        }
    }
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

    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsModal by remember { mutableStateOf(false) }
    val page = PageTarget(chat, settingsPage)
    val navigation = updateTransition(page, label = "Page navigation")
    val homeState = rememberSaveableStateHolder()
    // Track connection changes even while home is removed for chat or settings details.
    val connectionKey = rememberSaveable(ui.snapshot.status == "connected", ui.snapshot.serverId) { UUID.randomUUID().toString() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val closeChat = {
        if (chat != null) {
            focusManager.clearFocus()
            keyboard?.hide()
            chat = null
        }
    }

    LaunchedEffect(openAbout) {
        if (openAbout) { closeChat(); tab = 2; settingsPage = "about"; consumedAbout() }
    }

    LaunchedEffect(tab) { ClientSession.cancelPushToTalk() }

    BackHandler(enabled = navigation.isRunning || settingsPage != null || chat != null || selectedChannel != null || editingBookmark != null || showNewBookmark || drawerExpanded) {
        when {
            settingsPage != null -> settingsPage = null
            chat != null -> closeChat()
            navigation.isRunning -> Unit
            selectedChannel != null -> selectedChannel = null
            editingBookmark != null -> editingBookmark = null
            showNewBookmark -> showNewBookmark = false
            else -> setDrawerExpanded(false)
        }
    }

    LaunchedEffect(ui.snapshot.status, ui.snapshot.ownClient?.let { id -> ui.snapshot.clients.firstOrNull { it.id == id }?.channel }) {
        if (ui.snapshot.status != "connected") {
            drawerExpanded = false
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
        contentKey = { it.key },
        transitionSpec = {
            detailNavigationTransition(targetState.key != "home").using(null)
        },
    ) { target ->
        Box(Modifier.fillMaxSize().then(
            if (target.key != page.key) Modifier.clearAndSetSemantics {} else Modifier,
        )) {
            if (target.settings != null) {
                SettingsDetailPage(target.settings, onBack = { settingsPage = null }, onModal = { settingsModal = it })
            } else if (target.chat != null) {
                ChatScreen(target.chat, ui, active = chat?.conversation == target.chat.conversation && !navigation.isRunning, onBack = closeChat, requestPermissions = requestPushToTalkPermission)
            } else homeState.SaveableStateProvider("home") {
                val tabState = rememberSaveableStateHolder()
                var savedConnectionKey by rememberSaveable { mutableStateOf(connectionKey) }
                if (savedConnectionKey != connectionKey) {
                    tabState.removeState("channels:$savedConnectionKey")
                    tabState.removeState("members:$savedConnectionKey")
                    savedConnectionKey = connectionKey
                }
                val tabPageKey = when {
                    tab == 2 -> "settings"
                    ui.snapshot.status in setOf("connecting", "reconnecting") -> "busy:${ui.snapshot.status}"
                    ui.snapshot.status != "connected" -> "bookmarks"
                    tab == 0 -> "channels:$connectionKey"
                    else -> "members:$connectionKey"
                }
                Scaffold(
                    containerColor = Palette.background,
                    contentWindowInsets = WindowInsets(0),
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                    topBar = { Header(ui) },
                    bottomBar = {
                        Column(Modifier.background(Palette.bottom).navigationBarsPadding()) {
                            PushToTalkBar(ui, requestPushToTalkPermission,
                                active = page.key == "home" && !navigation.isRunning && !settingsModal &&
                                    editingBookmark == null && !showNewBookmark && deletingBookmark == null && selectedChannel == null)
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
                                PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                                    TextButton(interactionSource = source, modifier = feedback, onClick = ClientSession::clearError) { Text(stringResource(R.string.action_close)) }
                                }
                            }
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                            // Only the collapsed header takes layout space; expansion overlays the same list viewport.
                            Box(Modifier.fillMaxSize().padding(bottom = 64.dp)) {
                                tabState.SaveableStateProvider(tabPageKey) { when {
                                    tab == 2 -> SettingsScreen(ui, requestPermissions, onPage = { settingsPage = it }, onModal = { settingsModal = it })
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
                                } }
                            }
                            VoiceDrawer(ui, requestPermissions, drawerExpanded, setDrawerExpanded, maxHeight, maxWidth, Modifier.align(Alignment.BottomCenter))
                        }
                    }
                }
            }
        }
    }

    UpdatePrompt(suitable = !settingsModal && chat == null && !navigation.isRunning && editingBookmark == null && !showNewBookmark && deletingBookmark == null && selectedChannel == null && (tab != 2 || settingsPage == "about"), manualSuitable = settingsPage == "about")

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
internal fun Header(ui: SessionUiState) {
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
            Avatar(server, null, false, 34, accessible = false)
        }
        Text(server ?: "MobileSpeak", Modifier.weight(1f), fontSize = 16.sp, lineHeight = 19.sp, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        if (ui.snapshot.status != "disconnected") {
            Box(
                Modifier.size(44.dp).semantics { contentDescription = disconnectLabel }.clickable(interactionSource = null, indication = RowPressFeedback) { ClientSession.disconnect() },
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_off), contentDescription = null, modifier = Modifier.size(34.dp))
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
internal fun BookmarkScreen(
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
            PressFeedback(RowPressFeedback) { source, feedback, pressed ->
                val background by animateColorAsState(if (pressed) lerp(Palette.accent, Color.White, 0.2f) else Palette.accent,
                    tween(if (pressed) 0 else 350, easing = ObservedPressRecovery), label = "Connect press background")
                Button(onClick = onAdd, interactionSource = source, modifier = Modifier.padding(top = 8.dp), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = Palette.text)) {
                    Row(feedback, verticalAlignment = Alignment.CenterVertically) {
                        Icon(UiIcons.Plus, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_connect_server))
                    }
                }
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.bookmarks_title), fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold) }
        items(ui.bookmarks, key = { it.id }) { bookmark ->
            var menu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(interactionSource = null, indication = RowPressFeedback) { onConnect(bookmark) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(bookmark.title, null, false, 34, accessible = false)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(bookmark.title, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(bookmark.address, fontSize = 12.sp, lineHeight = 14.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    PressFeedback(null) { source, _, _ ->
                        IconButton(interactionSource = source, onClick = { menu = true }, modifier = Modifier.size(44.dp)) { Icon(UiIcons.More, stringResource(R.string.bookmark_manage, bookmark.title), tint = Palette.text) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Palette.card) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) },
                            leadingIcon = { Icon(UiIcons.Pencil, null, Modifier.size(24.dp), tint = Palette.accent) }, onClick = { menu = false; onEdit(bookmark) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = Palette.disconnect) },
                            leadingIcon = { Icon(UiIcons.Trash, null, Modifier.size(24.dp), tint = Palette.disconnect) }, onClick = { menu = false; onDelete(bookmark) })
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(interactionSource = null, indication = ToolbarPressFeedback, onClick = onAdd).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
            Spacer(Modifier.height(28.dp))
            PressFeedback(null) { source, _, pressed ->
                val background by animateColorAsState(if (pressed) lerp(Palette.card, Color.White, 0.066f) else Palette.card,
                    tween(if (pressed) 0 else 350, easing = ObservedPressRecovery), label = "Bookmark press background")
                Surface(
                    interactionSource = source,
                    onClick = {
                        ClientSession.saveBookmark(existing?.id, title, host, port, password)?.let(onSaved)
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = background,
                    contentColor = Palette.accent,
                ) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(if (existing == null) R.string.action_save_and_connect else R.string.action_save), fontSize = 17.sp, lineHeight = 20.sp)
                    }
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
internal fun ChannelScreen(ui: SessionUiState, onSelect: (Channel) -> Unit, onChat: (Channel) -> Unit) {
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
                    Column(Modifier.weight(1f).clickable(interactionSource = null, indication = RowPressFeedback) {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onSelect(channel)
                    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            ChannelIcon(channel)
                            Text(channel.name, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ChannelMemberPreview(members, Modifier.padding(start = 30.dp))
                    }
                    Box(Modifier.size(48.dp).clickable(interactionSource = null, indication = ToolbarPressFeedback, enabled = selected) { onChat(channel) }) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Palette.bottom), contentAlignment = Alignment.Center) {
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
                    PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                        TextButton(interactionSource = source, modifier = feedback, onClick = onDismiss) { Text(stringResource(R.string.action_close), fontSize = 17.sp, lineHeight = 20.sp) }
                    }
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
internal fun MemberScreen(ui: SessionUiState, onChat: (Member) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = pageTitleInset, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle(stringResource(R.string.tab_members), Modifier.padding(horizontal = pageTitleInset)) }
        items(ui.snapshot.clients.filter { it.id != ui.snapshot.ownClient }, key = { it.id }) { member ->
            MemberRow(member, Modifier.padding(horizontal = 16.dp).clickable(enabled = member.uid != null, interactionSource = null, indication = RowPressFeedback) { onChat(member) }, ui.unread.privateCounts[member.uid] ?: 0)
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
            Avatar(member.name, member.avatarPath, member.speaking, 34, member, accessible = false)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp))
        }
        val description = member.accessibilityLabel(LocalContext.current)
        Text(member.name, Modifier.weight(1f).clearAndSetSemantics { contentDescription = description }, fontSize = 17.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (groups.isNotEmpty() || badges.isNotEmpty() || group != null) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            groups.forEach { GroupIconView(it) }
            badges.forEach { BadgeView(it) }
            group?.let { GroupIconView(it) }
        }
    }
}

@Composable
internal fun ProfileCard(ui: SessionUiState, edit: () -> Unit) {
    val editLabel = stringResource(R.string.profile_edit_nickname, ui.profile.nickname)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.card).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(Palette.selected), contentAlignment = Alignment.Center) {
            if (ui.avatarPreviewPath == null) Icon(UiIcons.Person, stringResource(R.string.avatar_preview), Modifier.size(28.dp), tint = Palette.muted)
            else LocalImage(ui.avatarPreviewPath, Modifier.fillMaxSize(), stringResource(R.string.avatar_preview), ui.avatarRevision)
        }
        Column(Modifier.weight(1f).heightIn(min = 64.dp).semantics { contentDescription = editLabel }
            .clickable(interactionSource = null, indication = RowPressFeedback, role = Role.Button, onClick = edit),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui.profile.nickname, Modifier.weight(1f, fill = false), fontSize = 17.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(UiIcons.Pencil, null, Modifier.size(17.dp), tint = Palette.accent)
            }
            Text(stringResource(if (ui.avatarIntent == "clear") R.string.avatar_cleared_local else if (ui.avatarPreviewPath == null) R.string.avatar_none else R.string.avatar_local),
                fontSize = 13.sp, lineHeight = 17.sp, color = Palette.muted)
        }
    }
}

@Composable
internal fun NicknameDialog(ui: SessionUiState, onDismiss: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf(ui.profile.nickname) }
    var submitted by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(ui.nicknameSaving, ui.profile.requestId) {
        if (submitted != null && ui.profile.requestId == submitted && !ui.nicknameSaving && ui.nicknameSaveError == null) onDismiss()
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_nickname_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it }, singleLine = true,
                    label = { Text(stringResource(R.string.nickname)) },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text))
                if (submitted != null) ui.nicknameSaveError?.let { Text(it, color = Palette.disconnect, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(enabled = !ui.nicknameSaving, onClick = { submitted = ClientSession.saveNickname(draft) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = Palette.card, titleContentColor = Palette.text, textContentColor = Palette.text)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun SettingsScreen(ui: SessionUiState, requestPermissions: () -> Unit, onPage: (String) -> Unit, onModal: (Boolean) -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    var nicknamePresented by rememberSaveable { mutableStateOf(false) }
    if (nicknamePresented) NicknameDialog(ui) { nicknamePresented = false }
    var avatarClearPresented by rememberSaveable { mutableStateOf(false) }
    var avatarUri by rememberSaveable { mutableStateOf<String?>(null) }
    var avatarSelection by rememberSaveable { mutableStateOf(0L) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && ClientSession.isCurrentAvatarSelection(avatarSelection)) avatarUri = uri.toString()
        else ClientSession.cancelAvatarSelection(avatarSelection)
    }
    avatarUri?.let { uri ->
        AvatarCropDialog(uri, avatarSelection) {
            ClientSession.cancelAvatarSelection(avatarSelection)
            avatarUri = null
        }
    }
    if (avatarClearPresented) AlertDialog(
        onDismissRequest = { avatarClearPresented = false },
        title = { Text(stringResource(R.string.avatar_remove)) },
        text = { Text(stringResource(if (ui.snapshot.status == "connected") R.string.avatar_clear_confirm_connected else R.string.avatar_clear_confirm_offline)) },
        confirmButton = { TextButton(onClick = { avatarClearPresented = false; ClientSession.clearAvatar() }) { Text(stringResource(R.string.avatar_remove), color = Palette.disconnect) } },
        dismissButton = { TextButton(onClick = { avatarClearPresented = false }) { Text(stringResource(R.string.action_cancel)) } },
        containerColor = Palette.card, titleContentColor = Palette.text, textContentColor = Palette.text,
    )
    DisposableEffect(nicknamePresented, avatarClearPresented, avatarUri) {
        onModal(nicknamePresented || avatarClearPresented || avatarUri != null)
        onDispose { onModal(false) }
    }
    val selectedLanguage = AppLanguage.selected()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = pageTitleInset, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PageTitle(stringResource(R.string.tab_settings), Modifier.padding(horizontal = pageTitleInset))
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.profile_title), fontSize = 13.sp, lineHeight = 17.sp, color = Palette.muted)
                ProfileCard(ui) {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    nicknamePresented = true
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    PressFeedback(RowPressFeedback) { source, feedback, _ ->
                        TextButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            avatarClearPresented = true
                        }, enabled = !ui.avatarClearingLocally && ui.avatarStatus != "clearing",
                            interactionSource = source, modifier = Modifier.heightIn(min = 48.dp).then(feedback),
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = Palette.disconnect,
                                disabledContentColor = Palette.disconnect.copy(alpha = 0.38f))) {
                            Text(stringResource(R.string.avatar_remove), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
                        }
                    }
                    PressFeedback(RowPressFeedback) { source, feedback, _ ->
                        TextButton(interactionSource = source, modifier = Modifier.heightIn(min = 48.dp).then(feedback),
                            contentPadding = PaddingValues(0.dp), onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            avatarSelection = ClientSession.beginAvatarSelection(); avatarPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) { Text(stringResource(if (ui.avatarPreviewPath == null) R.string.avatar_choose else R.string.avatar_change),
                            fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal) }
                    }
                }
                ui.avatarCleanupDetail?.let { Text(it, fontSize = 13.sp, color = Palette.disconnect) }
                if (ui.avatarStatus in listOf("checking", "uploading", "clearing", "failed", "clear_failed", "clear_save_failed")) {
                    val status = when (ui.avatarStatus) {
                        "checking" -> R.string.avatar_checking
                        "uploading" -> R.string.avatar_uploading
                        "failed" -> R.string.avatar_failed
                        "clearing" -> R.string.avatar_clearing
                        "clear_failed" -> R.string.avatar_clear_failed
                        else -> R.string.avatar_clear_save_failed
                    }
                    Text(stringResource(status) + (if (ui.avatarStatus in listOf("failed", "clear_failed", "clear_save_failed") && ui.avatarDetail != null) ": ${ui.avatarDetail}" else ""),
                        fontSize = 13.sp, lineHeight = 17.sp, color = if (ui.avatarStatus in listOf("failed", "clear_failed", "clear_save_failed")) Palette.disconnect else Palette.muted)
                }
            }
            SettingSwitch(stringResource(R.string.settings_microphone), !ui.microphoneMuted, !ui.deafened) { enabled ->
                if (enabled && !ClientSession.microphonePermission) requestPermissions()
                else ClientSession.setAudio(inputMuted = !enabled)
            }
            SettingSwitch(stringResource(R.string.settings_listening), !ui.deafened) { ClientSession.setAudio(deafened = !it) }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingSwitch(stringResource(R.string.settings_receive_agc), ui.receiveAgc, onChange = ClientSession::setReceiveAgc)
                Text(stringResource(R.string.settings_receive_agc_help), fontSize = 13.sp, lineHeight = 16.sp, color = Palette.muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_speech_mode), fontSize = 17.sp, lineHeight = 20.sp)
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                    listOf("automatic" to R.string.settings_speech_automatic, "push_to_talk" to R.string.settings_speech_push_to_talk).forEachIndexed { index, (mode, label) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.border)
                        val selected = ui.speech.mode == mode
                        val selectionState = stringResource(if (selected) R.string.selection_selected else R.string.selection_not_selected)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = selectionState }
                            .selectable(selected, interactionSource = null, indication = RowPressFeedback, role = Role.RadioButton) {
                                if (!selected) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); ClientSession.setSpeechMode(mode) }
                            }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(label), Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp)
                            SelectionCircle(selected)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_noise_suppression), fontSize = 17.sp, lineHeight = 20.sp)
                Column(Modifier.clip(RoundedCornerShape(14.dp)).background(Palette.card)) {
                    listOf("dpdfnet2" to "DPDFNet2", "rnnoise" to "RNNoise", "none" to stringResource(R.string.settings_noise_none)).forEachIndexed { index, (mode, label) ->
                        if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp), color = Palette.border)
                        val selected = ui.noiseSuppression == mode
                        val selectionState = stringResource(if (selected) R.string.selection_selected else R.string.selection_not_selected)
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { stateDescription = selectionState }.selectable(selected, interactionSource = null, indication = RowPressFeedback, role = Role.RadioButton) {
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
                if (ui.audioProcessingStatus == "switching") Text(stringResource(R.string.settings_audio_switching), color = Palette.muted)
                ui.audioProcessingError?.let { Text(it, fontSize = 13.sp, color = Palette.disconnect,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
            }
            SettingsEntry(stringResource(R.string.settings_language), stringResource(selectedLanguage.title), stringResource(R.string.accessibility_choose_language)) { onPage("language") }
            FileCacheSettings(ui)
            SettingsEntry(stringResource(R.string.settings_about)) { onPage("about") }

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
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).toggleable(checked, interactionSource = null, indication = null, enabled = enabled, role = Role.Switch) {
        haptic.performHapticFeedback(toggleHapticType(it))
        onChange(it)
    }, verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, lineHeight = 20.sp)
        Canvas(Modifier.size(63.dp, 28.dp).alpha(if (enabled) 1f else 0.5f)) {
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

internal fun detailNavigationTransition(entering: Boolean): ContentTransform {
    val timing = tween<IntOffset>(350, easing = FastOutSlowInEasing)
    return if (entering) {
        (slideInHorizontally(timing) { it } togetherWith
            slideOutHorizontally(timing) { -(it * .28f).toInt() }).apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(timing) { -(it * .28f).toInt() } togetherWith
            slideOutHorizontally(timing) { it }).apply { targetContentZIndex = 0f }
    }
}

@Composable
internal fun VoiceDrawer(ui: SessionUiState, requestPermissions: () -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, availableHeight: Dp, availableWidth: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val connected = ui.snapshot.status == "connected"
    val travel = with(density) { voiceDrawerTravel(availableHeight.toPx(), 64.dp.toPx()) }
    val limit = with(density) { minOf(16.dp.toPx(), (availableHeight.toPx() - 64.dp.toPx() - travel).coerceAtLeast(0f)) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val geometry = remember(availableHeight, availableWidth, density.density) { Any() }
    val session = remember(connected, geometry) { Any() }
    val currentSession by rememberUpdatedState(session)
    val currentExpanded by rememberUpdatedState(expanded)
    val changeExpanded by rememberUpdatedState(onExpandedChange)
    var height by remember { mutableFloatStateOf(if (connected && expanded) travel else 0f) }
    var held by remember { mutableStateOf(false) }
    var releaseVelocity by remember { mutableFloatStateOf(0f) }
    var settleRequest by remember { mutableIntStateOf(0) }
    var automaticallyClosing by remember { mutableStateOf(false) }
    val currentAutomaticClose by rememberUpdatedState(automaticallyClosing)
    var filesHeaderHeight by remember { mutableIntStateOf(0) }
    val containerOffset = with(density) { (availableHeight.toPx() - 64.dp.toPx()).coerceAtLeast(0f) }
    // A resized expansion anchor must not clip an automatic close or the user's next gesture.
    val maximumOffset = maxOf(travel + limit, height).coerceAtMost(containerOffset)
    var configuredSession by remember { mutableStateOf<Any?>(null) }
    var configuredGeometry by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(session, expanded, held, settleRequest) {
        if (configuredSession !== session) {
            val resized = configuredGeometry !== geometry
            val initial = configuredGeometry == null
            configuredSession = session
            configuredGeometry = geometry
            held = false
            releaseVelocity = 0f
            if (initial || (resized && connected && !automaticallyClosing)) {
                automaticallyClosing = false
                height = if (connected && expanded) travel else 0f
                return@LaunchedEffect
            }
            // A connection change invalidates gestures, but keeps the visible position for closing.
            automaticallyClosing = !connected || automaticallyClosing
        }
        if (!held) {
            if (connected && expanded) automaticallyClosing = false
            height = height.coerceIn(-limit, maximumOffset)
            val target = if (connected && expanded) travel else 0f
            if (abs(target - height) <= with(density) { .5.dp.toPx() }) {
                height = target
                automaticallyClosing = false
                return@LaunchedEffect
            }
            val maxVelocity = minOf(with(density) { 1500.dp.toPx() }, maxOf(with(density) { 80.dp.toPx() }, abs(target - height) * 8f))
            AnimationState(height, initialVelocity = (-releaseVelocity).coerceIn(-maxVelocity, maxVelocity)).animateTo(
                target, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow,
                    visibilityThreshold = with(density) { .5.dp.toPx() }),
            ) {
                if (currentSession === session && currentExpanded == expanded && !held) height = value.coerceIn(-limit, maximumOffset)
            }
            if (currentSession === session && currentExpanded == expanded && !held) {
                releaseVelocity = 0f
                automaticallyClosing = false
            }
        }
    }
    val drag = Modifier.pointerInput(session) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Measure through the file toolbar divider; content below it keeps its own scrolling.
            if (!connected || travel == 0f || (down.position.y >= with(density) { 64.dp.toPx() } + filesHeaderHeight)) return@awaitEachGesture
            val closingOnPress = currentAutomaticClose
            if (!closingOnPress) {
                held = true
                releaseVelocity = 0f
                settleRequest++
            }
            val tracker = VelocityTracker()
            var position = Offset.Zero
            tracker.addPosition(down.uptimeMillis, position)
            if (!closingOnPress) height = height.coerceIn(-limit, containerOffset)
            var dragTravel = if (height > travel + limit) height else travel
            var raw = voiceDrawerRawOffset(height, dragTravel, limit)
            var dragging = false
            var released = false
            try {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    val delta = change.positionChange()
                    position += delta
                    // Accumulated deltas keep the velocity tracker independent of the moving drawer's local origin.
                    tracker.addPosition(change.uptimeMillis, position)
                    if (!dragging) {
                        if (abs(position.x) > touchSlop && abs(position.x) > abs(position.y)) break
                        if (abs(position.y) > touchSlop) {
                            if (closingOnPress) {
                                held = true
                                automaticallyClosing = false
                                dragTravel = maxOf(travel, height)
                                raw = voiceDrawerRawOffset(height, dragTravel, limit)
                            }
                            dragging = true
                            raw -= position.y
                        }
                    } else raw -= delta.y
                    if (dragging) {
                        height = voiceDrawerOffset(raw, dragTravel, limit)
                        change.consume() // Initial pass cancels a child button's press before it can click.
                    }
                    if (!change.pressed) { released = true; break }
                }
            } finally {
                if (currentSession === session && (!closingOnPress || dragging)) {
                    releaseVelocity = if (dragging && released) tracker.calculateVelocity().y else 0f
                    if (dragging && released) changeExpanded(voiceDrawerShouldExpand(height, travel, releaseVelocity))
                    held = false
                    settleRequest++ // Also settle taps/cancellations or drags returning to the same anchor.
                }
            }
        }
    }
    val label = stringResource(if (expanded && connected) R.string.voice_drawer_collapse else R.string.voice_drawer_expand)
    val state = stringResource(if (expanded && connected) R.string.voice_drawer_expanded else R.string.voice_drawer_collapsed)
    val visibleOffset = height.coerceIn(-limit, maximumOffset)
    // Also clip overscroll to the available container, so it cannot cover or capture navigation.
    Box(modifier.fillMaxWidth().height(availableHeight).clipToBounds()) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .offset { IntOffset(0, (-minOf(0f, visibleOffset)).roundToInt()) }
            .height((64.dp + with(density) { maxOf(0f, visibleOffset).toDp() }).coerceAtMost(availableHeight))
            .background(Palette.bottom).clipToBounds().testTag("voice-drawer").then(drag)) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    VoiceBar(ui, requestPermissions)
                }
                Box(Modifier.align(Alignment.TopCenter).size(48.dp)
                    .clickable(interactionSource = null, indication = RowPressFeedback, enabled = connected && travel > 0f, role = Role.Button, onClickLabel = label) { changeExpanded(!currentExpanded) }
                    .semantics { contentDescription = label; stateDescription = state }
                    .padding(top = 10.dp), contentAlignment = Alignment.TopCenter) {
                    Icon(UiIcons.DrawerHandle, null, Modifier.size(44.dp, 4.dp), tint = if (connected) Palette.text else Palette.muted)
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (visibleOffset > 0f) {
                    ChannelFileTools(ui, Modifier.onSizeChanged { filesHeaderHeight = it.height })
                }
            }
        }
    }
}

internal fun voiceDrawerTravel(availableHeight: Float, collapsedHeight: Float): Float =
    (availableHeight * 7f / 10f - collapsedHeight).coerceAtLeast(0f)

internal fun voiceDrawerShouldExpand(height: Float, travel: Float, velocity: Float): Boolean =
    travel > 0f && height.coerceIn(0f, travel) - velocity * .2f > travel / 2f

internal fun voiceDrawerOffset(raw: Float, travel: Float, limit: Float): Float {
    val clamped = raw.coerceIn(0f, travel)
    val overflow = raw - clamped
    return if (limit > 0f) clamped + overflow * .35f / (1f + abs(overflow) * .35f / limit) else clamped
}

internal fun voiceDrawerRawOffset(offset: Float, travel: Float, limit: Float): Float {
    val clamped = offset.coerceIn(0f, travel)
    val overflow = offset - clamped
    return if (limit > 0f) clamped + overflow / (.35f * (1f - minOf(.999f, abs(overflow) / limit))) else clamped
}

@Composable
private fun VoiceBar(ui: SessionUiState, requestPermissions: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val connected = ui.snapshot.status == "connected"
    val ownClient = ui.snapshot.clients.firstOrNull { it.id == ui.snapshot.ownClient }
    val channel = ownClient?.channel
    val muted = ui.microphoneMuted || ui.deafened
    Row(Modifier.fillMaxWidth().background(Palette.bottom).padding(end = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).height(48.dp).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(UiIcons.Wave, null, Modifier.size(18.dp), tint = if (connected) ui.snapshot.networkQuality?.iconGrade.networkColor() else Palette.muted)
            Text(if (connected) stringResource(R.string.status_connected_to_channel, ui.snapshot.channels.firstOrNull { it.id == channel }?.name.orEmpty()) else if (ui.snapshot.status in listOf("connecting", "reconnecting")) stringResource(R.string.status_connecting) else stringResource(R.string.status_disconnected), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PressFeedback(AudioPressFeedback) { source, feedback, _ ->
            IconButton(onClick = {
                haptic.performHapticFeedback(toggleHapticType(ui.microphoneMuted))
                if (ui.microphoneMuted && !ClientSession.microphonePermission) requestPermissions()
                else ClientSession.setAudio(inputMuted = !ui.microphoneMuted)
            }, enabled = !ui.deafened, interactionSource = source, modifier = Modifier.size(44.dp, 48.dp).then(feedback)) {
                Icon(if (muted) UiIcons.MicOff else UiIcons.Mic, stringResource(if (muted) R.string.voice_enable_microphone else R.string.voice_mute), Modifier.size(20.dp).alpha(if (ui.deafened) 0.5f else 1f), tint = if (muted) Palette.muted else if (connected && ownClient?.speaking == true) Palette.green else Palette.text)
            }
        }
        PressFeedback(AudioPressFeedback) { source, feedback, _ ->
            IconButton(onClick = {
                haptic.performHapticFeedback(toggleHapticType(ui.deafened))
                ClientSession.setAudio(deafened = !ui.deafened)
            }, interactionSource = source, modifier = Modifier.size(44.dp, 48.dp).then(feedback)) {
                Icon(if (ui.deafened) UiIcons.SpeakerOff else UiIcons.Speaker, stringResource(if (ui.deafened) R.string.voice_enable_listening else R.string.voice_disable_listening), Modifier.size(22.dp), tint = if (ui.deafened) Palette.muted else Palette.text)
            }
        }
    }
}

@Composable
internal fun ChatScreen(target: ChatTarget, ui: SessionUiState, active: Boolean, requestPermissions: () -> Unit = {}, onBack: () -> Unit) {
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
    val livePeer = ui.snapshot.onlineMember(target.member?.uid)
    val peer = livePeer ?: target.member
    Column(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).navigationBarsPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp)) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).size(44.dp)) {
                Icon(UiIcons.Back, stringResource(R.string.action_back), Modifier.size(24.dp), tint = Palette.text)
            }
            Row(Modifier.align(Alignment.Center).padding(horizontal = 52.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                peer?.let { Avatar(it.name, livePeer?.avatarPath ?: target.member?.avatarPath, livePeer?.speaking == true, 30, livePeer, accessible = false) }
                val description = livePeer?.accessibilityLabel(context) ?: peer?.name ?: target.title
                Text(peer?.name ?: target.title, Modifier.clearAndSetSemantics { contentDescription = description }, fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val bubbleWidth = (maxWidth - 28.dp - 38.dp - 46.dp).coerceAtLeast(40.dp)
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages, key = { it.id }) { message ->
                    val sender = ui.snapshot.onlineMember(message.senderUid)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.own) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                        if (!message.own) {
                            Avatar(message.senderName, sender?.avatarPath ?: message.avatarPath, sender?.speaking == true, 30, sender, accessible = target.channel == null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Column(Modifier.widthIn(max = bubbleWidth), horizontalAlignment = if (message.own) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (target.channel != null && !message.own) {
                                val description = sender?.accessibilityLabel(context) ?: message.senderName
                                Text(message.senderName, Modifier.clearAndSetSemantics { contentDescription = description }, fontSize = 12.sp, lineHeight = 14.sp, color = Palette.muted)
                            }
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
                            Avatar(message.senderName, sender?.avatarPath ?: message.avatarPath, sender?.speaking == true, 30, sender)
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = Palette.border, thickness = .5.dp)
        PushToTalkBar(ui, requestPermissions, active = active, showTarget = true)
        Row(Modifier.fillMaxWidth().background(Palette.bottom).padding(10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BasicTextField(input, { input = it }, Modifier.weight(1f).heightIn(min = 34.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black)
                .then(if (input.toByteArray().size > 8192) Modifier.border(1.dp, Palette.disconnect, RoundedCornerShape(5.dp)) else Modifier).padding(horizontal = 7.dp, vertical = 6.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 17.sp, lineHeight = 20.sp), cursorBrush = SolidColor(Palette.accent), maxLines = 4,
                decorationBox = { field -> Box { if (input.isEmpty()) Text(stringResource(R.string.chat_input_placeholder), color = Color(0xFF646468), fontSize = 17.sp, lineHeight = 20.sp); field() } })
            PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                IconButton(interactionSource = source, enabled = valid, onClick = {
                    val sent = target.channel?.let { ClientSession.sendChannelMessage(it, input) }
                        ?: target.member?.let { ClientSession.sendPrivateMessage(it, input) } ?: false
                    if (sent) input = ""
                }, modifier = Modifier.size(38.dp).then(feedback)) {
                    Icon(UiIcons.Send, stringResource(R.string.action_send), Modifier.size(22.dp), tint = if (valid) Palette.text else Palette.muted)
                }
            }
        }
    }
}

@Composable
internal fun RowScope.NavigationItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, unread: Int, onClick: () -> Unit) {
    val color = if (selected) Palette.accent else Palette.muted
    Column(Modifier.weight(1f).heightIn(min = 44.dp).selectable(selected, interactionSource = null, indication = ToolbarPressFeedback, role = Role.Tab, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box {
            Icon(icon, null, Modifier.size(22.dp), tint = color)
            UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-7).dp))
        }
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

@Composable
internal fun Avatar(name: String, path: String?, speaking: Boolean, size: Int, member: Member? = null, accessible: Boolean = true) {
    val description = member?.accessibilityLabel(LocalContext.current) ?: name
    val away = member?.away == true
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .clearAndSetSemantics { if (accessible) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().then(if (away) Modifier.drawWithCache {
            val paint = Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
            onDrawWithContent {
                drawContext.canvas.saveLayer(Rect(Offset.Zero, this.size), paint)
                drawContent()
                drawContext.canvas.restore()
                drawRect(Color.Black.copy(alpha = .75f))
            }
        } else Modifier).background(Palette.accent), contentAlignment = Alignment.Center) {
            if (member?.statusIcon == null) Text(name.take(2).uppercase(), fontWeight = FontWeight.Bold,
                fontSize = with(LocalDensity.current) { (size * .36).dp.toSp() },
                lineHeight = with(LocalDensity.current) { (size * .36).dp.toSp() })
            LocalImage(path, Modifier.fillMaxSize())
        }
        member?.statusIcon?.let { icon ->
            Icon(when (icon) { "afk" -> UiIcons.Afk; "speaker-off" -> UiIcons.SpeakerOff; else -> UiIcons.MicOff }, null,
                Modifier.size((if (size <= 24) 14 else if (size <= 30) 18 else 20).dp), tint = if (away) Palette.disconnect else Palette.text)
        }
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
private fun LocalImage(path: String?, modifier: Modifier, description: String? = null, revision: Long = 0) {
    if (path == null) return
    val bitmap by produceState<ImageBitmap?>(null, path, revision) {
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
