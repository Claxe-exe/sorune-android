package com.claxe.sorune

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.PixelCopy
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * "Translate screen", text in pictures too (foreign webtoons): the visible page is captured and
 * translated by Google Translate's image mode in a hidden browser; Translate returns the same
 * picture with the text replaced. Only the parts that changed (speech bubbles, captions) are laid
 * over the page, pinned to where the screen was, so they scroll with it. Same as the desktop app.
 *
 * Translate has no public API for this; it drives the web page. If Google changes it, this fails
 * with a message instead of doing anything odd.
 */
object ScreenTranslator {
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_SIDE = 1600
    private const val BLOCK = 8
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** Earlier patches are hidden while capturing; returns where the screen was (CSS pixels). */
    private const val HIDE = """
        document.querySelectorAll("[data-sorune-lens]").forEach((el) => el.style.visibility = "hidden");
        ({ x: scrollX, y: scrollY, w: innerWidth, h: innerHeight })
    """

    private const val SHOW = """document.querySelectorAll("[data-sorune-lens]").forEach((el) => el.style.visibility = ""); true"""

    const val REMOVE = """document.querySelectorAll("[data-sorune-lens]").forEach((el) => el.remove()); true"""

    /** Address, scroll, size, picture count and the middle element's position: the same answer twice means nothing moved. */
    const val VIEW = """
        (() => {
            const middle = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
            const top = middle ? Math.round(middle.getBoundingClientRect().top) : 0;
            return [location.href, Math.round(scrollX), Math.round(scrollY), innerWidth, innerHeight, document.images.length, top].join("|");
        })()
    """

    /** Puts the capture into Translate's image picker. */
    private const val UPLOAD = """
        ((b64) => {
            const input = [...document.querySelectorAll("input[type=file]")].find((i) => /image\/jpeg/.test(i.accept));
            if (!input) return "waiting";
            const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
            const transfer = new DataTransfer();
            transfer.items.add(new File([bytes], "screen.jpg", { type: "image/jpeg" }));
            input.files = transfer.files;
            input.dispatchEvent(new Event("change", { bubbles: true }));
            return "sent";
        })
    """

    /** Waits for the translated picture (blob: image of the uploaded width) and reads it as a data: URL. */
    private const val RESULT = """
        ((width) => {
            if (/consent\./.test(location.host)) return { state: "consent" };
            const ready = [...document.querySelectorAll("button, [role=button]")]
                .some((b) => /indir|download/i.test(b.getAttribute("aria-label") || b.textContent || ""));
            const image = [...document.querySelectorAll("img")].find((i) => i.src.startsWith("blob:") && i.naturalWidth === width);
            if (!ready || !image) return { state: "waiting" };
            if (!window.__soruneShot) {
                window.__soruneShot = "pending";
                fetch(image.src).then((r) => r.blob())
                    .then((blob) => new Promise((done) => { const reader = new FileReader(); reader.onload = () => done(reader.result); reader.readAsDataURL(blob); }))
                    .then((url) => window.__soruneShot = url, () => window.__soruneShot = "error");
                return { state: "reading" };
            }
            if (window.__soruneShot === "pending") return { state: "reading" };
            if (window.__soruneShot === "error") return { state: "error" };
            return { state: "done", image: window.__soruneShot };
        })
    """

