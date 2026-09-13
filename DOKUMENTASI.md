# DOKUMENTASI PERUBAHAN

# BUG 1 — "Buat Admin Pertama" (perangkat mana pun bisa jadi admin)

## Ringkasan bug

Sebelum perubahan ini, `AttendanceViewModel.loginAdmin()` mengecek jumlah admin
**pada database Room lokal perangkat** (`userDao.getConfiguredAdminCount() == 0`).
Karena setiap perangkat punya database lokalnya sendiri, HP mana pun yang baru
menginstal APK ini otomatis dianggap "belum punya admin", lalu layar login
menampilkan tombol **BUAT ADMIN PERTAMA** yang membuat akun admin dengan NIP +
kata sandi apa pun yang diketik di perangkat itu — tanpa verifikasi ke server
atau ke perangkat admin lain sama sekali. Ini persis bug yang dilaporkan: semua
orang yang menginstal aplikasi bisa membuat dirinya sendiri admin.

## Solusi yang diterapkan

Sesuai arahan: **verifikasi admin dipindah 100% ke server (Google Apps
Script)**, password admin tidak pernah dikirim ke atau disimpan di APK, dan
multi-admin tetap didukung tapi diatur oleh IT langsung di Apps Script.

### 1. Backend — `backend/Code.gs`

- Endpoint baru `action: "adminLogin"` di `doPost` (cabang paling atas, sebelum
  logika `SYNC_TOKEN`/spreadsheet — tidak mengubah perilaku sinkronisasi
  absensi yang sudah ada).
- Kredensial admin disimpan di Script Properties, key `ADMIN_ACCOUNTS`: array
  JSON `[{ "nip", "name", "hash" }, ...]` — **mendukung multi-admin** karena
  tinggal menambah entri baru.
- Hash password: SHA-256 bergaram (salt 16 byte) diulang N kali (default
  50.000×), format `sha256i:<iterasi>:<saltHex>:<hashHex>`. Diimplementasikan
  manual (fungsi `_sha256`, `hashAdminPassword`, `verifyAdminPassword`) karena
  Apps Script tidak punya PBKDF2 bawaan; jumlah iterasi ikut tersimpan di
  setiap hash sehingga aman diubah di masa depan tanpa merusak hash lama.
- **Tidak ada jalur jaringan yang bisa mengisi/mengubah `ADMIN_ACCOUNTS`.**
  Satu-satunya cara mengisi properti ini adalah menjalankan tiga fungsi
  berikut secara manual dari editor Apps Script (menu Run), yang berarti
  butuh akses akun Google pemilik proyek:
  - `setupAdminAccount()` — tambah/ubah satu admin (edit `nip`, `name`,
    `password` di kode, lalu Run). Menjalankan ulang dengan NIP yang sama =
    reset password admin itu.
  - `removeAdminAccount()` — hapus satu admin (mis. mutasi pegawai).
  - `listAdminAccounts()` — lihat NIP + nama admin terdaftar, tanpa pernah
    menampilkan hash/password.
- Rate limiting di server pakai `CacheService` (bukan hanya di klien seperti
  sebelumnya, yang gampang dilewati dengan uninstall/clear data):
  - Per NIP: kunci 5 menit setelah 5 kali salah dalam 15 menit.
  - Global (lintas semua NIP): kunci 10 menit setelah 20 kali salah dalam
    15 menit — supaya menebak-nebak banyak NIP sekaligus juga percuma,
    mengingat NIP PNS/PPPK pada praktiknya tidak benar-benar rahasia.
  - Waktu hashing untuk NIP yang tidak terdaftar dibuat sama dengan NIP yang
    terdaftar (pakai hash "dummy"), supaya respons tidak bisa dipakai untuk
    menebak NIP mana saja yang jadi admin.
- Tes otomatis ditambahkan di `backend/Code.test.cjs` (7 tes baru, total 16
  tes, semua lulus): tidak ada bootstrap tanpa `ADMIN_ACCOUNTS`, provisioning
  lewat `setupAdminAccount`-style call berhasil login, multi-admin saling
  independen, hash tidak pernah bocor di respons, lockout per-NIP tidak
  memengaruhi NIP lain, dan endpoint sinkronisasi absensi (`doPost` dengan
  `token`) tidak terganggu oleh cabang baru ini.

### 2. Android

- **`GoogleSheetsManager.kt`** — fungsi baru `adminLogin(nip, password)` yang
  mengirim `{ action: "adminLogin", nip, password }` ke URL webhook yang sama
  dengan sinkronisasi absensi (tidak perlu `SYNC_TOKEN`, karena password admin
  sendiri yang jadi kredensial). Mengembalikan `AdminLoginResult`
  (`Success` / `Rejected` / `NetworkFailure`) — pesan error jaringan tidak
  pernah memuat password.
