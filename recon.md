# Recon map: perekam layar Android (referensi: XRecorder)

Metode: clean-room. Hanya fitur dan pola penggunaan dari sumber publik. Tidak ada kode, logo, nama, teks, atau aset milik aplikasi asli yang dipakai.

## Scope
- Platform: Android 10+ (karena suara internal memakai AudioPlaybackCapture).
- Irisan: inti perekam layar untuk gamer: rekam layar + suara game + mic, kontrol mengambang, pengaturan kualitas, perpustakaan video.
- Untuk siapa: pemain game yang ingin merekam gameplay.

## Sumber
| sumber | isi |
| --- | --- |
| https://www.vidmore.com/?p=38698 | ulasan: resolusi, bitrate, fps, orientasi, sumber suara, ikon mengambang, editor |
| https://www.softwaresuggest.com/xrecorder | daftar fitur: suara internal, hitung mundur, facecam, livestream, trim/merge |
| https://www.apkmirror.com/apk/inshot-inc-2/screen-recorder-video-recorder-xrecorder | halaman rilis (changelog) |

## Inventaris layar
| ID | layar | tujuan | komponen utama | status |
| --- | --- | --- | --- | --- |
| S01 | Beranda | mulai/berhenti rekam, pilih sumber suara | tombol rekam besar, toggle suara, ringkasan kualitas | idle, siap, merekam, dijeda |
| S02 | Pengaturan | resolusi, fps, bitrate, orientasi, hitung mundur, tombol mengambang | daftar preferensi | - |
| S03 | Video saya | putar, bagikan, hapus rekaman | daftar + thumbnail | kosong, berisi |
| S04 | Tombol mengambang | mulai/jeda/stop di atas game | bubble yang bisa digeser + panel | siap, merekam, dijeda |
| S05 | Hitung mundur | memberi waktu pindah ke game | angka besar di tengah layar | 3-2-1 |
| S06 | Notifikasi | kontrol dari bilah notifikasi | aksi Jeda/Stop/Mulai/Batal | per status |

## Alur
- F01 Rekam game: S01 pilih suara -> izin -> (S04 siap) -> buka game -> ketuk bubble -> S05 -> rekam -> stop -> notifikasi tersimpan
- F02 Rekam cepat tanpa bubble: S01 rekam -> S05 -> rekam -> stop dari S06
- F03 Kelola hasil: S01 -> S03 -> putar / bagikan / hapus
- F04 Ubah kualitas: S01 -> S02

## Model data (kesimpulan)
Recording: uri, nama, durasi, ukuran, tanggal. Disimpan di MediaStore (Movies/Rekamin). Tanpa server dan akun.

## Tidak ikut (skip)
- Paket Pro berbayar, iklan, watermark, pengubah suara (model bisnis aplikasi asli).
- Livestream YouTube (butuh akun dan API pihak ketiga).
- Facecam, editor trim/merge, coretan layar, screenshot: ditunda ke versi berikutnya.

## Ukuran
M. Bagian tersulit: (1) mixing suara internal + mic dengan MediaCodec/MediaMuxer, (2) pause/resume tanpa merusak video, (3) overlay mengambang dan izin Android 14.
