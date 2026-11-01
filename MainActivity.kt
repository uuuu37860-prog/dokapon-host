package com.bom.dokapon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {

    private val cBg = Color.parseColor("#14122B")
    private val cCard = Color.parseColor("#201D42")
    private val cKey = Color.parseColor("#2C2859")
    private val cGold = Color.parseColor("#F2C14E")
    private val cInk = Color.parseColor("#F3EFE6")
    private val cDim = Color.parseColor("#9A96C0")
    private val cDark = Color.parseColor("#1B1630")

    private lateinit var info: TextView
    private lateinit var biosInfo: TextView
    private lateinit var startBtn: TextView
    private lateinit var roomText: TextView
    private lateinit var urlEdit: EditText
    private lateinit var hostSection: LinearLayout
    private lateinit var playerSection: LinearLayout
    private lateinit var tabHost: TextView
    private lateinit var tabPlayer: TextView

    private val pickGame =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importFiles(uris, File(filesDir, "game"), clearFirst = true)
        }
    private val pickBios =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importFiles(uris, File(filesDir, "system"), clearFirst = false)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun prefs() = getSharedPreferences("dokapon", MODE_PRIVATE)

    // ---------- ห้อง ----------
    private fun newRoom(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..6).map { a[r.nextInt(a.length)] }.joinToString("")
    }

    private fun room(): String {
        var r = prefs().getString("room", null)
        if (r == null) {
            r = newRoom()
            prefs().edit().putString("room", r).apply()
        }
        return r
    }

    private fun pageUrl(): String {
        return prefs().getString("page", null) ?: "https://bom979229-boop.github.io/Dokapon/play.html"
    }

    // ---------- ชิ้นส่วน UI ----------
    private fun rounded(color: Int, r: Int, stroke: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(r).toFloat()
            setColor(color)
            if (stroke != null) setStroke(dp(1), stroke)
        }
    }

    private fun lp(topMargin: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { this.topMargin = dp(topMargin) }
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cCard, 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = lp(12)
        }
    }

    private fun heading(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cInk)
        }
    }

    private fun note(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(cDim)
            setPadding(0, dp(6), 0, 0)
        }
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) cDark else cInk)
            background = rounded(if (primary) cGold else cKey, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = lp(10)
            setOnClickListener { onClick() }
        }
    }

    private fun field(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(cDim)
            setTextColor(cInk)
            textSize = 16f
            setSingleLine(true)
            background = rounded(cKey, 12, Color.parseColor("#3B3672"))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(10)
        }
    }

    private fun selectTab(host: Boolean) {
        hostSection.visibility = if (host) android.view.View.VISIBLE else android.view.View.GONE
        playerSection.visibility = if (host) android.view.View.GONE else android.view.View.VISIBLE
        tabHost.background = rounded(if (host) cGold else cKey, 14)
        tabHost.setTextColor(if (host) cDark else cInk)
        tabPlayer.background = rounded(if (host) cKey else cGold, 14)
        tabPlayer.setTextColor(if (host) cInk else cDark)
        prefs().edit().putBoolean("tab_host", host).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = cBg
        window.navigationBarColor = cBg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }

        // ---------- ปก ----------
        val cover = FrameLayout(this).apply {
            background = rounded(cCard, 20)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170))
        }
        cover.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.cover)
                scaleType = ImageView.ScaleType.CENTER_CROP
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val coverText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        coverText.addView(TextView(this).apply {
            text = "Dokapon Online"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        coverText.addView(TextView(this).apply {
            text = "เล่นเกมกระดานกับเพื่อนผ่านมือถือ"
            textSize = 13f
            setTextColor(Color.parseColor("#E8E3FF"))
        })
        cover.addView(
            coverText,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { gravity = Gravity.BOTTOM or Gravity.START }
        )
        cover.addView(
            TextView(this).apply {
                text = "⚙"
                textSize = 20f
                setTextColor(Color.WHITE)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = rounded(Color.argb(120, 0, 0, 0), 18)
                setOnClickListener { showSettingsDialog(this@MainActivity) }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = dp(10)
                    rightMargin = dp(10)
                }
        )
        root.addView(cover)

        // ---------- แท็บ ----------
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(14)
        }
        tabHost = TextView(this).apply {
            text = "🎮 โฮสต์ (มีไฟล์เกม)"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectTab(true) }
        }
        tabPlayer = TextView(this).apply {
            text = "🕹 ผู้เล่น (เข้าห้องเพื่อน)"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { selectTab(false) }
        }
        tabs.addView(tabHost, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        tabs.addView(tabPlayer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        root.addView(tabs)

        // ---------- ส่วนโฮสต์ ----------
        hostSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val gameCard = card()
        gameCard.addView(heading("1. เกมของคุณ"))
        info = TextView(this).apply { textSize = 13f; setTextColor(cDim); setPadding(0, dp(6), 0, 0) }
        gameCard.addView(info)
        gameCard.addView(button("เลือกไฟล์เกม (.cue + .bin หรือ .bin)", false) { pickGame.launch(arrayOf("*/*")) })
        biosInfo = TextView(this).apply { textSize = 13f; setTextColor(cDim); setPadding(0, dp(12), 0, 0) }
        gameCard.addView(biosInfo)
        gameCard.addView(button("เลือก BIOS PS1 (ไม่บังคับ)", false) { pickBios.launch(arrayOf("*/*")) })
        startBtn = button("▶  เริ่มเกม", true) { startGame() }
        gameCard.addView(startBtn)
        hostSection.addView(gameCard)

        val roomCard = card()
        roomCard.addView(heading("2. ห้องสำหรับเพื่อน"))
        roomText = TextView(this).apply {
            textSize = 34f
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cGold)
            letterSpacing = 0.18f
            setPadding(0, dp(8), 0, 0)
            text = room()
            setOnClickListener { copyRoom() }
        }
        roomCard.addView(roomText)
        roomCard.addView(note("แตะรหัสเพื่อคัดลอก เพื่อนที่ติดตั้งแอปนี้ใส่รหัสนี้ในแท็บ \"ผู้เล่น\""))
        roomCard.addView(button("📨  แชร์รหัสห้อง", true) { shareRoom() })
        roomCard.addView(button("📦  ส่งแอปให้เพื่อน (ไฟล์ APK)", false) { shareApk() })
        roomCard.addView(button("🔄  สุ่มรหัสห้องใหม่", false) {
            prefs().edit().putString("room", newRoom()).apply()
            roomText.text = room()
        })
        roomCard.addView(note("ลิงก์หน้าเว็บสำหรับเพื่อนที่ไม่ลงแอป (เช่น iPhone)"))
        urlEdit = field("https://.../play.html").apply { setText(pageUrl()); textSize = 13f }
        roomCard.addView(urlEdit)
        hostSection.addView(roomCard)
        root.addView(hostSection)

        // ---------- ส่วนผู้เล่น ----------
        playerSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val joinCard = card()
        joinCard.addView(heading("เข้าห้องของเพื่อน"))
        joinCard.addView(note("ไม่ต้องมีไฟล์เกม แค่ใส่รหัสห้องที่เพื่อนให้มา"))
        val joinCode = field("รหัสห้อง 6 ตัว").apply {
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            setText(prefs().getString("pcode", "") ?: "")
        }
        val joinName = field("ชื่อของคุณ").apply { setText(prefs().getString("pname", "") ?: "") }
        joinCard.addView(joinCode)
        joinCard.addView(joinName)
        joinCard.addView(button("เข้าห้องเป็นผู้เล่น", true) {
            val code = joinCode.text.toString().trim().uppercase()
            val nm = joinName.text.toString().trim()
            if (code.length < 4) {
                Toast.makeText(this, "ใส่รหัสห้องก่อน", Toast.LENGTH_SHORT).show()
            } else {
                prefs().edit().putString("pname", nm).putString("pcode", code).apply()
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("room", code).putExtra("name", nm))
            }
        })
        playerSection.addView(joinCard)
        root.addView(playerSection)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        })
        selectTab(prefs().getBoolean("tab_host", true))
        refresh()
    }

    // ---------- แชร์ ----------
    private fun copyRoom() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("room", room()))
        Toast.makeText(this, "คัดลอกรหัสห้องแล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun shareRoom() {
        val url = urlEdit.text.toString().trim()
        prefs().edit().putString("page", url).apply()
        val sep = if (url.contains("?")) "&" else "?"
        val web = if (url.isNotEmpty()) "\nไม่ลงแอป (เช่น iPhone) เปิดลิงก์นี้: $url${sep}room=${room()}" else ""
        val text = "เล่น Dokapon ด้วยกัน!\nรหัสห้อง: ${room()}\nติดตั้งแอป Dokapon Host แล้วไปแท็บ \"ผู้เล่น\" ใส่รหัสนี้$web"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "แชร์รหัสห้อง"))
    }

    private fun shareApk() {
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val out = File(dir, "Dokapon-Host.apk")
            File(applicationInfo.sourceDir).copyTo(out, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "com.bom.dokapon.fileprovider", out)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "ส่งแอปให้เพื่อน"))
        } catch (e: Exception) {
            Toast.makeText(this, "ส่งแอปไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- ไฟล์เกม ----------
    private fun displayName(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
        return File(name ?: uri.lastPathSegment ?: "file.bin").name
    }

    private fun importFiles(uris: List<Uri>, dir: File, clearFirst: Boolean) {
        info.text = "กำลังคัดลอกไฟล์เข้าแอป อาจใช้เวลาหลายนาที อย่าปิดแอป…"
        startBtn.isEnabled = false
        Thread {
            var err: String? = null
            try {
                if (clearFirst) dir.deleteRecursively()
                dir.mkdirs()
                for (uri in uris) {
                    val out = File(dir, displayName(uri))
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("เปิดไฟล์ไม่ได้")
                    input.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                }
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            runOnUiThread {
                refresh()
                if (err != null) Toast.makeText(this, "คัดลอกไม่สำเร็จ: $err", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun cueOk(cue: File): Boolean {
        return try {
            val m = Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cue.readText()) ?: return false
            File(cue.parentFile, m.groupValues[1]).exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun findGame(): File? {
        val files = File(filesDir, "game").listFiles()?.filter { it.isFile } ?: return null
        for (ext in listOf("cue", "chd", "pbp", "m3u", "iso", "img", "bin")) {
            val f = files.firstOrNull { it.extension.equals(ext, ignoreCase = true) } ?: continue
            if (ext == "cue" && !cueOk(f)) continue // .cue ชี้ไปที่ไฟล์ .bin ที่ชื่อไม่ตรง ข้ามไปใช้ .bin ตรง ๆ
            return f
        }
        return null
    }

    private fun refresh() {
        val game = findGame()
        val gameFiles = File(filesDir, "game").listFiles()?.filter { it.isFile }.orEmpty()
        val bios = File(filesDir, "system").listFiles()?.filter { it.isFile }.orEmpty()
        info.text = if (gameFiles.isEmpty()) {
            "ยังไม่ได้เลือกไฟล์เกม"
        } else {
            "เกม: " + gameFiles.joinToString { "${it.name} (${it.length() / 1048576} MB)" }
        }
        biosInfo.text = "BIOS: " + if (bios.isEmpty()) "ไม่มี (ใช้ตัวจำลอง HLE)" else bios.joinToString { it.name }
        startBtn.isEnabled = game != null
        startBtn.alpha = if (game != null) 1f else 0.45f
    }

    private fun startGame() {
        val game = findGame() ?: return
        startActivity(Intent(this, GameActivity::class.java).putExtra("game", game.absolutePath))
    }
}

/** หน้าตั้งค่า (ใช้ทั้งหน้าแรกและในเกม) ค่าเก็บใน SharedPreferences "dokapon" */
fun showSettingsDialog(a: AppCompatActivity) {
    val prefs = a.getSharedPreferences("dokapon", Context.MODE_PRIVATE)
    val density = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val box = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    fun label(t: String): TextView = TextView(a).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(14), 0, dp(4))
    }

    fun seek(title: String, key: String, lo: Int, hi: Int, def: Int, unit: String) {
        val tv = label("$title: ${prefs.getInt(key, def)}$unit")
        val bar = SeekBar(a)
        bar.max = hi - lo
        bar.progress = prefs.getInt(key, def) - lo
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$title: ${p + lo}$unit"
                prefs.edit().putInt(key, p + lo).apply()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(bar)
    }

    fun toggle(title: String, key: String, def: Boolean) {
        val sw = SwitchCompat(a)
        sw.text = title
        sw.isChecked = prefs.getBoolean(key, def)
        sw.setPadding(0, dp(10), 0, dp(10))
        sw.setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        box.addView(sw)
    }

    seek("ความเข้มของปุ่มบนจอ", "ui_alpha", 20, 100, 55, "%")
    seek("ขนาดปุ่มบนจอ", "ui_scale", 70, 140, 100, "%")
    toggle("สลับปุ่ม ✕ กับ ○ (ปุ่มบนจอของคุณ)", "swap_ab", false)
    toggle("โหมดประสิทธิภาพคงที่ (ช่วยลดอาการกระตุกเมื่อเครื่องร้อน)", "sustained", true)

    box.addView(label("สตรีม: เฟรมเรต (ลดลงถ้าเครื่องหน่วงตอนสตรีม)"))
    val fpsOptions = listOf(15, 24, 30)
    val group = RadioGroup(a)
    group.orientation = RadioGroup.HORIZONTAL
    fpsOptions.forEachIndexed { i, f ->
        val rb = RadioButton(a)
        rb.id = 1000 + i
        rb.text = "$f fps"
        rb.isChecked = prefs.getInt("stream_fps", 24) == f
        group.addView(rb)
    }
    group.setOnCheckedChangeListener { _, checkedId ->
        val idx = checkedId - 1000
        if (idx in fpsOptions.indices) prefs.edit().putInt("stream_fps", fpsOptions[idx]).apply()
    }
    box.addView(group)

    box.addView(label("การตั้งค่าปุ่มบนจอและสตรีมมีผลตอนเริ่มเกม/เริ่มสตรีมครั้งถัดไป"))

    AlertDialog.Builder(a)
        .setTitle("ตั้งค่า")
        .setView(ScrollView(a).apply { addView(box) })
        .setPositiveButton("ปิด", null)
        .show()
}


/** โหมดผู้เล่น: เปิดหน้าเล่นที่ฝังไว้ในแอปผ่าน WebView (ไม่ต้องพึ่งเว็บภายนอก) */
class PlayerActivity : AppCompatActivity() {
    private var web: WebView? = null

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val room = intent.getStringExtra("room") ?: ""
        val name = intent.getStringExtra("name") ?: ""

        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webChromeClient = WebChromeClient()
        web = w
        setContentView(w)

        val base = "https://dokapon.local/play.html?room=" + Uri.encode(room) +
            "&name=" + Uri.encode(name) + "&auto=1"
        w.loadDataWithBaseURL(base, PlayerPage.HTML, "text/html", "utf-8", null)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }
}

object PlayerPage {
    const val HTML = """<!doctype html>
<html lang="th">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, user-scalable=no">
<title>Dokapon Online — ผู้เล่น</title>
<style>
:root{
  --bg:#14122b; --panel:#201d42; --key:#2c2859; --ink:#f3efe6; --dim:#9a96c0;
  --tri:#4fb286; --cir:#e4586b; --crs:#5b7fe8; --sqr:#d88ac2; --gold:#f2c14e;
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none;touch-action:none}
html,body{margin:0;height:100%;background:var(--bg);color:var(--ink);overflow:hidden;
  font-family:ui-rounded,"Hiragino Maru Gothic ProN","Noto Sans Thai","Sarabun",system-ui,sans-serif}
input{-webkit-user-select:text;user-select:text;touch-action:auto}
.hidden{display:none!important}

/* ---------- หน้าเข้าห้อง ---------- */
#join{height:100%;display:flex;align-items:center;justify-content:center;padding:24px}
#join form{width:min(380px,100%);display:grid;gap:14px}
#join h1{margin:0 0 4px;font-size:30px;line-height:1.15;letter-spacing:.01em}
#join p{margin:0;color:var(--dim);font-size:15px;line-height:1.5}
label{display:grid;gap:6px;font-size:14px;color:var(--dim)}
input{font:inherit;font-size:20px;color:var(--ink);background:var(--panel);border:2px solid var(--key);
  border-radius:14px;padding:12px 14px;outline:none}
input:focus-visible{border-color:var(--gold)}
#code{letter-spacing:.3em;text-transform:uppercase;text-align:center;font-weight:700}
button.primary{font:inherit;font-size:18px;font-weight:700;color:#1b1630;background:var(--gold);
  border:0;border-radius:14px;padding:14px;touch-action:manipulation}
button.primary:active{transform:translateY(1px)}
#err{color:#ff8fa0;font-size:15px;min-height:1.3em}

/* ---------- หน้าเล่น ---------- */
#game{height:100%;display:flex;flex-direction:column}
.stage{flex:1;min-height:0;background:#000;position:relative}
video{width:100%;height:100%;object-fit:contain;background:#000}
#status{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
  text-align:center;padding:20px;color:var(--dim);font-size:16px;line-height:1.5;pointer-events:none}
#badge{position:absolute;top:calc(8px + env(safe-area-inset-top));left:10px;font-size:13px;font-weight:700;
  color:#1b1630;background:var(--gold);padding:4px 10px;border-radius:999px;pointer-events:none}
#sound{position:absolute;top:calc(8px + env(safe-area-inset-top));right:10px;font:inherit;font-size:13px;
  color:var(--ink);background:rgba(32,29,66,.9);border:1px solid var(--key);border-radius:999px;padding:5px 12px;touch-action:manipulation}

.pad{display:grid;grid-template-columns:1fr auto 1fr;align-items:end;gap:10px;
  padding:10px 14px calc(12px + env(safe-area-inset-bottom));background:var(--panel)}
.side{display:grid;gap:10px;justify-items:center}
.shoulders{display:flex;gap:10px;width:100%;justify-content:center}
.sh{flex:1;max-width:96px;height:38px;border-radius:12px;font-weight:700;font-size:15px}
.btn{display:flex;align-items:center;justify-content:center;color:var(--ink);background:var(--key);
  border:2px solid #3b3672;border-radius:14px;font-weight:700;touch-action:none}
.btn.on{background:var(--gold);color:#1b1630;border-color:var(--gold);transform:scale(.95)}

.dpad{display:grid;grid-template-columns:repeat(3,54px);grid-template-rows:repeat(3,54px);gap:4px}
.dpad .btn{font-size:22px;border-radius:12px}
.u{grid-area:1/2}.l{grid-area:2/1}.r{grid-area:2/3}.d{grid-area:3/2}
.face{display:grid;grid-template-columns:repeat(3,54px);grid-template-rows:repeat(3,54px);gap:4px}
.face .btn{border-radius:50%;font-size:24px;border-width:3px}
.f-tri{grid-area:1/2;border-color:var(--tri);color:var(--tri)}
.f-sqr{grid-area:2/1;border-color:var(--sqr);color:var(--sqr)}
.f-cir{grid-area:2/3;border-color:var(--cir);color:var(--cir)}
.f-crs{grid-area:3/2;border-color:var(--crs);color:var(--crs)}
.face .btn.on{color:#1b1630}
.mid{display:grid;gap:8px;padding-bottom:6px}
.mid .btn{width:76px;height:34px;font-size:13px;border-radius:999px}

/* แนวนอน: ปุ่มลอยทับภาพ */
@media (orientation:landscape) and (max-height:560px){
  #game{position:relative}
  .stage{position:absolute;inset:0}
  .pad{position:absolute;left:0;right:0;bottom:0;background:none;pointer-events:none;
    padding:0 calc(16px + env(safe-area-inset-right)) calc(10px + env(safe-area-inset-bottom)) calc(16px + env(safe-area-inset-left))}
  .pad .btn{pointer-events:auto;opacity:.6;background:rgba(32,29,66,.85)}
  .pad .btn.on{opacity:.95;background:var(--gold)}
  .dpad,.face{grid-template-columns:repeat(3,48px);grid-template-rows:repeat(3,48px)}
  .sh{height:32px}
}
</style>
</head>
<body>

<div id="join">
  <form id="joinForm" autocomplete="off">
    <h1>เข้าห้อง Dokapon</h1>
    <p>ใส่รหัสห้องที่เพื่อนส่งให้ แล้วกดเข้าห้อง จอเกมจะขึ้นมาทันทีที่โฮสต์เริ่มสตรีม</p>
    <label>รหัสห้อง<input id="code" maxlength="6" inputmode="text" autocapitalize="characters" required></label>
    <label>ชื่อของคุณ<input id="name" maxlength="20" placeholder="เช่น Bom"></label>
    <button class="primary" id="joinBtn" type="submit">เข้าห้อง</button>
    <div id="err" role="alert"></div>
  </form>
</div>

<div id="game" class="hidden">
  <div class="stage">
    <video id="v" autoplay playsinline muted></video>
    <div id="status">กำลังรอโฮสต์เริ่มสตรีม…</div>
    <div id="badge"></div>
    <button id="sound" class="hidden">แตะเพื่อเปิดเสียง</button>
  </div>
  <div class="pad">
    <div class="side">
      <div class="shoulders">
        <div class="btn sh" data-b="12">L2</div>
        <div class="btn sh" data-b="10">L1</div>
      </div>
      <div class="dpad">
        <div class="btn u" data-b="4">▲</div>
        <div class="btn l" data-b="6">◀</div>
        <div class="btn r" data-b="7">▶</div>
        <div class="btn d" data-b="5">▼</div>
      </div>
    </div>
    <div class="mid">
      <div class="btn" data-b="2">Select</div>
      <div class="btn" data-b="3">Start</div>
    </div>
    <div class="side">
      <div class="shoulders">
        <div class="btn sh" data-b="11">R1</div>
        <div class="btn sh" data-b="13">R2</div>
      </div>
      <div class="face">
        <div class="btn f-tri" data-b="9">△</div>
        <div class="btn f-sqr" data-b="1">□</div>
        <div class="btn f-cir" data-b="8">○</div>
        <div class="btn f-crs" data-b="0">✕</div>
      </div>
    </div>
  </div>
</div>

<script>
// หน้าเพื่อนสำหรับโหมด "โฮสต์เป็นแอปบนมือถือ"
// ใช้เซิร์ฟเวอร์ฟรีของ PeerJS เป็นแค่ตัวส่งต่อข้อความจับคู่ ภาพวิ่งตรงมาจากมือถือโฮสต์ด้วย WebRTC
// รหัสปุ่มแบบ RetroPad: 0=✕ 1=□ 2=Select 3=Start 4=ขึ้น 5=ลง 6=ซ้าย 7=ขวา 8=○ 9=△ 10=L1 11=R1 12=L2 13=R2
const SIGNAL = 'wss://0.peerjs.com/peerjs?key=peerjs';
const ICE = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'turn:eu-0.turn.peerjs.com:3478', username: 'peerjs', credential: 'peerjsp' },
];
const ${'$'} = (id) => document.getElementById(id);
const v = ${'$'}('v');
let ws = null, pc = null, dc = null, hb = null, hostId = '', joined = false, chain = Promise.resolve(), pending = [], wl = null;

const params = new URLSearchParams(location.search);
if (params.get('room')) ${'$'}('code').value = params.get('room').toUpperCase();
try { ${'$'}('name').value = localStorage.getItem('dokapon-name') || ''; } catch (e) {}

function rnd(n) {
  const a = 'abcdefghijklmnopqrstuvwxyz0123456789';
  return Array.from(crypto.getRandomValues(new Uint8Array(n)), (x) => a[x % a.length]).join('');
}
function send(type, payload) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify({ type, dst: hostId, payload }));
}
function setStatus(t) { ${'$'}('status').textContent = t; ${'$'}('status').classList.toggle('hidden', !t); }
async function keepAwake() { try { if ('wakeLock' in navigator) wl = await navigator.wakeLock.request('screen'); } catch (e) {} }

${'$'}('joinForm').addEventListener('submit', (e) => {
  e.preventDefault();
  ${'$'}('err').textContent = '';
  const room = ${'$'}('code').value.trim().toUpperCase();
  const name = ${'$'}('name').value.trim();
  if (!room) return;
  try { localStorage.setItem('dokapon-name', name); } catch (e2) {}
  hostId = 'dokapon-' + room;
  ${'$'}('joinBtn').disabled = true;
  ${'$'}('joinBtn').textContent = 'กำลังเชื่อมต่อ…';

  ws = new WebSocket(SIGNAL + '&id=friend-' + rnd(10) + '&token=' + rnd(12));
  ws.onopen = () => { hb = setInterval(() => { if (ws.readyState === 1) ws.send('{"type":"HEARTBEAT"}'); }, 5000); };
  ws.onmessage = (ev) => { try { onMsg(JSON.parse(ev.data), name); } catch (e3) { console.error(e3); } };
  ws.onclose = () => { clearInterval(hb); if (joined) fail('หลุดการเชื่อมต่อ'); };
  ws.onerror = () => fail('เชื่อมต่อเซิร์ฟเวอร์จับคู่ไม่ได้');
  setTimeout(() => { if (!joined) fail('เข้าห้องไม่สำเร็จ ตรวจรหัสห้อง และตรวจว่าโฮสต์เปิดสตรีมแล้ว'); }, 20000);
});

function onMsg(m, name) {
  const p = m.payload || {};
  if (m.type === 'OPEN') send('OFFER', { t: 'join', name });
  else if (m.type === 'ID-TAKEN') fail('เชื่อมต่อชนกัน ลองใหม่อีกครั้ง');
  else if (m.type === 'EXPIRE' && !joined) fail('ไม่พบห้องนี้ ตรวจรหัสห้อง หรือโฮสต์ยังไม่ได้เปิดสตรีม');
  else if (m.type === 'ERROR') fail('เซิร์ฟเวอร์จับคู่แจ้งข้อผิดพลาด');
  else if (m.type === 'ANSWER' && p.t === 'joined') showGame(p.slot);
  else if (m.type === 'ANSWER' && p.t === 'full') fail('ห้องเต็มแล้ว');
  else if (m.type === 'OFFER' && p.t === 'sdp') chain = chain.then(() => onOffer(p)).catch(console.error);
  else if (m.type === 'CANDIDATE' && p.t === 'ice') {
    const c = { candidate: p.candidate, sdpMid: p.sdpMid, sdpMLineIndex: p.sdpMLineIndex };
    if (pc && pc.remoteDescription) pc.addIceCandidate(c).catch(() => {});
    else pending.push(c);
  }
}

function showGame(slot) {
  joined = true;
  ${'$'}('join').classList.add('hidden');
  ${'$'}('game').classList.remove('hidden');
  ${'$'}('badge').textContent = 'ผู้เล่น ' + (slot + 1);
  setStatus('กำลังรอภาพจากโฮสต์…');
  keepAwake();
}

function fail(msg) {
  if (!${'$'}('game').classList.contains('hidden')) {
    setStatus(msg + ' (แตะที่จอเพื่อโหลดใหม่)');
    ${'$'}('status').style.pointerEvents = 'auto';
    ${'$'}('status').onclick = () => location.reload();
  } else {
    ${'$'}('err').textContent = msg;
    ${'$'}('joinBtn').disabled = false;
    ${'$'}('joinBtn').textContent = 'เข้าห้อง';
    try { if (ws) ws.close(); } catch (e) {}
  }
}

async function onOffer(p) {
  if (pc) pc.close();
  pc = new RTCPeerConnection({ iceServers: ICE });
  pc.ondatachannel = (e) => { dc = e.channel; };
  pc.ontrack = (e) => {
    v.srcObject = e.streams[0] || new MediaStream([e.track]);
    try { if ('playoutDelayHint' in e.receiver) e.receiver.playoutDelayHint = 0; } catch (e1) {}
    try { if ('jitterBufferTarget' in e.receiver) e.receiver.jitterBufferTarget = 0; } catch (e2) {}
    v.muted = true;
    v.play().catch(() => {});
    setStatus('');
  };
  pc.onicecandidate = (e) => {
    if (e.candidate) send('CANDIDATE', { t: 'ice', candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
  };
  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'failed') setStatus('เชื่อมต่อภาพไม่สำเร็จ ลองสลับ Wi-Fi/เน็ตมือถือ');
    else if (pc.connectionState === 'disconnected') setStatus('สัญญาณสะดุด กำลังพยายามต่อใหม่…');
    else if (pc.connectionState === 'connected') setStatus('');
  };
  await pc.setRemoteDescription({ type: 'offer', sdp: p.sdp });
  for (const c of pending.splice(0)) await pc.addIceCandidate(c).catch(() => {});
  const ans = await pc.createAnswer();
  await pc.setLocalDescription(ans);
  send('ANSWER', { t: 'sdp', type: 'answer', sdp: pc.localDescription.sdp });
}

// ---------- ปุ่มควบคุม ----------
const held = new Set();
function setBtn(b, down) {
  if (down === held.has(b)) return;
  down ? held.add(b) : held.delete(b);
  if (dc && dc.readyState === 'open') dc.send(JSON.stringify({ b, d: down ? 1 : 0 }));
}
document.querySelectorAll('[data-b]').forEach((el) => {
  const b = Number(el.dataset.b);
  const release = () => { el.classList.remove('on'); setBtn(b, false); };
  el.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    try { el.setPointerCapture(e.pointerId); } catch (e1) {}
    el.classList.add('on');
    setBtn(b, true);
    if (navigator.vibrate) navigator.vibrate(8);
  });
  el.addEventListener('pointerup', release);
  el.addEventListener('pointercancel', release);
  el.addEventListener('lostpointercapture', release);
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) [...held].forEach((b) => setBtn(b, false));
  else if (joined) keepAwake();
});
document.addEventListener('contextmenu', (e) => e.preventDefault());
${'$'}('sound').addEventListener('click', () => { v.muted = false; v.play(); ${'$'}('sound').classList.add('hidden'); });

// โหมดเข้าห้องอัตโนมัติ (ใช้ตอนเปิดจากในแอป)
if (params.get('auto') === '1' && params.get('room')) {
  if (params.get('name')) ${'$'}('name').value = params.get('name');
  setTimeout(() => { ${'$'}('joinForm').dispatchEvent(new Event('submit', { cancelable: true })); }, 400);
}

const KEYS = { ArrowUp: 4, ArrowDown: 5, ArrowLeft: 6, ArrowRight: 7, z: 0, x: 8, a: 1, s: 9, q: 10, w: 11, Enter: 3, Shift: 2 };
addEventListener('keydown', (e) => { if (e.target.tagName === 'INPUT' || e.repeat) return; const b = KEYS[e.key]; if (b !== undefined) { e.preventDefault(); setBtn(b, true); } });
addEventListener('keyup', (e) => { const b = KEYS[e.key]; if (b !== undefined) setBtn(b, false); });
</script>
</body>
</html>
"""
}
