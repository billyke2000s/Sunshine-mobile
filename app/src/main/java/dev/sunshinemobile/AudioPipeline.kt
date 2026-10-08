package dev.sunshinemobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import dev.sunshinemobile.protocol.HostSession
import dev.sunshinemobile.protocol.Native
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Playback capture supplies eligible app audio; protected audio is excluded by Android. */
class AudioPipeline(context: Context, projection: MediaProjection, private val session: HostSession) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private var record: AudioRecord? = null
    private val samples = session.audioDuration * 48
    private val encoder = Native.opusCreate(samples).also { check(it != 0L) { "Opus encoder creation failed" } }
    private val thread: Thread
    init {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                val format = AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                record = AudioRecord.Builder().setAudioFormat(format).setAudioPlaybackCaptureConfig(config)
                    .setBufferSizeInBytes(maxOf(AudioRecord.getMinBufferSize(48000,AudioFormat.CHANNEL_IN_STEREO,AudioFormat.ENCODING_PCM_16BIT),samples*8)).build()
                check(record?.state == AudioRecord.STATE_INITIALIZED)
                record?.startRecording()
            } catch (e: Exception) {
                record?.release(); record = null
                HostRuntime.update("Streaming with silent audio: playback capture unavailable")
            }
        }
        thread = Thread({
            val pcm = ShortArray(samples*2)
            var deadline = System.nanoTime()
            try {
                while (running.get() && !session.isClosed) {
                    var read = 0
                    val recorder = record
                    if (recorder != null) {
                        while (running.get() && read < pcm.size) {
                            val n = recorder.read(pcm,read,pcm.size-read,AudioRecord.READ_BLOCKING)
                            if (n <= 0) throw IllegalStateException("AudioRecord read failed: $n")
                            read += n
                        }
                    } else {
                        pcm.fill(0)
                        deadline += session.audioDuration * 1_000_000L
                        val wait = deadline - System.nanoTime()
                        if (wait > 0) LockSupport.parkNanos(wait) else deadline = System.nanoTime()
                    }
                    if (running.get()) Native.opusEncode(encoder,pcm,samples)?.let { session.transport.audio(it) }
                }
            } catch (e: Exception) { if (running.get()) session.fail("Audio failed: ${e.message}") }
            finally { Native.opusDestroy(encoder) }
        }, "capture-audio").apply { start() }
    }
    override fun close() {
        running.set(false)
        try { record?.stop() } catch (_: Exception) {}
        thread.interrupt()
        if (Thread.currentThread() != thread) thread.join(2000)
        record?.release(); record=null
    }
}
