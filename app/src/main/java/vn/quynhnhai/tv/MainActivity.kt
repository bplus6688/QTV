package vn.quynhnhai.tv

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

// ---------- Màu sắc (theo thiết kế) ----------
val C_BG = 0xFF0F1318.toInt()
val C_SIDE = 0xFF0B0E12.toInt()
val C_SURF = 0xFF1A2029.toInt()
val C_TXT = 0xFFF2F4F7.toInt()
val C_MUT = 0xFFA8B1BD.toInt()
val C_AMB = 0xFFFFB547.toInt()
val C_AMB_TXT = 0xFF1A1203.toInt()
val C_DARK = 0xFF3A4350.toInt()

data class Chan(val name: String, val group: String, val url: String)

/** Một ô trong danh sách (kênh hoặc nhóm kênh). */
data class Tile(val lead: String, val title: String, val sub: String, val star: Boolean = false)

/** Quy đổi kích thước thiết kế 1920x1080 sang pixel thật của màn hình. */
class U(val s: Float) {
    fun p(n: Int): Int = (n * s).toInt()
    fun f(n: Int): Float = n * s
}

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

fun mkText(ctx: Context, u: U, size: Int, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, u.f(size))
        setTextColor(C_TXT)
        includeFontPadding = false
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

// ---------- Danh sách ô: ô đang chọn phóng to 110% và đổi sang màu hổ phách ----------
class TileAdapter(private val u: U, private val items: List<Tile>) :
    RecyclerView.Adapter<TileAdapter.VH>() {

    var selected = -1

    class VH(
        val root: LinearLayout,
        val lead: TextView,
        val title: TextView,
        val sub: TextView,
        val star: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u.p(28), 0, u.p(28), 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u.p(96)
            ).apply { bottomMargin = u.p(8) }
        }
        val lead = mkText(ctx, u, 32, true).apply {
            layoutParams = LinearLayout.LayoutParams(u.p(64), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = mkText(ctx, u, 32, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val sub = mkText(ctx, u, 22).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val star = mkText(ctx, u, 32, true).apply { text = "★" }
        col.addView(title)
        col.addView(sub)
        root.addView(lead)
        root.addView(col)
        root.addView(star)
        return VH(root, lead, title, sub, star)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = items[i]
        val sel = i == selected
        h.lead.text = t.lead
        h.lead.visibility = if (t.lead.isEmpty()) View.GONE else View.VISIBLE
        h.title.text = t.title
        h.sub.text = t.sub
        h.sub.visibility = if (t.sub.isBlank()) View.GONE else View.VISIBLE
        h.star.visibility = if (t.star) View.VISIBLE else View.GONE
        h.root.background = rounded(if (sel) C_AMB else C_SURF, u.f(14))
        h.root.scaleX = if (sel) 1.10f else 1f
        h.root.scaleY = if (sel) 1.10f else 1f
        h.root.translationZ = if (sel) u.f(12) else 0f
        h.lead.setTextColor(if (sel) C_AMB_TXT else C_MUT)
        h.title.setTextColor(if (sel) C_AMB_TXT else C_TXT)
        h.sub.setTextColor(if (sel) C_AMB_TXT else C_MUT)
        h.star.setTextColor(if (sel) C_AMB_TXT else C_AMB)
    }
}

enum class Mode { WATCH, DRAWER, GROUPS, SETTINGS }

class MainActivity : Activity() {

    private lateinit var u: U
    private lateinit var root: FrameLayout
    private lateinit var pv: PlayerView
    private lateinit var exo: ExoPlayer

    // Thanh dưới: tên kênh (trái) + giờ, ngày (phải)
    private lateinit var bar: LinearLayout
    private lateinit var barNum: TextView
    private lateinit var barName: TextView
    private lateinit var barGrp: TextView
    private lateinit var barTime: TextView
    private lateinit var barDate: TextView

    private lateinit var dim: View
    private lateinit var drawer: LinearLayout
    private lateinit var drawerTitle: TextView
    private lateinit var drawerRv: RecyclerView
    private lateinit var groupsPanel: LinearLayout
    private lateinit var groupsRv: RecyclerView
    private lateinit var numView: TextView
    private lateinit var msgView: TextView

    private lateinit var settings: FrameLayout
    private lateinit var qrImage: ImageView
    private lateinit var addrText: TextView
    private lateinit var statusText: TextView
    private var server: SetupServer? = null
    private var token = ""

    private var drawerAdapter: TileAdapter? = null
    private var groupsAdapter: TileAdapter? = null

    private var chans: List<Chan> = emptyList()
    private var groupNames: List<String> = emptyList()
    private var visible: List<Int> = emptyList()
    private var favs: MutableSet<String> = HashSet()
    private var groupFilter = 0 // 0 = tất cả, 1 = yêu thích, 2.. = nhóm theo nguồn
    private var gsel = 0
    private var dsel = 0
    private var cur = -1
    private var mode = Mode.WATCH
    private var retry = 0
    private var numBuf = ""
    private var okDown = false
    private var favDone = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, dd/MM/yyyy", Locale.forLanguageTag("vi-VN"))

    private val handler = Handler(Looper.getMainLooper())
    private val retryRun = Runnable { loadCurrent() }
    private val hideBarRun = Runnable { bar.visibility = View.GONE }
    private val clockRun = object : Runnable {
        override fun run() {
            updateClock()
            if (bar.visibility == View.VISIBLE) handler.postDelayed(this, 1000)
        }
    }
    private val favRun = Runnable {
        if (mode == Mode.DRAWER && okDown) {
            favDone = true
            toggleFav()
        }
    }
    private val numRun = Runnable {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        numView.visibility = View.GONE
        if (n != null && n in 1..chans.size) {
            play(n - 1)
            showBar()
        }
    }

    private val prefs by lazy { getSharedPreferences("qn", MODE_PRIVATE) }

    // ================= Khởi tạo =================
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        val dm = resources.displayMetrics
        u = U(minOf(dm.widthPixels / 1920f, dm.heightPixels / 1080f))
        favs = HashSet(prefs.getStringSet("fav", emptySet()) ?: emptySet())

        exo = ExoPlayer.Builder(this).build()
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (retry < 3) {
                    retry++
                    say("Đang kết nối lại…")
                    handler.removeCallbacks(retryRun)
                    handler.postDelayed(retryRun, 3000)
                } else {
                    say("Không phát được kênh này")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retry = 0
                    say(null)
                }
            }
        })

        buildUi()
        setMode(Mode.WATCH)
        loadList()
    }

    private fun lp(w: Int, h: Int, l: Int, t: Int) =
        FrameLayout.LayoutParams(u.p(w), u.p(h)).apply {
            leftMargin = u.p(l)
            topMargin = u.p(t)
            gravity = Gravity.TOP or Gravity.START
        }

    private fun hintRow(label: String, msg: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ok = mkText(this, u, 24, true).apply {
            text = label
            setTextColor(C_AMB_TXT)
            background = rounded(C_AMB, u.f(8))
            setPadding(u.p(14), u.p(2), u.p(14), u.p(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = u.p(10) }
        }
        val t = mkText(this, u, 24).apply {
            text = msg
            setTextColor(C_MUT)
        }
        row.addView(ok)
        row.addView(t)
        return row
    }

    private fun mkRecycler(): RecyclerView = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        itemAnimator = null
        clipChildren = false
        clipToPadding = false
        setPadding(u.p(30), u.p(12), u.p(30), u.p(12))
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        // Video luôn toàn màn hình
        pv = layoutInflater.inflate(R.layout.player, root, false) as PlayerView
        pv.player = exo
        pv.setBackgroundColor(Color.BLACK)
        root.addView(
            pv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Thanh dưới: tên kênh bên trái, giờ + ngày bên phải
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u.p(80), u.p(28), u.p(80), u.p(36))
            setBackgroundColor(Color.argb(230, 15, 19, 24))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            )
        }
        val barLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        barNum = mkText(this, u, 56, true).apply {
            gravity = Gravity.CENTER
            setTextColor(C_AMB_TXT)
            background = rounded(C_AMB, u.f(16))
            layoutParams = LinearLayout.LayoutParams(u.p(104), u.p(104))
        }
        val barCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = u.p(28) }
        }
        barName = mkText(this, u, 56, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        barGrp = mkText(this, u, 28).apply {
            setTextColor(C_MUT)
            setPadding(0, u.p(8), 0, 0)
        }
        barCol.addView(barName)
        barCol.addView(barGrp)
        barLeft.addView(barNum)
        barLeft.addView(barCol)
        val barRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barTime = mkText(this, u, 104, true).apply { gravity = Gravity.END }
        barDate = mkText(this, u, 32).apply {
            gravity = Gravity.END
            setTextColor(C_MUT)
            setPadding(0, u.p(6), 0, 0)
        }
        barRight.addView(barTime)
        barRight.addView(barDate)
        bar.addView(barLeft)
        bar.addView(barRight)
        root.addView(bar)

        // Lớp tối khi mở danh sách
        dim = View(this).apply {
            setBackgroundColor(Color.argb(140, 8, 10, 13))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(dim)

        // Ngăn kéo chuyển kênh (bấm Trái khi đang xem)
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
            setPadding(u.p(40), u.p(48), u.p(40), u.p(48))
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(u.p(620), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        drawerTitle = mkText(this, u, 30, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, u.p(18))
        }
        drawerRv = mkRecycler().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.p(700))
        }
        drawer.addView(drawerTitle)
        drawer.addView(drawerRv)
        drawer.addView(hintRow("OK", "Chuyển kênh").apply { setPadding(0, u.p(12), 0, 0) })
        drawer.addView(hintRow("Giữ OK", "Thêm/bỏ yêu thích").apply { setPadding(0, u.p(10), 0, 0) })
        drawer.addView(hintRow("Trái", "Nhóm kênh").apply { setPadding(0, u.p(10), 0, 0) })
        root.addView(drawer)

        // Bảng nhóm kênh (bấm Trái lần nữa)
        groupsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SIDE)
            setPadding(u.p(40), u.p(48), u.p(40), u.p(48))
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(u.p(520), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val gTitle = mkText(this, u, 30, true).apply {
            text = "Nhóm kênh"
            setPadding(0, 0, 0, u.p(18))
        }
        groupsRv = mkRecycler().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.p(780))
        }
        groupsPanel.addView(gTitle)
        groupsPanel.addView(groupsRv)
        groupsPanel.addView(hintRow("OK", "Chọn nhóm").apply { setPadding(0, u.p(12), 0, 0) })
        groupsPanel.addView(hintRow("Phải", "Danh sách kênh").apply { setPadding(0, u.p(10), 0, 0) })
        root.addView(groupsPanel)

        // Màn hình đổi nguồn kênh (quét QR bằng điện thoại)
        settings = FrameLayout(this).apply {
            setBackgroundColor(C_BG)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val sTitle = mkText(this, u, 60, true).apply {
            text = "Đổi nguồn kênh"
            layoutParams = lp(900, 80, 120, 140)
        }
        val sSteps = mkText(this, u, 34).apply {
            text = "1. Điện thoại và tivi dùng cùng Wi-Fi\n\n2. Quét mã QR bên phải bằng camera điện thoại\n\n3. Dán link playlist M3U rồi bấm “Gửi sang tivi”"
            setLineSpacing(0f, 1.2f)
            layoutParams = lp(900, 360, 120, 260)
        }
        statusText = mkText(this, u, 30, true).apply {
            setTextColor(C_AMB)
            layoutParams = lp(900, 60, 120, 700)
        }
        val sHint = hintRow("Quay lại", "Đóng").apply { layoutParams = lp(900, 60, 120, 900) }
        qrImage = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = lp(560, 560, 1180, 140)
        }
        addrText = mkText(this, u, 26).apply {
            setTextColor(C_MUT)
            gravity = Gravity.CENTER
            layoutParams = lp(560, 120, 1180, 720)
        }
        settings.addView(sTitle)
        settings.addView(sSteps)
        settings.addView(statusText)
        settings.addView(sHint)
        settings.addView(qrImage)
        settings.addView(addrText)
        root.addView(settings)

        // Số kênh đang nhập
        numView = mkText(this, u, 96, true).apply {
            setTextColor(C_AMB)
            setShadowLayer(12f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = u.p(56)
                marginEnd = u.p(80)
                gravity = Gravity.TOP or Gravity.END
            }
        }
        root.addView(numView)

        // Thông báo giữa màn hình
        msgView = mkText(this, u, 40, true).apply {
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(msgView)
    }

    // ================= Chế độ màn hình =================
    private fun setMode(m: Mode) {
        mode = m
        val panels = m == Mode.DRAWER || m == Mode.GROUPS
        dim.visibility = if (panels) View.VISIBLE else View.GONE
        drawer.visibility = if (panels) View.VISIBLE else View.GONE
        drawer.translationX = if (m == Mode.GROUPS) u.f(520) else 0f
        groupsPanel.visibility = if (m == Mode.GROUPS) View.VISIBLE else View.GONE
        settings.visibility = if (m == Mode.SETTINGS) View.VISIBLE else View.GONE
        if (m != Mode.WATCH) {
            handler.removeCallbacks(hideBarRun)
            handler.removeCallbacks(clockRun)
            bar.visibility = View.GONE
        }
        exo.volume = if (m == Mode.SETTINGS) 0f else 1f
    }

    private fun say(t: String?) {
        if (t.isNullOrEmpty()) {
            msgView.visibility = View.GONE
        } else {
            msgView.text = t
            msgView.visibility = View.VISIBLE
        }
    }

    // ================= Thanh dưới: tên kênh + giờ + ngày =================
    private fun updateClock() {
        val now = Date()
        barTime.text = timeFmt.format(now)
        barDate.text = dateFmt.format(now).replaceFirstChar { it.uppercase() }
    }

    private fun showBar() {
        if (mode != Mode.WATCH) return
        val c = chans.getOrNull(cur) ?: return
        barNum.text = (cur + 1).toString()
        barName.text = c.name
        barGrp.text = c.group
        barGrp.visibility = if (c.group.isBlank()) View.GONE else View.VISIBLE
        bar.visibility = View.VISIBLE
        handler.removeCallbacks(clockRun)
        clockRun.run()
        handler.removeCallbacks(hideBarRun)
        handler.postDelayed(hideBarRun, 5000)
    }

    // ================= Đổi nguồn kênh bằng QR =================
    private fun currentUrl(): String =
        (prefs.getString("src", null) ?: "").trim().ifEmpty { getString(R.string.playlist_url).trim() }

    private fun hostOf(s: String): String = try {
        URL(s).host
    } catch (e: Exception) {
        s.take(30)
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = m.width
        val h = m.height
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    private fun openSettings() {
        stopServer()
        say(null)
        token = (100000..999999).random().toString()
        val srv = SetupServer(token, { currentUrl() }) { url ->
            runOnUiThread { onSourceReceived(url) }
        }
        val ip = localIp()
        if (ip != null && srv.start()) {
            server = srv
            val addr = "http://$ip:${srv.port}/?k=$token"
            qrImage.setImageBitmap(makeQr(addr, u.p(560)))
            addrText.text = addr
        } else {
            qrImage.setImageBitmap(null)
            addrText.text = "Không tìm thấy mạng Wi-Fi. Hãy kết nối mạng rồi thử lại."
        }
        val cu = currentUrl()
        statusText.text = if (cu.isEmpty()) "Chưa có nguồn kênh" else "Nguồn hiện tại: " + hostOf(cu)
        setMode(Mode.SETTINGS)
    }

    private fun closeSettings() {
        stopServer()
        setMode(Mode.WATCH)
        if (chans.isEmpty()) say("Chưa có danh sách kênh. OK: thử lại · Trái: đổi nguồn kênh")
    }

    private fun onSourceReceived(url: String) {
        prefs.edit().putString("src", url).remove("pl").apply()
        stopServer()
        setMode(Mode.WATCH)
        loadList()
    }

    // ================= Playlist =================
    private fun loadList() {
        val url = currentUrl()
        say("Đang tải danh sách kênh…")
        thread {
            var txt: String? = null
            if (url.isNotEmpty()) {
                try {
                    txt = fetch(url)
                    prefs.edit().putString("pl", txt).apply()
                } catch (e: Exception) {
                }
            }
            if (txt == null) txt = prefs.getString("pl", null)
            val list = if (txt != null) parseM3U(txt) else emptyList()
            runOnUiThread { onList(list, url.isNotEmpty()) }
        }
    }

    private fun fetch(first: String): String {
        var target = URL(first)
        for (i in 0..5) {
            val c = target.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 QuynhNhaiTV")
            val code = c.responseCode
            if (code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = c.getHeaderField("Location") ?: throw Exception("redirect")
                target = URL(target, loc)
                c.disconnect()
                continue
            }
            if (code != 200) throw Exception("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        }
        throw Exception("too many redirects")
    }

    private fun parseM3U(txt: String): List<Chan> {
        val out = ArrayList<Chan>()
        var name = ""
        var grp = ""
        val reName = Regex("tvg-name=\"([^\"]*)\"")
        val reGrp = Regex("group-title=\"([^\"]*)\"")
        for (raw in txt.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) continue
            if (l.startsWith("#EXTINF")) {
                val c = l.lastIndexOf(',')
                name = if (c >= 0) l.substring(c + 1).trim() else ""
                if (name.isEmpty()) name = reName.find(l)?.groupValues?.get(1) ?: ""
                grp = (reGrp.find(l)?.groupValues?.get(1) ?: "").trim()
            } else if (!l.startsWith("#")) {
                out.add(Chan(name.ifEmpty { "Kênh ${out.size + 1}" }, grp, l))
                name = ""
                grp = ""
            }
        }
        return out
    }

    private fun onList(list: List<Chan>, hasUrl: Boolean) {
        chans = list
        cur = -1
        if (list.isEmpty()) {
            if (hasUrl) {
                say("Chưa có danh sách kênh. OK: thử lại · Trái: đổi nguồn kênh")
            } else {
                say(null)
                openSettings()
            }
            return
        }
        say(null)
        groupNames = list.map { it.group }.filter { it.isNotBlank() }.distinct()
        groupFilter = 0
        computeVisible()
        val last = prefs.getInt("last", 0)
        setMode(Mode.WATCH)
        play(if (last in list.indices) last else 0)
        showBar()
    }

    // ================= Nhóm kênh & yêu thích =================
    private fun computeVisible() {
        if (groupFilter >= 2 + groupNames.size) groupFilter = 0
        visible = chans.indices.filter { i ->
            when (groupFilter) {
                0 -> true
                1 -> favs.contains(chans[i].url)
                else -> chans[i].group == groupNames[groupFilter - 2]
            }
        }
    }

    private fun groupTitle(i: Int): String = when (i) {
        0 -> "Tất cả kênh"
        1 -> "Yêu thích"
        else -> groupNames[i - 2]
    }

    private fun groupTiles(): List<Tile> {
        val out = ArrayList<Tile>()
        out.add(Tile("", "Tất cả kênh", "${chans.size} kênh"))
        out.add(Tile("", "Yêu thích", "${chans.count { favs.contains(it.url) }} kênh"))
        for (g in groupNames) out.add(Tile("", g, "${chans.count { it.group == g }} kênh"))
        out.add(Tile("", "Đổi nguồn kênh", "Dán link bằng điện thoại"))
        return out
    }

    private fun posOfCur(): Int = visible.indexOf(cur).let { if (it < 0) 0 else it }

    private fun center(rv: RecyclerView, pos: Int, heightDesign: Int) {
        (rv.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(pos, u.p((heightDesign - 96) / 2))
    }

    private fun buildDrawer() {
        val tiles = if (visible.isEmpty()) {
            listOf(Tile("", "Chưa có kênh", "Giữ OK trên một kênh để thêm vào Yêu thích"))
        } else {
            visible.map { Tile((it + 1).toString(), chans[it].name, chans[it].group, favs.contains(chans[it].url)) }
        }
        if (dsel >= visible.size) dsel = maxOf(0, visible.size - 1)
        drawerAdapter = TileAdapter(u, tiles).also { it.selected = if (visible.isEmpty()) -1 else dsel }
        drawerRv.adapter = drawerAdapter
        drawerTitle.text = if (groupFilter == 0) "Chuyển kênh" else "Chuyển kênh · " + groupTitle(groupFilter)
        if (visible.isNotEmpty()) center(drawerRv, dsel, 700)
    }

    private fun buildGroups() {
        groupsAdapter = TileAdapter(u, groupTiles()).also { it.selected = gsel }
        groupsRv.adapter = groupsAdapter
        center(groupsRv, gsel, 780)
    }

    private fun openDrawer() {
        computeVisible()
        dsel = posOfCur()
        buildDrawer()
        setMode(Mode.DRAWER)
    }

    private fun openGroups() {
        gsel = groupFilter
        buildGroups()
        setMode(Mode.GROUPS)
    }

    private fun moveDrawer(d: Int) {
        if (visible.isEmpty()) return
        dsel = (dsel + d + visible.size) % visible.size
        drawerAdapter?.let {
            it.selected = dsel
            it.notifyDataSetChanged()
        }
        center(drawerRv, dsel, 700)
    }

    private fun moveGroup(d: Int) {
        val total = 3 + groupNames.size
        gsel = (gsel + d + total) % total
        groupsAdapter?.let {
            it.selected = gsel
            it.notifyDataSetChanged()
        }
        center(groupsRv, gsel, 780)
        if (gsel < 2 + groupNames.size) {
            groupFilter = gsel
            computeVisible()
            dsel = posOfCur()
            buildDrawer()
        }
    }

    private fun chooseGroup() {
        if (gsel >= 2 + groupNames.size) {
            openSettings()
        } else {
            dsel = posOfCur()
            buildDrawer()
            setMode(Mode.DRAWER)
        }
    }

    private fun toggleFav() {
        if (visible.isEmpty()) return
        val url = chans[visible[dsel]].url
        if (!favs.remove(url)) favs.add(url)
        prefs.edit().putStringSet("fav", HashSet(favs)).apply()
        computeVisible()
        buildDrawer()
    }

    private fun selectFromDrawer() {
        if (visible.isEmpty()) return
        val i = visible[dsel]
        setMode(Mode.WATCH)
        if (i != cur) play(i)
        showBar()
    }

    // ================= Phát kênh =================
    private fun play(i: Int) {
        if (i !in chans.indices) return
        cur = i
        retry = 0
        handler.removeCallbacks(retryRun)
        prefs.edit().putInt("last", i).apply()
        loadCurrent()
    }

    private fun loadCurrent() {
        val c = chans.getOrNull(cur) ?: return
        say(null)
        exo.setMediaItem(MediaItem.fromUri(c.url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun zap(d: Int) {
        val list = if (visible.isEmpty()) chans.indices.toList() else visible
        val pos = list.indexOf(cur)
        val n = if (pos < 0) list[0] else list[(pos + d + list.size) % list.size]
        play(n)
        showBar()
    }

    // ================= Phím remote =================
    private fun digitOf(k: Int): Int = when (k) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
        else -> -1
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER ||
                k == KeyEvent.KEYCODE_NUMPAD_ENTER

        // Nhả phím OK trong danh sách kênh: bấm ngắn = chuyển kênh, giữ lâu đã xử lý yêu thích
        if (e.action == KeyEvent.ACTION_UP) {
            if (ok && okDown) {
                okDown = false
                handler.removeCallbacks(favRun)
                if (!favDone && mode == Mode.DRAWER) selectFromDrawer()
                favDone = false
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e)

        val back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE
        val up = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP
        val down = k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN
        val left = k == KeyEvent.KEYCODE_DPAD_LEFT
        val right = k == KeyEvent.KEYCODE_DPAD_RIGHT
        val menu = k == KeyEvent.KEYCODE_MENU

        if (mode == Mode.SETTINGS) {
            if (back) {
                closeSettings()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        if (chans.isEmpty()) {
            if (ok) {
                loadList()
                return true
            }
            if (left || menu) {
                openSettings()
                return true
            }
            if (back) {
                finish()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        when (mode) {
            Mode.WATCH -> {
                val digit = digitOf(k)
                when {
                    digit >= 0 -> {
                        numBuf += digit.toString()
                        numView.text = numBuf
                        numView.visibility = View.VISIBLE
                        handler.removeCallbacks(numRun)
                        handler.postDelayed(numRun, 2000)
                    }
                    up -> zap(-1)
                    down -> zap(1)
                    left -> openDrawer()
                    ok -> showBar()
                    menu -> openSettings()
                    back -> finish()
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }

            Mode.DRAWER -> {
                when {
                    up -> moveDrawer(-1)
                    down -> moveDrawer(1)
                    ok -> {
                        if (e.repeatCount == 0) {
                            okDown = true
                            favDone = false
                            handler.removeCallbacks(favRun)
                            handler.postDelayed(favRun, 700)
                        }
                    }
                    left -> openGroups()
                    right || back -> setMode(Mode.WATCH)
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }

            Mode.GROUPS -> {
                when {
                    up -> moveGroup(-1)
                    down -> moveGroup(1)
                    ok || right -> chooseGroup()
                    back -> setMode(Mode.DRAWER)
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }

            Mode.SETTINGS -> return super.dispatchKeyEvent(e)
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    // ================= Vòng đời =================
    override fun onStart() {
        super.onStart()
        if (cur >= 0) exo.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        exo.playWhenReady = false
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopServer()
        exo.release()
        super.onDestroy()
    }
}
