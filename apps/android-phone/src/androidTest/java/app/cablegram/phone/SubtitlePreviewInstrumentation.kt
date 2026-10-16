package app.cablegram.phone

import android.app.Activity
import android.app.Dialog
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.TextureView
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Physical rendering/lifecycle regression, using the generated red/blue MP4 in androidTest assets.
 * Build with -PCABLEGRAM_APP_ID_SUFFIX=.subtitleqa and
 * -PCABLEGRAM_TEST_RUNNER=app.cablegram.phone.SubtitlePreviewInstrumentation; install both APKs.
 * Run am instrument -w app.cablegram.phone.subtitleqa.test/app.cablegram.phone.SubtitlePreviewInstrumentation.
 */
class SubtitlePreviewInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var dialog: Dialog? = null
        var preview: SubtitlePreviewPlayer? = null
        try {
            check(targetContext.packageName.endsWith(".subtitleqa")) { "Use a disposable .subtitleqa installation" }
            val fixture = File(targetContext.cacheDir, "subtitle-preview.mp4")
            context.assets.open("subtitle-preview.mp4").use { input -> fixture.outputStream().use(input::copyTo) }
            activity = startActivitySync(Intent().setClassName(targetContext.packageName, "app.cablegram.phone.PhoneActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            fun await(message: String, condition: () -> Boolean) {
                val deadline = SystemClock.elapsedRealtime() + 8_000
                while (SystemClock.elapsedRealtime() < deadline) {
                    preview?.state?.value?.error?.let { error(it) }
                    if (condition()) return
                    Thread.sleep(50)
                }
                error(message)
            }
            fun isColor(view: TextureView, red: Boolean): Boolean {
                var matches = false
                runOnMainSync {
                    view.bitmap?.let { bitmap ->
                        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        matches = if (red) Color.red(pixel) > 180 && Color.blue(pixel) < 70
                        else Color.blue(pixel) > 180 && Color.red(pixel) < 70
                        bitmap.recycle()
                    }
                }
                return matches
            }
            // Three independent opens prove decoder/input disposal and reopening in a dialog.
            repeat(3) { attempt ->
                val closed = AtomicInteger()
                val fileInput = FileMediaInput(ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY))
                val input = object : SubtitleMediaInput by fileInput {
                    override fun close() { fileInput.close(); closed.incrementAndGet() }
                }
                val player = SubtitlePreviewPlayer(input).also { preview = it }
                fun texture(): TextureView = TextureView(activity).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { player.attach(texture) }
                        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean { player.detach(texture); return false }
                    }
                }
                lateinit var view: TextureView
                runOnMainSync {
                    view = texture()
                    dialog = Dialog(activity!!).apply {
                        setContentView(view)
                        show()
                        window!!.setLayout(600, 400)
                    }
                }
                await("Open $attempt did not render a real paused first frame") { player.state.value.ready && !player.state.value.playing && isColor(view, red = true) }
                player.togglePlayback()
                await("Open $attempt did not advance") { player.state.value.playing && player.state.value.positionMs > 600 }
                player.togglePlayback()
                await("Pause failed") { !player.state.value.playing }
                val pausedAt = player.state.value.positionMs
                Thread.sleep(400)
                check(kotlin.math.abs(player.state.value.positionMs - pausedAt) < 250) { "Playback advanced while paused" }
                player.seek(5_000)
                await("Seek did not display the blue frame") { !player.state.value.seeking && player.state.value.positionMs >= 4_900 && isColor(view, red = false) }
                // The same input must survive a destroyed surface and a replacement decoder.
                runOnMainSync { view = texture(); dialog!!.setContentView(view) }
                await("Replacement surface did not render") { player.state.value.ready && isColor(view, red = true) }
                runOnMainSync { dialog!!.dismiss(); dialog = null }
                player.close()
                await("Input was not closed exactly once") { closed.get() == 1 }
                preview = null
            }
            result.putString("result", "PASS: three dialog opens, paused red first frame, advancing playback, pause, blue frame after seek, surface replacement, input release")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            runOnMainSync { dialog?.dismiss(); activity?.finish() }
            preview?.close()
        }
    }
}
