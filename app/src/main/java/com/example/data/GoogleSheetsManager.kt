package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One row of the combined, all-employee recap fetched from Sheets via doGet — not tied to
 *  any single device's local database. */
data class RemoteAttendanceRecord(
    val recordId: String,
    val namaLengkap: String,
    val nip: String,
    val jabatan: String,
    val jenisAbsensi: String,
    val jamMasuk: String,
    val jamPulang: String,
    val tanggal: String,
    val fotoUrl: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

object GoogleSheetsManager {
    var webhookUrl = ""
    var syncToken = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).build()

    fun isValidWebhook(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.isHttps && parsed.host == "script.google.com" &&
            Regex("/macros/s/[A-Za-z0-9_-]+/exec").matches(parsed.encodedPath) &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null
    }

    fun payload(record: AttendanceEntity): JSONObject = JSONObject().apply {
        put("recordId", record.encryptedHash)
        put("namaLengkap", record.namaLengkap); put("nip", record.nip); put("jabatan", record.jabatan)
        put("jenisAbsensi", record.jenisAbsensi); put("timestamp", record.timestamp)
        put("jamMasuk", record.jamMasuk); put("jamPulang", record.jamPulang)
        put("tanggal", record.dateFormatted); put("foto", record.photoBase64)
        put("latitude", record.latitude); put("longitude", record.longitude)
        put("locationAddress", record.locationAddress)
    }

    // A 200 HTML login page or an HTTP redirect is not an acknowledgement of storage.
    fun isAcknowledged(code: Int, body: String, recordId: String): Boolean = try {
        val json = JSONObject(body)
        code in 200..299 && json.opt("success") == true && recordId.isNotBlank() &&
            json.optString("recordId") == recordId
    } catch (_: Exception) { false }

    suspend fun syncAttendanceRecord(record: AttendanceEntity): Boolean = withContext(Dispatchers.IO) {
        val url = webhookUrl
        val token = syncToken
        if (!isValidWebhook(url) || token.isBlank() || record.encryptedHash.isBlank()) return@withContext false
        try {
            val request = Request.Builder().url(url)
                .post(payload(record).put("token", token).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                isAcknowledged(response.code, response.body?.string().orEmpty(), record.encryptedHash)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    /** Pulls the combined, all-employee recap straight from Sheets (via doGet) so the admin
     *  never has to open Sheets manually to export a cross-device report.
     *  Returns null on any failure (bad config, network, auth); an empty list means the
     *  sheet genuinely has no rows yet. */
    suspend fun fetchAllRecords(): List<RemoteAttendanceRecord>? = withContext(Dispatchers.IO) {
        val url = webhookUrl
        val token = syncToken
        if (!isValidWebhook(url) || token.isBlank()) return@withContext null
        try {
            val getUrl = url.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("token", token)?.build()
                ?: return@withContext null
            val request = Request.Builder().url(getUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.optBoolean("success") != true) return@withContext null
                val array = json.optJSONArray("records") ?: return@withContext emptyList()
                (0 until array.length()).mapNotNull { i ->
                    val item = array.optJSONObject(i) ?: return@mapNotNull null
                    RemoteAttendanceRecord(
                        recordId = item.optString("recordId"),
                        namaLengkap = item.optString("namaLengkap"),
                        nip = item.optString("nip"),
                        jabatan = item.optString("jabatan"),
                        jenisAbsensi = item.optString("jenisAbsensi"),
                        jamMasuk = item.optString("jamMasuk"),
                        jamPulang = item.optString("jamPulang"),
                        tanggal = item.optString("tanggal"),
                        fotoUrl = item.optString("foto"),
                        latitude = item.optDouble("latitude", 0.0),
                        longitude = item.optDouble("longitude", 0.0),
                        timestamp = item.optLong("timestamp", 0L)
                    )
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }
}
