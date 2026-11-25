package pos.data

import kotlin.test.*
import pos.cloud.app.RepositoryCloudLocalStore
import pos.cloud.client.CLOUD_NEW_INSTALLATION
import pos.cloud.protocol.*
import pos.security.WorkspaceAccess
import java.nio.file.Files

class CloudFirstImportTest {
    @Test fun emptyInstallationImportsOnceRequiresNewUnlockAndCannotAddAnotherShopWithoutOwner() {
        val root=Files.createTempDirectory("pos-first-cloud-")
        try {
            val path=root.resolve("pos.db").toString()
            PosRepository(path).use {repo ->
                val owners=WorkspaceAccess(root)
                val source=PosRepository(":memory:").use {r ->
                    val shop=r.createTenant("Real source")
                    r.addProduct(shop.id,"Soap","piece",100,10.0)
                    val snapshot=r.cloudBusinessSnapshot(shop.id,"remote-shop")
                    CloudBootstrapReply("remote-shop",0,r.cloudBusinessSchema(),snapshot)
                }
                repo.setPermissionCheck {_,_->error("Local shop is locked")}
                val local=RepositoryCloudLocalStore(repo,owners,{PosRepository(path)}) {error("No signed-in owner")}
                local.authorizeCloudSetup(CLOUD_NEW_INSTALLATION)
                val id=local.importShop(CLOUD_NEW_INSTALLATION,"approved-ticket",source,"My shop","New-owner-password")
                assertEquals(1,repo.tenants().size)
                assertTrue(owners.unlock(id,"New-owner-password"))
                assertFalse(owners.unlock(id,"wrong-password"))
                assertFails {repo.products(id)}
                assertEquals(id,local.importShop(CLOUD_NEW_INSTALLATION,"approved-ticket",source,"Retry","Different-password"))
                assertTrue(owners.unlock(id,"New-owner-password"))
                assertFails {local.importShop(CLOUD_NEW_INSTALLATION,"another-ticket",source,"Another shop","New-owner-password")}
                assertFails {local.authorizeCloudSetup(id)}
                assertEquals(1,repo.tenants().size)
                PosRepository(path).use {trusted ->assertEquals(1,trusted.products(id).size)}
            }
        } finally {root.toFile().deleteRecursively()}
    }
    @Test fun existingLocalShopCannotBeBypassedThroughFirstInstallPath() {
        val root=Files.createTempDirectory("pos-existing-cloud-")
        try {
            val path=root.resolve("pos.db").toString()
            PosRepository(path).use {repo ->
                repo.createTenant("Existing local shop")
                val local=RepositoryCloudLocalStore(repo,WorkspaceAccess(root),{PosRepository(path)}) {error("No signed-in owner")}
                assertFails {local.authorizeCloudSetup(CLOUD_NEW_INSTALLATION)}
                assertEquals("Existing local shop",repo.tenants().single().name)
            }
        } finally {root.toFile().deleteRecursively()}
    }
}
