package vn.quynhnhai.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Rebind-safe, bounded logo loading; missing or unsupported logos keep a channel monogram. */
class ChannelLogo(context: Context, private val u: U) : FrameLayout(context) {
    private val placeholder = mkText(context, u, 26, true).apply {
        gravity = Gravity.CENTER
        setTextColor(0xFF253849.toInt())
        maxLines = 2
    }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(u.p(8), u.p(8), u.p(8), u.p(8))
    }
    private var boundUrl = ""
    private var generation = 0
    init {
        background = rounded(0xF0FFFFFF.toInt(), u.f(14))
        clipToOutline = true
        addView(placeholder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    fun bind(name: String, url: String) {
        contentDescription = "Logo $name"
        placeholder.text = name.trim().take(4).uppercase()
        if (url == boundUrl && image.drawable != null) return
        boundUrl = url
        val ticket = ++generation
        image.setImageDrawable(null)
        placeholder.visibility = View.VISIBLE
        if (url.isBlank()) return
        val cached = cache.get(url)
        if (cached != null) {
            image.setImageBitmap(cached)
            placeholder.visibility = View.GONE
            return
        }
        executor.execute {
            val bitmap = try { load(url) } catch (_: Exception) { null }
            if (bitmap != null) cache.put(url, bitmap)
            main.post {
                if (generation == ticket && bitmap != null) {
                    image.setImageBitmap(bitmap)
                    placeholder.visibility = View.GONE
                }
            }
        }
    }
    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val executor = java.util.concurrent.ThreadPoolExecutor(2, 2, 30,
            java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy())
        private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private fun load(address: String): Bitmap? {
            val url = URL(address)
            if (url.protocol != "https" && url.protocol != "http") return null
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 7000
            c.readTimeout = 7000
            c.setRequestProperty("User-Agent", "QTV-Android")
            try {
                if (c.responseCode != 200) return null
                val bytes = c.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > 2 * 1024 * 1024) return null
                        output.write(buffer, 0, n)
                    }
                    output.toByteArray()
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var sample = 1
                while (bounds.outWidth / sample > 384 || bounds.outHeight / sample > 384) sample *= 2
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            } finally { c.disconnect() }
        }
    }
}
