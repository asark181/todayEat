package com.todayeat.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.todayeat.app.MainActivity
import com.todayeat.app.R
import com.todayeat.app.TodayEatApp
import com.todayeat.app.data.PlanEntity
import com.todayeat.app.domain.Copy
import com.todayeat.app.domain.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒：每个计划一个闹钟。主提醒用 setAlarmClock（HyperOS 省电策略下最准，状态栏会有小闹钟），
 * 没点「已吃」的话按计划再提醒，最多 [MAX_RENAG] 次。
 */
object Reminders {
    const val CHANNEL = "dose_reminders"
    private const val CHANNEL_DONE = "dose_done"
    const val MAX_RENAG = 3
    private const val TAG = "Reminders"

    const val ACTION_FIRE = "com.todayeat.app.FIRE"
    const val ACTION_TAKEN = "com.todayeat.app.TAKEN"
    const val ACTION_SNOOZE = "com.todayeat.app.SNOOZE"
    const val ACTION_MIDNIGHT = "com.todayeat.app.MIDNIGHT"
    const val EXTRA_PLAN = "plan"
    const val EXTRA_DATE = "date"
    const val EXTRA_ATTEMPT = "attempt"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "吃药提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "到点提醒吃药，没点「已吃」会再提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "记录确认", NotificationManager.IMPORTANCE_LOW).apply {
                description = "在通知里点了「已吃」之后的确认，几秒后自动消失"
            }
        )
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun code(planUid: String, slot: Int): Int = (planUid.hashCode() and 0x0FFFFFFF) * 4 + slot
    fun notificationId(planUid: String): Int = planUid.hashCode() and 0x0FFFFFFF
    private fun recordedId(planUid: String): Int = notificationId(planUid) or 0x10000000

    private fun broadcast(context: Context, action: String, planUid: String, slot: Int, extras: Intent.() -> Unit = {}): PendingIntent {
        val i = Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_PLAN, planUid).apply(extras)
        return PendingIntent.getBroadcast(context, code(planUid, slot), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openApp(context: Context, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun epoch(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 重排全部计划的主提醒；删掉或停用的计划取消闹钟 */
    fun rescheduleAll(context: Context, plans: List<PlanEntity>, now: LocalDateTime = LocalDateTime.now()) {
        plans.forEach { schedule(context, it, now) }
        scheduleMidnight(context)
    }

    fun schedule(context: Context, plan: PlanEntity, now: LocalDateTime = LocalDateTime.now()) {
        val am = alarms(context)
        val next = Schedule.nextFire(plan, now)
        val pi = broadcast(context, ACTION_FIRE, plan.uid, 0) {
            putExtra(EXTRA_DATE, next?.toLocalDate()?.toString())
            putExtra(EXTRA_ATTEMPT, 0)
        }
        if (next == null) {
            am.cancel(pi)
            cancelRenag(context, plan.uid)
            return
        }
        am.setAlarmClock(AlarmManager.AlarmClockInfo(epoch(next), openApp(context, 7)), pi)
        Log.i(TAG, "plan ${plan.name} next at $next")
    }

    fun scheduleRenag(context: Context, planUid: String, date: String, attempt: Int, delayMinutes: Int) {
        val at = System.currentTimeMillis() + delayMinutes * 60_000L
        val pi = broadcast(context, ACTION_FIRE, planUid, 1) {
            putExtra(EXTRA_DATE, date)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val am = alarms(context)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancelRenag(context: Context, planUid: String) {
        alarms(context).cancel(broadcast(context, ACTION_FIRE, planUid, 1))
    }

    /** 每天 00:01 刷新小组件（日期变了，「今天」要换） */
    fun scheduleMidnight(context: Context) {
        val next = LocalDate.now().plusDays(1).atTime(0, 1)
        val pi = broadcast(context, ACTION_MIDNIGHT, "midnight", 2)
        alarms(context).setAndAllowWhileIdle(AlarmManager.RTC, epoch(next), pi)
    }

    fun canNotify(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showDue(context: Context, plan: PlanEntity, date: String, attempt: Int) {
        if (!canNotify(context)) return
        val title = if (attempt == 0) "该吃药了" else "${plan.name}还没记上"
        val what = plan.name + if (plan.dose.isNotBlank()) " · ${plan.dose}" else ""
        val body = if (attempt == 0) "$what。吃完点一下「已吃」，醒来就不用猜了。"
        else "${Copy.hm(plan.hour, plan.minute)} 的提醒。吃过了就点「已吃」，还没吃现在吃也行。"
        val taken = broadcast(context, ACTION_TAKEN, plan.uid, 3) { putExtra(EXTRA_DATE, date) }
        val snooze = broadcast(context, ACTION_SNOOZE, plan.uid, 2) {
            putExtra(EXTRA_DATE, date)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F8A76.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, 8))
            .setAutoCancel(true)
            .addAction(0, "已吃", taken)
            .addAction(0, "10 分钟后再提醒", snooze)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(plan.uid), n)
    }

    /** 在通知里点了「已吃」：换成一条安静的确认，几秒后自己消失 */
    fun showRecorded(context: Context, plan: PlanEntity, takenAt: Long) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F8A76.toInt())
            .setContentTitle("记下了，${Copy.hm(takenAt)} 吃的")
            .setContentText("${plan.name}。醒来打开看看就知道。")
            .setTimeoutAfter(6_000)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, 9))
            .build()
        NotificationManagerCompat.from(context).notify(recordedId(plan.uid), n)
    }

    fun cancelShown(context: Context, planUid: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(planUid))
    }
}

