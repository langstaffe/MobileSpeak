package dev.mobilespeak.mobilespeak

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun NetworkGrade?.networkColor(): Color = when (this) {
    NetworkGrade.GOOD -> Color(0xFF3DBE78)
    NetworkGrade.FAIR -> Color(0xFFE9B44C)
    NetworkGrade.POOR -> Color(0xFFC15AB8)
    null -> Palette.muted
}

@Composable
internal fun NetworkQualityPanel(quality: NetworkQuality, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val numberWidth = 123.dp
    BoxWithConstraints(modifier.fillMaxWidth().padding(12.dp)) {
        if (maxWidth < numberWidth + 116.dp || density.fontScale > 1f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NetworkNumbers(quality, style, Modifier.fillMaxWidth())
                NetworkChart(quality, Modifier.fillMaxWidth().height(64.dp))
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.Top) {
                NetworkNumbers(quality, style, Modifier.width(numberWidth))
                NetworkChart(quality, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun NetworkNumbers(quality: NetworkQuality, style: TextStyle, modifier: Modifier) {
    val description = if (quality.rttMs == null) stringResource(R.string.network_unavailable)
        else stringResource(R.string.network_summary, quality.latencyText, quality.deviationText, quality.lossText)
    val lossLabel = stringResource(R.string.network_packet_loss)
    Column(modifier.clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.network_latency), color = Palette.muted, fontSize = 12.sp)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = quality.rttGrade.networkColor())) { append(quality.latencyText) }
            withStyle(SpanStyle(color = Palette.muted)) { append(" ms ± ") }
            withStyle(SpanStyle(color = quality.deviationGrade.networkColor())) { append(quality.deviationText) }
        }, style = style)
        Text(buildAnnotatedString {
            withStyle(SpanStyle(color = Palette.muted)) { append("$lossLabel ") }
            withStyle(SpanStyle(color = quality.packetLossGrade.networkColor())) { append(quality.lossText) }
            withStyle(SpanStyle(color = Palette.muted)) { append("%") }
        }, fontSize = 12.sp, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun NetworkChart(quality: NetworkQuality, modifier: Modifier) {
    val density = LocalDensity.current
    val axisFontSize = with(density) { minOf(12.sp.toPx(), 16.dp.toPx()).toSp() }
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = axisFontSize, color = Palette.muted, fontFeatureSettings = "tnum")
    val upper = measurer.measure(AnnotatedString(quality.axisText + " ms"), style, softWrap = false)
    val middle = measurer.measure(AnnotatedString(quality.midAxisText + " ms"), style, softWrap = false)
    val description = stringResource(R.string.network_chart) + ", " + if (quality.samples.isEmpty()) stringResource(R.string.network_unavailable)
        else stringResource(R.string.network_chart_summary, quality.samples.size, quality.axisText)
    Canvas(modifier.clearAndSetSemantics { contentDescription = description }) {
        val labelWidth = maxOf(upper.size.width, middle.size.width).toFloat()
        val plotLeft = labelWidth + 8.dp.toPx()
        val plotWidth = (size.width - plotLeft).coerceAtLeast(0f)
        val top = upper.size.height / 2f
        val baseline = maxOf(top, size.height - 1.dp.toPx())
        val height = baseline - top
        for (fraction in listOf(0f, .5f, 1f)) {
            val y = top + height * fraction
            drawLine(Palette.muted.copy(alpha = .25f), Offset(plotLeft, y), Offset(size.width, y), .5.dp.toPx())
        }
        drawText(upper, topLeft = Offset(labelWidth - upper.size.width, top - upper.size.height / 2f))
        drawText(middle, topLeft = Offset(labelWidth - middle.size.width, top + height / 2f - middle.size.height / 2f))
        val step = plotWidth / 30
        val gap = minOf(2.dp.toPx(), step * .25f)
        for (sample in quality.samples) {
            val age = quality.nowSecond - sample.second
            if (age !in 0..29) continue
            val left = plotLeft + (29 - age).toFloat() * step + gap / 2
            val barHeight = maxOf(1.dp.toPx(), (height * (sample.rttMs / quality.axisMaxMs)).toFloat())
            val width = (step - gap).coerceAtLeast(0f)
            val radius = CornerRadius(minOf(1.5.dp.toPx(), width / 2, barHeight / 2))
            val path = Path().apply {
                addRoundRect(RoundRect(left, baseline - barHeight, left + width, baseline, topLeftCornerRadius = radius, topRightCornerRadius = radius))
            }
            drawPath(path, sample.grade.networkColor())
        }
    }
}
