package com.example.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

data class UserLocationResult(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val address: String,
    val isWithinBitungArea: Boolean = false,
    val distanceKmToOffice: Double = 0.0,
    val isAvailable: Boolean = false
)

object LocationHelper {
    const val DPRD_BITUNG_LAT = 1.4421
    const val DPRD_BITUNG_LNG = 125.1834
    const val ALLOWED_WFH_RADIUS_KM = 35.0

    suspend fun getCurrentLocation(context: Context): UserLocationResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return UserLocationResult(address = "Izin lokasi presisi diperlukan. Aktifkan izin GPS.")
        }
        val token = CancellationTokenSource()
        return try {
            val location = withTimeoutOrNull(20000) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token).await()
            } ?: return UserLocationResult(address = "GPS tidak tersedia. Aktifkan lokasi dan coba lagi.")
            @Suppress("DEPRECATION")
            if (location.isFromMockProvider || !location.hasAccuracy() || location.accuracy > 100 ||
                SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos > 120_000_000_000L) {
                return UserLocationResult(address = "GPS tidak akurat, kedaluwarsa, atau lokasi simulasi. Coba lagi.")
            }
            val distance = calculateDistanceKm(location.latitude, location.longitude, DPRD_BITUNG_LAT, DPRD_BITUNG_LNG)
            UserLocationResult(location.latitude, location.longitude,
                "GPS: ${location.latitude}, ${location.longitude} (±${location.accuracy.toInt()} m)",
                distance <= ALLOWED_WFH_RADIUS_KM, distance, true)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { UserLocationResult(address = "Gagal memperoleh GPS. Periksa izin dan layanan lokasi.") }
        finally { token.cancel() }
    }

    internal fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val a = sin(Math.toRadians(lat2 - lat1) / 2).pow(2) + cos(Math.toRadians(lat1)) *
            cos(Math.toRadians(lat2)) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return 6371.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
