# Notice / Bildirim

**EN:** The MIT license in [LICENSE](LICENSE) covers Sorune's source code (C#, XAML, HTML, CSS, JavaScript), its
build scripts and documentation text. It does **not** cover the artwork listed below. These images show anime
characters whose rights belong to their respective creators and publishers; they are included only so the
themes work out of the box. They are not licensed for reuse — if you fork or redistribute Sorune, replace them
with images you have the right to use.

**TR:** [LICENSE](LICENSE) dosyasındaki MIT lisansı Sorune'nin kaynak kodunu (C#, XAML, HTML, CSS, JavaScript),
derleme betiklerini ve belge metinlerini kapsar. Aşağıdaki görselleri **kapsamaz**. Bu görseller anime
karakterlerini gösterir ve hakları ilgili yaratıcılarına ve yayıncılarına aittir; depoda yalnızca temalar
kurulur kurulmaz çalışsın diye bulunurlar. Yeniden kullanım için lisanslanmamışlardır: Sorune'yi çatallar
(fork) ya da dağıtırsan bunları kullanma hakkın olan görsellerle değiştir.

## Excluded files / Kapsam dışı dosyalar

| Path / Yol | Content / İçerik |
|---|---|
| `src/Claxe/Assets/Themes/*.jpg`, `src/Claxe/Assets/Themes/thumbs/*.jpg` | Theme wallpapers / Tema duvar kâğıtları |
| `src/Claxe/Assets/Mascot/*.png` | Mascot / Maskot |
| `app/src/main/assets/themes/*.jpg`, `app/src/main/assets/themes/thumbs/*` | Theme wallpapers (Android) / Tema duvar kâğıtları (Android) |
| `app/src/main/res/drawable-nodpi/mascot_*.png` | Mascot (Android) / Maskot (Android) |
| `src/Claxe/Assets/claxe.ico`, `src/Claxe/Assets/claxe-256.png` | App icon / Uygulama simgesi |
| `docs/screenshots/*.png` | Screenshots showing the artwork above / Bu görselleri içeren ekran görüntüleri |

## Third-party components / Üçüncü taraf bileşenler

Sorune uses these packages under their own licenses / Sorune şu paketleri kendi lisanslarıyla kullanır:

- [Microsoft.Web.WebView2](https://www.nuget.org/packages/Microsoft.Web.WebView2) — Microsoft WebView2 SDK license
- [Velopack](https://github.com/velopack/velopack) — MIT
- [Microsoft.Data.Sqlite](https://github.com/dotnet/efcore) — MIT
- [PdfPig](https://github.com/UglyToad/PdfPig) — Apache-2.0
- [Open XML SDK](https://github.com/dotnet/Open-XML-SDK) — MIT

The QR code encoder (`src/Claxe/Pages/qr.js`) follows the algorithm of Project Nayuki's QR Code generator (MIT).
Weather data in the new tab widget comes from [Open-Meteo](https://open-meteo.com) (CC BY 4.0).

## Fonts

The fonts in `app/src/main/assets/fonts/` (Orbitron, Bebas Neue, Pacifico, Press Start 2P, Righteous,
Dancing Script) come from [Google Fonts](https://github.com/google/fonts) and are licensed under the
SIL Open Font License 1.1; each font's licence is next to it (`OFL-*.txt`).