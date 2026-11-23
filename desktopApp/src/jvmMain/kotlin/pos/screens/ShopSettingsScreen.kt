package pos.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.PdfReceiptService
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Receipt edits are reviewed as a draft and captured on new sales after saving. */
@Composable
fun ShopSettingsScreen(repo: PosRepository, tenant: Tenant, onChanged: () -> Unit) {
    var revision by remember(tenant.id) { mutableIntStateOf(0) }
    var logoRevision by remember(tenant.id) { mutableIntStateOf(0) }
    var showHistory by remember(tenant.id) { mutableStateOf(false) }
    var designTab by remember(tenant.id) { mutableStateOf(false) }
    var message by remember(tenant.id) { mutableStateOf<String?>(null) }
    val loaded = remember(tenant.id, revision) { runCatching { repo.shopProfile(tenant.id) to repo.shopProfileHistory(tenant.id) } }
    val profile = loaded.getOrNull()?.first
    val details = profile?.details
    var name by remember(tenant.id, profile?.version) { mutableStateOf(details?.displayName ?: tenant.name) }
    var phone by remember(tenant.id, profile?.version) { mutableStateOf(details?.phone.orEmpty()) }
    var address by remember(tenant.id, profile?.version) { mutableStateOf(details?.address.orEmpty()) }
    var footer by remember(tenant.id, profile?.version) { mutableStateOf(details?.footer.orEmpty()) }
    var paper by remember(tenant.id, profile?.version) { mutableStateOf(details?.receiptPaper ?: "A4") }
    var appearance by remember(tenant.id, profile?.version) { mutableStateOf(ReceiptAppearanceDraft(details?.appearance ?: ReceiptAppearance())) }
    var artworkUpload by remember(tenant.id, profile?.version) { mutableStateOf<ImageUpload?>(null) }
    var artworkName by remember(tenant.id, profile?.version) { mutableStateOf<String?>(null) }
    var reason by remember(tenant.id, profile?.version) { mutableStateOf(if (profile == null) "Initial receipt setup" else "") }
    var error by remember(tenant.id) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val operation = remember(tenant.id, name, phone, address, footer, paper, appearance, artworkUpload, reason, profile?.version) { UUID.randomUUID().toString() }
    fun edited() = ShopDetails(name.trim(), phone.trim().takeIf { it.isNotEmpty() }?.let {
        requireNotNull(normalizeNigerianPhone(it)) { "Enter a valid Nigerian phone number or leave it blank." }
    }, address.trim().ifEmpty { null }, footer.trim().ifEmpty { null }, paper, appearance.appearance())
    val logoResult = remember(tenant.id, logoRevision) { runCatching {
        repo.imageSelection(tenant.id, "shop", "shop").assetId?.let { repo.imageBytes(tenant.id, it) }
    } }
    val artworkResult = remember(tenant.id, appearance.artworkAssetId, artworkUpload) { runCatching {
        artworkUpload?.bytes ?: appearance.artworkAssetId?.let { repo.imageBytes(tenant.id, it) }
    } }
    val draft = remember(name, phone, address, footer, paper, appearance) { runCatching { edited() } }
    val preview by produceState<Result<ByteArray>?>(null, draft, artworkResult, logoResult) {
        value = null
        delay(180)
        value = withContext(Dispatchers.Default) {
            runCatching {
                val details = draft.getOrThrow()
                PdfReceiptService.createPdf(sampleReceipt(details), details.receiptPaper, logoResult.getOrThrow(),
                    details.appearance, artworkResult.getOrThrow())
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SectionHeader("Shop & receipt details", "Design new receipts and review them before saving")
        loaded.exceptionOrNull()?.let {
            InlineNotice("Settings could not be loaded", it.message ?: "Try again.", NoticeTone.Danger)
            NeutralButton("Try again", { revision++ })
        }
        message?.let { InlineNotice(it, tone = NoticeTone.Success) }
        if (loaded.isSuccess) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!designTab, { designTab = false }, label = { Text("Shop details") })
                FilterChip(designTab, { designTab = true }, label = { Text("Receipt design") })
            }
            if (!designTab) {
                LocalImageEditor(repo, tenant.id, "shop", "shop", "Shop logo",
                    "Appears in the workspace and on new PDF receipts. Completed receipts keep their saved logo.",
                    onChanged = { logoRevision++; onChanged() })
                Surface(shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Shop details on receipts", style = MaterialTheme.typography.titleMedium)
                        PosTextField(name, { name = it; error = null }, "Shop name on receipts", Modifier.fillMaxWidth())
                        PosTextField(phone, { phone = it; error = null }, "Shop phone (optional)", Modifier.fillMaxWidth(), hint = "08012345678 or +234…")
                        PosTextField(address, { address = it; error = null }, "Shop address (optional)", Modifier.fillMaxWidth(), hint = "Market, shop number, landmark and town")
                        PosTextField(footer, { footer = it; error = null }, "Message at the end of receipts (optional)", Modifier.fillMaxWidth(), hint = "Thank you for shopping with us")
                        Text("The sign-in workspace remains ${tenant.name}.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 960.dp
                @Composable fun settings(modifier: Modifier) {
                    Surface(modifier, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (designTab) ReceiptAppearanceEditor(appearance, { appearance = it; error = null },
                                artworkResult.getOrNull(), artworkName,
                                chooseArtwork = {
                                    try {
                                        choosePhotoFile("Choose receipt artwork")?.let {
                                            artworkName = it.first; artworkUpload = it.second; error = null
                                        }
                                    } catch (e: Exception) { error = e.message ?: "The receipt image could not be opened." }
                                },
                                removeArtwork = { artworkUpload = null; artworkName = null; appearance = appearance.copy(artworkAssetId = null); error = null })
                            Text("PDF receipt paper", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("A4", "58mm", "80mm").forEach { option ->
                                    FilterChip(paper == option, { paper = option; error = null }, label = { Text(if (option == "A4") "A4" else option.replace("mm", " mm")) })
                                }
                            }
                            Text("Thermal printers may print in black and white. Check the selected paper size and design on your printer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                @Composable fun receiptPreview(modifier: Modifier) {
                    Surface(modifier, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Receipt preview", style = MaterialTheme.typography.titleMedium)
                            Text("Sample sale · This preview uses the PDF layout. Save below to use this design on new sales.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            preview?.getOrNull()?.let { ReceiptPdfPreview(it, Modifier.fillMaxWidth()) }
                            preview?.exceptionOrNull()?.let { InlineNotice("Preview unavailable", it.message ?: "Check the receipt details.", NoticeTone.Warning) }
                        }
                    }
                }
                if (wide && designTab) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    settings(Modifier.weight(1f))
                    receiptPreview(Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    settings(Modifier.fillMaxWidth())
                    receiptPreview(Modifier.fillMaxWidth())
                }
            }
            Surface(shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Save receipt settings", style = MaterialTheme.typography.titleMedium)
                    Text("Completed receipts keep their saved details, design and images.", style = MaterialTheme.typography.bodySmall)
                    PosTextField(reason, { reason = it; error = null }, "Why are these details changing?", Modifier.fillMaxWidth())
                    error?.let { InlineNotice("Settings not saved", it, NoticeTone.Danger) }
                    PosButton(if (saving) "Saving…" else "Save receipt details", {
                        saving = true
                        try {
                            val value = edited()
                            logoResult.getOrThrow(); artworkResult.getOrThrow()
                            val assetId = artworkUpload?.let { repo.saveReceiptArtwork(tenant.id, it) } ?: value.appearance.artworkAssetId
                            repo.saveShopProfile(tenant.id, value.copy(appearance = value.appearance.copy(artworkAssetId = assetId)),
                                profile?.version ?: 0, reason.trim(), operation)
                            error = null; message = "Receipt details and design saved for new sales."; revision++; onChanged()
                        } catch (e: Exception) { error = e.message ?: "Your entries have been kept. Try again." }
                        finally { saving = false }
                    }, enabled = !saving && draft.isSuccess && preview?.isSuccess == true)
                }
            }
            NeutralButton(if (showHistory) "Hide settings history" else "View settings history", { showHistory = !showHistory })
            if (showHistory) {
                val history = loaded.getOrThrow().second.asReversed()
                if (history.isEmpty()) EmptyState("No receipt settings saved yet", "Save your shop details to start the settings history.")
                history.forEach { event ->
                    Surface(shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Receipt details ${event.version} · ${receiptDate(event.createdAt)}", style = MaterialTheme.typography.titleMedium)
                            Text(event.reason)
                            event.previous?.let { Text("Before · ${shopDetailsSummary(it)}", style = MaterialTheme.typography.bodySmall) }
                            Text("Saved · ${shopDetailsSummary(event.current)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun sampleReceipt(details: ShopDetails) = buildString {
    appendLine(details.displayName)
    details.phone?.let { appendLine("Phone: $it") }
    details.address?.let { appendLine(it) }
    appendLine("SALES RECEIPT")
    appendLine("Receipt: SAMPLE-PREVIEW")
    appendLine("Sample sale for design preview")
    appendLine()
    appendLine("Laundry soap · 2 pieces")
    appendLine("2 x ₦450.00 = ₦900.00")
    appendLine("Malt drink · 1 bottle")
    appendLine("1 x ₦500.00 = ₦500.00")
    appendLine()
    appendLine("Original sale total: ₦1,400.00")
    appendLine("Paid in cash: ₦1,400.00")
    appendLine("Cash given: ₦1,500.00")
    appendLine("Change given: ₦100.00")
    details.footer?.let { appendLine(it) }
}

private fun shopDetailsSummary(value: ShopDetails) = listOf(value.displayName, value.phone ?: "No phone", value.address ?: "No address",
    value.footer ?: "No footer", value.receiptPaper, value.appearance.style, "${value.appearance.frame} frame", value.appearance.accentHex,
    if (value.appearance.artworkAssetId != null) "Receipt artwork" else "No receipt artwork").joinToString(" · ")
