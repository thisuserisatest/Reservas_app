package com.example.repository

import com.example.model.MenuType
import com.example.model.ReservationResult
import com.example.model.ServiceStatus
import com.example.network.IntecapReservationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReservationRepository {

    val engine = IntecapReservationEngine()

    var savedUsername: String = ""
    var savedPassword: String = ""
    var selectedMenuType: MenuType = MenuType.NORMAL
    var selectedQuantity: Int = 1

    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun updateRunning(isRunning: Boolean, statusMessage: String? = null) {
        _status.update { current ->
            current.copy(
                isRunning = isRunning,
                statusMessage = statusMessage ?: if (isRunning) "Buscando menú en segundo plano..." else "Inactivo"
            )
        }
        addLog(if (isRunning) "🚀 Servicio en segundo plano iniciado" else "🛑 Servicio en segundo plano detenido")
    }

    fun recordAttempt(
        menuType: MenuType,
        isAvailable: Boolean,
        menuInfo: String?,
        quantity: Int
    ) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        _status.update { current ->
            current.copy(
                attempts = current.attempts + 1,
                lastCheckTime = now,
                isMenuAvailable = isAvailable,
                currentMenuInfo = menuInfo,
                targetMenuType = menuType,
                targetQuantity = quantity,
                statusMessage = if (isAvailable) "Menú disponible encontrado: $menuInfo" else "Aún no se ha encontrado el menú (${menuType.label}). No disponible."
            )
        }

        if (isAvailable) {
            addLog("[$timeStr] ✨ Menú ${menuType.label} disponible: $menuInfo")
        } else {
            addLog("[$timeStr] ⏳ Verificando menú ${menuType.label}... Aún no disponible (Disponibles: 0)")
        }
    }

    fun recordCompletedReservation(result: ReservationResult) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        _status.update { current ->
            current.copy(
                isRunning = false,
                statusMessage = if (result.isSuccess) "✅ Reserva completada exitosamente" else "❌ Error al procesar reserva",
                completedReservation = result
            )
        }
        if (result.isSuccess) {
            addLog("[$timeStr] 🎉 ¡RESERVA EXITOSA! Menú: ${result.menuName} | Cantidad: ${result.quantity}")
        } else {
            addLog("[$timeStr] ⚠️ Fallo al reservar: ${result.message}")
        }
    }

    fun addLog(message: String) {
        _status.update { current ->
            val updated = current.logs.toMutableList().apply {
                add(0, message)
                if (size > 100) removeAt(size - 1)
            }
            current.copy(logs = updated)
        }
    }

    fun clearLogs() {
        _status.update { it.copy(logs = emptyList(), attempts = 0, completedReservation = null) }
    }
}
