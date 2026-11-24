package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.PosRepository
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

private fun Product.catalogueBasis() = listOf(name, unit, priceMinor, barcode, archived, quantityPrecision, metadata())

/** One local transaction covers the item and organization changes; a SKU error rolls both back. */
internal fun PosRepository.saveAndroidCatalogueItem(tenantId:String, original:Product, name:String, priceMinor:Long,
    barcode:String?, metadata:ProductMetadata, operationId:String):Product = featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
    require(original.tenantId==tenantId && operationId.isNotBlank())
    val current=products(tenantId,true).single {it.id==original.id}
    require(!current.archived) {"Restore this item before editing it."}
    val code=barcode?.trim()?.ifEmpty {null}
    val desired=current.copy(name=name.trim(),priceMinor=priceMinor,barcode=code,sku=metadata.sku,category=metadata.category,
        brand=metadata.brand,aliases=metadata.aliases,favourite=metadata.favourite)
    if(current.catalogueBasis()==desired.catalogueBasis()) return@featureWrite current
    require(current.catalogueBasis()==original.catalogueBasis()) {"This item's details changed. Close this form and review the current item again."}
    if(current.name!=desired.name || current.priceMinor!=priceMinor || current.barcode!=code)
        updateProduct(tenantId,current.id,desired.name,current.unit,priceMinor,code,"$operationId:details")
    if(current.metadata()!=metadata) updateProductMetadata(tenantId,current.id,metadata,"$operationId:metadata",current.metadata())
    products(tenantId,true).single {it.id==current.id}
}

internal fun PosRepository.archiveAndroidCatalogueItem(tenantId:String,original:Product,archived:Boolean,operationId:String) =
    featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
        require(original.tenantId==tenantId)
        val current=products(tenantId,true).single {it.id==original.id}
        if(current.archived!=archived) {
            require(current.catalogueBasis()==original.catalogueBasis()) {"This item changed. Review its current details before changing its availability."}
            archiveProduct(tenantId,current.id,archived,operationId)
        }
    }

