package pos.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pos.data.*
import pos.domain.*
import pos.output.receiptDate
import pos.ui.components.*
import java.util.UUID

/** Contextual Stock tool: merchant-declared recipes and two explicit physical movements. */
@Composable
fun RepackingScreen(repo:PosRepository,tenantId:String,onChanged:()->Unit) {
    var revision by remember(tenantId) {mutableIntStateOf(0)}
    var editing by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf<RepackingRecipe?>(null)}
    var running by remember {mutableStateOf<RepackingRecipe?>(null)}
    var history by remember {mutableStateOf<RepackingRecipe?>(null)}
    var showRuns by remember {mutableStateOf(false)}
    var search by remember {mutableStateOf("")}
    var message by remember {mutableStateOf<String?>(null)}
    val loaded=remember(tenantId,revision) {runCatching {repo.repackingRecipes(tenantId) to repo.repackingHistory(tenantId)}}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SectionHeader("Repack goods","Move stock between two catalogue items using quantities you have explicitly checked.",Modifier.weight(1f))
            PosButton("New repacking recipe",{selected=null;editing=true})
        }
        InlineNotice("Check the actual goods and quantities", "A recipe records your own input/output quantities. The app does not assume bags, containers or different goods are equivalent. Repacking changes physical stock only; it records no sale, cash, cost or profit.",NoticeTone.Info)
        loaded.exceptionOrNull()?.let {InlineNotice("Repacking unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        message?.let {InlineNotice(it,tone=NoticeTone.Success)}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            FilterChip(!showRuns,{showRuns=false},label={Text("Recipes")})
            FilterChip(showRuns,{showRuns=true},label={Text("Completed repacking")})
            NeutralButton("Refresh",{revision++;message=null})
        }
        PosTextField(search,{search=it},if(showRuns) "Find completed repacking" else "Find a recipe",hint="Name, source goods, packed goods or reason")
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            if(showRuns) {
                val runs=loaded.getOrNull()?.second.orEmpty().filter {listOf(it.details.name,it.details.sourceName,it.details.destinationName,it.reason).any {text ->text.contains(search,true)}}
                if(runs.isEmpty() && loaded.isSuccess) item {EmptyState("No matching repacking", "Completed repacking keeps both original stock movements and its recipe revision.")}
                items(runs,key={it.id}) {run ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(run.details.name,style=MaterialTheme.typography.titleLarge)
                    Text("${run.batches} whole batch(es) · Recipe revision ${run.recipeVersion} · ${receiptDate(run.createdAt)}",style=MaterialTheme.typography.bodySmall)
                    LabelValueRow("Used: ${run.details.sourceName}","${quantityLabel(run.details.sourceQuantity(run.batches),run.details.sourcePrecision)} ${run.details.sourceUnit}",emphasized=true)
                    Text("Stock ${quantityLabel(run.sourceBefore,run.details.sourcePrecision)} → ${quantityLabel(run.sourceAfter,run.details.sourcePrecision)} ${run.details.sourceUnit}",style=MaterialTheme.typography.bodySmall)
                    LabelValueRow("Packed: ${run.details.destinationName}","${quantityLabel(run.details.destinationQuantity(run.batches),run.details.destinationPrecision)} ${run.details.destinationUnit}",emphasized=true)
                    Text("Stock ${quantityLabel(run.destinationBefore,run.details.destinationPrecision)} → ${quantityLabel(run.destinationAfter,run.details.destinationPrecision)} ${run.details.destinationUnit}",style=MaterialTheme.typography.bodySmall)
                    Text("Reason: ${run.reason}");Text("Saved record ${run.id}",style=MaterialTheme.typography.bodySmall)
                }}}
            } else {
                val recipes=loaded.getOrNull()?.first.orEmpty().filter {listOf(it.details.name,it.details.sourceName,it.details.destinationName).any {text ->text.contains(search,true)}}
                if(recipes.isEmpty() && loaded.isSuccess) item {EmptyState("No matching recipes", "Create a recipe after checking the real quantities used and packed in one batch.")}
                items(recipes,key={it.id}) {recipe ->Surface(shape=MaterialTheme.shapes.medium) {Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(recipe.details.name,style=MaterialTheme.typography.titleLarge)
                    Text("Revision ${recipe.version}${if(recipe.details.archived) " · Archived" else ""}",style=MaterialTheme.typography.bodySmall)
                    RepackingRatio(recipe.details)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PosButton("Repack using recipe",{running=recipe},enabled=!recipe.details.archived)
                        NeutralButton("Edit recipe",{selected=recipe;editing=true})
                        NeutralButton("Recipe history",{history=recipe})
                    }
                }}}
            }
        }
    }
    if(editing) RepackingRecipeEditor(repo,tenantId,selected,{editing=false}) {recipe ->editing=false;revision++;message="${recipe.details.name} saved. Earlier repacking keeps its original recipe.";onChanged()}
    running?.let {recipe ->RepackingExecution(repo,tenantId,recipe,{running=null}) {run ->running=null;revision++;showRuns=true;message="${run.details.name} saved. Both stock balances changed together on this computer.";onChanged()}}
    history?.let {recipe ->
        val loadedHistory=remember(recipe.id,revision) {runCatching {repo.repackingRecipeHistory(tenantId,recipe.id)}}
        AlertDialog(onDismissRequest={history=null},title={Text("${recipe.details.name} history")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            loadedHistory.exceptionOrNull()?.let {InlineNotice("History unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
            loadedHistory.getOrDefault(emptyList()).asReversed().forEach {event ->
                Text("Revision ${event.version} · ${receiptDate(event.createdAt)}",style=MaterialTheme.typography.titleMedium)
                Text(event.reason);RepackingRatio(event.current);if(event.current.archived) Text("Archived",style=MaterialTheme.typography.bodySmall);HorizontalDivider()
            }
        }},confirmButton={TextButton({history=null}) {Text("Close")}})
    }
}
@Composable
private fun RepackingRatio(details:RepackingRecipeDetails) {
    Text("One batch uses ${quantityLabel(details.sourcePerBatch,details.sourcePrecision)} ${details.sourceUnit} of ${details.sourceName}")
    Text("and makes ${quantityLabel(details.destinationPerBatch,details.destinationPrecision)} ${details.destinationUnit} of ${details.destinationName}.")
}
@Composable
private fun RepackingRecipeEditor(repo:PosRepository,tenantId:String,old:RepackingRecipe?,dismiss:()->Unit,saved:(RepackingRecipe)->Unit) {
    val loaded=remember(tenantId) {runCatching {repo.products(tenantId,true)}}
    val goods=loaded.getOrDefault(emptyList())
    var name by remember {mutableStateOf(old?.details?.name.orEmpty())}
    var sourceId by remember {mutableStateOf(old?.details?.sourceId)}
    var destinationId by remember {mutableStateOf(old?.details?.destinationId)}
    var input by remember {mutableStateOf(old?.details?.let {quantityLabel(it.sourcePerBatch,it.sourcePrecision)}.orEmpty())}
    var output by remember {mutableStateOf(old?.details?.let {quantityLabel(it.destinationPerBatch,it.destinationPrecision)}.orEmpty())}
    var sourceMenu by remember {mutableStateOf(false)}
    var destinationMenu by remember {mutableStateOf(false)}
    var checked by remember {mutableStateOf(false)}
    var archived by remember {mutableStateOf(old?.details?.archived ?: false)}
    var reason by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    val source=goods.singleOrNull {it.id==sourceId};val destination=goods.singleOrNull {it.id==destinationId}
    val recipeId=remember {old?.id ?: UUID.randomUUID().toString()}
    val operation=remember(name,sourceId,destinationId,input,output,archived,reason) {UUID.randomUUID().toString()}
    AlertDialog(onDismissRequest=dismiss,title={Text(if(old==null) "New repacking recipe" else "Edit repacking recipe")},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("Choose distinct catalogue items and the actual quantities for one whole batch. Do not use this to change a selling pack of the same item.")
        loaded.exceptionOrNull()?.let {InlineNotice("Goods unavailable",it.message ?: "Try again.",NoticeTone.Danger)}
        PosTextField(name,{name=it;checked=false},"Recipe name")
        Box {NeutralButton(source?.let {"Use: ${it.name} (${it.unit})"} ?: "Choose goods used",{sourceMenu=true});DropdownMenu(sourceMenu,{sourceMenu=false},Modifier.heightIn(max=280.dp)) {
            goods.filter {!it.archived && it.id!=destinationId}.forEach {item ->DropdownMenuItem(text={Text("${item.name} · ${item.unit}")},onClick={sourceId=item.id;input="";sourceMenu=false;checked=false})}
        }}
        if(source!=null) PosTextField(input,{input=it;checked=false},"Quantity used per batch (${source.unit})",hint="Up to ${source.quantityPrecision} decimal places")
        Box {NeutralButton(destination?.let {"Pack: ${it.name} (${it.unit})"} ?: "Choose goods made",{destinationMenu=true});DropdownMenu(destinationMenu,{destinationMenu=false},Modifier.heightIn(max=280.dp)) {
            goods.filter {!it.archived && it.id!=sourceId}.forEach {item ->DropdownMenuItem(text={Text("${item.name} · ${item.unit}")},onClick={destinationId=item.id;output="";destinationMenu=false;checked=false})}
        }}
        if(destination!=null) PosTextField(output,{output=it;checked=false},"Quantity made per batch (${destination.unit})",hint="Up to ${destination.quantityPrecision} decimal places")
        Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked,{checked=it});Text("I checked that these goods and quantities describe the same physical repacking batch.",Modifier.weight(1f))}
        if(old!=null) Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(archived,{archived=it});Text("Archive this recipe",Modifier.weight(1f))}
        PosTextField(reason,{reason=it},"Why is this recipe being saved?")
        Text("Saving a recipe changes no stock. Completed repacking keeps the version used at that time.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Recipe not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save checked recipe",{
        try {
            val from=requireNotNull(source) {"Choose the goods being used."};val to=requireNotNull(destination) {"Choose the goods being made."}
            val used=requireNotNull(parseQuantity(input,from.quantityPrecision)) {"Enter the quantity used without rounding."}
            val made=requireNotNull(parseQuantity(output,to.quantityPrecision)) {"Enter the quantity made without rounding."}
            val details=RepackingRecipeDetails(name.trim(),from.id,from.name,from.unit,from.quantityPrecision,used.scaledUnits,to.id,to.name,to.unit,to.quantityPrecision,made.scaledUnits,archived)
            saved(repo.saveRepackingRecipe(tenantId,recipeId,details,old?.version ?: 0,reason.trim(),operation))
        } catch(e:Exception) {error=e.message ?: "Your entries have been kept."}
    },enabled=checked && loaded.isSuccess)},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}
