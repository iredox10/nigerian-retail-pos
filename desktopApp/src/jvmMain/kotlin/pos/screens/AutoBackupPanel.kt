package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pos.backup.AutoBackupCoordinator
import pos.backup.AutoBackupStatus
import pos.domain.LocalBackupPolicy
import pos.domain.Tenant
import pos.ui.components.*
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

/** Session-only encryption access, with local saved copies and restore evidence kept distinct. */
@Composable
fun AutoBackupPanel(coordinator:AutoBackupCoordinator,tenant:Tenant) {
    val initial=remember(coordinator,tenant.id) {runCatching {coordinator.policy(tenant.id)}}
    var directory by remember(tenant.id) {mutableStateOf(initial.getOrNull()?.destinationDirectory.orEmpty())}
    var minutes by remember(tenant.id) {mutableStateOf((initial.getOrNull()?.intervalMinutes ?: 60).toString())}
    var versions by remember(tenant.id) {mutableStateOf((initial.getOrNull()?.retainedVersions ?: 10).toString())}
    var password by remember(tenant.id) {mutableStateOf("")}
    var repeat by remember(tenant.id) {mutableStateOf("")}
    var status by remember(tenant.id) {mutableStateOf<AutoBackupStatus?>(null)}
    var error by remember(tenant.id) {mutableStateOf(initial.exceptionOrNull()?.message)}
    var message by remember(tenant.id) {mutableStateOf<String?>(null)}
    var working by remember {mutableStateOf(false)}
    fun refresh() {try {status=coordinator.status(tenant.id)} catch(e:Exception) {error=e.message ?: "Backup status is unavailable."}}
    fun perform(action:()->String) {
        working=true;error=null;message=null
        try {message=action()} catch(e:Exception) {error=e.message ?: "Earlier saved copies were kept. Try again."}
        finally {working=false;refresh()}
    }
    LaunchedEffect(coordinator,tenant.id) {while(true) {refresh();delay(5000)}}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        SectionHeader("Automatic local backups","Encrypted versions · this shop only")
        Text("Backups run while this owner session stays unlocked. Enter the backup password after a lock or restart, and at least every four hours. It is held in memory for this session; it is never saved in settings.")
        Text("A folder on this computer cannot protect you if the computer is lost or damaged. Keep a separate recovery copy and its password somewhere safe. No cloud copy is created.",style=MaterialTheme.typography.bodySmall)
        Text("Chosen folder",style=MaterialTheme.typography.labelMedium)
        Text(directory.ifBlank {"Not chosen"},style=MaterialTheme.typography.bodyMedium)
        NeutralButton("Choose backup folder",{
            val chooser=JFileChooser().apply {dialogTitle="Choose local automatic backup folder";fileSelectionMode=JFileChooser.DIRECTORIES_ONLY}
            if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {directory=chooser.selectedFile.toPath().toAbsolutePath().toString();message=null}
        },enabled=!working)
        PosTextField(minutes,{minutes=it},"Save every (minutes, 5–1440)",Modifier.fillMaxWidth())
        PosTextField(versions,{versions=it},"Keep recent versions (2–100)",Modifier.fillMaxWidth())
        Text("Older versions are removed only after a new encrypted copy passes its file check. Changed or unreadable copies and unrelated files are kept.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(password,{password=it},label={Text("Automatic backup password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(repeat,{repeat=it},label={Text("Repeat password to start backups")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Use 8–1024 characters. If this folder already has automatic versions, use the same backup password. Losing it prevents recovery of those files.",style=MaterialTheme.typography.bodySmall)
        PosButton(if(working) "Working…" else "Start backups for this session",{
            perform {
                try {
                    require(password==repeat) {"The passwords do not match."}
                    val policy=LocalBackupPolicy(Path.of(directory).toAbsolutePath().toString(),minutes.toIntOrNull() ?: 0,versions.toIntOrNull() ?: 0)
                    val path=coordinator.enable(tenant.id,policy,password.toCharArray())
                    "Encrypted backup saved: ${path.fileName}. Automatic backups are running for this session."
                } finally {password="";repeat=""}
            }
        },enabled=!working && directory.isNotBlank())
        status?.let {value ->
            HorizontalDivider()
            LabelValueRow("Automatic backups",when {value.runningForSession -> "Running for this session";value.policy?.enabled==false -> "Paused";value.policy!=null -> "Waiting for backup password";else -> "Not set up"})
            value.sessionExpiresAt?.let {LabelValueRow("Password expires",backupTime(it))}
            value.nextDueAt?.let {LabelValueRow("Next scheduled copy",backupTime(it))}
            LabelValueRow("Newest saved copy",value.latestCreatedAt?.let(::backupTime) ?: "None saved")
            value.latestFile?.let {Text(it.toAbsolutePath().toString(),style=MaterialTheme.typography.bodySmall)}
            LabelValueRow("Versions listed",value.recordedVersions.toString())
            LabelValueRow("Last password and encryption check",value.checkedAt?.let(::backupTime) ?: "Not checked in this session")
            Text(if(value.checkedFile==value.latestFile && value.checkedAt!=null) "Newest file opened with its password. A full restore has not been tested here." else "Check the newest file with its backup password before relying on it for recovery.",style=MaterialTheme.typography.bodySmall)
            NeutralButton("Save another backup now",{perform {"Encrypted backup saved: ${coordinator.runNow(tenant.id).fileName}."}},enabled=!working && value.runningForSession)
            NeutralButton("Check latest encrypted file",{perform {
                try {coordinator.checkLatest(tenant.id,password.toCharArray());"Password and encryption check passed. No records were imported; full restore remains untested."}
                finally {password="";repeat=""}
            }},enabled=!working && value.latestFile!=null && password.isNotEmpty())
            NeutralButton("Pause automatic backups",{perform {coordinator.pause(tenant.id);"Automatic backups paused. Existing copies were kept."}},enabled=!working && value.policy!=null)
            value.retentionWarning?.let {InlineNotice("Some older copies were kept",it,NoticeTone.Warning)}
            value.error?.takeIf {it!=error}?.let {InlineNotice("Automatic backup needs attention",it,NoticeTone.Warning)}
        }
        Text("Use Restore an encrypted backup to restore into a new workspace. Staff credentials, unfinished baskets and device settings are not included. Replacement-computer recovery has not been verified.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Automatic backup not completed",it,NoticeTone.Danger)}
        message?.let {InlineNotice("Backup status",it,NoticeTone.Info)}
    }
}

private fun backupTime(value:Instant):String=DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm 'WAT'").withZone(ZoneId.of("Africa/Lagos")).format(value)
