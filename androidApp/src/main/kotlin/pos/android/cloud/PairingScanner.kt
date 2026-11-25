package pos.android.cloud

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import pos.cloud.client.CloudSetup
import pos.cloud.ui.LocalPairingScanner

/** Uses the phone's camera app; no camera permission or image file is retained by Retail POS. */
@Composable
fun PairingScannerProvider(content:@Composable ()->Unit) {
    val scope=rememberCoroutineScope()
    var receive by remember {mutableStateOf<((String)->Unit)?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) {bitmap ->
        val callback=receive;receive=null
        if(bitmap!=null && callback!=null)scope.launch {
            try {
                val code=withContext(Dispatchers.Default) {
                    val pixels=IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    CloudSetup.readQr(bitmap.width,bitmap.height,pixels)
                }
                callback(code)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {error="Could not read that connection QR code. Try taking a clearer photo, or type the code shown on the new device."}
            finally {bitmap.recycle()}
        }
    }
    error?.let {AlertDialog(onDismissRequest={error=null},title={Text("Enter the connection code")},text={Text(it)},confirmButton={TextButton(onClick={error=null}) {Text("Continue")}})}
    CompositionLocalProvider(LocalPairingScanner provides {callback ->
        receive=callback
        try {camera.launch(null)} catch(_:Exception) {receive=null;error="No camera app is available. Type the code shown on the new device instead."}
    },content=content)
}
