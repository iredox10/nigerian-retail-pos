package pos.output

import pos.domain.ProductImportRow
import pos.domain.parseNaira
import java.nio.file.Files
import java.nio.file.Path

object ProductImportService {
    const val TEMPLATE = "name,unit,price_naira,stock\r\nMalt drink,bottle,500.00,24\r\n"
    fun read(path:Path):List<ProductImportRow> {
        require(Files.size(path)<=5*1024*1024) { "Choose a CSV file smaller than 5 MB." }
        return parse(Files.readString(path,Charsets.UTF_8))
    }
    fun parse(text:String):List<ProductImportRow> {
        require(text.toByteArray(Charsets.UTF_8).size<=5*1024*1024) { "CSV exceeds 5 MB." }
        val records=csv(text.removePrefix("\uFEFF")).filterNot { row -> row.all { it.isBlank() } }
        require(records.isNotEmpty() && records.first().map { it.trim().lowercase() }==listOf("name","unit","price_naira","stock")) { "Use the template columns in order: name,unit,price_naira,stock." }
        require(records.size in 2..10_001) { "Provide 1 to 10,000 items below the header." }
        return records.drop(1).mapIndexed { index,row ->
            require(row.size==4) { "Record ${index+2}: expected four columns." }
            val price=parseNaira(row[2]) ?: throw IllegalArgumentException("Record ${index+2}: enter a non-negative naira price with at most two decimal places.")
            val count=row[3].trim().takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull()
            require(count!=null) { "Record ${index+2}: stock must be a whole number from 0 to ${Int.MAX_VALUE}." }
            ProductImportRow(row[0].trim(),row[1].trim(),price,count)
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
