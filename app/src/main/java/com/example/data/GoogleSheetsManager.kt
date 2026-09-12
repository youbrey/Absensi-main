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
    // Exposes the REAL reason the last sync/fetch attempt failed (exception type + message,
    // or the raw HTTP code/body when the server responded but wasn't acknowledged). Previously
    // every failure path collapsed to a bare `false` with the exception discarded, which made
    // it impossible to tell a network failure apart from a config problem or a server rejection.
    var lastSyncError: String? = null
        private set
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        // Apps Script /exec endpoints ALWAYS answer with an HTTP 302 to a
        // script.googleusercontent.com URL that carries the real doGet/doPost output; the
        // actual response is never on the first hop. Explicit here (matches OkHttp's default)
        // so this never silently breaks if the default ever changes.
        .followRedirects(true).followSslRedirects(true)
        .build()

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
        if (!isValidWebhook(url)) { lastSyncError = "URL webhook belum valid/kosong"; return@withContext false }
        if (token.isBlank()) { lastSyncError = "Token sinkronisasi kosong"; return@withContext false }
        if (record.encryptedHash.isBlank()) { lastSyncError = "recordId (hash) kosong — bug data lokal"; return@withContext false }
        try {
            val request = Request.Builder().url(url)
                .post(payload(record).put("token", token).toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val ok = isAcknowledged(response.code, body, record.encryptedHash)
                if (!ok) {
                    lastSyncError = "HTTP ${response.code}, respons: ${body.take(300)}"
                } else {
                    lastSyncError = null
                }
                ok
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            lastSyncError = "${e.javaClass.simpleName}: ${e.message}"
            false
        }
    }

    /** Pulls the combined, all-employee recap straight from Sheets (via doGet) so the admin
     *  never has to open Sheets manually to export a cross-device report.
     *  Returns null on any failure (bad config, network, auth); an empty list means the
     *  sheet genuinely has no rows yet. */
    suspend fun fetchAllRecords(): List<RemoteAttendanceRecord>? = withContext(Dispatchers.IO) {
        val url = webhookUrl
        val token = syncToken
        if (!isValidWebhook(url)) { lastSyncError = "URL webhook belum valid/kosong"; return@withContext null }
        if (token.isBlank()) { lastSyncError = "Token sinkronisasi kosong"; return@withContext null }
        try {
            val getUrl = url.toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("token", token)?.build()
                ?: run { lastSyncError = "URL webhook tidak bisa diparse"; return@withContext null }
            val request = Request.Builder().url(getUrl).get().build()
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastSyncError = "HTTP ${response.code}, respons: ${bodyText.take(300)}"
                    return@withContext null
                }
                val json = JSONObject(bodyText)
                if (json.optBoolean("success") != true) {
                    lastSyncError = "Server menolak: ${json.optString("error", bodyText.take(300))}"
                    return@withContext null
                }
                lastSyncError = null
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
        catch (e: Exception) {
            lastSyncError = "${e.javaClass.simpleName}: ${e.message}"
            null
        }
    }
}