- **`AttendanceViewModel.kt`**:
  - `loginAdmin()` ditulis ulang total: tidak lagi menyentuh Room DB sama
    sekali untuk autentikasi, langsung memanggil
    `GoogleSheetsManager.adminLogin(...)`. Kegagalan jaringan tidak dihitung
    sebagai percobaan salah (supaya limiter lokal tidak menghukum masalah
    koneksi).
  - `authenticatedAdminId: Long?` (Room id) diganti `authenticatedAdminNip:
    String?`, karena admin bisa saja tidak punya baris pegawai lokal sama
    sekali.
  - **`saveNewUser()` tidak lagi menerima `role`/`password`** — pegawai yang
    ditambahkan lewat menu Hak Akses selalu USER biasa tanpa kata sandi. Ini
    menutup jalur kedua yang juga bisa "membuat admin dari dalam app" (dialog
    Tambah Pegawai sebelumnya punya opsi role ADMIN + field password).
  - `resetUserPassword()` dihapus — tidak relevan lagi karena tidak ada admin
    lokal yang passwordnya perlu direset dari app.
  - `toggleUserActiveState()` kini membandingkan NIP (bukan Room id) untuk
    mencegah admin menonaktifkan akun yang sedang ia pakai.
- **`AdminLoginScreen.kt`** — state `needsAdminSetup` dan teks/tombol "BUAT
  ADMIN PERTAMA" dihapus total. Hanya ada satu jalur: "MASUK SEBAGAI ADMIN",
  selalu ke server.
- **`AdminUserManagementScreen.kt`** — dialog "Tambah Pegawai" tidak lagi
  punya pilihan role ADMIN atau field password; tombol "Atur kata sandi"
  (reset password admin lokal) dihapus. Badge "ADMIN" pada baris pegawai lama
  (dari database sebelum update ini) tetap tampil sebagai label saja, tidak
  lagi memberi hak apa pun.
- **`AttendanceDao.kt`** — query `getConfiguredAdminCount()` dihapus (itulah
  query yang dipakai untuk bootstrap admin per perangkat; sudah tidak dipakai
  di mana pun).
- `docs/AUDIT.md` dan `docs/SETUP.md` diperbarui supaya konsisten dengan alur
  baru (lihat bagian "Admin (server-verified, multi-admin)" di SETUP.md untuk
  langkah IT menambah/menghapus admin).

## Yang TIDAK diubah

- Alur sinkronisasi absensi (`doPost` dengan `token`, `doGet` rekap gabungan)
  — hanya ditambah satu cabang `if (action === 'adminLogin')` di paling atas
  `doPost`, sebelum logika lama; sudah dites tidak terganggu.
- `PasswordHasher.kt` (PBKDF2 lokal) dibiarkan ada di project meski sudah
  tidak dipakai — tidak dihapus karena di luar cakupan bug ini, tapi aman
  untuk dihapus kalau memang tidak akan dipakai lagi.

## Cara pakai (untuk IT)

1. Deploy ulang `backend/Code.gs` ke proyek Apps Script yang sudah ada
   (Script Properties `SPREADSHEET_ID`/`PHOTO_FOLDER_ID`/`SYNC_TOKEN` yang
   lama tidak perlu diubah).
2. Di editor Apps Script, jalankan `setupAdminAccount()` sekali per admin
   (edit NIP/nama/password di kode dulu, baru Run). Ulangi untuk setiap
   admin tambahan.
3. Build ulang APK dari source ini dan sebarkan. Perangkat lama yang masih
   pakai APK versi bug akan terus tertutup begitu di-update, karena tombol
   "Buat Admin Pertama" sudah tidak ada di kodenya.
4. Login admin sekarang butuh internet (sudah wajar karena fitur admin lain
   juga butuh internet).

## Verifikasi yang sudah dilakukan

- `node --test backend/Code.test.cjs` → 16/16 tes lulus (9 tes lama tidak
  berubah perilakunya + 7 tes baru untuk `adminLogin`).
- Tinjauan manual seluruh pemakaian `role`, `pinCode`, `authenticatedAdminId`,
  `getConfiguredAdminCount`, `needsAdminSetup`, `resetUserPassword` di kode
  Kotlin untuk memastikan tidak ada referensi menggantung setelah perubahan.
- Build Gradle/Android **belum** dijalankan di sini (lingkungan kerja ini
  tidak punya Android SDK — proyek ini memang di-build lewat GitHub Actions).
  Jalankan `gradle testDebugUnitTest lintDebug assembleDebug` di CI sebelum
  merge untuk memastikan kompilasi Kotlin bersih.

---

# BUG 2 — Manipulasi jam/tanggal perangkat menembus jendela absensi

