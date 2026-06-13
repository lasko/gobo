package com.gobo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gobo.app.net.OgsRest
import com.gobo.app.net.PuzzleCollection
import com.gobo.app.net.PuzzleSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PuzzlesUiState {
    data object Loading : PuzzlesUiState
    data class Ready(val collections: List<PuzzleCollection>) : PuzzlesUiState
    data class Error(val message: String) : PuzzlesUiState
}

/** The active sort field + direction, surfaced so the screen can highlight it. */
data class PuzzleSortState(val field: PuzzleSort, val descending: Boolean)

/**
 * Backs the puzzle-collection browse list. Sorting is **server-side** (OGS' `ordering` param) so the
 * top of a 4000+ collection list is the good stuff — we pull the first page of the sorted result.
 * Pure REST GETs — no socket, no analytics.
 */
class PuzzlesViewModel(private val rest: OgsRest) : ViewModel() {

    private val _state = MutableStateFlow<PuzzlesUiState>(PuzzlesUiState.Loading)
    val state = _state.asStateFlow()

    // Default to best-rated first, matching the OGS web puzzle browser's most useful entry point.
    private val _sort = MutableStateFlow(PuzzleSortState(PuzzleSort.Rating, descending = true))
    val sort = _sort.asStateFlow()

    init { load() }

    fun load() {
        val s = _sort.value
        viewModelScope.launch {
            _state.value = PuzzlesUiState.Loading
            rest.fetchPuzzleCollections(s.field, s.descending)
                .onSuccess { _state.value = PuzzlesUiState.Ready(it) }
                .onFailure {
                    _state.value = PuzzlesUiState.Error(it.message ?: "Failed to load puzzles")
                }
        }
    }

    /**
     * Pick a sort field. Re-selecting the current field flips the direction (like clicking a column
     * header again); a new field starts at its natural default direction. Re-fetches either way.
     */
    fun sortBy(field: PuzzleSort) {
        val current = _sort.value
        _sort.value = if (current.field == field) {
            current.copy(descending = !current.descending)
        } else {
            PuzzleSortState(field, field.descending)
        }
        load()
    }
}
