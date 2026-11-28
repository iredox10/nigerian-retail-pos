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
import kotlinx.coroutines.*
import pos.android.documents.saveReviewedAndroidReceiptDesign
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.io.ByteArrayOutputStream
import java.util.UUID

private data class AndroidShopDesignData(val profile: ShopProfile?, val workspace: String, val history: List<ShopProfileChange>, val logo: LocalImageSelection, val logoBytes: ByteArray?, val artwork: ByteArray?)

@Composable
fun AndroidShopSettingsScreen(repo: PosRepository, tenantId: String, onChanged: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val loaded = operationsLoad(repo, tenantId, revision) {
        repo.featureRead(tenantId, StaffPermission.MANAGE_SETTINGS) {
            val profile = shopProfile(tenantId)
            val logo = imageSelection(tenantId, "shop", "shop")
            AndroidShopDesignData(profile, tenants().single { it.id == tenantId }.name, shopProfileHistory(tenantId), logo,
                logo.assetId?.let { imageBytes(tenantId, it) }, profile?.details?.appearance?.artworkAssetId?.let { imageBytes(tenantId, it) })
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Shop & receipt details", "Design new receipts and review them before saving")
        OperationsLoadState(loaded, "receipt settings", { revision++ })
        notice?.let { InlineNotice(it, tone = NoticeTone.Success) }
        loaded?.getOrNull()?.let { data -> key(revision) {
            AndroidShopDesignEditor(repo, tenantId, data, onChanged,
                saved = { revision++; notice = "Receipt details and design saved for new sales."; onChanged() },
                reload = { revision++; notice = null })
        } }
    }
}

@Composable
private fun AndroidShopDesignEditor(repo: PosRepository, tenantId: String, data: AndroidShopDesignData, onChanged: () -> Unit, saved: () -> Unit, reload: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember { data.profile }
    val initial = original?.details
    var tab by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(initial?.displayName ?: data.workspace) }
    var phone by remember { mutableStateOf(initial?.phone.orEmpty()) }
    var address by remember { mutableStateOf(initial?.address.orEmpty()) }
    var footer by remember { mutableStateOf(initial?.footer.orEmpty()) }
    var paper by remember { mutableStateOf(initial?.receiptPaper ?: "A4") }
    var style by remember { mutableStateOf(initial?.appearance?.style ?: "modern") }
    var frame by remember { mutableStateOf(initial?.appearance?.frame ?: "none") }
    var accent by remember { mutableStateOf(initial?.appearance?.accentHex ?: "#087F73") }
    var alignment by remember { mutableStateOf(initial?.appearance?.headerAlignment ?: "left") }
    var showLogo by remember { mutableStateOf(initial?.appearance?.showLogo ?: true) }
    var tagline by remember { mutableStateOf(initial?.appearance?.tagline.orEmpty()) }
    var policy by remember { mutableStateOf(initial?.appearance?.policy.orEmpty()) }
    var artworkId by remember { mutableStateOf(initial?.appearance?.artworkAssetId) }
    var artworkUpload by remember { mutableStateOf<ImageUpload?>(null) }
    var savedLogo by remember { mutableStateOf(data.logo) }
    var savedLogoBytes by remember { mutableStateOf(data.logoBytes) }
    var logoUpload by remember { mutableStateOf<ImageUpload?>(null) }
    var removeLogo by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf(if (original == null) "Initial receipt setup" else "") }
    var reviewed by remember { mutableStateOf(false) }
    var logoReviewed by remember { mutableStateOf(false) }
    var previewedDetails by remember { mutableStateOf<ShopDetails?>(null) }
    var previewedLogo by remember { mutableStateOf<ByteArray?>(null) }
    var previewedArtwork by remember { mutableStateOf<ByteArray?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var historyLimit by remember { mutableIntStateOf(20) }
    var busy by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun edited() { reviewed = false; logoReviewed = false; error = null }
    fun reasonValue(): String = reason.trim().also { require(it.length in 3..250 && it.none { ch -> ch.isISOControl() }) { "Enter a reason from 3 to 250 printable characters." } }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { action() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Settings were not saved. Your entries are kept." }
            finally { busy = false }
        }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pickerTarget; pickerTarget = null
        if (uri != null) work {
            val upload = withContext(Dispatchers.IO) {
                repo.featureRead(tenantId, StaffPermission.MANAGE_SETTINGS) { Unit }
                requireNotNull(context.contentResolver.openInputStream(uri)) { "This image cannot be read." }.use { input ->
                    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; require(n > 0 && output.size() + n <= 8_388_608) { "Choose a readable PNG or JPEG up to 8 MB." }; output.write(buffer, 0, n) }
                    ImageUpload.fromBytes(output.toByteArray())
                }
            }
            if (target == "logo") { logoUpload = upload; removeLogo = false } else { require(target == "artwork"); artworkUpload = upload }
            edited()
        }
    }
    val draft = remember(name, phone, address, footer, paper, style, frame, accent, alignment, showLogo, artworkId, tagline, policy) {
        runCatching { ShopDetails(name.trim(), phone.trim().ifBlank { null }?.let(::normalizeNigerianPhone), address.trim().ifBlank { null }, footer.trim().ifBlank { null }, paper,
            ReceiptAppearance(style, frame, accent.trim(), alignment, showLogo, artworkId, tagline.trim().ifBlank { null }, policy.trim().ifBlank { null })) }
    }
    val operation = remember(draft.getOrNull(), artworkUpload, reason) { UUID.randomUUID().toString() }
    val logoOperation = remember(logoUpload, removeLogo, savedLogo.version, reason) { UUID.randomUUID().toString() }
    val artworkBytes = remember(artworkUpload, artworkId, data.artwork) { artworkUpload?.bytes ?: data.artwork.takeIf { artworkId != null } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!tab, { tab = false }, label = { Text("Shop details") })
        FilterChip(tab, { tab = true }, label = { Text("Receipt design") })
    }
    Text("Completed receipts retain their saved shop details, design and images.", style = MaterialTheme.typography.bodySmall)
    if (data.profile?.version != original?.version) InlineNotice("Saved settings changed", "Your entries are kept. Reload the latest settings before saving.", NoticeTone.Warning)
    error?.let { InlineNotice("Settings need checking", it, NoticeTone.Danger) }
    message?.let { InlineNotice(it, tone = NoticeTone.Success) }
    if (!tab) {
        PosTextField(name, { name = it; edited() }, "Shop name on receipts")
        PosTextField(phone, { phone = it; edited() }, "Shop phone (optional)")
        PosTextField(address, { address = it; edited() }, "Shop address (optional)")
        PosTextField(footer, { footer = it; edited() }, "Message at the end of receipts (optional)")
        Text("Shop logo", style = MaterialTheme.typography.titleMedium)
        val logoBytes = remember(logoUpload, savedLogoBytes, removeLogo) { if (removeLogo) null else logoUpload?.bytes ?: savedLogoBytes }
        logoBytes?.let { bytes ->
            val bitmap = remember(bytes) { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            bitmap?.let { Image(it.asImageBitmap(), "Selected shop logo", Modifier.size(96.dp)) }
        }
        OutlinedButton(enabled = !busy && pickerTarget == null, onClick = { pickerTarget = "logo"; runCatching { choose.launch(arrayOf("image/png", "image/jpeg")) }.onFailure { pickerTarget = null; error = it.message } }) { Text("Choose shop logo") }
        if (savedLogo.assetId != null || logoUpload != null) TextButton(enabled = !busy, onClick = { logoUpload = null; removeLogo = true; logoReviewed = false }) { Text("Remove shop logo") }
        if (logoUpload != null || (removeLogo && savedLogo.assetId != null)) {
            Text("Save this logo separately to use it in the design preview and on new receipts.")
            if (logoReviewed) Text(if (removeLogo) "Remove the current logo; previous receipt images stay in history." else "Use the selected logo for new receipts; previous receipt images stay in history.")
            Button(enabled = !busy && pickerTarget == null, onClick = { work {
                val why = reasonValue(); val upload = if (removeLogo) null else logoUpload
                val expected = savedLogo.version; val requestId = logoOperation
                if (!logoReviewed) logoReviewed = true else {
                    val result = withContext(Dispatchers.IO) {
                        val selection = repo.saveImage(tenantId, "shop", "shop", upload, expected, why, requestId)
                        selection to selection.assetId?.let { repo.imageBytes(tenantId, it) }
                    }
                    savedLogo = result.first; savedLogoBytes = result.second; logoUpload = null; removeLogo = false
                    edited(); message = "Shop logo saved. Receipt detail entries are kept."; onChanged()
                }
            } }) { Text(if (logoReviewed) "Save shop logo" else "Review logo change") }
        }
    } else {
        Text("Choose a look", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("classic" to "Classic", "modern" to "Modern", "compact" to "Compact").forEach { (value, label) -> FilterChip(style == value, { style = value; edited() }, label = { Text(label) }) } }
        Text("Frame", style = MaterialTheme.typography.titleMedium)
        Column { listOf("none" to "No frame", "line" to "Single line", "double" to "Double line").forEach { (value, label) -> FilterChip(frame == value, { frame = value; edited() }, label = { Text(label) }) } }
        Text("Accent colour", style = MaterialTheme.typography.titleMedium)
        listOf("Teal" to "#087F73", "Ink" to "#182230", "Blue" to "#1D4ED8", "Copper" to "#9A3412").chunked(2).forEach { colours -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            colours.forEach { (label, value) -> FilterChip(accent.equals(value, true), { accent = value; edited() }, label = { Text(label) }) }
        } }
        PosTextField(accent, { accent = it; edited() }, "Custom colour (#RRGGBB)")
        Text("Heading alignment", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("left" to "Left", "center" to "Centre").forEach { (value, label) -> FilterChip(alignment == value, { alignment = value; edited() }, label = { Text(label) }) } }
        OperationsCheck(showLogo, { showLogo = it; edited() }, "Show the saved shop logo on receipts")
        PosTextField(tagline, { tagline = it; edited() }, "Tagline (optional)")
        PosTextField(policy, { policy = it; edited() }, "Returns policy (optional)")
        Text("Receipt image", style = MaterialTheme.typography.titleMedium)
        Text("Add a banner, stamp or other artwork. Keep important receipt details in text.")
        OutlinedButton(enabled = !busy && pickerTarget == null, onClick = { pickerTarget = "artwork"; runCatching { choose.launch(arrayOf("image/png", "image/jpeg")) }.onFailure { pickerTarget = null; error = it.message } }) { Text(if (artworkBytes == null) "Choose receipt image" else "Replace receipt image") }
        if (artworkBytes != null) TextButton(enabled = !busy, onClick = { artworkId = null; artworkUpload = null; edited() }) { Text("Remove receipt image") }
    }
    Text("PDF receipt paper", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("A4", "58mm", "80mm").forEach { value -> FilterChip(paper == value, { paper = value; edited() }, label = { Text(value) }) } }
    Text("Check the selected paper and colour output on your printer.", style = MaterialTheme.typography.bodySmall)
    draft.exceptionOrNull()?.let { InlineNotice("Review receipt details", it.message ?: "Check the receipt fields.", NoticeTone.Warning) }
    draft.getOrNull()?.let { details -> AndroidReceiptDesignPreview(details, savedLogoBytes, artworkBytes) { renderedDetails, renderedLogo, renderedArtwork, ready ->
        previewedDetails = renderedDetails.takeIf { ready }; previewedLogo = renderedLogo; previewedArtwork = renderedArtwork
    } }
    PosTextField(reason, { reason = it; reviewed = false; logoReviewed = false }, "Why are these receipt details or images changing?")
    if (reviewed) InlineNotice("Ready to save for new receipts", "${name.trim()} · $paper · $style · $frame frame · $alignment headings. Completed receipts retain their original captured details.", NoticeTone.Info)
    Button(enabled = !busy && pickerTarget == null && draft.isSuccess && draft.getOrNull() == previewedDetails && savedLogoBytes === previewedLogo && artworkBytes === previewedArtwork, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = { work {
        val details = draft.getOrThrow(); val why = reasonValue(); val upload = artworkUpload; val requestId = operation
        if (!reviewed) reviewed = true else {
            withContext(Dispatchers.IO) { repo.saveReviewedAndroidReceiptDesign(tenantId, details, upload, original?.version ?: 0, why, requestId) }
            saved()
        }
    } }) { Text(if (busy) "Working…" else if (reviewed) "Save receipt details and design" else "Review receipt details") }
    TextButton(enabled = !busy, onClick = reload) { Text("Reload saved settings · replaces unsaved entries") }
    TextButton(onClick = { showHistory = !showHistory }) { Text(if (showHistory) "Hide settings history" else "View settings history") }
    if (showHistory) {
        if (data.history.isEmpty()) Text("No receipt settings saved yet.")
        data.history.asReversed().take(historyLimit).forEach { event ->
            HorizontalDivider(); Text("Revision ${event.version} · ${event.createdAt.take(19)} UTC", style = MaterialTheme.typography.titleMedium)
            Text(event.reason); Text("${event.current.displayName} · ${event.current.receiptPaper} · ${event.current.appearance.style} · ${event.current.appearance.frame} frame")
            event.previous?.let { Text("Previous shop name: ${it.displayName}") }
        }
        if (data.history.size > historyLimit) TextButton(onClick = { historyLimit += 20 }) { Text("Show earlier settings") }
    }
}