/** 闹钟与通知按钮都进这里 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodayEatApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                handle(app, intent)
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "handle ${intent.action}", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(app: TodayEatApp, intent: Intent) {
        val repo = app.repository
        val planUid = intent.getStringExtra(Reminders.EXTRA_PLAN) ?: return
        when (intent.action) {
            Reminders.ACTION_MIDNIGHT -> {
                app.refreshWidgets()
                Reminders.rescheduleAll(app, repo.allPlansForAlarms())
            }
            Reminders.ACTION_FIRE -> {
                val plan = repo.plan(planUid) ?: return
                val attempt = intent.getIntExtra(Reminders.EXTRA_ATTEMPT, 0)
                val date = intent.getStringExtra(Reminders.EXTRA_DATE) ?: LocalDate.now().toString()
                // 主提醒响过就排下一次
                if (attempt == 0) Reminders.schedule(app, plan)
                if (plan.deleted || !plan.active) return
                val already = repo.doseOn(plan.uid, LocalDate.parse(date))
                if (already != null) return
                Reminders.showDue(app, plan, date, attempt)
                if (plan.renag && attempt < Reminders.MAX_RENAG) {
                    Reminders.scheduleRenag(app, plan.uid, date, attempt + 1, plan.renagMinutes.coerceIn(5, 60))
                }
                app.refreshWidgets()
            }
            Reminders.ACTION_TAKEN -> {
                val plan = repo.plan(planUid) ?: return
                val date = intent.getStringExtra(Reminders.EXTRA_DATE)?.let(LocalDate::parse) ?: LocalDate.now()
                Reminders.cancelRenag(app, plan.uid)
                Reminders.cancelShown(app, plan.uid)
                val dose = repo.markTaken(plan.uid, date)
                Reminders.showRecorded(app, plan, dose.takenAt)
            }
            Reminders.ACTION_SNOOZE -> {
                val date = intent.getStringExtra(Reminders.EXTRA_DATE) ?: LocalDate.now().toString()
                val attempt = intent.getIntExtra(Reminders.EXTRA_ATTEMPT, 0)
                Reminders.cancelShown(app, planUid)
                Reminders.scheduleRenag(app, planUid, date, attempt.coerceAtLeast(1), 10)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodayEatApp
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Reminders.rescheduleAll(app, app.repository.allPlansForAlarms())
                app.refreshWidgets()
            } finally {
                pending.finish()
            }
        }
    }
}
