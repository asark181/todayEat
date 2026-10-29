package com.todayeat.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plans WHERE deleted = 0 ORDER BY sortOrder, hour, minute")
    fun observeActive(): Flow<List<PlanEntity>>

    @Query("SELECT * FROM plans WHERE deleted = 0 ORDER BY sortOrder, hour, minute")
    suspend fun active(): List<PlanEntity>

    @Query("SELECT * FROM plans")
    suspend fun all(): List<PlanEntity>

    @Query("SELECT * FROM plans WHERE uid = :uid")
    suspend fun byUid(uid: String): PlanEntity?

    @Upsert
    suspend fun upsert(plan: PlanEntity)

    @Upsert
    suspend fun upsertAll(plans: List<PlanEntity>)

    @Query("DELETE FROM plans")
    suspend fun clear()
}

@Dao
interface DoseDao {
    @Query("SELECT * FROM doses WHERE deleted = 0 AND date BETWEEN :from AND :to ORDER BY takenAt")
    fun observeRange(from: String, to: String): Flow<List<DoseEntity>>

    @Query("SELECT * FROM doses WHERE deleted = 0 AND date BETWEEN :from AND :to ORDER BY takenAt")
    suspend fun range(from: String, to: String): List<DoseEntity>

    @Query("SELECT * FROM doses WHERE deleted = 0 AND planUid = :planUid AND date = :date LIMIT 1")
    suspend fun find(planUid: String, date: String): DoseEntity?

    @Query("SELECT * FROM doses WHERE uid = :uid")
    suspend fun byUid(uid: String): DoseEntity?

    @Query("SELECT * FROM doses")
    suspend fun all(): List<DoseEntity>

    @Query("SELECT MIN(date) FROM doses WHERE deleted = 0")
    fun observeFirstDate(): Flow<String?>

    @Query("SELECT COUNT(*) FROM doses WHERE deleted = 0")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(dose: DoseEntity)

    @Upsert
    suspend fun upsertAll(doses: List<DoseEntity>)
}

@Database(entities = [PlanEntity::class, DoseEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun plans(): PlanDao
    abstract fun doses(): DoseDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "todayeat.db").build()
    }
}
