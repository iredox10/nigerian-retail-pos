package pos.output

import pos.domain.ProductImportRow
import pos.domain.parseNaira
import pos.domain.parseQuantity
import java.nio.file.Files
import java.nio.file.Path

object ProductImportService {
    const val TEMPLATE = "name,unit,price_naira,stock\r\nMalt drink,bottle,500.00,24\r\n"
    fun read(path:Path):List<ProductImportRow> {
        require(Files.size(path)<=5*1024*1024) { "Choose a CSV file smaller than 5 MB." }
        return parse(Files.newInputStream(path).use(CsvUtf8::read))
    }
    fun parse(text:String):List<ProductImportRow> {
        require(text.toByteArray(Charsets.UTF_8).size<=5*1024*1024) { "CSV exceeds 5 MB." }
        val records=csv(text.removePrefix("\uFEFF")).filterNot { row -> row.all { it.isBlank() } }
        val headers=records.firstOrNull()?.map {it.trim().lowercase()}
        val withPrecision=headers==listOf("name","unit","price_naira","stock","quantity_precision")
        require(headers==listOf("name","unit","price_naira","stock") || withPrecision) { "Use name,unit,price_naira,stock and optionally quantity_precision." }
        require(records.size in 2..10_001) { "Provide 1 to 10,000 items below the header." }
        return records.drop(1).mapIndexed { index,row ->
            require(row.size==if(withPrecision) 5 else 4) { "Record ${index+2}: expected four columns." }
            val price=parseNaira(row[2]) ?: throw IllegalArgumentException("Record ${index+2}: enter a non-negative naira price with at most two decimal places.")
            val precision=if(withPrecision) row[4].trim().toIntOrNull() else 0
            require(precision!=null && precision in 0..6) { "Record ${index+2}: quantity_precision must be from 0 to 6." }
            val count=parseQuantity(row[3],precision)?.scaledUnits?.takeIf {it<=Int.MAX_VALUE}?.toInt()
            require(count!=null) { "Record ${index+2}: stock must be non-negative and match its quantity precision." }
            ProductImportRow(row[0].trim(),row[1].trim(),price,count,precision)
        }
    }
    private fun csv(text:String):List<List<String>> {
        val rows=mutableListOf<List<String>>();val row=mutableListOf<String>();val field=StringBuilder()
        var quoted=false;var afterQuote=false;var index=0
        fun fieldEnd() {row.add(field.toString());field.setLength(0);afterQuote=false}
        fun rowEnd() {fieldEnd();rows.add(row.toList());row.clear();require(rows.size<=10_002) { "CSV has too many records." }}
        while(index<text.length) {
            val c=text[index]
            if(quoted) {
                if(c=='"') {if(index+1<text.length && text[index+1]=='"') {field.append('"');index++} else {quoted=false;afterQuote=true}}
                else field.append(c)
            } else when(c) {
                '"' -> {require(field.isEmpty() && !afterQuote) { "Unexpected quote in CSV." };quoted=true}
                ',' -> fieldEnd()
                '\r','\n' -> {rowEnd();if(c=='\r' && index+1<text.length && text[index+1]=='\n')index++}
                else -> {require(!afterQuote) { "Only a comma or newline may follow a quoted CSV field." };field.append(c)}
            }
            index++
        }
        require(!quoted) { "A quoted CSV field was not closed." }
        if(field.isNotEmpty() || row.isNotEmpty() || afterQuote)rowEnd()
        return rows
    }
}
