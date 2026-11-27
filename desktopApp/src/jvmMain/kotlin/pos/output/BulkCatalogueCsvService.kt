package pos.output

import pos.domain.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

/** Editable CSV with reversible spreadsheet protection for text cells and explicit stable identities. */
object BulkCatalogueCsvService {
    val headers=listOf("product_id","review_version","name","unit","quantity_precision","price_naira","barcode","sku","category","brand","aliases","favourite","reorder_level")
    const val maximumBytes=5*1024*1024
    fun export(rows:List<BulkCatalogueUpdateRow>):String {
        require(rows.size<=10000)
        fun quote(value:String)="\"${value.replace("\"","\"\"")}\""
        return buildString {
            append(headers.joinToString(",",transform=::quote));append("\r\n")
            rows.forEach {row ->
                val v=row.values
                val cells=listOf(row.productId,row.expectedVersion,v.name,v.unit,v.precision.toString(),BigDecimal.valueOf(v.priceMinor,2).toPlainString(),v.barcode.orEmpty(),v.metadata.sku.orEmpty(),v.metadata.category.orEmpty(),v.metadata.brand.orEmpty(),v.metadata.aliases.joinToString("\n"),v.metadata.favourite.toString(),v.reorderLevel?.toString().orEmpty())
                append(cells.mapIndexed {index,value ->if(index in setOf(4,5,12)) value else protect(value)}.joinToString(",",transform=::quote));append("\r\n")
            }
        }.also {require(it.toByteArray(Charsets.UTF_8).size<=maximumBytes) {"Catalogue CSV exceeds 5 MB. Export fewer goods."}}
    }
    fun read(path:Path):BulkCatalogueCsvResult {
        require(Files.size(path)<=maximumBytes) {"Choose a CSV file no larger than 5 MB."}
        return parse(Files.newInputStream(path).use(CsvUtf8::read))
    }
    fun parse(text:String):BulkCatalogueCsvResult {
        require(text.toByteArray(Charsets.UTF_8).size<=maximumBytes) {"CSV exceeds 5 MB."}
        val records=csv(text.removePrefix("\uFEFF")).filterNot {it.all(String::isBlank)}
        require(records.firstOrNull()?.map {it.trim().lowercase()}==headers) {"Use the exported bulk-update columns without adding, removing or reordering them."}
        require(records.size in 2..10001) {"Provide 1 to 10,000 product records below the header."}
        val issues=mutableListOf<BulkCatalogueIssue>()
        val rows=records.drop(1).mapIndexedNotNull {index,cells ->
            try {
                require(cells.size==headers.size) {"Expected ${headers.size} columns."}
                val c=cells.mapIndexed {column,value ->if(column in setOf(4,5,12)) value else unprotect(value)}
                val precision=c[4].toIntOrNull();require(precision!=null && precision in 0..6) {"Quantity precision must remain from 0 to 6."}
                val price=requireNotNull(parseNaira(c[5])) {"Selling price must be non-negative naira with at most two decimal places."}
                val favourite=when(c[11].trim().lowercase()) {"true"->true;"false"->false;else->error("Favourite must be true or false.")}
                val reorder=if(c[12].isBlank()) null else c[12].trim().takeIf {it.all {ch ->ch in '0'..'9'}}?.toIntOrNull().also {require(it!=null) {"Reorder level must be a whole number from 0 to ${Int.MAX_VALUE}, or blank."}}
                fun optional(value:String)=value.trim().ifEmpty {null}
                val aliases=if(c[10].isBlank()) emptyList() else c[10].split('\n').map {it.removeSuffix("\r").trim()}
                BulkCatalogueUpdateRow(index+2,c[0],c[1],BulkCatalogueValues(c[2].trim(),c[3],precision,price,optional(c[6]),ProductMetadata(optional(c[7]),optional(c[8]),optional(c[9]),aliases,favourite),reorder))
            } catch(e:Exception) {issues+=BulkCatalogueIssue(index+2,e.message ?: "This record could not be read.");null}
        }
        rows.groupBy {it.productId}.values.filter {it.size>1}.forEach {duplicates ->duplicates.forEach {issues+=BulkCatalogueIssue(it.record,"Product ID appears more than once in this file.")}}
        return BulkCatalogueCsvResult(rows,issues.sortedBy {it.record})
    }
    /** Leading apostrophes are doubled, so neutralization does not lose literal catalogue text. */
    private fun protect(value:String):String {
        if(value.startsWith("'")) return "'"+value
        val first=value.dropWhile {it.isWhitespace() || it=='\uFEFF'}.firstOrNull()
        return if(first in listOf('=','+','-','@') || value.firstOrNull() in listOf('\t','\r','\n')) "'"+value else value
    }
    private fun unprotect(value:String):String {
        if(value.startsWith("''")) return value.drop(1)
        if(value.startsWith("'")) {
            val plain=value.drop(1)
            val first=plain.dropWhile {it.isWhitespace() || it=='\uFEFF'}.firstOrNull()
            if(first in listOf('=','+','-','@') || plain.firstOrNull() in listOf('\t','\r','\n')) return plain
        }
        return value
    }
    private fun csv(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var afterQuote=false;var index=0
        fun fieldEnd() {row+=field.toString();field.setLength(0);afterQuote=false}
        fun rowEnd() {fieldEnd();rows+=row.toList();row.clear();require(rows.size<=10002) {"CSV has too many records."}}
        while(index<text.length) {
            val c=text[index]
            if(quoted) {
                if(c=='"') {if(index+1<text.length && text[index+1]=='"') {field.append('"');index++} else {quoted=false;afterQuote=true}}
                else field.append(c)
            } else when(c) {
                '"'->{require(field.isEmpty() && !afterQuote) {"Unexpected CSV quote near record ${rows.size+1}."};quoted=true}
                ','->fieldEnd()
                '\r','\n'->{rowEnd();if(c=='\r' && index+1<text.length && text[index+1]=='\n') index++}
                else->{require(!afterQuote) {"Only a comma or newline may follow a quoted field near record ${rows.size+1}."};field.append(c)}
            }
            index++
        }
        require(!quoted) {"A quoted CSV field was not closed near record ${rows.size+1}."}
        if(field.isNotEmpty() || row.isNotEmpty() || afterQuote) rowEnd()
        return rows
    }
}
