package pos.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import pos.data.*

/** Re-read only after saved workspace changes; never fetch remote image URLs. */
@Composable
fun SavedImage(repo:PosRepository?,tenantId:String,kind:String,ownerId:String,description:String,revision:Int,modifier:Modifier) {
    val bytes=remember(repo,tenantId,kind,ownerId,revision) {runCatching {
        repo?.imageSelection(tenantId,kind,ownerId)?.assetId?.let {repo.imageBytes(tenantId,it)}
    }.getOrNull()}
    LocalImage(bytes,description,modifier)
}
