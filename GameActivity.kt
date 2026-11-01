package com.bom.dokapon

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.JavaI420Buffer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * รหัสปุ่มแบบ RetroPad ที่หน้าเว็บของเพื่อนส่งมา -> รหัสปุ่มจอยของ Android
 * ทดสอบแล้ว: ✕ ต้องเป็น BUTTON_B, ○ เป็น BUTTON_A, □ เป็น BUTTON_Y, △ เป็น BUTTON_X
 */
object PadMap {
    fun cross() = KeyEvent.KEYCODE_BUTTON_B
    fun circle() = KeyEvent.KEYCODE_BUTTON_A
    fun square() = KeyEvent.KEYCODE_BUTTON_Y
    fun triangle() = KeyEvent.KEYCODE_BUTTON_X

    fun code(i: Int): Int? = when (i) {
        0 -> cross()
        1 -> square()
        2 -> KeyEvent.KEYCODE_BUTTON_SELECT
        3 -> KeyEvent.KEYCODE_BUTTON_START
        4 -> KeyEvent.KEYCODE_DPAD_UP
        5 -> KeyEvent.KEYCODE_DPAD_DOWN
        6 -> KeyEvent.KEYCODE_DPAD_LEFT
        7 -> KeyEvent.KEYCODE_DPAD_RIGHT
        8 -> circle()
        9 -> triangle()
        10 -> KeyEvent.KEYCODE_BUTTON_L1
        11 -> KeyEvent.KEYCODE_BUTTON_R1
        12 -> KeyEvent.KEYCODE_BUTTON_L2
        13 -> KeyEvent.KEYCODE_BUTTON_R2
        else -> null
    }
}

/** Foreground service จำเป็นสำหรับการจับภาพหน้าจอบน Android 10 ขึ้นไป */
class StreamService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "stream"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, "Streaming", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = Notification.Builder(this, channelId)
            .setContentTitle("Dokapon Host")
            .setContentText("กำลังสตรีมเกมให้เพื่อน")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
        return START_NOT_STICKY
    }
}

/** ติดต่อเซิร์ฟเวอร์จับคู่ฟรีของ PeerJS แบบดิบ ๆ ผ่าน WebSocket (ใช้เป็นแค่ตัวส่งต่อข้อความ) */
class Signaling(private val id: String, private val listener: Listener) {
    interface Listener {
        fun onMessage(type: String, src: String?, payload: JSONObject?)
    }

    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var generation = 0
    private var closed = false

    private val heartbeat = object : Runnable {
        override fun run() {
            ws?.send("{\"type\":\"HEARTBEAT\"}")
            if (!closed) main.postDelayed(this, 5000)
        }
    }

    fun connect() {
        closed = false
        val gen = ++generation
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val token = (1..12).map { chars[(Math.random() * chars.length).toInt()] }.joinToString("")
        val req = Request.Builder().url("wss://0.peerjs.com/peerjs?key=peerjs&id=$id&token=$token").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen != generation) return@post
                    main.removeCallbacks(heartbeat)
                    main.post(heartbeat)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val j = JSONObject(text)
                    val type = j.optString("type")
                    val src = if (j.has("src")) j.optString("src") else null
                    val payload = j.optJSONObject("payload")
                    main.post { if (gen == generation) listener.onMessage(type, src, payload) }
                } catch (e: Exception) {
                    // ข้อความที่อ่านไม่ได้ ข้ามไป
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (gen == generation && !closed) listener.onMessage("CLOSED", null, null) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation && !closed) listener.onMessage("CLOSED", null, null) }
            }
        })
    }

    fun send(type: String, dst: String, payload: JSONObject) {
        ws?.send(JSONObject().put("type", type).put("dst", dst).put("payload", payload).toString())
    }

    fun reconnect() {
        generation++
        try { ws?.cancel() } catch (e: Exception) { }
        connect()
    }

    fun close() {
        closed = true
        generation++
        main.removeCallbacks(heartbeat)
        try { ws?.close(1000, null) } catch (e: Exception) { }
    }
}

open class SdpAdapter(private val onCreated: ((SessionDescription) -> Unit)? = null) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) { onCreated?.invoke(sdp) }
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

/**
 * จับภาพหน้าจอ -> ส่งให้เพื่อนด้วย WebRTC และรับปุ่มจากเพื่อนผ่าน DataChannel
 * เพื่อนเข้าห้องแล้วได้ผู้เล่น 2–4 (port 1–3)
 */