@Composable
internal fun AndroidCatalogueTools(repo:PosRepository,tenantId:String,productId:String,dismiss:()->Unit,onChanged:()->Unit) = key(tenantId,productId) {
    var revision by remember {mutableIntStateOf(0)}
    var tab by rememberSaveable {mutableStateOf("Details")}
    var editing by remember {mutableStateOf(false)}
    var archiveReview by remember {mutableStateOf(false)}
    var addingPack by remember {mutableStateOf<String?>(null)}
    var packArchive by remember {mutableStateOf<Pair<String,String>?>(null)}
    var notice by remember {mutableStateOf<String?>(null)}
    val loaded=operationsLoad(repo,tenantId,revision) {repo.products(tenantId,true).single {it.id==productId}}
    val product=loaded?.getOrNull()
    val packs=operationsLoad(repo,tenantId,revision) {repo.sellingUnits(tenantId,productId,true) to repo.purchaseUnits(tenantId,productId,true)}
    fun changed(text:String) {revision++;notice=text;onChanged()}
    if(editing && product!=null) {
        EditAndroidCatalogueItem(repo,tenantId,product,{editing=false}) {editing=false;changed("Item details saved with change history")}
    } else if(archiveReview && product!=null) {
        val operation=rememberSaveable {UUID.randomUUID().toString()}
        OperationsDialog(if(product.archived)"Restore item" else "Archive item",{archiveReview=false},if(product.archived)"Restore item" else "Archive item",action={
            withContext(Dispatchers.IO) {repo.archiveAndroidCatalogueItem(tenantId,product,!product.archived,operation)}
            archiveReview=false;changed(if(product.archived)"Item restored" else "Item archived")
        }) {
            Text(product.name,style=MaterialTheme.typography.titleMedium)
            Text(if(product.archived) "This item will appear in the active catalogue again. Previous sales and stock records stay unchanged."
                else "This hides the item from new sales. Saved sales, quantities and financial history stay recorded. Goods reserved for orders must be released first.")
            LabelValueRow("Current stock", "${product.stockLabel} ${product.unit}")
        }
    } else if(addingPack!=null && product!=null) {
        AddAndroidPack(repo,tenantId,product,addingPack=="selling",{addingPack=null}) {addingPack=null;changed("Package definition saved")}
    } else if(packArchive!=null) {
        val selected=requireNotNull(packArchive)
        val selling=packs?.getOrNull()?.first?.singleOrNull {it.id==selected.second}
        val buying=packs?.getOrNull()?.second?.singleOrNull {it.id==selected.second}
        val archived=if(selected.first=="selling") selling?.archived else buying?.archived
        OperationsDialog(if(archived==true)"Restore package" else "Archive package",{packArchive=null},if(archived==true)"Restore package" else "Archive package",action={
            require(archived!=null) {"Reload package history before continuing."}
            withContext(Dispatchers.IO) {repo.featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
                if(selected.first=="selling") {
                    val original=requireNotNull(selling)
                    val current=sellingUnits(tenantId,productId,true).single {it.id==original.id}
                    require(current==original) {"The package changed. Reopen this review."}
                    archiveSellingUnit(tenantId,original.id,!archived)
                } else {
                    val original=requireNotNull(buying)
                    val current=purchaseUnits(tenantId,productId,true).single {it.id==original.id}
                    require(current==original) {"The package changed. Reopen this review."}
                    archivePurchaseUnit(tenantId,original.id,!archived)
                }
            }}
            packArchive=null;changed("Package availability saved with history")
        }) {
            Text(selling?.name ?: buying?.name ?: "Package unavailable",style=MaterialTheme.typography.titleMedium)
            Text("The saved quantity conversion and prices will not be rewritten. Archived packages cannot be selected for a new sale or stock receipt. Historical transactions retain their original package.")
        }
    } else AlertDialog(onDismissRequest=dismiss,title={Text(product?.name ?: "Manage item")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=540.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OperationsLoadState(loaded,"item details",{revision++})
            notice?.let {InlineNotice(it,tone=NoticeTone.Success)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Details","Packages","History").forEach {name ->FilterChip(tab==name,{tab=name},modifier=Modifier.weight(1f).heightIn(min=48.dp),label={Text(if(name=="Packages")"Packs" else name,style=MaterialTheme.typography.labelMedium)})}
            }
            product?.let {item ->when(tab) {
                "Details" -> {
                    LabelValueRow("Availability",if(item.archived)"Archived" else "Active")
                    LabelValueRow("On hand","${item.stockLabel} ${item.unit}")
                    LabelValueRow("Selling price", "${money(item.priceMinor)} / ${item.unit}")
                    item.barcode?.let {Text("Barcode · $it")};item.sku?.let {Text("SKU · $it")}
                    item.category?.let {Text("Category · $it")};item.brand?.let {Text("Brand · $it")}
                    Text(if(item.favourite)"Favourite item" else "Not marked as a favourite")
                    if(item.aliases.isNotEmpty()) Text("Other search names · ${item.aliases.joinToString(", ")}")
                    Text("Stock unit ${item.unit} and quantity precision stay fixed. Use a separate item when the meaning of a quantity changes.",style=MaterialTheme.typography.bodySmall)
                    if(!item.archived) PosButton("Edit item details",{editing=true},modifier=Modifier.fillMaxWidth())
                    OutlinedButton({archiveReview=true},Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(if(item.archived)"Restore item" else "Archive item")}
                }
                "Packages" -> {
                    Text("Every saved package retains its conversion. To change it, archive that definition and create a new package; buying names cannot be reused.")
                    if(!item.archived) {
                        PosButton("Add selling package",{addingPack="selling"},modifier=Modifier.fillMaxWidth())
                        OutlinedButton({addingPack="buying"},Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Add buying package")}
                    } else Text("Restore this item before adding a package.")
                    OperationsLoadState(packs,"packages",{revision++})
                    val values=packs?.getOrNull()
                    if(values!=null && values.first.isEmpty() && values.second.isEmpty()) Text("No package definitions yet. Sell and receive directly in ${item.unit}.")
                    values?.first?.forEach {unit ->
                        HorizontalDivider();Text("Selling · ${unit.name}",style=MaterialTheme.typography.titleMedium)
                        Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}")
                        LabelValueRow("Price",money(unit.priceMinor));unit.barcode?.let {Text("Barcode · $it")}
                        Text(if(unit.archived)"Archived" else "Active")
                        TextButton({packArchive="selling" to unit.id},Modifier.heightIn(min=48.dp)) {Text(if(unit.archived)"Restore selling package" else "Archive selling package")}
                    }
                    values?.second?.forEach {unit ->
                        HorizontalDivider();Text("Buying · ${unit.name}",style=MaterialTheme.typography.titleMedium)
                        Text("1 ${unit.name} = ${unit.baseUnitsPerUnit} ${unit.baseUnit}")
                        Text(if(unit.archived)"Archived" else "Active")
                        TextButton({packArchive="buying" to unit.id},Modifier.heightIn(min=48.dp)) {Text(if(unit.archived)"Restore buying package" else "Archive buying package")}
                    }
                }
                else -> AndroidCatalogueHistory(repo,tenantId,item.id,revision)
            }}
        }
    },confirmButton={TextButton(dismiss){Text("Close")}})
}

