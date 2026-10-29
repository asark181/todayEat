package com.todayeat.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.todayeat.app.TodayEatApp
import com.todayeat.app.BuildConfig
import com.todayeat.app.MainActivity
import com.todayeat.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class UpdatePhase { Available, Downloading, Ready, Failed, NeedsPermission }

data class UpdateUi(
    val offer: UpdateOffer,
    val phase: UpdatePhase,
    val received: Long = 0,
    val total: Long = 0,
    val message: String = "",
    /** 是否弹出更新面板；后台检查只亮红点不打扰 */
    val sheet: Boolean = false
)

/** 「我的」页检查更新那一行的状态 */
sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Checking : ManualCheck
    data object UpToDate : ManualCheck
    data class Failed(val reason: String) : ManualCheck
}

enum class CheckResult { Newer, Current, Failed }

/**
 * 应用内更新（与暖月、记账同一套协议）：检查版本 → 后台下载（断点续传、sha256 校验）→ 调起系统安装器。
 * 缺少「安装未知应用」权限时跳转授权，回到前台后自动继续安装。
 */
class AppUpdate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val currentCode: Int = BuildConfig.VERSION_CODE,
    private val serverUrl: String = BuildConfig.SERVER_URL,
    private val fetcher: ApkFetcher = ApkFetcher(HttpByteSource)
) {
    private val gate = Mutex()
    private val cancelFlag = AtomicBoolean(false)
    private var job: Job? = null
    private var awaitingPermission = false

    private val _ui = MutableStateFlow<UpdateUi?>(null)
    val ui: StateFlow<UpdateUi?> = _ui.asStateFlow()

    private val _manual = MutableStateFlow<ManualCheck>(ManualCheck.Idle)
    val manual: StateFlow<ManualCheck> = _manual.asStateFlow()

    private val dir: File get() = File(context.filesDir, "updates").also { it.mkdirs() }
    private val prefs get() = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    private fun apkFile(code: Int) = File(dir, "todayeat-$code.apk")
    private fun partFile(code: Int) = File(dir, "todayeat-$code.apk.part")

    /** 启动时调用：注册定期检查，并在距上次检查超过 6 小时时静默检查一次 */
    fun start() {
        createChannel()
        scheduleChecks(context)
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last > 6 * 60 * 60 * 1000L) {
            scope.launch { check(present = false, notify = false) }
        }
    }

    /** 「检查更新」按钮 */
    fun checkFromUser() {
        if (_manual.value == ManualCheck.Checking) return
        val existing = _ui.value
        if (existing != null) {
            _ui.value = existing.copy(sheet = true)
            return
        }
        _manual.value = ManualCheck.Checking
        scope.launch {
            val result = check(present = true, notify = false)
            _manual.value = when (result) {
                CheckResult.Current -> ManualCheck.UpToDate
                CheckResult.Failed -> ManualCheck.Failed(lastError ?: "网络异常")
                CheckResult.Newer -> ManualCheck.Idle
            }
        }
    }

    fun dismissSheet() {
        _ui.value = _ui.value?.copy(sheet = false)
    }

    @Volatile private var lastError: String? = null

    suspend fun check(present: Boolean, notify: Boolean): CheckResult = withContext(Dispatchers.IO) {
        gate.withLock {
            val offer = try {
                fetchOffer()
            } catch (e: Exception) {
                Log.w(TAG, "version check failed", e)
                lastError = e.message
                return@withLock CheckResult.Failed
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (offer.versionCode <= currentCode) {
                dir.listFiles()?.forEach { it.delete() }
                _ui.value = null
                return@withLock CheckResult.Current
            }
            // 清理其他版本的残留安装包
            dir.listFiles()?.filter { !it.name.startsWith("todayeat-${offer.versionCode}.") }?.forEach { it.delete() }
            if (notify && prefs.getInt(KEY_NOTIFIED, 0) < offer.versionCode) {
                notifyNew(offer)
                prefs.edit().putInt(KEY_NOTIFIED, offer.versionCode).apply()
            }
            val apk = apkFile(offer.versionCode)
            val phase = if (apk.exists() && verifyApk(apk, offer.sha256)) UpdatePhase.Ready else UpdatePhase.Available
            val keepSheet = present || _ui.value?.sheet == true
            if (_ui.value?.phase != UpdatePhase.Downloading) {
                _ui.value = UpdateUi(offer, phase, sheet = keepSheet)
            }
            CheckResult.Newer
        }
    }

    private fun fetchOffer(): UpdateOffer {
        val conn = (URL(serverUrl.trimEnd('/') + VERSION_PATH + "?t=" + System.currentTimeMillis()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("服务器返回 $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseOffer(body, serverUrl)
        } finally {
            conn.disconnect()
        }
    }

    /** 面板主按钮 */
    fun onPrimary(activityContext: Context) {
        val ui = _ui.value ?: return
        when (ui.phase) {
            UpdatePhase.Available, UpdatePhase.Failed -> download(ui.offer)
            UpdatePhase.Downloading -> cancel()
            UpdatePhase.Ready, UpdatePhase.NeedsPermission -> installOrAsk(activityContext, ui.offer)
        }
    }

    /** 从「安装未知应用」设置页返回后继续安装 */
    fun onHostResume(activityContext: Context) {
        val ui = _ui.value ?: return
        if (awaitingPermission && canInstall(activityContext)) {
            awaitingPermission = false
            installOrAsk(activityContext, ui.offer)
        }
    }

    fun cancel() {
        cancelFlag.set(true)
        job?.cancel()
        val ui = _ui.value ?: return
        partFile(ui.offer.versionCode).delete()
        _ui.value = ui.copy(phase = UpdatePhase.Available, received = 0, total = 0, message = "已取消下载")
    }

    private fun download(offer: UpdateOffer) {
        job?.cancel()
        cancelFlag.set(false)
        val part = partFile(offer.versionCode)
        _ui.value = UpdateUi(offer, UpdatePhase.Downloading, received = part.length(), sheet = true)
        job = scope.launch(Dispatchers.IO) {
            try {
                fetcher.fetch(listOf(offer.apkUrl), part, { cancelFlag.get() }) { got, total ->
                    val cur = _ui.value
                    if (cur != null && cur.phase == UpdatePhase.Downloading) _ui.value = cur.copy(received = got, total = total)
                }
                if (!verifyApk(part, offer.sha256)) {
                    part.delete()
                    publish(offer, UpdatePhase.Failed, "安装包校验未通过，请重试")
                    return@launch
                }
                val apk = apkFile(offer.versionCode)
                apk.delete()
                if (!part.renameTo(apk)) part.copyTo(apk, overwrite = true).also { part.delete() }
                publish(offer, UpdatePhase.Ready)
            } catch (_: CancelledDownload) {
                publish(offer, UpdatePhase.Available, "已取消下载")
            } catch (e: HttpStatusException) {
                publish(offer, UpdatePhase.Failed, "下载失败（HTTP ${e.code}）")
            } catch (e: Exception) {
                publish(offer, UpdatePhase.Failed, "下载中断：${e.message ?: "网络异常"}，可继续下载")
            }
        }
    }

    private fun publish(offer: UpdateOffer, phase: UpdatePhase, message: String = "") {
        _ui.value = UpdateUi(offer, phase, message = message, sheet = _ui.value?.sheet ?: true)
    }

    private fun installOrAsk(activityContext: Context, offer: UpdateOffer) {
        val apk = apkFile(offer.versionCode)
        if (!verifyApk(apk, offer.sha256)) {
            publish(offer, UpdatePhase.Failed, "安装包已失效，请重新下载")
            return
        }
        if (!canInstall(activityContext)) {
            awaitingPermission = true
            publish(offer, UpdatePhase.NeedsPermission, "请允许「吃了吗」安装未知应用，返回后会自动继续安装")
            activityContext.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activityContext.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        awaitingPermission = false
        val uri = FileProvider.getUriForFile(activityContext, "${activityContext.packageName}.fileprovider", apk)
        activityContext.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "版本更新", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "有新版本时提醒一次"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyNew(offer: UpdateOffer) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 21,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = offer.notes.ifBlank { "点击即可在应用内下载安装" }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF4F8A76.toInt())
            .setContentTitle("吃了吗 ${offer.versionName} 可更新")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_NEW, n)
    }

    companion object {
        const val CHANNEL_ID = "app_updates"
        const val EXTRA_OPEN_UPDATE = "todayeat.open_update"
        private const val NOTIF_NEW = 3100
        private const val KEY_LAST_CHECK = "last_check_at"
        private const val KEY_NOTIFIED = "notified_code"
        private const val WORK = "update-check"
        private const val TAG = "AppUpdate"

        fun scheduleChecks(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

/** 每 12 小时检查一次，有新版本时发一条通知（每个版本只提醒一次） */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TodayEatApp
        return if (app.updates.check(present = false, notify = true) == CheckResult.Failed) Result.retry() else Result.success()
    }
}
