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
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
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
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
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

/** บันทึกเหตุการณ์การเชื่อมต่อ (ไว้ถ่ายภาพส่งมาแก้ปัญหา) */
object DebugLog {
    private val lines = ArrayList<String>()

    @Synchronized
    fun add(text: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        lines.add("$t $text")
        while (lines.size > 60) lines.removeAt(0)
    }

    @Synchronized
    fun tail(n: Int): String = lines.takeLast(n).joinToString("\n")
}

/** เซิร์ฟเวอร์ MQTT สาธารณะที่ใช้เป็นตัวจับคู่/ล็อบบี้ (โฮสต์กับเพื่อนต้องใช้ตัวเดียวกัน) */
object Brokers {
    val urls = listOf(
        "wss://broker.emqx.io:8084/mqtt",
        "wss://broker.hivemq.com:8884/mqtt",
        "wss://test.mosquitto.org:8081"
    )

    fun current(ctx: Context): Int {
        return ctx.getSharedPreferences("dokapon", Context.MODE_PRIVATE).getInt("broker", 0).coerceIn(0, urls.size - 1)
    }

    fun url(ctx: Context): String = urls[current(ctx)]
}

private fun randomText(n: Int): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    val r = java.security.SecureRandom()
    return (1..n).map { chars[r.nextInt(chars.length)] }.joinToString("")
}

/**
 * ตัวจับคู่ผ่าน MQTT (เหมือนเกมออนไลน์ทั่วไปที่มีเซิร์ฟเวอร์กลาง)
 * - หัวข้อ dokapon/v1/room/<ห้อง>  : สถานะห้อง (retained) โฮสต์ประกาศ ผู้เล่น/ล็อบบี้อ่าน
 * - หัวข้อ dokapon/v1/box/<ห้อง>/<id> : กล่องรับข้อความของแต่ละเครื่อง
 * ภาพและปุ่มยังวิ่งตรงระหว่างมือถือด้วย WebRTC ผ่านเซิร์ฟเวอร์นี้แค่ตอนจับคู่
 */
class Signaling(
    private val ctx: Context,
    private val id: String,
    private val room: String,
    private val isHost: Boolean,
    private val hostInfo: () -> String,
    private val listener: Listener
) {
    interface Listener {
        fun onMessage(type: String, src: String?, payload: JSONObject?)
    }

    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private var closed = false
    private val roomTopic = "dokapon/v1/room/$room"
    private fun inbox(target: String) = "dokapon/v1/box/$room/$target"
    private val tag = id.takeLast(6)

    private val refresh = object : Runnable {
        override fun run() {
            if (closed) return
            if (isHost) publishPresence(true)
            main.postDelayed(this, 30000)
        }
    }

    fun connect() {
        closed = false
        val url = Brokers.url(ctx)
        DebugLog.add("[mqtt $tag] เชื่อมต่อ $url")
        try {
            val c = MqttAsyncClient(url, "dk-$tag-" + randomText(5), MemoryPersistence())
            val opt = MqttConnectOptions()
            opt.isCleanSession = true
            opt.keepAliveInterval = 20
            opt.connectionTimeout = 12
            opt.isAutomaticReconnect = true
            if (isHost) opt.setWill(roomTopic, "{\"online\":false}".toByteArray(), 1, true)
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    DebugLog.add("[mqtt $tag] เชื่อมต่อสำเร็จ reconnect=$reconnect")
                    subscribeAll(c)
                }

                override fun connectionLost(cause: Throwable?) {
                    val m = (cause?.message ?: "หลุด").take(80)
                    DebugLog.add("[mqtt $tag] หลุด: $m")
                    main.post { if (!closed) listener.onMessage("CLOSED", null, JSONObject().put("msg", m)) }
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) handle(topic, String(message.payload))
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client = c
            c.connect(opt, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    val m = (exception?.message ?: "เชื่อมต่อไม่ได้").take(80)
                    DebugLog.add("[mqtt $tag] เชื่อมต่อล้มเหลว: $m")
                    main.post { if (!closed) listener.onMessage("CLOSED", null, JSONObject().put("msg", m)) }
                }
            })
        } catch (e: Exception) {
            val m = (e.message ?: e.toString()).take(80)
            DebugLog.add("[mqtt $tag] สร้างการเชื่อมต่อไม่ได้: $m")
            main.post { if (!closed) listener.onMessage("CLOSED", null, JSONObject().put("msg", m)) }
        }
    }

    private fun subscribeAll(c: MqttAsyncClient) {
        try {
            c.subscribe(inbox(id), 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    DebugLog.add("[mqtt $tag] พร้อมรับข้อความ")
                    main.post {
                        if (closed) return@post
                        listener.onMessage("OPEN", null, null)
                        if (isHost) {
                            publishPresence(true)
                            main.removeCallbacks(refresh)
                            main.postDelayed(refresh, 30000)
                        }
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    DebugLog.add("[mqtt $tag] subscribe ล้มเหลว: ${exception?.message}")
                }
            })
            if (!isHost) c.subscribe(roomTopic, 1)
        } catch (e: Exception) {
            DebugLog.add("[mqtt $tag] subscribe error: ${e.message}")
        }
    }

    private fun handle(topic: String, text: String) {
        try {
            if (topic == roomTopic) {
                val j = JSONObject(text)
                DebugLog.add("[mqtt $tag] สถานะห้อง online=${j.optBoolean("online")}")
                main.post { if (!closed) listener.onMessage("HOST", null, j) }
                return
            }
            val j = JSONObject(text)
            val type = j.optString("type")
            val src = j.optString("src")
            val payload = j.optJSONObject("payload")
            DebugLog.add("[mqtt $tag] รับ $type จาก ${src.takeLast(6)} t=${payload?.optString("t") ?: "-"}")
            main.post { if (!closed) listener.onMessage(type, src, payload) }
        } catch (e: Exception) {
            DebugLog.add("[mqtt $tag] ข้อความอ่านไม่ได้")
        }
    }

    private fun publishPresence(online: Boolean) {
        if (!isHost) return
        try {
            val j = JSONObject(hostInfo()).put("online", online).put("ts", System.currentTimeMillis())
            val m = MqttMessage(j.toString().toByteArray())
            m.qos = 1
            m.isRetained = true
            client?.publish(roomTopic, m)
        } catch (e: Exception) {
            DebugLog.add("[mqtt $tag] ประกาศห้องไม่ได้: ${e.message}")
        }
    }

    /** โฮสต์เรียกเมื่อจำนวนคนเปลี่ยน */
    fun refreshPresence() {
        publishPresence(true)
    }

    fun send(type: String, dst: String, payload: JSONObject) {
        DebugLog.add("[mqtt $tag] ส่ง $type ถึง ${dst.takeLast(6)} t=${payload.optString("t")}")
        try {
            val text = JSONObject().put("type", type).put("src", id).put("payload", payload).toString()
            val m = MqttMessage(text.toByteArray())
            m.qos = 1
            client?.publish(inbox(dst), m)
        } catch (e: Exception) {
            DebugLog.add("[mqtt $tag] ส่งไม่สำเร็จ: ${e.message}")
        }
    }

    fun reconnect() {
        if (client?.isConnected == true) return
        try {
            client?.reconnect()
        } catch (e: Exception) {
            try { client?.close() } catch (e2: Exception) { }
            client = null
            connect()
        }
    }

    fun close() {
        closed = true
        main.removeCallbacks(refresh)
        val c = client
        client = null
        if (c == null) return
        try {
            if (isHost) {
                val m = MqttMessage("{\"online\":false}".toByteArray())
                m.qos = 1
                m.isRetained = true
                c.publish(roomTopic, m)
            }
        } catch (e: Exception) { }
        main.postDelayed({
            try { c.disconnect() } catch (e: Exception) { }
            try { c.close() } catch (e: Exception) { }
        }, 600)
    }
}

