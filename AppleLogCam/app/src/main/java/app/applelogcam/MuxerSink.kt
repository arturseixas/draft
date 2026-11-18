package app.applelogcam

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import java.nio.ByteBuffer

/**
 * Serializa as escritas de vídeo e áudio no MediaMuxer. O muxer só inicia quando todas as
 * trilhas esperadas foram adicionadas; amostras que chegam antes ficam numa fila.
 */
class MuxerSink(private val muxer: MediaMuxer, private val expectedTracks: Int) {

    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)

    private var tracks = 0
    private var started = false
    private var stopped = false
    private val pending = ArrayList<Pending>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val index = muxer.addTrack(format)
        tracks++
        if (tracks == expectedTracks) {
            muxer.start()
            started = true
            for (p in pending) {
                muxer.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info)
            }
            pending.clear()
        }
        return index
    }

    @Synchronized
    fun write(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (stopped) return
        if (started) {
            muxer.writeSampleData(track, buffer, info)
        } else {
            val bytes = ByteArray(info.size)
            buffer.get(bytes)
            val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending.add(Pending(track, bytes, copy))
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        try {
            if (started) muxer.stop()
        } finally {
            muxer.release()
        }
    }
}
