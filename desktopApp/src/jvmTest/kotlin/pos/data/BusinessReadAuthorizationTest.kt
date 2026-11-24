package pos.data

import pos.domain.*
import kotlin.test.*

class BusinessReadAuthorizationTest {
    @Test fun workspaceNamesStayVisibleWhileBusinessReadsRequireTheActiveShop() {
        PosRepository(":memory:").use {repo ->
            val a=repo.createTenant("Counter A");val b=repo.createTenant("Counter B")
            repo.addProduct(a.id,"Soap","piece",100,10.0)
            val product=repo.addProduct(b.id,"Rice","bag",200,10.0)
            repo.addCustomer(b.id,"QA customer");repo.openCashShift(b.id,1000)
            repo.checkout(b.id,listOf(CartLine(product,1)),200,operationId="b-sale")
            var authorized:String?=a.id
            repo.setPermissionCheck {id,_ ->require(id==authorized) {"Open the correct shop."}}
            assertEquals(1,repo.products(a.id).size)
            assertFails {repo.products(b.id)};assertFails {repo.customers(b.id)}
            assertFails {repo.sales(b.id)};assertFails {repo.activeCashShift(b.id)}
            assertFails {repo.exportCloudBusinessSnapshot(b.id)}
            authorized=null
            assertEquals(2,repo.tenants().size)
            assertFails {repo.products(a.id)}
            authorized=b.id
            assertEquals(1,repo.sales(b.id).size)
            assertEquals(1200L,repo.expectedCash(b.id,repo.activeCashShift(b.id)!!.id))
        }
    }
}