class Streamer(
    private val context: Context,
    private val surface: SurfaceView,
    private val room: String,
    private val fps: Int,
    private val onInput: (slot: Int, btn: Int, down: Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        const val FRAME_W = 320
        const val FRAME_H = 240
    }

    private class Peer(val id: String, val slot: Int, val name: String) {
        var pc: PeerConnection? = null
        var channel: DataChannel? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val eglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var grabThread: HandlerThread? = null
    private var grabHandler: Handler? = null
    private val frameBitmap: Bitmap = Bitmap.createBitmap(FRAME_W, FRAME_H, Bitmap.Config.ARGB_8888)
    private val framePixels = IntArray(FRAME_W * FRAME_H)
    @Volatile private var grabbing = false
    private var sig: Signaling? = null
    private val peers = HashMap<String, Peer>()
    private val held = HashMap<Int, MutableSet<Int>>()
    private var stopped = false
    private val hostId = "dokapon-$room"

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** ดึงภาพเฉพาะตัวเกมจาก GLRetroView ด้วย PixelCopy (ไม่จับทั้งหน้าจอ ไม่มีปุ่ม/แจ้งเตือนติดไปด้วย) */
    private val grabTask = object : Runnable {
        override fun run() {
            if (stopped) return
            grabOnce()
            main.postDelayed(this, (1000 / fps).toLong())
        }
    }

    private fun grabOnce() {
        if (grabbing || peers.isEmpty()) return
        if (!surface.holder.surface.isValid || surface.width == 0 || surface.height == 0) return
        val h = surface.height
        val w = minOf(surface.width, h * 4 / 3)
        val left = (surface.width - w) / 2
        grabbing = true
        try {
            PixelCopy.request(surface, Rect(left, 0, left + w, h), frameBitmap, { result ->
                try {
                    if (result == PixelCopy.SUCCESS) pushFrame()
                } finally {
                    grabbing = false
                }
            }, grabHandler ?: main)
        } catch (e: Exception) {
            grabbing = false
        }
    }

    /** แปลงภาพ ARGB ขนาด 320x240 เป็น I420 แล้วส่งให้ WebRTC */
    private fun pushFrame() {
        val src = source ?: return
        val w = FRAME_W
        val h = FRAME_H
        frameBitmap.getPixels(framePixels, 0, w, 0, 0, w, h)
        val buf = JavaI420Buffer.allocate(w, h)
        val yP = buf.dataY
        val uP = buf.dataU
        val vP = buf.dataV
        val ys = buf.strideY
        val us = buf.strideU
        val vs = buf.strideV
        for (j in 0 until h) {
            for (i in 0 until w) {
                val c = framePixels[j * w + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yP.put(j * ys + i, y.coerceIn(0, 255).toByte())
                if ((j and 1) == 0 && (i and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uP.put((j / 2) * us + i / 2, u.coerceIn(0, 255).toByte())
                    vP.put((j / 2) * vs + i / 2, v.coerceIn(0, 255).toByte())
                }
            }
        }
        val frame = VideoFrame(buf, 0, System.nanoTime())
        src.capturerObserver.onFrameCaptured(frame)
        frame.release()
    }

    fun start() {
        val src = factory.createVideoSource(false)
        src.capturerObserver.onCapturerStarted(true)
        source = src
        track = factory.createVideoTrack("game", src)

        val thread = HandlerThread("frame-grab")
        thread.start()
        grabThread = thread
        grabHandler = Handler(thread.looper)
        main.post(grabTask)

        sig = Signaling(hostId, object : Signaling.Listener {
            override fun onMessage(type: String, src: String?, payload: JSONObject?) {
                onSignal(type, src, payload)
            }
        })
        sig?.connect()
        status("กำลังเชื่อมต่อห้อง…")
    }

    private fun status(extra: String? = null) {
        onStatus(extra ?: "ห้อง $room · เพื่อน ${peers.size} คน")
    }

    private fun onSignal(type: String, src: String?, p: JSONObject?) {
        if (stopped) return
        when (type) {
            "OPEN" -> status()
            "ID-TAKEN" -> {
                status("รหัสห้องถูกใช้อยู่ (เซสชันเก่าค้าง) กำลังลองใหม่…")
                main.postDelayed({ if (!stopped) sig?.reconnect() }, 5000)
            }
            "CLOSED" -> {
                status("หลุดจากเซิร์ฟเวอร์จับคู่ กำลังต่อใหม่…")
                main.postDelayed({ if (!stopped) sig?.reconnect() }, 3000)
            }
            "OFFER" -> {
                if (src != null && p != null && p.optString("t") == "join") join(src, p.optString("name"))
            }
            "ANSWER" -> {
                if (src != null && p != null && p.optString("t") == "sdp") {
                    peers[src]?.pc?.setRemoteDescription(
                        SdpAdapter(),
                        SessionDescription(SessionDescription.Type.ANSWER, p.optString("sdp"))
                    )
                }
            }
            "CANDIDATE" -> {
                if (src != null && p != null && p.optString("t") == "ice") {
                    val c = IceCandidate(p.optString("sdpMid"), p.optInt("sdpMLineIndex"), p.optString("candidate"))
                    peers[src]?.pc?.addIceCandidate(c)
                }
            }
            "LEAVE" -> {
                if (src != null) drop(src)
            }
        }
    }

    private fun join(src: String, name: String) {
        if (peers.containsKey(src)) drop(src)
        val used = peers.values.map { it.slot }.toSet()
        val slot = listOf(1, 2, 3).firstOrNull { it !in used }
        if (slot == null) {
            sig?.send("ANSWER", src, JSONObject().put("t", "full"))
            return
        }
        val peer = Peer(src, slot, name.ifBlank { "Player${slot + 1}" }.take(20))
        peers[src] = peer
        sig?.send("ANSWER", src, JSONObject().put("t", "joined").put("slot", slot).put("name", peer.name))
        connect(peer)
        status()
    }

    private fun connect(peer: Peer) {
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:eu-0.turn.peerjs.com:3478")
                .setUsername("peerjs").setPassword("peerjsp").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                main.post {
                    if (peers[peer.id] !== peer) return@post
                    when (s) {
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> drop(peer.id)
                        PeerConnection.IceConnectionState.DISCONNECTED -> main.postDelayed({
                            if (peers[peer.id] === peer &&
                                peer.pc?.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED
                            ) drop(peer.id)
                        }, 10000)
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                sig?.send(
                    "CANDIDATE", peer.id,
                    JSONObject().put("t", "ice").put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                )
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
        }) ?: return
        peer.pc = pc

        val t = track
        if (t != null) {
            val sender = pc.addTrack(t, listOf("game"))
            try {
                val params = sender.parameters
                for (e in params.encodings) {
                    e.maxBitrateBps = 2_000_000
                    e.maxFramerate = 30
                }
                sender.setParameters(params)
            } catch (e: Exception) {
                // ตั้งบิตเรตไม่ได้ ใช้ค่าเริ่มต้น
            }
        }

        val channel = pc.createDataChannel("input", DataChannel.Init())
        peer.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(l: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val j = JSONObject(String(bytes))
                    val btn = j.getInt("b")
                    val down = j.getInt("d") == 1
                    main.post { dispatchInput(peer.slot, btn, down) }
                } catch (e: Exception) {
                    // ข้อความที่อ่านไม่ได้ ข้ามไป
                }
            }
        })

        pc.createOffer(SdpAdapter { offer ->
            pc.setLocalDescription(SdpAdapter(), offer)
            sig?.send(
                "OFFER", peer.id,
                JSONObject().put("t", "sdp").put("type", "offer").put("sdp", offer.description)
            )
        }, MediaConstraints())
    }

    private fun dispatchInput(slot: Int, btn: Int, down: Boolean) {
        val set = held.getOrPut(slot) { mutableSetOf() }
        if (down) set.add(btn) else set.remove(btn)
        onInput(slot, btn, down)
    }

    private fun drop(id: String) {
        val p = peers.remove(id) ?: return
        held[p.slot]?.toList()?.forEach { dispatchInput(p.slot, it, false) }
        try { p.channel?.close() } catch (e: Exception) { }
        try { p.pc?.close() } catch (e: Exception) { }
        status()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        peers.keys.toList().forEach { drop(it) }
        sig?.close()
        main.removeCallbacks(grabTask)
        try { source?.capturerObserver?.onCapturerStopped() } catch (e: Exception) { }
        try { grabThread?.quitSafely() } catch (e: Exception) { }
        try { track?.dispose() } catch (e: Exception) { }
        try { source?.dispose() } catch (e: Exception) { }
        try { factory.dispose() } catch (e: Exception) { }
        try { eglBase.release() } catch (e: Exception) { }
    }
}

