package pos.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import org.jetbrains.skia.Image as SkiaImage
import pos.data.*
import pos.output.receiptDate
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** Fixed image bounds preserve the goods/amount layout, including missing or invalid media. */
@Composable
fun LocalImage(bytes:ByteArray?,description:String,modifier:Modifier=Modifier) {
    val bitmap=remember(bytes) {runCatching {bytes?.let {SkiaImage.makeFromEncoded(it).toComposeImageBitmap()}}.getOrNull()}
    Surface(modifier,shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceVariant) {
        if(bitmap!=null) Image(bitmap,description,Modifier.fillMaxSize().padding(4.dp),contentScale=ContentScale.Fit)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            PosIcon(if (description.contains("shop", true)) PosIcons.Store else PosIcons.Package,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Selecting a file is a preview only; saving/removing requires an explicit recorded reason. */
@Composable
fun LocalImageEditor(
    repo:PosRepository,tenantId:String,ownerKind:String,ownerId:String,title:String,
    description:String,enabled:Boolean=true,onChanged:()->Unit={},
) {
    key(tenantId,ownerKind,ownerId) {
        var revision by remember {mutableIntStateOf(0)}
        val loaded=remember(revision) {runCatching {
            val selection=repo.imageSelection(tenantId,ownerKind,ownerId)
            selection to selection.assetId?.let {repo.imageBytes(tenantId,it)}
        }}
        var pending by remember {mutableStateOf<ImageUpload?>(null)}
        var removing by remember {mutableStateOf(false)}
        var filename by remember {mutableStateOf("")}
        var reason by remember {mutableStateOf("")}
        var error by remember {mutableStateOf<String?>(null)}
        var message by remember {mutableStateOf<String?>(null)}
        var showHistory by remember {mutableStateOf(false)}
        var adjusting by remember {mutableStateOf<ImageUpload?>(null)}
        val operationId=remember(pending,removing,reason,revision) {UUID.randomUUID().toString()}
        val selection=loaded.getOrNull()?.first
        val preview=if(removing) null else pending?.bytes ?: loaded.getOrNull()?.second
        val edited=pending!=null || removing
        Surface(shape=MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                SectionHeader(title,description)
                loaded.exceptionOrNull()?.let {InlineNotice("Image could not be loaded",it.message ?: "Try again.",NoticeTone.Danger)}
                LocalImage(preview,if(ownerKind=="shop") "Shop logo preview" else "Product photo preview",Modifier.size(160.dp))
                if(edited) Text(if(removing) "Removal preview · Save to remove the current image." else "Selected: $filename · Save to use this image.",style=MaterialTheme.typography.bodySmall)
                Text("Choose a PNG or JPEG file. A local copy is saved with your shop records and encrypted backups.",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    NeutralButton(if(selection?.assetId==null && pending==null) "Choose image" else "Choose another image",{
                        try {
                            val chosen=choosePhotoFile(if(ownerKind=="shop") "Choose shop logo" else "Choose product photo")
                            if(chosen!=null) {
                                pending=chosen.second;removing=false;filename=chosen.first;error=null;message=null
                            } else {message="Image selection cancelled. Saved image is unchanged.";error=null}
                        } catch(e:Exception) {error=e.message ?: "The image could not be opened. Saved image is unchanged."}
                    },enabled=enabled && loaded.isSuccess)
                    if(preview!=null && !removing) NeutralButton("Crop or rotate",{
                        runCatching {pending ?: ImageUpload.fromStoredBytes(preview)}
                            .onSuccess {adjusting=it;error=null;message=null}
                            .onFailure {error=it.message ?: "The image could not be opened."}
                    },enabled=enabled && loaded.isSuccess)
                    if(selection?.assetId!=null) NeutralButton("Remove image",{pending=null;removing=true;filename="";error=null;message=null},enabled=enabled && !removing)
                    if(edited) NeutralButton("Discard image change",{pending=null;removing=false;filename="";reason="";error=null;message=null})
                }
                if(edited) {
                    PosTextField(reason,{reason=it;error=null},"Why is this image changing?",hint="A reason is kept in image history",modifier=Modifier.widthIn(max=620.dp),enabled=enabled)
                    PosButton(if(removing) "Save image removal" else "Save image",{
                        try {
                            repo.saveImage(tenantId,ownerKind,ownerId,if(removing) null else requireNotNull(pending),requireNotNull(selection).version,reason.trim(),operationId)
                            val removed=removing
                            pending=null;removing=false;filename="";reason="";revision++;error=null
                            message=if(removed) "Image removed. Earlier image history is kept." else "Image saved on this computer."
                            onChanged()
                        } catch(e:Exception) {error=e.message ?: "Image not saved. Your selection is still here."}
                    },enabled=enabled && loaded.isSuccess && reason.trim().isNotEmpty())
                }
                error?.let {InlineNotice("Image not saved",it,NoticeTone.Danger)}
                message?.let {InlineNotice(it,tone=NoticeTone.Info)}
                if(!enabled) Text("Restore this item before changing its photo.",style=MaterialTheme.typography.bodySmall)
                NeutralButton(if(showHistory) "Hide image history" else "View image history",{showHistory=!showHistory})
                if(showHistory) {
                    val history=remember(revision,showHistory) {runCatching {repo.imageHistory(tenantId,ownerKind,ownerId)}}
                    history.exceptionOrNull()?.let {InlineNotice("Image history unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
                    if(history.isSuccess && history.getOrThrow().isEmpty()) Text("No image changes yet.",style=MaterialTheme.typography.bodySmall)
                    history.getOrDefault(emptyList()).asReversed().forEach {event ->
                        HorizontalDivider()
                        Text("Revision ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleSmall)
                        Text(if(event.assetId==null) "Image removed" else "Image saved",style=MaterialTheme.typography.bodySmall)
                        Text(event.reason,style=MaterialTheme.typography.bodySmall)
                    }
                }
                NeutralButton("Reload saved image",{pending=null;removing=false;filename="";reason="";revision++;error=null;message=null})
                if(ownerKind=="product") {
                    HorizontalDivider()
                    ProductPhotoGallery(repo,tenantId,ownerId,enabled)
                }
            }
        }
        adjusting?.let { source ->
            PhotoAdjustDialog(source,{adjusting=null}) {
                pending=it
                if(filename.isBlank()) filename="Adjusted saved image"
                adjusting=null
            }
        }
    }
}

internal fun choosePhotoFile(title: String): Pair<String, ImageUpload>? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileFilter = FileNameExtensionFilter("PNG or JPEG images", "png", "jpg", "jpeg")
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile.name to ImageUpload.fromFile(chooser.selectedFile.toPath())
}