    /** Draws the patch where the screen was; patches from an earlier translation of the same screen are replaced. */
    private const val PLACE = """
        ((ow, oh, px, py, pw, ph, b64, vx, vy, vw, vh, label) => {
            if (!vw || !vh) { vx = scrollX; vy = scrollY; vw = innerWidth; vh = innerHeight; }
            for (const old of document.querySelectorAll("canvas[data-sorune-lens]")) {
                const left = parseFloat(old.style.left), top = parseFloat(old.style.top);
                if (left >= vx - 1 && top >= vy - 1 && left + parseFloat(old.style.width) <= vx + vw + 1
                    && top + parseFloat(old.style.height) <= vy + vh + 1) old.remove();
            }
            const bytes = Uint8ClampedArray.from(atob(b64), (c) => c.charCodeAt(0));
            const canvas = document.createElement("canvas");
            canvas.width = pw;
            canvas.height = ph;
            canvas.getContext("2d").putImageData(new ImageData(bytes, pw, ph), 0, 0);
            const sx = vw / ow, sy = vh / oh;
            canvas.dataset.soruneLens = "";
            Object.assign(canvas.style, {
                position: "absolute", left: (vx + px * sx) + "px", top: (vy + py * sy) + "px",
                width: (pw * sx) + "px", height: (ph * sy) + "px", zIndex: "2147483646",
                pointerEvents: "none", margin: "0", padding: "0", border: "0", maxWidth: "none",
            });
            document.documentElement.appendChild(canvas);
            if (!document.querySelector("[data-sorune-lens-bar]")) {
                const bar = document.createElement("div");
                bar.dataset.soruneLens = "";
                bar.dataset.soruneLensBar = "";
                const shadow = bar.attachShadow({ mode: "closed" });
                const button = document.createElement("button");
                button.textContent = label;
                Object.assign(button.style, {
                    font: "600 13px system-ui, sans-serif", color: "#fff", background: "rgba(20,16,28,.88)",
                    border: "1px solid rgba(181,131,255,.7)", borderRadius: "999px", padding: "8px 14px",
                });
                button.onclick = () => document.querySelectorAll("[data-sorune-lens]").forEach((el) => el.remove());
                shadow.append(button);
                Object.assign(bar.style, { position: "fixed", right: "14px", bottom: "18px", zIndex: "2147483647" });
                document.documentElement.appendChild(bar);
            }
            return true;
        })
    """

    /** Null when it worked, otherwise a short note for the user. */
    suspend fun translateVisible(activity: Activity, page: WebView, language: String): String? {
        val view = runCatching { JSONTokener(page.eval(HIDE)).nextValue() as? JSONObject }.getOrNull()
        val screen = try {
            capture(activity, page)
        } finally {
            page.eval(SHOW)
        } ?: return "Ekran görüntüsü alınamadı."

        val jpeg = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { screen.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        }
        val (translatedUrl, note) = runTranslate(activity, jpeg, screen.width, language)
        if (note != null) return note
        translatedUrl ?: return "Bu ekranda çevrilecek yazı bulamadım."

        val patch = withContext(Dispatchers.Default) {
            val bytes = Base64.decode(translatedUrl.substringAfter(','), Base64.DEFAULT)
            val translated = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            changedParts(screen, translated)
        } ?: return "Bu ekranda çevrilecek yazı bulamadım."

        val v = listOf("x", "y", "w", "h").map { view?.optDouble(it, 0.0) ?: 0.0 }
        page.eval(
            "($PLACE)(${patch.overlayWidth}, ${patch.overlayHeight}, ${patch.x}, ${patch.y}, ${patch.width}, ${patch.height}, " +
                "\"${patch.rgba}\", ${v[0]}, ${v[1]}, ${v[2]}, ${v[3]}, ${JSONObject.quote("Ekran çevirisini kaldır")})"
        )
        return null
    }

