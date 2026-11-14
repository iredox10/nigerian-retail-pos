package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

/** Contextual owner/manager settings, following the existing light back-office form/reference lock. */
@Composable
fun ShopSettingsScreen(repo:PosRepository,tenant:Tenant,onChanged:()->Unit) {
    var revision by remember(tenant.id) {mutableIntStateOf(0)}
    var showHistory by remember(tenant.id) {mutableStateOf(false)}
    var message by remember(tenant.id) {mutableStateOf<String?>(null)}
    val loaded=remember(tenant.id,revision) {runCatching {repo.shopProfile(tenant.id) to repo.shopProfileHistory(tenant.id)}}
    val profile=loaded.getOrNull()?.first
    val details=profile?.details
    var name by remember(tenant.id,profile?.version) {mutableStateOf(details?.displayName ?: tenant.name)}
    var phone by remember(tenant.id,profile?.version) {mutableStateOf(details?.phone.orEmpty())}
    var address by remember(tenant.id,profile?.version) {mutableStateOf(details?.address.orEmpty())}
    var footer by remember(tenant.id,profile?.version) {mutableStateOf(details?.footer.orEmpty())}
    var paper by remember(tenant.id,profile?.version) {mutableStateOf(details?.receiptPaper ?: "A4")}
    var reason by remember(tenant.id,profile?.version) {mutableStateOf(if(profile==null) "Initial receipt setup" else "")}
    var error by remember(tenant.id) {mutableStateOf<String?>(null)}
    var saving by remember {mutableStateOf(false)}
    val operation=remember(tenant.id,name,phone,address,footer,paper,reason,profile?.version) {UUID.randomUUID().toString()}
    fun edited():ShopDetails = ShopDetails(name.trim(),phone.trim().takeIf {it.isNotEmpty()}?.let {requireNotNull(normalizeNigerianPhone(it)) {"Enter a valid Nigerian phone number or leave it blank."}},address.trim().ifEmpty {null},footer.trim().ifEmpty {null},paper)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        SectionHeader("Shop & receipt details","Saved on this computer · New receipts keep the details used when the sale is saved")
        loaded.exceptionOrNull()?.let {
            InlineNotice("Settings could not be loaded",it.message ?: "Try again.",NoticeTone.Danger)
            NeutralButton("Try again",{revision++})
        }
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        if(loaded.isSuccess) {
            LocalImageEditor(repo,tenant.id,"shop","shop","Shop logo","Appears in the workspace and on new PDF receipts. Completed receipts keep their saved logo.",onChanged={onChanged()})
            Surface(shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    SectionHeader("What should appear on your receipts?","The sign-in workspace remains ${tenant.name}. Completed receipts keep their captured details.")
                    PosTextField(name,{name=it;error=null},"Shop name on receipts",modifier=Modifier.fillMaxWidth())
                    PosTextField(phone,{phone=it;error=null},"Shop phone (optional)",modifier=Modifier.fillMaxWidth(),hint="08012345678 or +234…")
                    PosTextField(address,{address=it;error=null},"Shop address (optional)",modifier=Modifier.fillMaxWidth(),hint="Market, shop number, landmark and town")
                    PosTextField(footer,{footer=it;error=null},"Message at the end of receipts (optional)",modifier=Modifier.fillMaxWidth(),hint="For example, thank you for shopping with us")
                    Text("PDF receipt paper",style=MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("A4","58mm","80mm").forEach {option ->
                            FilterChip(paper==option,{paper=option;error=null},label={Text(if(option=="A4") "A4" else option.replace("mm"," mm"))})
                        }
                    }
                    Text("Narrow PDF pages are 200 mm long and continue on another page when needed. Printer compatibility and physical thermal output must be checked on your printer.",style=MaterialTheme.typography.bodySmall)
                    PosTextField(reason,{reason=it;error=null},"Why are these details changing?",modifier=Modifier.fillMaxWidth())
                    error?.let {InlineNotice("Settings not saved",it,NoticeTone.Danger)}
                    PosButton(if(saving) "Saving…" else "Save receipt details",{
                        saving=true
                        try {
                            repo.saveShopProfile(tenant.id,edited(),profile?.version ?: 0,reason.trim(),operation)
                            error=null;message="Receipt details saved. New sales will keep these details.";revision++;onChanged()
                        } catch(e:Exception) {error=e.message ?: "Your entries have been kept. Try again."}
                        finally {saving=false}
                    },enabled=!saving)
                }
            }
            Surface(shape=MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("Receipt heading preview",style=MaterialTheme.typography.titleMedium)
                    Text("These are the details currently in the form. Save them to use them on new receipts.",style=MaterialTheme.typography.bodySmall)
                    val preview=runCatching {edited()}
                    preview.getOrNull()?.let {value ->
                        Text(value.displayName,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                        value.phone?.let {Text("Phone: $it")}
                        value.address?.let {Text(it)}
                        HorizontalDivider()
                        Text("SALES RECEIPT",fontWeight=FontWeight.Medium)
                        Text("Your saved sale's items, amounts and payment status appear here.",style=MaterialTheme.typography.bodySmall)
                        value.footer?.let {Text(it)}
                    }
                    preview.exceptionOrNull()?.let {Text(it.message ?: "Check your receipt details.",color=MaterialTheme.colorScheme.error)}
                }
            }
            NeutralButton(if(showHistory) "Hide settings history" else "View settings history",{showHistory=!showHistory})
            if(showHistory) {
                val history=loaded.getOrThrow().second.asReversed()
                if(history.isEmpty()) EmptyState("No receipt settings saved yet","Save your shop details to start the settings history.")
                history.forEach {event ->
                    Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Receipt details ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleMedium)
                        Text(event.reason)
                        event.previous?.let {Text("Before: ${shopDetailsSummary(it)}",style=MaterialTheme.typography.bodySmall)}
                        Text("Saved: ${shopDetailsSummary(event.current)}",style=MaterialTheme.typography.bodySmall)
                    }}
                }
            }
        }
    }
}

private fun shopDetailsSummary(value:ShopDetails)=listOf(value.displayName,value.phone ?: "No phone",value.address ?: "No address",value.footer ?: "No footer",value.receiptPaper).joinToString(" · ")
