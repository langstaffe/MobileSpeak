package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChannelFilesTest {
    @Test fun fullStateDecodesZeroBytesAndKeepsTransferContext() {
        val json = JSONObject("""{"open":true,"server":"fixture","channel":7,"channelName":"当前频道","path":"/文档","sort":"name","status":"ready","error":null,"entries":[{"name":"很长的中文文件名称.pdf","size":0,"timestamp":1700000000,"directory":false,"icon":"file-text","localPath":null}],"transfers":[{"id":1,"server":"fixture","channel":7,"path":"/文档","name":"很长的中文文件名称.pdf","upload":false,"size":0,"transferred":0,"status":"failed","error":"files_disconnected","localPath":null}]}""")
        val files = json.channelFiles()
        assertEquals(0L, files.entries.single().size)
        assertEquals(7L, files.channel)
        assertEquals("files_disconnected", files.transfer(files.entries.single().name)?.error)
        assertNull(files.transfer("other.txt"))
        assertEquals("/文档", files.transfer(files.entries.single().name)?.path)
        val command = ChannelFileTarget("fixture", 7, files.path).command()
        assertEquals("fixture", command.getString("server")); assertEquals(7L, command.getLong("channel"))
        assertEquals(files.path, command.getString("path")); assertEquals(files.path, command.getString("directory"))
    }

    @Test fun nativeListRendersAtNormalAndLargeFontsWithoutAnotherDrawer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val files = ChannelFilesState(open = true, server = "fixture", channel = 7, channelName = "当前实际加入的频道", path = "/文档/a very long folder path/子目录", status = "ready",
            entries = (0..40).map { ChannelFileEntry("很长的中文文件名称用于保留扩展名-$it.pdf", 0, 1700000000, false, "file-text", null) })
        for (language in listOf("en", "zh-Hans")) for (font in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var activity: MainActivity
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration)
            val density = context.resources.displayMetrics.density
            scenario.onActivity { current ->
                activity = current
                activity.setContent { CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources, LocalActivityResultRegistryOwner provides activity) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, surface = Palette.card, onSurface = Palette.text)) { Box(Modifier.width(320.dp).background(Palette.bottom)) {
                        ChannelFilesDrawer(SessionUiState(snapshot = Snapshot(status = "connected"), channelFiles = files))
                    } }
                } }
            }
            android.os.SystemClock.sleep(300); instrumentation.runOnMainSync { }
            fun semantics(): List<SemanticsNode> {
                var result = emptyList<SemanticsNode>()
                instrumentation.runOnMainSync {
                    fun root(view: android.view.View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { group ->
                        (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
                    }
                    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                    result = descendants(requireNotNull(root(activity.window.decorView)).semanticsOwner.rootSemanticsNode)
                }
                return result
            }
            fun texts(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            fun buttons(label: String) = semantics().filter { it.config.getOrNull(SemanticsActions.OnClick)?.action != null && label in texts(it) }
            fun enabled(node: SemanticsNode) = !node.config.contains(SemanticsProperties.Disabled)
            fun button(label: String) = requireNotNull(buttons(label).firstOrNull()) { "Missing $label" }
            val refresh = button(localized.getString(R.string.files_reload))
            val sorting = button(localized.getString(R.string.files_sort_name))
            val refreshBounds = refresh.boundsInRoot
            val sortBounds = sorting.boundsInRoot
            assertTrue(enabled(refresh))
            assertTrue(refreshBounds.right <= sortBounds.left)
            assertTrue(sortBounds.right <= 320 * density)
            assertTrue(refreshBounds.height >= 48 * density - 1)
            assertTrue(sortBounds.height >= 48 * density - 1)
            val headerBounds = semantics().first { files.channelName in texts(it) }.boundsInRoot
            fun tagged(value: String) = semantics().single { it.config.getOrNull(SemanticsProperties.TestTag) == value }.boundsInRoot
            val heading = tagged("drawer-page-title")
            val subtitle = tagged("drawer-page-subtitle")
            assertEquals(160 * density, heading.center.x, density)
            assertEquals(heading.center.x, subtitle.center.x, density)
            assertEquals(2 * density, subtitle.top - heading.bottom, density / 2)
            assertTrue(tagged("drawer-page-back").right <= heading.left)
            assertTrue(heading.right <= tagged("files-upload").left)
            assertTrue(semantics().any { texts(it).any { text -> text.contains(".pdf") } })
            val scroll = semantics().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            scenario.onActivity { assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!(0f, 200 * density)) }
            android.os.SystemClock.sleep(300)
            assertEquals(headerBounds, semantics().first { files.channelName in texts(it) }.boundsInRoot)
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            assertTrue(shot.width > 0 && shot.height > 0)
            java.io.File(context.getExternalFilesDir(null), "channel-files-$language-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            shot.recycle()
            for ((status, connected) in listOf("ready" to true, "loading" to true, "failed" to true, "failed" to false)) {
                instrumentation.runOnMainSync {
                    activity.setContent { CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources, LocalActivityResultRegistryOwner provides activity) {
                        MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, surface = Palette.card, onSurface = Palette.text)) { Box(Modifier.width(320.dp).background(Palette.bottom)) {
                            ChannelFilesDrawer(SessionUiState(snapshot = Snapshot(status = if (connected) "connected" else "disconnected"),
                                channelFiles = files.copy(path = if (status == "ready") "/" else files.path, sort = "newest", status = status, entries = emptyList())))
                        } }
                    } }
                }
                android.os.SystemClock.sleep(300); instrumentation.runOnMainSync { }
                val refreshes = buttons(localized.getString(R.string.files_reload))
                assertEquals(if (status == "failed") 2 else 1, refreshes.size)
                assertTrue(refreshes.all { enabled(it) == (connected && status != "loading") })
                assertTrue(enabled(button(localized.getString(R.string.files_sort_newest))))
                val uploads = buttons(localized.getString(R.string.files_upload))
                assertEquals("Only the header offers upload, including an empty directory", 1, uploads.size)
                assertEquals(connected && status == "ready", enabled(uploads.single()))
                if (status == "ready") assertTrue(semantics().any { localized.getString(R.string.files_empty) in texts(it) })
                if (status == "loading") {
                    val all = semantics()
                    val list = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "channel-files-list" }.boundsInRoot
                    val text = all.first { localized.getString(R.string.files_loading) in texts(it) }.boundsInRoot
                    val spinner = all.first { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.boundsInRoot
                    assertEquals("Loading indicator centers at $language/$font", list.center.x, spinner.center.x, density)
                    assertEquals("Loading label centers at $language/$font", list.center.x, text.center.x, density)
                    assertEquals("Loading stays at the top of the list", list.top + 24 * density, minOf(spinner.top, text.top), density)
                    assertTrue("Loading label follows the native indicator", text.top >= spinner.bottom)
                    val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    java.io.File(context.getExternalFilesDir(null), "channel-files-loading-$language-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    shot.recycle()
                }
            }
        }
    }

    @Test fun actualDrawerUsesFileHeaderBoundsAndCancelsButtonClicksDuringDrag() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        org.junit.Assume.assumeFalse(context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        ClientSession.initialize(context)
        org.junit.Assume.assumeTrue("Use an offline profile", !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        val density = context.resources.displayMetrics.density
        val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("zh-Hans")) }
        val localized = context.createConfigurationContext(configuration)
        val permission = ClientSession.microphonePermission
        val language = AppLanguage.selected()
        val oldError = ClientSession.state.value.channelFilesLocalError
        val pickerLaunches = java.util.concurrent.atomic.AtomicInteger()
        val permissions = java.util.concurrent.atomic.AtomicInteger()
        androidx.test.runner.intent.IntentStubberRegistry.load(object : androidx.test.runner.intent.IntentStubber {
            override fun getActivityResultForIntent(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.action != android.content.Intent.ACTION_OPEN_DOCUMENT) return null
                pickerLaunches.incrementAndGet()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null)
            }
        })
        try {
            instrumentation.runOnMainSync { AppLanguage.select(AppLanguage.SIMPLIFIED_CHINESE) }
            ClientSession.setMicrophonePermission(false)
            for (font in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val files = ChannelFilesState(open = true, server = "fixture", channel = 7, channelName = "Scroll channel 20", path = "/", status = "ready",
                    entries = (0..40).map { ChannelFileEntry("File %02d.pdf".format(it), 0, 1700000000, false, "file-text", null) })
                var ui by mutableStateOf(SessionUiState(snapshot = Snapshot(status = "connected", ownClient = 1,
                    channels = listOf(Channel(7, 0, 0, "Scroll channel 20", false, true, "fixture", null)),
                    clients = listOf(Member(1, 7, "fixture", "Fixture", null, emptyList(), emptyList(), null, false, false, false)), networkQuality = NetworkQuality(iconGrade = NetworkGrade.GOOD)),
                    microphoneMuted = true, channelFiles = files))
                var expanded by mutableStateOf(true)
                lateinit var activity: MainActivity
                scenario.onActivity { current -> activity = current; activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides configuration, LocalResources provides localized.resources, LocalActivityResultRegistryOwner provides activity) {
                        MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, surface = Palette.card, onSurface = Palette.text)) {
                            CompositionLocalProvider(LocalContentColor provides Palette.text, LocalTextStyle provides TextStyle(fontSize = 17.sp, lineHeight = 20.sp)) {
                                Box(Modifier.width(320.dp).height(568.dp).background(Palette.background).semantics { testTagsAsResourceId = true }) {
                                    VoiceDrawer(ui, { permissions.incrementAndGet() }, expanded, { expanded = it }, 520.dp, 320.dp,
                                        Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    }
                } }
                // Loading indicators keep animating; synchronize with the main thread without waiting for idle.
                fun settle() { android.os.SystemClock.sleep(650); instrumentation.runOnMainSync { } }
                fun nodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
                    val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (node == null || !node.refresh()) return
                        if (node.isVisibleToUser) result += node
                        for (i in 0 until node.childCount) collect(node.getChild(i))
                    }
                    val roots = instrumentation.uiAutomation.windows.mapNotNull { it.root }.filter { it.packageName == context.packageName }
                    (roots.ifEmpty { listOfNotNull(instrumentation.uiAutomation.rootInActiveWindow) }).forEach(::collect)
                    return result
                }
                fun label(node: android.view.accessibility.AccessibilityNodeInfo) = node.contentDescription?.toString() ?: node.text?.toString().orEmpty()
                fun bounds(node: android.view.accessibility.AccessibilityNodeInfo) = android.graphics.Rect().also(node::getBoundsInScreen)
                fun node(resource: Int): android.view.accessibility.AccessibilityNodeInfo {
                    val expected = localized.getString(resource)
                    val deadline = android.os.SystemClock.uptimeMillis() + 3000
                    var all = nodes()
                    while (all.none { label(it) == expected } && android.os.SystemClock.uptimeMillis() < deadline) {
                        android.os.SystemClock.sleep(50); all = nodes()
                    }
                    return requireNotNull(all.firstOrNull { label(it) == expected }) { "Missing $expected: ${all.map(::label)}" }
                }
                fun top() = bounds(node(R.string.voice_drawer_collapse)).top.toFloat()
                fun listBounds() = bounds(requireNotNull(nodes().firstOrNull { it.viewIdResourceName == "channel-files-list" }))
                fun touch(x: Float, y: Float, dx: Float = 0f, dy: Float = 0f, cancel: Boolean = false, pressed: () -> Unit = {}, moved: () -> Unit = {}) {
                    val down = android.os.SystemClock.uptimeMillis()
                    fun send(action: Int, px: Float, py: Float) {
                        val event = android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, px, py, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                    send(android.view.MotionEvent.ACTION_DOWN, x, y)
                    try {
                        instrumentation.runOnMainSync { }; pressed()
                        for (step in 1..6) {
                            android.os.SystemClock.sleep(24)
                            send(android.view.MotionEvent.ACTION_MOVE, x + dx * step / 6, y + dy * step / 6)
                        }
                        android.os.SystemClock.sleep(40) // Observe the layout after the last pointer event's frame.
                        instrumentation.runOnMainSync { }; moved()
                    } finally {
                        android.os.SystemClock.sleep(100)
                        send(if (cancel) android.view.MotionEvent.ACTION_CANCEL else android.view.MotionEvent.ACTION_UP, x + dx, y + dy)
                    }
                    settle()
                }
                fun checkDrag(x: Float, y: Float, cancel: Boolean = false) {
                    val originalTop = top()
                    val launches = pickerLaunches.get(); val requested = permissions.get()
                    ClientSession.fileLocalError(null)
                    touch(x, y, dy = 24 * density, cancel = cancel) {
                        assertEquals("Header follows the finger at font $font", originalTop + 24 * density, top(), 2 * density)
                    }
                    assertEquals(originalTop, top(), density)
                    assertEquals(launches, pickerLaunches.get()); assertEquals(requested, permissions.get())
                    assertNull("Dragging must not submit a file command", ClientSession.state.value.channelFilesLocalError)
                    assertFalse(nodes().any { label(it) == localized.getString(R.string.files_sort_newest) })
                }
                settle()
                fun tagged(tag: String): SemanticsNode {
                    fun root(view: android.view.View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { group ->
                        (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) }
                    }
                    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                    return descendants(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
                        .single { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
                }
                fun header() = tagged("channel-files-header")
                fun sizes(node: SemanticsNode): List<androidx.compose.ui.unit.IntSize> =
                    node.children.flatMap { listOf(it.size) + sizes(it) }
                var headerHeight = 0
                var headerParts = emptyList<androidx.compose.ui.unit.IntSize>()
                instrumentation.runOnMainSync { headerHeight = header().size.height; headerParts = sizes(header()) }
                for (fraction in listOf(.2f, .75f, 1.5f)) {
                    val originalTop = top()
                    val launches = pickerLaunches.get()
                    // Header children keep their intrinsic dimensions even when only their top is revealed.
                    touch(160 * density, originalTop + 24 * density, dy = 300 * density - headerHeight * fraction, cancel = true) {
                        instrumentation.runOnMainSync {
                            assertEquals("The drag boundary uses the full header at font $font", headerHeight, header().size.height)
                            assertEquals("Header controls and text must not compress", headerParts, sizes(header()))
                        }
                        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        java.io.File(context.getExternalFilesDir(null), "drawer-files-reveal-$font-$fraction.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        shot.recycle()
                    }
                    assertEquals(originalTop, top(), density)
                    assertEquals(launches, pickerLaunches.get())
                }
                for (resource in listOf(R.string.voice_enable_microphone, R.string.action_back, R.string.files_upload, R.string.files_reload, R.string.files_sort_name)) {
                    val frame = bounds(node(resource))
                    checkDrag(frame.exactCenterX(), frame.exactCenterY())
                }
                // Both horizontal edges, all three rows, and the last pixel above content are draggable.
                val title = bounds(node(R.string.files_title))
                val toolbar = bounds(node(R.string.files_reload))
                for (x in listOf(density, 319 * density)) for (y in listOf(top() + 40 * density, title.exactCenterY(), toolbar.exactCenterY(), listBounds().top - density)) {
                    checkDrag(x, y, cancel = true)
                }
                val originalTop = top()
                val originalFiles = nodes().map(::label).filter { it.startsWith("File ") }
                touch(160 * density, listBounds().top + density, dy = -90 * density) { assertEquals(originalTop, top(), density) }
                assertEquals(originalTop, top(), density)
                assertNotEquals("Content swipe must scroll rows", originalFiles, nodes().map(::label).filter { it.startsWith("File ") })
                var scrollOffset = 0f
                instrumentation.runOnMainSync { scrollOffset = tagged("channel-files-list").config[SemanticsProperties.VerticalScrollAxisRange].value() }
                touch(160 * density, top() + 24 * density, dy = 300 * density - headerHeight * .2f, cancel = true) {
                    instrumentation.runOnMainSync {
                        val list = tagged("channel-files-list")
                        assertEquals("The clipped header leaves a zero-height list viewport", 0, list.size.height)
                        assertEquals("A zero-height list keeps its scroll position", scrollOffset, list.config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
                    }
                }
                instrumentation.runOnMainSync { assertEquals("Revealing the list restores its scroll position", scrollOffset, tagged("channel-files-list").config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f) }
                val refresh = bounds(node(R.string.files_reload))
                touch(refresh.exactCenterX(), refresh.exactCenterY(), dx = 30 * density, cancel = true) { assertEquals(originalTop, top(), density) }
                val upload = bounds(node(R.string.files_upload)); val launches = pickerLaunches.get()
                touch(upload.exactCenterX(), upload.exactCenterY())
                assertEquals("Upload tap still launches the native picker", launches + 1, pickerLaunches.get())
                val microphone = bounds(node(R.string.voice_enable_microphone)); val requested = permissions.get()
                touch(microphone.exactCenterX(), microphone.exactCenterY())
                assertEquals(requested + 1, permissions.get())
                touch(toolbar.exactCenterX(), toolbar.exactCenterY())
                assertNotNull("Refresh tap still submits its command", ClientSession.state.value.channelFilesLocalError)
                val sort = bounds(node(R.string.files_sort_name))
                touch(sort.exactCenterX(), sort.exactCenterY())
                assertTrue("Sort menu: ${nodes().map(::label)}", nodes().any { label(it) == localized.getString(R.string.files_sort_newest) })
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); settle()
                val row = bounds(requireNotNull(nodes().firstOrNull { label(it).startsWith("File ") }))
                touch(row.exactCenterX(), row.exactCenterY())
                assertNotNull(node(R.string.files_type))
                touch(160 * density, listBounds().top + density, dy = -24 * density) { assertEquals(originalTop, top(), density) }
                val back = bounds(node(R.string.action_back))
                ClientSession.fileLocalError(null)
                touch(back.exactCenterX(), back.exactCenterY())
                assertFalse(nodes().any { label(it) == localized.getString(R.string.files_type) })
                assertNull("Back from information must not navigate the directory", ClientSession.state.value.channelFilesLocalError)
                instrumentation.runOnMainSync { ui = ui.copy(microphoneMuted = false) }; settle()
                for (status in listOf("ready", "loading", "failed")) {
                    instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files.copy(status = status, entries = emptyList())) }; settle()
                    val content = listBounds()
                    touch(160 * density, content.top + 60 * density, dy = -24 * density) { assertEquals(originalTop, top(), density) }
                    assertEquals(originalTop, top(), density)
                    assertEquals(1, nodes().count { label(it) == localized.getString(R.string.files_upload) })
                    if (status != "ready") {
                        val disabledUpload = bounds(node(R.string.files_upload)); val beforeTap = pickerLaunches.get()
                        touch(disabledUpload.exactCenterX(), disabledUpload.exactCenterY())
                        assertEquals("Disabled upload must not launch the picker", beforeTap, pickerLaunches.get())
                    }
                    checkDrag(319 * density, listBounds().top - density, cancel = true)
                    if (status == "ready") {
                        assertNotNull(node(R.string.files_empty))
                        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                        java.io.File(context.getExternalFilesDir(null), "drawer-files-empty-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        val crop = android.graphics.Bitmap.createBitmap(shot, 0, originalTop.toInt(), (320 * density).toInt(), (364 * density).toInt())
                        java.io.File(context.getExternalFilesDir(null), "drawer-files-empty-crop-$font.png").outputStream().use { crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        crop.recycle()
                        shot.recycle()
                    }
                }
                instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files.copy(path = "/parent/child")) }; settle()
                checkDrag(160 * density, bounds(node(R.string.files_title)).exactCenterY())
                instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files.copy(path = "/parent")) }; settle()
                checkDrag(160 * density, bounds(node(R.string.files_title)).exactCenterY())
                instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files.copy(open = false)) }; settle()
                checkDrag(319 * density, top() + 90 * density)
                instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files); expanded = false }
                android.os.SystemClock.sleep(80)
                instrumentation.runOnMainSync { ui = ui.copy(snapshot = ui.snapshot.copy(status = "disconnected"), channelFiles = files.copy(open = false)) }
                settle()
                assertFalse(node(R.string.voice_drawer_expand).isEnabled)
                instrumentation.runOnMainSync { ui = ui.copy(snapshot = ui.snapshot.copy(status = "connected")); expanded = true }; settle()
                assertTrue(node(R.string.voice_drawer_collapse).isEnabled)
                instrumentation.runOnMainSync { ui = ui.copy(channelFiles = files); expanded = false }; settle()
                instrumentation.runOnMainSync { expanded = true }
                android.os.SystemClock.sleep(150); instrumentation.runOnMainSync { }
                var openingTop = 0f
                val openingUpload = bounds(node(R.string.files_upload)); val beforeOpeningDrag = pickerLaunches.get()
                touch(openingUpload.exactCenterX(), openingUpload.exactCenterY(), dy = 24 * density, pressed = { openingTop = top() }) {
                    assertEquals("A press stops the spring and a drag resumes from that position", openingTop + 24 * density, top(), 4 * density)
                }
                assertEquals(beforeOpeningDrag, pickerLaunches.get())
                instrumentation.runOnMainSync { expanded = true }; settle()
                val disconnectUpload = bounds(node(R.string.files_upload))
                touch(disconnectUpload.exactCenterX(), disconnectUpload.exactCenterY(), dy = 24 * density) {
                    instrumentation.runOnMainSync { ui = ui.copy(snapshot = ui.snapshot.copy(status = "disconnected"), channelFiles = files.copy(open = false)); expanded = false }
                }
                assertFalse(node(R.string.voice_drawer_expand).isEnabled)
                assertEquals(beforeOpeningDrag, pickerLaunches.get())
            }
        } finally {
            androidx.test.runner.intent.IntentStubberRegistry.reset()
            ClientSession.setMicrophonePermission(permission); ClientSession.fileLocalError(oldError)
            instrumentation.runOnMainSync { AppLanguage.select(language) }
        }
    }
}
