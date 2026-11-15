# Rekamin: perekam layar dan suara game (Android)

Perekam layar bergaya aplikasi perekam populer, dibuat dari nol dengan nama dan tampilan sendiri.
Ganti nama aplikasi di `app/src/main/res/values/strings.xml` (`app_name`) sesuai keinginan.

## Fitur
- Rekam layar ke MP4 (H.264 + AAC), tersimpan otomatis di Galeri: Movies/Rekamin
- Sumber suara: tanpa suara, mikrofon, **suara game / internal**, atau **mic + suara game**
- Tombol merah mengambang yang bisa digeser: ketuk untuk mulai, lalu jeda dan stop
- Hitung mundur 0/3/5/10 detik, supaya sempat membuka game
- Jeda dan lanjut, kontrol dari notifikasi
- Pengaturan: resolusi (480p / 720p / 1080p / asli), fps (24/30/60), bitrate (2-16 Mbps), orientasi
- Video saya: putar, bagikan, hapus (dengan thumbnail)

## Cara pakai untuk merekam game
1. Buka Rekamin, pilih sumber suara **Game** (atau **Mic+Game**), ketuk **Rekam**.
2. Izinkan mikrofon, notifikasi, dan Tampil di atas aplikasi lain (untuk tombol mengambang).
3. Izinkan rekam layar. Aplikasi pindah ke latar belakang dan tombol merah muncul.
4. Buka game, ketuk tombol merah, tunggu hitung mundur. Rekaman mulai.
5. Berhenti lewat notifikasi (atau tombol merah bila "Sembunyikan tombol saat merekam" dimatikan).

## Memasang ke HP
1. Pasang Android Studio, buka folder `Rekamin` (File > Open), tunggu Gradle sync (perlu internet).
2. Aktifkan Opsi pengembang > Debugging USB di HP, sambungkan lewat kabel, klik Run.
3. Untuk berkas APK: Build > Build APK(s).

## Batasan yang perlu diketahui
- Android 10 ke atas. Suara game memakai fitur resmi Android (AudioPlaybackCapture).
- Sebagian game atau aplikasi memblokir perekaman suaranya (aturan dari pembuatnya). Hasilnya video tetap
  terekam tapi tanpa suara game. Rekamin tidak bisa menembus pemblokiran itu.
- Pakai headset bila memilih Mic+Game, agar suara speaker tidak ikut masuk lewat mikrofon.
- Pengaturan fps adalah batas atas dengan upaya terbaik; sebagian HP mengabaikannya.
- Rekaman yang terlalu singkat (sekitar 1 detik) tidak disimpan.
- Belum ada: facecam, editor video, coretan layar, livestream.

## Catatan pengembangan
Folder `replica/` berisi peta recon dan matriks fitur (format skill replica) untuk membandingkan
hasil dengan aplikasi referensi di versi berikutnya.
