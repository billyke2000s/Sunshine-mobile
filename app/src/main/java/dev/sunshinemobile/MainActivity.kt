package dev.sunshinemobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var addresses: TextView
    private var resumed=false
    private var requestingCapture=false
    private var consentPending=false
    private var projectionResult: Intent?=null
    private val observer: () -> Unit = {
        status.text=HostRuntime.status+"\nDiscovery: "+HostRuntime.discoveryStatus
        addresses.text="Manual Moonlight address: "+localAddresses()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(32,64,32,32) }
        layout.addView(TextView(this).apply { text="Sunshine Mobile"; textSize=28f })
        layout.addView(TextView(this).apply { text="Share this phone with Moonlight on your TV. Start the host, pair in Moonlight, then enter the TV’s PIN here. Launch Phone screen.\n\nH.264 SDR • up to 1080p60 • stereo audio"; textSize=16f })
        addresses=TextView(this); layout.addView(addresses)
        status=TextView(this).apply { textSize=18f; setPadding(0,24,0,24) }; layout.addView(status)
        layout.addView(Button(this).apply { text="Start host"; setOnClickListener {
            startHost()
        } })
        layout.addView(Button(this).apply { text="Enable screen sharing"; setOnClickListener { startHost() } })
        layout.addView(Button(this).apply { text="Approve pairing"; setOnClickListener {
            val pending=HostRuntime.pending
            if (pending==null) Toast.makeText(this@MainActivity,"Start pairing in Moonlight first",Toast.LENGTH_SHORT).show()
            else {
                // This is a short-lived pairing code, not an account password. A persistent
                // password input can make Android classify the entire host UI as sensitive.
                val pin=EditText(this@MainActivity).apply {
                    hint="4-digit PIN from Moonlight"
                    inputType=InputType.TYPE_CLASS_NUMBER
                    importantForAutofill=android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                    filters=arrayOf(android.text.InputFilter.LengthFilter(4))
                }
                val dialog=AlertDialog.Builder(this@MainActivity).setTitle("Pair Moonlight")
                    .setMessage("Enter the code shown on your TV.").setView(pin)
                    .setNegativeButton("Cancel",null).setPositiveButton("Approve",null).create()
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener approve@{
                    val code=pin.text.toString()
                    if (!code.matches(Regex("[0-9]{4}"))) { pin.error="Enter four digits"; return@approve }
                    if (HostRuntime.pending!==pending) {
                        Toast.makeText(this@MainActivity,"Pairing request ended. Try again in Moonlight.",Toast.LENGTH_SHORT).show()
                    } else {
                        HostRuntime.server?.pairing?.approve(pending.id,code)
                        HostRuntime.pending=null
                        HostRuntime.update("Verifying pairing")
                    }
                    dialog.dismiss()
                } }
                dialog.setOnDismissListener { pin.text.clear() }
                dialog.show()
            }
        } })
        layout.addView(Button(this).apply { text="Stop host"; setOnClickListener {
            consentPending=false
            stopService(Intent(this@MainActivity,CaptureService::class.java))
            HostRuntime.update("Host stopped by you")
        } })
        layout.addView(Button(this).apply { text="Forget paired clients"; setOnClickListener {
            stopService(Intent(this@MainActivity,CaptureService::class.java))
            AndroidIdentity.load(this@MainActivity).clearClients()
            HostRuntime.update("Paired clients removed")
        } })
        layout.addView(Button(this).apply { text="Copy diagnostics"; setOnClickListener {
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Sunshine Mobile diagnostics",HostRuntime.diagnostics()+"\nLAN addresses: "+localAddresses()))
            Toast.makeText(this@MainActivity,"Diagnostics copied",Toast.LENGTH_SHORT).show()
        } })
        layout.addView(Button(this).apply { text="App and battery settings"; setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
        } })
        layout.addView(TextView(this).apply { text="The host can be discovered and paired before screen sharing is enabled. Keep the phone unlocked. In OnePlus app settings, allow background activity and Wi-Fi/network access. Playback audio is limited to apps that permit capture. VPNs, guest Wi-Fi and router client isolation can block discovery." })
        setContentView(ScrollView(this).apply { addView(layout) })
        requestingCapture=savedInstanceState?.getBoolean("requestingCapture") ?: false
    }
    private fun localAddresses(): String = try {
        java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
            .flatMap { java.util.Collections.list(it.inetAddresses) }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
            .joinToString(" • ") { it.hostAddress ?: "" }.ifEmpty { "No IPv4 connection — connect to the TV’s Wi-Fi" }
    } catch(e: Exception) { "Unavailable: ${e.message}" }
    private fun startHost() {
        try {
            if(HostRuntime.server==null && !HostRuntime.starting) {
                HostRuntime.starting=true
                HostRuntime.update("Starting network host")
                startForegroundService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_START))
            }
            if(HostRuntime.captureReady) { HostRuntime.update("Host already running with screen sharing enabled"); return }
            if(requestingCapture) { HostRuntime.update("Waiting for Android screen-sharing permission"); return }
            requestingCapture=true
            val permissions=mutableListOf<String>()
            if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.RECORD_AUDIO)
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            if(permissions.isNotEmpty()) {
                HostRuntime.update("Requesting optional audio and notification permissions")
                requestPermissions(permissions.toTypedArray(),43)
            } else { consentPending=true; continueStartup() }
        } catch(e: Exception) { requestingCapture=false; HostRuntime.starting=false; HostRuntime.fail("Could not start host",e) }
    }
    private fun continueStartup() {
        if(!resumed) return
        projectionResult?.let { result ->
            projectionResult=null
            try {
                HostRuntime.update("Enabling screen capture")
                startForegroundService(Intent(this,CaptureService::class.java).setAction(CaptureService.ACTION_CAPTURE)
                    .putExtra("resultCode",RESULT_OK).putExtra("data",result))
            } catch(e: Exception) { HostRuntime.fail("Android refused capture service",e) }
        }
        if(consentPending) { consentPending=false; captureConsent() }
    }
    private fun captureConsent() {
        HostRuntime.update("Waiting for Android screen-sharing consent")
        try {
        val manager=getSystemService(MediaProjectionManager::class.java)
        // Phone mirroring must follow app switches. Android 14+ otherwise defaults
        // to offering a single task, which does not mirror the whole display.
        val intent=if (Build.VERSION.SDK_INT>=34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent,42)
        } catch(e: Exception) {
            requestingCapture=false
            HostRuntime.fail("Could not open Android screen-sharing dialog",e)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int,permissions: Array<out String>,grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==43) { consentPending=true; continueStartup() }
    }
    @Deprecated("Activity result bridge")
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==42) {
            requestingCapture=false
            if(resultCode==RESULT_OK&&data!=null) { projectionResult=data; continueStartup() }
            else HostRuntime.update("Screen sharing was not approved. Host remains online for pairing; tap Enable screen sharing to retry.")
        }
    }
    override fun onResume() { super.onResume(); resumed=true; continueStartup() }
    override fun onPause() { resumed=false; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("requestingCapture",requestingCapture); super.onSaveInstanceState(outState) }
    override fun onStart() { super.onStart(); HostRuntime.observe(observer) }
    override fun onStop() { HostRuntime.remove(observer); super.onStop() }
}
