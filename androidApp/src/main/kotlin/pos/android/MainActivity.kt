package pos.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import pos.android.storage.AndroidSqliteDriver
import pos.android.ui.*
import pos.data.*
import pos.domain.*
import pos.cloud.client.*
import pos.cloud.app.RepositoryCloudLocalStore
import pos.cloud.ui.*
import pos.android.cloud.AndroidCloudCredentials
import pos.security.*
import pos.ui.theme.PosTheme
import pos.ui.components.*

class MainActivity : ComponentActivity() {
    private lateinit var repo: PosRepository
    private lateinit var access: StaffAccess
    private val backgroundHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val backgroundLock = Runnable { lockAction?.invoke() }
    private var lockAction: (() -> Unit)? = null
    private var interactionAction: (() -> Unit)? = null
    private var lastInteractionMillis = Long.MIN_VALUE
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // PosTheme uses a light canvas; system icons must remain visible on it,
        // including when the phone itself is configured for a dark system theme.
        enableEdgeToEdge(
            statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
            navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT),
        )
        System.setProperty("pos.dataDir",filesDir.absolutePath)
        LocalSqlite.installPlatformAdapter(AndroidSqliteDriver::open)
        pos.android.platform.AndroidPrivateFiles.install()
        repo=PosRepository(filesDir.resolve("pos.db").path)
        access=StaffAccess(filesDir.toPath())
        setContent {PosTheme {AndroidApp(repo,access,{lockAction=it},{interactionAction=it})}}
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        val now=android.os.SystemClock.elapsedRealtime()
        if(lastInteractionMillis==Long.MIN_VALUE || now-lastInteractionMillis>=1000) {
            lastInteractionMillis=now
            interactionAction?.invoke()
        }
    }
    override fun onStart() {super.onStart();backgroundHandler.removeCallbacks(backgroundLock)}
    override fun onStop() {backgroundHandler.postDelayed(backgroundLock,60_000);super.onStop()}
    override fun onDestroy() {backgroundHandler.removeCallbacks(backgroundLock);access.close();repo.close();super.onDestroy()}
}

