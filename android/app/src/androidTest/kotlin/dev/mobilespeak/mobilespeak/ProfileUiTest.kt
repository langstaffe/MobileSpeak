package dev.mobilespeak.mobilespeak

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class ProfileUiTest {
    @Test fun nativeProfileStatesEditorErrorsAndPressCancellation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Use an isolated test installation", InstrumentationRegistry.getArguments().getString("profileIsolated") == "1")
        val context = instrumentation.targetContext
        ClientSession.initialize(context)
        val original = ClientSession.state.value.profile.nickname
        var fixture by mutableStateOf(SessionUiState())
        var language by mutableStateOf("en")
        var font by mutableFloatStateOf(1f)
        var editor by mutableStateOf(false)
        val image = File(context.cacheDir, "profile-ui-avatar.png")
        val bitmap = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFFFD159.toInt()) }
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                scenario.onActivity { current ->
                    activity=current
                    current.setContent {
                        val configuration=Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
                        val localized=context.createConfigurationContext(configuration)
                        val density=LocalDensity.current
                        val live by ClientSession.state.collectAsState()
                        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalDensity provides Density(density.density,font),
                            LocalContentColor provides Palette.text,
                            androidx.activity.compose.LocalActivityResultRegistryOwner provides current) {
                            MaterialTheme(colorScheme=darkColorScheme(background=Palette.background,surface=Palette.card,onSurface=Palette.text,primary=Palette.accent)) {
                                ProvideTextStyle(TextStyle(fontSize=17.sp,lineHeight=20.sp)) {
                                    if (editor) NicknameDialog(live) { editor=false }
                                    Box(Modifier.fillMaxSize().background(Palette.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
                                        SettingsScreen(fixture,{}, {})
                                    }
                                }
                            }
                        }
                    }
                }
                fun root(view:View): androidx.compose.ui.node.RootForTest? = (view as? androidx.compose.ui.node.RootForTest)
                    ?: (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { root(group.getChildAt(it)) } }
                fun nodes():List<SemanticsNode> {
                    fun collect(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::collect)
                    var result=emptyList<SemanticsNode>()
                    instrumentation.runOnMainSync { result=collect(requireNotNull(root(activity.window.decorView)).semanticsOwner.unmergedRootSemanticsNode) }
                    return result
                }
                fun settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(200) }
                fun capture(name:String) {
                    val shot=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                    File(context.cacheDir,"profile-$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }; shot.recycle()
                }
                fun editNode() = nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { value -> value.startsWith("Edit nickname,") || value.startsWith("修改昵称，") } == true }
                for (lang in listOf("en","zh-Hans")) for (scale in listOf(1f,2f)) for (state in 0..3) {
                    instrumentation.runOnMainSync {
                        language=lang; font=scale
                        fixture=SessionUiState(profile=ProfileState(nickname=if(state==2) "用户😀".repeat(12) else "MobileSpeakUser"),
                            avatarPreviewPath=if(state==1) image.path else null,avatarStatus=if(state==3) "uploading" else "idle")
                    }; settle()
                    val edit=editNode()
                    assertTrue(edit.boundsInRoot.height >= 44*context.resources.displayMetrics.density)
                    assertTrue(edit.config.getOrNull(SemanticsProperties.ContentDescription)!!.single().contains(fixture.profile.nickname))
                    assertFalse(edit.config.contains(SemanticsProperties.Disabled))
                    assertFalse(nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("所有服务器") || text.text.contains("all servers") } == true })
                    capture("settings-$state-$lang-font-$scale")
                }
                instrumentation.runOnMainSync { language="en"; font=1f; fixture=SessionUiState() }; settle()
                val bounds=editNode().boundsInRoot
                val down=SystemClock.uptimeMillis()
                fun touch(action:Int) {
                    instrumentation.runOnMainSync {
                        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.center.x,bounds.center.y,0)
                        activity.window.decorView.dispatchTouchEvent(event); event.recycle()
                    }
                }
                touch(MotionEvent.ACTION_DOWN); settle(); capture("nickname-held")
                touch(MotionEvent.ACTION_CANCEL); settle(); capture("nickname-cancelled")
                assertFalse(editor)
                // Separate live dialog uses the actual controller and shared core response.
                for (scale in listOf(1f,2f)) {
                    instrumentation.runOnMainSync { font=scale; editor=true }; settle(); capture("editor-font-$scale")
                    // Dialog accessibility and keyboard are exercised using the platform view tree.
                    val save=instrumentation.uiAutomation.rootInActiveWindow
                    assertNotNull(save)
                    fun find(node:android.view.accessibility.AccessibilityNodeInfo, predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
                        if(predicate(node)) return node
                        for(i in 0 until node.childCount) node.getChild(i)?.let { find(it,predicate)?.let { found -> return found } }
                        return null
                    }
                    val input=requireNotNull(find(save) { it.isEditable })
                    assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS))
                    input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                    val args=android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE," ") }
                    assertTrue(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args)); settle(); capture("editor-keyboard-font-$scale")
                    val label=requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Save" || it.contentDescription?.toString()=="Save" })
                    var button=label
                    while (!button.isClickable && button.parent != null) button=button.parent
                    assertTrue(button.isClickable)
                    assertTrue(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    val deadline=SystemClock.elapsedRealtime()+5_000
                    while(ClientSession.state.value.nicknameSaveError==null && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(20)
                    assertNotNull(ClientSession.state.value.nicknameSaveError); assertEquals(original,ClientSession.state.value.profile.nickname)
                    settle(); capture("editor-save-failed-font-$scale")
                    if (scale == 1f) {
                        val current=requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.isEditable })
                        val retry=android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"  保存后重试😀  ") }
                        assertTrue(current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,retry)); settle()
                        val saveLabel=requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Save" })
                        var retryButton=saveLabel
                        while(!retryButton.isClickable && retryButton.parent!=null) retryButton=retryButton.parent
                        assertTrue(retryButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                        val completed=SystemClock.elapsedRealtime()+5_000
                        while(editor && SystemClock.elapsedRealtime()<completed) SystemClock.sleep(20)
                        assertFalse(editor); assertEquals("保存后重试😀", ClientSession.state.value.profile.nickname)
                        val restore=ClientSession.saveNickname(original)
                        val restored=SystemClock.elapsedRealtime()+5_000
                        while(ClientSession.state.value.profile.requestId!=restore && SystemClock.elapsedRealtime()<restored) SystemClock.sleep(20)
                    } else {
                        val cancelLabel=requireNotNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()=="Cancel" })
                        var cancelButton=cancelLabel
                        while(!cancelButton.isClickable && cancelButton.parent!=null) cancelButton=cancelButton.parent
                        assertTrue(cancelButton.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)); settle()
                        assertFalse(editor); assertEquals(original, ClientSession.state.value.profile.nickname)
                    }
                }
            }
        } finally {
            image.delete()
            val request=ClientSession.saveNickname(original)
            val deadline=SystemClock.elapsedRealtime()+5_000
            while(ClientSession.state.value.profile.requestId!=request && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(20)
        }
    }
}
