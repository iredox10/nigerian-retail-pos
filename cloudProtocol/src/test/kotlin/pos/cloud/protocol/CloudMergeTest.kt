package pos.cloud.protocol

import kotlin.test.*

class CloudMergeTest {
    private val products=CloudTable("products",listOf(
        CloudColumn("tenant_id","TEXT",1),CloudColumn("id","TEXT",2),CloudColumn("name","TEXT",0),
        CloudColumn("price_minor","INTEGER",0),CloudColumn("stock","REAL",0),CloudColumn("stock_scaled","INTEGER",0),CloudColumn("quantity_precision","INTEGER",0)),
        listOf("tenant_id","id"),setOf("name","price_minor","stock","stock_scaled"),setOf("stock","stock_scaled"))
    private val customers=CloudTable("customers",listOf(CloudColumn("tenant_id","TEXT",1),CloudColumn("id","TEXT",2),CloudColumn("name","TEXT",0),CloudColumn("balance_minor","INTEGER",0)),listOf("tenant_id","id"),setOf("name","balance_minor"),setOf("balance_minor"))
    private val sales=CloudTable("sales",listOf(CloudColumn("tenant_id","TEXT",1),CloudColumn("id","TEXT",2),CloudColumn("amount_minor","INTEGER",0)),listOf("tenant_id","id"))
    private val lines=CloudTable("sale_lines",listOf(CloudColumn("id","INTEGER",1,true),CloudColumn("tenant_id","TEXT",0),CloudColumn("sale_id","TEXT",0),CloudColumn("selling_unit_id","TEXT",0),CloudColumn("quantity","INTEGER",0)),emptyList())
    private val drafts=CloudTable("draft_lines",listOf(CloudColumn("tenant_id","TEXT",1),CloudColumn("id","TEXT",2),CloudColumn("quantity","INTEGER",0)),listOf("tenant_id","id"),setOf("quantity"),allowDelete=true)
    private val schema=CloudSchema(tables=listOf(products,customers,sales,lines,drafts))
    private fun product(stock:String="10",scaled:String?="10000",name:String="Rice")=mapOf("tenant_id" to "shop","id" to "rice","name" to name,"price_minor" to "10000","stock" to stock,"stock_scaled" to scaled,"quantity_precision" to "3")
    private fun sale(id:String)=mapOf("tenant_id" to "shop","id" to id,"amount_minor" to "10000")
    private fun customer(balance:String="0",name:String="Ada")=mapOf("tenant_id" to "shop","id" to "ada","name" to name,"balance_minor" to balance)
    private fun snapshot(p:CloudRow=product(),c:CloudRow=customer(),s:List<CloudRow> = emptyList(),l:List<CloudRow> = emptyList(),d:List<CloudRow> = emptyList(),name:String="Shop")=
        CloudSnapshot("shop",name,mapOf("products" to listOf(p),"customers" to listOf(c),"sales" to s,"sale_lines" to l,"draft_lines" to d))

