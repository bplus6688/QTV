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
val C_TXT = 0xFFF6F9FD.toInt()
val C_MUT = 0xFFD4E0E8.toInt()
val C_AMB = 0xFFFF9D2E.toInt()
val C_AMB_TXT = 0xFFFFFFFF.toInt()
val C_DARK = 0xFF3A4350.toInt()

data class Chan(val name: String, val group: String, val url: String, val logo: String = "")

/** Một ô trong danh sách (kênh hoặc nhóm kênh). */
data class Tile(val lead: String, val title: String, val sub: String, val star: Boolean = false, val logo: String = "")

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

// Channel rows: translucent surface with an amber selection outline.
class TileAdapter(private val u: U, private val items: List<Tile>) :
    RecyclerView.Adapter<TileAdapter.VH>() {

    var selected = -1

    class VH(
        val root: LinearLayout,
        val lead: TextView,
        val logo: ChannelLogo,
        val title: TextView,
        val sub: TextView,
        val star: TextView
    ) : RecyclerView.ViewHolder(root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u.p(20), 0, u.p(20), 0)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u.p(96)
            ).apply { bottomMargin = u.p(8) }
        }
        val lead = mkText(ctx, u, 22, true).apply {
            layoutParams = LinearLayout.LayoutParams(u.p(42), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val logo = ChannelLogo(ctx, u).apply {
            layoutParams = LinearLayout.LayoutParams(u.p(88), u.p(64))
                .apply { marginEnd = u.p(16) }
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = mkText(ctx, u, 30, true).apply {
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
        root.addView(logo)
        root.addView(col)
        root.addView(star)
        return VH(root, lead, logo, title, sub, star)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, i: Int) {
        val t = items[i]
        val sel = i == selected
        h.lead.text = t.lead
        h.lead.visibility = if (t.lead.isEmpty()) View.GONE else View.VISIBLE
        h.logo.visibility = if (t.lead.isEmpty()) View.GONE else View.VISIBLE
        h.logo.bind(t.title, t.logo)
        h.title.text = t.title
        h.sub.text = t.sub
        h.sub.visibility = if (t.sub.isBlank()) View.GONE else View.VISIBLE
        h.star.visibility = if (t.star) View.VISIBLE else View.GONE
        h.root.background = if (sel) glass(u, 16).apply {
            setStroke(maxOf(2, u.p(3)), C_AMB)
        } else rounded(0x16FFFFFF, u.f(16))
        h.root.scaleX = 1f
        h.root.scaleY = 1f
        h.root.translationZ = if (sel) u.f(12) else 0f
        h.lead.setTextColor(if (sel) C_AMB else C_MUT)
        h.title.setTextColor(C_TXT)
        h.sub.setTextColor(if (sel) C_AMB else C_MUT)
        h.star.setTextColor(C_AMB)
    }
}

// Translucent gradient and fine highlight, supported on Android 6+.
fun glass(u: U, radius: Int = 22): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xA94E6878.toInt(), 0xB52A3B4B.toInt())).apply {
        cornerRadius = u.f(radius)
        setStroke(maxOf(1, u.p(2)), 0x70FFFFFF)
    }

enum class Mode { WATCH, DRAWER, GROUPS, SOURCE, PREFERENCES }

class MainActivity : Activity() {

    private lateinit var u: U
    private lateinit var root: FrameLayout
    private lateinit var pv: PlayerView
    private lateinit var exo: ExoPlayer

    // Thanh dưới: tên kênh (trái) + giờ, ngày (phải)
    private lateinit var bar: LinearLayout
    private lateinit var barLogo: ChannelLogo
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

    private lateinit var preferencesPanel: LinearLayout
    private val menuGears = mutableListOf<TextView>()
    private lateinit var updater: AppUpdater
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

        updater = AppUpdater(this)
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

