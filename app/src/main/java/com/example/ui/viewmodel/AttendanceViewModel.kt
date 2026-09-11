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
    val pushNotificationsEnabled = MutableStateFlow(prefs.getBoolean("push_enabled", false))
    private val _isAdminAuthenticated = MutableStateFlow(false)
    val isAdminAuthenticated = _isAdminAuthenticated.asStateFlow()
    val needsAdminSetup = MutableStateFlow(false)
    private var authenticatedAdminId: Long? = null
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

    // Call while holding authMutex so concurrent taps share one attempt limit.
    // Admin-only: regular attendance no longer requires any account or password.
    private suspend fun authenticateAdmin(username: String, password: String): UserEntity {
        checkLoginAllowed()
        val user = userDao.getUserByNip(username)
        val valid = user != null && user.isActive && user.role == "ADMIN" &&
            withContext(Dispatchers.Default) { PasswordHasher.verify(password, user.pinCode) }
        recordLoginResult(valid)
        require(valid) { "NIP atau kata sandi tidak sesuai, atau akun admin tidak aktif" }
        return requireNotNull(user)
    }

    init {
        GoogleSheetsManager.webhookUrl = webhookUrlState.value
        GoogleSheetsManager.syncToken = syncTokenState.value
        viewModelScope.launch { needsAdminSetup.value = userDao.getConfiguredAdminCount() == 0 }
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

    fun loginAdmin(username: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val error = authMutex.withLock {
                try {
                    checkLoginAllowed()
                    if (userDao.getConfiguredAdminCount() == 0) {
                        require(username.isNotBlank()) { "Isi NIP admin pertama" }
                        val hashed = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
                        db.withTransaction {
                            require(userDao.getConfiguredAdminCount() == 0) { "Admin sudah disiapkan. Silakan login." }
                            val existing = userDao.getUserByNip(username)
                            if (existing == null) {
                                userDao.insertUser(UserEntity(namaLengkap = "Administrator", nip = username,
                                    jabatan = "Administrator", tipePegawai = "PNS", role = "ADMIN", pinCode = hashed))
                            } else {
                                // Explicit first-device provisioning / recovery from the former shared default PIN.
                                userDao.updateUser(existing.copy(role = "ADMIN", pinCode = hashed, isActive = true))
                            }
                        }
                    }
                    val admin = authenticateAdmin(username, password)
                    authenticatedAdminId = admin.id
                    _isAdminAuthenticated.value = true
                    needsAdminSetup.value = false
                    null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { e.message ?: "Login gagal" }
            }
            onResult(error)
        }
    }

    fun logoutAdmin() {
        authenticatedAdminId = null
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

    fun submitAttendance(onSuccess: (Boolean) -> Unit, onError: (String) -> Unit) {
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
                val now = System.currentTimeMillis()
                val mode = _scheduleMode.value
                require(mode != ScheduleMode.FORCE_LOCKED && (mode == ScheduleMode.FORCE_OPEN ||
                    AttendancePolicy.window(now) == kind.removePrefix("ABSENSI "))) { "Jadwal untuk jenis absensi ini sedang ditutup" }
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
                onSuccess(synced)
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
                    toast("$success tersinkron; ${list.size - success} masih tersimpan lokal")
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
                    toast("Gagal mengambil rekap dari Google Sheets. Periksa konfigurasi webhook/token dan koneksi internet.")
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

    fun saveNewUser(nama: String, nipInput: String, jabatanInput: String, tipe: String, role: String,
                    password: String, onResult: (String?) -> Unit) {
        if (!_isAdminAuthenticated.value) { onResult("Login admin diperlukan"); return }
        viewModelScope.launch {
            try {
                require(nama.isNotBlank() && nipInput.isNotBlank() && jabatanInput.isNotBlank()) { "Lengkapi nama, NIP, dan jabatan" }
                require(role in listOf("ADMIN", "USER") && tipe in listOf("PNS", "PPPK")) { "Peran atau tipe pegawai tidak valid" }
                // Only ADMIN accounts authenticate with a password (to unlock Monitoring, Hak
                // Akses, and Pengaturan / mode pembatasan waktu finger). Regular USER employees
                // attend without any login, so their password is left blank/unused.
                require(role != "ADMIN" || password.length >= 8) { "Kata sandi admin minimal 8 karakter" }
                // hash() enforces a minimum length, so only ever call it for ADMIN accounts —
                // USER accounts simply keep the default blank pinCode (no password, no login).
                val hash = if (role == "ADMIN") {
                    withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
                } else ""
                db.withTransaction {
                    require(userDao.getUserByNip(nipInput.trim()) == null) { "NIP sudah terdaftar" }
                    userDao.insertUser(UserEntity(namaLengkap = nama.trim(), nip = nipInput.trim(), jabatan = jabatanInput.trim(),
                        tipePegawai = tipe, role = role, pinCode = hash))
                }
                onResult(null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onResult(e.message ?: "Gagal menyimpan pengguna") }
        }
    }

    fun resetUserPassword(user: UserEntity, password: String, onResult: (String?) -> Unit) {
        if (!_isAdminAuthenticated.value) { onResult("Login admin diperlukan"); return }
        viewModelScope.launch {
            try {
                val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
                db.withTransaction {
                    val fresh = userDao.getUserByNip(user.nip) ?: error("Pengguna tidak ditemukan")
                    userDao.updateUser(fresh.copy(pinCode = hash))
                }
                onResult(null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onResult(e.message ?: "Gagal mengubah kata sandi") }
        }
    }

    fun toggleUserActiveState(user: UserEntity) {
        if (!_isAdminAuthenticated.value) return
        viewModelScope.launch {
            try {
                require(user.id != authenticatedAdminId) { "Tidak dapat menonaktifkan akun admin yang sedang digunakan" }
                userDao.updateUser(user.copy(isActive = !user.isActive))
                if (_currentUser.value?.id == user.id) _currentUser.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toast(e.message ?: "Gagal mengubah pengguna") }
        }
    }
    private fun toast(message: String) = Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
}
