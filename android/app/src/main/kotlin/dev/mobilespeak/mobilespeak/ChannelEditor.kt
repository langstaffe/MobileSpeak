package dev.mobilespeak.mobilespeak

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

internal data class ChannelEditSelection(val channel: Channel, val target: ServerChatTarget)

internal val channelFormBackground = Color(0xFF1C1C1E)
private val channelFormGroup = Color(0xFF2C2C2E)
private val formShape = RoundedCornerShape(24.dp)
private val channelMoveEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelManagementSheet(
    saving: Boolean, onClose: () -> Unit, swipeToClose: Boolean = true, containerColor: Color = Color.Black,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { if (!saving) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.imePadding(),
        containerColor = containerColor, contentColor = Palette.text,
        shape = RoundedCornerShape(topStart = 40.dp, topEnd = 40.dp),
        dragHandle = null, scrimColor = Color.Black.copy(alpha = .55f),
        sheetGesturesEnabled = !saving && swipeToClose,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !saving, shouldDismissOnClickOutside = !saving),
        content = content,
    )
}

@Composable
private fun ChannelFormHeader(title: String, enabled: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) {
            PressFeedback(ToolbarPressFeedback) { source, feedback, _ ->
                TextButton(onClick = onClose, enabled = enabled, interactionSource = source, modifier = feedback) {
                    Text(stringResource(R.string.action_cancel), fontSize = 17.sp)
                }
            }
        }
        Text(title, Modifier.align(Alignment.Center), fontSize = 17.sp, lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ChannelFormSubmit(
    text: String, enabled: Boolean, saving: Boolean, containerColor: Color = channelFormBackground, onClick: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(formShape).background(containerColor)
            .clickable(interactionSource = null, indication = RowPressFeedback, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
        else Text(text, fontSize = 17.sp, color = if (enabled) Palette.accent else Palette.muted.copy(alpha = 0.55f))
    }
}

@Composable
private fun ChannelFormError(error: ChannelCommandResult?) {
    error?.let {
        Text(LocalContext.current.localizedCoreError(it.error, it.detail), Modifier.padding(bottom = 12.dp), color = Palette.disconnect)
    }
}

@Composable
internal fun ChannelCreator(
    target: ServerChatTarget, parent: Channel?, result: ChannelCommandResult?,
    onSaving: (Boolean) -> Unit, onClose: () -> Unit, onCreated: (Long) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("temporary") }
    var position by remember { mutableStateOf("bottom") }
    var request by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<ChannelCommandResult?>(null) }
    val focus = LocalFocusManager.current
    val enabled = request == null
    LaunchedEffect(result) {
        if (result != null && result.requestId == request) {
            request = null
            onSaving(false)
            if (result.error == null && result.channel != null) onCreated(result.channel)
            else error = result.takeIf { it.error != null } ?: result.copy(error = "channel_create_failed")
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
        ChannelFormHeader(stringResource(if (parent == null) R.string.channel_create_title else R.string.channel_create_child), enabled, onClose)
        if (parent == null) {
            Spacer(Modifier.height(32.dp))
        } else {
            Text(stringResource(R.string.channel_create_under, parent.name), Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                fontSize = 15.sp, color = Palette.muted)
        }
        Column(Modifier.fillMaxWidth().clip(formShape).background(channelFormBackground)) {
            EditField(name, { name = it }, R.string.channel_edit_name, enabled)
            EditDivider()
            EditField(password, { password = it }, R.string.channel_password, enabled, keyboard = KeyboardType.Password)
            EditDivider()
            EditChoice(R.string.channel_edit_type, type, listOf(
                "temporary" to R.string.channel_edit_temporary,
                "semi_permanent" to R.string.channel_edit_semi_permanent,
                "permanent" to R.string.channel_edit_permanent,
            ), enabled) { type = it }
            if (parent == null) {
                EditDivider()
                EditChoice(R.string.channel_position, position, listOf(
                    "top" to R.string.channel_position_top, "bottom" to R.string.channel_position_bottom,
                ), enabled) { position = it }
            }
        }
        Spacer(Modifier.height(36.dp))
        ChannelFormError(error)
        ChannelFormSubmit(stringResource(R.string.channel_create_action), enabled && name.isNotBlank(), !enabled) {
            val id = UUID.randomUUID().toString()
            val changes = JSONObject().put("name", name).put("password", password).put("channel_type", type)
            if (parent == null) changes.put("position", position)
            request = id; error = null; focus.clearFocus(); onSaving(true)
            ClientSession.channelCommand("create", target, parent?.id ?: 0, id, changes)
        }
    }
}

