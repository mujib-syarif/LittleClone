# Little Clone

Aplikasi Android untuk **menggandakan aplikasi** (mis. Clash of Clans) sehingga ada dua atau lebih salinan di ponsel, masing-masing dengan data dan akun sendiri. Satu fokus: cloning.
Dioptimalkan untuk **Infinix Hot 11s NFC** (Helio G88, Android 11, layar 90 Hz, penyimpanan terbatas).

Tema: iOS · monokrom · glassmorphism · data-driven / modular.

## Cara kerja

Little Clone tidak memakai virtualisasi. Ia membuat **APK baru** dari aplikasi terpasang, lalu memasangnya:

1. Membaca APK dasar + split APK aplikasi sumber.
2. `engine` menulis ulang `AndroidManifest.xml` dan `resources.arsc` (nama paket baru `…​.lcN`, authority provider & izin kustom dibuat unik, label baru), membuang tanda tangan lama dan library ABI yang tidak dipakai ponsel.
3. Semua APK ditandatangani dengan satu kunci milik Little Clone (apksig, skema v2).
4. Dipasang lewat `PackageInstaller`; Android meminta konfirmasi Anda.

Klon muncul sebagai aplikasi biasa di layar utama, dengan data terpisah.

## Struktur modular

| Modul / paket | Isi |
|---|---|
| `engine/` | Java murni, tanpa Android. Penulis ulang APK/manifest/ARSC + pembuat sertifikat. Diuji dengan JUnit. |
| `app/.../clone` | Pipeline klon berbasis `Stage` (Prepare → Patch → Sign → Install), installer, penyimpanan kunci. |
| `app/.../data` | Pemindai aplikasi, penyimpanan daftar klon (JSON). |
| `app/.../ui` | `HomeState` → `buildModules()` → daftar `HomeModule` yang digambar Compose (data-driven). Komponen glass & token monokrom di `ui/components`, `ui/theme`. |

## Build

Sandbox pengembangan tidak punya akses Maven, jadi build dilakukan di **GitHub Actions**: buka tab *Actions* → workflow *Build APK* → unduh artifact `LittleClone-release` (APK sudah ditandatangani kunci debug, bisa langsung dipasang).
Lokal: `./gradlew :app:assembleRelease` (JDK 17).

## Batasan yang perlu diketahui

- **Bukan virtualisasi.** Aplikasi yang memeriksa nama paket/tanda tangan sendiri, memakai Play Integrity, atau login Google Play Games bisa menolak klon. Aplikasi biasa dan banyak game bekerja; Clash of Clans sebaiknya diuji langsung. Login Supercell ID umumnya aman, login Google Play Games bisa gagal.
- **Data OBB / folder eksternal** tidak ikut disalin (game akan mengunduh ulang datanya).
- Perlu izin *Pasang aplikasi tidak dikenal* untuk Little Clone. Play Protect mungkin menampilkan peringatan; pilih *Tetap instal*.
- Klon **tidak ikut diperbarui** saat aplikasi asli diperbarui; buat klon baru dan hapus yang lama.
- Kunci tanda tangan disimpan di penyimpanan privat Little Clone. Jika Little Clone di-uninstall, klon lama tetap jalan tetapi tidak bisa diperbarui oleh versi baru.
- Android 11 tidak punya blur latar yang murah, jadi efek *glass* dibuat dengan lapisan translusen + tepi cahaya + gradien latar.
- Ruang kosong dibutuhkan sekitar 3× ukuran aplikasi selama proses.
