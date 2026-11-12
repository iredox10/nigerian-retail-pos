package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import pos.data.PosRepository
import pos.domain.Tenant
import pos.output.BackupService
import pos.security.WorkspaceAccess
import pos.ui.components.*
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun BackupPanel(repo: PosRepository, tenant: Tenant) {
    var password by remember(tenant.id) { mutableStateOf("") }
    var repeat by remember(tenant.id) { mutableStateOf("") }
    var result by remember(tenant.id) { mutableStateOf<String?>(null) }
    var error by remember(tenant.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max=680.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SectionHeader("Back up this shop", "Encrypted recovery file · ${tenant.name}")
        Text("This file includes this shop's business records only. It does not include other shops, owner passwords, unfinished baskets, or printer settings. Keep the file and its password somewhere you can recover them.")
        OutlinedTextField(password,{password=it},label={Text("Backup password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(repeat,{repeat=it},label={Text("Repeat backup password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("At least 8 characters. This password protects the backup file; it does not encrypt the working database.",style=MaterialTheme.typography.bodySmall)
        error?.let { InlineNotice("Backup not saved",it,NoticeTone.Danger) }
        result?.let { InlineNotice("Backup saved",it,NoticeTone.Success) }
        PosButton(if(saving) "Saving…" else "Choose file & save backup",{
            error=null; result=null
            try {
                require(password.length in 8..1024) { "Use 8 to 1024 characters." }
                require(password==repeat) { "The passwords do not match." }
                val path=chooseBackupFile(save=true)
                if(path!=null) {
                    saving=true
                    BackupService.create(repo,tenant.id,password,path)
                    result="${path.fileName} · Only records already saved for this shop are included."
                    password="";repeat=""
                }
            } catch(e:Exception) { error=e.message ?: "The backup could not be saved. Existing files were not replaced." }
            finally { saving=false }
        },enabled=!saving)
    }
}

@Composable
fun RestoreBackupDialog(repo:PosRepository, access:WorkspaceAccess, dismiss:()->Unit, restored:(Tenant)->Unit) {
    var source by remember { mutableStateOf<Path?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var ownerPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest={if(!working)dismiss()},title={Text("Restore a shop backup")},text={
        Column(Modifier.width(480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Restore into a new local workspace. Existing shops are never overwritten. Restored cash counts are historical: verify the physical drawer before trading.")
            NeutralButton(source?.fileName?.toString() ?: "Choose backup file",{ source=chooseBackupFile(false);error=null })
            OutlinedTextField(backupPassword,{backupPassword=it},label={Text("Backup file password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            PosTextField(name,{name=it},"Name for restored shop")
            OutlinedTextField(ownerPassword,{ownerPassword=it},label={Text("New owner password")},visualTransformation=PasswordVisualTransformation(),singleLine=true)
            Text("Owner passwords are not imported. The new password unlocks only the restored workspace.",style=MaterialTheme.typography.bodySmall)
            error?.let { InlineNotice("Nothing restored",it,NoticeTone.Danger) }
        }
    },confirmButton={ PosButton(if(working) "Restoring…" else "Restore as new shop",{
        try {
            require(source!=null) { "Choose a backup file." };require(name.isNotBlank()) { "Enter a shop name." };require(ownerPassword.length in 8..1024) { "Use 8 to 1024 characters for the new owner password." }
            working=true
            val snapshot=BackupService.read(source!!,backupPassword)
            val shop=repo.restoreTenantSnapshot(snapshot,name.trim()) { access.register(it.id,ownerPassword) }
            restored(shop)
        } catch(e:Exception) { error=e.message ?: "The backup could not be restored." }
        finally { working=false }
    },enabled=!working) },dismissButton={TextButton({if(!working)dismiss()}) { Text("Cancel") }})
}

private fun chooseBackupFile(save:Boolean):Path? {
    val chooser=JFileChooser().apply {
        dialogTitle=if(save) "Save encrypted shop backup" else "Open encrypted shop backup"
        fileFilter=FileNameExtensionFilter("Retail POS encrypted backup (*.rpos)","rpos")
        if(save) selectedFile=java.io.File("shop-backup-${java.time.LocalDate.now()}.rpos")
    }
    val result=if(save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
    if(result!=JFileChooser.APPROVE_OPTION)return null
    val file=chooser.selectedFile.toPath()
    return if(save && !file.fileName.toString().endsWith(".rpos",true)) file.resolveSibling(file.fileName.toString()+".rpos") else file
}
