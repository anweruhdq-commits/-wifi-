package com.anwar.walkie

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * محرك الصوت والشبكة: بث UDP Multicast على الشبكة المحلية.
 * الحزمة: [MAGIC:1][TYPE:1][CHANNEL:1][UID:4][CODE_HASH:4][PAYLOAD...]
 * حزم المكالمات تبدأ حمولتها بـ [TARGET_UID:4].
 */
class Radio(private val ctx: Context, val uid: Int) {

    data class Peer(val uid: Int, val name: String, val channel: Int, val seen: Long)

    @Volatile var channel = 1
    @Volatile var listen = true
    @Volatile var boost = 1.5f
    @Volatile var name = "مستخدم"
    @Volatile var code = "88888888"
    @Volatile var muted = false
    var onPeers: (List<Peer>) -> Unit = {}
    /** state: 0 خامل، 1 اتصال صادر، 2 اتصال وارد، 3 في مكالمة */
    var onCall: (state: Int, peer: Int) -> Unit = { _, _ -> }

    @Volatile var callState = 0
        private set
    @Volatile var callPeer = 0
        private set
    @Volatile private var callTs = 0L

    private val peers = ConcurrentHashMap<Int, Peer>()
    private val io = Executors.newSingleThreadExecutor()
    private val recActive = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var talking = false
    private var sock: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var track: AudioTrack? = null
    private val group: InetAddress = InetAddress.getByName(GROUP)

    fun peerName(id: Int) = peers[id]?.name ?: "مستخدم"

    // ------------------------------------------------------------ دورة الحياة

