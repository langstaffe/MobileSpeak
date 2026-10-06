package dev.mobilespeak.mobilespeak

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Resource-backed vectors; state tint remains at each call site. */
internal object UiIcons {
    val Number: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_channels)
    val Plus: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_plus)
    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_checkmark)
    val More: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_more)
    val Pencil: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_pencil)
    val Trash: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_trash)
    val People: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_members)
    val Gear: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_settings)
    val Mic: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic_on)
    val MicOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_mic_off)
    val Speaker: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_speaker_on)
    val SpeakerOff: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_speaker_off)
    val Wave: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_waveform)
    val DrawerHandle: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_drawer_handle)
    val Chat: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_channel_chat)
    val Send: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_send)
    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_lock)
    val Person: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_avatar_placeholder)
    val Headphones: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_headphones)
    val Error: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_error)
    val ChevronRight: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_chevron_right)

    val Folder: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_folder)
    val File: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_file)
    val FileText: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_text)
    val FileImage: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_image)
    val FileAudio: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_audio)
    val FileArchive: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_file_archive)
    val Upload: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_upload)
    val Download: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_download)
    val Sort: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_sort)
    val Refresh: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_refresh)
    val Info: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_info)
    val Share: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.ic_share)

    val Back = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M15 3L6 12L15 21"), stroke = SolidColor(Color.White), strokeLineWidth = 1.65f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
}
