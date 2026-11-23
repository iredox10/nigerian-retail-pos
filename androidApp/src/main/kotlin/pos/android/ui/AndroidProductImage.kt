package pos.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.*
import pos.ui.components.*
import java.util.UUID

@Composable
internal fun AndroidProductPicture(repo:PosRepository,tenantId:String,productId:String,revision:Int,modifier:Modifier=Modifier) {
    val image=remember(tenantId,productId,revision) {runCatching {repo.imageSelection(tenantId,"product",productId).assetId?.let {id ->repo.imageBytes(tenantId,id)}?.let {BitmapFactory.decodeByteArray(it,0,it.size)?.asImageBitmap()}}.getOrNull()}
    image?.let {Image(it,contentDescription="Product photo",modifier=modifier.height(120.dp).fillMaxWidth(),contentScale=ContentScale.Fit)}
}

@Composable
internal fun AndroidProductImageEditor(repo:PosRepository,tenantId:String,productId:String,onChanged:()->Unit) {
    var open by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var upload by remember {mutableStateOf<ImageUpload?>(null)}
    var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val selected=remember(revision) {runCatching {repo.imageSelection(tenantId,"product",productId)}}
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) {busy=true;scope.launch {
            try {upload=withContext(Dispatchers.IO) {val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use {input ->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=8_388_608){"Choose an image up to 8 MB."};out.write(buffer,0,n)};out.toByteArray()};ImageUpload.fromBytes(bytes)}}
            catch(e:Exception){error=e.message ?: "Image could not be read."} finally {busy=false}
        }}
    }
    TextButton({open=true;error=null}) {Text("Product photo")}
    if(open) AlertDialog(onDismissRequest={if(!busy)open=false},title={Text("Product photo")},text={
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            AndroidProductPicture(repo,tenantId,productId,revision)
            upload?.let {Image(BitmapFactory.decodeByteArray(it.bytes,0,it.bytes.size).asImageBitmap(),"Selected image preview",Modifier.fillMaxWidth().height(120.dp),contentScale=ContentScale.Fit)}
            Text("Saving keeps the original photo in change history. Choose a PNG or JPEG up to 8 MB.")
            OutlinedButton({choose.launch(arrayOf("image/png","image/jpeg"))},enabled=!busy){Text("Choose image")}
            PosTextField(reason,{reason=it},"Reason for changing this photo")
            error?.let {InlineNotice("Photo not saved",it,NoticeTone.Danger)}
            if(selected.getOrNull()?.assetId!=null) TextButton({
                busy=true;scope.launch {try {withContext(Dispatchers.IO){repo.saveImage(tenantId,"product",productId,null,selected.getOrThrow().version,reason.trim(),UUID.randomUUID().toString())};revision++;onChanged();open=false}catch(e:Exception){error=e.message}finally{busy=false}}
            },enabled=!busy){Text("Remove current photo")}
        }
    },confirmButton={TextButton({busy=true;scope.launch {
        try {withContext(Dispatchers.IO){repo.saveImage(tenantId,"product",productId,requireNotNull(upload){"Choose an image first."},selected.getOrThrow().version,reason.trim(),UUID.randomUUID().toString())};revision++;upload=null;onChanged();open=false}
        catch(e:Exception){error=e.message ?: "Image was not saved."}finally{busy=false}
    }},enabled=!busy && selected.isSuccess){Text(if(busy)"Saving…" else "Save image")}},dismissButton={TextButton({open=false},enabled=!busy){Text("Cancel")}})
}
