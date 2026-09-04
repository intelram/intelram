package com.threadprotection.app.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "TPCallAudio"
private const val SAMPLE_RATE = 16_000
private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** 20ms at 16kHz mono 16-bit = 320 samples = 640 bytes per frame — small enough to keep latency
 *  low, large enough that per-frame AES-GCM + RFCOMM write overhead doesn't dominate. */
const val CALL_FRAME_BYTES = 320 * 2

/**
 * Raw PCM capture/playback for Bluetooth voice calls (see [BtCallState], [ChatWireMessage.CallAudio]).
 *
 * Deliberately uncompressed: classic Bluetooth RFCOMM over BR/EDR comfortably carries the ~25.6
 * kbps this needs (320 samples * 16-bit * 50 frames/sec), so a real codec (Opus, AMR) would only
 * add complexity and a native dependency for no bandwidth benefit at this scale. This rides the
 * *same* RFCOMM socket and AES-256-GCM session key the text chat already uses (see
 * `BluetoothChatManager.sendWire`) — no second connection, no weaker channel than chat already has.
 *
 * Honest limitation: this is not a phone's real voice-call path (HFP/SCO is a dedicated,
 * hardware-accelerated audio profile with its own jitter handling). This is raw PCM over a general-
 * purpose serial socket, sharing bandwidth with any text messages sent mid-call. Expect
 * walkie-talkie-grade quality and latency, not carrier-call quality — that's a real property of
 * the transport, not a bug to "fix" by tuning buffer sizes.
 */
class CallAudioEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var captureJob: Job? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    fun hasMicPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts capturing the mic and invokes [onFrame] with each ~20ms PCM chunk on a background
     *  dispatcher. Never throws: a missing permission or hardware that refuses to initialize is
     *  logged and left for the caller to notice via [isCapturing] — a call with a silent mic isn't
     *  a fatal condition the same way a lost connection is. */
    fun startCapture(onFrame: (ByteArray) -> Unit) {
        if (captureJob?.isActive == true) return
        if (!hasMicPermission()) {
            Log.w(TAG, "startCapture: RECORD_AUDIO not granted")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "startCapture: getMinBufferSize returned $minBuf — unsupported config on this hardware")
            return
        }
        val audioRecord = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING,
                maxOf(minBuf, CALL_FRAME_BYTES * 4),
            )
        }.getOrNull()
        if (audioRecord == null || audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "startCapture: AudioRecord failed to initialize")
            audioRecord?.release()
            return
        }
        if (runCatching { audioRecord.startRecording() }.isFailure) {
            Log.e(TAG, "startCapture: startRecording() threw")
            audioRecord.release()
            return
        }
        record = audioRecord
        captureJob = scope.launch {
            val buf = ByteArray(CALL_FRAME_BYTES)
            while (isActive) {
                val read = audioRecord.read(buf, 0, buf.size)
                when {
                    read == buf.size -> onFrame(buf.copyOf())
                    read < 0 -> {
                        Log.e(TAG, "startCapture: AudioRecord.read() returned error $read")
                        break
                    }
                    // A short read (device warming up) is not an error — just skip this tick
                    // rather than sending a partial, misaligned frame.
                }
            }
        }
    }

    val isCapturing: Boolean get() = captureJob?.isActive == true

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        record?.let { r -> runCatching { r.stop() }; runCatching { r.release() } }
        record = null
    }

    /** Prepares playback for an active call. Call once when both sides have accepted; feed
     *  decoded frames with [playFrame] as they arrive. */
    fun startPlayback() {
        if (track != null) return
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        if (minBuf <= 0) {
            Log.e(TAG, "startPlayback: getMinBufferSize returned $minBuf — unsupported config on this hardware")
            return
        }
        val audioTrack = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(ENCODING)
                        .setChannelMask(CHANNEL_OUT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, CALL_FRAME_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()
        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "startPlayback: AudioTrack failed to initialize")
            audioTrack?.release()
            return
        }
        if (runCatching { audioTrack.play() }.isFailure) {
            Log.e(TAG, "startPlayback: play() threw")
            audioTrack.release()
            return
        }
        track = audioTrack
    }

    /** Feeds one decoded PCM frame to the speaker/earpiece. Silently dropped if playback isn't
     *  active — a frame arriving mid-teardown is an ordinary race, not an error. */
    fun playFrame(pcm: ByteArray) {
        val t = track ?: return
        runCatching { t.write(pcm, 0, pcm.size) }
            .onFailure { Log.w(TAG, "playFrame: AudioTrack.write() threw", it) }
    }

    fun stopPlayback() {
        track?.let { t -> runCatching { t.stop() }; runCatching { t.release() } }
        track = null
    }

    /** Tears down both directions — call on hangup, decline, or an unexpected disconnect. */
    fun release() {
        stopCapture()
        stopPlayback()
    }
}
