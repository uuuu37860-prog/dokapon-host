package com.bom.dokapon

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import java.io.File

class GameActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null

    // ถ้าในเกมปุ่ม ✕ กับ ○ ทำงานสลับกัน ให้เปลี่ยนเป็น true แล้ว build ใหม่
    private val swapAB = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val gamePath = intent.getStringExtra("game")
        if (gamePath == null) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        try {
            val data = GLRetroViewData(this).apply {
                coreFilePath = "libpcsx_rearmed_libretro_android.so"
                gameFilePath = gamePath
                systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
                savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
            }
            val view = GLRetroView(this, data)
            lifecycle.addObserver(view)
            retroView = view
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Throwable) {
            Toast.makeText(this, "เริ่มเกมไม่สำเร็จ: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildControls(root)
        setContentView(root)
    }

    private fun press(code: Int, down: Boolean) {
        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pad(label: String, code: Int, circle: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            alpha = 0.55f
            background = GradientDrawable().apply {
                shape = if (circle) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(110, 32, 32, 64))
                setStroke(dp(2), Color.argb(190, 255, 255, 255))
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 1f
                        press(code, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 0.55f
                        press(code, false)
                    }
                }
                true
            }
        }
    }

    private fun place(parent: FrameLayout, v: TextView, x: Int, y: Int, w: Int, h: Int) {
        parent.addView(v, FrameLayout.LayoutParams(w, h).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = x
            topMargin = y
        })
    }

    private fun buildControls(root: FrameLayout) {
        val cell = dp(54)
        val gap = dp(4)
        val step = cell + gap
        val size = cell * 3 + gap * 2
        val margin = dp(16)

        // ปุ่มทิศทาง (ล่างซ้าย)
        val dpad = FrameLayout(this)
        place(dpad, pad("▲", KeyEvent.KEYCODE_DPAD_UP), step, 0, cell, cell)
        place(dpad, pad("◀", KeyEvent.KEYCODE_DPAD_LEFT), 0, step, cell, cell)
        place(dpad, pad("▶", KeyEvent.KEYCODE_DPAD_RIGHT), step * 2, step, cell, cell)
        place(dpad, pad("▼", KeyEvent.KEYCODE_DPAD_DOWN), step, step * 2, cell, cell)
        root.addView(dpad, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = margin
            bottomMargin = margin
        })

        // ปุ่มกด △ □ ○ ✕ (ล่างขวา) รหัสปุ่มตามรูปแบบจอยทั่วไป: ✕=A ○=B □=X △=Y
        val cross = if (swapAB) KeyEvent.KEYCODE_BUTTON_B else KeyEvent.KEYCODE_BUTTON_A
        val circle = if (swapAB) KeyEvent.KEYCODE_BUTTON_A else KeyEvent.KEYCODE_BUTTON_B
        val face = FrameLayout(this)
        place(face, pad("△", KeyEvent.KEYCODE_BUTTON_Y, true), step, 0, cell, cell)
        place(face, pad("□", KeyEvent.KEYCODE_BUTTON_X, true), 0, step, cell, cell)
        place(face, pad("○", circle, true), step * 2, step, cell, cell)
        place(face, pad("✕", cross, true), step, step * 2, cell, cell)
        root.addView(face, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        // ปุ่มไหล่
        val sw = dp(84)
        val sh = dp(40)
        val top = dp(10)
        fun shoulder(label: String, code: Int, left: Boolean, row: Int) {
            root.addView(pad(label, code), FrameLayout.LayoutParams(sw, sh).apply {
                gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
                if (left) leftMargin = margin else rightMargin = margin
                topMargin = top + row * (sh + dp(6))
            })
        }
        shoulder("L2", KeyEvent.KEYCODE_BUTTON_L2, true, 0)
        shoulder("L1", KeyEvent.KEYCODE_BUTTON_L1, true, 1)
        shoulder("R2", KeyEvent.KEYCODE_BUTTON_R2, false, 0)
        shoulder("R1", KeyEvent.KEYCODE_BUTTON_R1, false, 1)

        // Start / Select (กลางล่าง)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(pad("Select", KeyEvent.KEYCODE_BUTTON_SELECT), LinearLayout.LayoutParams(dp(78), dp(34)).apply { rightMargin = dp(10) })
        mid.addView(pad("Start", KeyEvent.KEYCODE_BUTTON_START), LinearLayout.LayoutParams(dp(78), dp(34)))
        root.addView(mid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = margin
        })
    }
}
