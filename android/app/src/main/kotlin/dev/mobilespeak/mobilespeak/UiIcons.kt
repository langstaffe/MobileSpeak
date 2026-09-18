package dev.mobilespeak.mobilespeak

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** App-owned vectors for status and navigation icons. */
internal object UiIcons {
    private fun icon(name: String, outline: String = "", fill: String = "", strokeWidth: Float = 1.65f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (outline.isNotEmpty()) addPath(addPathNodes(outline), stroke = SolidColor(Color.White), strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            if (fill.isNotEmpty()) addPath(addPathNodes(fill), fill = SolidColor(Color.White))
        }.build()

    val Number = icon("Number", outline = "M9.3 2.8L5.8 21.2 M17.7 2.8L14.2 21.2 M4.2 8.3H21 M3 15.7H19.8", fill = "", strokeWidth = 1.9f)
    val Plus = icon("Plus", "M12 3V21 M3 12H21")
    val Back = icon("Back", "M15 3L6 12L15 21")
    val More = icon("More", fill = "M5 12a1.5 1.5 0 1 0 -3 0a1.5 1.5 0 1 0 3 0 M13.5 12a1.5 1.5 0 1 0 -3 0a1.5 1.5 0 1 0 3 0 M22 12a1.5 1.5 0 1 0 -3 0a1.5 1.5 0 1 0 3 0")
    val People = icon("People", outline = "M8.6 7.7 C8.6 3.8 3.4 3.8 3.4 7.7 C3.4 11.6 8.6 11.6 8.6 7.7 Z M19.5 6.8 C19.5 2.3 13.3 2.3 13.3 6.8 C13.3 11.3 19.5 11.3 19.5 6.8 Z M8.2 19.6 C8.2 12.3 22.8 12.3 22.8 19.6 Q22.8 20 22.3 20 H8.7 Q8.2 20 8.2 19.6 Z M9.3 13.9 C5.5 11.9 1.2 14.8 1.2 18.9 Q1.2 19.3 1.7 19.3 H6", fill = "", strokeWidth = 1.45f)
    val Gear = icon("Gear", outline = "M10.072 4.804 L10.298 2.349 L13.702 2.349 L13.928 4.804 L15.725 5.548 L17.621 3.972 L20.028 6.379 L18.452 8.275 L19.196 10.072 L21.651 10.298 L21.651 13.702 L19.196 13.928 L18.452 15.725 L20.028 17.621 L17.621 20.028 L15.725 18.452 L13.928 19.196 L13.702 21.651 L10.298 21.651 L10.072 19.196 L8.275 18.452 L6.379 20.028 L3.972 17.621 L5.548 15.725 L4.804 13.928 L2.349 13.702 L2.349 10.298 L4.804 10.072 L5.548 8.275 L3.972 6.379 L6.379 3.972 L8.275 5.548 Z M15.4 12 A3.4 3.4 0 1 0 8.6 12 A3.4 3.4 0 1 0 15.4 12 Z", fill = "", strokeWidth = 1.65f)
    val Mic = icon("Mic", outline = "M6.5 10V12.4 C6.5 19.7 17.5 19.7 17.5 12.4V10 M12 18V21.5 M8.5 21.5H15.5", fill = "M8.7 5.2 C8.7 .8 15.3 .8 15.3 5.2 V12 C15.3 16.4 8.7 16.4 8.7 12 Z", strokeWidth = 1.65f)
    val MicOff = icon("MicOff", outline = "M4 3.5L20 20.5 M6.5 11V12.4 C6.5 17.5 11.5 19.6 15.2 17 M17.5 10V12.4 Q17.5 13.1 17.3 13.8 M12 18V21.5 M8.5 21.5H15.5", fill = "M8.7 5.2 C8.7 .8 15.3 .8 15.3 5.2 V11.4 Z M8.7 10.4 L13.6 15.6 C11.3 16.6 8.7 15.2 8.7 12 Z", strokeWidth = 1.65f)
    val Speaker = icon("Speaker", outline = "M16.3 8Q19 12 16.3 16 M19.5 5.5Q23.5 12 19.5 18.5", fill = "M2.8 8.4 H6.3 L11.7 4 Q13 3 13 4.6 V19.4 Q13 21 11.7 20 L6.3 15.6 H2.8 Q1.5 15.6 1.5 14.2 V9.8 Q1.5 8.4 2.8 8.4 Z", strokeWidth = 1.65f)
    val SpeakerOff = icon("SpeakerOff", outline = "M3 3.5L20.5 21", fill = "M8.2 6.9 L11.7 4 Q13 3 13 4.6 V11.7 Z M2.8 8.4 H4.8 L13 16.6 V19.4 Q13 21 11.7 20 L6.3 15.6 H2.8 Q1.5 15.6 1.5 14.2 V9.8 Q1.5 8.4 2.8 8.4 Z", strokeWidth = 1.65f)
    val Wave = icon("Wave", outline = "M3 10V14 M6.6 6.5V17.5 M10.2 2.5V21.5 M13.8 5V19 M17.4 8V16 M21 10V14", fill = "", strokeWidth = 1.65f)
    val Chat = icon("Chat", fill = "M2 1H13Q17 1 17 5V12Q17 15 13 15H8L4 19V15H2Q0 15 0 12V5Q0 1 2 1Z M17 11H21Q24 11 24 14V19Q24 21 21 21V24L17 21H15Q12 21 12 18V16H14Q18 16 18 12Z")
    val Send = icon("Send", fill = "M22.7 1.3Q23.5 0.7 23 2L16.5 22Q16 23.5 15 22L11 15L20 4L9 13L2 10Q0.5 9.5 2 8.8Z")
    val Lock = icon("Lock", "M7 10V6C7 0 17 0 17 6V10 M5 10H19V22H5Z M12 15V18")
    val Person = icon("Person", fill = "M17 6a5 5 0 1 0 -10 0a5 5 0 1 0 10 0 M1 23V20C1 10 23 10 23 20V23Z")
    val Headphones = icon("Headphones", "M3 14V11C3 -1 21 -1 21 11V14 M3 12H7V22H3Q1 22 1 19V15Q1 12 3 12 M21 12H17V22H21Q23 22 23 19V15Q23 12 21 12")
    val Error = icon("Error", "M22 12a10 10 0 1 0 -20 0a10 10 0 1 0 20 0 M12 6V13 M12 17V17.5")
}
