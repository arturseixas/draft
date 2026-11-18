package app.applelogcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer

/** Microfone -> AAC-LC 48 kHz estéreo. O PTS usa elapsedRealtimeNanos, a mesma base dos frames da câmera. */
class AudioEncoder private constructor(
    private val record: AudioRecord,
    private val codec: MediaCodec
) : Thread("audio-encoder") {

    private lateinit var sink: MuxerSink
    private var baseNsProvider: () -> Long = { -1L }
    @Volatile private var stopRequested = false
    private var track = -1
    private val info = MediaCodec.BufferInfo()

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = 2 * CHANNELS

        @SuppressLint("MissingPermission")
        fun create(): AudioEncoder? {
            return try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
                )
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return null
                }
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
                }
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                AudioEncoder(record, codec)
            } catch (t: Throwable) {
                Log.w(LogRenderer.TAG, "Áudio indisponível: ${t.message}")
                null
            }
        }
    }

    /** @param baseNs devolve o timestamp (ns) do primeiro frame de vídeo gravado, ou -1 se ainda não chegou. */
    fun begin(sink: MuxerSink, baseNs: () -> Long) {
        this.sink = sink
        this.baseNsProvider = baseNs
        start()
    }

    fun stopAndJoin() {
        if (state == Thread.State.NEW) { abort(); return }
        stopRequested = true
        try { join(3000) } catch (_: InterruptedException) {}
    }

    /** Libera o microfone e o codec de um encoder que nunca chegou a iniciar. */
    fun abort() {
        if (state != Thread.State.NEW) return
        try { record.release() } catch (_: Throwable) {}
        try { codec.release() } catch (_: Throwable) {}
    }

    override fun run() {
        try {
            codec.start()
            record.startRecording()
            val pcm = ByteBuffer.allocateDirect(4096)
            var framesRead = 0L
            var startNs = -1L

            while (!stopRequested) {
                pcm.clear()
                val n = record.read(pcm, pcm.capacity())
                if (n <= 0) continue
                val frames = n / BYTES_PER_FRAME
                if (startNs < 0) startNs = SystemClock.elapsedRealtimeNanos() - framesToNs(frames.toLong())
                val ptsNs = startNs + framesToNs(framesRead)
                framesRead += frames

                val base = baseNsProvider()
                if (base >= 0 && ptsNs >= base) {
                    feed(pcm, n, (ptsNs - base) / 1000, eos = false)
                }
                drain(false)
            }
            feed(pcm, 0, (SystemClock.elapsedRealtimeNanos() - maxOf(baseNsProvider(), 0L)) / 1000, eos = true)
            drain(true)
        } catch (t: Throwable) {
            Log.e(LogRenderer.TAG, "Falha no áudio: ${t.message}", t)
        } finally {
            try { record.stop() } catch (_: Throwable) {}
            record.release()
            try { codec.stop() } catch (_: Throwable) {}
            codec.release()
        }
    }

    private fun framesToNs(frames: Long) = frames * 1_000_000_000L / SAMPLE_RATE

    private fun feed(pcm: ByteBuffer, size: Int, ptsUs: Long, eos: Boolean) {
        var idx = codec.dequeueInputBuffer(10_000)
        var tries = 0
        while (idx < 0 && eos && tries++ < 100) idx = codec.dequeueInputBuffer(10_000)
        if (idx < 0) return
        val input = codec.getInputBuffer(idx) ?: return
        input.clear()
        if (size > 0) {
            pcm.position(0)
            pcm.limit(size)
            input.put(pcm)
        }
        codec.queueInputBuffer(idx, 0, size, ptsUs, if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
    }

    private fun drain(eos: Boolean) {
        var empty = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    if (++empty > 300) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track = sink.addTrack(codec.outputFormat)
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && track >= 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        sink.write(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