    @Test fun independentCatalogueAndStockEditsMergeWithoutOverwritingEither() {
        val base=snapshot()
        val remote=snapshot(p=product(name="Renamed rice").plus("price_minor" to "12000"))
        val local=snapshot(p=product("8.75","8750"),s=listOf(sale("local-sale")))
        val result=CloudMerge.merge(remote,CloudMerge.diff(base,local,schema),schema)
        assertTrue(result.conflicts.isEmpty())
        val row=requireNotNull(result.snapshot).tables.getValue("products").single()
        assertEquals("Renamed rice",row["name"]);assertEquals("12000",row["price_minor"])
        assertEquals("8.75",row["stock"]);assertEquals("8750",row["stock_scaled"])
        assertEquals(listOf(sale("local-sale")),result.snapshot!!.tables.getValue("sales"))
    }
    @Test fun twoIndependentIdenticalSalesBothDeductStock() {
        val base=snapshot()
        val a=snapshot(p=product("9","9000"),s=listOf(sale("a")))
        val b=snapshot(p=product("9","9000"),s=listOf(sale("b")))
        val result=CloudMerge.merge(a,CloudMerge.diff(base,b,schema),schema)
        val final=requireNotNull(result.snapshot)
        assertEquals("8",final.tables.getValue("products").single()["stock"])
        assertEquals("8000",final.tables.getValue("products").single()["stock_scaled"])
        assertEquals(setOf("a","b"),final.tables.getValue("sales").map {it.getValue("id")}.toSet())
    }
    @Test fun backendOperationIdMustDeduplicateRetriesBeforeAdditiveMerge() {
        val base=snapshot();val local=snapshot(p=product("9","9000"),s=listOf(sale("a")))
        val delta=CloudMerge.diff(base,local,schema)
        val first=requireNotNull(CloudMerge.merge(base,delta,schema).snapshot)
        // Deliberate: the merge primitive cannot distinguish a retry from a new identical movement.
        // The durable server's operation fingerprint/receipt is the at-most-once boundary.
        assertEquals("8",requireNotNull(CloudMerge.merge(first,delta,schema).snapshot).tables.getValue("products").single()["stock"])
    }
    @Test fun distinctCustomerDebtMovementsUseExactIntegersPastDoublePrecision() {
        val initial="9007199254740993"
        val base=snapshot(c=customer(initial))
        val remote=snapshot(c=customer("9007199254740994", "Renamed Ada"))
        val local=snapshot(c=customer("9007199254740995"))
        val merged=requireNotNull(CloudMerge.merge(remote,CloudMerge.diff(base,local,schema),schema).snapshot)
        assertEquals("9007199254740996",merged.tables.getValue("customers").single()["balance_minor"])
        assertEquals("Renamed Ada",merged.tables.getValue("customers").single()["name"])
    }
    @Test fun sameMutableFieldConflictReturnsNoPartiallyAppliedSnapshot() {
        val base=snapshot();val remote=snapshot(p=product(name="Remote rice"))
        val local=snapshot(p=product(name="Local rice"),s=listOf(sale("retained-pending")))
        val result=CloudMerge.merge(remote,CloudMerge.diff(base,local,schema),schema)
        assertNull(result.snapshot);assertEquals(listOf("name"),result.conflicts.single().fields)
        assertTrue(remote.tables.getValue("sales").isEmpty())
    }
    @Test fun oversoldStockAndOverflowDebtBecomeExplicitConflicts() {
        val base=snapshot(p=product("1","1000"))
        val remote=snapshot(p=product("0","0"))
        val local=snapshot(p=product("0","0"),s=listOf(sale("oversell")))
        assertNull(CloudMerge.merge(remote,CloudMerge.diff(base,local,schema),schema).snapshot)
        val debtBase=snapshot(c=customer("0"));val debtRemote=snapshot(c=customer(Long.MAX_VALUE.toString()))
        val debtLocal=snapshot(c=customer("1"))
        assertNull(CloudMerge.merge(debtRemote,CloudMerge.diff(debtBase,debtLocal,schema),schema).snapshot)
    }
    @Test fun stockProjectionCannotDisagreeWithScaledQuantity() {
        val base=snapshot();val broken=snapshot(p=product("9","8000"))
        val result=CloudMerge.merge(base,CloudMerge.diff(base,broken,schema),schema)
        assertNull(result.snapshot);assertTrue(result.conflicts.isNotEmpty())
    }
    @Test fun nullableLegacyStockCannotBeArithmeticallyInventedDuringConcurrentConversion() {
        val base=snapshot(p=product("10",null))
        val remote=snapshot(p=product("9",null))
        val local=snapshot(p=product("8","8000"))
        assertNull(CloudMerge.merge(remote,CloudMerge.diff(base,local,schema),schema).snapshot)
    }
    @Test fun immutableFinancialEditsAndRemovalAreRejectedEvenWhenTotalsCouldStillBalance() {
        val base=snapshot(s=listOf(sale("original")))
        val altered=snapshot(s=listOf(sale("original")+("amount_minor" to "9999")))
        assertNull(CloudMerge.merge(base,CloudMerge.diff(base,altered,schema),schema).snapshot)
        assertNull(CloudMerge.merge(base,CloudMerge.diff(base,snapshot(),schema),schema).snapshot)
        assertFails {CloudMerge.applyExact(base,CloudMerge.diff(base,altered,schema),schema)}
    }
    @Test fun duplicateFinancialIdWithDifferentContentIsAConflict() {
        val remote=snapshot(s=listOf(sale("same")))
        val local=snapshot(s=listOf(sale("same")+("amount_minor" to "20000")))
        assertNull(CloudMerge.merge(remote,CloudMerge.diff(snapshot(),local,schema),schema).snapshot)
    }
    @Test fun identicalImmutableInsertIsHarmlessButDoesNotPermitRewriting() {
        val value=snapshot(s=listOf(sale("same")))
        val result=CloudMerge.merge(value,CloudMerge.diff(snapshot(),value,schema),schema)
        assertEquals(CloudMerge.normalize(value,schema),result.snapshot)
    }
    @Test fun declaredDraftDeletionChecksCurrentBeforeImageAndNeverDeletesOtherRows() {
        val draft=mapOf("tenant_id" to "shop","id" to "draft","quantity" to "1")
        val base=snapshot(d=listOf(draft));val delta=CloudMerge.diff(base,snapshot(),schema)
        assertTrue(requireNotNull(CloudMerge.merge(base,delta,schema).snapshot).tables.getValue("draft_lines").isEmpty())
        assertNull(CloudMerge.merge(snapshot(d=listOf(draft+("quantity" to "2"))),delta,schema).snapshot)
    }
    @Test fun exactDownloadRequiresCheckpointAndPreservesLargeIntegerValues() {
        val base=snapshot();val updated=snapshot(c=customer("9007199254740993"),s=listOf(sale("new")))
        val delta=CloudMerge.diff(base,updated,schema)
        assertEquals(CloudMerge.normalize(updated,schema),CloudMerge.applyExact(base,delta,schema))
        assertFails {CloudMerge.applyExact(updated,delta,schema)}
    }
    @Test fun generatedLocalIdsAreIgnoredWhileNullableUnicodeContentStaysDistinct() {
        val line=mapOf("id" to "1","tenant_id" to "shop","sale_id" to "s:|🙌","selling_unit_id" to null,"quantity" to "001")
        val first=snapshot(l=listOf(line));val second=snapshot(l=listOf(line+("id" to "999")+("quantity" to "1")))
        assertTrue(CloudMerge.diff(first,second,schema).isEmpty)
        val normalized=CloudMerge.normalize(first,schema).tables.getValue("sale_lines").single()
        assertFalse(normalized.containsKey("id"));assertNull(normalized["selling_unit_id"])
        assertNotEquals(CloudMerge.rowKey(lines,normalized),CloudMerge.rowKey(lines,normalized+("selling_unit_id" to "null")))
        assertFails {CloudMerge.normalize(snapshot(l=listOf(line,line+("id" to "2"))),schema)}
    }
    @Test fun delimiterLikeAndUnicodeIdentifiersNeverAlias() {
        val table=CloudTable("keys",listOf(CloudColumn("a","TEXT",1),CloudColumn("b","TEXT",2)),listOf("a","b"))
        assertNotEquals(CloudMerge.rowKey(table,mapOf("a" to "x:1|y","b" to "z")),CloudMerge.rowKey(table,mapOf("a" to "x","b" to "1|y:z")))
        assertNotEquals(CloudMerge.rowKey(table,mapOf("a" to "é","b" to "🙌")),CloudMerge.rowKey(table,mapOf("a" to "é","b" to "🙌")))
    }
    @Test fun schemaAndSnapshotOrderingNeverChangeFingerprintsOrDiffs() {
        val reordered=schema.copy(tables=schema.tables.reversed().map {it.copy(columns=it.columns.reversed())})
        assertEquals(schema.fingerprint,reordered.fingerprint)
        val base=snapshot(p=product("10.000","010000"))
        val next=snapshot(p=product()).copy(tables=snapshot().tables.toList().reversed().toMap())
        assertTrue(CloudMerge.diff(base,next,schema).isEmpty)
    }
    @Test fun missingUnknownCrossTenantOrDuplicateRecordsAreNotSilentlyDropped() {
        val base=snapshot()
        assertFails {CloudMerge.normalize(base.copy(tables=base.tables-"sales"),schema)}
        assertFails {CloudMerge.normalize(base.copy(tables=base.tables+("credentials" to emptyList())),schema)}
        assertFails {CloudMerge.normalize(snapshot(p=product()+ ("password" to "secret")),schema)}
        assertFails {CloudMerge.normalize(snapshot(p=product()-("price_minor")),schema)}
        assertFails {CloudMerge.normalize(snapshot(p=product()+("tenant_id" to "other")),schema)}
        assertFails {CloudMerge.normalize(base.copy(tables=base.tables+("products" to listOf(product(),product()))),schema)}
        assertFails {CloudMerge.normalize(snapshot(p=product()+("id" to null)),schema)}
    }
    @Test fun unknownOrMisidentifiedChangesAreRejectedBeforeAnyMutation() {
        val change=CloudRowChange("sales","0".repeat(64),null,sale("s"))
        assertFails {CloudMerge.merge(snapshot(),CloudDelta(listOf(change)),schema)}
        assertFails {CloudMerge.merge(snapshot(),CloudDelta(listOf(change.copy(table="credentials"))),schema)}
        assertFails {CloudDelta(listOf(change,change))}
    }
    @Test fun shopNameChangeMergesOnlyUnchangedOrAlreadyEqualNames() {
        val delta=CloudMerge.diff(snapshot(),snapshot(name="New shop"),schema)
        assertEquals("New shop",CloudMerge.merge(snapshot(),delta,schema).snapshot?.tenantName)
        assertEquals("New shop",CloudMerge.merge(snapshot(name="New shop"),delta,schema).snapshot?.tenantName)
        assertNull(CloudMerge.merge(snapshot(name="Other name"),delta,schema).snapshot)
    }
    @Test fun nonFiniteMalformedNumbersAndOversizedFieldsCannotEnterCloudRows() {
        listOf("NaN","Infinity","1e999","0x10").forEach {value ->assertFails {CloudMerge.normalize(snapshot(p=product(value)),schema)}}
        assertFails {CloudMerge.normalize(snapshot(c=customer("9223372036854775808")),schema)}
        assertFails {CloudMerge.normalize(snapshot(p=product(name="x".repeat(CloudLimits.MAX_FIELD_BYTES+1))),schema)}
    }
}