@Composable
private fun AndroidApp(repo:PosRepository,access:StaffAccess,installLock:((()->Unit))->Unit,installInteraction:((()->Unit))->Unit) {
    var session by remember {mutableStateOf<StaffSession?>(null)}
    var tenant by remember {mutableStateOf<Tenant?>(null)}
    var revision by remember {mutableIntStateOf(0)}
    var route by remember {mutableStateOf("Sell")}
    var receipt by remember {mutableStateOf<String?>(null)}
    var approvalRequest by remember {mutableStateOf<MinimumPriceApprovalRequest?>(null)}
    var approvalResponse by remember {mutableStateOf<((String)->Unit)?>(null)}
    fun lock(){session?.let(access::logout);session=null;tenant=null;receipt=null;route="Sell";approvalRequest=null;approvalResponse=null}
    val cloud=remember(repo) {
        val root=java.nio.file.Path.of(System.getProperty("pos.dataDir"),"cloud")
        val protection=CloudFileProtection {path,directory ->pos.security.LocalFilePermissions.restrict(path,if(directory) "rwx------" else "rw-------")}
        CloudCoordinator(RepositoryCloudLocalStore(repo,WorkspaceAccess(),{PosRepository(java.io.File(System.getProperty("pos.dataDir"),"pos.db").path)}) {id ->
            val identity=requireNotNull(session) {"Sign in as the local owner."}
            require(identity.tenantId==id && identity.role==StaffRole.OWNER) {"Only this shop's owner can configure cloud access."}
            access.requireSession(identity,StaffPermission.BACKUP,false)
        },HttpCloudTransport(),CloudStateStore(PrivateCloudFiles(root.resolve("checkpoints"),protection)),AndroidCloudCredentials(PrivateCloudFiles(root.resolve("credentials"),protection)))
    }
    DisposableEffect(cloud) {onDispose {cloud.close()}}
    val lifecycle=(androidx.compose.ui.platform.LocalContext.current as ComponentActivity).lifecycle
    DisposableEffect(session?.token,lifecycle) {
        fun resumeSync() {
            val identity=session
            if(identity!=null && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))cloud.activate(identity.tenantId) else cloud.lock()
        }
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START)resumeSync()
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)cloud.lock()
        }
        lifecycle.addObserver(observer);resumeSync()
        onDispose {lifecycle.removeObserver(observer);cloud.lock()}
    }
    var cloudRevision by remember {mutableLongStateOf(0L)}
    LaunchedEffect(cloud,session?.token) {
        var last:String?=null
        while(session!=null) {
            val synced=cloud.status(requireNotNull(session).tenantId).lastSyncedAt
            if(synced!=last) {last=synced;cloudRevision++}
            kotlinx.coroutines.delay(1000)
        }
    }
    fun changed() {revision++;tenant?.let {cloud.localChanged(it.id)}}
    SideEffect {
        installLock(::lock)
        installInteraction {session?.let {identity ->
            try {access.touchSession(identity)} catch(_:Exception) {lock()}
        }}
        repo.setPermissionCheck {id,permission ->
            val active=requireNotNull(session) {"Sign in to use this shop."}
            require(active.tenantId==id) {"Sign in to this shop."}
            access.requireSession(active,permission,touch=false)
        }
        repo.setActorProvider {id ->session?.takeIf {it.tenantId==id}?.let {AuditActor(id,it.staffId,it.name,it.role)}}
        repo.setMinimumPriceApprovalCheck {id,grant,request -> val active=requireNotNull(session);require(active.tenantId==id);access.requireMinimumPriceApproval(active,grant,request)}
        repo.setCashHandoverAuthorizationCheck {id,grant,request ->val active=requireNotNull(session);require(active.tenantId==id);access.requireCashHandover(active,grant,request)}
    }
    LaunchedEffect(session?.token) {while(session!=null) {kotlinx.coroutines.delay(15_000);if(runCatching {access.requireSession(requireNotNull(session),StaffPermission.SELL,false)}.isFailure)lock()}}
    val current=tenant;val active=session
    if(current==null || active==null) {
        AndroidSignIn(repo,access) {shop,identity ->tenant=shop;session=identity;changed()}
        return
    }
    fun allowed(p:StaffPermission)=runCatching {access.requireSession(active,p,false)}.isSuccess
    approvalRequest?.let {request -> AndroidPriceApprovalDialog(access,active,request,
        dismiss={approvalRequest=null;approvalResponse=null},
        approved={token ->approvalResponse?.invoke(token);approvalRequest=null;approvalResponse=null}) }
    BackHandler(receipt!=null || route!="Sell") {if(receipt!=null)receipt=null else route="Sell"}
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {Text(current.name,style=MaterialTheme.typography.titleMedium);Text(active.name,style=MaterialTheme.typography.bodySmall);CloudStatus(cloud,current.id)}
            TextButton({lock()}) {Text("Lock")}
        }
        HorizontalDivider()
        CompositionLocalProvider(LocalCloudRevision provides cloudRevision) {
        Box(Modifier.weight(1f).padding(16.dp)) {key(current.id,route) {
            if(receipt!=null) AndroidReceiptScreen(repo,current.id,requireNotNull(receipt),onBack={receipt=null})
            else when(route) {
                "Sell" -> AndroidSellScreen(repo,current.id,{changed()},{receipt=it},
                    onRequestApproval=if(android.os.Build.VERSION.SDK_INT>=33) ({lines,operation,response ->
                        approvalRequest=repo.minimumPriceApprovalRequest(current.id,operation,lines);approvalResponse=response
                    }) else null, allowDiscounts=allowed(StaffPermission.DISCOUNT))
                "Sales" -> if(allowed(StaffPermission.VIEW_SALES)) AndroidSalesScreen(repo,current.id) {receipt=it} else Text("Your role cannot view sales.")
                "Stock" -> if(allowed(StaffPermission.MANAGE_STOCK)) AndroidStockScreen(repo,current.id) {changed()} else Text("Your role cannot manage stock.")
                "Customers" -> if(allowed(StaffPermission.MANAGE_CUSTOMERS)) AndroidCustomersScreen(repo,current.id) {changed()} else Text("Your role cannot manage customers.")
                "Money" -> if(allowed(StaffPermission.MANAGE_MONEY)) AndroidMoneyScreen(repo,current.id) {changed()} else Text("Your role cannot manage money.")
                "Cloud connection" -> if(active.role==StaffRole.OWNER) CloudConnectionScreen(cloud,current.id,current.name) {lock()} else Text("Owner sign-in required.")
                else -> AndroidMoreScreen(repo,current.id,access,active,{changed()},onCloud={route="Cloud connection"})
            }
        }}}
        HorizontalDivider()
        Column {
          listOf("Sell","Sales","Stock","Customers","Money","More").chunked(3).forEach { destinations ->
          Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            destinations.forEach {item ->
                TextButton({route=item;receipt=null},contentPadding=PaddingValues(horizontal=3.dp,vertical=12.dp),modifier=Modifier.weight(1f)) {
                    PosIcon(destinationIcon(item), Modifier.size(18.dp), tint=if(route==item)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(item,style=MaterialTheme.typography.labelLarge,color=if(route==item)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                }
            }
          }
          }
        }
    }
}

