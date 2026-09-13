package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.example.data.*
import com.example.domain.AttendancePolicy
import com.example.ui.components.ScheduleMode
import com.example.util.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AttendanceViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val attendanceDao = db.attendanceDao()
    private val userDao = db.userDao()
    private val prefs = application.getSharedPreferences("admin_settings", Context.MODE_PRIVATE)
    private val syncMutex = Mutex()
    private val authMutex = Mutex()
    private val _scheduleMode = MutableStateFlow(runCatching {
        ScheduleMode.valueOf(prefs.getString("schedule_mode", "AUTOMATIC")!!)
    }.getOrDefault(ScheduleMode.AUTOMATIC))
    val scheduleMode = _scheduleMode.asStateFlow()
    private val _currentUser = MutableStateFlow<UserEntity?>(null)
    val currentUser = _currentUser.asStateFlow()
    val namaLengkap = MutableStateFlow("")
    val nip = MutableStateFlow("")
    val jabatan = MutableStateFlow("")
    val jenisAbsensi = MutableStateFlow(AttendancePolicy.MASUK)
    val capturedBitmap = MutableStateFlow<Bitmap?>(null)
    val photoBase64 = MutableStateFlow("")
    private val _locationState = MutableStateFlow<UserLocationResult?>(null)
    val locationState = _locationState.asStateFlow()
    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting = _isSubmitting.asStateFlow()
    val currentTab = MutableStateFlow(0)
    val selectedMonthFilter = MutableStateFlow(AttendancePolicy.monthLabel())
    val allAttendanceList = attendanceDao.getAllAttendanceFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val allUsersList = userDao.getAllUsersFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // A manual override saved in Settings always wins; otherwise fall back to the value
    // baked into this build via ABSENSI_WEBHOOK_URL / ABSENSI_SYNC_TOKEN (see build.gradle.kts),
    // so a device needs zero manual setup when the office builds its own APK.
    val syncTokenState = MutableStateFlow(prefs.getString("sync_token", "").orEmpty().ifBlank { com.example.BuildConfig.SYNC_TOKEN_DEFAULT })
    val webhookUrlState = MutableStateFlow(prefs.getString("webhook_url", "").orEmpty().ifBlank { com.example.BuildConfig.SYNC_WEBHOOK_URL })
    val isUsingBuiltInSyncConfig = MutableStateFlow(
        prefs.getString("webhook_url", "").isNullOrBlank() && com.example.BuildConfig.SYNC_WEBHOOK_URL.isNotBlank()
    )
    // Defaults to ON: the goal is every employee who installs the app gets reminded on
    // their own device automatically, without needing an admin to visit Settings on that
    // specific phone first (this flag lives in per-device SharedPreferences, so there is
    // no way to flip it centrally for everyone -- opting everyone in by default is the
    // only way a per-device flag can reach "seluruh pegawai" without real push infra).
    // The admin toggle in Settings still exists as a per-device override/kill switch.
    val pushNotificationsEnabled = MutableStateFlow(prefs.getBoolean("push_enabled", true))
    private val _isAdminAuthenticated = MutableStateFlow(false)
    val isAdminAuthenticated = _isAdminAuthenticated.asStateFlow()
    // Identifies the currently-authenticated admin by NIP rather than a local Room id: admin
    // identity now lives entirely on the server (see loginAdmin below), so there is not
    // necessarily any local UserEntity row corresponding to it.
    private var authenticatedAdminNip: String? = null
    private var failedLoginAttempts = 0
    private var loginBlockedUntil = 0L

    private fun checkLoginAllowed() {
        require(android.os.SystemClock.elapsedRealtime() >= loginBlockedUntil) { "Terlalu banyak percobaan. Coba lagi setelah 30 detik." }
    }
    private fun recordLoginResult(success: Boolean) {
        if (success) failedLoginAttempts = 0
        else if (++failedLoginAttempts >= 5) {
            loginBlockedUntil = android.os.SystemClock.elapsedRealtime() + 30000
            failedLoginAttempts = 0
        }
    }

    init {
        GoogleSheetsManager.webhookUrl = webhookUrlState.value
        GoogleSheetsManager.syncToken = syncTokenState.value
        TrustedTime.init(application)
        // Persist the resolved default (rather than leaving it implicit) so this and
        // ReminderReceiver's own SharedPreferences read can never silently disagree again
        // if either fallback literal is ever changed on its own in the future.
        if (!prefs.contains("push_enabled")) {
            prefs.edit().putBoolean("push_enabled", pushNotificationsEnabled.value).apply()
        }
        NotificationHelper.scheduleReminders(application, pushNotificationsEnabled.value)
        refreshGpsLocation()
    }

    fun setScheduleMode(mode: ScheduleMode) {
        check(_isAdminAuthenticated.value) { "Login admin diperlukan" }
        _scheduleMode.value = mode
        prefs.edit().putString("schedule_mode", mode.name).apply()
    }

    fun saveWebhook(url: String, token: String): Boolean {
        if (!_isAdminAuthenticated.value || (url.isNotBlank() && !GoogleSheetsManager.isValidWebhook(url.trim()))) return false
        if (url.isNotBlank() && token.isBlank()) return false
        // Clearing the fields reverts this device to whatever is baked into the build
        // (if any) instead of leaving sync fully disabled.
        val effectiveUrl = url.trim().ifBlank { com.example.BuildConfig.SYNC_WEBHOOK_URL }
        val effectiveToken = token.trim().ifBlank { com.example.BuildConfig.SYNC_TOKEN_DEFAULT }
        syncTokenState.value = effectiveToken
        GoogleSheetsManager.syncToken = effectiveToken
        webhookUrlState.value = effectiveUrl
        GoogleSheetsManager.webhookUrl = effectiveUrl
        isUsingBuiltInSyncConfig.value = url.trim().isBlank() && com.example.BuildConfig.SYNC_WEBHOOK_URL.isNotBlank()
        prefs.edit().putString("webhook_url", url.trim()).putString("sync_token", token.trim()).apply()
        return true
    }

    fun setPushNotifications(enabled: Boolean) {
        if (!_isAdminAuthenticated.value) return
        pushNotificationsEnabled.value = enabled
        prefs.edit().putBoolean("push_enabled", enabled).apply()
        NotificationHelper.scheduleReminders(getApplication(), enabled)
    }

    // Admin identity and credentials live entirely on the server (see backend/Code.gs,
    // action: "adminLogin") -- there is deliberately no path here that reads or writes a
    // local Room "admin" account, and no bootstrap branch that lets a fresh install create
    // one. Removing that branch was the actual fix for the "any device can make itself
    // admin" bug; everything else here just wires the server's answer into the session.
    fun loginAdmin(username: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = authMutex.withLock {
                try {
                    checkLoginAllowed()
                    require(username.isNotBlank() && password.isNotBlank()) { "NIP dan kata sandi admin wajib diisi" }
                    when (val result = GoogleSheetsManager.adminLogin(username.trim(), password)) {
                        is AdminLoginResult.Success -> {
                            recordLoginResult(true)
                            authenticatedAdminNip = result.nip
                            _isAdminAuthenticated.value = true
                            null
                        }
                        is AdminLoginResult.Rejected -> {
                            recordLoginResult(false)
                            result.message
                        }
                        is AdminLoginResult.NetworkFailure -> {
                            // A connectivity/config problem is not a wrong password -- don't
                            // burn the attempt budget for it.
                            "Tidak dapat menghubungi server: ${result.message}"
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { e.message ?: "Login gagal" }
            }
            onResult(error)
        }
    }

    fun logoutAdmin() {
        authenticatedAdminNip = null
        _isAdminAuthenticated.value = false
        currentTab.value = 0
    }

    fun selectUserForForm(user: UserEntity) {
        if (!user.isActive) return
        namaLengkap.value = user.namaLengkap
        nip.value = user.nip
        jabatan.value = user.jabatan
        capturedBitmap.value = null
        photoBase64.value = ""
    }

    fun refreshGpsLocation() {
        viewModelScope.launch { _locationState.value = LocationHelper.getCurrentLocation(getApplication()) }
    }

    fun setCapturedPhoto(bitmap: Bitmap, base64: String) {
        capturedBitmap.value = bitmap
        photoBase64.value = base64
    }

    fun submitAttendance(onSuccess: (Boolean, String?) -> Unit, onError: (String) -> Unit) {
        if (_isSubmitting.value) return
        _isSubmitting.value = true
        // Snapshot inputs before any suspension so a camera/profile change cannot mix records.
        // No login/account is required to submit attendance: name, NIP, and jabatan are taken
        // directly from the form as typed (or as filled from the quick-select shortcut).
        val inputNama = namaLengkap.value.trim()
        val inputNip = nip.value.trim()
        val inputJabatan = jabatan.value.trim()
        val kind = jenisAbsensi.value
        val bitmap = capturedBitmap.value
        val photo = photoBase64.value
        viewModelScope.launch {
            try {
                require(kind == AttendancePolicy.MASUK || kind == AttendancePolicy.PULANG) { "Jenis absensi tidak valid" }
                require(inputNama.isNotBlank() && inputNip.isNotBlank() && inputJabatan.isNotBlank()) {
                    "Lengkapi nama, NIP, dan jabatan"
                }
                require(bitmap != null && photo.isNotBlank()) { "Foto absensi wajib dilampirkan" }
                val loc = LocationHelper.getCurrentLocation(getApplication())
                _locationState.value = loc
                require(loc.isAvailable) { loc.address }
                require(loc.isWithinBitungArea) { "Lokasi berada di luar radius WFH yang diizinkan (35 km)" }
                // The device's own system clock (Settings > Date & time) is user-editable and
                // must never decide whether a submission falls inside the attendance window --
                // that was exactly the bug this closes (change the clock, submit anytime).
                // TrustedTime anchors real time to our own server via a monotonic per-boot
                // counter the user cannot edit, so changing the phone's date/time no longer
                // changes what "now" means here. If no trusted time can be established at all
                // (e.g. never been online since the last reboot), the submission is refused
                // rather than silently falling back to the spoofable system clock.
                val now = TrustedTime.ensureFreshOrNull()
                    ?: throw IllegalStateException("Tidak dapat memverifikasi waktu perangkat. Sambungkan ke internet, lalu coba lagi.")
                val mode = _scheduleMode.value
                val windowOpen = mode != ScheduleMode.FORCE_LOCKED &&
                    (mode == ScheduleMode.FORCE_OPEN || AttendancePolicy.window(now) == kind.removePrefix("ABSENSI "))
                if (!windowOpen) {
                    // `now` is TrustedTime's server-anchored value -- immune to Settings > Date
                    // & time changes. System.currentTimeMillis() still reflects whatever the
                    // device's own clock says. A large gap between the two means the device
                    // clock has been changed manually; that mismatch is what TrustedTime exists
                    // to catch, independent of whether the *real* time also happens to fall
                    // outside the attendance window.
                    val deviceClockMs = System.currentTimeMillis()
                    val driftMs = kotlin.math.abs(deviceClockMs - now)
                    val clockTamperThresholdMs = 5 * 60 * 1000L // 5 minutes: generous vs. normal drift
                    val message = if (driftMs > clockTamperThresholdMs) {
                        val driftMinutes = driftMs / 60000
                        "Jam pada perangkat ini terpaut sekitar $driftMinutes menit dari waktu server " +
                            "-- terindikasi diubah manual. Absensi ditolak untuk mencegah manipulasi waktu. " +
                            "Aktifkan \"Tanggal & waktu otomatis\" di pengaturan perangkat, lalu coba lagi."
                    } else {
                        "Jadwal untuk jenis absensi ini sedang ditutup"
                    }
                    throw IllegalStateException(message)
                }
                val time = AttendancePolicy.format("HH:mm:ss", now)
                val record = AttendanceEntity(namaLengkap = inputNama, nip = inputNip, jabatan = inputJabatan,
                    jenisAbsensi = kind, timestamp = now, dateFormatted = AttendancePolicy.format("EEEE, d MMMM yyyy", now),
                    timeFormatted = time, jamMasuk = if (kind == AttendancePolicy.MASUK) time else "-",
                    jamPulang = if (kind == AttendancePolicy.PULANG) time else "-", latitude = loc.latitude,
                    longitude = loc.longitude, locationAddress = loc.address,
                    photoBase64 = photo, encryptedHash = CryptoUtils.generatePayloadHash(inputNip, now, kind))
                val id = db.withTransaction {
                    // If this NIP happens to belong to a registered profile that has been
                    // deactivated by an admin, block the submission; unregistered NIPs are fine.
                    require(userDao.getUserByNip(inputNip)?.isActive != false) { "Pegawai sudah dinonaktifkan" }
                    val (start, end) = AttendancePolicy.dayBounds(now)
                    require(attendanceDao.getRecordInDay(inputNip, start, end, kind) == null) { "Absensi ini sudah tercatat hari ini" }
                    attendanceDao.insertAttendance(record)
                }
                _currentUser.value = userDao.getUserByNip(inputNip)
                capturedBitmap.value = null; photoBase64.value = ""
                // Local save is already committed: any sync failure must remain a pending record.
                val synced = syncMutex.withLock {
                    try {
                        val ok = GoogleSheetsManager.syncAttendanceRecord(record.copy(id = id))
                        if (ok) attendanceDao.markSynced(id)
                        ok
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                }
                onSuccess(synced, if (synced) null else GoogleSheetsManager.lastSyncError)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "Gagal menyimpan absensi") }
            finally { _isSubmitting.value = false }
        }
    }

    fun syncAllUnsyncedRecords() {
        if (!_isAdminAuthenticated.value) return
        viewModelScope.launch {
            syncMutex.withLock {
                try {
                    val list = attendanceDao.getUnsyncedIds()
                    var success = 0
                    for (id in list) {
                        val item = attendanceDao.getAttendanceById(id) ?: continue
                        if (GoogleSheetsManager.syncAttendanceRecord(item)) {
                            attendanceDao.markSynced(item.id); success++
                        }
                    }
                    val failed = list.size - success
                    toast(
                        if (failed == 0) "$success tersinkron; semua data lokal berhasil dikirim"
                        else "$success tersinkron; $failed masih tersimpan lokal — sebab: ${GoogleSheetsManager.lastSyncError ?: "tidak diketahui"}"
                    )
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { toast("Sinkronisasi gagal. Data lokal tetap tersimpan.") }
            }
        }
    }

    fun exportReportPdf() = exportReport(true)
    fun exportReportCsv() = exportReport(false)
    private fun exportReport(pdf: Boolean) {
        if (!_isAdminAuthenticated.value) return
        val month = selectedMonthFilter.value
        viewModelScope.launch {
            try {
                val records = attendanceDao.getAllAttendanceFlow().first().filter { AttendancePolicy.monthLabel(it.timestamp) == month }
                val file = withContext(Dispatchers.IO) {
                    if (pdf) ExportUtils.exportToPdf(getApplication(), month, records)
                    else ExportUtils.exportToExcelCsv(getApplication(), month, records)
                }
                ExportUtils.openOrShareFile(getApplication(), file, if (pdf) "application/pdf" else "text/csv")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast("Ekspor gagal: ${e.message}") }
        }
    }

    private val _isExportingRekapGabungan = MutableStateFlow(false)
    val isExportingRekapGabungan = _isExportingRekapGabungan.asStateFlow()

    /** Rekap gabungan semua pegawai dari SEMUA device, ditarik langsung dari Google Sheets
     *  (via doGet) — admin tidak perlu membuka Sheets manual. Beda dengan exportReportPdf/Csv
     *  di atas yang hanya membaca database lokal device ini. */
    fun exportRekapGabunganPdf() = exportRekapGabungan(true)
    fun exportRekapGabunganCsv() = exportRekapGabungan(false)
    private fun exportRekapGabungan(pdf: Boolean) {
        if (!_isAdminAuthenticated.value || _isExportingRekapGabungan.value) return
        val month = selectedMonthFilter.value
        viewModelScope.launch {
            _isExportingRekapGabungan.value = true
            try {
                val remote = GoogleSheetsManager.fetchAllRecords()
                if (remote == null) {
                    toast("Gagal mengambil rekap dari Google Sheets: ${GoogleSheetsManager.lastSyncError ?: "alasan tidak diketahui"}")
                    return@launch
                }
                val summaries = remote
                    .filter { AttendancePolicy.monthLabel(it.timestamp) == month }
                    .sortedBy { it.timestamp }
                    .mapIndexed { index, r ->
                        AttendanceSummary(
                            id = index.toLong(),
                            namaLengkap = r.namaLengkap,
                            nip = r.nip,
                            jabatan = r.jabatan,
                            jenisAbsensi = r.jenisAbsensi,
                            timestamp = r.timestamp,
                            dateFormatted = r.tanggal,
                            timeFormatted = r.jamMasuk,
                            jamMasuk = r.jamMasuk,
                            jamPulang = r.jamPulang,
                            locationAddress = if (r.latitude != 0.0 || r.longitude != 0.0) "${r.latitude}, ${r.longitude}" else "-",
                            hasPhoto = r.fotoUrl.isNotBlank(),
                            isSyncedToSheets = true
                        )
                    }
                val label = "$month (Gabungan Semua Pegawai)"
                val file = withContext(Dispatchers.IO) {
                    if (pdf) ExportUtils.exportToPdf(getApplication(), label, summaries)
                    else ExportUtils.exportToExcelCsv(getApplication(), label, summaries)
                }
                ExportUtils.openOrShareFile(getApplication(), file, if (pdf) "application/pdf" else "text/csv")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast("Ekspor rekap gabungan gagal: ${e.message}") }
            finally { _isExportingRekapGabungan.value = false }
        }
    }

    // Employees added here are always plain USER records (attendance only, no login/password).
    // There is intentionally no ADMIN option and no password field: admin accounts are
    // configured only on the server (setupAdminAccount() in backend/Code.gs), never from
    // inside the app -- that is the whole point of this fix. See docs/AUDIT.md.
    fun saveNewUser(nama: String, nipInput: String, jabatanInput: String, tipe: String,
                    onResult: (String?) -> Unit) {
        if (!_isAdminAuthenticated.value) { onResult("Login admin diperlukan"); return }
        viewModelScope.launch {
            try {
                require(nama.isNotBlank() && nipInput.isNotBlank() && jabatanInput.isNotBlank()) { "Lengkapi nama, NIP, dan jabatan" }
                require(tipe in listOf("PNS", "PPPK")) { "Tipe pegawai tidak valid" }
                db.withTransaction {
                    require(userDao.getUserByNip(nipInput.trim()) == null) { "NIP sudah terdaftar" }
                    userDao.insertUser(UserEntity(namaLengkap = nama.trim(), nip = nipInput.trim(), jabatan = jabatanInput.trim(),
                        tipePegawai = tipe, role = "USER", pinCode = ""))
                }
                onResult(null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onResult(e.message ?: "Gagal menyimpan pengguna") }
        }
    }

    fun toggleUserActiveState(user: UserEntity) {
        if (!_isAdminAuthenticated.value) return
        viewModelScope.launch {
            try {
                require(user.nip != authenticatedAdminNip) { "Tidak dapat menonaktifkan akun admin yang sedang digunakan" }
                userDao.updateUser(user.copy(isActive = !user.isActive))
                if (_currentUser.value?.id == user.id) _currentUser.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Gagal mengubah pengguna") }
        }
    }
    private fun toast(message: String) = Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
}
