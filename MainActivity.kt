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
    private lateinit var hostSection: LinearLayout
    private lateinit var playerSection: LinearLayout
    private lateinit var tabHost: TextView
    private lateinit var tabPlayer: TextView
    private lateinit var joinCode: EditText
    private lateinit var joinName: EditText
    private lateinit var lobbyBox: LinearLayout
    private var lobby: LobbyWatcher? = null

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
        roomCard.addView(note("เริ่มเกมแล้วห้องจะเปิดให้เพื่อนเข้าทันที แตะรหัสเพื่อคัดลอก เพื่อนที่ติดตั้งแอปนี้ใส่รหัสนี้ในแท็บ \"ผู้เล่น\""))
        roomCard.addView(button("📨  แชร์รหัสห้อง", true) { shareRoom() })
        roomCard.addView(button("📦  ส่งแอปให้เพื่อน (ไฟล์ APK)", false) { shareApk() })
        roomCard.addView(button("🔄  สุ่มรหัสห้องใหม่", false) {
            prefs().edit().putString("room", newRoom()).apply()
            roomText.text = room()
        })
        hostSection.addView(roomCard)
        root.addView(hostSection)

        // ---------- ส่วนผู้เล่น ----------
        playerSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val joinCard = card()
        joinCard.addView(heading("เข้าห้องของเพื่อน"))
        joinCard.addView(note("ไม่ต้องมีไฟล์เกม แค่ใส่รหัสห้องที่เพื่อนให้มา"))
        joinCode = field("รหัสห้อง 6 ตัว").apply {
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(6))
            setText(prefs().getString("pcode", "") ?: "")
        }
        joinName = field("ชื่อของคุณ").apply { setText(prefs().getString("pname", "") ?: "") }
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

        val lobbyCard = card()
        lobbyCard.addView(heading("ห้องที่เปิดอยู่"))
        lobbyCard.addView(note("แตะห้องเพื่อขอเข้าเล่น (โฮสต์ต้องกดยอมรับ)"))
        lobbyBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lobbyCard.addView(lobbyBox)
        playerSection.addView(lobbyCard)
        root.addView(playerSection)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        })
        selectTab(prefs().getBoolean("tab_host", true))
        refresh()
    }

    // ---------- ล็อบบี้ (รายชื่อห้องที่เปิดอยู่) ----------
    override fun onResume() {
        super.onResume()
        startLobby()
    }

    override fun onPause() {
        lobby?.stop()
        lobby = null
        super.onPause()
    }

    private fun startLobby() {
        lobby?.stop()
        lobbyBox.removeAllViews()
        lobbyBox.addView(note("กำลังค้นหาห้อง…"))
        val w = LobbyWatcher(this) { rooms -> renderLobby(rooms) }
        lobby = w
        w.start()
    }

    private fun renderLobby(rooms: List<LobbyRoom>) {
        lobbyBox.removeAllViews()
        if (rooms.isEmpty()) {
            lobbyBox.addView(note("ยังไม่มีห้องที่เปิดอยู่ (ให้โฮสต์เริ่มเกมก่อน)"))
            return
        }
        for (r in rooms) {
            lobbyBox.addView(button("🎮 ${r.name} · ${r.players}/4 คน · รหัส ${r.room}", false) {
                val nm = joinName.text.toString().trim().ifBlank { "ผู้เล่น" }
                prefs().edit().putString("pname", nm).putString("pcode", r.room).apply()
                startActivity(Intent(this, PlayerActivity::class.java).putExtra("room", r.room).putExtra("name", nm))
            })
        }
    }

    // ---------- แชร์ ----------
    private fun copyRoom() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("room", room()))
        Toast.makeText(this, "คัดลอกรหัสห้องแล้ว", Toast.LENGTH_SHORT).show()
    }

    private fun shareRoom() {
        val text = "เล่น Dokapon ด้วยกัน!\nรหัสห้อง: ${room()}\nติดตั้งแอป Dokapon Host แล้วไปแท็บ \"ผู้เล่น\" ใส่รหัสนี้ (ไม่ต้องมีไฟล์เกม)"
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
    toggle("เปิดห้องให้เพื่อนเข้าอัตโนมัติเมื่อเริ่มเกม", "auto_room", true)
    toggle("ยอมรับเพื่อนอัตโนมัติ (ไม่ต้องกดยอมรับทุกครั้ง)", "auto_accept", false)

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

    box.addView(label("ผู้เล่นหลายคน (Multitap): ถ้าเพื่อนคนที่ 3–4 ขยับไม่ได้ ให้เลือก \"เปิด\""))
    val mtValues = listOf("auto", "enabled", "disabled")
    val mtLabels = listOf("อัตโนมัติ", "เปิด (3–4 คน)", "ปิด (2 คน)")
    val mtGroup = RadioGroup(a)
    mtGroup.orientation = RadioGroup.VERTICAL
    mtValues.forEachIndexed { i, value ->
        val rb = RadioButton(a)
        rb.id = 2000 + i
        rb.text = mtLabels[i]
        rb.isChecked = (prefs.getString("multitap", "auto") ?: "auto") == value
        mtGroup.addView(rb)
    }
    mtGroup.setOnCheckedChangeListener { _, checkedId ->
        val idx = checkedId - 2000
        if (idx in mtValues.indices) prefs.edit().putString("multitap", mtValues[idx]).apply()
    }
    box.addView(mtGroup)

    toggle("แสดงห้องของฉันในรายชื่อห้อง (คนในแอปเห็นและขอเข้าได้)", "public_room", true)

    box.addView(label("เซิร์ฟเวอร์จับคู่ (โฮสต์กับเพื่อนต้องเลือกให้ตรงกัน)"))
    val brokerLabels = listOf("EMQX (แนะนำ)", "HiveMQ", "Mosquitto")
    val bGroup = RadioGroup(a)
    bGroup.orientation = RadioGroup.VERTICAL
    brokerLabels.forEachIndexed { i, l ->
        val rb = RadioButton(a)
        rb.id = 3000 + i
        rb.text = l
        rb.isChecked = prefs.getInt("broker", 0) == i
        bGroup.addView(rb)
    }
    bGroup.setOnCheckedChangeListener { _, checkedId ->
        val idx = checkedId - 3000
        if (idx in brokerLabels.indices) prefs.edit().putInt("broker", idx).apply()
    }
    box.addView(bGroup)

    box.addView(label("การตั้งค่าปุ่มบนจอ สตรีม Multitap และเซิร์ฟเวอร์ มีผลตอนเริ่มเกม/เข้าห้องครั้งถัดไป"))

    AlertDialog.Builder(a)
        .setTitle("ตั้งค่า")
        .setView(ScrollView(a).apply { addView(box) })
        .setPositiveButton("ปิด", null)
        .show()
}