@Composable
private fun EditAndroidCatalogueItem(repo:PosRepository,tenantId:String,original:Product,dismiss:()->Unit,saved:()->Unit) {
    var name by rememberSaveable {mutableStateOf(original.name)}
    var price by rememberSaveable {mutableStateOf(catalogueNaira(original.priceMinor))}
    var barcode by rememberSaveable {mutableStateOf(original.barcode.orEmpty())}
    var sku by rememberSaveable {mutableStateOf(original.sku.orEmpty())}
    var category by rememberSaveable {mutableStateOf(original.category.orEmpty())}
    var brand by rememberSaveable {mutableStateOf(original.brand.orEmpty())}
    var aliases by rememberSaveable {mutableStateOf(original.aliases.joinToString("\n"))}
    var favourite by rememberSaveable {mutableStateOf(original.favourite)}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    fun metadata()=ProductMetadata(sku.trim().ifBlank {null},category.trim().ifBlank {null},brand.trim().ifBlank {null},
        aliases.lines().map(String::trim).filter(String::isNotEmpty),favourite)
    fun change(){reviewed=false}
    OperationsDialog("Edit item details",dismiss,if(reviewed)"Save item details" else "Review item details",action={
        val amount=requireNotNull(parseNaira(price)) {"Enter an exact non-negative selling price."}
        val details=metadata();require(name.isNotBlank()) {"Enter an item name."}
        if(!reviewed) reviewed=true else {
            withContext(Dispatchers.IO) {repo.saveAndroidCatalogueItem(tenantId,original,name,amount,barcode,details,operation)}
            saved()
        }
    }) {
        if(!reviewed) {
            PosTextField(name,{name=it;change()},"Item name")
            Text("Stock unit · ${original.unit} · ${quantityPrecisionChoiceLabel(original.quantityPrecision)}")
            PosTextField(price,{price=it;change()},"Selling price per ${original.unit} (₦)")
            PosTextField(barcode,{barcode=it;change()},"Barcode (optional)")
            PosTextField(sku,{sku=it;change()},"SKU (optional)")
            PosTextField(category,{category=it;change()},"Category (optional)")
            PosTextField(brand,{brand=it;change()},"Brand (optional)")
            PosTextField(aliases,{aliases=it;change()},"Other search names, one per line", singleLine=false)
            OperationsCheck(favourite,{favourite=it;change()},"Mark this item as a favourite")
        } else {
            Text(name.trim(),style=MaterialTheme.typography.titleMedium)
            LabelValueRow("Previous price",money(original.priceMinor));LabelValueRow("New price",money(requireNotNull(parseNaira(price))),emphasized=true)
            Text("Barcode · ${barcode.trim().ifBlank {"None"}}")
            Text("SKU · ${sku.trim().ifBlank {"None"}}")
            Text("Category · ${category.trim().ifBlank {"None"}}")
            Text("Brand · ${brand.trim().ifBlank {"None"}}")
            Text("Search names · ${metadata().aliases.joinToString(", ").ifEmpty {"None"}}")
            Text(if(favourite)"Favourite" else "Not a favourite")
            Text("Saved sales retain their original names, quantities and prices. This does not change stock or cash.")
            TextButton({reviewed=false},Modifier.heightIn(min=48.dp)){Text("Edit again")}
        }
    }
}

