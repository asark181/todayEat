package com.todayeat.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.todayeat.app.data.DoseEntity
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.domain.Copy
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.Chip
import com.todayeat.app.ui.components.ConfirmDialog
import com.todayeat.app.ui.components.Divider
import com.todayeat.app.ui.components.Field
import com.todayeat.app.ui.components.FieldLabel
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.PhotoThumb
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.SheetHeader
import com.todayeat.app.ui.components.SoftButton
import com.todayeat.app.ui.components.WheelTimePicker
import com.todayeat.app.ui.components.press
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 某一天的记录列表：标题行带「添加」，每条点开编辑 */
@Composable
fun RecordList(
    title: String,
    doses: List<DoseEntity>,
    plans: Map<String, PlanEntity>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onOpen: (DoseEntity) -> Unit
) {
    val c = T.c
    val viewer = LocalViewer.current
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = T.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
        if (canAdd) {
            Row(Modifier.press(onClick = onAdd).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(GlyphKind.Plus, c.sage, 18.dp)
                Spacer(Modifier.width(4.dp))
                Text("添加", style = T.body, color = c.sage)
            }
        }
    }
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
        if (doses.isEmpty()) {
            Text("无记录", style = T.body, color = c.ink3, modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp))
        }
        doses.sortedBy { it.takenAt }.forEachIndexed { i, d ->
            if (i > 0) Divider()
            val plan = plans[d.planUid]
            Row(
                Modifier.fillMaxWidth().press { onOpen(d) }.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(Copy.hm(d.takenAt), style = T.title.merge(T.digits), color = c.ink, modifier = Modifier.widthIn(min = 64.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        (plan?.name ?: "已删除的药") + if (plan?.dose?.isNotBlank() == true) " · ${plan.dose}" else "",
                        style = T.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (d.note.isNotBlank()) {
                        Text(d.note, style = T.small, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (d.photoIds.isNotEmpty()) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.photoIds.forEach { id -> PhotoThumb(id, 44.dp) { viewer.photo = id } }
                        }
                    }
                }
                Glyph(GlyphKind.Chevron, c.ink3, 16.dp)
            }
        }
    }
}

/** 添加或编辑一条记录 */
@Composable
fun ColumnScope.RecordEditor(vm: AppViewModel, initial: DoseEntity, isNew: Boolean, close: () -> Unit) {
    val c = T.c
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val plans by vm.plans.collectAsState()
    val allPlans by vm.allPlans.collectAsState()
    val initialTime = remember { Instant.ofEpochMilli(initial.takenAt).atZone(ZoneId.systemDefault()).toLocalTime() }
    var planUid by remember { mutableStateOf(initial.planUid) }
    var date by remember { mutableStateOf(LocalDate.parse(initial.date)) }
    var hour by remember { mutableStateOf(initialTime.hour) }
    var minute by remember { mutableStateOf(initialTime.minute) }
    var note by remember { mutableStateOf(initial.note) }
    var photos by remember { mutableStateOf(initial.photoIds) }
    var open by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    // 原来那种药已经删了，也留在选项里，免得改别的字段时被换掉
    val choices = plans + listOfNotNull(allPlans[initial.planUid]?.takeIf { p -> plans.none { it.uid == p.uid } })
    LaunchedEffect(choices.size) { if (planUid.isBlank()) choices.firstOrNull()?.let { planUid = it.uid } }

    SheetHeader(if (isNew) "添加记录" else "编辑记录", close)

    FieldLabel("药品")
    if (choices.isEmpty()) {
        Text("还没有药品，请先在「设置」里添加", style = T.body, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { p -> Chip(p.name, p.uid == planUid) { planUid = p.uid } }
        }
    }

    Spacer(Modifier.height(18.dp))
    FieldLabel("时间")
    ValueRow("日期", Copy.dayShort(date), open == "date") { open = if (open == "date") null else "date" }
    if (open == "date") {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
            }
        )
        LaunchedEffect(state.selectedDateMillis) {
            state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        }
        DatePicker(
            state = state, title = null, headline = null, showModeToggle = false,
            colors = DatePickerDefaults.colors(
                containerColor = c.card, selectedDayContainerColor = c.sage, todayDateBorderColor = c.sage,
                todayContentColor = c.sage, dayContentColor = c.ink, weekdayContentColor = c.ink3, navigationContentColor = c.ink2,
                yearContentColor = c.ink, selectedYearContainerColor = c.sage, currentYearContentColor = c.sage
            )
        )
    }
    Spacer(Modifier.height(8.dp))
    ValueRow("时刻", Copy.hm(hour, minute), open == "time") { open = if (open == "time") null else "time" }
    if (open == "time") {
        Spacer(Modifier.height(8.dp))
        WheelTimePicker(hour, minute) { h, m -> hour = h; minute = m }
    }

    Spacer(Modifier.height(18.dp))
    Field(note, { note = it.take(200) }, "选填", label = "备注")

    Spacer(Modifier.height(18.dp))
    FieldLabel("照片")
    PhotoRow(
        photos,
        onAdd = { uri ->
            scope.launch {
                val id = vm.importPhoto(uri)
                if (id == null) toasts.show("无法读取这张照片") else photos = photos + id
            }
        },
        onRemove = { id -> photos = photos - id }
    )

    Spacer(Modifier.height(24.dp))
    PrimaryButton("保存", Modifier.fillMaxWidth(), enabled = planUid.isNotBlank()) {
        vm.saveDose(
            initial.copy(
                planUid = planUid, date = date.toString(), takenAt = AppViewModel.epoch(date, hour, minute),
                note = note.trim(), photos = photos.joinToString(","), deleted = false
            )
        )
        close()
    }
    if (!isNew) {
        Spacer(Modifier.height(10.dp))
        SoftButton("删除记录", Modifier.fillMaxWidth(), tint = c.clay) { confirmDelete = true }
    }
    if (confirmDelete) {
        ConfirmDialog("删除这条记录？", null, "删除", onConfirm = {
            confirmDelete = false
            vm.deleteDose(initial.uid)
            toasts.show("已删除")
            close()
        }, onDismiss = { confirmDelete = false })
    }
    Box(Modifier.height(4.dp))
}
