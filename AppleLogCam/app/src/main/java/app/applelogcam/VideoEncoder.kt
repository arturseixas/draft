package app.applelogcam

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/** HEVC Main10 recebendo frames pela input surface (RGBA1010102 desenhado via EGL). */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitrate: Int,
    private val colorStandard: Int,
    private val colorTransfer: Int,
    private val sink: MuxerSink
) {
    private var codec: MediaCodec
    val inputSurface: Surface
    private var track = -1
    private val info = MediaCodec.BufferInfo()
    private var released = false

    init {
        var c: MediaCodec
        try {
            c = configure(width, height, fps, bitrate, withLevel = true)
        } catch (t: Throwable) {
            Log.w(LogRenderer.TAG, "Encoder recusou o nível High Tier 5.1 (${t.message}); tentando sem nível")
            c = configure(width, height, fps, bitrate, withLevel = false)
        }
        codec = c
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    private fun configure(width: Int, height: Int, fps: Int, bitrate: Int, withLevel: Boolean): MediaCodec {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC)
        try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                if (withLevel) setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel51)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Apple Log não tem rótulo no MediaCodec: sai marcado como BT.2020 + HLG (veja o README).
                setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer)
            }
            c.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            return c
        } catch (t: Throwable) {
            c.release()
            throw t
        }
    }

    /** Esvazia o encoder. Com [endOfStream] sinaliza EOS e espera o último buffer. */
    fun drain(endOfStream: Boolean) {
        if (released) return
        if (endOfStream) codec.signalEndOfInputStream()
        var emptyReads = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++emptyReads > 300) {
                        Log.w(LogRenderer.TAG, "Encoder não entregou EOS a tempo")
                        return
                    }
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = sink.addTrack(codec.outputFormat)
                }
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

    fun release() {
        if (released) return
        released = true
        try { codec.stop() } catch (_: Throwable) {}
        codec.release()
        inputSurface.release()
    }
}