    private fun mkRecycler(): RecyclerView = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@MainActivity)
        isFocusable = false
        overScrollMode = View.OVER_SCROLL_NEVER
        itemAnimator = null
        clipChildren = true
        clipToPadding = true
        setPadding(u.p(4), u.p(8), u.p(4), u.p(8))
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF263A52.toInt(), 0xFF111924.toInt(), 0xFF162D33.toInt()))
        }
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
            setPadding(u.p(32), u.p(22), u.p(32), u.p(22))
            background = glass(u)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
            ).apply { setMargins(u.p(48), 0, u.p(48), u.p(40)) }
        }
        val barLeft = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        barNum = mkText(this, u, 26, true).apply {
            gravity = Gravity.CENTER
            setTextColor(C_AMB_TXT)
            background = rounded(0x28FFFFFF, u.f(12))
            layoutParams = LinearLayout.LayoutParams(u.p(52), u.p(52))
        }
        val barCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = u.p(28) }
        }
        barName = mkText(this, u, 38, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        barGrp = mkText(this, u, 28).apply {
            setTextColor(C_MUT)
            setPadding(0, u.p(8), 0, 0)
        }
        barCol.addView(barName)
        barCol.addView(barGrp)
        barLogo = ChannelLogo(this, u)
        barLeft.addView(barLogo, LinearLayout.LayoutParams(u.p(150), u.p(94))
            .apply { marginEnd = u.p(20) })
        barLeft.addView(barNum)
        barLeft.addView(barCol)
        val barRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        barTime = mkText(this, u, 48, true).apply { gravity = Gravity.END }
        barDate = mkText(this, u, 24).apply {
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
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(dim)

        // Ngăn kéo chuyển kênh (bấm Trái khi đang xem)
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(u, 28)
            clipToOutline = true
            setPadding(u.p(24), u.p(32), u.p(24), u.p(16))
            clipChildren = true
            clipToPadding = true
            layoutParams = FrameLayout.LayoutParams(u.p(504), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(u.p(8), u.p(12), 0, u.p(12))
            }
        }
        drawerTitle = mkText(this, u, 30, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, u.p(18))
        }
        drawerRv = mkRecycler().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        drawer.addView(brandHeader(drawerTitle))
        drawer.addView(drawerRv)
        root.addView(drawer)

        // Bảng nhóm kênh (bấm Trái lần nữa)
        groupsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(u, 28)
            clipToOutline = true
            setPadding(u.p(20), u.p(32), u.p(20), u.p(16))
            clipChildren = true
            clipToPadding = true
            layoutParams = FrameLayout.LayoutParams(u.p(324), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setMargins(u.p(8), u.p(12), 0, u.p(12))
            }
        }
        val gTitle = mkText(this, u, 30, true).apply {
            text = "Nhóm kênh"
            setPadding(0, 0, 0, u.p(18))
        }
        groupsRv = mkRecycler().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        groupsPanel.addView(brandHeader(gTitle))
        groupsPanel.addView(groupsRv)
        root.addView(groupsPanel)

        // Màn hình đổi nguồn kênh (quét QR bằng điện thoại)
        settings = FrameLayout(this).apply {
            background = glass(u, 0)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val sTitle = mkText(this, u, 60, true).apply {
            text = "Nguồn kênh"
            layoutParams = lp(900, 80, 120, 140)
        }
        statusText = mkText(this, u, 30, true).apply {
            setTextColor(C_AMB)
            layoutParams = lp(900, 160, 120, 300)
        }
        qrImage = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = lp(560, 560, 1180, 140)
        }
        addrText = mkText(this, u, 26).apply {
            setTextColor(C_MUT)
            gravity = Gravity.CENTER
            layoutParams = lp(560, 120, 1180, 720)
        }
        settings.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "QTV"
            layoutParams = lp(140, 140, 120, 90)
        })
        sTitle.layoutParams = lp(760, 80, 290, 120)
        settings.addView(sTitle)
        val scanHelp = mkText(this, u, 32).apply {
            text = "Dùng camera điện thoại quét mã QR để thêm nguồn kênh.\n\nĐiện thoại và TV cần kết nối cùng mạng Wi-Fi.\n\nDán link playlist M3U rồi bấm “Gửi sang tivi”."
            setTextColor(C_TXT)
            setLineSpacing(0f, 1.15f)
            layoutParams = lp(900, 360, 120, 440)
        }
        settings.addView(scanHelp)
        settings.addView(statusText)
        settings.addView(qrImage)
        settings.addView(addrText)
        root.addView(settings)

        preferencesPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glass(u)
            setPadding(u.p(56), u.p(40), u.p(56), u.p(40))
            layoutParams = FrameLayout.LayoutParams(u.p(980), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER)
            visibility = View.GONE
        }
        preferencesPanel.addView(brandHeader(mkText(this, u, 46, true).apply { text = "Cài đặt" }, false))
        fun option(title: String, action: () -> Unit) {
            preferencesPanel.addView(mkText(this, u, 34, true).apply {
                text = title
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u.p(24), 0, u.p(24), 0)
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = rounded(0x16FFFFFF, u.f(16))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, u.p(92))
                    .apply { bottomMargin = u.p(16) }
                setOnFocusChangeListener { v, focus ->
                    v.background = glass(u, 16).apply {
                        if (focus) setStroke(maxOf(2, u.p(3)), C_AMB)
                    }
                }
                setOnClickListener { action() }
            })
        }
        option("Sửa nguồn kênh") { openSettings() }
        option("Cập nhật ứng dụng") { updater.check() }
        option("Người phát triển") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Người phát triển")
                .setMessage("Bản quyền thuộc QTV")
                .setPositiveButton("Đóng", null).show()
        }
        preferencesPanel.addView(mkText(this, u, 26).apply {
            text = "Bản quyền thuộc QTV"
            setTextColor(C_MUT)
            setPadding(0, u.p(12), 0, 0)
        })
        root.addView(preferencesPanel)
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
            setTextColor(Color.WHITE)
            setShadowLayer(12f, 0f, 2f, Color.BLACK)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        root.addView(msgView)
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    // ================= Chế độ màn hình =================
    private fun setMode(m: Mode) {
        mode = m
        menuGears.forEach { it.clearFocus() }
        val panels = m == Mode.DRAWER || m == Mode.GROUPS
        dim.visibility = if (panels) View.VISIBLE else View.GONE
        drawer.visibility = if (panels) View.VISIBLE else View.GONE
        drawer.translationX = if (m == Mode.GROUPS) u.f(340) else 0f
        groupsPanel.visibility = if (m == Mode.GROUPS) View.VISIBLE else View.GONE
        settings.visibility = if (m == Mode.SOURCE) View.VISIBLE else View.GONE
        preferencesPanel.visibility = if (m == Mode.PREFERENCES) View.VISIBLE else View.GONE
        if (m != Mode.WATCH) {
            handler.removeCallbacks(hideBarRun)
            handler.removeCallbacks(clockRun)
            bar.visibility = View.GONE
        }
        resizeVideo(when (m) {
            Mode.DRAWER -> u.p(520)
            Mode.GROUPS -> u.p(860)
            else -> 0
        })
        exo.volume = if (m == Mode.SOURCE || m == Mode.PREFERENCES) 0f else 1f
    }

    private fun openPreferences() {
        stopServer()
        say(null)
        setMode(Mode.PREFERENCES)
        preferencesPanel.getChildAt(1).requestFocus()
    }

    private fun resizeVideo(left: Int) {
        // One layout change: avoid repeatedly resizing the decoder surface during playback.
        val screenWidth = resources.displayMetrics.widthPixels
        val current = pv.layoutParams as FrameLayout.LayoutParams
        if (current.leftMargin == left && current.width == screenWidth - left) return
        pv.layoutParams = FrameLayout.LayoutParams(
            screenWidth - left, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { leftMargin = left }
        pv.requestLayout()
    }

    private fun brandHeader(title: TextView, showSettings: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, u.p(104)
        ).apply { bottomMargin = u.p(16) }
        if (showSettings) {
            val button = mkText(this@MainActivity, u, 46, true).apply {
                text = "⚙"
                contentDescription = "Cài đặt"
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                isClickable = true
                background = glass(u, 18)
                setOnFocusChangeListener { v, focused ->
                    v.background = glass(u, 18).apply {
                        if (focused) setStroke(maxOf(2, u.p(3)), C_AMB)
                    }
                }
                setOnClickListener { openPreferences() }
            }
            menuGears.add(button)
            addView(button, LinearLayout.LayoutParams(u.p(76), u.p(76))
                .apply { marginEnd = u.p(16) })
        }
        title.setPadding(0, 0, 0, 0)
        addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
        barLogo.bind(c.name, c.logo)
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
        setMode(Mode.SOURCE)
    }

    private fun closeSettings() {
        stopServer()
        openPreferences()
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
        var logo = ""
        val reLogo = Regex("""tvg-logo\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
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
                logo = (reLogo.find(l)?.groupValues?.get(1) ?: "").trim()
            } else if (!l.startsWith("#")) {
                out.add(Chan(name.ifEmpty { "Kênh ${out.size + 1}" }, grp, l, logo))
                name = ""
                grp = ""
                logo = ""
            }
        }
        return out
    }

    private fun onList(list: List<Chan>, hasUrl: Boolean) {
        chans = list
        cur = -1
        if (list.isEmpty()) {
            if (hasUrl) {
                say("Chưa có danh sách kênh")
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

    private fun center(rv: RecyclerView, pos: Int) {
        (rv.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(pos, maxOf(0, ((if (rv.height > 0) rv.height else u.p(950)) - u.p(96)) / 2))
    }

    private fun buildDrawer() {
        val tiles = if (visible.isEmpty()) {
            listOf(Tile("", "Chưa có kênh", ""))
        } else {
            visible.map { Tile((it + 1).toString(), chans[it].name, chans[it].group, favs.contains(chans[it].url), chans[it].logo) }
        }
        if (dsel >= visible.size) dsel = maxOf(0, visible.size - 1)
        drawerAdapter = TileAdapter(u, tiles).also { it.selected = if (visible.isEmpty()) -1 else dsel }
        drawerRv.adapter = drawerAdapter
        drawerTitle.text = if (groupFilter == 0) "Kênh" else "Kênh · " + groupTitle(groupFilter)
        if (visible.isNotEmpty()) center(drawerRv, dsel)
    }

    private fun buildGroups() {
        groupsAdapter = TileAdapter(u, groupTiles()).also { it.selected = gsel }
        groupsRv.adapter = groupsAdapter
        center(groupsRv, gsel)
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
        center(drawerRv, dsel)
    }

    private fun moveGroup(d: Int) {
        val total = 3 + groupNames.size
        gsel = (gsel + d + total) % total
        groupsAdapter?.let {
            it.selected = gsel
            it.notifyDataSetChanged()
        }
        center(groupsRv, gsel)
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
        exo.stop()
        exo.clearMediaItems()
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

        if (menu) {
            openPreferences()
            return true
        }
        if (mode == Mode.PREFERENCES) {
            if (back) {
                setMode(Mode.WATCH)
                root.requestFocus()
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (mode == Mode.SOURCE) {
            if (back) {
                closeSettings()
                return true
            }
            return super.dispatchKeyEvent(e)
        }

        if (menuGears.any { it.hasFocus() } && ok) {
            if (e.repeatCount == 0) openPreferences()
            return true
        }
        if (mode == Mode.WATCH && right) {
            openPreferences()
            return true
        }
        if (menuGears.any { it.hasFocus() } && (up || down)) {
            menuGears.forEach { it.clearFocus() }
            root.requestFocus()
            return true
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
                    ok -> openDrawer()
                    menu -> openPreferences()
                    back -> finish()
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }

            Mode.DRAWER -> {
                when {
                    up -> if (dsel == 0) menuGears[0].requestFocus() else moveDrawer(-1)
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
                    up -> if (gsel == 0) menuGears[1].requestFocus() else moveGroup(-1)
                    down -> moveGroup(1)
                    ok || right -> chooseGroup()
                    back -> setMode(Mode.DRAWER)
                    else -> return super.dispatchKeyEvent(e)
                }
                return true
            }

            Mode.SOURCE, Mode.PREFERENCES -> return super.dispatchKeyEvent(e)
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    // ================= Vòng đời =================
    override fun onResume() {
        super.onResume()
        updater.resumePendingInstall()
    }

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
