# Absensi DPRD Bitung

Aplikasi Android Kotlin/Jetpack Compose untuk absensi WFH PNS dan PPPK. Foto selfie/galeri merupakan dokumentasi; aplikasi tidak melakukan verifikasi wajah.

Data disimpan dengan Room pada perangkat, kemudian dikirim ke Google Sheets melalui Google Apps Script yang dikonfigurasi pengelola. Sinkronisasi yang gagal tetap berstatus lokal.

- [Konfigurasi perangkat, admin, Sheets, dan build](docs/SETUP.md)
- [Laporan audit, perbaikan, migrasi, dan batas operasional](docs/AUDIT.md)
- [Kode penerima Google Apps Script](backend/Code.gs)

Build membutuhkan JDK 17, Gradle 9.3.1, Android SDK 36.1 dan Build Tools 36.0.0:

```sh
gradle testDebugUnitTest lintDebug assembleDebug
node --test backend/Code.test.cjs
```

GitHub Actions menjalankan pengujian, lint, dan build APK untuk perubahan kode.
