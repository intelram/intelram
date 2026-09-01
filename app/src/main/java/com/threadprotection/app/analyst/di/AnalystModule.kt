package com.threadprotection.app.analyst.di

import android.content.Context
import com.threadprotection.app.analyst.data.local.AnalystDatabase
import com.threadprotection.app.analyst.data.local.CveDao
import com.threadprotection.app.analyst.data.remote.EpssApi
import com.threadprotection.app.analyst.data.repository.CveRepository
import com.threadprotection.app.analyst.data.repository.CveRepositoryImpl
import com.threadprotection.app.data.SettingsRepository
import com.threadprotection.app.network.NetworkModule
import com.threadprotection.app.network.NvdApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for Analyst Mode. Deliberately reuses [NetworkModule]'s shared OkHttp client/JSON
 * config rather than standing up a second one — every existing threat-intel client in this app
 * (VirusTotal, Safe Browsing, URLhaus, …) already goes through it, and a second client would mean
 * a second connection pool and a second place to configure timeouts.
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalystNetworkModule {

    @Provides
    @Singleton
    fun provideNvdApi(): NvdApi = NetworkModule.create(NvdApi.BASE_URL)

    @Provides
    @Singleton
    fun provideEpssApi(): EpssApi = NetworkModule.create(EpssApi.BASE_URL)

    @Provides
    @Singleton
    fun provideAnalystDatabase(@ApplicationContext context: Context): AnalystDatabase =
        AnalystDatabase.getInstance(context)

    @Provides
    fun provideCveDao(database: AnalystDatabase): CveDao = database.cveDao()

    /**
     * A second, lightweight [SettingsRepository] wrapper around the same on-disk DataStore file
     * [ThreadProtectionApp][com.threadprotection.app.ThreadProtectionApp] already uses (the
     * `preferencesDataStore` delegate is keyed to the Application instance, so this reads/writes
     * the exact same store — not a separate one). Reusing it here means the analyst's NVD key,
     * entered once in Settings, works for both the consumer scan pipeline and Analyst Mode.
     */
    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalystRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindCveRepository(impl: CveRepositoryImpl): CveRepository
}
