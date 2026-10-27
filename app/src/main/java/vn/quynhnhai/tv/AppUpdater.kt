package vn.quynhnhai.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class QTVFileProvider : FileProvider()

/** Manual update flow: public GitHub release -> confirmed download -> Android installer. */
class AppUpdater(private val activity: Activity) {
    private val busy = AtomicBoolean(false)
    private var awaitingPermission = false
    private val apk get() = File(activity.cacheDir, "updates/qtv-update.apk")
    private val api = "https://api.github.com/repos/bplus6688/QTV/releases/latest"

    private fun ui(action: () -> Unit) {
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    private fun message(text: String) = ui {
        AlertDialog.Builder(activity).setTitle("Cập nhật QTV")
            .setMessage(text).setPositiveButton("Đóng", null).show()
    }

    fun check() {
        if (!busy.compareAndSet(false, true)) {
            message("Đang kiểm tra hoặc tải bản cập nhật. Vui lòng đợi.")
            return
        }
        val progress = AlertDialog.Builder(activity).setTitle("Cập nhật QTV")
            .setMessage("Đang kiểm tra phiên bản mới…").setCancelable(false).show()
        thread {
            try {
                val c = URL(api).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "QTV-Android")
                val release = try {
                    if (c.responseCode == 404) throw Exception("Chưa có bản cập nhật công khai trên GitHub Releases.")
                    if (c.responseCode == 403 || c.responseCode == 429)
                        throw Exception("GitHub đang giới hạn lượt kiểm tra. Anh thử lại sau.")
                    if (c.responseCode != 200) throw Exception("Không kiểm tra được cập nhật (HTTP ${c.responseCode}).")
                    JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } finally { c.disconnect() }
                val assets = release.getJSONArray("assets")
                val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .filter { it.getString("name").endsWith(".apk", ignoreCase = true) }
                if (urls.size != 1) throw Exception("Bản phát hành cần có đúng một file APK dùng chung cho Android TV.")
                val url = urls[0].getString("browser_download_url")
                if (!url.startsWith("https://github.com/bplus6688/QTV/releases/download/"))
                    throw Exception("Đường dẫn bản cập nhật không hợp lệ.")
                val tag = release.getString("tag_name")
                @Suppress("DEPRECATION")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                ui {
                    progress.dismiss()
                    AlertDialog.Builder(activity).setTitle("Bản phát hành $tag")
                        .setMessage("Đang cài: ${installed.versionName}\n\nTải APK để kiểm tra phiên bản và cập nhật? Nguồn: bplus6688/QTV.")
                        .setNegativeButton("Để sau", null)
                        .setPositiveButton("Tải bản cập nhật") { _, _ -> download(url) }.show()
                }
            } catch (e: Exception) {
                ui { progress.dismiss() }
                message(e.message ?: "Không kết nối được máy chủ cập nhật.")
            } finally { busy.set(false) }
        }
    }

    private fun download(url: String) {
        if (!busy.compareAndSet(false, true)) return
        val progress = AlertDialog.Builder(activity).setTitle("Cập nhật QTV")
            .setMessage("Đang tải APK…").setCancelable(false).show()
        thread {
            val temp = File(activity.cacheDir, "updates/qtv-update.part")
            try {
                temp.parentFile?.mkdirs()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                try {
                    if (c.responseCode != 200) throw Exception("Tải APK thất bại (HTTP ${c.responseCode}).")
                    c.inputStream.use { input -> temp.outputStream().use { output ->
                        val buffer = ByteArray(32768)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > 100L * 1024 * 1024) throw Exception("APK vượt quá giới hạn 100 MB.")
                            output.write(buffer, 0, n)
                        }
                    } }
                } finally { c.disconnect() }
                @Suppress("DEPRECATION")
                val candidate = activity.packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
                    ?: throw Exception("File tải về không phải APK hợp lệ.")
                if (candidate.packageName != activity.packageName) throw Exception("APK không phải ứng dụng QTV này.")
                @Suppress("DEPRECATION")
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0)
                @Suppress("DEPRECATION")
                val candidateCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
                @Suppress("DEPRECATION")
                val installedCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
                if (candidateCode <= installedCode) throw Exception("Ứng dụng đang ở phiên bản này hoặc mới hơn. Không cần cập nhật.")
                if (apk.exists() && !apk.delete()) throw Exception("Không xóa được bản tải trước.")
                if (!temp.renameTo(apk)) throw Exception("Không lưu được APK cập nhật.")
                ui {
                    progress.dismiss()
                    AlertDialog.Builder(activity).setTitle("Đã tải phiên bản ${candidate.versionName}")
                        .setMessage("Tiếp tục mở trình cài đặt của Android? TV sẽ yêu cầu anh xác nhận cập nhật.")
                        .setNegativeButton("Để sau", null)
                        .setPositiveButton("Cài cập nhật") { _, _ -> install() }.show()
                }
            } catch (e: Exception) {
                temp.delete()
                ui { progress.dismiss() }
                message(e.message ?: "Không tải được APK.")
            } finally { busy.set(false) }
        }
    }

    private fun install() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity).setTitle("Cho phép cập nhật QTV")
                    .setMessage("Bật quyền cho QTV cài ứng dụng từ nguồn này, rồi quay lại. Android vẫn yêu cầu xác nhận trước khi cài.")
                    .setNegativeButton("Hủy", null)
                    .setPositiveButton("Mở cài đặt") { _, _ ->
                        try {
                            awaitingPermission = true
                            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")))
                        } catch (_: Exception) {
                            awaitingPermission = false
                            message("TV không hỗ trợ mở trang cấp quyền. Anh vào Cài đặt của TV để cấp quyền cài đặt cho QTV.")
                        }
                    }.show()
                return
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) { message("Không mở được trình cài đặt trên thiết bị này.") }
    }

    fun resumePendingInstall() {
        if (!awaitingPermission) return
        awaitingPermission = false
        if (Build.VERSION.SDK_INT >= 26 && activity.packageManager.canRequestPackageInstalls() && apk.exists())
            install()
    }
}
