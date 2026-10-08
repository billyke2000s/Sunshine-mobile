package dev.sunshinemobile

import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
 private val requestCode = 42
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32,64,32,32) }
  layout.addView(TextView(this).apply { text = "Sunshine Mobile — prototype\n\nLocal screen capture only. Moonlight/GameStream pairing and transport are NOT implemented yet."; textSize = 19f })
  layout.addView(Button(this).apply { text = "Start screen capture"; setOnClickListener {
   val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
   startActivityForResult(mgr.createScreenCaptureIntent(), requestCode)
  } })
  layout.addView(Button(this).apply { text = "Stop capture"; setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) } })
  setContentView(layout)
 }
 @Deprecated("Android callback retained for minimal prototype")
 override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
  super.onActivityResult(requestCode, resultCode, data)
  if (requestCode == this.requestCode && resultCode == RESULT_OK && data != null) {
   startForegroundService(Intent(this, CaptureService::class.java).putExtra("resultCode", resultCode).putExtra("data", data))
  }
 }
}
