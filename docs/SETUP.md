# Menjalankan Absensi

## Android

Gunakan JDK 17, Android SDK platform 36.1, Build Tools 36.0.0, dan Gradle 9.3.1.
Jalankan `gradle testDebugUnitTest lintDebug assembleDebug` dari root proyek.
Debug memakai keystore standar Android, sehingga tidak perlu membuat `debug.keystore` di repositori.
Release tetap membutuhkan keystore milik pengelola.

1. Login admin diverifikasi oleh server (Apps Script), bukan perangkat. Pastikan bagian "Google Sheets dan foto" di bawah (termasuk `ADMIN_ACCOUNTS`) sudah dikonfigurasi lebih dulu, lalu buka menu admin dan masuk dengan NIP + kata sandi yang sudah didaftarkan IT. Tidak ada lagi opsi "Buat Admin Pertama" -- perangkat baru tidak bisa membuat admin sendiri.
2. Daftarkan pegawai melalui Hak Akses (selalu sebagai pegawai biasa/USER, tanpa kata sandi -- absensi tidak memerlukan login). Menambah/mengubah akun ADMIN hanya bisa dilakukan lewat editor Apps Script, bukan dari aplikasi.
3. Pegawai memilih profil/NIP, memasukkan kata sandi, melampirkan foto dokumentasi, mengizinkan lokasi presisi, lalu mengirim absensi sesuai jenis dan jadwal. Foto tidak dianalisis atau diverifikasi sebagai wajah.
4. Jadwal dan tanggal menggunakan Asia/Makassar (WITA), terlepas zona waktu ponsel. Batas masuk 07:00:00–09:00:00, pulang 12:00:00–14:00:00. Pembukaan manual berlaku untuk kedua jenis; kunci manual menolak semuanya. Radius bawaan 35 km dari titik yang telah ada di repositori; pengelola perlu memastikan titik dan radius sesuai kebijakan instansi.
5. Riwayat dibuka dengan login pegawai dan dapat ditutup melalui Keluar dari riwayat. Data di perangkat lain tidak otomatis muncul: arsitektur masih database lokal dengan unggah ke Sheets, bukan server akun terpusat.

Penyiapan pertama merupakan kepercayaan terhadap pengelola perangkat untuk hal-hal di luar akun admin (mis. token sinkronisasi bawaan build). Login admin sendiri tidak lagi bergantung pada perangkat mana pun -- lihat "Admin (server-verified, multi-admin)" di bawah.

## Google Sheets dan foto

1. Buat spreadsheet dan folder Drive privat milik instansi. Jangan gunakan folder dengan akses publik untuk foto pegawai.
2. Buat proyek Google Apps Script, salin `backend/Code.gs`.
3. Isi Script Properties: `SPREADSHEET_ID`, `PHOTO_FOLDER_ID`, dan `SYNC_TOKEN` (token acak panjang, misalnya 32 byte acak). Jangan masukkan token ke Git.
4. Deploy sebagai Web App yang dijalankan sebagai pemilik. Agar klien Android dapat POST tanpa login browser Google, endpoint harus dapat diakses klien; kode memeriksa `SYNC_TOKEN` pada setiap permintaan. Token adalah rahasia bersama perangkat, bukan autentikasi pegawai server.
5. Simpan URL deployment `https://script.google.com/macros/s/<deployment-id>/exec` dan token yang sama, dengan salah satu dari dua cara:
   - **Manual per device** (Admin > Pengaturan > Sinkronisasi Google Sheets API), disimpan di preferensi privat aplikasi; jangan bagikan APK/perangkat yang telah dikonfigurasi kepada pihak tidak berwenang.
   - **Dibakukan ke dalam build (direkomendasikan agar pegawai/IT tidak perlu setting manual per device)**: set environment variable `ABSENSI_WEBHOOK_URL` dan `ABSENSI_SYNC_TOKEN` sebelum menjalankan Gradle (`export ABSENSI_WEBHOOK_URL=... ABSENSI_SYNC_TOKEN=... && gradle assembleDebug`), atau sebagai secrets di GitHub Actions lalu diteruskan sebagai env var pada step build. Nilainya masuk ke `BuildConfig.SYNC_WEBHOOK_URL` / `BuildConfig.SYNC_TOKEN_DEFAULT` saat kompilasi, jadi setiap APK dari build itu sudah terkonfigurasi — Settings di app hanya perlu diisi manual untuk override darurat (misalnya rotasi token tanpa rebuild). Jangan commit nilai asli ke Git; env var tidak pernah masuk ke source code.
