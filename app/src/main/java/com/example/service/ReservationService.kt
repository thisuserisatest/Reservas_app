package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.model.MenuType
import com.example.model.ReservationResult
import com.example.repository.ReservationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ReservationService : Service() {

    companion object {
        private const val TAG = "ReservationService"
        const val CHANNEL_ID = "intecap_reserva_channel"
        const val CHANNEL_NAME = "Monitoreo de Reservas"
        const val NOTIFICATION_ID = 1001
        const val SUCCESS_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_MENU_TYPE = "extra_menu_type"
        const val EXTRA_QUANTITY = "extra_quantity"

        fun start(context: Context, username: String, pass: String, menuType: MenuType, quantity: Int) {
            val intent = Intent(context, ReservationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, pass)
                putExtra(EXTRA_MENU_TYPE, menuType.name)
                putExtra(EXTRA_QUANTITY, quantity)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ReservationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var searchJob: Job? = null
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop action received")
                stopForegroundMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: ReservationRepository.savedUsername
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ReservationRepository.savedPassword
                val menuTypeName = intent.getStringExtra(EXTRA_MENU_TYPE) ?: ReservationRepository.selectedMenuType.name
                val menuType = try { MenuType.valueOf(menuTypeName) } catch (e: Exception) { MenuType.NORMAL }
                val quantity = intent.getIntExtra(EXTRA_QUANTITY, ReservationRepository.selectedQuantity)

                ReservationRepository.savedUsername = username
                ReservationRepository.savedPassword = password
                ReservationRepository.selectedMenuType = menuType
                ReservationRepository.selectedQuantity = quantity

                startForegroundMonitoring(username, password, menuType, quantity)
                return START_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones de búsqueda y estado de reservas del restaurante"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundMonitoring(
        username: String,
        password: String,
        menuType: MenuType,
        quantity: Int
    ) {
        val initialNotification = buildMonitoringNotification(
            menuType = menuType,
            quantity = quantity,
            statusText = "Aún no se ha encontrado el menú, no está disponible. Verificando automáticamente..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        ReservationRepository.updateRunning(true, "Monitoreando menú ${menuType.label} ($quantity porción/es)")

        searchJob?.cancel()
        searchJob = serviceScope.launch {
            val intervalMs = if (menuType == MenuType.DIETA) 7000L else 10000L
            var attemptCount = 0

            Log.i(TAG, "Starting polling loop for ${menuType.label} (Qty: $quantity) every ${intervalMs}ms")

            // Ensure logged in initially
            ReservationRepository.engine.validateCredentials(username, password)

            while (isActive) {
                attemptCount++
                try {
                    // Check availability
                    val availableMenu = ReservationRepository.engine.checkAvailableMenu(menuType)

                    if (availableMenu != null && availableMenu.isAvailable) {
                        // Found menu! Update UI & Notification
                        Log.i(TAG, "Menu available: ${availableMenu.name} (id: ${availableMenu.id}). Reserving now!")
                        ReservationRepository.recordAttempt(menuType, true, availableMenu.name, quantity)

                        updateNotification(
                            buildMonitoringNotification(
                                menuType = menuType,
                                quantity = quantity,
                                statusText = "¡Menú disponible encontrado! Enviando reserva..."
                            )
                        )

                        // Perform reservation
                        val reservationResult = ReservationRepository.engine.submitReservation(
                            menuType = menuType,
                            menuId = availableMenu.id,
                            quantity = quantity
                        ).copy(menuName = availableMenu.name)

                        // Record result
                        ReservationRepository.recordCompletedReservation(reservationResult)

                        // Show final success notification
                        showCompletionNotification(
                            menuName = availableMenu.name,
                            quantity = quantity,
                            menuType = menuType,
                            isSuccess = reservationResult.isSuccess
                        )

                        // Stop monitoring loop
                        break
                    } else {
                        // Not available yet
                        ReservationRepository.recordAttempt(menuType, false, null, quantity)
                        updateNotification(
                            buildMonitoringNotification(
                                menuType = menuType,
                                quantity = quantity,
                                statusText = "Aún no se ha encontrado el menú, no está disponible. (Intento #$attemptCount)"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during check cycle: ${e.message}", e)
                    ReservationRepository.addLog("⚠️ Error en ciclo #$attemptCount: ${e.localizedMessage}")
                }

                delay(intervalMs)
            }

            ReservationRepository.updateRunning(false)
            stopForeground(false)
            stopSelf()
        }
    }

    private fun buildMonitoringNotification(
        menuType: MenuType,
        quantity: Int,
        statusText: String
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ReservationService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🍽️ Buscando Menú ${menuType.label} (x$quantity)")
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Detener Búsqueda", stopPendingIntent)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(
        menuName: String,
        quantity: Int,
        menuType: MenuType,
        isSuccess: Boolean
    ) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (isSuccess) "✅ ¡Reserva realizada con éxito!" else "❌ Intento de reserva fallido"
        val message = if (isSuccess) {
            "Se reservó: $menuName | Cantidad: $quantity (${menuType.label})"
        } else {
            "No se pudo completar la reserva para: $menuName"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(SUCCESS_NOTIFICATION_ID, notification)
    }

    private fun stopForegroundMonitoring() {
        searchJob?.cancel()
        ReservationRepository.updateRunning(false, "Búsqueda cancelada por el usuario")
        stopForeground(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        ReservationRepository.updateRunning(false)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
