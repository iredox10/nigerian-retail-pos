package pos.domain

/** Normalizes a Nigerian-format number; this does not verify ownership or that it is assigned. */
fun normalizeNigerianPhone(value: String?): String? {
    val raw=value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    require(raw.length<=40 && raw.all { it in '0'..'9' || it in " +()-" }) { "Use a Nigerian phone number, for example 08012345678 or +2348012345678." }
    val compact=raw.filterNot { it==' ' || it=='(' || it==')' || it=='-' }
    return when {
        compact.matches(Regex("0[0-9]{10}")) -> "+234"+compact.drop(1)
        compact.matches(Regex("\\+234[0-9]{10}")) -> compact
        else -> throw IllegalArgumentException("Use 11 digits starting with 0, or +234 followed by 10 digits.")
    }
}

/** A real ISO calendar date, without a JVM, locale or network dependency. */
fun isValidCreditDueDate(value: String): Boolean {
    if(!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
    val year=value.take(4).toInt(); val month=value.substring(5,7).toInt(); val day=value.takeLast(2).toInt()
    if(year !in 1..9999 || month !in 1..12)return false
    val days=when(month) { 2 -> if(year%4==0 && (year%100!=0 || year%400==0)) 29 else 28; 4,6,9,11 -> 30; else -> 31 }
    return day in 1..days
}

/** Immutable before/after details; debt itself changes only through sales and repayments. */
data class CustomerChange(
    val id:String, val tenantId:String, val customerId:String,
    val previousName:String, val previousPhone:String?, val previousAddress:String?, val previousCreditLimitMinor:Long?,
    val name:String, val phone:String?, val address:String?, val creditLimitMinor:Long?, val createdAt:String,
) {
    init {
        require(id.isNotBlank() && createdAt.isNotBlank())
        Customer(customerId,tenantId,previousName,0,previousPhone,previousAddress,previousCreditLimitMinor)
        Customer(customerId,tenantId,name,0,phone,address,creditLimitMinor)
    }
}
