# RK Camera AI v2.2 — Android 8.0+

This repository contains the Android application source code (Java + CameraX); it is not a prebuilt APK. Build it with the included GitHub Actions workflow, then install the resulting APK.

## Pembaruan versi 2.2

- Menambahkan delapan preset warna (NATURAL, VIVID, GOLDEN, ARCTIC, CINEMA, FADED, PORTRAIT, SUNSET) ke delapan preset awal.
- Menambahkan galeri/editor internal dan pemrosesan AI Potret foto.

## Perubahan tahap 2.2

- Menambahkan **RK Studio**, galeri internal untuk menelusuri foto/video, memilih media dari file picker, dan membuka editor sesuai jenis media.
- Menambahkan segmentasi manusia on-device dengan ML Kit Selfie Segmentation untuk blur latar foto Potret (tidak memerlukan koneksi internet setelah model terkemas dalam APK).
- Menambahkan enhancement foto malam: CLAHE pada luminance + bilateral denoise bila OpenCV tersedia, dengan fallback koreksi gamma; filter foto memakai preset warna bersama kamera.
- Menambahkan alat Hapus Objek berbasis OpenCV inpainting di area yang disentuh pengguna, rotasi foto, serta menyimpan hasil sebagai salinan.
- Menambahkan render salinan video dengan stabilisasi optik-flow yang sudah ada dan preset warna Cinema/Natural.
- Tombol thumbnail kamera kini membuka RK Studio, tidak lagi melempar pengguna ke aplikasi galeri eksternal.

### Batasan yang sengaja tidak disamarkan

- Potret AI saat ini memisahkan **orang/manusia** dari latar belakang; ini bukan deteksi semua jenis objek. Pemisahan dapat keliru pada rambut, gerakan, pencahayaan buruk, atau foto tanpa orang.
- Hapus Objek menggunakan inpainting pada area kecil yang dipilih lewat sentuhan; hasilnya tidak dijamin bersih untuk objek besar atau latar rumit.
- Mode malam adalah enhancement satu foto, bukan multi-frame RAW night stacking.
- Video Cinematic di versi ini menerapkan warna Cinema, bukan AI blur latar belakang per frame. Video blur AI, Live Photo/Motion Photo, object tracking AI, dan deteksi bulan AI masih belum masuk tahap ini.
- Zoom di atas kemampuan lensa tetap digital; tidak menciptakan detail optik asli. Fitur menyesuaikan dukungan perangkat dan tidak dapat menambahkan OIS/lensa telefoto fisik.

## Fitur kamera yang tersedia

- Tampilan ala kamera iOS: bar atas (flash, timer, pengaturan), preview 4:3 untuk foto dan layar penuh untuk video, tombol zoom, pilihan VIDEO/FOTO (geser kiri/kanan di layar), rana putih, thumbnail galeri, tombol balik kamera.
- Menu Pengaturan (ikon slider di kanan atas): Stabilisasi Video (Mati / Standar / Maks), Kunci Horizon, Filter, Kualitas Video (HD/FHD), Grid. Semua tersimpan di HP.
- Stabilizer diperbaiki: arah koreksi vertikal dan rotasi dibenahi (Media3 memakai sumbu Y ke atas), cuplikan gerak lebih rapat, penghalusan Gaussian. Mode Maks: potongan layar lebih besar, jalur lebih halus, proses lebih lama.
- Kunci Horizon (kamera belakang): memakai sensor gravitasi, meluruskan cakrawala sampai 15 derajat saat HP miring.
- Zoom: cubit layar atau ketuk tombol (sampai 100x). Zoom di atas batas kamera dikerjakan software: preview diperbesar, foto di-crop lalu diperbesar dan dipertajam, video di-crop saat diproses. Kualitas turun drastis di atas sekitar 10x.
- Flash: Otomatis / Nyala / Mati (siklus di ikon kiri atas). Layar dikunci portrait; arah foto/video mengikuti cara HP dipegang.
- Bitrate hasil video dinaikkan (10 Mbps HD, 16 Mbps FHD).

