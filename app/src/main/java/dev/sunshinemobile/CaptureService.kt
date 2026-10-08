package dev.sunshinemobile

import android.app.*
import android.content.*
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.Surface

class CaptureService : Service() {
 private val videoPipeline = EncodedVideoPipeline()
 private var projection: MediaProjection? = null
 private var codec: MediaCodec? = null
 private var surface: Surface? = null
 private var display: android.hardware.display.VirtualDisplay? = null
 private var thread: Thread? = null
 private val running = java.util.concurrent.atomic.AtomicBoolean(false)
 override fun onBind(intent: Intent?): IBinder? = null
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  val channelId = "capture"
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId,"Screen capture",NotificationManager.IMPORTANCE_LOW))
  val notification = Notification.Builder(this, channelId).setContentTitle("Sunshine Mobile").setContentText("Screen capture running (not streaming)").setSmallIcon(android.R.drawable.ic_media_play).build()
  startForeground(1,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
  val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
  val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data",Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
  if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
  try {
   val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   projection = mgr.getMediaProjection(resultCode,data)
   projection?.registerCallback(object: MediaProjection.Callback() { override fun onStop() { stopSelf() } },Handler(Looper.getMainLooper()))
   val metrics = resources.displayMetrics
   val width = ((metrics.widthPixels.coerceAtMost(1920) + 15) / 16) * 16
   val height = ((metrics.heightPixels.coerceAtMost(1080) + 15) / 16) * 16
   val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,width,height).apply {
    setInteger(MediaFormat.KEY_COLOR_FORMAT,0x7F000789)
    setInteger(MediaFormat.KEY_BIT_RATE,6_000_000)
    setInteger(MediaFormat.KEY_FRAME_RATE,60)
    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
   }
   codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); surface = createInputSurface(); start() }
   display = projection?.createVirtualDisplay("SunshineMobileCapture",width,height,metrics.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,null)
   running.set(true)
   thread = Thread {
    val info = MediaCodec.BufferInfo()
    while(running.get()) {
     try {
      val index = codec?.dequeueOutputBuffer(info,10_000) ?: -1
      if (index >= 0) {
       val encoder = codec ?: break
       try { encoder.getOutputBuffer(index)?.let { videoPipeline.onEncodedBuffer(it, info) } }
       finally { encoder.releaseOutputBuffer(index,false) }
      } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
       codec?.outputFormat?.let { videoPipeline.onFormatChanged(it) }
      }
     } catch (_: Exception) { break }
    }
   }.apply { start() }
  } catch (_: Exception) { stopSelf() }
  return START_NOT_STICKY
 }
 override fun onDestroy() {
  running.set(false); display?.release(); display=null; surface?.release(); surface=null
  try { codec?.stop() } catch (_: Exception) {}
  codec?.release(); codec=null; projection?.stop(); projection=null
  super.onDestroy()
 }
}
