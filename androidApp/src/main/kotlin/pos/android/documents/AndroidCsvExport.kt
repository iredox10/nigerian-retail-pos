package pos.android.documents

import pos.data.PosRepository
import pos.domain.*
import java.math.BigDecimal
import java.time.Instant

/** Typed text and numeric cells prevent untrusted spreadsheet formulas without changing signed amounts. */
sealed interface AndroidCsvCell {
    data class Text(val value: String): AndroidCsvCell
    data class Number(val value: String): AndroidCsvCell {
        init { require(value.matches(Regex("-?\\d+(\\.\\d+)?"))) { "CSV numbers must be plain decimals." } }
    }
}

enum class AndroidCsvKind(val label: String, val requiredPermission: StaffPermission, val scopeNote: String) {
    SALES("Sales", StaffPermission.VIEW_SALES, "Original saved receipts across all recorded dates. Cash, customer credit and manually recorded unconfirmed external amounts remain separate. Later payments and returns do not rewrite original sales."),
    STOCK("Stock", StaffPermission.MANAGE_STOCK, "Current catalogue and on-hand quantities, including archived items. This is a snapshot taken when the preview was generated, not historical stock valuation."),
    CUSTOMERS("Customers", StaffPermission.MANAGE_CUSTOMERS, "Current names, contact details, credit limits and debt. This is a current snapshot, not a dated customer statement."),
    CASH("Cash movements", StaffPermission.MANAGE_MONEY, "Signed actual cash movement records across all dates and shifts, including unassigned entries. Opening counts are separate; this is not the current drawer balance."),
}

data class AndroidCsvExport(
    val tenantId: String, val kind: AndroidCsvKind, val generatedAt: String, val rowCount: Int,
    val csv: String, val previewCsv: String,
)

object AndroidCsvCodec {
    fun encode(headers: List<String>, rows: List<List<AndroidCsvCell>>): String {
        require(headers.isNotEmpty() && rows.all { it.size == headers.size }) { "CSV rows must match their columns." }
        fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""
        fun text(value: String): String {
            val first = value.dropWhile { it.isWhitespace() || it == '\uFEFF' }.firstOrNull()
            return if (first in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        }
        return buildString {
            append(headers.joinToString(",") { quote(text(it)) }); append("\r\n")
            rows.forEach { row ->
                append(row.joinToString(",") { cell -> quote(when(cell) {is AndroidCsvCell.Text -> text(cell.value); is AndroidCsvCell.Number -> cell.value}) })
                append("\r\n")
            }
        }
    }
    fun amount(minor: Long): AndroidCsvCell.Number = AndroidCsvCell.Number(BigDecimal.valueOf(minor, 2).toPlainString())
    fun sales(tenantId: String, values: List<Sale>): Pair<List<String>, List<List<AndroidCsvCell>>> {
        require(values.all { it.tenantId == tenantId }) { "Sales include another shop." }
        return listOf("Sale ID", "Recorded at UTC", "Original sale total NGN", "Cash applied at sale NGN", "Original customer credit NGN", "Customer ID", "Agreed repayment due date WAT", "External amount recorded NGN", "External recording status", "External references", "Cash given NGN", "Change at sale NGN", "Sale note", "Order ID", "Order deposit applied NGN") to values.map { sale ->
            listOf(t(sale.id), t(sale.createdAt), amount(sale.totalMinor), amount(sale.cashMinor), amount(sale.creditMinor), t(sale.customerId.orEmpty()), t(sale.dueDate.orEmpty()), amount(sale.externalRecordedMinor),
                t(if(sale.externalPayments.isEmpty()) "" else "Manually recorded - unconfirmed"), t(sale.externalPayments.joinToString("; ") { "${it.method}: ${it.reference} @ ${it.receivingAccount} = ${BigDecimal.valueOf(it.amountMinor,2).toPlainString()} NGN" }),
                sale.cashGivenMinor?.let(::amount) ?: t(""), sale.changeMinor?.let(::amount) ?: t(""), t(sale.note.orEmpty()), t(sale.orderPayment?.orderId.orEmpty()), sale.orderPayment?.depositMinor?.let(::amount) ?: t(""))
        }
    }
    fun products(tenantId: String, values: List<Product>): Pair<List<String>, List<List<AndroidCsvCell>>> {
        require(values.all { it.tenantId == tenantId }) { "Stock includes another shop." }
        return listOf("Product ID", "Current name", "Stock unit", "Current selling price NGN", "Current stock quantity", "Quantity decimal places", "Barcode", "Archived", "SKU", "Category", "Brand", "Aliases", "Favourite") to values.map {
            listOf(t(it.id),t(it.name),t(it.unit),amount(it.priceMinor),AndroidCsvCell.Number(it.stockLabel),AndroidCsvCell.Number(it.quantityPrecision.toString()),t(it.barcode.orEmpty()),t(it.archived.toString()),t(it.sku.orEmpty()),t(it.category.orEmpty()),t(it.brand.orEmpty()),t(it.aliases.joinToString(" | ")),t(it.favourite.toString()))
        }
    }
    fun customers(tenantId: String, values: List<Customer>): Pair<List<String>, List<List<AndroidCsvCell>>> {
        require(values.all { it.tenantId == tenantId }) { "Customers include another shop." }
        return listOf("Customer ID", "Current name", "Current debt NGN", "Phone", "Address", "Credit limit NGN (blank means no limit)") to values.map {
            listOf(t(it.id),t(it.name),amount(it.balanceMinor),t(it.phone.orEmpty()),t(it.address.orEmpty()),it.creditLimitMinor?.let(::amount) ?: t(""))
        }
    }
    fun cash(tenantId: String, values: List<CashEntry>): Pair<List<String>, List<List<AndroidCsvCell>>> {
        require(values.all { it.tenantId == tenantId }) { "Cash records include another shop." }
        return listOf("Entry ID", "Recorded at UTC", "Shift ID (blank means unassigned)", "Kind", "Description", "Signed cash movement NGN") to values.map {
            listOf(t(it.id),t(it.createdAt),t(it.shiftId.orEmpty()),t(it.kind),t(it.description),amount(it.amountMinor))
        }
    }
    private fun t(value: String) = AndroidCsvCell.Text(value)
}

/** A consistent local read; no generated amount or export action changes the financial records. */
fun PosRepository.prepareAndroidCsv(tenantId: String, kind: AndroidCsvKind): AndroidCsvExport = featureRead(tenantId, StaffPermission.VIEW_REPORTS) {
    featureRead(tenantId, kind.requiredPermission) {
        val (headers, rows) = when(kind) {
            AndroidCsvKind.SALES -> AndroidCsvCodec.sales(tenantId, sales(tenantId))
            AndroidCsvKind.STOCK -> AndroidCsvCodec.products(tenantId, products(tenantId, true))
            AndroidCsvKind.CUSTOMERS -> AndroidCsvCodec.customers(tenantId, customers(tenantId))
            AndroidCsvKind.CASH -> AndroidCsvCodec.cash(tenantId, cashEntries(tenantId))
        }
        AndroidCsvExport(tenantId,kind,Instant.now().toString(),rows.size,AndroidCsvCodec.encode(headers,rows),AndroidCsvCodec.encode(headers,rows.take(20)))
    }
}
fun PosRepository.authorizeAndroidCsvSave(tenantId: String, export: AndroidCsvExport) = featureRead(tenantId, StaffPermission.VIEW_REPORTS) {
    require(export.tenantId == tenantId) { "Choose a preview from this shop." }
    featureRead(tenantId, export.kind.requiredPermission) { Unit }
}