## Baru di v1.4

- Warna bergaya iPhone (`IPhoneLook.java`): bayangan sedikit terangkat, highlight dilembutkan, kontras halus, sedikit hangat, vibrance yang melindungi warna kulit. Fungsi yang sama dipakai untuk foto (per piksel) dan video (3D LUT lewat Media3), jadi tampilan keduanya konsisten.
- 8 filter bergaya iPhone: STANDAR, CERAH, HANGAT, DINGIN, DRAMATIS, MONO, SILVERTONE, NOIR. Ketuk tulisan kuning di kiri atas untuk berganti filter. Filter diterapkan setelah foto diambil / video selesai direkam (preview live belum berfilter).
- Perbaikan: orientasi EXIF foto disalin ke hasil olahan supaya foto tidak miring.
- Ini pendekatan umum, bukan pipeline asli Apple; hasilnya perlu dicocokkan lewat uji di HP asli.

## What this version does

- Uses the real phone camera through CameraX for preview, photo capture, and MP4 recording.
- Opens photo mode separately from video mode so a video profile failure does not intentionally leave the entire camera screen black.
- In video mode, prefers HD, then FHD/SD, and includes CameraX's `HIGHEST` sentinel so other reported qualities can be selected if those preferences are unavailable.
- Keeps live preview free of extra software stabilization work.
- After recording stops, samples real video frames, tracks image motion with OpenCV, smooths the camera path, then renders per-frame translation/rotation correction with Media3 Transformer. An overscan crop reduces visible empty edges.
- Applies a restrained tone adjustment during the exported video and a small tone curve to captured photos. This is a generic natural-looking treatment, not Apple's proprietary image pipeline.
- Retains the original recording if motion analysis or export fails. Once a stabilized copy has been successfully published, the raw recording is removed so the gallery contains the processed result rather than two copies.
- Includes flash/torch where supported, digital 1x/2x zoom, tap-to-focus, a front/back camera switch, and a custom adaptive launcher icon.
- Packages ARM phone libraries (`armeabi-v7a` and `arm64-v8a`) rather than emulator-only x86 libraries.

## Stabilization limitations

The stabilizer is real post-processing, not a claim of perfect iPhone-equivalent results. It works best when the video has visible background detail and moderate movement. Strong motion blur, fast turns, rolling-shutter distortion, parallax, low light, or a scene with few trackable features can reduce quality. It re-encodes the video and can take a while on older phones. Some encoders may fail; in that case the original video is kept. Software cannot add optical stabilization hardware or reproduce Apple's ISP/Neural Engine.

The APK must be built and tested on real hardware before its camera preview, codec support, image quality, and stabilization result can be considered verified. This environment did not run an Android SDK/Gradle build.

## Build the APK using GitHub Actions

1. Create a repository at https://github.com/new.
2. Upload all files inside this folder, including the hidden `.github` directory. The `app/`, `build.gradle`, and `settings.gradle` files should be at the repository root in their shown structure—not inside an extra nested folder.
3. Open **Actions** → **Build RK Camera AI APK** → **Run workflow**, or push to `main`/`master`.
4. Wait for the workflow to finish. A green `Success` status is required; a ZIP source file alone is not an APK.
5. Download artifact **RK-Camera-AI-APK**, extract it, and install `app-debug.apk`.

## Updating an installed build

GitHub Actions debug builds may use different debug-signing keys between runs. Android can reject an update with a signature conflict. If this happens, uninstall the older RK Camera AI app first, then install the new APK. This removes app-local data but does not remove photos or videos saved in Gallery.

## Toolchain

- Android `minSdk 26` (Android 8 Oreo), `compileSdk 35`
- Java 17, Android Gradle Plugin 8.6.1, Gradle 8.7
- CameraX 1.4.2
- Media3 Transformer/Effect 1.5.1
- OpenCV 4.10.0
