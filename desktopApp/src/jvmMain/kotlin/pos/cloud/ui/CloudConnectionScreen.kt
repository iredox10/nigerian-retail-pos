package pos.cloud.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pos.cloud.client.*
import pos.cloud.protocol.*

val LocalCloudRevision = staticCompositionLocalOf { 0L }

/** Shared installed-app setup. Secrets stay in memory and are never saveable navigation state. */
@Composable
fun CloudConnectionScreen(cloud:CloudCoordinator,shopId:String,shopName:String,onImported:()->Unit) = key(shopId) {
    val scope=rememberCoroutineScope()
    var endpointText by remember {mutableStateOf("https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com")}
    var email by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var deviceName by remember {mutableStateOf("Shop device")}
    var account by remember {mutableStateOf<CloudAccountSession?>(null)}
    var authenticatedEndpoint by remember {mutableStateOf<CloudEndpoint?>(null)}
    var connection by remember {mutableStateOf<CloudConnectionInfo?>(null)}
    var businesses by remember {mutableStateOf<List<CloudBusinessSummary>>(emptyList())}
    var devices by remember {mutableStateOf<List<CloudDeviceSummary>>(emptyList())}
    var selected by remember {mutableStateOf<CloudBusinessSummary?>(null)}
    var newName by remember {mutableStateOf("")}
    var localPassword by remember {mutableStateOf("")}
    var reviewPublish by remember {mutableStateOf(false)}
    var revoke by remember {mutableStateOf<CloudDeviceSummary?>(null)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    fun runWork(work:suspend ()->Unit) {
        if(busy)return
        busy=true;error=null;notice=null
        scope.launch {
            try {work()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=when(e) {is CloudHttpException,is CloudConnectionException,is IllegalArgumentException ->e.message;else->"Could not complete cloud setup. Saved local records were kept."}}
            finally {busy=false}
        }
    }
    LaunchedEffect(shopId) {
        try {connection=withContext(Dispatchers.IO) {cloud.connection(shopId)};connection?.let {endpointText=it.endpoint.url;deviceName=it.deviceName}}
        catch(_:Exception) {error="The saved cloud connection needs review. Its checkpoints have been kept."}
    }
    if(reviewPublish) AlertDialog(onDismissRequest={if(!busy)reviewPublish=false},title={Text("Connect $shopName?")},text={Text("Upload this shop's saved business records and images to your cloud account. Other enrolled devices can download them. Local owner/staff passwords and recovery codes stay on this device.")},confirmButton={Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {
        val linked=withContext(Dispatchers.IO) {cloud.publish(shopId,requireNotNull(authenticatedEndpoint),requireNotNull(account),deviceName.trim())}
        connection=linked;reviewPublish=false
        withContext(Dispatchers.IO) {cloud.syncNow(shopId)}
        notice="Shop connected. Open Cloud connection on your other device and sign in to the same cloud account."
    }}) {Text("Connect this shop")}},dismissButton={TextButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={reviewPublish=false}) {Text("Cancel")}})
    revoke?.let {device ->AlertDialog(onDismissRequest={if(!busy)revoke=null},title={Text("Revoke ${device.name}?")},text={Text("This stops its future cloud access. Records already downloaded remain on that device. Unsynchronized work there will stay there until access is renewed.")},confirmButton={Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {
        withContext(Dispatchers.IO) {cloud.revokeDevice(shopId,requireNotNull(account),device.id)}
        devices=withContext(Dispatchers.IO) {cloud.devices(shopId,requireNotNull(account))};revoke=null;notice="Device access revoked."
    }}) {Text("Revoke access")}},dismissButton={TextButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={revoke=null}) {Text("Cancel")}})}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Cloud connection",style=MaterialTheme.typography.headlineSmall)
        Text("Use the same shop on desktop and Android. You can keep selling without internet.")
        CloudStatus(cloud,shopId,expanded=true)
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        notice?.let {Text(it,color=MaterialTheme.colorScheme.primary)}
        connection?.let {link ->
            Text("Connected to ${link.endpoint.url}",style=MaterialTheme.typography.bodyMedium)
            Text("This device: ${link.deviceName}")
            Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {
                val result=withContext(Dispatchers.IO) {cloud.syncNow(shopId)}
                notice=result.message
            }}) {Text("Sync now")}
        }
        HorizontalDivider()
        Text(if(account==null) "Owner cloud sign-in" else "Cloud account signed in",style=MaterialTheme.typography.titleMedium)
        Text("Cloud sign-in is separate from the password used to unlock this shop. A new cloud password needs at least 12 characters.",style=MaterialTheme.typography.bodyMedium)
        if(account==null) {
            OutlinedTextField(endpointText,{endpointText=it},enabled=!busy && connection==null,label={Text("HTTPS server address")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(email,{email=it},enabled=!busy,label={Text("Cloud email")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},enabled=!busy,label={Text("Cloud password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            fun authenticate(register:Boolean)=runWork {
                val endpoint=CloudEndpoint.parse(endpointText)
                val attempt=password;password=""
                val signed=withContext(Dispatchers.IO) {if(register)cloud.register(endpoint,email,attempt) else cloud.signIn(endpoint,email,attempt)}
                val shops=withContext(Dispatchers.IO) {cloud.businesses(endpoint,signed)}
                authenticatedEndpoint=endpoint;account=signed;businesses=shops
            }
            Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={authenticate(false)}) {Text("Sign in to cloud")}
            OutlinedButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={authenticate(true)}) {Text("Create cloud account")}
        } else {
            TextButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={account=null;authenticatedEndpoint=null;businesses=emptyList();devices=emptyList();selected=null;localPassword=""}) {Text("Sign out of cloud setup")}
            OutlinedTextField(deviceName,{deviceName=it},enabled=!busy,label={Text("Name this device")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(connection==null) {
                Text("Connect this shop",style=MaterialTheme.typography.titleMedium)
                Text("Start here on the device that already holds your shop's records.")
                Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={reviewPublish=true}) {Text("Review connecting $shopName")}
                HorizontalDivider()
                Text("Download an existing cloud shop",style=MaterialTheme.typography.titleMedium)
                Text("On your second device, choose the shop below. It opens as a new local shop; existing shops are kept separately.")
                if(businesses.isEmpty())Text("This account has no cloud shops yet.")
                businesses.forEach {business ->FilterChip(selected?.id==business.id,{selected=business;newName=business.name;localPassword=""},enabled=!busy,label={Text(business.name)})}
                selected?.let {business ->
                    OutlinedTextField(newName,{newName=it},enabled=!busy,label={Text("Local shop name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(localPassword,{localPassword=it},enabled=!busy,label={Text("New local owner password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("Use at least 8 characters. Staff access is set up separately on each device.")
                    Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {
                        val secret=localPassword;localPassword=""
                        withContext(Dispatchers.IO) {cloud.join(shopId,requireNotNull(authenticatedEndpoint),requireNotNull(account),business.id,deviceName.trim(),newName,secret)}
                        onImported()
                    }}) {Text("Download as a new local shop")}
                }
            } else {
                Button(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {
                    connection=withContext(Dispatchers.IO) {cloud.reconnect(shopId,requireNotNull(account))}
                    notice="Access renewed. Local work will be synchronized."
                }}) {Text("Renew this device's access")}
                OutlinedButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={runWork {devices=withContext(Dispatchers.IO) {cloud.devices(shopId,requireNotNull(account))}}}) {Text("Show connected devices")}
                devices.forEach {device ->
                    Text(device.name+(if(device.id==connection?.deviceId) " · This device" else "")+(if(device.revokedAt!=null) " · Access revoked" else ""))
                    if(device.revokedAt==null) TextButton(modifier=Modifier.heightIn(min=48.dp),enabled=!busy,onClick={revoke=device}) {Text("Revoke ${device.name}")}
                }
            }
        }
        Text("Sync shares saved business records. It does not confirm bank transfers, card payments or settlement. Another offline device may have newer records that have not reached the cloud.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun CloudStatus(cloud:CloudCoordinator,shopId:String,expanded:Boolean=false) {
    var status by remember(cloud,shopId) {mutableStateOf(cloud.status(shopId))}
    LaunchedEffect(cloud,shopId) {while(isActive) {status=cloud.status(shopId);delay(1000)}}
    val title=when(status.phase) {
        "up_to_date"->"Cloud checked · Records synchronized"
        "syncing"->"Checking cloud records…"
        "local_pending"->"Saved here · Waiting to sync"
        "retry_wait"->"Saved here · Cloud connection unavailable"
        "auth_required"->"Saved here · Cloud sign-in needed"
        "conflict"->"Saved here · Sync needs review"
        "paused"->"Saved here · Cloud sync paused"
        else->"Saved here · Cloud not connected"
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            status.lastSyncedAt?.let {Text("Last synchronized: $it",style=MaterialTheme.typography.bodySmall)}
            status.message?.let {Text(it)}
            status.conflicts.forEach {conflict ->Text("${conflict.table.replace('_',' ')}: ${conflict.message}",color=MaterialTheme.colorScheme.error)}
            if(status.conflicts.isNotEmpty())Text("Review the affected records on both devices. Keep their backups. Retry after resolving the underlying business conflict; completed history is never replaced automatically.")
        }
    }
}
