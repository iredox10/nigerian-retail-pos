package pos.android.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pos.data.PosRepository
import pos.domain.StaffPermission
import pos.output.BackupService
import pos.security.WorkspaceAccess
import pos.ui.components.*
import java.io.File
import java.nio.file.Files

@Composable
fun AndroidBackupScreen(repo:PosRepository,tenantId:String,access:WorkspaceAccess,onRestored:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var password by remember {mutableStateOf("")};var repeat by remember {mutableStateOf("")}
    var newName by remember {mutableStateOf("")};var ownerPassword by remember {mutableStateOf("")}
    var backupFile by remember {mutableStateOf<File?>(null)}
    var busy by remember {mutableStateOf(false)};var notice by remember {mutableStateOf<String?>(null)};var error by remember {mutableStateOf<String?>(null)}
    DisposableEffect(Unit){onDispose {backupFile?.delete()}}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->
        val source=backupFile
        if(uri==null){source?.delete();backupFile=null;busy=false;notice="Backup export cancelled."}
        else scope.launch {try {
            withContext(Dispatchers.IO){
                repo.featureRead(tenantId,StaffPermission.BACKUP){Unit}
                requireNotNull(source){"Prepare the encrypted backup again."}
                requireNotNull(context.contentResolver.openOutputStream(uri,"wt")){"The selected destination cannot be written."}.use {output->source.inputStream().use {it.copyTo(output)};output.flush()}
            }
            notice="Encrypted backup saved to the selected document. Keep its password separately."
        }catch(e:Exception){error=e.message ?: "Backup could not be exported. The selected document may be incomplete."}finally {source?.delete();backupFile=null;busy=false}}
    }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri==null){busy=false;notice="Restore cancelled."}
        else scope.launch {
            var temp:File?=null
            try {
                withContext(Dispatchers.IO){
                    repo.featureRead(tenantId,StaffPermission.BACKUP){Unit}
                    require(newName.isNotBlank() && ownerPassword.length in 8..1024){"Enter the restored shop name and a new owner password."}
                    val file=File.createTempFile("restore-",".rpos",context.cacheDir).also {temp=it}
                    requireNotNull(context.contentResolver.openInputStream(uri)){"The selected document cannot be read."}.use {input->file.outputStream().use {output->
                        val buffer=ByteArray(8192);var total=0L
                        while(true){val count=input.read(buffer);if(count<0)break;total+=count;require(total<=64L*1024*1024+1024){"Backup exceeds the supported size."};output.write(buffer,0,count)}
                    }}
                    val snapshot=BackupService.read(file.toPath(),password)
                    // Explicit trusted recovery connection. Never disable the live shop's session guard.
                    pos.android.backup.restoreAndroidBackup(repo,tenantId,snapshot,context.filesDir.resolve("pos.db").path,newName,ownerPassword,access)
                }
                notice="Backup restored as a new shop. Lock and select it to sign in. Verify its historical drawer count before trading."
                onRestored()
            }catch(e:Exception){error=e.message ?: "No shop restored."}finally {temp?.delete();password="";repeat="";ownerPassword="";busy=false}
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Encrypted shop backup","Save or restore local records with a separate password")
        Text("Backup files contain this shop's saved records and managed images. They do not include owner/staff passwords or recovery codes. Cloud backup is not connected.")
        error?.let {InlineNotice("Backup action unavailable",it,NoticeTone.Danger)}
        notice?.let {InlineNotice(it,tone=NoticeTone.Info)}
        OutlinedTextField(password,{password=it},label={Text("Backup password (8–1024 characters)")},visualTransformation=PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        OutlinedTextField(repeat,{repeat=it},label={Text("Repeat backup password for export")},visualTransformation=PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        Button({
            scope.launch {busy=true;error=null;notice=null
                try {val file=withContext(Dispatchers.IO){
                    require(password.length in 8..1024 && password==repeat){"Use matching backup passwords of 8–1024 characters."}
                    File(context.cacheDir,"backup-${java.util.UUID.randomUUID()}.rpos").also {BackupService.create(repo,tenantId,password,it.toPath())}
                };backupFile=file;password="";repeat="";save.launch("shop-backup-${java.time.LocalDate.now()}.rpos")}
                catch(e:Exception){error=e.message ?: "Backup could not be prepared.";busy=false}
            }
        },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(busy)"Working…" else "Choose document & save encrypted backup")}
        HorizontalDivider()
        Text("Restore as a new shop",style=MaterialTheme.typography.titleMedium)
        Text("Existing shops stay unchanged. Importing a desktop file does not create synchronization. Restored cash counts are historical: verify the actual drawer.")
        PosTextField(newName,{newName=it},"Name for restored shop")
        OutlinedTextField(ownerPassword,{ownerPassword=it},label={Text("New owner password")},visualTransformation=PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        OutlinedButton({
            error=null;notice=null
            runCatching {repo.featureRead(tenantId,StaffPermission.BACKUP){Unit};require(password.length in 8..1024);require(newName.isNotBlank() && ownerPassword.length in 8..1024);busy=true;restore.launch(arrayOf("application/octet-stream","*/*"))}
                .onFailure {error=it.message ?: "Enter the backup password, new shop name and owner password.";busy=false}
        },enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("Choose encrypted backup & restore")}
    }
}
