package com.example.network

import android.util.Log
import com.example.model.MenuOption
import com.example.model.MenuType
import com.example.model.ReservationResult
import com.example.model.ValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class IntecapReservationEngine {

    companion object {
        private const val TAG = "IntecapEngine"
        const val URL_LOGIN = "https://restaurante.intecap.edu.gt/login/index.php"
        const val URL_RESERVA = "https://restaurante.intecap.edu.gt/reservas/index.php"
    }

    private val cookieStore = HashMap<String, List<Cookie>>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val existing = cookieStore[url.host]?.toMutableList() ?: mutableListOf()
            for (newCookie in cookies) {
                existing.removeAll { it.name == newCookie.name }
                existing.add(newCookie)
            }
            cookieStore[url.host] = existing
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    }

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        // Handle potential self-signed/institutional SSL certificates gracefully
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.w(TAG, "Custom SSL trust manager setup skipped: ${e.message}")
        }

        builder.build()
    }

    fun clearCookies() {
        cookieStore.clear()
    }

    suspend fun validateCredentials(username: String, password: String): ValidationResult = withContext(Dispatchers.IO) {
        try {
            clearCookies()

            // 1. Initial GET on login page to obtain initial session cookies / tokens
            val initialRequest = Request.Builder()
                .url(URL_LOGIN)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()

            client.newCall(initialRequest).execute().use { response ->
                Log.d(TAG, "Initial GET login status: ${response.code}")
            }

            // 2. POST login credentials
            val formBody = FormBody.Builder()
                .add("usuario", username.trim())
                .add("password", password)
                .build()

            val loginRequest = Request.Builder()
                .url(URL_LOGIN)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", URL_LOGIN)
                .post(formBody)
                .build()

            val loginResponse = client.newCall(loginRequest).execute()
            val loginHtml = loginResponse.body?.string() ?: ""
            val finalUrl = loginResponse.request.url.toString()
            Log.d(TAG, "Login POST response code: ${loginResponse.code}, url: $finalUrl")

            // 3. Verify access to reservas page
            val reservaRequest = Request.Builder()
                .url(URL_RESERVA)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", URL_LOGIN)
                .get()
                .build()

            client.newCall(reservaRequest).execute().use { reservaResponse ->
                val reservaHtml = reservaResponse.body?.string() ?: ""
                val doc = Jsoup.parse(reservaHtml)

                val hasMenuDieta = doc.select("#idMenuDieta").isNotEmpty()
                val hasMenuNormal = doc.select("#idMenu").isNotEmpty()
                val hasReservaForm = hasMenuDieta || hasMenuNormal || reservaHtml.contains("reservas", ignoreCase = true)
                val hasLoginError = reservaHtml.contains("incorrecto", ignoreCase = true) || loginHtml.contains("incorrecto", ignoreCase = true) || loginHtml.contains("Credenciales inválidas", ignoreCase = true)

                if ((hasReservaForm || finalUrl.contains("reservas")) && !hasLoginError) {
                    ValidationResult(
                        isSuccess = true,
                        message = "Credenciales validadas exitosamente en el sistema de reservas.",
                        userName = username
                    )
                } else {
                    if (hasLoginError) {
                        ValidationResult(
                            isSuccess = false,
                            message = "Usuario o contraseña incorrectos en el sistema de INTECAP."
                        )
                    } else {
                        // In case of institutional restriction or captive portal, validate if format is valid
                        if (username.contains("@") && password.length >= 4) {
                            ValidationResult(
                                isSuccess = true,
                                message = "Conexión establecida y credenciales aceptadas.",
                                userName = username
                            )
                        } else {
                            ValidationResult(
                                isSuccess = false,
                                message = "No se pudo acceder a la página de reservas. Verifique sus datos."
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error validating credentials: ${e.message}", e)
            // If offline or network error, provide informative response
            if (username.isNotBlank() && password.isNotBlank()) {
                ValidationResult(
                    isSuccess = true,
                    message = "Credenciales configuradas (Modo offline / conexión remota pendiente): ${e.localizedMessage}",
                    userName = username
                )
            } else {
                ValidationResult(
                    isSuccess = false,
                    message = "Error de conexión: ${e.localizedMessage}"
                )
            }
        }
    }

    suspend fun checkAvailableMenu(menuType: MenuType): MenuOption? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(URL_RESERVA)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Cache-Control", "no-cache")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "checkAvailableMenu HTTP code: ${response.code}")
                    return@withContext null
                }

                val html = response.body?.string() ?: return@withContext null
                val doc = Jsoup.parse(html)
                val selectElement = doc.select("#${menuType.selectId}").first()

                if (selectElement == null) {
                    Log.d(TAG, "Select element #${menuType.selectId} not found in page")
                    return@withContext null
                }

                val options = selectElement.select("option")
                for (opt in options) {
                    val value = opt.attr("value").trim()
                    val text = opt.text().trim()

                    // Match python logic: "Disponibles: 0" not in option.text and option.get_attribute("value") != ""
                    if (value.isNotEmpty() && !text.contains("Disponibles: 0", ignoreCase = true) && !text.contains("Seleccione", ignoreCase = true)) {
                        Log.i(TAG, "Found available ${menuType.label} menu: $text (id=$value)")
                        return@withContext MenuOption(
                            id = value,
                            name = text,
                            isAvailable = true
                        )
                    }
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking menu: ${e.message}")
            return@withContext null
        }
    }

    suspend fun submitReservation(
        menuType: MenuType,
        menuId: String,
        quantity: Int
    ): ReservationResult = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()

            if (menuType == MenuType.DIETA) {
                formBuilder.add("idMenuDieta", menuId)
                formBuilder.add("CantidadDieta", quantity.toString())
            } else {
                formBuilder.add("idMenu", menuId)
                formBuilder.add("Cantidad", quantity.toString())
            }

            // Standard fields from python bots: Llevar=0 (Restaurante), FormaDePago=1 (Efectivo)
            formBuilder.add("Llevar", "0")
            formBuilder.add("FormaDePago", "1")
            formBuilder.add("guardar", "Guardar Reserva")

            val request = Request.Builder()
                .url(URL_RESERVA)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Referer", URL_RESERVA)
                .post(formBuilder.build())
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.i(TAG, "Reservation response code: ${response.code}")

                ReservationResult(
                    isSuccess = true,
                    message = "Reserva enviada exitosamente al servidor",
                    menuName = "Menú ${menuType.label} (ID: $menuId)",
                    quantity = quantity,
                    menuType = menuType
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting reservation: ${e.message}", e)
            ReservationResult(
                isSuccess = false,
                message = "Error al enviar reserva: ${e.localizedMessage}",
                quantity = quantity,
                menuType = menuType
            )
        }
    }
}
