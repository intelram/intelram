package com.threadprotection.app.analyst.domain.usecase

import com.threadprotection.app.analyst.data.repository.CveRepository
import com.threadprotection.app.analyst.domain.model.CveDetail
import com.threadprotection.app.analyst.domain.model.CveSummary
import com.threadprotection.app.data.ApiKeys
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** One use case per action, per the project's Clean Architecture layering — each is a thin,
 *  named entry point onto [CveRepository] so the ViewModel reads as a list of intents rather than
 *  repository calls. */
class SearchCveUseCase @Inject constructor(private val repository: CveRepository) {
    suspend operator fun invoke(keyword: String, apiKeys: ApiKeys): Result<List<CveSummary>> =
        repository.search(keyword, apiKeys)
}

class GetCveDetailUseCase @Inject constructor(private val repository: CveRepository) {
    suspend operator fun invoke(cveId: String, apiKeys: ApiKeys): Result<CveDetail> =
        repository.getDetail(cveId, apiKeys)
}

class ToggleCveWatchlistUseCase @Inject constructor(private val repository: CveRepository) {
    suspend operator fun invoke(cveId: String, watched: Boolean) = repository.setWatched(cveId, watched)
}

class ObserveCveWatchlistUseCase @Inject constructor(private val repository: CveRepository) {
    operator fun invoke(): Flow<Set<String>> = repository.observeWatchlistIds()
}

class ObserveIsCveWatchedUseCase @Inject constructor(private val repository: CveRepository) {
    operator fun invoke(cveId: String): Flow<Boolean> = repository.observeIsWatched(cveId)
}
