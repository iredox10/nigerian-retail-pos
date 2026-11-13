package pos.help

/** Bundled task guidance. Reading these articles never requests a network connection. */
data class HelpArticle(val id: String, val title: String, val summary: String, val steps: List<String>)

object HelpContent {
    val articles = listOf(
        HelpArticle("cash-sale", "Make a cash sale", "Choose goods, check the selling unit and take payment.", listOf(
            "Open Sell. Search for an item or choose it from the goods on the left. A keyboard barcode scanner can enter a barcode followed by Enter.",
            "Check This sale on the right. Review each quantity, selling unit and price before choosing Take payment.",
            "Choose Cash. Enter the cash given by the customer or choose Exact amount. Check any change to give before finishing the sale.",
            "Wait for Sale saved. If the printer fails, find the saved receipt under Sales and print it again. Do not make a second sale to obtain another receipt."
        )),
        HelpArticle("credit", "Let a named customer pay later", "Record an agreed balance explicitly; a short payment alone does not create debt.", listOf(
            "Ask an owner or manager to sign in when your role cannot make credit sales. Add the customer under Customers if needed.",
            "At payment, choose Pay later and select who will pay the balance. Enter any cash received and an agreed due date if applicable.",
            "Check the amount the customer will owe before saving. The customer's credit limit may prevent new debt.",
            "For a later repayment, open Customers and choose Record payment. Assign that payment to the relevant receipts in customer history for exact overdue reporting. A repayment is not another sale."
        )),
        HelpArticle("saved-sale", "Put a sale aside and resume it", "A saved basket holds your work, without reserving goods.", listOf(
            "In Sell, choose Put sale aside and give it a name you will recognise.",
            "Use Saved sales to retrieve it. Put any other active sale aside first.",
            "Review goods and prices when resuming: holding does not reserve stock or freeze a price. Checkout checks current stock and prices.",
            "Closing and reopening the app retains supported unfinished baskets on this computer. An unfinished basket is not a completed sale or payment."
        )),
        HelpArticle("stock-units", "Add stock and check units", "Buying cartons and selling loose goods use the same base stock.", listOf(
            "Open Stock, choose the item and Add stock. Check the buying quantity and the buying price per displayed unit.",
            "For a carton or pack, define its actual contents under Manage → Buying units. Define selling packs separately under Selling units, with their own prices or barcodes.",
            "Read the base-stock preview before saving. A carton is not assumed to contain a standard number of bottles or pieces.",
            "Enter how much cash you paid and name the supplier if any amount remains unpaid. Cash paid from the drawer requires an open drawer with sufficient recorded cash.",
            "Use Count stock for a physical count correction with a reason. Keep the original purchase and sale records."
        )),
        HelpArticle("cash-drawer", "Open and close today's cash", "Count physical cash and preserve any shortage or excess.", listOf(
            "Open Money → Today's cash. Count the money physically present and open the cash drawer with that starting amount.",
            "Cash sales, repayments, refunds, expenses, supplier payments and manual movements recorded during the shift affect its expected cash.",
            "Sales or repayments recorded before the shift opened stay unassigned. Do not add them again unless their physical cash belongs in the counted opening amount.",
            "At closing, count what is actually present and save that count. Any shortage or excess remains in the closing record; it does not change recorded sales.",
            "Use Take money out for owner withdrawals or bank deposits. Use Record expense for an actual shop cost."
        )),
        HelpArticle("backup", "Save and restore an encrypted backup", "A manual backup file is separate from records on this computer.", listOf(
            "An owner can open More → Encrypted shop backup and save the selected shop's completed business records using a backup password.",
            "Keep the backup password and a copy of the file somewhere safe away from this computer. Losing the only device and the only backup can mean losing the records.",
            "The backup excludes unfinished baskets, sign-in credentials and device settings. Encryption of the backup does not encrypt the working database.",
            "Restore creates a separate shop with a new owner password. Check the restored records and count physical cash before trading.",
            "Pending local changes are not an online backup. Automatic cloud backup and synchronization are not connected in this build."
        )),
        HelpArticle("offline", "What works without internet", "Local saving, bank confirmation and online backup are separate.", listOf(
            "Supported cash sales, local customer credit, stock work, cash counts, reports and local backup files work on this computer without a server request.",
            "Bank transfer and card verification services are not connected. A screenshot, customer SMS or internet connection does not confirm that money arrived.",
            "Saved on this computer means a local record exists. It does not mean another computer has received it or a cloud backup exists.",
            "A printer needs its own working connection. If printing fails after a sale is saved, reprint the saved receipt instead of repeating checkout.",
            "For an error while saving, keep the entered details and read the error before retrying. Do not delete completed financial history to fix a balance."
        ))
    )

    fun search(query: String): List<HelpArticle> {
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        return articles.filter { article ->
            val text = "${article.title} ${article.summary} ${article.steps.joinToString(" ")}".lowercase()
            terms.all { it.lowercase() in text }
        }
    }
}
