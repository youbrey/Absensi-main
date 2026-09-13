# Audit dan refactor Absensi

Basis audit: commit `c49e19fe9f4f86d0040ed12806c42edb8636979a` pada `youbrey/Absensi`.
Cakupan: seluruh sumber Kotlin, navigasi/form/admin, DAO/entity/database, utility, resource keamanan, konfigurasi Gradle, tes, dan workflow CI.

## Temuan dan tindakan

| Area | Temuan awal | Perbaikan |
|---|---|---|
| Google Sheets | Semua respons HTTP dan exception mengembalikan `true` | Hanya HTTP sukses dengan JSON acknowledgement dan ID cocok yang menandai synced; gagal tetap pending |
| Webhook | URL contoh tidak nyata; URL tidak persisten | URL kosong sampai dikonfigurasi; validasi URL HTTPS deployment, token dan SharedPreferences |
| Foto Sheets | Payload berisi teks pengganti foto | Kirim JPEG base64 asli; receiver menyimpan foto Drive privat dan URL foto pada sheet |
| Penerima server | Tidak disertakan | `backend/Code.gs` dengan pemeriksaan token, validasi, locking, deduplikasi ID, dan acknowledgement setelah write |
| GPS | Null/error diganti lokasi kantor dan label terverifikasi | Izin lokasi presisi, timeout, cek freshness/accuracy/mock, error nyata; tanpa koordinat cadangan palsu |
| Foto/wajah | Stub selalu mengklaim match 98,5% dan liveness | Modul verifikasi dihapus sesuai arahan pengguna. Foto semata dokumentasi; tanpa analisis wajah |
| Pengambilan foto | Kamera hanya thumbnail; decode galeri tanpa batas; reset tidak dipakai | TakePicture full-resolution, FileProvider, decode terbatasi, rotasi EXIF, penanganan error, tombol hapus |
| Submit | Bisa kirim tanpa foto, akun aktif, atau GPS; jenis tidak sesuai jadwal; duplikat | Validasi nyata, data profil kanonis, snapshot input, guard submit, transaksi cek rekaman harian |
| Akun admin | `admin/admin`, `admin/admin123`, kecocokan nama parsial, PIN bersama, lalu (temuan lanjutan) perangkat mana pun bisa membuat dirinya admin lewat "Buat Admin Pertama" karena cek dilakukan pada Room DB lokal per perangkat | Verifikasi admin dipindah sepenuhnya ke server (`action: "adminLogin"` di `backend/Code.gs`, dicocokkan ke `ADMIN_ACCOUNTS` pada Script Properties). Tidak ada jalur di kode Android yang membuat/mengubah admin; multi-admin diatur oleh IT lewat `setupAdminAccount()`/`removeAdminAccount()` di editor Apps Script. Dibatasi percobaan per-NIP dan global via `CacheService`. |
| Hak akses | Mutasi hanya bergantung pada layar admin | Metode mutasi memeriksa sesi admin; akun admin yang sedang dipakai tidak bisa dinonaktifkan |
| Riwayat | Tanpa profil menampilkan seluruh pegawai | Login pegawai sebelum melihat riwayat pribadi; tombol keluar sesi |
| Pegawai | NIP duplikat, PIN universal, error insert tidak ditangani | Cek duplikat dalam transaksi, password individual, validasi input dan pesan error |
| Jadwal | Zona perangkat dipakai tetapi diberi label WITA; badge membeku; lalu (temuan lanjutan) jendela absensi dievaluasi memakai jam sistem perangkat, yang bisa diubah manual di Setelan sehingga jenis absensi apa pun bisa "dipaksa" masuk jendela | Aturan waktu terpisah di AttendancePolicy (Asia/Makassar untuk jam/tanggal/bulan; badge diperbarui). Untuk keputusan submit (bukan sekadar tampilan), `AttendanceViewModel` sekarang memakai `TrustedTime` -- waktu dijangkarkan ke jam server (`backend/Code.gs`) lewat `SystemClock.elapsedRealtime()` yang tidak terpengaruh perubahan tanggal/jam manual; submit ditolak jika belum ada jangkar tepercaya (mis. belum pernah online sejak boot). Server juga menolak `timestamp` yang jelas tidak masuk akal (>30 hari lalu atau >5 menit ke depan) sebagai lapis pertahanan kedua, tanpa menegakkan ulang jendela 07-09/12-14 di server itu sendiri (supaya tidak merusak mode admin FORCE_OPEN yang memang sengaja membuka jadwal di luar jam itu). |
| Filter bulan | Default Agustus 2026, ekspor tidak difilter | Default bulan sekarang, pemilih periode nyata, filter konsisten pada monitoring/statistik/ekspor |
| Memori foto | Semua foto dimuat pada daftar Compose dan antrean sekaligus | Projection ringkas untuk daftar/laporan, ambil foto satu per satu saat sync, ukuran foto dokumentasi dibatasi |
| Antrean sync | Mengandalkan cache StateFlow yang bisa kosong | Query pending langsung DAO, serialisasi sync, laporan jumlah sukses/gagal |
| Notifikasi | Helper hanya tes manual; switch tidak persisten; tanpa scheduler | Jadwal alarm harian WITA, penerima reboot/update, izin notifikasi, preferensi persisten |
| PDF | Menghentikan iterasi setelah satu halaman; teks dipotong; tanpa tanggal baris | Pagination semua baris, wrapping teks, tanggal per baris, ekspor di IO dispatcher |
| CSV | Kutip tidak di-escape; formula injection dan NIP dibulatkan Excel | Escaping CSV, BOM UTF-8, perlindungan formula, NIP panjang sebagai teks |
| Penyimpanan ekspor | Gagal salin Downloads ditelan | File privat + Android chooser untuk simpan/bagikan; tidak mengklaim salinan publik berhasil |
| Enkripsi | Klaim AES end-to-end tidak dipakai; key/IV hardcoded dan fallback plaintext | Hapus utility AES tidak terpakai dan klaim palsu; jelaskan HTTPS, hash password, dan database lokal |
| Backup/FileProvider | Backup default dan akses path terlalu luas | Exclude data/token/foto dari backup/transfer, FileProvider terbatas folder laporan/kamera |
| Database | Fallback destructive migration | Migrasi 2→3 mempertahankan baris dan mencabut status keberhasilan semu |
| Build/tes | Keystore debug wajib file yang tidak ada; tes Greeting tidak dapat dikompilasi; versi Gradle tidak ditetapkan | Keystore debug standar; dependency/plugin tidak dipakai dibuang; tes perilaku dan CI Gradle 9.3.1, test/lint/APK |

