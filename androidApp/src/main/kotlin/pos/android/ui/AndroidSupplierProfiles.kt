package pos.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pos.data.*
import pos.domain.*
import pos.ui.components.*
import java.util.UUID

@Composable
internal fun AndroidSupplierProfileEditor(repo:PosRepository,tenantId:String,profile:SupplierProfile,dismiss:()->Unit,saved:()->Unit) = key(profile.id,profile.version) {
    var name by rememberSaveable {mutableStateOf(profile.details.name)}
    var phone by rememberSaveable {mutableStateOf(profile.details.phone.orEmpty())}
    var contact by rememberSaveable {mutableStateOf(profile.details.contactPerson.orEmpty())}
    var address by rememberSaveable {mutableStateOf(profile.details.address.orEmpty())}
    var terms by rememberSaveable {mutableStateOf(profile.details.paymentTerms.orEmpty())}
    var notes by rememberSaveable {mutableStateOf(profile.details.notes.orEmpty())}
    var archived by rememberSaveable {mutableStateOf(profile.archived)}
    var reason by rememberSaveable {mutableStateOf("")}
    var reviewed by remember {mutableStateOf(false)}
    val operation=rememberSaveable {UUID.randomUUID().toString()}
    fun changed() {reviewed=false}
    fun details():SupplierDetails {
        fun optional(s:String)=s.trim().ifBlank {null}
        val normalized=optional(phone)?.let {requireNotNull(normalizeNigerianPhone(it)) {"Use a valid Nigerian supplier phone number."}}
        return SupplierDetails(name.trim(),normalized,optional(contact),optional(address),optional(terms),optional(notes))
    }
    OperationsDialog("Manage ${profile.details.name}",dismiss,if(reviewed)"Save supplier changes" else "Review changes",action={
        val edited=details();val why=reason.trim()
        require(why.length in 3..250) {"Enter a reason from 3 to 250 characters."}
        require(edited!=profile.details || archived!=profile.archived) {"Change a supplier detail or archive status first."}
        if(!reviewed) {
            withContext(Dispatchers.IO) {require(repo.supplierProfiles(tenantId,true).single {it.id==profile.id}.version==profile.version) {"This supplier changed. Reopen it and review the latest details."}}
            reviewed=true
        } else {
            withContext(Dispatchers.IO) {repo.updateSupplierProfile(tenantId,profile.id,edited,archived,profile.version,why,operation)}
            saved()
        }
    }) {
        Text("Changes retain the supplier identity and history. Existing receipt names, money and debt stay unchanged.")
        PosTextField(name,{name=it;changed()},"Supplier name")
        PosTextField(phone,{phone=it;changed()},"Phone number (optional)")
        PosTextField(contact,{contact=it;changed()},"Contact person (optional)")
        PosTextField(address,{address=it;changed()},"Address (optional)")
        PosTextField(terms,{terms=it;changed()},"Payment terms (optional)")
        PosTextField(notes,{notes=it;changed()},"Notes (optional)")
        OperationsCheck(archived,{archived=it;changed()},"Archive this supplier")
        Text("Archived suppliers keep their buying receipts, balances and history. Restore before receiving new goods against this account.")
        PosTextField(reason,{reason=it;changed()},"Why are these details changing?")
        if(reviewed) {
            HorizontalDivider()
            LabelValueRow("Supplier",details().name)
            LabelValueRow("Account status",if(archived)"Archived" else "Active")
            Text("Saving revision ${profile.version+1}. Review the entered details above before confirming.")
        }
    }
}

@Composable
internal fun AndroidSupplierProfileHistory(repo:PosRepository,tenantId:String,profile:SupplierProfile,dismiss:()->Unit) {
    val loaded=operationsLoad(repo,tenantId,profile.id) {repo.supplierProfileChanges(tenantId,profile.id)}
    var limit by remember {mutableIntStateOf(20)}
    OperationsDialog("Supplier history",dismiss,"Close",action={dismiss()}) {
        Text(profile.details.name,style=MaterialTheme.typography.titleMedium)
        OperationsLoadState(loaded,"supplier history",{})
        loaded?.getOrNull()?.let {changes ->
            changes.asReversed().take(limit).forEach {change ->
                HorizontalDivider()
                Text("Revision ${change.version} · ${change.createdAt.take(19)} UTC")
                Text(change.current.name)
                Text(if(change.currentArchived)"Archived" else "Active")
                Text("Reason: ${change.reason}")
                change.current.phone?.let {Text("Phone: $it")}
                change.current.address?.let {Text("Address: $it")}
                change.current.contactPerson?.let {Text("Contact: $it")}
                change.current.paymentTerms?.let {Text("Terms: $it")}
                change.current.notes?.let {Text("Notes: $it")}
            }
            if(changes.size>limit)TextButton(onClick={limit+=20}) {Text("Show earlier revisions")}
        }
    }
}
