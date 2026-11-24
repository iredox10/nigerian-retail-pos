package pos.android.documents

import org.junit.Assert.*
import org.junit.Test
import pos.domain.*

class AndroidCsvCodecTest {
    @Test fun quotesNewlinesAndSpreadsheetTextStaySafeWhileSignedMoneyRemainsNumeric() {
        val csv=AndroidCsvCodec.encode(listOf("Text","Money"),listOf(
            listOf(AndroidCsvCell.Text(" \uFEFF=HYPERLINK(\"bad\")"),AndroidCsvCodec.amount(-12345)),
            listOf(AndroidCsvCell.Text("line one\nline \"two\""),AndroidCsvCodec.amount(Long.MAX_VALUE)),
            listOf(AndroidCsvCell.Text("\t@SUM(1)"),AndroidCsvCodec.amount(Long.MIN_VALUE)),
        ))
        assertTrue(csv.startsWith("\"Text\",\"Money\"\r\n"))
        assertTrue(csv.contains("\"' \uFEFF=HYPERLINK(\"\"bad\"\")\",\"-123.45\""))
        assertTrue(csv.contains("\"line one\nline \"\"two\"\"\",\"92233720368547758.07\""))
        assertTrue(csv.contains("\"'\t@SUM(1)\",\"-92233720368547758.08\""))
        assertFalse(csv.contains("'-123.45"))
        try {AndroidCsvCell.Number("=1+2");fail("Formula accepted as number")}catch(_:IllegalArgumentException){}
        try {AndroidCsvCodec.encode(listOf("One"),listOf(emptyList()));fail("Mismatched columns")}catch(_:IllegalArgumentException){}
    }
    @Test fun measuredStockAndLargestMoneyExportExactlyAndRejectMixedTenants() {
        val item=Product("item","shop","=SUM(1)","kg",Long.MAX_VALUE,1.25,quantityPrecision=3,stockScaled=1250)
        val (header,rows)=AndroidCsvCodec.products("shop",listOf(item))
        val csv=AndroidCsvCodec.encode(header,rows)
        assertTrue(csv.contains("\"'=SUM(1)\""))
        assertTrue(csv.contains("\"92233720368547758.07\",\"1.25\",\"3\""))
        try {AndroidCsvCodec.products("another-shop",listOf(item));fail("Mixed tenant stock")}catch(_:IllegalArgumentException){}
        val cash=CashEntry("entry","shop",null,"expense",-12345,"Refund, \"cash\"","2026-11-24T12:00:00Z")
        val (cashHeader,cashRows)=AndroidCsvCodec.cash("shop",listOf(cash))
        assertTrue(AndroidCsvCodec.encode(cashHeader,cashRows).contains("\"Refund, \"\"cash\"\"\",\"-123.45\""))
        try {AndroidCsvCodec.cash("another-shop",listOf(cash));fail("Mixed tenant cash")}catch(_:IllegalArgumentException){}
    }
    @Test fun originalCreditAndUnconfirmedExternalFundingStaySeparateFromCash() {
        val item=Product("item","shop","Soap","piece",12345,2.0)
        val sale=Sale("sale","shop",listOf(CartLine(item,1)),12345,1000,6345,"customer","2026-11-24T12:00:00Z",
            externalPayments=listOf(ExternalPayment("transfer",5000,"REF-1","Shop account")),cashGivenMinor=1000,note="Customer agreed original terms")
        val (header,rows)=AndroidCsvCodec.sales("shop",listOf(sale))
        val csv=AndroidCsvCodec.encode(header,rows)
        assertTrue(csv.contains("\"123.45\",\"10.00\",\"63.45\""))
        assertTrue(csv.contains("\"50.00\",\"Manually recorded - unconfirmed\""))
        assertTrue(csv.contains("transfer: REF-1 @ Shop account = 50.00 NGN"))
        assertTrue(csv.contains("\"10.00\",\"0.00\""))
        try {AndroidCsvCodec.sales("foreign",listOf(sale));fail("Mixed tenant sale")}catch(_:IllegalArgumentException){}
        val customer=Customer("customer","shop","@name",0)
        try {AndroidCsvCodec.customers("foreign",listOf(customer));fail("Mixed tenant customer")}catch(_:IllegalArgumentException){}
    }
}
