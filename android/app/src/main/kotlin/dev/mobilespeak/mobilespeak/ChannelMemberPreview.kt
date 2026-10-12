package dev.mobilespeak.mobilespeak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun channelPreviewAvatarCount(width: Int, textWidth: Int, memberCount: Int, avatarStride: Int): Int =
    ((width - textWidth).coerceAtLeast(0) / avatarStride).coerceIn(0, memberCount)

@Composable
internal fun ChannelMemberPreview(members: List<Member>, modifier: Modifier = Modifier) {
    if (members.isEmpty()) return
    val summary = pluralStringResource(R.plurals.channel_members_summary, members.size, members.size)
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val textMeasurable = subcompose("summary") {
            Text(summary, fontSize = 11.sp, lineHeight = 13.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }.single()
        val text = textMeasurable.measure(Constraints(maxWidth = constraints.maxWidth))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else members.size * (24.dp.roundToPx() + 5.dp.roundToPx()) + text.width
        val count = channelPreviewAvatarCount(width, text.width, members.size, 24.dp.roundToPx() + 5.dp.roundToPx())
        val avatars = subcompose("avatars") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                members.take(count).forEach { member -> key(member.id) { Avatar(member.name, member.avatarPath, member.speaking, 24) } }
            }
        }.single().measure(Constraints(maxWidth = (width - text.width).coerceAtLeast(0)))
        val height = constraints.constrainHeight(maxOf(24.dp.roundToPx(), text.height))
        layout(constraints.constrainWidth(width), height) {
            avatars.placeRelative(0, (height - avatars.height) / 2)
            text.placeRelative(if (count == 0) 0 else avatars.width + 5.dp.roundToPx(), (height - text.height) / 2)
        }
    }
}
