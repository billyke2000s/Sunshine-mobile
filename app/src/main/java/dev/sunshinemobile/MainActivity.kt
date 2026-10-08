package dev.sunshinemobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pin: EditText
    private val observer: () -> Unit = { status.text=HostRuntime.status }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,64,32,32) }
        layout.addView(TextView(this).apply { text="Sunshine Mobile"; textSize=28f })
        layout.addView(TextView(this).apply { text="Share this phone with Moonlight on your TV. Start the host, pair in Moonlight, then enter the TV’s PIN here. Launch Phone screen.\n\nH.264 SDR • up to 1080p60 • stereo audio"; textSize=16f })
        val addresses = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).flatMap { java.util.Collections.list(it.inetAddresses) }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }.joinToString(" • ") { it.hostAddress ?: "" }
        layout.addView(TextView(this).apply { text="Manual Moonlight address: $addresses" })
        status=TextView(this).apply { textSize=18f; setPadding(0,24,0,24) }; layout.addView(status)
        layout.addView(Button(this).apply { text="Start host"; setOnClickListener {
            if (HostRuntime.server!=null) return@setOnClickListener
            val permissions=mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(),43) else captureConsent()
        } })
        pin=EditText(this).apply { hint="4-digit PIN from Moonlight"; inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; filters=arrayOf(android.text.InputFilter.LengthFilter(4)) }; layout.addView(pin)
        layout.addView(Button(this).apply { text="Approve pairing"; setOnClickListener {
            val pending=HostRuntime.pending
            if (pending==null) Toast.makeText(this@MainActivity,"Start pairing in Moonlight first",Toast.LENGTH_SHORT).show()
            else if (!pin.text.toString().matches(Regex("[0-9]{4}"))) pin.error="Enter four digits"
            else { HostRuntime.server?.pairing?.approve(pending.id,pin.text.toString()); pin.text.clear(); HostRuntime.pending=null; HostRuntime.update("Verifying pairing") }
        } })
        layout.addView(Button(this).apply { text="Stop host"; setOnClickListener { stopService(Intent(this@MainActivity,CaptureService::class.java)) } })
        layout.addView(Button(this).apply { text="Forget paired clients"; setOnClickListener {
            stopService(Intent(this@MainActivity,CaptureService::class.java))
            AndroidIdentity.load(this@MainActivity).clearClients()
            HostRuntime.update("Paired clients removed")
        } })
        layout.addView(TextView(this).apply { text="Android requires screen-sharing consent each time the host starts. Audio is captured only from apps that permit playback capture. Keep the phone unlocked for screen sharing." })
        setContentView(layout)
    }
    private fun captureConsent() { @Suppress("DEPRECATION") startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),42) }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) { super.onRequestPermissionsResult(requestCode,permissions,grantResults); if(requestCode==43) captureConsent() }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==42&&resultCode==RESULT_OK&&data!=null) startForegroundService(Intent(this,CaptureService::class.java).putExtra("resultCode",resultCode).putExtra("data",data))
    }
    override fun onStart() { super.onStart(); HostRuntime.observe(observer) }
    override fun onStop() { HostRuntime.remove(observer); super.onStop() }
}