@Composable
internal fun ChannelMover(
    selection: ChannelEditSelection, live: List<Channel>, result: ChannelCommandResult?,
    onSaving: (Boolean) -> Unit, onClose: () -> Unit,
) {
    val tree = remember(live) { orderedChannels(live) }
    var original by remember(selection) { mutableStateOf(selection.channel) }
    var parent by remember(selection) { mutableLongStateOf(original.parent) }
    fun rows(id: Long) = tree.map { it.first }.filter { it.parent == id }
    var expected by remember(selection) { mutableStateOf(rows(parent).filter { it.id != original.id }) }
    var preview by remember(selection) { mutableStateOf(rows(parent)) }
    var choosing by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<ChannelCommandResult?>(null) }
    val list = rememberLazyListState()
    var dragY by remember { mutableStateOf<Float?>(null) }
    var beforeDrag by remember { mutableStateOf<Int?>(null) }
    val edge = with(LocalDensity.current) { 54.dp.toPx() }
    val step = with(LocalDensity.current) { 9.dp.toPx() }
    val index = preview.indexOfFirst { it.id == original.id }
    val order = preview.getOrNull(index - 1)?.id ?: 0
    val enabled = request == null
    val excluded = remember(live, original.id) {
        val children = live.groupBy(Channel::parent)
        val visited = mutableSetOf<Long>()
        val stack = ArrayDeque(listOf(original.id))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (visited.add(id)) children[id]?.forEach { stack.addLast(it.id) }
        }
        visited
    }
    fun selectParent(id: Long) {
        parent = id
        val latest = rows(id)
        expected = latest.filter { it.id != original.id }
        preview = if (id == original.parent) latest else expected + original
    }
    fun moveTo(index: Int): Boolean {
        val from = preview.indexOfFirst { it.id == original.id }
        if (from < 0 || index !in preview.indices || from == index) return false
        if (list.firstVisibleItemIndex in minOf(from, index)..maxOf(from, index)) {
            // Keep the first visible slot from following a reordered row's key.
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        }
        preview = preview.toMutableList().apply { add(index, removeAt(from)) }
        return true
    }
    fun place(y: Float) {
        val viewport = list.layoutInfo
        if (y < viewport.viewportStartOffset || y > viewport.viewportEndOffset) return
        val from = preview.indexOfFirst { it.id == original.id }
        val neighbors = viewport.visibleItemsInfo.filter { it.key != original.id }
        val next = neighbors.firstOrNull { y < it.offset + it.size / 2f }
        val anchor = next ?: neighbors.lastOrNull() ?: return
        val anchorIndex = preview.indexOfFirst { it.id == anchor.key }
        if (anchorIndex < 0) return
        val insertion = anchorIndex + if (next == null) 1 else 0
        moveTo(insertion - if (from < insertion) 1 else 0)
    }
    LaunchedEffect(choosing, parent) {
        if (!choosing && index >= 0 && list.layoutInfo.visibleItemsInfo.none { it.key == original.id }) {
            list.scrollToItem(index)
        }
    }
    LaunchedEffect(dragY != null) {
        while (dragY != null) {
            withFrameNanos { }
            val y = dragY ?: break
            val viewport = list.layoutInfo
            val delta = when {
                y < viewport.viewportStartOffset + edge -> -step
                y > viewport.viewportEndOffset - edge -> step
                else -> 0f
            }
            if (delta != 0f && list.scrollBy(delta) != 0f) place(y)
        }
    }
    LaunchedEffect(result) {
        if (result != null && result.requestId == request) {
            request = null
            onSaving(false)
            if (result.error == null) onClose()
            else {
                error = result
                if (result.error == "channel_position_changed") live.firstOrNull { it.id == original.id }?.let {
                    original = it
                    selectParent(parent.takeIf { id -> id == 0L || live.any { row -> row.id == id } } ?: it.parent)
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp)) {
        ChannelFormHeader(stringResource(if (choosing) R.string.channel_choose_parent else R.string.channel_move_title), enabled) {
            if (choosing) choosing = false else onClose()
        }
        if (choosing) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                item {
                    ChannelParentRow(stringResource(R.string.channel_root_level), parent == 0L, 0) {
                        if (parent != 0L) selectParent(0)
                        choosing = false
                    }
                }
                items(tree.filter { it.first.id !in excluded && channelSpacer(it.first) == null }, key = { it.first.id }) { (row, depth) ->
                    ChannelParentRow(row.name, row.id == parent, depth) {
                        if (parent != row.id) selectParent(row.id)
                        choosing = false
                    }
                }
            }
        } else {
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth().clip(formShape).background(channelFormGroup).heightIn(min = 72.dp)
                .clickable(interactionSource = null, indication = RowPressFeedback, enabled = enabled && dragY == null,
                    role = Role.Button) { choosing = true }
                .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.channel_move_target), Modifier.weight(0.8f), fontSize = 17.sp)
                Text(if (parent == 0L) stringResource(R.string.channel_as_root) else
                    stringResource(R.string.channel_as_child, live.firstOrNull { it.id == parent }?.name.orEmpty()),
                    Modifier.weight(1.2f).padding(end = 8.dp), textAlign = TextAlign.End, color = Palette.accent, fontSize = 17.sp)
                Icon(UiIcons.ChevronRight, null, Modifier.size(14.dp), tint = Palette.accent)
            }
            Text(stringResource(R.string.channel_move_drag), Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 24.dp),
                color = Palette.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = list,
                verticalArrangement = Arrangement.spacedBy(30.dp)) {
                items(preview, key = { it.id }) { row ->
                    val selected = row.id == original.id
                    val shape = RoundedCornerShape(14.dp)
                    val handle = stringResource(R.string.channel_move_drag)
                    val up = stringResource(R.string.channel_move_up)
                    val down = stringResource(R.string.channel_move_down)
                    val placement = if (selected) Modifier.zIndex(1f).offset {
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.id }
                        val y = dragY
                        IntOffset(0, if (item == null || y == null) 0 else (y - item.offset - item.size / 2f).roundToInt())
                    } else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                        placementSpec = tween(durationMillis = 180, easing = channelMoveEasing))
                    Row(Modifier.fillMaxWidth().then(placement), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).clip(shape).background(if (selected) Palette.selected else Palette.card)
                            .border(1.dp, if (selected) Palette.accent else Palette.border, shape)
                            .heightIn(min = 48.dp).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelIcon(row)
                            Text(channelSpacer(row)?.takeIf { it.isNotEmpty() } ?: row.name, Modifier.weight(1f), fontSize = 17.sp, maxLines = 2)
                        }
                        if (selected) {
                            Box(Modifier.size(44.dp)
                                .semantics {
                                    contentDescription = handle
                                    if (enabled && dragY == null) customActions = listOf(
                                        CustomAccessibilityAction(up) { moveTo(preview.indexOfFirst { it.id == row.id } - 1) },
                                        CustomAccessibilityAction(down) { moveTo(preview.indexOfFirst { it.id == row.id } + 1) },
                                    )
                                }
                                .pointerInput(row.id, enabled) {
                                    if (enabled) detectDragGestures(
                                        onDragStart = {
                                            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == row.id }
                                            if (item != null) {
                                                beforeDrag = item.index
                                                dragY = item.offset + item.size / 2f
                                            }
                                        },
                                        onDragEnd = { dragY = null; beforeDrag = null },
                                        onDragCancel = {
                                            beforeDrag?.let(::moveTo)
                                            dragY = null; beforeDrag = null
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragY?.let { old -> (old + amount.y).also { dragY = it; place(it) } }
                                        },
                                    )
                                }, contentAlignment = Alignment.Center) {
                                Icon(UiIcons.ChannelReorderHandle, null, Modifier.size(22.dp), tint = Color(0xFF49494B))
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(vertical = 8.dp)) {
                ChannelFormError(error)
                PressFeedback(RowPressFeedback) { source, feedback, _ ->
                    Button(
                        onClick = {
                            val id = UUID.randomUUID().toString()
                            request = id; error = null; onSaving(true)
                            val siblings = JSONArray().apply {
                                expected.forEach { put(JSONObject().put("id", it.id).put("order", it.order)) }
                            }
                            ClientSession.channelCommand("move", selection.target, original.id, id,
                                JSONObject().put("parent", parent).put("order", order).put("original_parent", original.parent)
                                    .put("original_order", original.order).put("siblings", siblings))
                        },
                        enabled = enabled && dragY == null && index >= 0 && (parent != original.parent || order != original.order),
                        modifier = feedback.fillMaxWidth().heightIn(min = 68.dp),
                        shape = RoundedCornerShape(14.dp), interactionSource = source,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accent, contentColor = Palette.text,
                            disabledContainerColor = if (enabled) Palette.card else Palette.accent,
                            disabledContentColor = if (enabled) Palette.muted else Palette.text),
                    ) {
                        if (enabled) Text(stringResource(R.string.channel_move_save), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        else CircularProgressIndicator(Modifier.size(20.dp), color = Palette.text, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelParentRow(name: String, selected: Boolean, depth: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(channelFormGroup).heightIn(min = 48.dp)
        .clickable(interactionSource = null, indication = RowPressFeedback, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp).padding(start = (depth.coerceAtMost(4) * 10).dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), fontSize = 17.sp)
        if (selected) Icon(UiIcons.Check, null, Modifier.size(18.dp), tint = Palette.accent)
    }
}

@Composable
internal fun ChannelEditor(
    selection: ChannelEditSelection,
    current: Channel,
    results: Map<String, ChannelCommandResult>,
    onSaving: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val channel = selection.channel
    var name by remember(selection) { mutableStateOf(channel.name) }
    var topic by remember(selection) { mutableStateOf(channel.topic.orEmpty()) }
    var originalDescription by remember(selection) { mutableStateOf(channel.description) }
    var description by remember(selection) { mutableStateOf(channel.description.orEmpty()) }
    var passwordMode by remember(selection) { mutableStateOf("keep") }
    var password by remember(selection) { mutableStateOf("") }
    var limitMode by remember(selection) { mutableStateOf(channel.maxClients?.mode ?: "unknown") }
    var limit by remember(selection) { mutableStateOf(channel.maxClients?.value?.toString().orEmpty()) }
    var type by remember(selection) { mutableStateOf(channel.channelType ?: "unknown") }
    var request by remember(selection) { mutableStateOf<String?>(null) }
    var error by remember(selection) { mutableStateOf<ChannelCommandResult?>(null) }
    val focus = LocalFocusManager.current
    val enabled = request == null

    LaunchedEffect(current.description) {
        current.description?.let { value ->
            if (originalDescription == null) {
                originalDescription = value
                description = value
            }
        }
    }
    val result = results["edit"]
    LaunchedEffect(result) {
        if (result != null && result.requestId == request) {
            request = null
            onSaving(false)
            if (result.error == null) onClose() else error = result
        }
    }

    val count = limit.toIntOrNull()?.takeIf { it in 0..65_535 }
    val valid = name.isNotBlank() && (passwordMode != "set" || password.isNotEmpty())
        && (limitMode != "limited" || count != null)
    val changes = JSONObject().apply {
        if (name != channel.name) put("name", name)
        if (topic != channel.topic.orEmpty()) put("topic", topic)
        if (originalDescription != null && description != originalDescription) put("description", description)
        if (passwordMode == "set") put("password", JSONObject().put("mode", "set").put("value", password))
        if (passwordMode == "remove") put("password", JSONObject().put("mode", "remove"))
        if (limitMode == "unlimited" && channel.maxClients?.mode != "unlimited") {
            put("max_clients", JSONObject().put("mode", "unlimited"))
        }
        if (limitMode == "limited" && count != null &&
            (channel.maxClients?.mode != "limited" || count != channel.maxClients.value)) {
            put("max_clients", JSONObject().put("mode", "limited").put("value", count))
        }
        if (type != "unknown" && type != channel.channelType) put("channel_type", type)
    }
    val unknown = "unknown" to R.string.channel_edit_unknown

    Column(Modifier.fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
        ChannelFormHeader(stringResource(R.string.channel_edit_title), enabled, onClose)
        Text(channel.name, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
        Column(Modifier.fillMaxWidth().clip(formShape).background(channelFormGroup)) {
            EditField(name, { name = it }, R.string.channel_edit_name, enabled)
            EditDivider()
            EditField(topic, { topic = it }, R.string.channel_edit_topic, enabled)
            EditDivider()
            if (originalDescription != null) {
                EditField(description, { description = it }, R.string.channel_edit_description, enabled, multiline = true)
            } else {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.channel_edit_description), color = Palette.muted, fontSize = 15.sp)
                    if (results["description"]?.error != null) {
                        Text(stringResource(R.string.error_channel_description_failed), color = Palette.muted, fontSize = 13.sp)
                    } else {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Palette.accent, strokeWidth = 2.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(36.dp))
        Column(Modifier.fillMaxWidth().clip(formShape).background(channelFormGroup)) {
            EditChoice(R.string.channel_password, passwordMode, listOf(
                "keep" to R.string.channel_edit_password_keep,
                "set" to R.string.channel_edit_password_set,
                "remove" to R.string.channel_edit_password_remove,
            ), enabled) { passwordMode = it; if (it != "set") password = "" }
            if (passwordMode == "set") {
                EditDivider()
                EditField(password, { password = it }, R.string.channel_edit_password_set, enabled, keyboard = KeyboardType.Password)
            }
            EditDivider()
            EditChoice(R.string.channel_edit_max_clients, limitMode,
                listOfNotNull(unknown.takeIf { channel.maxClients == null }) + listOf(
                    "unlimited" to R.string.channel_edit_unlimited,
                    "limited" to R.string.channel_edit_limited,
                ), enabled) { limitMode = it }
            if (limitMode == "limited") {
                EditDivider()
                EditField(limit, { limit = it }, R.string.channel_edit_max_clients, enabled, keyboard = KeyboardType.Number)
            }
            EditDivider()
            EditChoice(R.string.channel_edit_type, type,
                listOfNotNull(unknown.takeIf { channel.channelType == null }) + listOf(
                    "temporary" to R.string.channel_edit_temporary,
                    "semi_permanent" to R.string.channel_edit_semi_permanent,
                    "permanent" to R.string.channel_edit_permanent,
                ), enabled) { type = it }
        }
        Spacer(Modifier.height(36.dp))
        ChannelFormError(error)
        ChannelFormSubmit(stringResource(R.string.action_save), enabled && valid && changes.length() != 0, !enabled, channelFormGroup) {
            val id = UUID.randomUUID().toString()
            request = id; error = null; focus.clearFocus(); onSaving(true)
            ClientSession.channelCommand("edit", selection.target, channel.id, id, changes)
        }
    }
}

@Composable
private fun EditField(
    value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, multiline: Boolean = false,
) {
    val height = when {
        multiline -> 148.dp
        keyboard != KeyboardType.Text -> 52.dp
        else -> 74.dp
    }
    TextField(
        value, onChange, Modifier.fillMaxWidth().heightIn(min = height), enabled = enabled,
        label = if (keyboard == KeyboardType.Text) { { Text(stringResource(label), fontSize = 15.sp) } } else null,
        placeholder = if (keyboard != KeyboardType.Text) { { Text(stringResource(label), color = Palette.muted) } } else null,
        singleLine = !multiline, minLines = if (multiline) 3 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
            focusedTextColor = Palette.text, unfocusedTextColor = Palette.text,
            focusedLabelColor = Palette.muted, unfocusedLabelColor = Palette.muted,
        ),
    )
}

@Composable
private fun EditDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = Color(0xFF38383A))
}

@Composable
private fun EditChoice(label: Int, value: String, options: List<Pair<String, Int>>, enabled: Boolean, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(interactionSource = null, indication = RowPressFeedback, enabled = enabled, role = Role.Button) { expanded = true }
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(label), Modifier.weight(0.9f), fontSize = 17.sp, color = Palette.text)
            Text(stringResource(options.first { it.first == value }.second),
                Modifier.weight(1.1f).padding(start = 12.dp), fontSize = 17.sp,
                textAlign = TextAlign.End, color = Palette.accent)
            Icon(UiIcons.ChevronsUpDown, null, Modifier.padding(start = 6.dp).size(14.dp), tint = Palette.accent)
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }, containerColor = Palette.card) {
            options.forEach { (key, text) ->
                val isSelected = key == value
                DropdownMenuItem(
                    text = { Text(stringResource(text)) },
                    onClick = { expanded = false; select(key) },
                    modifier = Modifier.semantics {
                        role = Role.RadioButton
                        selected = isSelected
                    },
                    leadingIcon = {
                        Box(Modifier.size(18.dp)) {
                            if (isSelected) Icon(UiIcons.Check, null, Modifier.fillMaxSize(), tint = Palette.text)
                        }
                    },
                )
            }
        }
    }
}