6. Uji satu data nonproduksi, periksa baris spreadsheet dan foto Drive, lalu coba sinkronisasi ulang. Server mengakui hanya sesudah penyimpanan berhasil, menggunakan `{ "success": true, "recordId": "<hash>" }`.
7. Endpoint yang sama juga melayani `GET ?token=<SYNC_TOKEN>` (dipakai tombol "Rekap Gabungan" di Dashboard Admin) yang mengembalikan seluruh baris `{ "success": true, "records": [...] }` tanpa pernah mengembalikan token itu sendiri. Ini read-only, tidak menulis apa pun; tidak perlu deployment ulang selama Web App sudah di-deploy dengan akses yang mencakup GET (deployment "Anyone" yang sama seperti untuk POST sudah cukup).
8. Endpoint juga melayani `GET ?action=time` tanpa token (hanya mengembalikan jam server saat ini) -- dipakai aplikasi untuk memverifikasi waktu asli (lihat "Waktu tepercaya (anti manipulasi jam perangkat)" di bawah), bukan untuk data pegawai.

Rekaman memakai SHA-256 sebagai ID untuk menghindari duplikasi saat retry. Foto JPEG dikirim sungguhan, disimpan di Drive, dan tautannya masuk Sheets. Kegagalan koneksi/otorisasi/format respons tetap berstatus lokal. Tidak ada URL demo yang berpura-pura berhasil. Pengiriman otomatis dilakukan saat submit; tombol Sync mengulang antrean lokal. Tidak ada sinkronisasi background berkala saat aplikasi ditutup.

## Admin (server-verified, multi-admin)

Kata sandi admin tidak pernah ada di dalam APK. Login admin (`action: "adminLogin"` pada endpoint yang sama) dicocokkan sepenuhnya di server terhadap Script Property `ADMIN_ACCOUNTS`, bukan database lokal perangkat mana pun. Tidak ada jalur di aplikasi Android yang bisa membuat, mengubah, atau menemukan kredensial admin, sehingga membongkar APK sekalipun tidak akan menemukan kata sandi apa pun.

1. Buka proyek Apps Script yang sama dengan langkah "Google Sheets dan foto" di atas (yang berisi `backend/Code.gs`).
2. Di editor, pilih fungsi `setupAdminAccount` pada dropdown fungsi, lalu sunting tiga nilai di baris atas fungsi tersebut: `nip`, `name`, `password` (minimal 8 karakter). Klik **Run**. Setelah berhasil (cek log di **Executions**), kosongkan/ganti nilai `password` di kode supaya tidak tertinggal di editor.
3. Ulangi langkah di atas (jalankan `setupAdminAccount` lagi dengan NIP+nama+password admin lain) untuk setiap admin tambahan -- ini yang mempertahankan multi-admin, sepenuhnya diatur di server. Menjalankannya lagi dengan NIP yang sama akan mengganti kata sandi admin tersebut (dipakai juga untuk reset password).
4. Untuk menghapus admin (mis. mutasi/keluar), sunting `nip` pada fungsi `removeAdminAccount` lalu Run. Untuk melihat daftar admin yang terdaftar (tanpa pernah menampilkan hash/kata sandi), jalankan `listAdminAccounts` dan lihat hasilnya di **Executions**.
5. Login admin membutuhkan koneksi internet (endpoint yang sama dengan sinkronisasi Sheets). Ini wajar karena hampir semua fitur admin (Rekap Gabungan, ubah jadwal untuk semua pegawai, dll.) sudah membutuhkan internet juga.
6. Percobaan login yang salah dibatasi di server: setelah 5 kali gagal untuk satu NIP, NIP itu dikunci sementara (~5 menit); ada juga batas global lintas-NIP untuk mencegah percobaan masif dari NIP yang ditebak-tebak. Tidak memerlukan konfigurasi tambahan.

Kolom `role`/`pinCode` pada tabel pegawai lokal (Room) tidak lagi dipakai untuk autentikasi admin; menu Hak Akses hanya menambah pegawai biasa (USER) tanpa kata sandi. Baris lama yang masih berlabel ADMIN dari versi sebelumnya hanya tampil sebagai badge, tidak lagi memberi akses apa pun.

## Waktu tepercaya (anti manipulasi jam perangkat)

Jendela absensi (MASUK 07:00-09:00, PULANG 12:00-14:00 WITA) tidak lagi dievaluasi memakai jam sistem perangkat (`System.currentTimeMillis()`), karena itu bisa diubah manual lewat Setelan > Tanggal & waktu. Sebagai gantinya, `TrustedTime` (`app/src/main/java/com/example/util/TrustedTime.kt`) menjangkarkan "sekarang" ke jam server (field `serverTime` yang disertakan di setiap respons `backend/Code.gs`) lewat `SystemClock.elapsedRealtime()` -- penghitung sejak boot yang TIDAK terpengaruh perubahan jam manual.

