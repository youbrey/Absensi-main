# Menjalankan Absensi

## Android

Gunakan JDK 17, Android SDK platform 36.1, Build Tools 36.0.0, dan Gradle 9.3.1.
Jalankan `gradle testDebugUnitTest lintDebug assembleDebug` dari root proyek.
Debug memakai keystore standar Android, sehingga tidak perlu membuat `debug.keystore` di repositori.
Release tetap membutuhkan keystore milik pengelola.

1. Buka menu admin pada perangkat yang dikelola instansi. Jika belum ada admin dengan kata sandi baru, isikan NIP dan kata sandi minimal 8 karakter untuk penyiapan admin pertama.
2. Daftarkan pegawai dan kata sandi masing-masing melalui Hak Akses. Untuk akun dari versi lama, gunakan **Atur kata sandi**; PIN bersama `123456` tidak lagi diterima.
3. Pegawai memilih profil/NIP, memasukkan kata sandi, melampirkan foto dokumentasi, mengizinkan lokasi presisi, lalu mengirim absensi sesuai jenis dan jadwal. Foto tidak dianalisis atau diverifikasi sebagai wajah.
4. Jadwal dan tanggal menggunakan Asia/Makassar (WITA), terlepas zona waktu ponsel. Batas masuk 07:00:00–09:00:00, pulang 12:00:00–14:00:00. Pembukaan manual berlaku untuk kedua jenis; kunci manual menolak semuanya. Radius bawaan 35 km dari titik yang telah ada di repositori; pengelola perlu memastikan titik dan radius sesuai kebijakan instansi.
5. Riwayat dibuka dengan login pegawai dan dapat ditutup melalui Keluar dari riwayat. Data di perangkat lain tidak otomatis muncul: arsitektur masih database lokal dengan unggah ke Sheets, bukan server akun terpusat.

Penyiapan pertama merupakan kepercayaan terhadap pengelola perangkat. Lakukan sebelum perangkat diserahkan kepada pegawai. Aplikasi ini belum menyediakan pengelolaan akun terpusat atau perlindungan terhadap pemilik perangkat yang memodifikasi APK/database/waktu sistem.

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

Rekaman memakai SHA-256 sebagai ID untuk menghindari duplikasi saat retry. Foto JPEG dikirim sungguhan, disimpan di Drive, dan tautannya masuk Sheets. Kegagalan koneksi/otorisasi/format respons tetap berstatus lokal. Tidak ada URL demo yang berpura-pura berhasil. Pengiriman otomatis dilakukan saat submit; tombol Sync mengulang antrean lokal. Tidak ada sinkronisasi background berkala saat aplikasi ditutup.

## Migrasi dan keterbatasan data lama

Migrasi Room 2 → 3 mempertahankan semua baris. Flag verifikasi wajah dan confidence lama direset karena sebelumnya nilainya dibuat-buat. Status sinkronisasi lama dikembalikan menjadi pending karena versi lama mengaku berhasil saat permintaan gagal. Kolom wajah tetap berada di schema sebagai kompatibilitas data lama; aplikasi tidak menggunakannya.

Migrasi ini tidak bisa membuktikan GPS lama, memulihkan foto yang tidak pernah disimpan, atau membatalkan baris yang sudah diterima endpoint lama. Periksa rekaman lama sebelum retry; endpoint baru mencegah duplikasi berdasarkan RECORD ID, tetapi endpoint lama mungkin belum mempunyai kolom tersebut. Database versi selain 2/3 tidak dihapus otomatis: butuh migrasi khusus setelah schema lamanya diperiksa.

## Notifikasi dan ekspor

Aktifkan notifikasi dari Pengaturan dan izinkan notifikasi Android 13+. Pengingat dijadwalkan sekitar 07:15 dan 12:15 WITA, dipulihkan setelah reboot/update. Alarm tidak eksak dan dapat ditunda optimasi baterai; reminder terlambat di luar jadwal tidak dikirim. Force-stop memerlukan membuka aplikasi kembali.

Filter bulan berlaku pada monitoring, statistik, dan ekspor. PDF memuat tanggal serta seluruh baris lintas halaman. CSV melakukan escaping dan mempertahankan NIP panjang sebagai teks. Gunakan menu bagikan/simpan Android untuk memilih lokasi file; aplikasi tidak mengaku menyalin ke Downloads jika gagal. CSV bukan workbook XLSX.

Dashboard Admin punya dua pasang tombol ekspor yang sumber datanya berbeda:
- **Ekspor PDF / Ekspor Excel** — hanya membaca database lokal HP yang dipakai; hanya berisi data yang pernah masuk/tersinkron di device itu.
- **Rekap Gabungan PDF / Rekap Gabungan Excel** — menarik seluruh baris dari Google Sheets instansi (lintas semua device/pegawai) lewat endpoint `doGet`, lalu difilter per bulan yang sama seperti tampilan monitoring. Butuh koneksi internet dan webhook/token yang valid; gagal ambil data akan menampilkan pesan error, bukan file kosong yang berpura-pura berhasil.

Database lokal tidak dienkripsi secara khusus. HTTPS melindungi transmisi; password memakai PBKDF2 dengan salt. Backup dinonaktifkan untuk data pribadi; tidak ada klaim enkripsi end-to-end.