Placeholder pada `OutlinedTextField` adalah hint input yang sah. Callback bawaan navigasi tidak dipakai sebagai fitur palsu karena MainActivity memasok callback nyata.

## Batas operasional yang tetap perlu diperhatikan

- Google Apps Script belum di-deploy ke akun pengguna. Sinkronisasi end-to-end perlu URL/token, spreadsheet, dan folder Drive nyata. Kontrak receiver diuji dengan layanan penyimpanan tiruan; tidak ada data pegawai dikirim selama audit.
- Foto adalah dokumentasi. Tidak ada verifikasi identitas wajah/liveness, sesuai permintaan.
- Arsitektur jadwal dan riwayat masih per perangkat (database lokal). Google Sheets menerima data absensi dari semua perangkat, tetapi bukan server konfigurasi bersama untuk jadwal/riwayat. Akun admin sudah terpusat di server (lihat di bawah); bagian lain sistem ini belum bisa dianggap absensi terpusat penuh dengan proteksi terhadap pemilik perangkat yang memodifikasi aplikasi atau waktu.
- Login admin kini diverifikasi sepenuhnya di server (Script Properties `ADMIN_ACCOUNTS`), bukan Room DB lokal. Tidak ada tombol/alur "Buat Admin Pertama" atau opsi membuat akun ADMIN dari menu Hak Akses -- admin ditambah/diubah/dihapus hanya oleh IT lewat editor Apps Script (`setupAdminAccount()`, `removeAdminAccount()`, `listAdminAccounts()` di `backend/Code.gs`). Login admin membutuhkan koneksi internet.
- Keputusan jendela absensi (MASUK/PULANG) memakai `TrustedTime`, bukan jam sistem perangkat -- lihat `app/src/main/java/com/example/util/TrustedTime.kt`. Ini butuh minimal satu kali koneksi internet berhasil sejak perangkat terakhir dinyalakan ulang sebelum submit pertama diizinkan; tanpa itu, submit ditolak dengan pesan yang jelas (bukan diam-diam memakai jam perangkat). Batasan yang tetap ada: badge status jendela di layar utama (`AttendanceTimeBadge`) dan jadwal pengingat notifikasi lokal (`ReminderReceiver`) masih memakai jam perangkat untuk TAMPILAN saja -- tidak menggerbang submit yang sesungguhnya, tapi bisa tampil tidak sinkron kalau jam perangkat sengaja diubah. Validasi server terhadap `timestamp` (lihat baris "Jadwal" di atas) adalah sanity bound longgar, bukan penegakan jendela per jam-hari, agar mode FORCE_OPEN admin tetap berfungsi.
- Data legacy yang memiliki GPS/status wajah/sync palsu tidak dapat dibuktikan ulang dari kode. Migrasi hanya mencabut flag palsu; baris tetap dipertahankan. Periksa rekaman lama sebelum sinkron ulang.
- APK dengan kunci debug baru tidak dapat dipasang sebagai update atas APK dengan kunci penandatanganan lain. Untuk update produksi yang mempertahankan data, gunakan signing key yang sama dengan instalasi lama.
- Izin kamera/GPS, pengingat saat ponsel idle/reboot, chooser file, dan integrasi Sheets perlu uji penerimaan pada perangkat Android nyata.

Petunjuk konfigurasi lengkap: [SETUP.md](SETUP.md).