class GameActivity : AppCompatActivity() {

    private var retroView: GLRetroView? = null
    private var streamer: Streamer? = null
    private lateinit var statusView: TextView
    private lateinit var streamBtn: TextView

    private var gameKey = "game"
    private var lastSramHash = 0
    private var padAlpha = 0.55f
    private var padScale = 1f
    private var swapLocal = false
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    private val autosave = object : Runnable {
        override fun run() {
            saveSram(false)
            ui.postDelayed(this, 60000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val settings = getSharedPreferences("dokapon", MODE_PRIVATE)
        padAlpha = settings.getInt("ui_alpha", 55) / 100f
        padScale = settings.getInt("ui_scale", 100) / 100f
        swapLocal = settings.getBoolean("swap_ab", false)
        if (settings.getBoolean("sustained", true)) {
            try { window.setSustainedPerformanceMode(true) } catch (e: Throwable) { }
        }

        val gamePath = intent.getStringExtra("game")
        if (gamePath == null) {
            finish()
            return
        }

        gameKey = File(gamePath).nameWithoutExtension
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        try {
            val data = GLRetroViewData(this).apply {
                coreFilePath = "libpcsx_rearmed_libretro_android.so"
                gameFilePath = gamePath
                val sram = sramFile()
                if (sram.exists()) saveRAMState = sram.readBytes()
                systemDirectory = File(filesDir, "system").apply { mkdirs() }.absolutePath
                savesDirectory = File(filesDir, "saves").apply { mkdirs() }.absolutePath
                val mt = settings.getString("multitap", "auto") ?: "auto"
                val oldMt = if (mt == "enabled") "port 1 only" else "disabled"
                variables = arrayOf(
                    Variable("pcsx_rearmed_multitap1", mt),
                    Variable("pcsx_rearmed_multitap2", "disabled"),
                    Variable("pcsx_rearmed_multitap", oldMt)
                )
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
        buildStreamBar(root)
        setContentView(root)

        ui.postDelayed(autosave, 60000)
        if (settings.getBoolean("auto_room", true)) {
            ui.postDelayed({
                if (streamer == null && !isFinishing) startStreaming()
            }, 1500)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitGame()
            }
        })
    }

    override fun onPause() {
        saveSram(false)
        super.onPause()
    }

    // ---------- เซฟเกม / เมนู ----------
    private fun savesDir(): File = File(filesDir, "saves").apply { mkdirs() }
    private fun sramFile(): File = File(savesDir(), "$gameKey.srm")
    private fun stateFile(n: Int): File = File(savesDir(), "$gameKey.state$n")

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    }

    /** บันทึกเมมการ์ดของเกม (ที่เกมเซฟลงไป) ลงไฟล์ในแอป */
    private fun saveSram(showToast: Boolean) {
        val v = retroView ?: return
        scope.launch {
            try {
                val bytes = v.serializeSRAM()
                if (bytes.isNotEmpty()) {
                    val hash = bytes.contentHashCode()
                    if (showToast || hash != lastSramHash) {
                        withContext(Dispatchers.IO) { sramFile().writeBytes(bytes) }
                        lastSramHash = hash
                    }
                    if (showToast) toast("บันทึกเมมการ์ดแล้ว")
                } else if (showToast) {
                    toast("ยังไม่มีข้อมูลเมมการ์ดให้บันทึก")
                }
            } catch (e: Throwable) {
                if (showToast) toast("บันทึกเมมการ์ดไม่สำเร็จ: ${e.message}")
            }
        }
    }

    private fun saveState(n: Int) {
        val v = retroView ?: return
        scope.launch {
            try {
                val bytes = v.serializeState()
                stateFile(n).writeBytes(bytes)
                toast("บันทึกสถานะช่อง $n แล้ว")
            } catch (e: Throwable) {
                toast("บันทึกสถานะไม่สำเร็จ: ${e.message}")
            }
        }
    }

    private fun loadState(n: Int) {
        val v = retroView ?: return
        val f = stateFile(n)
        if (!f.exists()) {
            toast("ช่อง $n ยังว่างอยู่")
            return
        }
        scope.launch {
            try {
                v.unserializeState(f.readBytes())
                toast("โหลดสถานะช่อง $n แล้ว")
            } catch (e: Throwable) {
                toast("โหลดสถานะไม่สำเร็จ: ${e.message}")
            }
        }
    }

    private fun exitGame() {
        val v = retroView
        if (v == null) {
            finish()
            return
        }
        scope.launch {
            try {
                val bytes = v.serializeSRAM()
                if (bytes.isNotEmpty()) sramFile().writeBytes(bytes)
            } catch (e: Throwable) {
                // เซฟไม่ได้ก็ออกต่อ
            }
            finish()
        }
    }

    private fun showMenu() {
        val v = retroView ?: return
        val items = arrayOf(
            "💾 บันทึกสถานะ ช่อง 1", "💾 บันทึกสถานะ ช่อง 2", "💾 บันทึกสถานะ ช่อง 3",
            "📂 โหลดสถานะ ช่อง 1", "📂 โหลดสถานะ ช่อง 2", "📂 โหลดสถานะ ช่อง 3",
            "💳 บันทึกเมมการ์ดตอนนี้",
            if (v.audioEnabled) "🔇 ปิดเสียงเกม" else "🔊 เปิดเสียงเกม",
            "🔄 รีเซ็ตเกม",
            "⚙ ตั้งค่า",
            "❌ ออกจากเกม"
        )
        AlertDialog.Builder(this)
            .setTitle("เมนู")
            .setItems(items) { _, i ->
                when (i) {
                    0, 1, 2 -> { saveState(i + 1) }
                    3, 4, 5 -> { loadState(i - 2) }
                    6 -> { saveSram(true) }
                    7 -> { v.audioEnabled = !v.audioEnabled }
                    8 -> { v.reset() }
                    9 -> { showSettingsDialog(this) }
                    10 -> { exitGame() }
                }
            }
            .show()
    }

    override fun onDestroy() {
        ui.removeCallbacks(autosave)
        streamer?.stop()
        streamer = null
        super.onDestroy()
    }

    private fun press(code: Int, down: Boolean) {
        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0)
    }

