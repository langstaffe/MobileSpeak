package dev.mobilespeak.mobilespeak

import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UserVolumeTest {
    @Test fun bridgeDefaultsAndTokens() {
        val member = """{"id":7,"channel":9,"uid":"stable","name":"User","avatarPath":null,"badges":[],"serverGroupIcons":[],"channelGroupIcon":null,"muted":false,"deafened":false,"speaking":false}"""
        val old = JSONObject("""{"status":"connected","channels":[],"clients":[$member]}""").snapshot()
        assertEquals(100, old.clients.single().volumePercent); assertNull(old.clients.single().volumeToken)
        val current = JSONObject(member).put("volumePercent",143).put("volumeToken","instance-7")
        val saved = JSONObject().put("status","connected").put("channels",org.json.JSONArray()).put("clients",org.json.JSONArray().put(current)).snapshot()
        assertEquals(143,saved.clients.single().volumePercent); assertEquals("instance-7",saved.clients.single().volumeToken)
        current.put("volumePercent",300)
        assertEquals(300, JSONObject().put("status","connected").put("channels",org.json.JSONArray()).put("clients",org.json.JSONArray().put(current)).snapshot().clients.single().volumePercent)
        assertTrue(SessionUiState().receiveAgc)
    }

    @Test fun settingsPlaceAgcBetweenListeningAndNoiseWithoutChangingAudio() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ClientSession.initialize(context)
        val before = ClientSession.state.value
        org.junit.Assume.assumeTrue("Requires offline test profile", before.snapshot.status == "disconnected")
        val density = context.resources.displayMetrics.density
        for (language in listOf("en", "zh-Hans")) for (font in listOf(1f, 2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(config)
            lateinit var activity: MainActivity
            scenario.onActivity { a -> activity = a; a.setContent {
                CompositionLocalProvider(LocalDensity provides Density(density, font), LocalContext provides localized, LocalConfiguration provides config, LocalResources provides localized.resources, LocalActivityResultRegistryOwner provides activity) {
                    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, surface = Palette.card, onSurface = Palette.text)) {
                        CompositionLocalProvider(LocalContentColor provides Palette.text, LocalTextStyle provides TextStyle(fontSize = 17.sp, lineHeight = 20.sp)) {
                            Box(Modifier.width(320.dp).height(568.dp).background(Palette.background)) {
                                SettingsScreen(SessionUiState(fileImporting = true), {}, {})
                            }
                        }
                    }
                }
            } }
            android.os.SystemClock.sleep(300)
            fun nodes(): List<SemanticsNode> {
                lateinit var result: List<SemanticsNode>
                instrumentation.runOnMainSync {
                    fun root(view: android.view.View): ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { (0 until it.childCount).firstNotNullOfOrNull { i -> root(it.getChildAt(i)) } }
                    fun all(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::all)
                    result = all(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
                }
                return result
            }
            fun label(id: Int) = nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == localized.getString(id) } == true }
            val listening = label(R.string.settings_listening)
            val agc = label(R.string.settings_receive_agc)
            val help = label(R.string.settings_receive_agc_help)
            val noise = label(R.string.settings_noise_suppression)
            assertTrue(listening.positionInRoot.y < agc.positionInRoot.y)
            assertTrue(agc.positionInRoot.y < help.positionInRoot.y)
            assertTrue(help.positionInRoot.y < noise.positionInRoot.y)
            val scroll = nodes().single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            instrumentation.runOnMainSync { scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, (listening.positionInRoot.y - 50 * density).coerceAtLeast(0f)) }
            android.os.SystemClock.sleep(300)
            val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            java.io.File(context.getExternalFilesDir(null), "receive-agc-settings-$language-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; shot.recycle()
        }
        assertEquals(before.microphoneMuted, ClientSession.state.value.microphoneMuted)
        assertEquals(before.deafened, ClientSession.state.value.deafened)
        assertEquals(before.receiveAgc, ClientSession.state.value.receiveAgc)
        assertEquals("disconnected", ClientSession.state.value.snapshot.status)
    }

    @Test fun drawerHeightScrollingAndSliderGestures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ClientSession.initialize(context)
        org.junit.Assume.assumeTrue("Requires offline test profile",ClientSession.state.value.snapshot.status == "disconnected")
        val members = (1..41).map { Member(it.toLong(),if(it%2==0) 9 else 7,"fixture-$it",if(it==1) "SELF MUST BE EXCLUDED" else "User $it",null,emptyList(),emptyList(),null,false,false,false,if(it==2) 143 else 100,"instance-$it") }
        val density = context.resources.displayMetrics.density
        ClientSession.closeUserVolumes()
        try {
            for (language in listOf("en","zh-Hans")) for(font in listOf(1f,2f)) ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val config = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)) }
                val localized = context.createConfigurationContext(config)
                var fixtureOpen by mutableStateOf(false)
                var fixture by mutableStateOf(Snapshot(status="connected",server="Fixture server",serverId="fixture",ownClient=1,clients=members))
                lateinit var activity: MainActivity
                scenario.onActivity { a -> activity=a; a.setContent {
                    val live by ClientSession.state.collectAsState()
                    CompositionLocalProvider(LocalDensity provides Density(density,font),LocalContext provides localized,LocalConfiguration provides config,LocalResources provides localized.resources) {
                        MaterialTheme(colorScheme=darkColorScheme(primary=Palette.accent,surface=Palette.card,onSurface=Palette.text)) {
                            CompositionLocalProvider(LocalContentColor provides Palette.text,LocalTextStyle provides TextStyle(fontSize=17.sp,lineHeight=20.sp)) {
                                Box(Modifier.width(320.dp).height(568.dp).background(Palette.background).semantics { testTagsAsResourceId=true }) {
                                    VoiceDrawer(live.copy(snapshot=fixture,microphoneMuted=true,userVolumesOpen=fixtureOpen),{},true,{},520.dp,320.dp,Modifier.align(Alignment.BottomCenter))
                                }
                            }
                        }
                    }
                } }
                fun settle() { android.os.SystemClock.sleep(500); instrumentation.runOnMainSync {} }
                fun nodes(): List<SemanticsNode> {
                    lateinit var result: List<SemanticsNode>
                    instrumentation.runOnMainSync {
                        fun root(view:android.view.View):ViewRootForTest? = (view as? ViewRootForTest) ?: (view as? android.view.ViewGroup)?.let { (0 until it.childCount).firstNotNullOfOrNull { i -> root(it.getChildAt(i)) } }
                        fun all(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::all)
                        result=all(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode)
                    }
                    return result
                }
                fun tag(value:String)=nodes().single { it.config.getOrNull(SemanticsProperties.TestTag)==value }
                fun click(value:String) { val action=tag(value).config[SemanticsActions.OnClick].action!!; instrumentation.runOnMainSync { assertTrue(action.invoke()); fixtureOpen=value=="user-volumes-tool" }; settle() }
                settle()
                val original=tag("voice-drawer").boundsInRoot
                click("user-volumes-tool")
                assertEquals(original,tag("voice-drawer").boundsInRoot)
                val heading = tag("drawer-page-title").boundsInRoot
                val subtitle = tag("drawer-page-subtitle").boundsInRoot
                assertEquals(original.center.x, heading.center.x, density)
                assertEquals(heading.center.x, subtitle.center.x, density)
                assertEquals(2 * density, subtitle.top - heading.bottom, density / 2)
                assertTrue(tag("drawer-page-back").boundsInRoot.right <= heading.left)
                assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text=="SELF MUST BE EXCLUDED" } == true })
                val slider=tag("user-volume-2")
                val range=slider.config[SemanticsProperties.ProgressBarRangeInfo]
                assertEquals(0f..300f,range.range); assertEquals(143f,range.current); assertEquals(299,range.steps)
                val shot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null),"user-volumes-$language-$font.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; shot.recycle()
                val bounds=slider.boundsInRoot
                val location=IntArray(2); instrumentation.runOnMainSync { activity.window.decorView.getLocationOnScreen(location) }
                val down=android.os.SystemClock.uptimeMillis()
                fun touch(action:Int,x:Float,y:Float) {
                    val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x+location[0],y+location[1],0)
                    try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                }
                touch(android.view.MotionEvent.ACTION_DOWN,bounds.center.x,bounds.center.y)
                val end = bounds.right - density
                for(step in 1..6) { android.os.SystemClock.sleep(24); touch(android.view.MotionEvent.ACTION_MOVE,bounds.center.x+(end-bounds.center.x)*step/6,bounds.center.y) }
                instrumentation.runOnMainSync {} // Observe the draft while the pointer is still held.
                assertEquals(300f,tag("user-volume-2").config[SemanticsProperties.ProgressBarRangeInfo].current)
                android.os.SystemClock.sleep(100); instrumentation.waitForIdleSync()
                val maximumShot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null),"user-volumes-300-$language-$font.png").outputStream().use { maximumShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; maximumShot.recycle()
                touch(android.view.MotionEvent.ACTION_UP,end,bounds.center.y)
                settle(); assertEquals(original,tag("voice-drawer").boundsInRoot)
                assertEquals("Rejected stale fixture commands restore the effective value",143f,tag("user-volume-2").config[SemanticsProperties.ProgressBarRangeInfo].current)
                val scroll=tag("user-volumes-list")
                instrumentation.runOnMainSync { assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,100*density)) }
                settle(); assertEquals(original,tag("voice-drawer").boundsInRoot)
                assertTrue(tag("user-volumes-list").config[SemanticsProperties.VerticalScrollAxisRange].value()>0)
                instrumentation.runOnMainSync { scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f,-1000*density) }; settle()

                click("drawer-page-back"); assertEquals(original,tag("voice-drawer").boundsInRoot)
                instrumentation.runOnMainSync { fixture=fixture.copy(clients=members.take(2).map { if(it.id==2L) it.copy(volumeToken=null) else it }) }
                click("user-volumes-tool")
                assertTrue(tag("user-volume-2").config.contains(SemanticsProperties.Disabled))
                assertEquals(original,tag("voice-drawer").boundsInRoot)
                val disabledShot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                java.io.File(context.getExternalFilesDir(null),"user-volumes-disabled-$language-$font.png").outputStream().use { disabledShot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; disabledShot.recycle()
                click("drawer-page-back")
                instrumentation.runOnMainSync { fixture=fixture.copy(clients=members.take(1)) }
                click("user-volumes-tool")
                assertTrue(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text==localized.getString(R.string.volume_empty) } == true })
                ClientSession.closeUserVolumes(); settle()
            }
        } finally {
            ClientSession.closeUserVolumes(); ClientSession.clearError()
            // The fixture only sends deliberately stale local volume commands; never connects.
        }
    }
}
