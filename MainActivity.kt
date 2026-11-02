package com.kamil.xiaozhi

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val http = OkHttpClient()
    private var ws: WebSocket? = null
    private var sid = ""
    @Volatile private var talking = false
    @Volatile private var speaking = false
    private var track: AudioTrack? = null
    private var dec: OpusDecoder? = null
    private var rate = 24000
    private lateinit var status: TextView
    private lateinit var logv: TextView
    private lateinit var talk: Button
    private lateinit var devId: String
    private lateinit var cid: String

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("x", MODE_PRIVATE)
        devId = p.getString("d", null)
            ?: (0 until 6).joinToString(":") { "%02x".format((0..255).random()) }
                .also { p.edit().putString("d", it).apply() }
        cid = p.getString("c", null)
            ?: UUID.randomUUID().toString().also { p.edit().putString("c", it).apply() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply { textSize = 18f; text = "Tap Connect" }
        logv = TextView(this).apply { textSize = 16f }
        val conn = Button(this).apply { text = "Connect"; setOnClickListener { connect() } }
        talk = Button(this).apply {
            text = "Hold to talk"
            textSize = 20f
            isEnabled = false
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> startTalk()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopTalk()
                }
                true
            }
        }
        root.addView(status)
        root.addView(conn)
        root.addView(ScrollView(this).apply { addView(logv) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(talk, LinearLayout.LayoutParams(-1, 280))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun say(s: String) = runOnUiThread { status.text = s }
    private fun addLog(s: String) = runOnUiThread { logv.append(s + "\n\n") }

    private fun connect() {
        say("Connecting…")
        thread {
            try {
                val body = """{"application":{"version":"1.6.0"},"board":{"type":"android","name":"xiaozhi-android"},"mac_address":"$devId"}"""
                val r = Request.Builder().url("https://api.tenclass.net/xiaozhi/ota/")
                    .header("Device-Id", devId).header("Client-Id", cid)
                    .post(body.toRequestBody("application/json".toMediaType())).build()
                val j = JSONObject(http.newCall(r).execute().use { it.body!!.string() })
                if (j.has("activation")) {
                    val code = j.getJSONObject("activation").optString("code")
                    say("Enter this code in the xiaozhi.me console: $code\nThen tap Connect again.")
                    return@thread
                }
                val w = j.getJSONObject("websocket")
                open(w.getString("url"), w.optString("token"))
            } catch (e: Exception) {
                say("Error: " + e.message)
            }
        }
    }

    private fun open(url: String, token: String) {
        val rq = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Protocol-Version", "1")
            .header("Device-Id", devId)
            .header("Client-Id", cid).build()
        ws = http.newWebSocket(rq, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"hello","version":1,"transport":"websocket","audio_params":{"format":"opus","sample_rate":16000,"channels":1,"frame_duration":60}}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) = onText(JSONObject(text))
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onAudio(bytes.toByteArray())
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                say("Connection failed: " + t.message)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                say("Disconnected")
                runOnUiThread { talk.isEnabled = false }
            }
        })
    }

    private fun onText(m: JSONObject) {
        when (m.optString("type")) {
            "hello" -> {
                sid = m.optString("session_id")
                rate = m.optJSONObject("audio_params")?.optInt("sample_rate", 24000) ?: 24000
                dec = OpusDecoder(rate, 1)
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                track?.release()
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(maxOf(min, rate * 2))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                track!!.play()
                say("Connected. Hold the button and talk.")
                runOnUiThread { talk.isEnabled = true }
            }
            "stt" -> addLog("You: " + m.optString("text"))
            "tts" -> when (m.optString("state")) {
                "start" -> speaking = true
                "sentence_start" -> addLog("AI: " + m.optString("text"))
                "stop" -> speaking = false
            }
        }
    }

    private fun onAudio(d: ByteArray) {
        val dc = dec ?: return
        val pcm = ShortArray(rate * 120 / 1000)
        val n = dc.decode(d, 0, d.size, pcm, 0, pcm.size, false)
        track?.write(pcm, 0, n)
    }

    private fun startTalk() {
        if (sid.isEmpty() || talking) return
        if (speaking) {
            ws?.send("""{"session_id":"$sid","type":"abort"}""")
            track?.pause(); track?.flush(); track?.play()
            speaking = false
        }
        talking = true
        ws?.send("""{"session_id":"$sid","type":"listen","state":"start","mode":"manual"}""")
        say("Listening…")
        thread {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 7680)
            )
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
            val enc = OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP)
            enc.bitrate = 24000
            val pcm = ShortArray(960)
            val out = ByteArray(1275)
            rec.startRecording()
            while (talking) {
                var got = 0
                while (got < 960 && talking) {
                    val r = rec.read(pcm, got, 960 - got)
                    if (r <= 0) break
                    got += r
                }
                if (got == 960) {
                    val n = enc.encode(pcm, 0, 960, out, 0, out.size)
                    ws?.send(out.toByteString(0, n))
                }
            }
            rec.stop()
            rec.release()
        }
    }

    private fun stopTalk() {
        if (!talking) return
        talking = false
        ws?.send("""{"session_id":"$sid","type":"listen","state":"stop"}""")
        say("Thinking…")
    }
}
