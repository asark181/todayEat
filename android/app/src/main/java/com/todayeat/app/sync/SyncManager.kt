package com.todayeat.app.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.todayeat.app.TodayEatApp
import com.todayeat.app.data.Repository
import com.todayeat.app.photo.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val at: Long) : SyncState
    data class Failed(val reason: String) : SyncState
}

/**
 * 账号与云同步。登录后数据密钥存在 [KeyVault]，令牌存在私有偏好。
 * 本机改动后通过 WorkManager 排一次同步（有网才跑，失败自动退避重试）。
 */
class SyncManager(
    private val context: Context,
    private val repo: Repository,
    private val photos: PhotoStore,
    private val api: SyncApi
) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val gate = Mutex()

    private val _username = MutableStateFlow(prefs.getString(KEY_USER, null))
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _state = MutableStateFlow<SyncState>(
        prefs.getLong(KEY_LAST_OK, 0L).takeIf { it > 0 }?.let { SyncState.Done(it) } ?: SyncState.Idle
    )
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val token: String? get() = prefs.getString(KEY_TOKEN, null)

    val loggedIn: Boolean get() = token != null && _username.value != null

    private fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)

    /** 注册或登录；成功后立刻同步一次。失败抛出带中文信息的异常 */
    suspend fun signIn(username: String, password: String, register: Boolean) = withContext(Dispatchers.IO) {
        val name = username.trim()
        require(name.length in 2..32) { "用户名需 2–32 个字符" }
        require(password.length >= 6) { "密码至少 6 位" }
        val derived = Crypto.derive(name, password)
        val (tok, serverName) = try {
            if (register) api.register(name, derived.auth, device()) else api.login(name, derived.auth, device())
        } catch (e: ApiException) {
            throw IllegalStateException(e.detail)
        } catch (e: IOException) {
            throw IllegalStateException("无法连接服务器")
        }
        KeyVault.save(context, derived.key)
        prefs.edit().putString(KEY_TOKEN, tok).putString(KEY_USER, serverName).putBoolean(KEY_FIRST, true).apply()
        _username.value = serverName
        syncNow()
        (_state.value as? SyncState.Failed)?.let { throw IllegalStateException("已登录，但同步失败：${it.reason}") }
    }

    /** 退出登录：本机数据保留，只是不再同步 */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        token?.let { t -> runCatching { api.logout(t) } }
        clearSession()
    }

    private fun clearSession() {
        KeyVault.clear(context)
        prefs.edit().clear().apply()
        _username.value = null
        _state.value = SyncState.Idle
        WorkManager.getInstance(context).cancelUniqueWork(WORK_ONCE)
    }

    /** 排一次同步；连续改动会合并成一次 */
    fun request(delaySeconds: Long = 3) {
        if (!loggedIn) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** @return false 表示网络问题，值得稍后重试 */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        gate.withLock {
            val tok = token ?: return@withLock true
            val key = KeyVault.load(context) ?: run {
                _state.value = SyncState.Failed("本机密钥丢失，请重新登录")
                return@withLock true
            }
            _state.value = SyncState.Running
            try {
                syncSnapshot(tok, key)
                syncPhotos(tok, key)
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_OK, now).apply()
                _state.value = SyncState.Done(now)
                true
            } catch (e: ApiException) {
                Log.w(TAG, "sync rejected ${e.code}", e)
                if (e.code == 401) {
                    clearSession()
                    _state.value = SyncState.Failed("登录已失效，请重新登录")
                } else {
                    _state.value = SyncState.Failed(e.detail)
                }
                true
            } catch (e: AEADBadTagException) {
                _state.value = SyncState.Failed("无法解密云端数据，请确认密码")
                true
            } catch (e: IOException) {
                Log.w(TAG, "sync network", e)
                _state.value = SyncState.Failed("网络异常，稍后自动重试")
                false
            }
        }
    }

    private suspend fun syncSnapshot(tok: String, key: ByteArray) {
        repeat(3) {
            val remote = api.getSnapshot(tok)
            val remoteSnap = remote.blob?.let { SyncMerge.fromJson(Crypto.decryptSnapshot(it, key)) }
            if (prefs.getBoolean(KEY_FIRST, false)) {
                if (remoteSnap != null && remoteSnap.plans.any { !it.deleted }) repo.dropLocalPlansIfFresh()
                prefs.edit().putBoolean(KEY_FIRST, false).apply()
            }
            val (plans, doses) = repo.snapshotAll()
            val result = SyncMerge.merge(Snapshot(plans, doses), remoteSnap, System.currentTimeMillis())
            repo.applyMerged(result.plansToWrite, result.dosesToWrite)
            if (!result.remoteOutdated) return
            try {
                api.putSnapshot(tok, remote.version, Crypto.encryptSnapshot(SyncMerge.toJson(result.merged), key))
                return
            } catch (e: ApiException) {
                if (e.code != 409) throw e
                // 别的设备刚传过，重新拉取再合并
            }
        }
        throw IOException("同步冲突重试次数过多")
    }

    /** 本机有、云端没有的照片上传；云端有本机没有的，显示时再按需下载 */
    private suspend fun syncPhotos(tok: String, key: ByteArray) {
        val (_, doses) = repo.snapshotAll()
        val wanted = doses.filter { !it.deleted }.flatMap { it.photoIds }.toSet()
        val remote = api.listPhotos(tok)
        for (id in wanted) {
            if (id in remote || !photos.has(id)) continue
            api.putPhoto(tok, id, Crypto.seal(photos.file(id).readBytes(), key))
        }
    }

    /** 照片不在本机就从云端取回来；取不到返回 false */
    suspend fun ensurePhoto(id: String): Boolean = withContext(Dispatchers.IO) {
        if (photos.has(id)) return@withContext true
        val tok = token ?: return@withContext false
        val key = KeyVault.load(context) ?: return@withContext false
        runCatching { photos.write(id, Crypto.open(api.getPhoto(tok, id), key)) }
            .onFailure { Log.w(TAG, "photo $id", it) }
        photos.has(id)
    }

    companion object {
        private const val TAG = "Sync"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER = "username"
        private const val KEY_LAST_OK = "last_ok"
        private const val KEY_FIRST = "first_sync"
        private const val WORK_ONCE = "sync-once"
        private const val WORK_PERIODIC = "sync-periodic"
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TodayEatApp
        return if (app.sync.syncNow()) Result.success() else Result.retry()
    }
}
