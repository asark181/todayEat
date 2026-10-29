package com.todayeat.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.todayeat.app.ui.common.LocalViewer
import com.todayeat.app.ui.common.UpdatePanel
import com.todayeat.app.ui.common.Viewer
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.LocalToasts
import com.todayeat.app.ui.components.SheetHost
import com.todayeat.app.ui.components.Sheets
import com.todayeat.app.ui.components.ToastHost
import com.todayeat.app.ui.components.Toasts
import com.todayeat.app.ui.mine.MineScreen
import com.todayeat.app.ui.settings.SettingsScreen
import com.todayeat.app.ui.onboarding.Onboarding
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TABS = listOf("我的", "设置")

@Composable
fun Root(vm: AppViewModel, openUpdate: Boolean) {
    val sheets = remember { Sheets() }
    val toasts = remember { Toasts() }
    val viewer = remember { Viewer() }
    val c = T.c
    val onboarded by vm.onboarded.collectAsState()
    val plans by vm.plans.collectAsState()
    val update by vm.app.updates.ui.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) toasts.show("未开启通知，将收不到提醒")
        vm.tick()
    }
    val askNotifications = {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 有新版本且需要弹出时，用底部面板展示
    LaunchedEffect(update?.sheet, openUpdate) {
        if (update?.sheet == true || (openUpdate && update != null)) {
            sheets.show(onDismiss = { vm.app.updates.dismissSheet() }) { close ->
                val live by vm.app.updates.ui.collectAsState()
                live?.let { UpdatePanel(it, vm.app.updates, close) }
            }
        }
    }

    CompositionLocalProvider(LocalSheets provides sheets, LocalToasts provides toasts, LocalViewer provides viewer) {
        Box(Modifier.fillMaxSize().background(c.paper)) {
            if (!onboarded && plans.isEmpty()) {
                Onboarding(vm) { askNotifications() }
            } else {
                val barHeight = 72.dp
                Crossfade(tab, label = "tab") { t ->
                    when (t) {
                        0 -> MineScreen(vm, barHeight)
                        else -> SettingsScreen(vm, barHeight, askNotifications)
                    }
                }
                if (tab != 0) BackHandler { tab = 0 }
                BottomBar(tab, hasUpdate = update != null, Modifier.align(Alignment.BottomCenter)) { tab = it }
            }
            SheetHost(sheets)
            ToastHost(toasts)
            PhotoViewer(viewer)
        }
    }
}

@Composable
private fun BottomBar(selected: Int, hasUpdate: Boolean, modifier: Modifier, onSelect: (Int) -> Unit) {
    val c = T.c
    Column(modifier.fillMaxWidth().background(c.paper).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            TABS.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier.weight(1f).fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    val lift by animateDpAsState(if (on) (-3).dp else 0.dp, spring(dampingRatio = 0.5f, stiffness = 500f), label = "lift")
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = lift)) {
                        Text(label, style = if (on) T.bodyStrong else T.body, color = if (on) c.ink else c.ink3)
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.size(width = if (on) 16.dp else 4.dp, height = 4.dp).clip(CircleShape)
                            .background(if (on) c.sage else Color.Transparent))
                    }
                    if (i == 1 && hasUpdate) {
                        Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).offset(x = 22.dp).size(7.dp).clip(CircleShape).background(c.clay))
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoViewer(viewer: Viewer) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.todayeat.app.TodayEatApp
    val id = viewer.photo
    if (id != null) BackHandler { viewer.photo = null }
    AnimatedVisibility(id != null, enter = fadeIn(), exit = fadeOut()) {
        val shownId = remember(id) { id } ?: return@AnimatedVisibility
        val bmp by produceState<ImageBitmap?>(null, shownId) {
            value = withContext(Dispatchers.IO) {
                app.sync.ensurePhoto(shownId)
                app.photos.decode(shownId, 2000)?.asImageBitmap()
            }
        }
        var scale by remember(shownId) { mutableFloatStateOf(1f) }
        var dx by remember(shownId) { mutableFloatStateOf(0f) }
        var dy by remember(shownId) { mutableFloatStateOf(0f) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, null) { viewer.photo = null }
                .pointerInput(shownId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) { dx += pan.x; dy += pan.y } else { dx = 0f; dy = 0f }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            bmp?.let {
                Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = dx; translationY = dy
                })
            } ?: Text("加载中…", style = T.small, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
