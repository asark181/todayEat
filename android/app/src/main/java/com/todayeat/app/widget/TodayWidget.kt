package com.todayeat.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.todayeat.app.MainActivity
import com.todayeat.app.R
import com.todayeat.app.TodayEatApp
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.DayState
import com.todayeat.app.domain.Days
import com.todayeat.app.domain.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/** 2×2 桌面小组件：醒来不用解锁进 App，看一眼就知道今天吃没吃 */
class TodayWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return@withContext
            val app = context.applicationContext as TodayEatApp
            val now = LocalDateTime.now()
            val today = now.toLocalDate()
            val info = Days.info(today, app.repository.activePlans(), app.repository.dosesBetween(today, today), now)
            val line = Copy.headline(info, now)
            val title = when (info.state) {
                DayState.ALL -> "吃过了"
                DayState.NONE -> "今天不用吃"
                DayState.PENDING -> "还没到时间"
                DayState.PARTIAL -> line.title
                else -> "还没吃"
            }
            val sub = when (info.state) {
                DayState.ALL -> info.doses.maxByOrNull { it.takenAt }?.let { Copy.hm(it.takenAt) + " 吃的" } ?: ""
                else -> line.sub
            }
            val dot = when (info.state) {
                DayState.ALL -> R.drawable.widget_dot_taken
                DayState.NONE -> R.drawable.widget_dot_none
                else -> R.drawable.widget_dot_pending
            }
            val date = "${today.monthValue}月${today.dayOfMonth}日 周${Schedule.WEEK_SHORT[today.dayOfWeek.value - 1]}"
            val open = PendingIntent.getActivity(
                context, 31,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.widget_date, date)
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_sub, sub)
                setImageViewResource(R.id.widget_dot, dot)
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
