package com.example.model

enum class MenuType(val label: String, val selectId: String, val quantityFieldId: String) {
    DIETA("Dieta", "idMenuDieta", "CantidadDieta"),
    NORMAL("Normal", "idMenu", "Cantidad")
}

data class ValidationResult(
    val isSuccess: Boolean,
    val message: String,
    val userName: String? = null,
    val details: String? = null
)

data class MenuOption(
    val id: String,
    val name: String,
    val isAvailable: Boolean = true
)

data class ReservationResult(
    val isSuccess: Boolean,
    val message: String,
    val menuName: String? = null,
    val quantity: Int = 1,
    val menuType: MenuType = MenuType.NORMAL,
    val timestamp: Long = System.currentTimeMillis()
)

data class ServiceStatus(
    val isRunning: Boolean = false,
    val attempts: Int = 0,
    val lastCheckTime: Long? = null,
    val statusMessage: String = "Inactivo",
    val isMenuAvailable: Boolean = false,
    val currentMenuInfo: String? = null,
    val targetMenuType: MenuType = MenuType.NORMAL,
    val targetQuantity: Int = 1,
    val completedReservation: ReservationResult? = null,
    val logs: List<String> = emptyList()
)
