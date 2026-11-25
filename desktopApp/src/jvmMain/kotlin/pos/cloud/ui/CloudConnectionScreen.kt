package pos.cloud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pos.cloud.client.*
import pos.cloud.protocol.*
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalCloudRevision = staticCompositionLocalOf { 0L }
/** Android supplies a camera/photo QR reader. The typed code remains available on every device. */
val LocalPairingScanner = staticCompositionLocalOf<(((String)->Unit)->Unit)?> {null}

/** Account/session secrets intentionally remain outside saveable navigation state. */
@Composable
fun CloudConnectionScreen(cloud:CloudCoordinator,shopId:String,shopName:String,onImported:()->Unit) = key(shopId) {
    val fresh=shopId==CLOUD_NEW_INSTALLATION
    val scope=rememberCoroutineScope()
    var endpointText by remember {mutableStateOf(CloudSetup.server)}
    var advanced by remember {mutableStateOf(false)}
    var stage by remember {mutableStateOf("intro")}
    var intent by remember {mutableStateOf(if(fresh) "join" else "connect")}
    var email by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var confirmPassword by remember {mutableStateOf("")}
    var creating by remember {mutableStateOf(false)}
    var deviceName by remember {mutableStateOf("Shop device")}
    var account by remember {mutableStateOf<CloudAccountSession?>(null)}
    var authenticatedEndpoint by remember {mutableStateOf<CloudEndpoint?>(null)}
    var connection by remember {mutableStateOf<CloudConnectionInfo?>(null)}
    var businesses by remember {mutableStateOf<List<CloudBusinessSummary>>(emptyList())}
    var devices by remember {mutableStateOf<List<CloudDeviceDetails>>(emptyList())}
    var selected by remember {mutableStateOf<CloudBusinessSummary?>(null)}
    var newName by remember {mutableStateOf(shopName)}
    var localPassword by remember {mutableStateOf("")}
    var localConfirm by remember {mutableStateOf("")}
    var reviewPublish by remember {mutableStateOf(false)}
    var revoke by remember {mutableStateOf<CloudDeviceSummary?>(null)}
    var ticket by remember {mutableStateOf<CloudPairingTicket?>(null)}
    var approved by remember {mutableStateOf<CloudPairingResult?>(null)}
    var pairCode by remember {mutableStateOf("")}
    var pairReview by remember {mutableStateOf<CloudPairingReview?>(null)}
    var recoveryAvailable by remember {mutableStateOf(false)}
    var recoveryCode by remember {mutableStateOf("")}
    var verificationCode by remember {mutableStateOf("")}
    var accountDetails by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val scanner=LocalPairingScanner.current
    fun endpoint()=authenticatedEndpoint ?: CloudEndpoint.parse(endpointText)
    fun runWork(work:suspend ()->Unit) {
        if(busy)return
        busy=true;error=null;notice=null
        scope.launch {
            try {work()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=when(e) {is CloudHttpException,is CloudConnectionException,is IllegalArgumentException ->e.message;else->"Could not complete this step. Your saved shop records are unchanged. Try again."}}
            finally {busy=false}
        }
    }
    fun authenticate()=runWork {
        require(!creating || password==confirmPassword) {"The account passwords do not match."}
        val destination=CloudEndpoint.parse(endpointText)
        val secret=password;password="";confirmPassword=""
        val signed=withContext(Dispatchers.IO) {if(creating)cloud.register(destination,email,secret) else cloud.signIn(destination,email,secret)}
        authenticatedEndpoint=destination;account=signed
        if(intent=="join") {
            businesses=withContext(Dispatchers.IO) {cloud.businesses(destination,signed)}
            stage="shops"
        } else stage=if(connection==null)"publish" else "devices"
        recoveryAvailable=withContext(Dispatchers.IO) {runCatching {cloud.capabilities(destination).emailRecoveryAvailable}.getOrDefault(false)}
    }
    LaunchedEffect(shopId) {
        if(!fresh)try {
            connection=withContext(Dispatchers.IO) {cloud.connection(shopId)}
            connection?.let {endpointText=it.endpoint.url;deviceName=it.deviceName;stage="connected"}
        } catch(_:Exception) {error="The saved connection needs checking. Your records and retry information are kept."}
    }
    LaunchedEffect(ticket?.id) {
        val active=ticket ?:return@LaunchedEffect
        while(isActive && approved==null) {
            if(!Instant.parse(active.expiresAt).isAfter(Instant.now())) {error="This code has expired. Get a new code to continue.";break}
            delay(5000)
            try {
                val result=withContext(Dispatchers.IO) {cloud.pollPairing(CloudEndpoint.parse(endpointText),active)}
                if(result.status=="approved") {approved=result;newName=result.shopName.orEmpty();error=null}
            } catch(e:CancellationException) {throw e}
            catch(e:CloudHttpException) {error=e.message;if(e.status in setOf(400,401,403,410))break}
            catch(_:Exception) {error="Waiting for a connection. Your code will be checked again automatically."}
        }
    }
    if(reviewPublish) AlertDialog(onDismissRequest={if(!busy)reviewPublish=false},title={Text("Connect $shopName?")},text={Text("Share this shop’s saved records and images with devices you approve. Local unlock passwords stay on this device. Existing records are kept.")},confirmButton={Button(enabled=!busy,onClick={runWork {
        connection=withContext(Dispatchers.IO) {cloud.publish(shopId,endpoint(),requireNotNull(account),deviceName.trim())}
        reviewPublish=false;stage="connected"
        withContext(Dispatchers.IO) {cloud.syncNow(shopId)}
        notice="Shop connected. You can now add another device."
    }}) {Text("Connect this shop")}},dismissButton={TextButton(enabled=!busy,onClick={reviewPublish=false}) {Text("Cancel")}})
    pairReview?.let {review ->AlertDialog(onDismissRequest={if(!busy)pairReview=null},title={Text("Allow ${review.deviceName}?")},text={Text("This device will be able to download and share $shopName’s records. Only approve if the code matches the device in front of you. Do not approve codes sent by strangers or support callers.")},confirmButton={Button(enabled=!busy,onClick={runWork {
        withContext(Dispatchers.IO) {cloud.approvePairing(shopId,requireNotNull(account),CloudSetup.pairingCode(pairCode),review)}
        pairReview=null;pairCode="";notice="Device approved. Return to the new device to finish downloading the shop."
    }}) {Text("Allow this device")}},dismissButton={TextButton(enabled=!busy,onClick={pairReview=null}) {Text("Do not allow")}})}
    revoke?.let {device ->AlertDialog(onDismissRequest={if(!busy)revoke=null},title={Text("Remove access for ${device.name}?")},text={Text("This stops future sharing. Records already downloaded remain on that device. Work it has not sent will stay there. Removing access does not erase the device.")},confirmButton={Button(enabled=!busy,onClick={runWork {
        withContext(Dispatchers.IO) {cloud.revokeDevice(shopId,requireNotNull(account),device.id)}
        devices=withContext(Dispatchers.IO) {cloud.deviceDetails(shopId,requireNotNull(account))};revoke=null;notice="Device access removed."
    }}) {Text("Remove access")}},dismissButton={TextButton(enabled=!busy,onClick={revoke=null}) {Text("Keep access")}})}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Connected devices",style=MaterialTheme.typography.headlineSmall)
        Text("Use your shop on a phone or computer. Keep selling when the internet goes off.")
        if(!fresh)CloudStatus(cloud,shopId,expanded=true)
        if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Please wait — keeping your saved records safe.")}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        notice?.let {Text(it,color=MaterialTheme.colorScheme.primary)}
        when(stage) {
            "intro" -> {
                Text(if(fresh) "Bring your shop to this device" else "What would you like to do?",style=MaterialTheme.typography.titleLarge)
                if(!fresh) {
                    Button(enabled=!busy,onClick={intent="connect";creating=true;stage="account"},modifier=Modifier.heightIn(min=48.dp)) {Text("Connect this shop")}
                    Text("Start on the device that already holds your shop’s records.")
                }
                OutlinedButton(enabled=!busy,onClick={intent="join";stage="pair"},modifier=Modifier.heightIn(min=48.dp)) {Text("Use my existing shop")}
                Text("Add this device to a shop you already use elsewhere. Existing shops on this device stay separate.")
            }
            "pair" -> {
                Text("1. Get a connection code",style=MaterialTheme.typography.titleLarge)
                if(ticket==null) {
                    SetupField(deviceName,{deviceName=it},"Name this device",!busy)
                    Text("For example, Shop computer or Owner’s phone.")
                    Button(enabled=!busy,onClick={runWork {ticket=withContext(Dispatchers.IO) {cloud.startPairing(shopId,CloudEndpoint.parse(endpointText),deviceName.trim())}}},modifier=Modifier.heightIn(min=48.dp)) {Text("Get connection code")}
                    TextButton(enabled=!busy,onClick={creating=false;intent="join";stage="account"}) {Text("Sign in instead")}
                } else if(approved==null) {
                    val current=requireNotNull(ticket)
                    PairingQr(current.code)
                    SelectionContainer {Text(current.code,style=MaterialTheme.typography.headlineLarge)}
                    Text("2. On your connected device, open More → Connected devices → Add a device. Scan this QR code or enter the code above, then approve.")
                    Text("Expires ${friendlyTime(current.expiresAt)}. Keep this screen open. Only connect devices you trust.")
                    TextButton(enabled=!busy,onClick={runWork {withContext(Dispatchers.IO) {cloud.discardPairing(shopId)};ticket=null;approved=null}}) {Text("Start again")}
                } else {
                    Text("Approved for ${approved?.shopName}",style=MaterialTheme.typography.titleMedium)
                    Text("3. Choose a password to unlock this shop on this device. It does not change passwords on your other devices.")
                    SetupField(newName,{newName=it},"Shop name on this device",!busy)
                    SetupPassword(localPassword,{localPassword=it},"Device unlock password (at least 8 characters)",!busy)
                    SetupPassword(localConfirm,{localConfirm=it},"Confirm device unlock password",!busy)
                    Button(enabled=!busy,onClick={runWork {
                        require(localPassword==localConfirm) {"The device unlock passwords do not match."}
                        val secret=localPassword;localPassword="";localConfirm=""
                        withContext(Dispatchers.IO) {cloud.joinPaired(shopId,CloudEndpoint.parse(endpointText),requireNotNull(ticket),newName,secret)}
                        onImported()
                    }},modifier=Modifier.heightIn(min=48.dp)) {Text("Download my shop")}
                    Text("Check that this is your shop before downloading. Local staff access is set up separately.")
                }
            }
            "account" -> {
                Text(if(creating) "Create your shop account" else "Sign in to your account",style=MaterialTheme.typography.titleLarge)
                Text("Your account manages connected devices. Your shop password unlocks this device and continues to work offline.")
                SetupField(email,{email=it},"Account email",!busy,KeyboardType.Email)
                SetupPassword(password,{password=it},if(creating)"Account password (at least 12 characters)" else "Account password",!busy)
                if(creating) SetupPassword(confirmPassword,{confirmPassword=it},"Confirm account password",!busy)
                Button(enabled=!busy,onClick={authenticate()},modifier=Modifier.heightIn(min=48.dp)) {Text(if(creating)"Create account" else "Sign in")}
                TextButton(enabled=!busy,onClick={creating=!creating;password="";confirmPassword=""}) {Text(if(creating)"I already have an account" else "Create an account")}
                TextButton(enabled=!busy,onClick={runWork {recoveryAvailable=withContext(Dispatchers.IO) {cloud.capabilities(CloudEndpoint.parse(endpointText)).emailRecoveryAvailable};stage="reset"}}) {Text("Forgot account password?")}
            }
            "reset" -> {
                Text("Recover your account",style=MaterialTheme.typography.titleLarge)
                if(!recoveryAvailable) Text("Email recovery is not available on this service yet. Your local shop password still works. Ask the service owner to configure email delivery.")
                else {
                    SetupField(email,{email=it},"Verified account email",!busy,KeyboardType.Email)
                    OutlinedButton(enabled=!busy,onClick={runWork {notice=withContext(Dispatchers.IO) {cloud.requestPasswordReset(CloudEndpoint.parse(endpointText),email)}.message}}) {Text("Send reset code")}
                    SetupField(recoveryCode,{recoveryCode=it},"Code from your email",!busy)
                    SetupPassword(password,{password=it},"New account password (at least 12 characters)",!busy)
                    SetupPassword(confirmPassword,{confirmPassword=it},"Confirm new account password",!busy)
                    Button(enabled=!busy,onClick={runWork {
                        require(password==confirmPassword) {"The account passwords do not match."}
                        val secret=password;password="";confirmPassword=""
                        notice=withContext(Dispatchers.IO) {cloud.resetAccountPassword(CloudEndpoint.parse(endpointText),email,recoveryCode,secret)}.message
                        recoveryCode="";account=null;authenticatedEndpoint=null;creating=false;stage="account"
                    }}) {Text("Change account password")}
                    Text("Changing this password removes existing cloud access. Sign in again on each device to restore sharing. Saved shop records and local passwords stay unchanged.")
                }
                TextButton(enabled=!busy,onClick={stage="account";password="";confirmPassword=""}) {Text("Back to sign in")}
            }
            "publish" -> {
                Text("Connect $shopName",style=MaterialTheme.typography.titleLarge)
                SetupField(deviceName,{deviceName=it},"Name this device",!busy)
                Text("Your saved products, sales, customers and images will be shared with devices you approve.")
                Button(enabled=!busy,onClick={reviewPublish=true},modifier=Modifier.heightIn(min=48.dp)) {Text("Review and connect")}
            }
            "shops" -> {
                Text("Choose your shop",style=MaterialTheme.typography.titleLarge)
                if(businesses.isEmpty())Text("This account has no connected shops. Connect the device holding your records first.")
                businesses.forEach {business ->FilterChip(selected?.id==business.id,{selected=business;newName=business.name},enabled=!busy,label={Text(business.name)})}
                selected?.let {business ->
                    SetupField(deviceName,{deviceName=it},"Name this device",!busy)
                    SetupField(newName,{newName=it},"Shop name on this device",!busy)
                    SetupPassword(localPassword,{localPassword=it},"Device unlock password (at least 8 characters)",!busy)
                    SetupPassword(localConfirm,{localConfirm=it},"Confirm device unlock password",!busy)
                    Text("This creates a separate local shop. Existing shops are kept. Staff access is set up separately on this device.")
                    Button(enabled=!busy,onClick={runWork {
                        require(localPassword==localConfirm) {"The device unlock passwords do not match."}
                        val secret=localPassword;localPassword="";localConfirm=""
                        withContext(Dispatchers.IO) {cloud.join(shopId,endpoint(),requireNotNull(account),business.id,deviceName.trim(),newName,secret)}
                        onImported()
                    }},modifier=Modifier.heightIn(min=48.dp)) {Text("Download my shop")}
                }
            }
            "connected","devices" -> {
                Text("This device: ${connection?.deviceName.orEmpty()}",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(enabled=!busy,onClick={runWork {notice=withContext(Dispatchers.IO) {cloud.syncNow(shopId)}.message}}) {Text("Check for updates")}
                if(account==null) {
                    Button(enabled=!busy,onClick={intent="manage";creating=false;stage="account"},modifier=Modifier.heightIn(min=48.dp)) {Text("Add or manage devices")}
                    Text("Owner account sign-in is needed only to manage access. Everyday sharing runs automatically while the shop is open and unlocked.")
                } else {
                    HorizontalDivider()
                    Text("Add a device",style=MaterialTheme.typography.titleLarge)
                    Text("On the new device, choose Use my existing shop → Get connection code. Enter that code here.")
                    SetupField(pairCode,{pairCode=it;pairReview=null},"Code shown on the new device",!busy)
                    scanner?.let {scan ->OutlinedButton(enabled=!busy,onClick={scan {pairCode=it;pairReview=null}}) {Text("Scan QR code")}}
                    Button(enabled=!busy,onClick={runWork {pairCode=CloudSetup.pairingCode(pairCode);pairReview=withContext(Dispatchers.IO) {cloud.reviewPairing(shopId,requireNotNull(account),pairCode)}}},modifier=Modifier.heightIn(min=48.dp)) {Text("Review device")}
                    HorizontalDivider()
                    OutlinedButton(enabled=!busy,onClick={runWork {devices=withContext(Dispatchers.IO) {cloud.deviceDetails(shopId,requireNotNull(account))}}}) {Text("Show connected devices")}
                    devices.forEach {detail ->
                        val device=detail.device
                        Text(device.name+(if(device.id==connection?.deviceId) " · This device" else ""),style=MaterialTheme.typography.titleMedium)
                        Text(if(device.revokedAt!=null) "Access removed" else "Last connected: "+(detail.lastSeenAt?.let(::friendlyTime) ?: "Not yet recorded"))
                        if(device.revokedAt==null)OutlinedButton(enabled=!busy,onClick={revoke=device}) {Text("Remove access")}
                    }
                    TextButton(enabled=!busy,onClick={runWork {connection=withContext(Dispatchers.IO) {cloud.reconnect(shopId,requireNotNull(account))};notice="Device access restored. Saved work will be checked automatically."}}) {Text("Restore this device’s connection")}
                }
            }
        }
        if(account!=null) {
            HorizontalDivider()
            TextButton(enabled=!busy,onClick={accountDetails=!accountDetails}) {Text(if(accountDetails)"Hide account options" else "Account and recovery")}
            if(accountDetails) {
                if(recoveryAvailable) {
                    Text("Verify your email so you can recover your account if you forget its password.")
                    OutlinedButton(enabled=!busy,onClick={runWork {notice=withContext(Dispatchers.IO) {cloud.sendVerification(endpoint(),requireNotNull(account))}.message}}) {Text("Send verification code")}
                    SetupField(verificationCode,{verificationCode=it},"Code from your email",!busy)
                    OutlinedButton(enabled=!busy,onClick={runWork {notice=withContext(Dispatchers.IO) {cloud.verifyEmail(endpoint(),requireNotNull(account),verificationCode)}.message;verificationCode=""}}) {Text("Verify email")}
                } else Text("Email verification and recovery are unavailable until the service owner configures delivery. Keep your account password safe.")
                TextButton(enabled=!busy,onClick={account=null;authenticatedEndpoint=null;devices=emptyList();businesses=emptyList();selected=null;password="";localPassword="";stage=if(connection==null)"intro" else "connected"}) {Text("Sign out of account setup")}
            }
        }
        if(stage !in setOf("connected","devices")) {
            TextButton(enabled=!busy && ticket==null && account==null,onClick={advanced=!advanced}) {Text(if(advanced)"Hide advanced settings" else "Advanced settings")}
            if(advanced) {
                SetupField(endpointText,{endpointText=it},"Custom HTTPS server address",!busy && ticket==null && account==null,KeyboardType.Uri)
                Text("For separately hosted services. Most shop owners can leave this unchanged.")
            }
            if(stage!="intro")TextButton(enabled=!busy,onClick={stage="intro";ticket=null;approved=null;password="";localPassword="";localConfirm="";error=null}) {Text("Back to connection choices")}
        }
        HorizontalDivider()
        Text("Sharing does not confirm bank transfers or card payments. Another offline device may have work it has not sent yet. Keep separate shop backups.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun SetupField(value:String,change:(String)->Unit,label:String,enabled:Boolean,type:KeyboardType=KeyboardType.Text) {
    OutlinedTextField(value,change,label={Text(label)},enabled=enabled,singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
}
@Composable private fun SetupPassword(value:String,change:(String)->Unit,label:String,enabled:Boolean) {
    OutlinedTextField(value,change,label={Text(label)},enabled=enabled,singleLine=true,visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp))
}
@Composable private fun PairingQr(code:String) {
    val matrix=remember(code) {CloudSetup.qr(code)}
    Canvas(Modifier.size(200.dp).background(Color.White).semantics {contentDescription="Connection QR code. You can also type the code shown below."}) {
        val cell=size.width/matrix.size
        matrix.forEachIndexed {y,row ->row.forEachIndexed {x,black ->if(black) drawRect(Color.Black,Offset(x*cell,y*cell),Size(cell,cell))}}
    }
}
private fun friendlyTime(value:String):String=runCatching {
    val instant=Instant.parse(value)
    val seconds=Duration.between(instant,Instant.now()).seconds
    when {seconds in 0..59 ->"just now";seconds in 60..3599 ->"${seconds/60} minutes ago";else ->DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault()).format(instant)}
}.getOrDefault("Time unavailable")

@Composable
fun CloudStatus(cloud:CloudCoordinator,shopId:String,expanded:Boolean=false) {
    var status by remember(cloud,shopId) {mutableStateOf(cloud.status(shopId))}
    var details by remember {mutableStateOf(false)}
    LaunchedEffect(cloud,shopId) {while(isActive) {status=cloud.status(shopId);delay(1000)}}
    val title=when(status.phase) {
        "up_to_date"->"Changes sent · Last checked ${status.lastContactAt?.let(::friendlyTime) ?: "recently"}"
        "syncing"->"Updating your other devices…"
        "local_pending"->"Saved on this device · Waiting to share"
        "retry_wait"->"Connection unavailable — you can keep selling"
        "auth_required"->"Saved on this device · Owner sign-in needed"
        "conflict"->"Saved on this device · Some changes need checking"
        "paused"->"Saved on this device · Sharing paused"
        else->"Saved on this device"
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            if(status.phase=="retry_wait")Text("Your saved work will be sent automatically when the connection returns. You do not need to record sales again.")
            if(status.phase=="auth_required")Text("The owner can sign in below and restore this device’s connection. Local shop access is unchanged.")
            status.conflicts.forEach {conflict ->Text(friendlyConflict(conflict),color=MaterialTheme.colorScheme.error)}
            if(status.conflicts.isNotEmpty())Text("Keep backups from both devices. Review the original records and record an authorized correction. Completed sales are never overwritten to make sharing succeed.")
            TextButton(onClick={details=!details}) {Text(if(details)"Hide connection details" else "Connection details")}
            if(details)SelectionContainer {
                Column {
                    Text("Status: ${status.phase}")
                    Text("Last server check: ${status.lastContactAt ?: "Not recorded"}")
                    Text("Last completed update: ${status.lastSyncedAt ?: "Not recorded"}")
                    Text("Changes in the current batch: ${status.pendingRows}. This is not a sales count.")
                    status.message?.let {Text(it)}
                    status.conflicts.forEach {Text("${it.table}: ${it.message}")}
                }
            }
        }
    }
}
private fun friendlyConflict(conflict:CloudConflict):String=when {
    conflict.table.startsWith("stock_count") || "count" in conflict.message.lowercase() ->"Stock changed elsewhere after a count. Review the count and stock history before recording an adjustment."
    conflict.table in setOf("returns","return_lines") ->"A return needs checking against the original sale and earlier refunds."
    conflict.table.startsWith("cash_") ->"Cash records changed on another device. Review the drawer history with the owner."
    conflict.table=="products" ->"An item changed on more than one device. Review its item details and stock history."
    else ->"Some shop records changed on more than one device. The owner needs to review them before sharing can continue."
}
