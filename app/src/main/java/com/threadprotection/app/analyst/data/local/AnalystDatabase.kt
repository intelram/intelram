package com.threadprotection.app.analyst.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for Analyst Mode only — separate from the rest of the app, which persists via
 * DataStore Preferences (see [com.threadprotection.app.data.SettingsRepository]). A relational
 * table is the right fit here (indexed lookups by id, room to grow into `attack_techniques`/
 * `threat_actors`/`cases` per the master spec) in a way a single JSON blob isn't.
 *
 * [getInstance] is a manual singleton — the same pattern already used by
 * [com.threadprotection.app.chat.BluetoothChatManager.getInstance] and
 * [com.threadprotection.app.hardware.ExternalDeviceMonitor.getInstance] — so that both the
 * Hilt-provided path (ViewModel → Repository) and [com.threadprotection.app.analyst.data.CveWatchlistSyncWorker]
 * (a plain `CoroutineWorker`, constructed by WorkManager itself and therefore outside the Hilt
 * graph, matching every other worker in this app — see `service/ScheduledScanWorker.kt`) share
 * exactly one database instance rather than opening the file twice.
 */
@Database(entities = [WatchedCveEntity::class], version = 1, exportSchema = true)
abstract class AnalystDatabase : RoomDatabase() {
    abstract fun cveDao(): CveDao

    companion object {
        @Volatile private var instance: AnalystDatabase? = null

        fun getInstance(context: Context): AnalystDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AnalystDatabase::class.java,
                    "analyst.db",
                ).build().also { instance = it }
            }
    }
}
