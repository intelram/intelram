package com.threadprotection.app.analyst.presentation.cve

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.threadprotection.app.analyst.domain.model.CveDetail
import com.threadprotection.app.analyst.domain.model.CveSummary
import com.threadprotection.app.analyst.domain.usecase.GetCveDetailUseCase
import com.threadprotection.app.analyst.domain.usecase.ObserveIsCveWatchedUseCase
import com.threadprotection.app.analyst.domain.usecase.SearchCveUseCase
import com.threadprotection.app.analyst.domain.usecase.ToggleCveWatchlistUseCase
import com.threadprotection.app.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per project convention: every screen's state is Loading, Success, or Error — never a raw
 *  exception or a partially-filled model reaching Compose. Search and detail get their own sealed
 *  states (not one shared state) precisely so opening a CVE's detail can't blank out the search
 *  results still sitting behind it when the analyst navigates back. */
sealed interface CveSearchUiState {
    data object Idle : CveSearchUiState
    data object Loading : CveSearchUiState
    data class Results(val results: List<CveSummary>) : CveSearchUiState
    data class Error(val message: String) : CveSearchUiState
}

sealed interface CveDetailUiState {
    data object Idle : CveDetailUiState
    data object Loading : CveDetailUiState
    data class Success(val cve: CveDetail) : CveDetailUiState
    data class Error(val message: String) : CveDetailUiState
}

@HiltViewModel
class CveViewModel @Inject constructor(
    private val searchCve: SearchCveUseCase,
    private val getCveDetail: GetCveDetailUseCase,
    private val toggleWatchlist: ToggleCveWatchlistUseCase,
    private val observeIsWatched: ObserveIsCveWatchedUseCase,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _searchState = MutableStateFlow<CveSearchUiState>(CveSearchUiState.Idle)
    val searchState: StateFlow<CveSearchUiState> = _searchState.asStateFlow()

    private val _detailState = MutableStateFlow<CveDetailUiState>(CveDetailUiState.Idle)
    val detailState: StateFlow<CveDetailUiState> = _detailState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Id of the CVE currently shown on the detail screen. Analyst Mode's own navigation state,
     *  kept out of the app-wide `AppUiState` on purpose — see GRAPH_ENGINEERING_MAP.md §10. */
    private val _selectedCveId = MutableStateFlow<String?>(null)
    val selectedCveId: StateFlow<String?> = _selectedCveId.asStateFlow()

    /** True while [selectedCveId]'s CVE is on the watchlist — recomputed live as the id changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val isSelectedWatched: StateFlow<Boolean> = _selectedCveId
        .flatMapLatest { id -> if (id == null) flowOf(false) else observeIsWatched(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setQuery(text: String) {
        _query.value = text
    }

    fun search() {
        val keyword = _query.value.trim()
        if (keyword.isEmpty()) return
        _searchState.value = CveSearchUiState.Loading
        viewModelScope.launch {
            val apiKeys = settingsRepository.apiKeysFlow.first()
            searchCve(keyword, apiKeys).fold(
                onSuccess = { _searchState.value = CveSearchUiState.Results(it) },
                onFailure = { _searchState.value = CveSearchUiState.Error(it.friendlyMessage()) },
            )
        }
    }

    fun openDetail(cveId: String) {
        _selectedCveId.value = cveId
        _detailState.value = CveDetailUiState.Loading
        viewModelScope.launch {
            val apiKeys = settingsRepository.apiKeysFlow.first()
            getCveDetail(cveId, apiKeys).fold(
                onSuccess = { _detailState.value = CveDetailUiState.Success(it) },
                onFailure = { _detailState.value = CveDetailUiState.Error(it.friendlyMessage()) },
            )
        }
    }

    fun retryDetail() {
        _selectedCveId.value?.let { openDetail(it) }
    }

    fun closeDetail() {
        _selectedCveId.value = null
        _detailState.value = CveDetailUiState.Idle
    }

    fun toggleWatch(cveId: String, watch: Boolean) {
        viewModelScope.launch { toggleWatchlist(cveId, watch) }
    }

    /** Never surfaces a raw exception message — per the project's error-handling rule, the UI
     *  gets a plain-language explanation of what's actionable. */
    private fun Throwable.friendlyMessage(): String = when {
        message?.contains("No NVD record found") == true -> "No CVE found with that ID. Check the format (e.g. CVE-2021-44228)."
        this is java.net.UnknownHostException || this is java.io.IOException ->
            "Couldn't reach the NVD database. Check your connection and try again."
        else -> "Lookup failed (${this::class.java.simpleName}). Try again in a moment."
    }
}
