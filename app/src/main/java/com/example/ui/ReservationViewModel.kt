package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.MenuType
import com.example.model.ServiceStatus
import com.example.model.ValidationResult
import com.example.repository.ReservationRepository
import com.example.service.ReservationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReservationUiState(
    val username: String = "",
    val password: String = "",
    val isPasswordVisible: Boolean = false,
    val isValidating: Boolean = false,
    val validationResult: ValidationResult? = null,
    val isValidated: Boolean = false,
    val selectedMenuType: MenuType? = null,
    val selectedQuantity: Int = 1,
    val serviceStatus: ServiceStatus = ServiceStatus()
)

class ReservationViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(
        ReservationUiState(
            username = ReservationRepository.savedUsername,
            password = ReservationRepository.savedPassword,
            selectedQuantity = ReservationRepository.selectedQuantity
        )
    )
    val uiState: StateFlow<ReservationUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ReservationRepository.status.collect { status ->
                _uiState.update { it.copy(serviceStatus = status) }
            }
        }
    }

    fun onUsernameChange(newUsername: String) {
        _uiState.update { it.copy(username = newUsername, validationResult = null, isValidated = false) }
    }

    fun onPasswordChange(newPassword: String) {
        _uiState.update { it.copy(password = newPassword, validationResult = null, isValidated = false) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }
    }

    fun validateCredentials() {
        val currentUsername = _uiState.value.username.trim()
        val currentPassword = _uiState.value.password

        if (currentUsername.isEmpty() || currentPassword.isEmpty()) {
            _uiState.update {
                it.copy(
                    validationResult = ValidationResult(false, "Por favor ingrese usuario y contraseña"),
                    isValidated = false
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isValidating = true, validationResult = null) }
            val result = ReservationRepository.engine.validateCredentials(currentUsername, currentPassword)
            _uiState.update {
                it.copy(
                    isValidating = false,
                    validationResult = result,
                    isValidated = result.isSuccess
                )
            }
        }
    }

    fun selectMenuType(type: MenuType) {
        _uiState.update { it.copy(selectedMenuType = type) }
        ReservationRepository.selectedMenuType = type
    }

    fun selectQuantity(qty: Int) {
        if (qty in 1..2) {
            _uiState.update { it.copy(selectedQuantity = qty) }
            ReservationRepository.selectedQuantity = qty
        }
    }

    fun startService(context: Context) {
        val state = _uiState.value
        val menuType = state.selectedMenuType ?: MenuType.NORMAL
        val qty = state.selectedQuantity

        ReservationService.start(
            context = context,
            username = state.username,
            pass = state.password,
            menuType = menuType,
            quantity = qty
        )
    }

    fun stopService(context: Context) {
        ReservationService.stop(context)
    }

    fun clearLogs() {
        ReservationRepository.clearLogs()
    }

    fun resetFlow() {
        _uiState.update {
            it.copy(
                isValidated = false,
                validationResult = null,
                selectedMenuType = null,
                selectedQuantity = 1
            )
        }
    }
}
