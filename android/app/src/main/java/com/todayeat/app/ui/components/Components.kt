package com.todayeat.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.todayeat.app.TodayEatApp
import com.todayeat.app.ui.theme.T
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 按下轻轻缩一下的点击 */
@Composable
fun Modifier.press(enabled: Boolean = true, haptic: Boolean = false, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    val h = LocalHapticFeedback.current
    return this
        .scale(s)
        .clickable(interactionSource = source, indication = null, enabled = enabled) {
            if (haptic) h.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(20.dp), content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(T.c.card)
            .padding(padding),
        content = content
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = T.c.sage, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) color else T.c.line)
            .press(enabled, haptic = true, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.bodyStrong, color = if (enabled) T.c.onSage else T.c.ink3)
    }
}

@Composable
fun SoftButton(text: String, modifier: Modifier = Modifier, tint: Color = T.c.ink, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(T.c.paper)
            .press(enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.bodyStrong, color = if (enabled) tint else T.c.ink3)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = T.c
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.sageSoft else c.paper)
            .border(1.dp, if (selected) c.sage else Color.Transparent, RoundedCornerShape(14.dp))
            .press(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = T.small, color = if (selected) c.sage else c.ink2)
    }
}

@Composable
fun Row2(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    value: String? = null,
    valueColor: Color = T.c.ink2,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {
        if (onClick != null) Glyph(GlyphKind.Chevron, T.c.ink3, 16.dp)
    }
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body, color = T.c.ink)
            if (sub != null) Text(sub, style = T.tiny, color = T.c.ink3, modifier = Modifier.padding(top = 2.dp))
        }
        if (value != null) Text(value, style = T.small, color = valueColor, modifier = Modifier.padding(start = 12.dp, end = 6.dp))
        trailing()
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(T.c.line))
}

@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = T.c
    Switch(
        checked = checked, onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onSage, checkedTrackColor = c.sage,
            uncheckedThumbColor = c.card, uncheckedTrackColor = c.line, uncheckedBorderColor = c.line
        )
    )
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    val c = T.c
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(c.paper)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) Text(placeholder, style = T.body, color = c.ink3)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = T.body.copy(color = c.ink),
            cursorBrush = SolidColor(c.sage),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

enum class GlyphKind { Check, Camera, Plus, Chevron, Close, Back, Cloud }

/** 自绘的几个线条小图标，粗细统一 */
@Composable
fun Glyph(kind: GlyphKind, color: Color, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val st = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            GlyphKind.Check -> drawPath(Path().apply {
                moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.72f); lineTo(w * 0.8f, w * 0.3f)
            }, color, style = st)
            GlyphKind.Plus -> {
                drawLine(color, Offset(w * 0.5f, w * 0.2f), Offset(w * 0.5f, w * 0.8f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, w * 0.5f), Offset(w * 0.8f, w * 0.5f), st.width, StrokeCap.Round)
            }
            GlyphKind.Close -> {
                drawLine(color, Offset(w * 0.25f, w * 0.25f), Offset(w * 0.75f, w * 0.75f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.75f, w * 0.25f), Offset(w * 0.25f, w * 0.75f), st.width, StrokeCap.Round)
            }
            GlyphKind.Chevron -> drawPath(Path().apply {
                moveTo(w * 0.38f, w * 0.22f); lineTo(w * 0.66f, w * 0.5f); lineTo(w * 0.38f, w * 0.78f)
            }, color, style = st)
            GlyphKind.Back -> drawPath(Path().apply {
                moveTo(w * 0.62f, w * 0.22f); lineTo(w * 0.34f, w * 0.5f); lineTo(w * 0.62f, w * 0.78f)
            }, color, style = st)
            GlyphKind.Camera -> {
                drawRoundRect(color, Offset(w * 0.12f, w * 0.3f), androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.52f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = st)
                drawLine(color, Offset(w * 0.36f, w * 0.3f), Offset(w * 0.42f, w * 0.18f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.42f, w * 0.18f), Offset(w * 0.58f, w * 0.18f), st.width, StrokeCap.Round)
                drawLine(color, Offset(w * 0.58f, w * 0.18f), Offset(w * 0.64f, w * 0.3f), st.width, StrokeCap.Round)
                drawCircle(color, w * 0.14f, Offset(w * 0.5f, w * 0.56f), style = st)
            }
            GlyphKind.Cloud -> drawPath(Path().apply {
                moveTo(w * 0.28f, w * 0.74f)
                cubicTo(w * 0.08f, w * 0.74f, w * 0.08f, w * 0.46f, w * 0.3f, w * 0.46f)
                cubicTo(w * 0.32f, w * 0.24f, w * 0.62f, w * 0.2f, w * 0.68f, w * 0.42f)
                cubicTo(w * 0.92f, w * 0.42f, w * 0.94f, w * 0.74f, w * 0.72f, w * 0.74f)
                close()
            }, color, style = st)
        }
    }
}