@Composable
private fun AddAndroidPack(repo:PosRepository,tenantId:String,original:Product,selling:Boolean,dismiss:()->Unit,saved:()->Unit) {
    var name by rememberSaveable {mutableStateOf("")};var factor by rememberSaveable {mutableStateOf("")}
    var price by rememberSaveable {mutableStateOf("")};var barcode by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    OperationsDialog(if(selling)"Add selling package" else "Add buying package",dismiss,if(reviewed)"Save package" else "Review package",action={
        val count=requireNotNull(factor.toIntOrNull()?.takeIf {it>0}) {"Enter a positive whole number of ${original.unit}."}
        val amount=if(selling)requireNotNull(parseNaira(price)) {"Enter an exact selling price for this package."} else 0L
        require(name.isNotBlank()) {"Enter the package name."}
        if(!reviewed) reviewed=true else {
            withContext(Dispatchers.IO) {repo.featureWrite(tenantId,StaffPermission.MANAGE_STOCK) {
                val item=products(tenantId,true).single {it.id==original.id}
                require(!item.archived && item.unit==original.unit && item.quantityPrecision==original.quantityPrecision && item.name==original.name) {"The item changed. Reopen its package form."}
                if(selling) addSellingUnit(tenantId,item.id,name.trim(),count,amount,barcode.trim().ifBlank {null},operation)
                else addPurchaseUnit(tenantId,item.id,name.trim(),count,operation)
            }}
            saved()
        }
    }) {
        if(!reviewed) {
            PosTextField(name,{name=it;reviewed=false},"Package name",hint="carton, pack, dozen…")
            PosTextField(factor,{factor=it;reviewed=false},"Number of ${original.unit} in one package",hint="Whole base units; for example, 24")
            if(selling) {
                PosTextField(price,{price=it;reviewed=false},"Selling price for one package (₦)")
                PosTextField(barcode,{barcode=it;reviewed=false},"Package barcode (optional)")
            }
        } else {
            LabelValueRow("Package",name.trim())
            Text("1 ${name.trim()} = ${factor.toInt()} ${original.unit}",style=MaterialTheme.typography.titleMedium)
            if(selling)LabelValueRow("Price per package",money(requireNotNull(parseNaira(price))),emphasized=true)
            Text("This definition does not add stock. Receiving or selling the package uses this exact conversion.")
            TextButton({reviewed=false},Modifier.heightIn(min=48.dp)){Text("Edit again")}
        }
        Text("Quantity conversions stay fixed in history. This editor supports whole multiples of the base unit; it does not infer container sizes or nested packages.",style=MaterialTheme.typography.bodySmall)
    }
}

private fun catalogueNaira(minor:Long)="${minor/100}.${(minor%100).toString().padStart(2,'0')}"

@Composable
private fun AndroidCatalogueHistory(repo:PosRepository,tenantId:String,productId:String,revision:Int) {
    val history=operationsLoad(repo,tenantId,revision) {repo.featureRead(tenantId,StaffPermission.MANAGE_STOCK) {
        val basic=featureQuery("SELECT name,unit,price_minor,barcode,archived,kind,created_at FROM catalogue_changes WHERE tenant_id=? AND product_id=? ORDER BY created_at,id",tenantId,productId) {
            listOf(it.getString("created_at"),it.getString("kind"),it.getString("name"),it.getString("unit"),it.getLong("price_minor").toString(),it.getString("barcode").orEmpty(),it.getInt("archived").toString())
        }
        basic to productMetadataChanges(tenantId,productId)
    }}
    OperationsLoadState(history,"item history",null)
    history?.getOrNull()?.let { (basic,metadata) ->
        if(basic.isEmpty() && metadata.isEmpty()) Text("No item edits recorded yet. Opening stock and movements are retained separately.")
        basic.asReversed().forEach {event ->
            HorizontalDivider();Text(if(event[1]=="archive")if(event[6]=="1")"Item archived" else "Item restored" else "Item details edited",style=MaterialTheme.typography.titleSmall)
            Text(event[0].take(19).replace('T',' ')+" UTC",style=MaterialTheme.typography.bodySmall)
            Text("${event[2]} · ${event[3]}");LabelValueRow("Price at this change",money(event[4].toLong()))
            if(event[5].isNotBlank())Text("Barcode · ${event[5]}")
        }
        metadata.asReversed().forEach {event ->
            HorizontalDivider();Text("Organization revision ${event.sequence}",style=MaterialTheme.typography.titleSmall)
            Text(event.createdAt.take(19).replace('T',' ')+" UTC",style=MaterialTheme.typography.bodySmall)
            Text("Category · ${event.current.category ?: "None"}");Text("Brand · ${event.current.brand ?: "None"}")
            Text("SKU · ${event.current.sku ?: "None"}");Text(if(event.current.favourite)"Favourite" else "Not a favourite")
            if(event.current.aliases.isNotEmpty())Text("Search names · ${event.current.aliases.joinToString(", ")}")
        }
    }
}
