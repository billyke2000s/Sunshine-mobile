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

class CaptureService : Service(), HostSession.Capture {
    @Volatile private var projection: MediaProjection? = null
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
    companion object {
        const val ACTION_START = "dev.sunshinemobile.START"
        const val ACTION_CAPTURE = "dev.sunshinemobile.CAPTURE"
    }
    private fun foreground(capturing: Boolean) {
        val channel = "capture"
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"Moonlight host",NotificationManager.IMPORTANCE_LOW))
        val stopIntent=PendingIntent.getService(this,1,Intent(this,CaptureService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val openIntent=PendingIntent.getActivity(this,2,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,channel).setContentTitle("Sunshine Mobile")
            .setContentText(if(capturing) "Screen sharing enabled" else "Network host online; screen sharing not enabled")
            .setContentIntent(openIntent).setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(Notification.Action.Builder(null,"Stop host",stopIntent).build()).build()
        val types=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            if(capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        startForeground(1,notification,types)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action=="stop") { HostRuntime.update("Host stopped by you"); stopSelf(); return START_NOT_STICKY }
        val data=if(Build.VERSION.SDK_INT>=33) intent?.getParcelableExtra("data",Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        val capture=intent?.action==ACTION_CAPTURE && intent.getIntExtra("resultCode",Activity.RESULT_CANCELED)==Activity.RESULT_OK && data!=null
        try {
            // Advertise/control the TV connection before capture consent. Projection type is
            // added only after Android returns a fresh, user-approved capture token.
            foreground(capture || projection!=null)
        } catch(e: Exception) {
            HostRuntime.starting=false
            HostRuntime.fail("Foreground host permission failed",e)
            stopSelf(); return START_NOT_STICKY
        }
        handler.post {
            if(stopping.get()) return@post
            if(server==null) {
                try {
                    HostRuntime.update("Loading persistent host certificate")
                    val identity=AndroidIdentity.load(this)
                    HostRuntime.update("Opening GameStream HTTP, HTTPS and RTSP listeners")
                    server=HostServer(identity,this,{ HostRuntime.pairing(it) },{ HostRuntime.update(it) }).also { it.start() }
                    HostRuntime.server=server
                    HostRuntime.starting=false
                    discovery=GameStreamDiscovery(this).also { it.start("Sunshine Mobile") }
                    val manager=applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                    wifi=manager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"SunshineMobile:stream").apply { acquire() }
                    HostRuntime.update("Network host online — ready for Moonlight pairing")
                } catch(e: Exception) {
                    HostRuntime.starting=false
                    HostRuntime.fail("Network host startup failed",e)
                    stopSelf(); return@post
                }
            }
            if(capture && projection==null) {
                try {
                    HostRuntime.update("Creating Android screen-capture session")
                    val granted=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,data!!)
                        ?: throw IllegalStateException("Android did not return a projection")
                    projection=granted
                    granted.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() { handler.post {
                            if(projection===granted && !stopping.get()) {
                                server?.session()?.let { server?.end(it,"Android ended screen sharing") }
                                releaseProjection()
                                foreground(false)
                                HostRuntime.update("Android ended screen sharing — host remains online. Unlock the phone and tap Enable screen sharing.")
                            }
                        } }
                    },handler)
                    // One display per consent token on Android 14+. Reuse it on reconnect.
                    display=granted.createVirtualDisplay("SunshineMobile",1280,720,resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler)
                    wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SunshineMobile:host").apply { acquire() }
                    HostRuntime.captureReady=true
                    HostRuntime.update("Host online — screen sharing enabled; launch Phone screen in Moonlight")
                } catch(e: Exception) {
                    releaseProjection()
                    try { foreground(false) } catch(_: Exception) {}
                    HostRuntime.fail("Screen-capture setup failed; host remains online",e)
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun ready() = HostRuntime.captureReady && projection!=null && !stopping.get()
    private fun releaseProjection() {
        HostRuntime.captureReady=false
        releaseEncoder()
        try { display?.release() } catch(_: Exception) {}; display=null
        val old=projection; projection=null
        try { old?.stop() } catch(_: Exception) {}
        wake?.let { if(it.isHeld) it.release() }; wake=null
    }
    override fun start(s: HostSession) { handler.post {
        if (stopping.get() || s.isClosed) return@post
        releaseEncoder()
        try {
            active = s; config = byteArrayOf()
            HostRuntime.encodedFrames=0
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec=encoder
            val caps=encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val profiles=caps.profileLevels.map { it.profile }
            val profile=listOf(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline).firstOrNull { it in profiles }
                ?: throw IllegalStateException("No supported H.264 encoder profile")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,s.width,s.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,s.bitrate*1000)
                setInteger(MediaFormat.KEY_FRAME_RATE,s.fps)
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER,s.fps.toFloat())
                setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,1_000_000L/s.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
                setInteger(MediaFormat.KEY_PROFILE,profile)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES,0)
                setInteger(MediaFormat.KEY_BITRATE_MODE,if(caps.encoderCapabilities.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD,MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_RANGE,MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER,MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            }
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
                        HostRuntime.encodedFrames++
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
        HostRuntime.starting=false; HostRuntime.captureReady=false
        HostRuntime.server=null; HostRuntime.pending=null
        try { discovery?.stop() } catch(_: Exception) {}
        HostRuntime.discoveryStatus="Not advertised"
        // Do not replace a startup failure with an uninformative "Host stopped".
        handler.post {
            server?.close(); server=null
            releaseProjection()
            wifi?.let { if(it.isHeld) it.release() }; wifi=null
            encoderThread.quitSafely()
        }
        super.onDestroy()
    }
}