data class LobbyRoom(val room: String, val name: String, val players: Int, val max: Int)

/** ดูรายชื่อห้องที่เปิดอยู่ (ล็อบบี้) จากเซิร์ฟเวอร์ MQTT */
class LobbyWatcher(private val ctx: Context, private val onChange: (List<LobbyRoom>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var client: MqttAsyncClient? = null
    private val rooms = LinkedHashMap<String, LobbyRoom>()
    private var stopped = false

    fun start() {
        stopped = false
        try {
            val c = MqttAsyncClient(Brokers.url(ctx), "dk-lobby-" + randomText(6), MemoryPersistence())
            val opt = MqttConnectOptions()
            opt.isCleanSession = true
            opt.keepAliveInterval = 20
            opt.connectionTimeout = 12
            opt.isAutomaticReconnect = true
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    try { c.subscribe("dokapon/v1/room/+", 1) } catch (e: Exception) { }
                }

                override fun connectionLost(cause: Throwable?) {}

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic == null || message == null) return
                    val room = topic.substringAfterLast('/')
                    try {
                        val j = JSONObject(String(message.payload))
                        if (j.optBoolean("online") && j.optBoolean("public", true)) {
                            rooms[room] = LobbyRoom(room, j.optString("name", "โฮสต์"), j.optInt("players", 1), j.optInt("max", 4))
                        } else {
                            rooms.remove(room)
                        }
                    } catch (e: Exception) {
                        rooms.remove(room)
                    }
                    val list = rooms.values.toList()
                    main.post { if (!stopped) onChange(list) }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client = c
            c.connect(opt, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    DebugLog.add("[lobby] เชื่อมต่อไม่ได้: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            DebugLog.add("[lobby] error: ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        val c = client
        client = null
        if (c == null) return
        try { c.disconnect() } catch (e: Exception) { }
        try { c.close() } catch (e: Exception) { }
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
    private val autoAccept: Boolean,
    private val quality: Int,
    private val poolSize: Int,
    val hostSlot: Int,
    private val randomSeat: Boolean,
    private val turnLock: Boolean,
    private val useHw: Boolean,
    private val onJoinRequest: (src: String, name: String, decide: (Boolean, Int) -> Unit) -> Unit,
    private val onInput: (slot: Int, btn: Int, down: Boolean) -> Unit,
    private val onTurn: (seat: Int, name: String, lock: Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private class Peer(val id: String, var slot: Int, val name: String) {
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
    private val frameW: Int = when (quality) { 0 -> 320; 2 -> 640; else -> 480 }
    private val frameH: Int = frameW * 3 / 4
    private val frameBitmap: Bitmap = Bitmap.createBitmap(frameW, frameH, Bitmap.Config.ARGB_8888)
    private val framePixels = IntArray(frameW * frameH)
    @Volatile private var grabbing = false
    private var sig: Signaling? = null
    private val peers = HashMap<String, Peer>()
    private val pendingJoin = HashMap<String, String>()
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
    private var nextGrabAt = 0L
    private val grabTask = object : Runnable {
        override fun run() {
            if (stopped) return
            grabOnce()
            val interval = (1000 / fps).toLong()
            val now = android.os.SystemClock.uptimeMillis()
            if (nextGrabAt == 0L) nextGrabAt = now
            nextGrabAt += interval
            if (nextGrabAt < now) nextGrabAt = now + interval
            main.postAtTime(this, nextGrabAt)
        }
    }

    private var loggedGrabSize = false

    private fun grabOnce() {
        if (grabbing || peers.isEmpty()) return
        if (!surface.holder.surface.isValid) return
        // PixelCopy ใช้พิกัดของ "พื้นผิวภาพ" (surface) ซึ่งอาจต่างจากขนาดของ View
        val f = surface.holder.surfaceFrame
        val fw = f.width()
        val fh = f.height()
        if (fw <= 0 || fh <= 0) return
        // กรอบ 4:3 ที่พอดีอยู่ในพื้นผิว (ตรงกับพื้นที่ที่เกมวาดจริง)
        val rw: Int
        val rh: Int
        if (fw * 3 > fh * 4) {
            rh = fh
            rw = fh * 4 / 3
        } else {
            rw = fw
            rh = fw * 3 / 4
        }
        val left = (fw - rw) / 2
        val top = (fh - rh) / 2
        if (!loggedGrabSize) {
            loggedGrabSize = true
            DebugLog.add("[host] จับภาพจากพื้นผิว ${fw}x$fh (view ${surface.width}x${surface.height}) ใช้กรอบ ${rw}x$rh")
        }
        grabbing = true
        try {
            PixelCopy.request(surface, Rect(left, top, left + rw, top + rh), frameBitmap, { result ->
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

    private val yArr = ByteArray(frameW * frameH)
    private val uArr = ByteArray((frameW / 2) * (frameH / 2))
    private val vArr = ByteArray((frameW / 2) * (frameH / 2))

    private fun copyPlane(dst: java.nio.ByteBuffer, dstStride: Int, srcArr: ByteArray, w: Int, h: Int) {
        if (dstStride == w) {
            dst.position(0)
            dst.put(srcArr, 0, w * h)
        } else {
            for (row in 0 until h) {
                dst.position(row * dstStride)
                dst.put(srcArr, row * w, w)
            }
        }
    }

    /** แปลงภาพ ARGB เป็น I420 แล้วส่งให้ WebRTC */
    private fun pushFrame() {
        val src = source ?: return
        val w = frameW
        val h = frameH
        frameBitmap.getPixels(framePixels, 0, w, 0, 0, w, h)
        var yi = 0
        var ci = 0
        for (j in 0 until h) {
            val row = j * w
            val evenRow = (j and 1) == 0
            for (i in 0 until w) {
                val c = framePixels[row + i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                yArr[yi++] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                if (evenRow && (i and 1) == 0) {
                    uArr[ci] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    vArr[ci] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    ci++
                }
            }
        }
        val buf = JavaI420Buffer.allocate(w, h)
        copyPlane(buf.dataY, buf.strideY, yArr, w, h)
        copyPlane(buf.dataU, buf.strideU, uArr, w / 2, h / 2)
        copyPlane(buf.dataV, buf.strideV, vArr, w / 2, h / 2)
        val frame = VideoFrame(buf, 0, System.nanoTime())
        src.capturerObserver.onFrameCaptured(frame)
        frame.release()
    }

    fun start() {
        val src = factory.createVideoSource(true)
        src.capturerObserver.onCapturerStarted(true)
        source = src
        track = factory.createVideoTrack("game", src)

        val thread = HandlerThread("frame-grab")
        thread.start()
        grabThread = thread
        grabHandler = Handler(thread.looper)
        main.post(grabTask)
        main.postDelayed(turnTicker, 3000)

        sig = Signaling(context, hostId, room, true, { hostInfoJson() }, object : Signaling.Listener {
            override fun onMessage(type: String, src: String?, payload: JSONObject?) {
                onSignal(type, src, payload)
            }
        })
        sig?.connect()
        status("กำลังเชื่อมต่อห้อง…")
    }

    // ---------- ระบบสลับตา: เฉพาะผู้ถือตาเท่านั้นที่กดปุ่มได้ กด "จบตา" เพื่อส่งต่อให้คนถัดไป ----------
    private var holder: String = "host"
    private val turnTicker = object : Runnable {
        override fun run() {
            if (stopped) return
            broadcastTurn()
            main.postDelayed(this, 3000)
        }
    }

    private fun hostName(): String {
        val p = context.getSharedPreferences("dokapon", Context.MODE_PRIVATE)
        return (p.getString("pname", "") ?: "").ifBlank { "โฮสต์" }
    }

    private fun turnSeat(): Int = if (holder == "host") hostSlot else (peers[holder]?.slot ?: hostSlot)
    private fun turnName(): String = if (holder == "host") hostName() else (peers[holder]?.name ?: hostName())
    private fun allowed(slot: Int): Boolean = !turnLock || slot == turnSeat()

    /** โฮสต์กดปุ่มได้ไหม (ตอนนี้ถึงตาโฮสต์หรือปิดโหมดสลับตา) */
    fun hostMayPress(): Boolean = !turnLock || holder == "host"

    private fun advance() {
        val order = ArrayList<Pair<String, Int>>()
        order.add("host" to hostSlot)
        for (p in peers.values) order.add(p.id to p.slot)
        order.sortBy { it.second }
        val idx = order.indexOfFirst { it.first == holder }
        val next = order[(if (idx < 0) 0 else idx + 1) % order.size].first
        if (holder != "host") peers[holder]?.let { releaseSlot(it.slot) }
        holder = next
        DebugLog.add("[host] ส่งต่อตาให้ ${turnName()}")
        notifyTurn()
    }

    /** โฮสต์กดจบตา คืนค่า false ถ้ายังไม่ถึงตาโฮสต์ */
    fun hostEndTurn(): Boolean {
        if (!turnLock || holder != "host") return false
        advance()
        return true
    }

    private fun endTurnBy(id: String) {
        if (turnLock && holder == id) advance()
    }

    private fun notifyTurn() {
        onTurn(turnSeat(), turnName(), turnLock)
        broadcastTurn()
        status()
    }

    private fun broadcastTurn() {
        val msg = JSONObject().put("turn", turnSeat()).put("name", turnName()).put("lock", turnLock).toString()
        for (p in peers.values) {
            val ch = p.channel ?: continue
            if (ch.state() != DataChannel.State.OPEN) continue
            try {
                ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(msg.toByteArray()), false))
            } catch (e: Exception) {
                // ส่งไม่ได้ รอบถัดไปค่อยส่งใหม่
            }
        }
    }

    private fun preferH264(sdp: String): String {
        try {
            val lines = sdp.split("\r\n").toMutableList()
            val mIdx = lines.indexOfFirst { it.startsWith("m=video") }
            if (mIdx < 0) return sdp
            val rtpmap = Regex("^a=rtpmap:(\\d+) H264/90000")
            val h264 = ArrayList<String>()
            for (l in lines) {
                val m = rtpmap.find(l)
                if (m != null) h264.add(m.groupValues[1])
            }
            if (h264.isEmpty()) return sdp
            val parts = lines[mIdx].split(" ")
            if (parts.size < 4) return sdp
            val header = parts.subList(0, 3)
            val pts = parts.subList(3, parts.size)
            val ordered = h264.filter { it in pts } + pts.filter { it !in h264 }
            lines[mIdx] = (header + ordered).joinToString(" ")
            return lines.joinToString("\r\n")
        } catch (e: Exception) {
            return sdp
        }
    }

    private fun hostInfoJson(): String {
        val p = context.getSharedPreferences("dokapon", Context.MODE_PRIVATE)
        val name = (p.getString("pname", "") ?: "").ifBlank { "โฮสต์" }
        return JSONObject()
            .put("name", name)
            .put("players", peers.size + 1)
            .put("max", poolSize)
            .put("public", p.getBoolean("public_room", true))
            .toString()
    }

    private fun status(extra: String? = null) {
        onStatus(extra ?: ("ห้อง $room · คุณคือผู้เล่น ${hostSlot + 1} · เพื่อน ${peers.size} คน" + if (turnLock) " · ถึงตา: ${turnName()}" else ""))
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
        val existing = peers[src]
        if (existing != null) {
            // เพื่อนส่งคำขอซ้ำ (ยังไม่ได้รับคำตอบ) ตอบซ้ำโดยไม่สร้างการเชื่อมต่อใหม่
            sig?.send("ANSWER", src, JSONObject().put("t", "joined").put("slot", existing.slot).put("name", existing.name))
            return
        }
        DebugLog.add("[host] เพื่อนขอเข้าห้อง: $name")
        if (pendingJoin.containsKey(src)) {
            sig?.send("ANSWER", src, JSONObject().put("t", "wait"))
            return
        }
        if (autoAccept) {
            admit(src, name, -1)
            return
        }
        pendingJoin[src] = name
        sig?.send("ANSWER", src, JSONObject().put("t", "wait"))
        onJoinRequest(src, name) { ok, seat ->
            main.post {
                pendingJoin.remove(src)
                if (!stopped) {
                    if (ok) admit(src, name, seat) else sig?.send("ANSWER", src, JSONObject().put("t", "denied"))
                }
            }
        }
    }

    private fun admit(src: String, name: String, seat: Int) {
        val used = peers.values.map { it.slot }.toSet()
        val free = (0 until poolSize).filter { it != hostSlot && it !in used }
        // เจ้าของห้องเลือกช่องเองได้ (seat >= 0) ถ้าไม่เลือกจะเรียงตามลำดับที่ว่างก่อน
        val slot = if (free.isEmpty()) null else if (seat in free) seat else free.first()
        if (slot == null) {
            sig?.send("ANSWER", src, JSONObject().put("t", "full"))
            return
        }
        val peer = Peer(src, slot, name.ifBlank { "Player${slot + 1}" }.take(20))
        peers[src] = peer
        sig?.refreshPresence()
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
                DebugLog.add("[host] ICE ${peer.name}: $s")
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
                val maxBps = when (quality) { 0 -> 1_500_000; 2 -> 4_000_000; else -> 2_500_000 }
                for (e in params.encodings) {
                    e.maxBitrateBps = maxBps
                    e.minBitrateBps = maxBps / 3
                    e.maxFramerate = fps
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
                    if (j.has("e")) {
                        main.post { endTurnBy(peer.id) }
                        return
                    }
                    val btn = j.getInt("b")
                    val down = j.getInt("d") == 1
                    main.post { dispatchInput(peer.slot, btn, down) }
                } catch (e: Exception) {
                    // ข้อความที่อ่านไม่ได้ ข้ามไป
                }
            }
        })

        pc.createOffer(SdpAdapter { offer ->
            val desc = if (useHw) SessionDescription(offer.type, preferH264(offer.description)) else offer
            pc.setLocalDescription(SdpAdapter(), desc)
            sig?.send(
                "OFFER", peer.id,
                JSONObject().put("t", "sdp").put("type", "offer").put("sdp", desc.description)
            )
        }, MediaConstraints())
    }

    private fun dispatchInput(slot: Int, btn: Int, down: Boolean) {
        if (down && !allowed(slot)) return
        val set = held.getOrPut(slot) { mutableSetOf() }
        if (down) set.add(btn) else set.remove(btn)
        onInput(slot, btn, down)
    }

    private fun drop(id: String) {
        val p = peers.remove(id) ?: return
        held[p.slot]?.toList()?.forEach { dispatchInput(p.slot, it, false) }
        try { p.channel?.close() } catch (e: Exception) { }
        try { p.pc?.close() } catch (e: Exception) { }
        if (holder == id) holder = "host"
        sig?.refreshPresence()
        notifyTurn()
    }

    // ---------- จัดลำดับผู้เล่นโดยเจ้าของห้อง ----------
    /** รายชื่อเพื่อนในห้อง: (id, ชื่อ, ช่องปัจจุบัน) */
    fun friendList(): List<Triple<String, String, Int>> =
        peers.values.sortedBy { it.slot }.map { Triple(it.id, it.name, it.slot) }

    fun freeSeats(): List<Int> =
        (0 until poolSize).filter { it != hostSlot && peers.values.none { p -> p.slot == it } }

    /** ช่องที่ย้ายเพื่อนคนนี้ไปได้ พร้อมป้ายบอกว่าว่างหรือสลับกับใคร */
    fun seatOptions(movingId: String): List<Pair<Int, String>> {
        val res = ArrayList<Pair<Int, String>>()
        for (seat in 0 until poolSize) {
            if (seat == hostSlot) continue
            val occ = peers.values.firstOrNull { it.slot == seat }
            if (occ != null && occ.id == movingId) continue
            res.add(seat to ("ผู้เล่น ${seat + 1} " + if (occ == null) "(ว่าง)" else "(สลับกับ ${occ.name})"))
        }
        return res
    }

    private fun releaseSlot(slot: Int) {
        held[slot]?.toList()?.forEach { dispatchInput(slot, it, false) }
    }

    private fun notifySlot(p: Peer) {
        sig?.send("ANSWER", p.id, JSONObject().put("t", "slot").put("slot", p.slot).put("name", p.name))
    }

    /** ย้ายเพื่อนไปช่องใหม่ ถ้าช่องนั้นมีคนอยู่แล้วจะสลับกัน */
    fun moveFriend(id: String, newSlot: Int) {
        val p = peers[id] ?: return
        if (newSlot == p.slot || newSlot == hostSlot || newSlot !in 0 until poolSize) return
        val other = peers.values.firstOrNull { it.slot == newSlot && it.id != id }
        val old = p.slot
        releaseSlot(old)
        releaseSlot(newSlot)
        p.slot = newSlot
        if (other != null) {
            other.slot = old
            notifySlot(other)
        }
        notifySlot(p)
        DebugLog.add("[host] ย้าย ${p.name} ไปผู้เล่น ${newSlot + 1}")
        notifyTurn()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        peers.keys.toList().forEach { drop(it) }
        sig?.close()
        main.removeCallbacks(grabTask)
        main.removeCallbacks(turnTicker)
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
    private lateinit var endBtn: TextView

    private var gameKey = "game"
    private var lastSramHash = 0
    private var hostPort = 0
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
            "👥 จัดลำดับผู้เล่น",
            "📋 บันทึกการเชื่อมต่อ",
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
                    9 -> { showSeatDialog() }
                    10 -> {
                        AlertDialog.Builder(this)
                            .setTitle("บันทึกการเชื่อมต่อ")
                            .setMessage(DebugLog.tail(30))
                            .setPositiveButton("ปิด", null)
                            .show()
                    }
                    11 -> { showSettingsDialog(this) }
                    12 -> { exitGame() }
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

    private var lastBlockToast = 0L

    private fun press(code: Int, down: Boolean) {
        val s = streamer
        if (down && s != null && !s.hostMayPress()) {
            val now = System.currentTimeMillis()
            if (now - lastBlockToast > 1500) {
                lastBlockToast = now
                toast("ยังไม่ถึงตาคุณ")
            }
            return
        }
        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, hostPort)
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
        endBtn = TextView(this).apply {
            text = "⏭ จบตา"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(242, 193, 78)) }
            visibility = View.GONE
            setOnClickListener {
                val st = streamer
                if (st == null || !st.hostEndTurn()) toast("ยังไม่ถึงตาคุณ")
            }
        }
        root.addView(endBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = dp(16)
        })
        bar.addView(statusView)
        bar.addView(streamBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })
    }

    private fun askJoin(name: String, decide: (Boolean, Int) -> Unit) {
        if (isFinishing) {
            decide(false, -1)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("เพื่อนขอเข้าห้อง")
            .setMessage("\"$name\" ขอเข้าห้องเพื่อเล่นด้วย ให้เข้าไหม?")
            .setCancelable(false)
            .setPositiveButton("✅ ยอมรับ") { _, _ -> decide(true, -1) }
            .setNeutralButton("🎯 เลือกช่อง") { _, _ -> askSeatForNew(name, decide) }
            .setNegativeButton("❌ ปฏิเสธ") { _, _ -> decide(false, -1) }
            .show()
    }

    private fun askSeatForNew(name: String, decide: (Boolean, Int) -> Unit) {
        val seats = streamer?.freeSeats() ?: emptyList()
        if (seats.isEmpty()) {
            decide(true, -1)
            return
        }
        val labels = seats.map { "ผู้เล่น ${it + 1}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ให้ \"$name\" เป็นผู้เล่นคนที่")
            .setItems(labels) { _, i -> decide(true, seats[i]) }
            .setCancelable(false)
            .show()
    }

    private fun showSeatDialog() {
        val s = streamer
        if (s == null) {
            toast("ยังไม่ได้เปิดห้อง")
            return
        }
        val friends = s.friendList()
        if (friends.isEmpty()) {
            toast("ยังไม่มีเพื่อนในห้อง")
            return
        }
        val labels = friends.map { "${it.second} (ตอนนี้ผู้เล่น ${it.third + 1})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("เลือกเพื่อนที่จะย้ายช่อง")
            .setItems(labels) { _, i -> pickSeat(friends[i].first, friends[i].second) }
            .setNegativeButton("ปิด", null)
            .show()
    }

    private fun pickSeat(id: String, name: String) {
        val s = streamer ?: return
        val options = s.seatOptions(id)
        if (options.isEmpty()) {
            toast("ไม่มีช่องให้ย้าย (ต้องเปิด Multitap ถ้าจะใช้ผู้เล่น 3–4)")
            return
        }
        val labels = options.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ย้าย \"$name\" ไปเป็น")
            .setItems(labels) { _, i -> s.moveFriend(id, options[i].first) }
            .setNegativeButton("ปิด", null)
            .show()
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
        val prefs = getSharedPreferences("dokapon", MODE_PRIVATE)
        val fps = prefs.getInt("stream_fps", 30)
        val pool = if ((prefs.getString("multitap", "auto") ?: "auto") == "enabled") 4 else 2
        // เกมสุ่มลำดับตาเดินเอง จึงไม่สุ่มช่องจอยซ้ำ: โฮสต์ = ผู้เล่น 1 เพื่อนเรียงตามลำดับที่เข้าห้อง
        val randomSeat = false
        hostPort = if (randomSeat) (0 until pool).random() else 0
        try {
            val s = Streamer(
                this, view, room, fps,
                prefs.getBoolean("auto_accept", false),
                prefs.getInt("stream_q", 2), pool, hostPort, randomSeat,
                prefs.getBoolean("turn_lock", true), prefs.getBoolean("hw_codec", true),
                { _, name, decide -> runOnUiThread { askJoin(name, decide) } },
                { slot, btn, down ->
                    val code = PadMap.code(btn)
                    if (code != null) {
                        retroView?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, slot)
                    }
                },
                { seat, _, lock ->
                    runOnUiThread {
                        endBtn.visibility = if (lock) View.VISIBLE else View.GONE
                        endBtn.alpha = if (seat == hostPort) 1f else 0.45f
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


/**
 * โหมดผู้เล่นแบบเนทีฟทั้งหมด (ไม่ใช้เว็บ/WebView):
 * รับภาพสตรีมจากโฮสต์ด้วย WebRTC แล้วส่งปุ่มกลับทาง DataChannel
 */
class PlayerActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var sig: Signaling? = null
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var renderer: SurfaceViewRenderer? = null
    private lateinit var statusView: TextView
    private lateinit var badge: TextView
    private var hostId = ""
    private var joined = false
    private var finished = false
    private var remoteSet = false
    private val pending = ArrayList<IceCandidate>()
    private val held = HashSet<Int>()
    private var padAlpha = 0.55f
    private var padScale = 1f
    private var swapLocal = false
    private var joinAttempts = 0
    private var joinName = ""
    private var hostSeen = false
    private var mySlot = -1
    private var turnSeat = -1
    private var turnLock = false
    private var turnName = ""
    private var lastBlockToast = 0L
    private lateinit var endBtn: TextView

    private fun refreshBadge() {
        badge.text = "ผู้เล่น ${mySlot + 1}" + if (turnLock && turnName.isNotEmpty()) " · ถึงตา: $turnName" else ""
    }

    private fun onTurn(seat: Int, name: String, lock: Boolean) {
        turnSeat = seat
        turnLock = lock
        turnName = name
        endBtn.visibility = if (lock) View.VISIBLE else View.GONE
        endBtn.alpha = if (seat == mySlot) 1f else 0.45f
        refreshBadge()
    }

    private fun sendEndTurn() {
        val ch = channel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap("{\"e\":1}".toByteArray()), false))
    }

    /** ส่งคำขอเข้าห้องซ้ำทุก 3 วินาที (สูงสุด 8 ครั้ง) จนกว่าโฮสต์จะตอบ */
    private val joinRetry = object : Runnable {
        override fun run() {
            if (finished || joined) return
            if (joinAttempts < 40) {
                joinAttempts++
                sig?.send("OFFER", hostId, JSONObject().put("t", "join").put("name", joinName))
                main.postDelayed(this, 3000)
            }
        }
    }

    private fun show(msg: String) {
        statusView.text = msg + "\n\n" + DebugLog.tail(6)
        statusView.visibility = View.VISIBLE
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

        val room = intent.getStringExtra("room") ?: ""
        val name = intent.getStringExtra("name") ?: ""
        hostId = "dokapon-$room"

        val settings = getSharedPreferences("dokapon", MODE_PRIVATE)
        padAlpha = settings.getInt("ui_alpha", 55) / 100f
        padScale = settings.getInt("ui_scale", 100) / 100f
        swapLocal = settings.getBoolean("swap_ab", false)

        val egl = EglBase.create()
        eglBase = egl
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val r = SurfaceViewRenderer(this)
        r.init(egl.eglBaseContext, null)
        r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        r.setEnableHardwareScaler(true)
        renderer = r
        root.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // SurfaceViewRenderer จะตัดภาพให้เต็มกรอบเมื่อกรอบเป็น MATCH_PARENT (ภาพจึงถูกซูม)
        // จึงกำหนดกรอบเป็นสัดส่วน 4:3 ตรงกลางจอเอง ภาพจะไม่ถูกตัด
        root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w > 0 && h > 0) {
                val vw = minOf(w, h * 4 / 3)
                val vh = vw * 3 / 4
                val lp = r.layoutParams as FrameLayout.LayoutParams
                if (lp.width != vw || lp.height != vh) {
                    lp.width = vw
                    lp.height = vh
                    lp.gravity = Gravity.CENTER
                    r.layoutParams = lp
                }
            }
        }

        statusView = TextView(this).apply {
            text = "กำลังเชื่อมต่อห้อง $room…"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(24), dp(12), dp(24), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.argb(150, 0, 0, 0)) }
        }
        root.addView(statusView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })
        badge = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.BLACK)
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.rgb(242, 193, 78)) }
            visibility = View.GONE
        }
        root.addView(badge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(6)
        })

        buildPad(root)
        setContentView(root)

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()

        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val rnd = java.security.SecureRandom()
        val myId = "friend-" + (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        sig = Signaling(this, myId, room, false, { "{}" }, object : Signaling.Listener {
            override fun onMessage(type: String, src: String?, payload: JSONObject?) {
                if (finished) return
                when (type) {
                    "OPEN" -> {
                        show("เชื่อมต่อเซิร์ฟเวอร์แล้ว กำลังหาห้อง…")
                        joinName = name
                        main.postDelayed({
                            if (!hostSeen && !finished && !joined) fail("ไม่พบห้องนี้ ตรวจรหัสห้อง และรอให้โฮสต์เริ่มเกมก่อน")
                        }, 10000)
                    }
                    "HOST" -> {
                        if (payload != null && payload.optBoolean("online")) {
                            if (!hostSeen) {
                                hostSeen = true
                                show("พบห้องแล้ว กำลังขอเข้าห้อง…")
                                joinAttempts = 0
                                main.removeCallbacks(joinRetry)
                                main.post(joinRetry)
                            }
                        } else if (!joined) {
                            fail("ห้องนี้ปิดอยู่")
                        }
                    }
                    "CLOSED" -> {
                        val m = payload?.optString("msg") ?: ""
                        if (!joined) {
                            show("เชื่อมต่อเซิร์ฟเวอร์จับคู่ไม่ได้ ($m) กำลังลองใหม่…")
                            main.postDelayed({ if (!finished && !joined) sig?.reconnect() }, 2500)
                        }
                    }
                    "ID-TAKEN" -> fail("เชื่อมต่อชนกัน ลองเข้าห้องใหม่อีกครั้ง")
                    "EXPIRE" -> if (!joined) fail("ไม่พบห้องนี้ ตรวจรหัสห้อง และรอให้โฮสต์เริ่มเกมก่อน")
                    "ERROR" -> fail("เซิร์ฟเวอร์จับคู่แจ้งข้อผิดพลาด")
                    "ANSWER" -> {
                        if (payload != null) {
                            val kind = payload.optString("t")
                            if (kind == "joined") onJoined(payload.optInt("slot"))
                            else if (kind == "full") fail("ห้องเต็มแล้ว")
                            else if (kind == "wait") show("โฮสต์ได้รับคำขอแล้ว รอโฮสต์กดยอมรับ…")
                            else if (kind == "denied") fail("โฮสต์ไม่อนุญาตให้เข้าห้อง")
                            else if (kind == "slot") {
                                mySlot = payload.optInt("slot")
                                val n = mySlot + 1
                                refreshBadge()
                                Toast.makeText(this@PlayerActivity, "โฮสต์ย้ายคุณเป็นผู้เล่น $n", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    "OFFER" -> {
                        if (payload != null && payload.optString("t") == "sdp") onOffer(payload.optString("sdp"))
                    }
                    "CANDIDATE" -> {
                        if (payload != null && payload.optString("t") == "ice") {
                            val c = IceCandidate(
                                payload.optString("sdpMid"), payload.optInt("sdpMLineIndex"), payload.optString("candidate")
                            )
                            val conn = pc
                            if (conn != null && remoteSet) conn.addIceCandidate(c) else pending.add(c)
                        }
                    }
                }
            }
        })
        DebugLog.add("[player] เข้าห้อง $room (host id $hostId)")
        sig?.connect()

        main.postDelayed({
            if (!joined && !finished) fail("เข้าห้องไม่สำเร็จ ตรวจรหัสห้อง และตรวจว่าโฮสต์เริ่มเกมแล้ว")
        }, 120000)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leave()
            }
        })
    }

    private fun onJoined(slot: Int) {
        joined = true
        mySlot = slot
        badge.text = "ผู้เล่น ${slot + 1}"
        badge.visibility = View.VISIBLE
        show("โฮสต์ตอบรับแล้ว กำลังรอภาพ…")
    }

    private fun onOffer(sdp: String) {
        DebugLog.add("[player] ได้รับ OFFER จากโฮสต์")
        val f = factory ?: return
        pc?.close()
        remoteSet = false
        val servers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:eu-0.turn.peerjs.com:3478")
                .setUsername("peerjs").setPassword("peerjsp").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        val c = f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                DebugLog.add("[player] ICE: $s")
                main.post {
                    when (s) {
                        PeerConnection.IceConnectionState.FAILED -> fail("เชื่อมต่อภาพไม่สำเร็จ ลองสลับ Wi-Fi/เน็ตมือถือ")
                        PeerConnection.IceConnectionState.DISCONNECTED -> statusView.apply {
                            text = "สัญญาณสะดุด กำลังพยายามต่อใหม่…"
                            visibility = View.VISIBLE
                        }
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> statusView.visibility = View.GONE
                        else -> {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                sig?.send(
                    "CANDIDATE", hostId,
                    JSONObject().put("t", "ice").put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                )
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {
                channel = d
                d?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(l: Long) {}
                    override fun onStateChange() {}
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        try {
                            val bytes = ByteArray(buffer.data.remaining())
                            buffer.data.get(bytes)
                            val j = JSONObject(String(bytes))
                            if (j.has("turn")) {
                                val seat = j.optInt("turn")
                                val nm = j.optString("name")
                                val lock = j.optBoolean("lock", false)
                                main.post { onTurn(seat, nm, lock) }
                            }
                        } catch (e: Exception) {
                            // ข้อความที่อ่านไม่ได้ ข้ามไป
                        }
                    }
                })
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {
                if (r != null) {
                    try {
                        val m = r.javaClass.getMethod("setJitterBufferMinimumDelay", java.lang.Double::class.java)
                        m.invoke(r, 0.0)
                    } catch (e: Throwable) {
                        // ไลบรารีเวอร์ชันนี้ไม่มีตัวเลือกนี้ ข้ามไป
                    }
                }
                val t = r?.track()
                if (t is VideoTrack) {
                    main.post {
                        renderer?.let { t.addSink(it) }
                        statusView.visibility = View.GONE
                    }
                }
            }
        }) ?: return
        pc = c

        c.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                main.post {
                    remoteSet = true
                    pending.forEach { c.addIceCandidate(it) }
                    pending.clear()
                }
                c.createAnswer(SdpAdapter { ans ->
                    c.setLocalDescription(SdpAdapter(), ans)
                    sig?.send(
                        "ANSWER", hostId,
                        JSONObject().put("t", "sdp").put("type", "answer").put("sdp", ans.description)
                    )
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun fail(msg: String) {
        if (finished) return
        statusView.text = "$msg (แตะเพื่อออก)\n\n" + DebugLog.tail(10)
        statusView.visibility = View.VISIBLE
        statusView.setOnClickListener { leave() }
    }

    private fun leave() {
        if (finished) return
        sig?.send("LEAVE", hostId, JSONObject())
        finish()
    }

    override fun onPause() {
        held.toList().forEach { sendBtn(it, false) }
        super.onPause()
    }

    override fun onDestroy() {
        finished = true
        main.removeCallbacksAndMessages(null)
        try { sig?.close() } catch (e: Exception) { }
        try { channel?.close() } catch (e: Exception) { }
        try { pc?.close() } catch (e: Exception) { }
        try { renderer?.release() } catch (e: Exception) { }
        try { factory?.dispose() } catch (e: Exception) { }
        try { eglBase?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    // ---------- ส่งปุ่มให้โฮสต์ (รหัสปุ่มแบบ RetroPad) ----------
    private fun sendBtn(idx: Int, down: Boolean) {
        if (down && turnLock && turnSeat != mySlot) {
            val now = System.currentTimeMillis()
            if (now - lastBlockToast > 1500) {
                lastBlockToast = now
                Toast.makeText(this, "ยังไม่ถึงตาคุณ", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (down == held.contains(idx)) return
        if (down) held.add(idx) else held.remove(idx)
        val ch = channel ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        val j = JSONObject().put("b", idx).put("d", if (down) 1 else 0).toString()
        ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(j.toByteArray()), false))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun pad(label: String, idx: Int, circle: Boolean = false): TextView {
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
                        sendBtn(idx, true)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = padAlpha
                        sendBtn(idx, false)
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

    private fun buildPad(root: FrameLayout) {
        val cell = (dp(54) * padScale).toInt()
        val gap = dp(4)
        val step = cell + gap
        val size = cell * 3 + gap * 2
        val margin = dp(16)

        val dpad = FrameLayout(this)
        place(dpad, pad("▲", 4), step, 0, cell, cell)
        place(dpad, pad("◀", 6), 0, step, cell, cell)
        place(dpad, pad("▶", 7), step * 2, step, cell, cell)
        place(dpad, pad("▼", 5), step, step * 2, cell, cell)
        root.addView(dpad, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = margin
            bottomMargin = margin
        })

        val face = FrameLayout(this)
        place(face, pad("△", 9, true), step, 0, cell, cell)
        place(face, pad("□", 1, true), 0, step, cell, cell)
        place(face, pad("○", if (swapLocal) 0 else 8, true), step * 2, step, cell, cell)
        place(face, pad("✕", if (swapLocal) 8 else 0, true), step, step * 2, cell, cell)
        root.addView(face, FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = margin
            bottomMargin = margin
        })

        val sw = (dp(84) * padScale).toInt()
        val sh = (dp(40) * padScale).toInt()
        val top = dp(10)
        fun shoulder(label: String, idx: Int, left: Boolean, row: Int) {
            root.addView(pad(label, idx), FrameLayout.LayoutParams(sw, sh).apply {
                gravity = Gravity.TOP or (if (left) Gravity.START else Gravity.END)
                if (left) leftMargin = margin else rightMargin = margin
                topMargin = top + row * (sh + dp(6))
            })
        }
        shoulder("L2", 12, true, 0)
        shoulder("L1", 10, true, 1)
        shoulder("R2", 13, false, 0)
        shoulder("R1", 11, false, 1)

        endBtn = TextView(this).apply {
            text = "⏭ จบตา"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.rgb(242, 193, 78)) }
            visibility = View.GONE
            setOnClickListener {
                if (!turnLock) return@setOnClickListener
                if (turnSeat != mySlot) {
                    Toast.makeText(this@PlayerActivity, "ยังไม่ถึงตาคุณ", Toast.LENGTH_SHORT).show()
                } else {
                    sendEndTurn()
                }
            }
        }
        root.addView(endBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            rightMargin = margin
        })

        val mid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mid.addView(pad("Select", 2), LinearLayout.LayoutParams(dp(78), dp(34)).apply { rightMargin = dp(10) })
        mid.addView(pad("Start", 3), LinearLayout.LayoutParams(dp(78), dp(34)))
        root.addView(mid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = margin
        })
    }
}