- Jangkar diperbarui otomatis setiap kali aplikasi berhasil menghubungi server (sinkron absensi, ambil rekap, login admin), dan lewat endpoint ringan `GET ?action=time` yang dipanggil sebelum submit kalau belum ada jangkar yang valid.
- Jangkar dianggap tidak valid lagi setelah perangkat di-restart (penghitung boot-nya reset) atau setelah 12 jam tanpa pembaruan -- pada titik itu aplikasi akan mencoba menghubungi server lagi sebelum mengizinkan submit.
- **Konsekuensi:** submit absensi butuh setidaknya satu koneksi internet yang berhasil sejak perangkat terakhir dinyalakan ulang. Kalau device benar-benar belum pernah online sejak boot, submit ditolak dengan pesan "Tidak dapat memverifikasi waktu perangkat" -- ini disengaja; ini yang menutup celah manipulasi jam, bukan bug.
- Sebagai lapis kedua di server, `doPost` menolak `timestamp` yang jelas tidak masuk akal (lebih dari 30 hari ke masa lalu atau lebih dari 5 menit ke masa depan dibanding jam server saat data disinkronkan) -- lihat komentar di atas `ATTENDANCE_TIMESTAMP_FUTURE_SLACK_MS` pada `backend/Code.gs`. Ini sengaja TIDAK menegakkan ulang jendela 07-09/12-14 di server, supaya mode admin **FORCE_OPEN** (Pengaturan > Jadwal) tetap berfungsi untuk submission yang sah di luar jam normal.
- Yang masih memakai jam perangkat murni untuk tampilan saja (bukan untuk memutuskan boleh/tidaknya submit): badge status jendela di layar utama pegawai (`AttendanceTimeBadge`) dan penjadwalan notifikasi pengingat lokal (`ReminderReceiver`). Kalau jam perangkat diubah, keduanya bisa menampilkan info yang tidak sinkron dengan kenyataan, tapi submit yang sesungguhnya tetap dievaluasi lewat `TrustedTime` dan akan ditolak jika memang di luar jendela nyata.

## Migrasi dan keterbatasan data lama

Migrasi Room 2 → 3 mempertahankan semua baris. Flag verifikasi wajah dan confidence lama direset karena sebelumnya nilainya dibuat-buat. Status sinkronisasi lama dikembalikan menjadi pending karena versi lama mengaku berhasil saat permintaan gagal. Kolom wajah tetap berada di schema sebagai kompatibilitas data lama; aplikasi tidak menggunakannya.

Migrasi ini tidak bisa membuktikan GPS lama, memulihkan foto yang tidak pernah disimpan, atau membatalkan baris yang sudah diterima endpoint lama. Periksa rekaman lama sebelum retry; endpoint baru mencegah duplikasi berdasarkan RECORD ID, tetapi endpoint lama mungkin belum mempunyai kolom tersebut. Database versi selain 2/3 tidak dihapus otomatis: butuh migrasi khusus setelah schema lamanya diperiksa.

## Notifikasi dan ekspor

Aktifkan notifikasi dari Pengaturan dan izinkan notifikasi Android 13+. Pengingat dijadwalkan sekitar 07:15 dan 12:15 WITA, dipulihkan setelah reboot/update. Alarm tidak eksak dan dapat ditunda optimasi baterai; reminder terlambat di luar jadwal tidak dikirim. Force-stop memerlukan membuka aplikasi kembali.

Filter bulan berlaku pada monitoring, statistik, dan ekspor. PDF memuat tanggal serta seluruh baris lintas halaman. CSV melakukan escaping dan mempertahankan NIP panjang sebagai teks. Gunakan menu bagikan/simpan Android untuk memilih lokasi file; aplikasi tidak mengaku menyalin ke Downloads jika gagal. CSV bukan workbook XLSX.

Dashboard Admin punya dua pasang tombol ekspor yang sumber datanya berbeda:
- **Ekspor PDF / Ekspor Excel** — hanya membaca database lokal HP yang dipakai; hanya berisi data yang pernah masuk/tersinkron di device itu.
- **Rekap Gabungan PDF / Rekap Gabungan Excel** — menarik seluruh baris dari Google Sheets instansi (lintas semua device/pegawai) lewat endpoint `doGet`, lalu difilter per bulan yang sama seperti tampilan monitoring. Butuh koneksi internet dan webhook/token yang valid; gagal ambil data akan menampilkan pesan error, bukan file kosong yang berpura-pura berhasil.

Database lokal tidak dienkripsi secara khusus. HTTPS melindungi transmisi. Pegawai (USER) tidak punya kata sandi sama sekali; kata sandi admin tidak pernah disimpan di perangkat -- hanya di server sebagai hash SHA-256 bergaram-berulang (lihat "Admin (server-verified, multi-admin)" di atas). Backup dinonaktifkan untuk data pribadi; tidak ada klaim enkripsi end-to-end.