@Composable
private fun RepackingExecution(repo:PosRepository,tenantId:String,recipe:RepackingRecipe,dismiss:()->Unit,saved:(RepackingRun)->Unit) {
    var batches by remember {mutableStateOf("1")}
    var reason by remember {mutableStateOf("")}
    var review by remember {mutableStateOf<RepackingReview?>(null)}
    var checked by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val count=batches.toLongOrNull()?.takeIf {it>0}
    val operation=remember(batches,reason,review) {UUID.randomUUID().toString()}
    val d=recipe.details
    AlertDialog(onDismissRequest=dismiss,title={Text("Review ${d.name}")},text={Column(Modifier.heightIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        RepackingRatio(d)
        PosTextField(batches,{batches=it;review=null;checked=false;error=null},"Whole number of batches")
        NeutralButton(if(review==null) "Review current stock" else "Refresh stock review",{
            try {val current=repo.repackingReview(tenantId,recipe.id,requireNotNull(count) {"Enter a positive whole number of batches."});require(current.recipeVersion==recipe.version) {"This recipe changed. Close this form, refresh and review the updated recipe."};review=current;checked=false;error=null}
            catch(e:Exception) {review=null;checked=false;error=e.message ?: "Stock could not be reviewed."}
        })
        review?.let {value ->
            val used=d.sourceQuantity(value.batches);val made=d.destinationQuantity(value.batches)
            LabelValueRow("Use ${d.sourceName}","${quantityLabel(used,d.sourcePrecision)} ${d.sourceUnit}",emphasized=true)
            LabelValueRow("Stock after","${quantityLabel(value.sourceBefore-used,d.sourcePrecision)} ${d.sourceUnit}")
            LabelValueRow("Make ${d.destinationName}","${quantityLabel(made,d.destinationPrecision)} ${d.destinationUnit}",emphasized=true)
            LabelValueRow("Stock after","${quantityLabel(Math.addExact(value.destinationBefore,made),d.destinationPrecision)} ${d.destinationUnit}")
            Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked,{checked=it});Text("I checked the actual quantities used and packed against this review.",Modifier.weight(1f))}
        }
        PosTextField(reason,{reason=it},"Reason for this repacking")
        Text("Both stock movements save together. If either balance or any item/recipe detail changed after review, neither movement is saved. No cash, debt or cost is recorded.",style=MaterialTheme.typography.bodySmall)
        error?.let {InlineNotice("Repacking not saved",it,NoticeTone.Danger)}
    }},confirmButton={PosButton("Save both stock movements",{
        try {saved(repo.executeRepacking(tenantId,recipe.id,requireNotNull(count),reason.trim(),requireNotNull(review),operation))}
        catch(e:Exception) {error=e.message ?: "Neither stock movement was saved.";review=null;checked=false}
    },enabled=review!=null && checked && reason.trim().length in 3..500)},dismissButton={TextButton(dismiss) {Text("Cancel")}})
}
