package com.bom.dokapon

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var info: TextView
    private lateinit var startBtn: Button

    private val pickGame =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importFiles(uris, File(filesDir, "game"), clearFirst = true)
        }
    private val pickBios =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importFiles(uris, File(filesDir, "system"), clearFirst = false)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        root.addView(TextView(this).apply { text = "Dokapon Host"; textSize = 26f; setTextColor(Color.BLACK) })
        root.addView(TextView(this).apply {
            text = "รันเกมบนเครื่องนี้แบบเนทีฟ (ขั้นที่ 1: ทดสอบความเร็ว)"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        root.addView(Button(this).apply {
            text = "1. เลือกไฟล์เกม (เลือก .cue และ .bin พร้อมกัน หรือเลือก .bin อย่างเดียว)"
            setOnClickListener { pickGame.launch(arrayOf("*/*")) }
        })
        root.addView(Button(this).apply {
            text = "2. เลือก BIOS PS1 (ไม่บังคับ)"
            setOnClickListener { pickBios.launch(arrayOf("*/*")) }
        })
        startBtn = Button(this).apply {
            text = "3. เริ่มเกม"
            setOnClickListener { startGame() }
        }
        root.addView(startBtn)

        info = TextView(this).apply { textSize = 14f; setPadding(0, dp(16), 0, 0) }
        root.addView(info)

        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

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
        val sb = StringBuilder()
        sb.append("ไฟล์เกม: ")
        if (gameFiles.isEmpty()) sb.append("ยังไม่ได้เลือก\n")
        else sb.append(gameFiles.joinToString { "${it.name} (${it.length() / 1048576} MB)" }).append('\n')
        sb.append("จะเปิด: ").append(game?.name ?: "-").append('\n')
        sb.append("BIOS: ").append(if (bios.isEmpty()) "ไม่มี (ใช้ตัวจำลอง HLE)" else bios.joinToString { it.name })
        info.text = sb.toString()
        startBtn.isEnabled = game != null
    }

    private fun startGame() {
        val game = findGame() ?: return
        startActivity(Intent(this, GameActivity::class.java).putExtra("game", game.absolutePath))
    }
}
