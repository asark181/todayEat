package com.todayeat.app

import android.app.Application
import com.todayeat.app.data.AppDatabase
import com.todayeat.app.data.Repository
import com.todayeat.app.notify.Reminders
import com.todayeat.app.photo.PhotoStore
import com.todayeat.app.sync.SyncApi
import com.todayeat.app.sync.SyncManager
import com.todayeat.app.update.AppUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 手动依赖注入：数据库、仓库、照片、同步、更新都在这里各一份 */
class TodayEatApp : Application() {
    val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var db: AppDatabase private set
    lateinit var repository: Repository private set
    lateinit var photos: PhotoStore private set
    lateinit var sync: SyncManager private set
    lateinit var updates: AppUpdate private set

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        photos = PhotoStore(this)
        repository = Repository(db) { plansChanged, fromSync -> onDataChanged(plansChanged, fromSync) }
        sync = SyncManager(this, repository, photos, SyncApi(BuildConfig.SERVER_URL))
        updates = AppUpdate(this, mainScope)
        Reminders.createChannels(this)
        sync.schedulePeriodic()
        mainScope.launch(Dispatchers.IO) {
            Reminders.rescheduleAll(this@TodayEatApp, repository.allPlansForAlarms())
        }
    }

    private fun onDataChanged(plansChanged: Boolean, fromSync: Boolean) {
        mainScope.launch(Dispatchers.IO) {
            if (plansChanged) Reminders.rescheduleAll(this@TodayEatApp, repository.allPlansForAlarms())
            // 今天已经吃过的，收起还挂着的提醒，不再追着提醒
            val today = LocalDate.now()
            repository.dosesBetween(today, today).forEach {
                Reminders.cancelRenag(this@TodayEatApp, it.planUid)
                Reminders.cancelShown(this@TodayEatApp, it.planUid)
            }
        }
        if (!fromSync) sync.request()
    }
}