    // ---------- สตรีม ----------
    private fun buildStreamBar(root: FrameLayout) {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(140, 0, 0, 0)) }
            text = "ยังไม่ได้สตรีม"
        }
        streamBtn = TextView(this).apply {
            text = "📡 เริ่มสตรีม"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(242, 193, 78)) }
            setOnClickListener {
                startStreaming()
            }
        }
        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.argb(140, 0, 0, 0)) }
            setOnClickListener { showMenu() }
        }
        bar.addView(gear, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        bar.addView(statusView)
        bar.addView(streamBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })
    }

    private fun startStreaming() {
        val view = retroView
        if (view == null) {
            Toast.makeText(this, "เกมยังไม่พร้อม", Toast.LENGTH_SHORT).show()
            return
        }
        streamBtn.visibility = View.GONE
        statusView.text = "กำลังเริ่มสตรีม…"
        val room = getSharedPreferences("dokapon", MODE_PRIVATE).getString("room", "DOKAPN") ?: "DOKAPN"
        val fps = getSharedPreferences("dokapon", MODE_PRIVATE).getInt("stream_fps", 24)
        try {
            val s = Streamer(
                this, view, room, fps,
                { slot, btn, down ->
                    val code = PadMap.code(btn)
                    if (code != null) {
                        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, slot)
                    }
                },
                { text -> runOnUiThread { statusView.text = text } }
            )
            streamer = s
            s.start()
        } catch (e: Throwable) {
            Toast.makeText(this, "เริ่มสตรีมไม่สำเร็จ: ${e.message ?: e}", Toast.LENGTH_LONG).show()
            statusView.text = "สตรีมไม่สำเร็จ"
            streamBtn.visibility = View.VISIBLE
        }
    }

    // ---------- ปุ่มบนจอของเรา (ผู้เล่น 1) ----------
    @SuppressLint("ClickableViewAccessibility")
    private fun pad(label: String, code: Int, circle: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.WHITE)
            alpha = padAlpha
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
                        v.alpha = padAlpha
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
        val cell = (dp(54) * padScale).toInt()
        val gap = dp(4)
        val step = cell + gap
        val size = cell * 3 + gap * 2
        val margin = dp(16)

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

        val face = FrameLayout(this)
        place(face, pad("△", PadMap.triangle(), true), step, 0, cell, cell)
        place(face, pad("□", PadMap.square(), true), 0, step, cell, cell)
        place(face, pad("○", if (swapLocal) PadMap.cross() else PadMap.circle(), true), step * 2, step, cell, cell)
        place(face, pad("✕", if (swapLocal) PadMap.circle() else PadMap.cross(), true), step, step * 2, cell, cell)
        root.addView(face, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        val sw = (dp(84) * padScale).toInt()
        val sh = (dp(40) * padScale).toInt()
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

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(pad("Select", KeyEvent.KEYCODE_BUTTON_SELECT), LinearLayout.LayoutParams(dp(78), dp(34)).apply { rightMargin = dp(10) })
        mid.addView(pad("Start", KeyEvent.KEYCODE_BUTTON_START), LinearLayout.LayoutParams(dp(78), dp(34)))
        root.addView(mid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = margin
        })
    }
}
