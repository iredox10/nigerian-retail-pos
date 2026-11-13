package pos

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import pos.data.*
import pos.domain.CartLine
import pos.domain.StaffRole
import pos.security.StaffAccess
import pos.security.WorkspaceAccess
import pos.ui.theme.PosTheme
import java.nio.file.Files

/** Isolated visual-review fixture. Never opens the normal database or a real payment service. */
fun main() {
    System.setProperty("pos.dataDir", Files.createTempDirectory("retail-pos-preview-").toString())
    application {
        val repo = remember { PosRepository(":memory:") }
        val tenant = remember {
            repo.createTenant("Practice shop · Sample data").also { t ->
                val malt=repo.addProduct(t.id,"Malt drink · 330 ml","bottle",50000,240.0,"6150000000010")
                repo.addPurchaseUnit(t.id,malt.id,"carton",24,"sample-carton-definition")
                repo.addSellingUnit(t.id,malt.id,"carton",24,1100000,"6150000000027")
                val rice=repo.addProduct(t.id,"Premium rice · 5 kg","bag",1250000,32.0)
                repo.addProduct(t.id,"Vegetable oil · 1 litre","bottle",380000,48.0)
                repo.addProduct(t.id,"Laundry soap · 200 g","piece",45000,96.0)
                repo.addProduct(t.id,"Tomato paste · 70 g","sachet",25000,120.0)
                repo.setReorderLevel(t.id,malt.id,240,"sample-malt-reorder")
                repo.setReorderLevel(t.id,rice.id,34,"sample-rice-reorder")
                val customer = repo.addCustomer(t.id,"Amina · Sample customer",phone="08012345678",address="Sample market, Lagos",creditLimitMinor=2000000)
                val firstShift = repo.openCashShift(t.id,500000)
                repo.closeCashShift(t.id,firstShift.id,490000)
                repo.openCashShift(t.id,1000000)
                repo.checkout(t.id,listOf(CartLine(malt,3)),50000,customer.id,"sample-credit-sale",java.time.LocalDate.now(java.time.ZoneId.of("Africa/Lagos")).plusDays(7).toString())
                repo.recordRepayment(t.id,customer.id,25000)
                repo.allocateRepayment(t.id,repo.repayments(t.id,customer.id).single().id,"sample-credit-sale",25000,"sample-payment-assignment")
                repo.recordExpense(t.id,"Fuel","Generator fuel",125000,"sample-fuel")
                repo.recordCashMovement(t.id,250000,"Owner added cash","sample-contribution")
                repo.receiveStock(t.id,rice.id,2,1000000,200000,"Sample wholesaler","sample-stock")
                val held=SaleDraft("${malt.id}:2",java.util.UUID.randomUUID().toString())
                DraftStore(t.id).save(held)
                DraftStore(t.id).hold("Sample · collect later",held)
                DraftStore(t.id).save(SaleDraft("${malt.id}:3;${rice.id}:1",java.util.UUID.randomUUID().toString()))
            }
        }
        val route = System.getenv("POS_PREVIEW_SCREEN")?.takeIf { it in setOf("Sell","Stock","Money","Sales","Customers","Reports","Suppliers","Backup","Import","Credit review","Staff") } ?: "Sell"
        val width = System.getenv("POS_PREVIEW_WIDTH")?.toIntOrNull()?.coerceIn(800,1920) ?: 1440
        val height = System.getenv("POS_PREVIEW_HEIGHT")?.toIntOrNull()?.coerceIn(600,1200) ?: 900
        val placement = if (System.getenv("POS_PREVIEW_WIDTH") != null || System.getenv("POS_PREVIEW_HEIGHT") != null) WindowPlacement.Floating else WindowPlacement.Fullscreen
        val staffAccess = remember { StaffAccess() }
        val staffSession = remember {
            if (route=="Staff" || System.getenv("POS_PREVIEW_ROLE")!=null) {
                WorkspaceAccess().register(tenant.id,"Preview-owner-2026")
                val owner=requireNotNull(staffAccess.unlockOwner(tenant.id,"Preview-owner-2026"))
                val cashier=staffAccess.addMember(owner,"Sample cashier",StaffRole.CASHIER,"Preview-cashier-2026")
                val manager=staffAccess.addMember(owner,"Sample manager",StaffRole.MANAGER,"Preview-manager-2026")
                when(System.getenv("POS_PREVIEW_ROLE")) {
                    "Cashier" -> staffAccess.unlockStaff(tenant.id,cashier.id,"Preview-cashier-2026")
                    "Manager" -> staffAccess.unlockStaff(tenant.id,manager.id,"Preview-manager-2026")
                    else -> owner
                }
            } else null
        }
        DesktopWindow(title="Retail POS · Isolated preview · $route",onClose=::exitApplication,initialPlacement=placement,width=width,height=height) {
            App(repo,tenant,route,staffSession,staffAccess)
        }
    }
}
