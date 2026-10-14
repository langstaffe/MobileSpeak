package dev.mobilespeak.mobilespeak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.icu.text.BreakIterator

internal fun channelPreviewAvatarCount(width: Int, textWidth: Int, memberCount: Int, avatarStride: Int): Int =
    ((width - textWidth).coerceAtLeast(0) / avatarStride).coerceIn(0, memberCount)

internal fun channelPreviewNicknameWidth(width: Int, textWidth: Int, memberCount: Int, nameWidth: Int,
                                         minimumNameWidth: Int, avatarStride: Int, gap: Int): Int {
    if (memberCount != 1 || nameWidth == 0) return 0
    val available = width - textWidth - avatarStride - gap
    return if (available >= minOf(nameWidth, minimumNameWidth)) minOf(nameWidth, available) else 0
}

@Composable
internal fun ChannelMemberPreview(members: List<Member>, modifier: Modifier = Modifier) {
    if (members.isEmpty()) return
    val summary = pluralStringResource(R.plurals.channel_members_summary, members.size, members.size)
    val member = members.singleOrNull()
    val description = member?.accessibilityLabel(LocalContext.current)
    val style = LocalTextStyle.current.copy(fontSize = 11.sp, lineHeight = 13.sp, color = Palette.muted)
    val measurer = rememberTextMeasurer()
    val name = member?.name.orEmpty()
    val nameWidth = if (name.isEmpty()) 0 else measurer.measure(name, style, softWrap = false, maxLines = 1).size.width
    val firstCharacterEnd = if (name.isEmpty()) 0 else BreakIterator.getCharacterInstance().apply { setText(name) }.next()
    val minimumNameWidth = if (name.isEmpty()) 0 else measurer.measure(name.substring(0, firstCharacterEnd) + "…", style, softWrap = false, maxLines = 1).size.width
    SubcomposeLayout(modifier.fillMaxWidth()) { constraints ->
        val textMeasurable = subcompose("summary") {
            Text(summary, Modifier.semantics { if (description != null) contentDescription = "$description, $summary" },
                fontSize = 11.sp, lineHeight = 13.sp, color = Palette.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }.single()
        val text = textMeasurable.measure(Constraints(maxWidth = constraints.maxWidth))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else members.size * (24.dp.roundToPx() + 5.dp.roundToPx()) + text.width
        val gap = 5.dp.roundToPx()
        val stride = 24.dp.roundToPx() + gap
        val count = channelPreviewAvatarCount(width, text.width, members.size, stride)
        val nicknameWidth = channelPreviewNicknameWidth(width, text.width, members.size, nameWidth, minimumNameWidth, stride, gap)
        val nickname = if (nicknameWidth > 0) subcompose("nickname") {
            Text(name, Modifier.semantics { hideFromAccessibility() }, style = style,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }.single().measure(Constraints(maxWidth = nicknameWidth)) else null
        val avatars = subcompose("avatars") {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                members.take(count).forEach { member -> key(member.id) { Avatar(member.name, member.avatarPath, member.speaking, 24, member, accessible = members.size != 1) } }
            }
        }.single().measure(Constraints(maxWidth = (width - text.width).coerceAtLeast(0)))
        val height = constraints.constrainHeight(maxOf(24.dp.roundToPx(), text.height, nickname?.height ?: 0))
        layout(constraints.constrainWidth(width), height) {
            avatars.placeRelative(0, (height - avatars.height) / 2)
            nickname?.placeRelative(avatars.width + gap, (height - nickname.height) / 2)
            text.placeRelative(if (count == 0) 0 else avatars.width + gap + (nickname?.let { it.width + gap } ?: 0), (height - text.height) / 2)
        }
    }
}
