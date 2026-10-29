package com.todayeat.app.ui.me

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todayeat.app.BuildConfig
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.Schedule
import com.todayeat.app.notify.Reminders
import com.todayeat.app.sync.SyncState
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.common.LoginSheet
import com.todayeat.app.ui.common.PlanEditor
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.Divider
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.Row2
import com.todayeat.app.ui.components.SectionLabel
import com.todayeat.app.ui.components.SoftButton
import com.todayeat.app.ui.theme.T
import com.todayeat.app.update.ManualCheck
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun MeScreen(vm: AppViewModel, bottomPad: Dp, onRequestNotifications: () -> Unit) {
    val c = T.c
    val context = LocalContext.current
    val sheets = LocalSheets.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val sync = vm.app.sync
    val user by sync.username.collectAsState()
    val state by sync.state.collectAsState()
    val plans by vm.plans.collectAsState()
    val course by vm.courseStart.collectAsState()
    val manual by vm.app.updates.manual.collectAsState()
    val offer by vm.app.updates.ui.collectAsState()
    // 每次回到这页都会重组，权限状态顺带刷新
    val now by vm.now.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = bottomPad + 24.dp)
    ) {
        Text("我的", style = T.title, color = c.ink)
        Spacer(Modifier.height(20.dp))

        // ───── 账号与同步 ─────
        Card {
            if (user == null) {
                Text("登录后，换手机记录也不会丢", style = T.bodyStrong, color = c.ink)
                Text("打卡、药和照片会加密后存到云端，多台手机自动同步。", style = T.small, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                Row {
                    PrimaryButton("注册", Modifier.weight(1f)) { sheets.show { close -> LoginSheet(sync, true, close) } }
                    Spacer(Modifier.width(10.dp))
                    SoftButton("登录", Modifier.weight(1f)) { sheets.show { close -> LoginSheet(sync, false, close) } }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(GlyphKind.Cloud, c.sage, 24.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user ?: "", style = T.bodyStrong, color = c.ink)
                        Text(syncLine(state), style = T.tiny, color = if (state is SyncState.Failed) c.clay else c.ink3)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    SoftButton(if (state == SyncState.Running) "同步中…" else "现在同步", Modifier.weight(1f), enabled = state != SyncState.Running) {
                        scope.launch { sync.syncNow() }
                    }
                    Spacer(Modifier.width(10.dp))
                    SoftButton("退出登录", Modifier.weight(1f), tint = c.ink2) {
                        sheets.show { close ->
                            Text("退出登录？", style = T.title, color = c.ink)
                            Text("手机上的记录会留着，只是不再同步。", style = T.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
                            Spacer(Modifier.height(20.dp))
                            PrimaryButton("退出", Modifier.fillMaxWidth(), color = c.clay) {
                                scope.launch { sync.signOut(); toasts.show("已退出") }
                                close()
                            }
                        }
                    }
                }
            }
        }

        // ───── 药和提醒 ─────
        Spacer(Modifier.height(24.dp))
        SectionLabel("药和提醒")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            plans.forEachIndexed { i, p ->
                if (i > 0) Divider()
                Row2(
                    p.name,
                    sub = Schedule.describe(p) + " " + Copy.hm(p.hour, p.minute) + if (p.dose.isNotBlank()) " · ${p.dose}" else "",
                    value = if (p.active) null else "已停",
                    onClick = { sheets.show { close -> PlanEditor(vm, p, false, close) } }
                )
            }
            if (plans.isNotEmpty()) Divider()
            Row2("添加一种药", onClick = { sheets.show { close -> PlanEditor(vm, vm.newPlan(), true, close) } }) {
                Glyph(GlyphKind.Plus, c.sage, 18.dp)
            }
        }

        // ───── 提醒能不能准时 ─────
        Spacer(Modifier.height(24.dp))
        SectionLabel("让提醒准时响（小米手机建议都打开）")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            val notifOk = remember(now) { Reminders.canNotify(context) }
            Row2("通知", value = if (notifOk) "已开" else "没开", valueColor = if (notifOk) c.sage else c.clay, onClick = {
                if (!notifOk) onRequestNotifications() else openNotificationSettings(context)
            })
            Divider()
            val batteryOk = remember(now) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
            Row2("省电策略设为无限制", sub = "不然深夜可能被系统冻住", value = if (batteryOk) "已设置" else "去设置",
                valueColor = if (batteryOk) c.sage else c.clay, onClick = { openBattery(context) })
            Divider()
            Row2("允许自启动", sub = "重启手机后提醒还在", onClick = { openAutostart(context) })
            Divider()
            Row2("现在响一次试试", sub = "点通知上的「已吃」会真的记一笔", onClick = {
                val p = plans.firstOrNull()
                if (p == null) toasts.show("先添加一种药")
                else if (!Reminders.canNotify(context)) onRequestNotifications()
                else Reminders.showDue(context, p, LocalDate.now().toString(), 0)
            })
        }

        // ───── 其他 ─────
        Spacer(Modifier.height(24.dp))
        SectionLabel("其他")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            Row2("开始吃药的日子", sub = "首页会写「开始吃药的第几天」", value = course?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "没设",
                onClick = { sheets.show { close -> CoursePicker(vm, course, close) } })
            Divider()
            Row2(
                "检查更新",
                value = when {
                    offer != null -> "有新版本 ${offer?.offer?.versionName}"
                    manual == ManualCheck.Checking -> "正在检查…"
                    manual == ManualCheck.UpToDate -> "已是最新"
                    manual is ManualCheck.Failed -> "没连上，再点一次"
                    else -> "v${BuildConfig.VERSION_NAME}"
                },
                valueColor = if (offer != null) c.sage else c.ink2,
                onClick = { vm.app.updates.checkFromUser() }
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            "吃了吗 v${BuildConfig.VERSION_NAME}\n记录先存在手机里；登录后加密同步，服务器看不到内容。\n按时吃药，早日好起来。",
            style = T.tiny, color = c.ink3, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun <T> remember(key: Any?, calc: () -> T): T = androidx.compose.runtime.remember(key) { calc() }

private fun syncLine(s: SyncState): String = when (s) {
    SyncState.Idle -> "还没同步过"
    SyncState.Running -> "正在同步…"
    is SyncState.Done -> "上次同步 " + Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault()).let {
        val d = it.toLocalDate()
        (if (d == LocalDate.now()) "今天 " else "${d.monthValue}月${d.dayOfMonth}日 ") + Copy.hm(s.at)
    }
    is SyncState.Failed -> s.reason
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CoursePicker(vm: AppViewModel, current: LocalDate?, close: () -> Unit) {
    val c = T.c
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (current ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= System.currentTimeMillis()
        }
    )
    Text("从哪天开始吃药", style = T.title, color = c.ink)
    DatePicker(
        state = state, title = null, headline = null, showModeToggle = false,
        colors = DatePickerDefaults.colors(containerColor = c.card, selectedDayContainerColor = c.sage, todayDateBorderColor = c.sage,
            todayContentColor = c.sage, dayContentColor = c.ink, weekdayContentColor = c.ink3, navigationContentColor = c.ink2,
            yearContentColor = c.ink, selectedYearContainerColor = c.sage, currentYearContentColor = c.sage)
    )
    PrimaryButton("就是这天", Modifier.fillMaxWidth()) {
        state.selectedDateMillis?.let { vm.setCourseStart(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        close()
    }
    if (current != null) {
        Spacer(Modifier.height(10.dp))
        SoftButton("不显示了", Modifier.fillMaxWidth(), tint = c.ink2) {
            vm.setCourseStart(null)
            close()
        }
    }
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
}

private fun openBattery(context: Context) {
    val pm = context.getSystemService(PowerManager::class.java)
    val intent = if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    } else appDetails(context)
    runCatching { context.startActivity(intent) }.onFailure { context.startActivity(appDetails(context)) }
}

/** 小米安全中心的自启动管理；别的系统打开应用详情 */
private fun openAutostart(context: Context) {
    val miui = Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    runCatching { context.startActivity(miui) }.onFailure { context.startActivity(appDetails(context)) }
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
