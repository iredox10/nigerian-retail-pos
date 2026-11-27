package pos.android.documents

import pos.data.*
import pos.domain.*

/** Compare metadata inside the same transaction; concurrent sales may change debt without being overwritten. */
fun PosRepository.updateReviewedAndroidCustomer(tenantId:String,expected:Customer,requested:Customer,operationId:String):Customer = featureWrite(tenantId,StaffPermission.MANAGE_CUSTOMERS) {
    require(expected.tenantId==tenantId && requested.tenantId==tenantId && expected.id==requested.id) {"This customer review belongs to another shop or customer."}
    val current=requireNotNull(customers(tenantId).singleOrNull {it.id==expected.id}) {"This customer is unavailable."}
    if(customerChanges(tenantId,expected.id).none {it.id==operationId}) {
        require(current.copy(balanceMinor=expected.balanceMinor)==expected) {"Customer details changed after review. Reopen the customer and review the latest details."}
    }
    updateCustomer(tenantId,expected.id,requested.name,requested.phone,requested.address,requested.creditLimitMinor,operationId)
}
