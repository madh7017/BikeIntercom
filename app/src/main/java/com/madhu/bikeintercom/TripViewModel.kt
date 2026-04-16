package com.madhu.bikeintercom

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class TripUiState {
    object Idle : TripUiState()
    object Loading : TripUiState()
    data class Success(val trip: ParsedTrip) : TripUiState()
    data class Error(val message: String) : TripUiState()
}

class TripViewModel(private val repository: TripRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<TripUiState>(TripUiState.Idle)
    val uiState = _uiState.asStateFlow()

    fun planTrip(input: String) {
        if (input.isBlank()) return
        
        viewModelScope.launch {
            _uiState.value = TripUiState.Loading
            try {
                val parsed = repository.parseTripWithAI(input)
                _uiState.value = TripUiState.Success(parsed)
            } catch (e: Exception) {
                _uiState.value = TripUiState.Error(e.message ?: "Failed to parse trip")
            }
        }
    }

    fun planTripWithImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = TripUiState.Loading
            try {
                val parsed = repository.parseTripWithImage(bitmap)
                _uiState.value = TripUiState.Success(parsed)
            } catch (e: Exception) {
                _uiState.value = TripUiState.Error(e.message ?: "Failed to parse image")
            }
        }
    }

    fun planTripWithDocument(mimeType: String, bytes: ByteArray) {
        viewModelScope.launch {
            _uiState.value = TripUiState.Loading
            try {
                val parsed = repository.parseTripWithDocument(mimeType, bytes)
                _uiState.value = TripUiState.Success(parsed)
            } catch (e: Exception) {
                _uiState.value = TripUiState.Error(e.message ?: "Failed to parse document")
            }
        }
    }
}
