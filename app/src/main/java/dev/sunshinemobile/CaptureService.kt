package dev.sunshinemobile

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Surface
import dev.sunshinemobile.protocol.Avc
import dev.sunshinemobile.protocol.HostServer
import dev.sunshinemobile.protocol.HostSession
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureService : Service(), HostSession.Capture {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var audio: AudioPipeline? = null
    private var active: HostSession? = null
    private var config = byteArrayOf()
    private val encoderThread = HandlerThread("capture-codec")
    private lateinit var handler: Handler
    private var discovery: GameStreamDiscovery? = null
    private var server: HostServer? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null
    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); encoderThread.start(); handler = Handler(encoderThread.looper) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_NOT_STICKY
        val channel = "capture"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Moonlight host",NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this,channel).setContentTitle("Sunshine Mobile")
            .setContentText("Moonlight host active").setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null,"Stop host",stopIntent).build()).build()
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startForeground(1,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val result = intent?.getIntExtra("resultCode",Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        if (result != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(result,data)
            projection!!.registerCallback(object : MediaProjection.Callback() { override fun onStop() { HostRuntime.update("Screen capture permission ended"); stopSelf() } },Handler(Looper.getMainLooper()))
            // Android 14+ permits one virtual display per consent token. Reuse it on reconnect.
            display = projection!!.createVirtualDisplay("SunshineMobile",1280,720,resources.displayMetrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler)
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SunshineMobile:host").apply { acquire() }
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"SunshineMobile:stream").apply { acquire() }
            val identity = AndroidIdentity.load(this)
            server = HostServer(identity,this,{ HostRuntime.pairing(it) },{ HostRuntime.update(it) }).also { it.start() }
            HostRuntime.server = server
            discovery = GameStreamDiscovery(this).also { it.start("Sunshine Mobile") }
        } catch (e: Exception) { HostRuntime.update("Host could not start: ${e.message}"); stopSelf() }
        return START_NOT_STICKY
    }
    override fun start(s: HostSession) { handler.post {
        if (stopping.get() || s.isClosed) return@post
        releaseEncoder()
        try {
            active = s; config = byteArrayOf()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,s.width,s.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,s.bitrate*1000)
                setInteger(MediaFormat.KEY_FRAME_RATE,s.fps)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,s.fps.toFloat())
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,1_000_000L/s.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                setInteger(MediaFormat.KEY_BITRATE_MODE,MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec=encoder
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec,index: Int) = Unit
                override fun onOutputFormatChanged(c: MediaCodec,f: MediaFormat) {
                    config = listOfNotNull(f.getByteBuffer("csd-0"),f.getByteBuffer("csd-1")).flatMap { buffer ->
                        val view=buffer.duplicate(); val bytes=ByteArray(view.remaining()); view.get(bytes); Avc.annexB(bytes).toList()
                    }.toByteArray()
                }
                override fun onError(c: MediaCodec,e: MediaCodec.CodecException) { if (active===s) s.fail("Encoder: ${e.diagnosticInfo}") }
                override fun onOutputBufferAvailable(c: MediaCodec,index: Int,info: MediaCodec.BufferInfo) {
                    try {
                        if (active!==s || s.isClosed || info.size<=0) return
                        val buffer=c.getOutputBuffer(index) ?: return
                        val view=buffer.duplicate(); view.position(info.offset); view.limit(info.offset+info.size)
                        val bytes=ByteArray(info.size); view.get(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) { config=Avc.annexB(bytes); return }
                        val key=info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val annex = Avc.annexB(bytes)
                        val packet=if (key) config+annex else annex
                        s.transport.video(packet,info.presentationTimeUs,key)
                    } catch (e: Exception) { if (active===s && !s.isClosed) s.fail("Video failed: ${e.message}") }
                    finally { try { c.releaseOutputBuffer(index,false) } catch (_: Exception) {} }
                }
            },handler)
            encoder.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface=encoder.createInputSurface(); encoder.start()
            display!!.resize(s.width,s.height,resources.displayMetrics.densityDpi)
            display!!.surface=surface
            audio=AudioPipeline(this,projection!!,s)
            requestIdr()
            HostRuntime.update("Streaming ${s.width}×${s.height} at ${s.fps} FPS • H.264 / Opus stereo")
        } catch (e: Exception) { releaseEncoder(); s.fail("Capture failed: ${e.message}") }
    } }
    override fun stop(s: HostSession) { handler.post { if (active===s) releaseEncoder() } }
    override fun requestIdr() { handler.post { try { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME,0) }) } catch (_: Exception) {} } }
    private fun releaseEncoder() {
        active=null
        display?.surface=null
        audio?.close(); audio=null
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec=null; surface?.release(); surface=null
    }
    override fun onDestroy() {
        stopping.set(true)
        HostRuntime.server=null; HostRuntime.pending=null
        try { discovery?.stop() } catch (_: Exception) {}
        server?.close(); server=null
        val done=CountDownLatch(1)
        handler.post { try { releaseEncoder(); display?.release(); display=null; projection?.stop(); projection=null } finally { done.countDown() } }
        done.await(3,TimeUnit.SECONDS); encoderThread.quitSafely()
        wake?.let { if(it.isHeld) it.release() }; wifi?.let { if(it.isHeld) it.release() }
        HostRuntime.update("Host stopped")
        super.onDestroy()
    }
}