    fun start() {
        if (running) return
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        lock = wm.createMulticastLock("walkie").apply { setReferenceCounted(false); acquire() }

        sock = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
            val ni = pickInterface()
            if (ni != null) {
                networkInterface = ni
                joinGroup(InetSocketAddress(group, PORT), ni)
            } else {
                @Suppress("DEPRECATION") joinGroup(group)
            }
            timeToLive = 4
        }

        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }

        running = true
        Thread({ receiveLoop() }, "walkie-rx").start()
        Thread({ heartbeatLoop() }, "walkie-hb").start()
    }

    fun stop() {
        if (callState != 0) endCall()
        io.shutdown()
        try { io.awaitTermination(300, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
        running = false
        talking = false
        try { sock?.close() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        try { lock?.release() } catch (_: Exception) {}
        sock = null; track = null; lock = null
        peers.clear()
        onPeers(emptyList())
    }

    /** يفضّل واجهة الهوتسبوت (ap/swlan) ثم wlan، ويتجاهل غير الشبكية. */
    private fun pickInterface(): NetworkInterface? {
        val all = try { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val ok = all.filter { ni ->
            try {
                ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                    ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress } &&
                    Regex("^(ap|swlan|softap|wlan|eth)\\d*").containsMatchIn(ni.name)
            } catch (_: Exception) { false }
        }
        return ok.firstOrNull { Regex("^(ap|swlan|softap)").containsMatchIn(it.name) }
            ?: ok.firstOrNull { it.name.startsWith("wlan") } ?: ok.firstOrNull()
    }

    // ------------------------------------------------------------ التحدث الجماعي

    fun talk(on: Boolean) {
        if (!running) return
        talking = on
        if (on) startRec()
    }

    // ------------------------------------------------------------ المكالمات

    fun startCall(peer: Int) {
        if (!running || callState != 0) return
        setCall(1, peer)
        sendCtl(T_CALL_REQ, peer)
    }

    fun accept() {
        if (callState != 2) return
        sendCtl(T_CALL_ACC, callPeer)
        setCall(3, callPeer)
        startRec()
    }

    /** إنهاء المكالمة، أو رفضها إن كانت واردة، أو إلغاؤها إن كانت صادرة. */
    fun endCall() {
        if (callState == 0) return
        sendCtl(if (callState == 2) T_CALL_REJ else T_CALL_END, callPeer)
        setCall(0, 0)
    }

    private fun setCall(s: Int, p: Int) {
        callState = s; callPeer = p; callTs = System.currentTimeMillis()
        if (s == 0) muted = false
        onCall(s, p)
    }

    private fun handleCtl(type: Byte, from: Int) {
        when (type) {
            T_CALL_REQ -> if (callState == 0) setCall(2, from)
                          else if (callPeer != from) sendCtl(T_CALL_REJ, from)
            T_CALL_ACC -> if (callState == 1 && callPeer == from) { setCall(3, from); startRec() }
            T_CALL_REJ, T_CALL_END -> if (callState != 0 && callPeer == from) setCall(0, 0)
        }
    }

    // ------------------------------------------------------------ الشبكة

    private fun hash() = code.hashCode()

    private fun send(type: Byte, payload: ByteArray, len: Int = payload.size) {
        val s = sock ?: return
        val bb = ByteBuffer.allocate(HDR + len)
        bb.put(MAGIC).put(type).put(channel.toByte()).putInt(uid).putInt(hash()).put(payload, 0, len)
        try { s.send(DatagramPacket(bb.array(), bb.capacity(), group, PORT)) } catch (_: Exception) {}
    }

    /** إرسال تحكم من أي خيط (حتى الرئيسي) عبر منفّذ خلفي. */
    private fun sendCtl(type: Byte, target: Int) {
        val p = ByteBuffer.allocate(4).putInt(target).array()
        try { io.execute { send(type, p) } } catch (_: Exception) {}
    }

    private fun receiveLoop() {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try { sock?.receive(pkt) ?: break } catch (_: Exception) { if (!running) break else continue }
            if (pkt.length < HDR || buf[0] != MAGIC) continue
            val bb = ByteBuffer.wrap(buf, 0, pkt.length)
            bb.get()
            val type = bb.get()
            val ch = bb.get().toInt()
            val id = bb.getInt()
            val h = bb.getInt()
            if (id == uid || h != hash()) continue
            val len = pkt.length - HDR
            when (type) {
                T_HB -> {
                    peers[id] = Peer(id, String(buf, HDR, len, Charsets.UTF_8), ch, System.currentTimeMillis())
                    onPeers(peers.values.sortedBy { it.name })
                }
                T_AUDIO -> if (listen && ch == channel && callState != 3) play(buf, HDR, len)
                T_CALL_REQ, T_CALL_ACC, T_CALL_REJ, T_CALL_END -> {
                    if (len >= 4 && ByteBuffer.wrap(buf, HDR, 4).int == uid) handleCtl(type, id)
                }
                T_CALL_AUDIO -> {
                    if (callState == 3 && id == callPeer && len > 4 && ByteBuffer.wrap(buf, HDR, 4).int == uid)
                        play(buf, HDR + 4, len - 4)
                }
            }
        }
    }

    private fun heartbeatLoop() {
        while (running) {
            send(T_HB, name.toByteArray(Charsets.UTF_8))
            val now = System.currentTimeMillis()
            if (peers.values.removeIf { now - it.seen > 7000 }) onPeers(peers.values.sortedBy { it.name })
            // انتهاء مهلة الرنين (30 ثانية)
            if ((callState == 1 || callState == 2) && now - callTs > 30_000) endCall()
            // انقطاع الطرف الآخر أثناء المكالمة
            if (callState == 3 && peers[callPeer] == null && now - callTs > 10_000) setCall(0, 0)
            try { Thread.sleep(2000) } catch (_: InterruptedException) { return }
        }
    }

    // ------------------------------------------------------------ الصوت

    private fun play(buf: ByteArray, off: Int, len: Int) {
        val t = track ?: return
        val n = len / 2
        val src = ByteBuffer.wrap(buf, off, len).order(ByteOrder.LITTLE_ENDIAN)
        val out = ShortArray(n)
        val b = boost
        for (i in 0 until n) out[i] = (src.short * b).toInt().coerceIn(-32768, 32767).toShort()
        t.write(out, 0, n)
    }

    private fun startRec() {
        if (recActive.compareAndSet(false, true)) {
            Thread({ try { recordLoop() } finally { recActive.set(false) } }, "walkie-tx").start()
        }
    }

    private fun recordLoop() {
        val frame = RATE / 50 // 20ms
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 4)
            )
        } catch (_: SecurityException) { talking = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); talking = false; return }
        val pcm = ShortArray(frame)
        val bytes = ByteArray(frame * 2)
        rec.startRecording()
        try {
            while (running && (talking || callState == 3)) {
                val r = rec.read(pcm, 0, frame)
                if (r <= 0) continue
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, r)
                if (callState == 3) {
                    if (!muted) {
                        val p = ByteBuffer.allocate(4 + r * 2).putInt(callPeer).put(bytes, 0, r * 2).array()
                        send(T_CALL_AUDIO, p)
                    }
                } else {
                    send(T_AUDIO, bytes, r * 2)
                }
            }
        } finally {
            rec.stop(); rec.release()
        }
    }

    companion object {
        const val GROUP = "239.255.77.77"
        const val PORT = 45678
        const val RATE = 16000
        const val MAGIC: Byte = 0x57
        const val T_AUDIO: Byte = 0
        const val T_HB: Byte = 1
        const val T_CALL_REQ: Byte = 2
        const val T_CALL_ACC: Byte = 3
        const val T_CALL_REJ: Byte = 4
        const val T_CALL_END: Byte = 5
        const val T_CALL_AUDIO: Byte = 6
        const val HDR = 11
    }
}
