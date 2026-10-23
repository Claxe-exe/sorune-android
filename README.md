# Sorune for Android

**Anime ruhlu, kişisel tarayıcı Sorune'nin Android sürümü.** Masaüstü sürümü:
[Claxe-exe/sorune-browser](https://github.com/Claxe-exe/sorune-browser).

> [!TIP]
> ### 📥 Nasıl kurulur?
> 1. Telefonda **[Sorune.apk'yı indir](https://github.com/Claxe-exe/sorune-android/releases/latest/download/Sorune.apk)**.
> 2. İndirilen dosyayı aç. Android "bu kaynaktan uygulama yükleme" iznini sorarsa izin ver.
> 3. Bu kadar. Bundan sonra Sorune yeni sürümleri **kendisi bulur ve indirir**; güncellemek için tek dokunuş yeter.

## ✨ Özellikler

- Sekmeler, adres çubuğu, arama motoru önerileri (Google, DuckDuckGo, Bing, Brave)
- Masaüstüyle aynı **Sorune temaları**: duvar kâğıdı, renkler ve yeni sekme sayfası
- **Sayfa çevirisi** (Google Çeviri, yerinde) ve **ekran çevirisi**: resimlerdeki yazılar dahil, webtoon okumak için;
  istersen **otomatik**, 3–20 saniyede bir ekran değişince
- Tam ekran video, dosya indirme, dosya yükleme, masaüstü sitesi modu
- Uygulama içi güncelleme (GitHub sürümlerinden)

## 🛠️ Derleme

Android SDK (platform 36) ve JDK 17 gerekir.

```bash
./gradlew assembleDebug
```

Yayın: `./release.ps1 -Version 0.2.0 -NotesFile notlar.md`. İmza anahtarı repoda değil,
`~/.sorune-android/` klasöründe durur; her sürüm aynı anahtarla imzalanmalıdır, yoksa telefonlar
güncellemeyi kabul etmez.

## 📄 Lisans

Kod MIT lisanslıdır ([LICENSE](LICENSE)). Tema görselleri bu lisansa dahil değildir
([NOTICE.md](NOTICE.md)).