/** 打卡圆：空心 → 实心带勾，带一点弹 */
@Composable
fun CheckDot(checked: Boolean, size: Dp = 32.dp, accent: Color = T.c.sage, modifier: Modifier = Modifier) {
    val c = T.c
    val k by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 400f), label = "check")
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (checked) accent else Color.Transparent)
            .border(2.dp, if (checked) accent else c.line, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (k > 0.01f) Glyph(GlyphKind.Check, c.onSage, size * 0.62f, Modifier.scale(k))
    }
}

// ───────────────────────── 底部面板（同一窗口内绘制，不用 Dialog） ─────────────────────────

class Sheets {
    var content by mutableStateOf<(@Composable ColumnScope.(close: () -> Unit) -> Unit)?>(null)
        private set
    var visible by mutableStateOf(false)
        private set

    private var onDismiss: (() -> Unit)? = null

    fun show(onDismiss: (() -> Unit)? = null, body: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
        this.onDismiss?.invoke()
        this.onDismiss = onDismiss
        content = body
        visible = true
    }

    fun close() {
        visible = false
        onDismiss?.invoke()
        onDismiss = null
    }
}

val LocalSheets = staticCompositionLocalOf { Sheets() }

@Composable
fun SheetHost(sheets: Sheets) {
    val c = T.c
    if (sheets.visible) BackHandler { sheets.close() }
    AnimatedVisibility(sheets.visible, enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.scrim)
                .clickable(remember { MutableInteractionSource() }, null) { sheets.close() }
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            sheets.visible,
            enter = slideInVertically(spring(dampingRatio = 0.86f, stiffness = 500f)) { it },
            exit = slideOutVertically(tween(200)) { it }
        ) {
            val body = sheets.content
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(c.card)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 20.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(CircleShape).background(c.line))
                Spacer(Modifier.height(14.dp))
                body?.invoke(this) { sheets.close() }
            }
        }
    }
}

// ───────────────────────── 轻提示 ─────────────────────────

class Toasts {
    var text by mutableStateOf<String?>(null)
        private set
    private var seq by mutableStateOf(0)
    val key get() = seq

    fun show(message: String) {
        text = message
        seq++
    }

    fun clear() {
        text = null
    }
}

val LocalToasts = staticCompositionLocalOf { Toasts() }

@Composable
fun ToastHost(toasts: Toasts) {
    val c = T.c
    val msg = toasts.text
    LaunchedEffect(toasts.key) {
        if (msg != null) {
            delay(2400)
            toasts.clear()
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 96.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(msg != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Text(
                toasts.text ?: "",
                style = T.small,
                color = c.paper,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(c.ink.copy(alpha = 0.92f))
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

// ───────────────────────── 照片缩略图 ─────────────────────────

@Composable
fun PhotoThumb(id: String, size: Dp, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as TodayEatApp
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) {
            if (!app.photos.has(id)) app.sync.ensurePhoto(id)
            app.photos.decode(id, px)?.asImageBitmap()
        }
    }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(T.c.line)
            .press(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text("…", style = T.tiny, color = T.c.ink3)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = T.tiny, color = T.c.ink3, modifier = modifier.padding(start = 6.dp, bottom = 8.dp, top = 4.dp))
}

@Composable
fun TextLink(text: String, color: Color = T.c.sage, style: TextStyle = T.small, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(text, style = style, color = color, modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp))
}
