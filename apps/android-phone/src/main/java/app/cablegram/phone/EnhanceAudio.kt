package app.cablegram.phone

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Keep the first video track and first audio track (drop extra/noisy tracks). */
object EnhanceAudio {
    fun remuxPrimaryTracks(input: File, output: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        var video = -1
        var audio = -1
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString("mime").orEmpty()
            if (video < 0 && mime.startsWith("video/")) video = i
            if (audio < 0 && mime.startsWith("audio/")) audio = i
        }
        if (video < 0) {
            extractor.release()
            error("No video track")
        }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = mutableMapOf<Int, Int>()
        extractor.selectTrack(video)
        map[video] = muxer.addTrack(extractor.getTrackFormat(video))
        if (audio >= 0) {
            extractor.selectTrack(audio)
            map[audio] = muxer.addTrack(extractor.getTrackFormat(audio))
        }
        muxer.start()
        val buffer = ByteBuffer.allocate(1 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        extractor.unselectTrack(video)
        if (audio >= 0) extractor.unselectTrack(audio)
        listOfNotNull(video, audio.takeIf { it >= 0 }).forEach { track ->
            extractor.selectTrack(track)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                info.offset = 0
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                // Extractor and codec flags have different meanings despite sharing integer values.
                check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) {
                    "Encrypted tracks cannot be remuxed"
                }
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer.writeSampleData(map.getValue(track), buffer, info)
                extractor.advance()
            }
            extractor.unselectTrack(track)
        }
        muxer.stop()
        muxer.release()
        extractor.release()
    }
}
