package pos.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*

@Composable
fun AndroidShopSettingsScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var revision by remember {mutableIntStateOf(0)}
    val loaded=remember(revision){runCatching {repo.shopProfile(tenantId)}}
    val initial=loaded.getOrNull();val defaultName=remember {repo.tenants().singleOrNull {it.id==tenantId}?.name ?: "Shop"}
    var name by remember(revision){mutableStateOf(initial?.details?.displayName ?: defaultName)}
    var phone by remember(revision){mutableStateOf(initial?.details?.phone.orEmpty())};var address by remember(revision){mutableStateOf(initial?.details?.address.orEmpty())}
    var footer by remember(revision){mutableStateOf(initial?.details?.footer.orEmpty())};var reason by remember {mutableStateOf("")}
    var paper by remember(revision){mutableStateOf(initial?.details?.receiptPaper ?: "A4")}
    var style by remember(revision){mutableStateOf(initial?.details?.appearance?.style ?: "modern")}
    var frame by remember(revision){mutableStateOf(initial?.details?.appearance?.frame ?: "none")}
    var accent by remember(revision){mutableStateOf(initial?.details?.appearance?.accentHex ?: "#087F73")}
    var tagline by remember(revision){mutableStateOf(initial?.details?.appearance?.tagline.orEmpty())};var policy by remember(revision){mutableStateOf(initial?.details?.appearance?.policy.orEmpty())}
    var error by remember {mutableStateOf<String?>(null)};var notice by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    var upload by remember {mutableStateOf<ImageUpload?>(null)}
    val image=remember(revision){runCatching {repo.imageSelection(tenantId,"shop","shop")}.getOrNull()}
    val choose=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch {
        busy=true;error=null
        try {upload=withContext(Dispatchers.IO){val bytes=requireNotNull(context.contentResolver.openInputStream(uri)).use {input->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val count=input.read(buffer);if(count<0)break;require(output.size()+count<=8_388_608){"Choose an image up to 8 MB."};output.write(buffer,0,count)};output.toByteArray()};ImageUpload.fromBytes(bytes)}}
        catch(e:Exception){error=e.message ?: "Image could not be opened."}finally {busy=false}
    }}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        SectionHeader("Shop & receipt details","New sales capture these details; saved receipts keep their original identity")
        loaded.exceptionOrNull()?.let {InlineNotice("Settings unavailable",it.message.orEmpty(),NoticeTone.Danger)}
        error?.let {InlineNotice("Settings not saved",it,NoticeTone.Danger)};notice?.let {InlineNotice(it,tone=NoticeTone.Success)}
        PosTextField(name,{name=it},"Shop name");PosTextField(phone,{phone=it},"Shop phone (optional)");PosTextField(address,{address=it},"Shop address (optional)");PosTextField(footer,{footer=it},"Receipt footer (optional)")
        Text("Receipt paper",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("A4","58mm","80mm").forEach {value->FilterChip(paper==value,{paper=value},label={Text(value)})}}
        Text("Receipt appearance",style=MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("classic","modern","compact").forEach {value->FilterChip(style==value,{style=value},label={Text(value.replaceFirstChar(Char::uppercase))})}}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("none","line","double").forEach {value->FilterChip(frame==value,{frame=value},label={Text("Frame: $value")})}}
        PosTextField(accent,{accent=it},"Accent colour (#RRGGBB)");PosTextField(tagline,{tagline=it},"Tagline (optional)");PosTextField(policy,{policy=it},"Returns policy (optional)")
        Text("Android receipt output preserves captured identity, amounts, logo and artwork. Full desktop style/frame rendering parity is still being completed.",style=MaterialTheme.typography.bodySmall)
        PosTextField(reason,{reason=it},"Reason for changing receipt details or logo")
        Button({runCatching {val old=initial?.details?.appearance ?: ReceiptAppearance();val appearance=old.copy(style=style,frame=frame,accentHex=accent.trim(),tagline=tagline.trim().ifEmpty {null},policy=policy.trim().ifEmpty {null});repo.saveShopProfile(tenantId,ShopDetails(name.trim(),phone.trim().ifEmpty {null}?.let {requireNotNull(normalizeNigerianPhone(it)){"Enter a valid Nigerian phone number."}},address.trim().ifEmpty {null},footer.trim().ifEmpty {null},paper,appearance),initial?.version ?: 0,reason.trim());revision++;notice="Receipt details saved on this device.";error=null;onChanged()}.onFailure {error=it.message}},enabled=!busy && loaded.isSuccess,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Save receipt details")}
        HorizontalDivider()
        OutlinedButton({choose.launch(arrayOf("image/png","image/jpeg"))},enabled=!busy){Text("Choose shop logo")}
        upload?.let {draft->
            val bitmap=remember(draft){android.graphics.BitmapFactory.decodeByteArray(draft.bytes,0,draft.bytes.size)}
            bitmap?.let {Image(it.asImageBitmap(),"Selected shop logo",Modifier.size(96.dp))}
            Button({runCatching {repo.saveImage(tenantId,"shop","shop",draft,image?.version ?: 0,reason.trim());upload=null;revision++;notice="Shop logo saved. New sales will capture it.";onChanged()}.onFailure {error=it.message}},enabled=!busy){Text("Save shop logo")}
        }
        if(image?.assetId!=null)OutlinedButton({runCatching {repo.saveImage(tenantId,"shop","shop",null,image.version,reason.trim());revision++;notice="Logo removal saved with history.";onChanged()}.onFailure {error=it.message}},enabled=!busy){Text("Remove shop logo")}
    }
}
