package dev.mobilespeak.mobilespeak

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class AvatarImagesTest {
    @Test fun selectionUsesPrivateFilesAndClearPersistsIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "avatar-selection-test.jpg")
        try {
            file.outputStream().use {
                Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            initializeOffline(context)
            org.junit.Assume.assumeTrue(ClientSession.state.value.avatarPreviewPath == null)
            val image = AvatarImages.load(context, Uri.fromFile(file))
            ClientSession.saveAvatar(image, AvatarCrop(), ClientSession.beginAvatarSelection())
            val current = File(context.filesDir, "core/${AvatarImages.currentName}")
            for (attempt in 0 until 200) {
                if (current.isFile && ClientSession.state.value.avatarPreviewPath != null) break
                Thread.sleep(25)
            }
            image.recycle()
            assertTrue(current.isFile)
            val directory = File(context.filesDir, "core/avatars/${current.readText()}")
            assertTrue(File(directory, "preview.jpg").isFile)
            assertTrue((0..2).all { File(directory, "upload-$it.jpg").isFile })
            clearLocalAvatar()
            assertTrue(current.readText() == "clear")
            assertTrue(ClientSession.state.value.avatarPreviewPath == null)
        } finally { file.delete() }
    }

    private fun initializeOffline(context: android.content.Context) {
        ClientSession.initialize(context)
        org.junit.Assume.assumeTrue("Image fixture tests never operate on a live server", !ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
    }

    private fun clearLocalAvatar() {
        org.junit.Assume.assumeTrue(!ClientSession.shouldRunService() && ClientSession.state.value.snapshot.status == "disconnected")
        ClientSession.clearAvatar()
        val current = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "core/${AvatarImages.currentName}")
        for (attempt in 0 until 200) {
            if (!ClientSession.state.value.avatarClearingLocally && current.isFile && current.readText() == "clear") return
            Thread.sleep(10)
        }
        error("Clear intent was not saved")
    }

    @Test fun clearConfirmationCancelKeepsStateAndConfirmPersistsOfflineInBothLanguages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        initializeOffline(context)
        org.junit.Assume.assumeTrue("Do not clear a pre-existing local avatar", ClientSession.state.value.avatarPreviewPath == null)
        val current = File(context.filesDir, "core/${AvatarImages.currentName}")
        val language = AppLanguage.selected()
        try {
            for (locale in listOf(AppLanguage.ENGLISH, AppLanguage.SIMPLIFIED_CHINESE)) {
                instrumentation.runOnMainSync { AppLanguage.select(locale) }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        if (android.os.Build.VERSION.SDK_INT >= 27) { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
                    }
                    instrumentation.waitForIdleSync()
                    waitForCropNode(context.localized(R.string.tab_settings), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    val before = if (current.isFile) current.readBytes() else null
                    val preview = ClientSession.state.value.avatarPreviewPath
                    waitForCropNode(context.localized(R.string.avatar_remove), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_clear_confirm_offline)).isVisibleToUser)
                    scenario.recreate() // Rebuilding the activity must not silently confirm.
                    instrumentation.waitForIdleSync()
                    waitForCropNode(context.localized(R.string.action_cancel), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    instrumentation.waitForIdleSync()
                    Thread.sleep(250) // Wait for the cancel dismissal before reopening the dialog.
                    org.junit.Assert.assertArrayEquals(before, if (current.isFile) current.readBytes() else null)
                    org.junit.Assert.assertEquals(preview, ClientSession.state.value.avatarPreviewPath)
                    waitForCropNode(context.localized(R.string.avatar_remove), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    instrumentation.waitForIdleSync()
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_clear_confirm_offline)).isVisibleToUser)
                    val revision = ClientSession.state.value.avatarRevision
                    // Compose exposes both the title surface and the text button
                    // as clickable Views on this API level; select the small action.
                    waitForCropNode(context.localized(R.string.avatar_remove), click = true, buttonOnly = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    for (attempt in 0 until 200) {
                        if (current.isFile && current.readText() == "clear" && ClientSession.state.value.avatarStatus == "clear_pending" && ClientSession.state.value.avatarRevision > revision) break
                        Thread.sleep(10)
                    }
                    assertTrue(ClientSession.state.value.avatarRevision > revision)
                    org.junit.Assert.assertEquals("clear", current.readText())
                    instrumentation.waitForIdleSync()
                    Thread.sleep(250) // Finish native dialog dismissal animation before the screenshot.
                    assertTrue(ClientSession.state.value.avatarPreviewPath == null)
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_cleared_local)).isVisibleToUser)
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_remove), click = true).isEnabled)
                    instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                        File(context.cacheDir, "clear-ui-${locale.code}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        shot.recycle()
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { AppLanguage.select(language) } }
    }

    @Test fun exifOrientationIsAppliedBeforePreviewEncoding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "avatar-rotation-test.jpg")
        try {
            file.outputStream().use {
                Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val upright = AvatarImages.load(context, Uri.fromFile(file))
            assertTrue(upright.height > upright.width)
            val (preview, _) = AvatarImages.prepare(upright, AvatarCrop())
            val decoded = BitmapFactory.decodeByteArray(preview, 0, preview.size)
            assertTrue(decoded.height == decoded.width)
            upright.recycle(); decoded.recycle()
        } finally { file.delete() }
    }

    @Test fun largeOriginalGetsPreviewAndDescendingActualUploadSizes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val width = 2400
        val pixels = IntArray(width * width)
        val random = Random(42)
        for (index in pixels.indices) pixels[index] = random.nextInt() or -0x1000000
        val bitmap = Bitmap.createBitmap(pixels, width, width, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "avatar-image-test.jpg")
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
            bitmap.recycle()
            assertTrue(file.length() > 5_000_000)
            val (preview, uploads) = AvatarImages.prepare(context, Uri.fromFile(file))
            assertTrue(uploads.size == 3)
            assertTrue(uploads[0].size > uploads[1].size)
            assertTrue(uploads[1].size > uploads[2].size)
            assertTrue(BitmapFactory.decodeByteArray(preview, 0, preview.size).width <= 640)
            assertTrue(BitmapFactory.decodeByteArray(uploads[0], 0, uploads[0].size).width <= 2048)
        } finally {
            bitmap.recycle()
            file.delete()
        }
    }

    private fun fixture(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { image ->
        android.graphics.Canvas(image).apply {
            drawColor(android.graphics.Color.DKGRAY)
            val paint = android.graphics.Paint().apply { color = android.graphics.Color.RED }
            drawCircle(width / 2f, height / 2f, 100f, paint)
            paint.color = android.graphics.Color.BLUE; drawRect(0f, 0f, 60f, height.toFloat(), paint)
            paint.color = android.graphics.Color.GREEN; drawRect(width - 60f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
    @Test fun cropGeometryAndCandidatesPreserveCirclesAndCorners() {
        for ((width, height) in listOf(800 to 600, 600 to 800, 600 to 600)) {
            val image = fixture(width, height)
            val rect = AvatarCrop().rect(width, height)
            org.junit.Assert.assertEquals(minOf(width, height), rect.width())
            org.junit.Assert.assertEquals(width / 2, rect.centerX())
            org.junit.Assert.assertEquals(height / 2, rect.centerY())
            val (preview, uploads) = AvatarImages.prepare(image, AvatarCrop())
            for (data in listOf(preview) + uploads) {
                val decoded = BitmapFactory.decodeByteArray(data, 0, data.size)
                org.junit.Assert.assertEquals(decoded.width, decoded.height)
                assertTrue(decoded.width <= minOf(width, height))
                val center = decoded.width / 2
                fun red(color: Int) = android.graphics.Color.red(color) > 160 && android.graphics.Color.green(color) < 100
                val horizontal = (0 until decoded.width).count { red(decoded.getPixel(it, center)) }
                val vertical = (0 until decoded.height).count { red(decoded.getPixel(center, it)) }
                assertTrue(kotlin.math.abs(horizontal - vertical) <= 4)
                org.junit.Assert.assertEquals(200.0 / minOf(width, height), horizontal.toDouble() / decoded.width, .025)
                assertTrue(android.graphics.Color.red(decoded.getPixel(decoded.width / 4, 8)) < 180)
                decoded.recycle()
            }
            val edge = AvatarCrop(zoom = 2f).transform(width, height, 100f, 10000f, -10000f)
            val bound = edge.rect(width, height)
            org.junit.Assert.assertEquals(0, bound.left)
            org.junit.Assert.assertEquals(height, bound.bottom)
            val (cropped, _) = AvatarImages.prepare(image, edge)
            val decoded = BitmapFactory.decodeByteArray(cropped, 0, cropped.size)
            assertTrue(android.graphics.Color.blue(decoded.getPixel(10, 10)) > 180)
            image.recycle(); decoded.recycle()
        }
        val transparent = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(transparent).drawCircle(32f, 32f, 20f, android.graphics.Paint().apply { color = android.graphics.Color.RED })
        val (preview, uploads) = AvatarImages.prepare(transparent, AvatarCrop())
        for (data in listOf(preview) + uploads) {
            val decoded = BitmapFactory.decodeByteArray(data, 0, data.size)
            org.junit.Assert.assertEquals(64, decoded.width)
            org.junit.Assert.assertEquals(decoded.width, decoded.height)
            val corner = decoded.getPixel(2, 2)
            assertTrue(android.graphics.Color.red(corner) > 240 && android.graphics.Color.green(corner) > 240 && android.graphics.Color.blue(corner) > 240)
            decoded.recycle()
        }
        transparent.recycle()
    }
    @Test fun everyExifOrientationIsAppliedBeforeSquareExportAndMetadataIsDropped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = Bitmap.createBitmap(420, 300, Bitmap.Config.ARGB_8888)
        val colors = listOf(android.graphics.Color.RED, android.graphics.Color.GREEN, android.graphics.Color.BLUE, android.graphics.Color.YELLOW)
        android.graphics.Canvas(source).apply {
            val paint = android.graphics.Paint()
            colors.forEachIndexed { index, color ->
                paint.color = color
                drawRect((index % 2) * 210f, (index / 2) * 150f, (index % 2 + 1) * 210f, (index / 2 + 1) * 150f, paint)
            }
        }
        val expected = listOf(colors[0], colors[1], colors[3], colors[2], colors[0], colors[2], colors[3], colors[1])
        for (orientation in 1..8) {
            val file = File(context.cacheDir, "crop-exif-$orientation.jpg")
            try {
                file.outputStream().use { source.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                ExifInterface(file.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    setAttribute(ExifInterface.TAG_GPS_LATITUDE, "1/1,0/1,0/1")
                    saveAttributes()
                }
                val image = AvatarImages.load(context, Uri.fromFile(file))
                org.junit.Assert.assertEquals(if (orientation >= 5) 300 else 420, image.width)
                val (_, uploads) = AvatarImages.prepare(image, AvatarCrop())
                val decoded = BitmapFactory.decodeByteArray(uploads[0], 0, uploads[0].size)
                val actual = decoded.getPixel(20, 20)
                for (component in listOf<(Int) -> Int>(android.graphics.Color::red, android.graphics.Color::green, android.graphics.Color::blue)) {
                    assertTrue(kotlin.math.abs(component(actual) - component(expected[orientation - 1])) < 15)
                }
                val exif = ExifInterface(java.io.ByteArrayInputStream(uploads[0]))
                assertTrue(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) == null)
                image.recycle(); decoded.recycle()
            } finally { file.delete() }
        }
        source.recycle()
    }
    @Test fun cancellationAndProcessingFailureKeepPreviousAvatar() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        initializeOffline(context)
        val current = File(context.filesDir, "core/${AvatarImages.currentName}")
        val created = !current.exists()
        if (created) {
            val baseline = fixture(600, 600)
            val token = ClientSession.beginAvatarSelection()
            ClientSession.saveAvatar(baseline, AvatarCrop(), token)
            waitForSave(token, true)
            val saved = current.readText()
            ClientSession.saveAvatar(baseline, AvatarCrop(zoom = 2f), token)
            Thread.sleep(100)
            org.junit.Assert.assertEquals(saved, current.readText())
            baseline.recycle()
        }
        try {
            val before = current.readText()
            val previous = ClientSession.state.value.avatarPreviewPath
            val selection = ClientSession.beginAvatarSelection()
            ClientSession.cancelAvatarSelection(selection)
            val image = fixture(800, 600)
            ClientSession.saveAvatar(image, AvatarCrop(), selection)
            Thread.sleep(100)
            org.junit.Assert.assertEquals(before, current.readText())
            val invalid = ClientSession.beginAvatarSelection()
            image.recycle()
            ClientSession.saveAvatar(image, AvatarCrop(), invalid)
            waitForSave(invalid, false)
            org.junit.Assert.assertEquals(before, current.readText())
            org.junit.Assert.assertEquals(previous, ClientSession.state.value.avatarPreviewPath)
        } finally { if (created) clearLocalAvatar() }
    }
    private fun waitForSave(selection: Long, success: Boolean) {
        for (attempt in 0 until 100) {
            if (ClientSession.avatarSave.value == (selection to success)) return
            Thread.sleep(20)
        }
        org.junit.Assert.assertEquals(selection to success, ClientSession.avatarSave.value)
    }
    @Test fun nativeCropDialogSupportsLocalizedControlsConfirmAndCancelAtLargeFont() {
        fun seekBar(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.className == "android.widget.SeekBar") return node
            for (index in 0 until node.childCount) seekBar(node.getChild(index))?.let { return it }
            return null
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        initializeOffline(context)
        val before = ClientSession.state.value.avatarPreviewPath
        val originalLocales = AppCompatDelegate.getApplicationLocales()
        val originalConfiguration = android.content.res.Configuration(context.resources.configuration)
        val file = File(context.cacheDir, "crop-ui.jpg")
        fixture(800, 600).also { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
        }
        try {
            for ((locale, fontScale) in listOf("en" to 1f, "zh-Hans" to 1f, "en" to 2f, "zh-Hans" to 2f)) {
                instrumentation.runOnMainSync {
                    AppLanguage.select(if (locale == "en") AppLanguage.ENGLISH else AppLanguage.SIMPLIFIED_CHINESE)
                }
                val selection = ClientSession.beginAvatarSelection()
                val visible = mutableStateOf(true)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { activity ->
                        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            activity.setShowWhenLocked(true)
                            activity.setTurnScreenOn(true)
                        }
                        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
                        activity.setContent {
                            MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Palette.accent, onPrimary = Palette.text, onSurface = Palette.text)) {
                                if (visible.value) AvatarCropDialog(Uri.fromFile(file).toString(), selection) {
                                    ClientSession.cancelAvatarSelection(selection); visible.value = false
                                }
                            }
                        }
                    }
                    val area = waitForCropNode(context.localized(R.string.avatar_crop_area))
                    val bounds = android.graphics.Rect().also(area::getBoundsInScreen)
                    org.junit.Assert.assertEquals(bounds.width(), bounds.height())
                    assertTrue(bounds.width() > 0)
                    val titleBounds = android.graphics.Rect().also(waitForCropNode(context.localized(R.string.avatar_crop_title))::getBoundsInScreen)
                    val cancelBounds = android.graphics.Rect().also(waitForCropNode(context.localized(R.string.action_cancel), click = true)::getBoundsInScreen)
                    org.junit.Assert.assertEquals(bounds.exactCenterX().toDouble(), titleBounds.exactCenterX().toDouble(), 2.0)
                    assertTrue(cancelBounds.right <= titleBounds.left)
                    instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                        File(context.cacheDir, "crop-baseline-$locale-$fontScale.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        shot.recycle()
                    }
                    cropGesture(bounds, pinch = false)
                    cropGesture(bounds, pinch = true)
                    instrumentation.waitForIdleSync()
                    assertTrue(requireNotNull(seekBar(instrumentation.uiAutomation.rootInActiveWindow)).rangeInfo.current > 1.5f)
                    val cropArea = waitForCropNode(context.localized(R.string.avatar_crop_area))
                    val directionLabels = listOf(R.string.avatar_crop_left, R.string.avatar_crop_up, R.string.avatar_crop_down, R.string.avatar_crop_right).map(context::localized)
                    assertTrue(directionLabels.all { label -> cropArea.actionList.any { it.label?.toString() == label } })
                    assertTrue(cropArea.performAction(cropArea.actionList.first { it.label?.toString() == context.localized(R.string.avatar_crop_right) }.id))
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_crop_zoom)).isEnabled)
                    assertTrue(waitForCropNode(context.localized(R.string.avatar_crop_use)).isEnabled)
                    instrumentation.uiAutomation.takeScreenshot()?.let { shot ->
                        File(context.cacheDir, "crop-ui-$locale.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        shot.recycle()
                    }
                    if (locale == "zh-Hans" && before == null) {
                        val slider = requireNotNull(seekBar(instrumentation.uiAutomation.rootInActiveWindow))
                        assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, android.os.Bundle().apply {
                            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 2f)
                        }))
                        assertTrue(waitForCropNode(context.localized(R.string.avatar_crop_use), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        for (attempt in 0 until 100) {
                            if (ClientSession.state.value.avatarPreviewPath != null) break
                            Thread.sleep(20)
                        }
                        val saved = BitmapFactory.decodeFile(requireNotNull(ClientSession.state.value.avatarPreviewPath))
                        org.junit.Assert.assertEquals(300, saved.width)
                        org.junit.Assert.assertEquals(saved.width, saved.height)
                        saved.recycle()
                        android.util.Log.i("CropUiTest", "Confirmed local square export: 300x300")
                        clearLocalAvatar()
                    } else {
                        assertTrue(waitForCropNode(context.localized(R.string.action_cancel), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    instrumentation.waitForIdleSync()
                    org.junit.Assert.assertEquals(before, ClientSession.state.value.avatarPreviewPath)
                    assertTrue(!ClientSession.isCurrentAvatarSelection(selection))
                }
            }
        } finally {
            file.delete()
            instrumentation.runOnMainSync {
                AppCompatDelegate.setApplicationLocales(originalLocales)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(originalConfiguration, context.resources.displayMetrics)
            }
        }
    }
    private fun waitForCropNode(label: String, click: Boolean = false, buttonOnly: Boolean = false): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) {
                if (!click) return node
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                val bounds = android.graphics.Rect().also { target?.getBoundsInScreen(it) }
                val maxButtonHeight = 96 * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
                if (target?.isVisibleToUser == true && (!buttonOnly || bounds.height() <= maxButtonHeight)) return target
            }
            for (index in 0 until node.childCount) find(node.getChild(index))?.let { return it }
            return null
        }
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (index in 0 until node.childCount) if (scroll(node.getChild(index))) return true
            return false
        }
        for (attempt in 0 until 20) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            find(root)?.let { return it }
            scroll(root)
            Thread.sleep(50)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        error("Missing crop accessibility control: $label; window=${root?.packageName}, class=${root?.className}")
    }
    @Test fun settingsPickerResultOpensCropAndActivityRecreationPreservesAdjustmentWithoutSaving() {
        org.junit.Assume.assumeTrue("The MediaStore test fixture requires scoped storage", android.os.Build.VERSION.SDK_INT >= 29)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        initializeOffline(context)
        val before = ClientSession.state.value.avatarPreviewPath
        val name = "MobileSpeakCrop-${System.nanoTime()}.jpg"
        val uri = requireNotNull(context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }))
        // Return only our fixture; the installed Google picker exposes dates rather than filenames.
        val launches = java.util.concurrent.atomic.AtomicInteger()
        androidx.test.runner.intent.IntentStubberRegistry.load(object : androidx.test.runner.intent.IntentStubber {
            override fun getActivityResultForIntent(intent: android.content.Intent): android.app.Instrumentation.ActivityResult? {
                if (intent.type != "image/*") return null
                launches.incrementAndGet()
                return android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, android.content.Intent().setData(uri))
            }
        })
        try {
            val image = fixture(800, 600)
            context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            image.recycle()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    if (android.os.Build.VERSION.SDK_INT >= 27) { activity.setShowWhenLocked(true); activity.setTurnScreenOn(true) }
                }
                assertTrue(waitForCropNode(context.localized(R.string.tab_settings), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                assertTrue(waitForCropNode(context.localized(if (before == null) R.string.avatar_choose else R.string.avatar_change), click = true)
                    .performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val area = waitForCropNode(context.localized(R.string.avatar_crop_area))
                org.junit.Assert.assertEquals(1, launches.get())
                val bounds = android.graphics.Rect().also(area::getBoundsInScreen)
                cropGesture(bounds, pinch = true)
                instrumentation.waitForIdleSync()
                val adjusted = requireNotNull(cropZoom())
                assertTrue(adjusted > 1.5f)
                scenario.recreate()
                assertTrue(waitForCropNode(context.localized(R.string.avatar_crop_area)).isVisibleToUser)
                org.junit.Assert.assertEquals(adjusted.toDouble(), requireNotNull(cropZoom()).toDouble(), .001)
                assertTrue(waitForCropNode(context.localized(R.string.action_cancel), click = true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                instrumentation.waitForIdleSync()
                org.junit.Assert.assertEquals(before, ClientSession.state.value.avatarPreviewPath)
            }
        } finally {
            androidx.test.runner.intent.IntentStubberRegistry.reset()
            context.contentResolver.delete(uri, null, null)
        }
    }
    private fun cropZoom(node: AccessibilityNodeInfo? = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow): Float? {
        if (node == null) return null
        if (node.className == "android.widget.SeekBar") return node.rangeInfo.current
        for (index in 0 until node.childCount) cropZoom(node.getChild(index))?.let { return it }
        return null
    }
    private fun cropGesture(bounds: android.graphics.Rect, pinch: Boolean) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = android.os.SystemClock.uptimeMillis()
        val properties = Array(if (pinch) 2 else 1) { index ->
            android.view.MotionEvent.PointerProperties().apply { id = index; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
        }
        fun event(action: Int, step: Int, count: Int = properties.size) {
            val coordinates = Array(count) { index -> android.view.MotionEvent.PointerCoords().apply {
                x = bounds.exactCenterX() + if (pinch) (if (index == 0) -1 else 1) * bounds.width() * .12f * (1 + step / 8f)
                    else bounds.width() * .12f * step / 8
                y = bounds.exactCenterY(); pressure = 1f; size = 1f
            } }
            val motion = android.view.MotionEvent.obtain(down, down + step * 16L, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(automation.injectInputEvent(motion, true)) } finally { motion.recycle() }
        }
        event(android.view.MotionEvent.ACTION_DOWN, 0, 1)
        if (pinch) event(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0)
        for (step in 1..8) event(android.view.MotionEvent.ACTION_MOVE, step)
        if (pinch) event(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), 8)
        event(android.view.MotionEvent.ACTION_UP, 8, 1)
    }
}