## Ringkasan bug

`AttendanceViewModel.submitAttendance()` mengevaluasi jendela MASUK/PULANG
memakai `System.currentTimeMillis()` — jam sistem Android yang bisa diubah
manual oleh siapa pun lewat **Setelan > Tanggal & waktu** (matikan
"Tanggal & waktu otomatis"). Karena keputusan boleh/tidaknya submit,
`jamMasuk`/`jamPulang` yang tercatat, `tanggal`, dan `timestamp` yang dikirim
ke server SEMUANYA diturunkan dari jam ini, pegawai bisa memundurkan jam HP
ke, misalnya, 08:00 padahal aslinya sudah lewat jam 15:00, lalu absen MASUK
seolah tepat waktu — dan catatan yang tersimpan pun ikut memuat jam palsu itu.

## Solusi yang diterapkan

**Prinsip:** jangan pernah percaya jam sistem perangkat untuk keputusan yang
berpengaruh ke data. Ganti dengan waktu yang dijangkarkan ke server, memakai
mekanisme yang tidak ikut berubah walau pengguna mengubah tanggal/jam di
Setelan.

### 1. Android — `util/TrustedTime.kt` (baru)

- Memakai `SystemClock.elapsedRealtime()` — penghitung milidetik sejak boot
  perangkat, TIDAK terpengaruh saat pengguna mengubah jam sistem (beda
  dengan `System.currentTimeMillis()`) — dijangkarkan ke sebuah `serverTime`
  epoch yang diambil dari server.
- `recordAnchor(serverTimeMs)`: simpan pasangan (jam server saat itu,
  `elapsedRealtime()` saat itu) ke SharedPreferences.
- `nowOrNull()`: hitung "sekarang" = jangkar + selisih `elapsedRealtime()`
  sejak jangkar disimpan. Mengembalikan `null` (bukan jam perangkat sebagai
  fallback diam-diam) kalau: belum pernah ada jangkar, PERANGKAT SUDAH
  DI-REBOOT sejak jangkar terakhir (`elapsedRealtime()` sekarang lebih kecil
  dari saat jangkar disimpan — tanda reboot), atau jangkar sudah basi
  (>12 jam sejak pembaruan terakhir).
- `ensureFreshOrNull()`: kalau tidak ada jangkar valid, coba hubungi server
  dulu (`GoogleSheetsManager.fetchServerTime()`) sebelum menyerah.
- **Konsekuensi yang disengaja:** kalau tidak ada jangkar valid DAN tidak ada
  internet, tidak ada waktu tepercaya yang bisa dipakai. Pemanggil (lihat di
  bawah) WAJIB menolak submit pada kondisi ini, bukan diam-diam kembali ke
  `System.currentTimeMillis()` — itu justru akan membuka lagi celah yang
  sedang ditutup.

### 2. Android — `GoogleSheetsManager.kt`

- `fetchServerTime()` baru: `GET ?action=time` ke Apps Script (tanpa token —
  jam bukan data rahasia), lalu simpan hasilnya lewat `TrustedTime.recordAnchor`.
- `captureServerTime(body)`: dipanggil setelah SETIAP respons dari server
  (sync absensi, ambil rekap, login admin) — karena `reply()` di server kini
  selalu menyisipkan `serverTime`, jangkar ini otomatis ter-refresh gratis
  dari lalu lintas yang memang sudah terjadi, tidak perlu ping tambahan
  kecuali belum ada jangkar sama sekali.

### 3. Android — `AttendanceViewModel.kt`

- `init{}`: panggil `TrustedTime.init(application)` sekali (menyimpan
  Application Context saja, bukan Activity — tidak bisa bocor).
- `submitAttendance()`: baris `val now = System.currentTimeMillis()` diganti
  `val now = TrustedTime.ensureFreshOrNull() ?: throw IllegalStateException(...)`.
  Semua kode di bawahnya (`AttendancePolicy.window(now)`, `jamMasuk`/`jamPulang`,
  `dateFormatted`, `dayBounds` untuk cek duplikat harian, dan
  `encryptedHash`/`timestamp` yang dikirim ke server) **tidak perlu diubah**
  karena semuanya sudah menerima `now` sebagai parameter — begitu sumbernya
  benar, seluruh hilirnya otomatis ikut benar.
