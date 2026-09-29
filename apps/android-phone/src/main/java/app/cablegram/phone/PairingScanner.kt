package app.cablegram.phone

import android.view.ViewGroup
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.DecoratedBarcodeView

@Composable
fun PairingScanner(modifier: Modifier = Modifier, onQr: (String) -> Unit) {
    val context = LocalContext.current
    var cameraAllowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { cameraAllowed = it }
    val currentOnQr by rememberUpdatedState(onQr)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<DecoratedBarcodeView?>(null) }
    var consumed by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (!cameraAllowed) {
            Text("Allow camera access to scan the TV code. You can also enter the code above.", color = VlcMuted)
            Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context ->
                    DecoratedBarcodeView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        setStatusText("Point the camera at the QR on the TV")
                        decodeContinuous { result ->
                            val text = result.text?.trim().orEmpty()
                            if (text.isNotBlank() && !consumed) {
                                consumed = true
                                currentOnQr(text)
                            }
                        }
                        view = this
                        runCatching { resume() }
                    }
                },
                onRelease = {
                    runCatching { it.pause() }
                    view = null
                },
            )
            if (consumed) Button(onClick = { consumed = false }) { Text("Scan again") }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> runCatching { view?.resume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { view?.pause() }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view?.pause()
        }
    }
}
