package com.todayeat.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.todayeat.app.ui.components.ConfirmDialog
import com.todayeat.app.ui.components.Divider
import com.todayeat.app.ui.components.Glyph
import com.todayeat.app.ui.components.GlyphKind
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.Row2
import com.todayeat.app.ui.components.SectionLabel
import com.todayeat.app.ui.theme.T
import com.todayeat.app.update.ManualCheck
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SettingsScreen(vm: AppViewModel, bottomPad: Dp, onRequestNotifications: () -> Unit) {
    val c = T.c
    val context = LocalContext.current
    val sheets = LocalSheets.current
    val toasts = LocalToasts.current
    val scope = rememberCoroutineScope()
    val sync = vm.app.sync
    val user by sync.username.collectAsState()
    val state by sync.state.collectAsState()
    val plans by vm.plans.collectAsState()
    val manual by vm.app.updates.manual.collectAsState()
    val offer by vm.app.updates.ui.collectAsState()
    // 每 20 秒和回到前台都会变，权限状态顺带刷新
    val now by vm.now.collectAsState()
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = bottomPad + 24.dp)
    ) {
        Text("设置", style = T.title, color = c.ink, modifier = Modifier.padding(vertical = 10.dp))
        Spacer(Modifier.height(12.dp))

        SectionLabel("药品")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            plans.forEachIndexed { i, p ->
                if (i > 0) Divider()
                Row2(
                    p.name + if (p.dose.isNotBlank()) " · ${p.dose}" else "",
                    sub = Schedule.describe(p) + " " + Copy.hm(p.hour, p.minute),
                    value = if (p.active) null else "已停用",
                    onClick = { sheets.show { close -> PlanEditor(vm, p, false, close) } }
                )
            }
            if (plans.isNotEmpty()) Divider()
            Row2("添加药品", valueColor = c.sage, onClick = { sheets.show { close -> PlanEditor(vm, vm.newPlan(), true, close) } }) {
                Glyph(GlyphKind.Plus, c.sage, 18.dp)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("账号与同步")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            if (user == null) {
                Row2("登录", onClick = { sheets.show { close -> LoginSheet(sync, false, close) } })
                Divider()
                Row2("注册", onClick = { sheets.show { close -> LoginSheet(sync, true, close) } })
            } else {
                Row2(user ?: "", sub = syncLine(state), value = null, trailing = {})
                Divider()
                Row2("立即同步", value = if (state == SyncState.Running) "同步中…" else null, onClick = {
                    if (state != SyncState.Running) scope.launch { sync.syncNow() }
                })
                Divider()
                Row2("退出登录", onClick = { confirmSignOut = true })
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("提醒")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            val notifOk = remember(now) { Reminders.canNotify(context) }
            Row2("通知权限", value = if (notifOk) "已开启" else "未开启", valueColor = if (notifOk) c.ink2 else c.clay, onClick = {
                if (!notifOk) onRequestNotifications() else openNotificationSettings(context)
            })
            Divider()
            val batteryOk = remember(now) { context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName) }
            Row2("省电策略", sub = "设为「无限制」，锁屏后提醒更准时", value = if (batteryOk) "无限制" else "未设置",
                valueColor = if (batteryOk) c.ink2 else c.clay, onClick = { openBattery(context) })
            Divider()
            Row2("自启动", sub = "重启手机后提醒仍然有效", onClick = { openAutostart(context) })
            Divider()
            Row2("测试提醒", onClick = {
                val p = plans.firstOrNull()
                if (p == null) toasts.show("请先添加药品")
                else if (!Reminders.canNotify(context)) onRequestNotifications()
                else Reminders.showDue(context, p, LocalDate.now().toString(), 0)
            })
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel("关于")
        Card(padding = PaddingValues(vertical = 4.dp)) {
            Row2(
                "检查更新",
                value = when {
                    offer != null -> "新版本 ${offer?.offer?.versionName}"
                    manual == ManualCheck.Checking -> "检查中…"
                    manual == ManualCheck.UpToDate -> "已是最新"
                    manual is ManualCheck.Failed -> "网络错误"
                    else -> "v${BuildConfig.VERSION_NAME}"
                },
                valueColor = if (offer != null) c.sage else c.ink2,
                onClick = { vm.app.updates.checkFromUser() }
            )
        }
    }

    if (confirmSignOut) {
        ConfirmDialog("退出登录？", "手机上的记录保留，不再同步。", "退出", onConfirm = {
            confirmSignOut = false
            scope.launch { sync.signOut(); toasts.show("已退出") }
        }, onDismiss = { confirmSignOut = false })
    }
}

private fun syncLine(s: SyncState): String = when (s) {
    SyncState.Idle -> "未同步"
    SyncState.Running -> "同步中…"
    is SyncState.Done -> "上次同步 " + Instant.ofEpochMilli(s.at).atZone(ZoneId.systemDefault()).let {
        val d = it.toLocalDate()
        (if (d == LocalDate.now()) "今天 " else "${d.monthValue}月${d.dayOfMonth}日 ") + Copy.hm(s.at)
    }
    is SyncState.Failed -> s.reason
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