@Composable
private fun AndroidSignIn(repo:PosRepository,access:StaffAccess,onEnter:(Tenant,StaffSession)->Unit) {
    var shops by remember {mutableStateOf(repo.tenants())}
    var selected by remember {mutableStateOf(shops.firstOrNull())}
    var create by remember {mutableStateOf(shops.isEmpty())}
    var name by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var memberId by remember {mutableStateOf<String?>(null)}
    var usePin by remember {mutableStateOf(false)}
    var configuringPin by remember {mutableStateOf(false)}
    var recovering by remember {mutableStateOf(false)}
    var notice by remember {mutableStateOf<String?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    val owners=remember {WorkspaceAccess()}
    val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)}
    val members=remember(selected?.id) {selected?.let {access.signInMembers(it.id)}.orEmpty()}
    LaunchedEffect(selected?.id,memberId,create) {usePin=false;password="";notice=null;error=null;configuringPin=false;recovering=false}
    if(configuringPin && selected!=null && memberId!=null) AndroidPinSetupDialog(access,requireNotNull(selected).id,requireNotNull(memberId),
        onDismiss={configuringPin=false},onSaved={configuringPin=false;password="";notice="PIN settings saved. Use your PIN while its authorization remains valid."})
    if(recovering && selected!=null) AndroidRecoveryDialog(access,requireNotNull(selected).id,
        onDismiss={recovering=false},onRecovered={recovering=false;password="";usePin=false;notice="Owner access recovered. Sign in with your new password."})
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Retail POS", "Sell and keep shop records without internet")
        Text("Records save on this device. After opening a shop, use More → Cloud connection to link your other devices.")
        if(!create) {
            shops.forEach {shop -> FilterChip(selected?.id==shop.id,{selected=shop;memberId=null;password=""},label={Text(shop.name)})}
            FilterChip(memberId==null,{memberId=null;password=""},label={Text("Owner")})
            members.forEach {member ->FilterChip(memberId==member.id,{memberId=member.id;password=""},label={Text("${member.name} · ${member.role}")})}
        } else PosTextField(name,{name=it},"Shop name")
        if(!create && memberId!=null) {
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                FilterChip(!usePin,{usePin=false;password=""},label={Text("Password")})
                FilterChip(usePin,{usePin=true;password=""},label={Text("Staff PIN")})
            }
            Text("PIN access must first be authorized using this staff member's full password and expires under the shop's security policy.",style=MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(password,{password=it},label={Text(if(create)"Owner password (at least 8 characters)" else if(usePin && memberId!=null) "Staff PIN" else "Password")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true)
        notice?.let {InlineNotice(it,tone=NoticeTone.Info)}
        error?.let {InlineNotice("Could not sign in",it,NoticeTone.Danger)}
        Button(onClick={
            val creating=create;val chosen=selected;val staff=memberId;val pinLogin=usePin
            val attemptPassword=password;val shopName=name.trim()
            busy=true;error=null
            scope.launch {
                try {
                    val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val shop=if(creating) {require(shopName.isNotBlank());require(attemptPassword.length in 8..1024);repo.createTenant(shopName) {owners.register(it.id,attemptPassword)}} else requireNotNull(chosen)
                        val identity=if(creating || staff==null) access.unlockOwner(shop.id,attemptPassword) else if(pinLogin) access.unlockStaffPin(shop.id,staff,attemptPassword) else access.unlockStaff(shop.id,staff,attemptPassword)
                        shop to requireNotNull(identity) {"Password or PIN was not accepted, authorization expired, or sign-in is temporarily locked."}
                    }
                    password="";onEnter(result.first,result.second)
                } catch(e:Exception){error=e.message ?: "Try again."} finally {busy=false}
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {Text(if(busy)"Opening shop…" else if(create)"Create shop" else "Open shop")}
        if(!create && selected!=null && memberId==null) TextButton({recovering=true;password=""}) {Text("Recover owner access")}
        if(!create && selected!=null && memberId!=null) TextButton({configuringPin=true;password=""}) {Text("Set up or remove staff PIN")}
        if(shops.isNotEmpty()) TextButton({create=!create;error=null;password=""}) {Text(if(create && shops.isNotEmpty())"Use an existing shop" else "Create another shop")}
    }
}