    /** The page as it is on screen (video and canvas included), scaled to at most 1600 px. */
    private suspend fun capture(activity: Activity, page: WebView): Bitmap? {
        if (page.width <= 0 || page.height <= 0) return null
        val location = IntArray(2)
        page.getLocationInWindow(location)
        val rect = android.graphics.Rect(location[0], location[1], location[0] + page.width, location[1] + page.height)
        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { done ->
            PixelCopy.request(activity.window, rect, bitmap, { result -> if (done.isActive) done.resume(result == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()))
        }
        if (!ok) return null
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        return if (scale >= 1f) bitmap
        else Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runTranslate(activity: Activity, jpeg: ByteArray, width: Int, language: String): Pair<String?, String?> {
        // A browser off the screen, as a desktop: Translate's image mode is the desktop page.
        val helper = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_UA
            webViewClient = WebViewClient()
            translationX = -20000f
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.addView(helper, FrameLayout.LayoutParams(1280, 900))
        try {
            helper.loadUrl("https://translate.google.com/?sl=auto&tl=$language&op=images")
            val upload = "($UPLOAD)(${JSONObject.quote(Base64.encodeToString(jpeg, Base64.NO_WRAP))})"
            var sent = false
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(400)
                if (helper.url?.contains("google.") != true) continue
                if (!sent) {
                    sent = helper.eval(upload) == "\"sent\""
                    continue
                }
                val result = runCatching { JSONTokener(helper.eval("($RESULT)($width)")).nextValue() as? JSONObject }.getOrNull() ?: continue
                when (result.optString("state")) {
                    "done" -> return result.optString("image") to null
                    "error" -> return null to "Çevrilen resim okunamadı, tekrar dene."
                    "consent" -> return null to "Google önce bir onay sayfası gösteriyor: google.com'u bir kez açıp seçimini yap, sonra tekrar dene."
                }
            }
            return null to (if (sent) null else "Google Çeviri zamanında yanıt vermedi, tekrar dene.")
        } finally {
            root.removeView(helper)
            helper.destroy()
        }
    }

    // ---------------------------------------------------------------- what changed

    class Patch(val overlayWidth: Int, val overlayHeight: Int, val x: Int, val y: Int, val width: Int, val height: Int, val rgba: String)

    /**
     * Compares the capture with Translate's picture in 8×8 blocks; blocks that differ more than
     * JPEG noise (plus two around them, so no trace of the old letters is left) are kept from
     * the translation, everything else is transparent. Null when nothing changed.
     */
    fun changedParts(original: Bitmap, translatedIn: Bitmap): Patch? {
        val width = original.width
        val height = original.height
        val translated = if (translatedIn.width != width || translatedIn.height != height)
            Bitmap.createScaledBitmap(translatedIn, width, height, true) else translatedIn
        val a = IntArray(width * height)
        val b = IntArray(width * height)
        original.getPixels(a, 0, width, 0, 0, width, height)
        translated.getPixels(b, 0, width, 0, 0, width, height)

        val columns = (width + BLOCK - 1) / BLOCK
        val rows = (height + BLOCK - 1) / BLOCK
        val changed = Array(rows) { BooleanArray(columns) }
        for (by in 0 until rows) for (bx in 0 until columns) {
            var sum = 0L
            var count = 0
            for (y in by * BLOCK until minOf(height, (by + 1) * BLOCK)) for (x in bx * BLOCK until minOf(width, (bx + 1) * BLOCK)) {
                val p = a[y * width + x]
                val q = b[y * width + x]
                sum += Math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) +
                    Math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + Math.abs((p and 0xFF) - (q and 0xFF))
                count++
            }
            changed[by][bx] = sum / maxOf(1, count) > 24
        }

        val keep = Array(rows) { BooleanArray(columns) }
        var left = columns
        var top = rows
        var right = -1
        var bottom = -1
        for (by in 0 until rows) for (bx in 0 until columns) {
            if (!changed[by][bx]) continue
            for (dy in -2..2) for (dx in -2..2) {
                val nx = bx + dx
                val ny = by + dy
                if (nx < 0 || ny < 0 || nx >= columns || ny >= rows) continue
                keep[ny][nx] = true
                left = minOf(left, nx); right = maxOf(right, nx)
                top = minOf(top, ny); bottom = maxOf(bottom, ny)
            }
        }
        if (right < 0) return null

        val px = left * BLOCK
        val py = top * BLOCK
        val pw = minOf(width, (right + 1) * BLOCK) - px
        val ph = minOf(height, (bottom + 1) * BLOCK) - py
        val rgba = ByteArray(pw * ph * 4)
        for (y in 0 until ph) for (x in 0 until pw) {
            if (!keep[(py + y) / BLOCK][(px + x) / BLOCK]) continue
            val q = b[(py + y) * width + (px + x)]
            val i = (y * pw + x) * 4
            rgba[i] = (q shr 16 and 0xFF).toByte()
            rgba[i + 1] = (q shr 8 and 0xFF).toByte()
            rgba[i + 2] = (q and 0xFF).toByte()
            rgba[i + 3] = 0xFF.toByte()
        }
        return Patch(width, height, px, py, pw, ph, Base64.encodeToString(rgba, Base64.NO_WRAP))
    }
}
