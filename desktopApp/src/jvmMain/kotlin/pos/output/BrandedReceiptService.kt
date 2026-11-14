package pos.output

import pos.domain.*

/** Uses the receipt's captured business identity, never today's edited address for an old sale. */
object BrandedReceiptService {
    fun format(tenant:Tenant,sale:Sale,returns:List<SaleReturn> = emptyList(),profile:ReceiptProfileSnapshot?=null):String {
        require(profile==null || (profile.tenantId==tenant.id && profile.saleId==sale.id)) {"Receipt branding belongs to another sale or shop."}
        if(profile==null) return ReceiptService.format(tenant,sale,returns)+
            "\nShop contact details were not captured for this sale.\nThe workspace name shown is current.\n"
        val details=profile.details
        val receipt=ReceiptService.format(tenant.copy(name=details.displayName),sale,returns)
        return buildString {
            appendLine(details.displayName)
            details.phone?.let {appendLine("Phone: $it")}
            details.address?.let {appendLine("Address: $it")}
            append(receipt.substringAfter('\n'))
            details.footer?.let {appendLine();appendLine(it)}
        }
    }
}
