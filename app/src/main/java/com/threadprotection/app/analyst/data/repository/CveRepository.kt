package com.threadprotection.app.analyst.data.repository

import android.content.Context
import com.threadprotection.app.analyst.data.CveWatchlistSyncWorker
import com.threadprotection.app.analyst.data.local.CveDao
import com.threadprotection.app.analyst.data.local.WatchedCveEntity
import com.threadprotection.app.analyst.data.remote.EpssApi
import com.threadprotection.app.analyst.domain.PriorityScoring
import com.threadprotection.app.analyst.domain.model.CveDetail
import com.threadprotection.app.analyst.domain.model.CveReference
import com.threadprotection.app.analyst.domain.model.CveSummary
import com.threadprotection.app.analyst.domain.model.KevStatus
import com.threadprotection.app.data.ApiKeyId
import com.threadprotection.app.data.ApiKeys
import com.threadprotection.app.network.NvdApi
import com.threadprotection.app.network.NvdCve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface CveRepository {
    suspend fun search(keyword: String, apiKeys: ApiKeys): Result<List<CveSummary>>
    suspend fun getDetail(cveId: String, apiKeys: ApiKeys): Result<CveDetail>
    suspend fun setWatched(cveId: String, watched: Boolean)
    fun observeWatchlistIds(): Flow<Set<String>>
    fun observeIsWatched(cveId: String): Flow<Boolean>
    suspend fun watchlistSnapshot(): List<WatchedCveEntity>
    suspend fun updateWatchlistEntry(entity: WatchedCveEntity)
}

/**
 * Combines NVD (description, CVSS, CWE, references, affected products, KEV fields) with FIRST.org
 * EPSS (exploitation probability), and reconciles both against the local watchlist. Every function
 * returns `Result<T>` per the project's error-handling rule — a failed lookup surfaces as a
 * `Result.failure` with a message the ViewModel turns into user-facing text, never a raw exception
 * reaching Compose.
 *
 * EPSS is fetched best-effort: if it fails (network, or the CVE simply has no EPSS score), the CVE
 * result still returns with `epssScore = null` rather than failing the whole lookup — a missing
 * exploitation-probability score is not the same class of failure as not being able to reach NVD
 * for the CVE record itself, which *is* the thing this repository can't produce a result without.
 */
@Singleton
class CveRepositoryImpl @Inject constructor(
    private val nvdApi: NvdApi,
    private val epssApi: EpssApi,
    private val cveDao: CveDao,
    @ApplicationContext private val context: Context,
) : CveRepository {

    override suspend fun search(keyword: String, apiKeys: ApiKeys): Result<List<CveSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = nvdApi.searchCves(apiKey = apiKeys[ApiKeyId.NVD].ifBlank { null }, keyword = keyword, resultsPerPage = 20)
            response.vulnerabilities.map { wrapper ->
                val cve = wrapper.cve
                val score = cve.metrics?.bestScore
                val isKev = cve.cisaExploitAdd != null
                CveSummary(
                    id = cve.id,
                    description = cve.englishDescription.orEmpty(),
                    publishedDate = cve.published,
                    cvssScore = score,
                    cvssSeverity = cve.metrics?.bestSeverity,
                    isKev = isKev,
                    // Search results don't fetch EPSS per-row (would be N extra calls per search);
                    // priority here is CVSS+KEV only, refined to include EPSS once the analyst
                    // opens the detail screen. Documented on CveSummary's absence of an epss field.
                    priority = PriorityScoring.calculate(score, epssProbability = null, isKev = isKev),
                )
            }
        }
    }

    override suspend fun getDetail(cveId: String, apiKeys: ApiKeys): Result<CveDetail> = withContext(Dispatchers.IO) {
        runCatching {
            coroutineScope {
                val nvdDeferred = async { nvdApi.getCveById(apiKey = apiKeys[ApiKeyId.NVD].ifBlank { null }, cveId = cveId) }
                val epssDeferred = async {
                    runCatching { epssApi.getScore(cveId) }.getOrNull()?.data?.firstOrNull()
                }
                val watchedDeferred = async { cveDao.get(cveId) != null }

                val cve = nvdDeferred.await().vulnerabilities.firstOrNull()?.cve
                    ?: error("No NVD record found for $cveId")
                val epss = epssDeferred.await()
                toDomain(cve, epss?.epssScore, epss?.percentileScore, watchedDeferred.await())
            }
        }
    }

    private fun toDomain(cve: NvdCve, epssScore: Double?, epssPercentile: Double?, isWatched: Boolean): CveDetail {
        val cvssScore = cve.metrics?.bestScore
        val isKev = cve.cisaExploitAdd != null
        return CveDetail(
            id = cve.id,
            description = cve.englishDescription.orEmpty(),
            publishedDate = cve.published,
            lastModifiedDate = cve.lastModified,
            cvssVersion = cve.metrics?.bestMetric?.cvssData?.version,
            cvssVectorString = cve.metrics?.bestMetric?.cvssData?.vectorString,
            cvssScore = cvssScore,
            cvssSeverity = cve.metrics?.bestSeverity,
            cweIds = cve.cweIds,
            references = cve.references.map { CveReference(it.url, it.source.orEmpty(), it.tags) },
            affectedProducts = cve.affectedProductCriteria,
            epssScore = epssScore,
            epssPercentile = epssPercentile,
            kev = KevStatus(
                isKnownExploited = isKev,
                dateAdded = cve.cisaExploitAdd,
                dueDate = cve.cisaActionDue,
                requiredAction = cve.cisaRequiredAction,
                vulnerabilityName = cve.cisaVulnerabilityName,
            ),
            priority = PriorityScoring.calculate(cvssScore, epssScore, isKev),
            isWatched = isWatched,
        )
    }

    override suspend fun setWatched(cveId: String, watched: Boolean) {
        if (watched) {
            cveDao.upsert(WatchedCveEntity(cveId = cveId, addedAtMs = System.currentTimeMillis()))
            // Idempotent (ExistingPeriodicWorkPolicy.KEEP) — only actually schedules on the first
            // ever watch, but calling it here means the daily KEV check exists exactly when there
            // is something to check, rather than running unconditionally from app startup.
            CveWatchlistSyncWorker.ensureScheduled(context)
        } else {
            cveDao.remove(cveId)
        }
    }

    override fun observeWatchlistIds(): Flow<Set<String>> =
        cveDao.observeAll().map { list -> list.map { it.cveId }.toSet() }

    override fun observeIsWatched(cveId: String): Flow<Boolean> = cveDao.observeIsWatched(cveId)

    override suspend fun watchlistSnapshot(): List<WatchedCveEntity> = cveDao.getAll()

    override suspend fun updateWatchlistEntry(entity: WatchedCveEntity) = cveDao.update(entity)
}
