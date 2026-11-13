package pos.data

import kotlin.test.*
import pos.domain.*
import pos.output.BackupService
import pos.output.CsvService
import java.nio.file.Files
import java.sql.DriverManager

class ActivityAuditTest {
    @Test fun eventsKeepOriginalIdentityAndRetriesDoNotDuplicateThem() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop")
            val original=AuditActor(shop.id,"cashier","Amina",StaffRole.CASHIER)
            repo.setActorProvider { original }
            val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            repo.checkout(shop.id,listOf(CartLine(item,2)),200,operationId="sale")
            val events=repo.auditEvents(shop.id)
            assertEquals(setOf("product.created","sale.completed"),events.map {it.kind}.toSet())
            assertTrue(events.all {it.actor==original})
            repo.setActorProvider { original.copy(name="Renamed",role=StaffRole.MANAGER) }
            repo.checkout(shop.id,listOf(CartLine(item,2)),200,operationId="sale")
            assertEquals(events,repo.auditEvents(shop.id))
            repo.setReorderLevel(shop.id,item.id,2,"reminder")
            assertEquals("Renamed",repo.auditEvents(shop.id).single {it.kind=="stock.reminder_changed"}.actor?.name)
            assertEquals(original,repo.auditEvents(shop.id).single {it.kind=="sale.completed"}.actor)
        }
    }

    @Test fun failedOrCrossShopAttributionRollsBackTheEntireSale() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop"); val other=repo.createTenant("Other")
            val item=repo.addProduct(shop.id,"Soap","piece",100,5.0)
            val before=repo.exportTenantSnapshot(shop.id)
            repo.setActorProvider { error("Session expired") }
            assertFails {repo.checkout(shop.id,listOf(CartLine(item,2)),200,operationId="sale")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
            repo.setActorProvider { AuditActor(other.id,null,"Owner",StaffRole.OWNER) }
            assertFails {repo.checkout(shop.id,listOf(CartLine(item,2)),200,operationId="sale")}
            assertEquals(before,repo.exportTenantSnapshot(shop.id))
        }
    }

    @Test fun historySurvivesReopenAndCannotBeEditedOrDeleted() {
        val dir=Files.createTempDirectory("audit-reopen")
        try {
            val path=dir.resolve("pos.db").toString()
            val tenant=PosRepository(path).use { repo ->
                repo.createTenant("Shop").also {shop -> repo.addCustomer(shop.id,"Amina")}
            }
            PosRepository(path).use { repo ->
                assertEquals("customer.created",repo.auditEvents(tenant.id).single().kind)
                assertNull(repo.auditEvents(tenant.id).single().actor)
                DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
                    db.createStatement().use { statement ->
                        assertFails {statement.executeUpdate("UPDATE activity_audit SET actor_name='Invented'")}
                        assertFails {statement.executeUpdate("DELETE FROM activity_audit")}
                        statement.execute("CREATE TRIGGER reject_audit BEFORE INSERT ON activity_audit BEGIN SELECT RAISE(ABORT,'Audit storage failure'); END")
                    }
                }
                val before=repo.exportTenantSnapshot(tenant.id)
                assertFails {repo.addCustomer(tenant.id,"Another")}
                assertEquals(before,repo.exportTenantSnapshot(tenant.id))
            }
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun historyIsShopScopedAndChecksReadPermissions() {
        PosRepository(":memory:").use { repo ->
            val shop=repo.createTenant("Shop"); val other=repo.createTenant("Other")
            repo.addCustomer(shop.id,"Amina")
            assertTrue(repo.auditEvents(other.id).isEmpty())
            assertFails {repo.auditEvents("missing")}
            repo.setPermissionCheck { _,permission -> require(permission!=StaffPermission.VIEW_REPORTS) }
            assertFails {repo.auditEvents(shop.id)}
        }
    }

    @Test fun encryptedBackupRetainsIdentityAndOldBackupsDoNotInventActors() {
        val dir=Files.createTempDirectory("audit-backup")
        try {
            PosRepository(":memory:").use { repo ->
                val shop=repo.createTenant("Shop")
                repo.setActorProvider { AuditActor(shop.id,"manager","Amina",StaffRole.MANAGER) }
                repo.addCustomer(shop.id,"Customer")
                val original=repo.auditEvents(shop.id).single()
                val file=dir.resolve("shop.backup")
                BackupService.create(repo,shop.id,"backup-password",file)
                val snapshot=BackupService.read(file,"backup-password")
                val restored=repo.restoreTenantSnapshot(snapshot,"Recovered")
                assertEquals(original.copy(tenantId=restored.id,actor=original.actor!!.copy(tenantId=restored.id)),repo.auditEvents(restored.id).single())
                val legacy=repo.restoreTenantSnapshot(snapshot.copy(tables=snapshot.tables-"activity_audit"),"Legacy")
                assertTrue(repo.auditEvents(legacy.id).isEmpty())
                assertEquals(1,repo.customers(legacy.id).size)
                val before=repo.tenants()
                listOf("entity_id" to "forged", "created_at" to "invalid", "actor_name" to "", "actor_role" to null).forEach { change ->
                    val corrupt=snapshot.copy(tables=snapshot.tables+("activity_audit" to snapshot.tables.getValue("activity_audit").map {it+change}))
                    assertFails {repo.restoreTenantSnapshot(corrupt,"Invalid")}
                    assertEquals(before,repo.tenants())
                }
            }
        } finally {dir.toFile().deleteRecursively()}
    }

    @Test fun csvProtectsFormulasAndRejectsForeignOrDuplicateEvents() {
        val event=AuditEvent("event","shop","sale.completed","=source","2026-11-13T10:00:00Z",AuditActor("shop","staff","=Amina",StaffRole.CASHIER),"@description")
        val csv=CsvService.auditEvents("shop",listOf(event))
        assertContains(csv,"\"'=Amina\"")
        assertContains(csv,"\"'=source\"")
        assertContains(csv,"\"'@description\"")
        assertFails {CsvService.auditEvents("other",listOf(event))}
        assertFails {CsvService.auditEvents("shop",listOf(event,event))}
    }
}