- Mode **FORCE_OPEN**/**FORCE_LOCKED** admin (`AdminSettingsScreen`) tetap
  berfungsi seperti biasa — ini fitur admin yang disengaja untuk membuka
  jadwal manual, bukan bagian dari bug yang dilaporkan.

### 4. Server — `backend/Code.gs`

- `reply()` kini menyisipkan `serverTime: Date.now()` ke SETIAP respons
  (sukses maupun gagal) — ini yang jadi sumber jangkar di atas. Jam bukan
  data sensitif, jadi aman disertakan tanpa autentikasi apa pun.
- `doGet` dapat cabang baru `?action=time` (tanpa token) khusus untuk
  mengambil jam server ini.
- `doPost` menambahkan sanity bound longgar pada `data.timestamp`: tolak
  kalau lebih dari 30 hari ke masa lalu atau lebih dari 5 menit ke masa
  depan (dibanding jam server saat sinkron diterima). Ini jaring pengaman
  kedua untuk data yang jelas rusak/dipalsukan mentah-mentah, BUKAN
  penegakan ulang jendela 07-09/12-14 — sengaja tidak menegakkan jam-hari di
  server supaya mode FORCE_OPEN admin (yang memang absen di luar jam normal)
  tidak ikut ditolak. Batas 30 hari juga sengaja longgar supaya sinkron yang
  tertunda lama (device offline berhari-hari lalu online lagi) tetap
  diterima dengan jam asli saat absen dilakukan.

## Kenapa tidak menegakkan jendela jam di server sekalian?

Sempat dipertimbangkan supaya server independen menghitung ulang jam WITA
dari `timestamp` dan menolak kalau di luar jendela — ini akan lebih kuat,
TAPI akan merusak fitur **FORCE_OPEN** yang sudah ada (admin sengaja membuka
jadwal di luar jam normal lewat Pengaturan; server tidak (belum) punya cara
membedakan "FORCE_OPEN sah dari admin" vs "klien berbohong soal mode-nya").
Menutup ini dengan benar butuh mengirim status FORCE_OPEN yang terverifikasi
ke server (perubahan arsitektur lebih besar, di luar cakupan laporan bug
ini). Keputusan yang diambil: perbaiki akar masalah yang dilaporkan (jam
perangkat gampang diubah lewat Setelan) secara tuntas di sisi klien, dan
biarkan sanity bound di server sebagai jaring pengaman longgar saja.

## Keterbatasan yang tersisa (jujur diungkap)

- **Butuh koneksi internet minimal sekali sejak reboot** sebelum submit
  pertama bisa dilakukan (untuk mendapat jangkar). Ini konsekuensi yang
  disengaja, bukan bug — kalau tidak, tidak ada cara membedakan "device
  belum sempat online" dari "device sengaja diputus dari internet supaya
  bisa memakai jam palsu".
- `AttendanceTimeBadge` (badge status jendela di layar utama pegawai) dan
  `ReminderReceiver` (penjadwalan notifikasi pengingat lokal) masih memakai
  `System.currentTimeMillis()` — tapi keduanya HANYA tampilan/pengingat, tidak
  menggerbang submit yang sesungguhnya. Kalau jam perangkat diubah, badge itu
  bisa menampilkan status yang tidak sinkron dengan kenyataan, tapi submit
  yang sebenarnya tetap dievaluasi lewat `TrustedTime` dan akan ditolak kalau
  memang di luar jendela nyata. Ini didokumentasikan sebagai batasan kosmetik
  di `docs/AUDIT.md`, bisa jadi perbaikan lanjutan kalau diinginkan.
- Sanity bound di server (30 hari / 5 menit) tidak bisa mendeteksi
  `timestamp` yang dipalsukan secara halus oleh APK yang dimodifikasi untuk
  melewati `TrustedTime` sepenuhnya dan mengirim langsung ke webhook dengan
  jam yang "masuk akal". Ini sudah disebut sebagai batas skenario ancaman di
  `docs/AUDIT.md` bagian SYNC_TOKEN — sama seperti sebelumnya, token itu
  rahasia bersama perangkat, bukan autentikasi pegawai penuh.

## Verifikasi yang sudah dilakukan

- `node --test backend/Code.test.cjs` → 21/21 tes lulus, termasuk 5 tes baru:
  endpoint `?action=time`, timestamp jauh di masa lalu ditolak, timestamp
  jauh di masa depan ditolak, sinkron yang tertunda berjam-jam tetap
  diterima, dan submission di luar jam MASUK/PULANG tetap diterima (bukti
  FORCE_OPEN tidak rusak).
- Tinjauan manual seluruh pemanggil `AttendancePolicy.window(...)` untuk
  memastikan jalur submit yang sesungguhnya (di `AttendanceViewModel`) sudah
  memakai `now` yang tepercaya, dan mendata dua pemanggil kosmetik yang belum
  (`AttendanceTimeBadge`, `ReminderReceiver`) sebagai batasan yang diketahui.
- Build Gradle/Android **belum** dijalankan di sini (tidak ada Android SDK di
  lingkungan kerja ini). Jalankan `gradle testDebugUnitTest lintDebug
  assembleDebug` di CI sebelum merge.
