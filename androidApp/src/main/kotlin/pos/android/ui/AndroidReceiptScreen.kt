package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.android.documents.*
import pos.data.PosRepository
import pos.ui.components.*
import java.io.File

@Composable
fun AndroidReceiptScreen(repo:PosRepository,tenantId:String,saleId:String,onBack:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val loaded=remember(repo,tenantId,saleId){runCatching {loadAndroidReceipt(repo,tenantId,saleId)}}
    var busy by remember {mutableStateOf(false)};var notice by remember {mutableStateOf<String?>(null)}
    fun prepare(action:(File)->Unit){busy=true;notice=null;scope.launch {try {val file=withContext(Dispatchers.IO){writeAndroidReceiptPdf(context,loadAndroidReceipt(repo,tenantId,saleId))};action(file)}catch(e:Exception){notice=e.message ?: "Receipt unavailable. The sale stays saved."}finally {busy=false}}}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){SectionHeader("Saved receipt","Sale saved on this device",Modifier.weight(1f));TextButton(onBack){Text("Back")}}
        loaded.exceptionOrNull()?.let {InlineNotice("Receipt unavailable",it.message.orEmpty(),NoticeTone.Danger)}
        notice?.let {InlineNotice("Receipt action",it,NoticeTone.Warning)}
        loaded.getOrNull()?.let {source->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button({prepare {printAndroidReceipt(context,it)}},enabled=!busy){Text(if(busy)"Preparing…" else "Print receipt")}
                OutlinedButton({prepare {shareAndroidReceipt(context,it)}},enabled=!busy){Text("Share PDF")}
            }
            Text("Printing and sharing use your Android system apps. Opening them does not confirm paper delivery.",style=MaterialTheme.typography.bodySmall)
            Surface(shape=MaterialTheme.shapes.medium,modifier=Modifier.weight(1f)){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)){Text(androidReceiptText(source))}}
        }
    }
}
