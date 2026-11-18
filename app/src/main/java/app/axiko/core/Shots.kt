package app.axiko.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Build
import android.util.Base64
import android.webkit.WebView
import java.io.ByteArrayOutputStream

/** A picture of a screen for the model: the owner's screen and the agent's own are taken the same way. */
object Shots {
    /**
     * The view as a JPEG in base64, no wider than [maxWidth]; [clip] (in the view's pixels) cuts
     * one part out of it. Main thread only. Null when the view has no size yet.
     */
    fun take(web: WebView, background: Int, maxWidth: Int = 720, clip: Rect? = null): String? {
        if (web.width <= 0 || web.height <= 0) return null
        var whole = drawn(web, background)
        // Nothing but one colour may be the truth — or the card's way not working here: the plain way tells.
        if (whole == null || flat(whole)) {
            val plain = painted(web, background)
            if (whole == null || !flat(plain)) whole = plain
        }
        val part = clip?.let { wanted ->
            val box = Rect(wanted)
            if (box.intersect(0, 0, whole.width, whole.height) && box.width() > 1 && box.height() > 1) {
                Bitmap.createBitmap(whole, box.left, box.top, box.width(), box.height())
            } else {
                null
            }
        } ?: whole
        val scale = minOf(1f, maxWidth.toFloat() / part.width)
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(part, (part.width * scale).toInt().coerceAtLeast(1), (part.height * scale).toInt().coerceAtLeast(1), true)
        } else {
            part
        }
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
        return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * The screen as the graphics card draws it. Drawn the plain way, in software, a page comes
     * out without what the card draws by itself — a WebGL scene (three.js), a video — or with it
     * at the wrong size, and the agent then judges a 3D screen by a picture that is not it. So
     * the page's drawing is recorded and played by a renderer of its own into a picture; it does
     * not matter whether the chat covers the screen. Null when this cannot be done.
     */
    private fun drawn(web: WebView, background: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val node = RenderNode("shot")
            node.setPosition(0, 0, web.width, web.height)
            val canvas = node.beginRecording()
            canvas.drawColor(background)
            // A view draws itself where its scroll puts it; its parent moves the canvas back by
            // that much. Here there is no parent: without the move a scrolled page is drawn
            // off the picture.
            canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
            web.draw(canvas)
            node.endRecording()
            val reader = ImageReader.newInstance(web.width, web.height, PixelFormat.RGBA_8888, 1)
            val renderer = HardwareRenderer()
            try {
                renderer.setSurface(reader.surface)
                renderer.setContentRoot(node)
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                reader.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    // A row in the card's memory may be longer than the picture is wide.
                    val rows = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, web.height, Bitmap.Config.ARGB_8888)
                    rows.copyPixelsFromBuffer(plane.buffer)
                    if (rows.width == web.width) rows else Bitmap.createBitmap(rows, 0, 0, web.width, web.height)
                }
            } finally {
                renderer.destroy()
                reader.close()
            }
        }.getOrNull()
    }

    /** The screen drawn the plain way: what there was before, kept for where the card's way fails. */
    private fun painted(web: WebView, background: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(web.width, web.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        canvas.translate(-web.scrollX.toFloat(), -web.scrollY.toFloat())
        web.draw(canvas)
        return bitmap
    }

    /** Whether the picture is one colour all over, judged by a grid of its points. */
    private fun flat(bitmap: Bitmap): Boolean {
        val first = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        for (row in 1..15) for (column in 1..15) {
            if (bitmap.getPixel(bitmap.width * column / 16, bitmap.height * row / 16) != first) return false
        }
        return true
    }
}
