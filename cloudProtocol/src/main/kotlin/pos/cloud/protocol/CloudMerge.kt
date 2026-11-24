package pos.cloud.protocol

import java.math.BigDecimal
import java.math.BigInteger

/** Deterministic record merging. Idempotent operation IDs belong to the durable server transaction,
 * not to this function: two distinct, otherwise identical stock changes must both be counted. */
object CloudMerge {
    private val safeStock = BigDecimal("9007199254740991")

    fun normalize(snapshot: CloudSnapshot, schema: CloudSchema): CloudSnapshot {
        require(snapshot.tables.keys == schema.tables.map { it.name }.toSet()) { "Cloud snapshot tables do not match this app's schema. Local records were kept." }
        fieldSize(snapshot.tenantId); fieldSize(snapshot.tenantName)
        var count = 0L
        val tables = schema.tables.sortedBy { it.name }.associate { table ->
            val rows = snapshot.tables.getValue(table.name)
            count += rows.size
            require(count <= CloudLimits.MAX_ROWS) { "Cloud snapshot exceeds the supported record count." }
            val normalized = rows.map { normalizeRow(it, table, snapshot.tenantId) }
            val keyed = normalized.map { rowKey(table, it) to it }
            require(keyed.map { it.first }.distinct().size == keyed.size) { "Cloud snapshot repeats a record identity." }
            table.name to keyed.sortedBy { it.first }.map { it.second }
        }
        return snapshot.copy(tables = tables)
    }

    /** Generated local counters are never part of shared identities or business row payloads. */
    fun rowKey(table: CloudTable, row: CloudRow): String {
        val normalized = normalizeRow(row, table, null)
        val identity = table.identityColumns.ifEmpty { table.columns.filterNot { it.generated }.map { it.name }.sorted() }
        require(table.columns.filter { it.primaryKeyPosition > 0 && !it.generated }.all { normalized[it.name] != null }) { "Cloud row primary identity cannot be null." }
        // JSON preserves Unicode/null without delimiters that can collide with user text.
        return CloudCodec.fingerprint(identity.map { column -> listOf(column, normalized[column]) })
    }

    fun diff(before: CloudSnapshot, after: CloudSnapshot, schema: CloudSchema): CloudDelta {
        require(before.tenantId == after.tenantId) { "Cloud changes belong to another business." }
        val old = normalize(before, schema)
        val new = normalize(after, schema)
        val changes = schema.tables.sortedBy { it.name }.flatMap { table ->
            val original = index(old.tables.getValue(table.name), table)
            val updated = index(new.tables.getValue(table.name), table)
            (original.keys + updated.keys).sorted().mapNotNull { key ->
                val a = original[key]; val b = updated[key]
                if (a == b) null else CloudRowChange(table.name, key, a, b)
            }
        }
        return CloudDelta(changes, old.tenantName.takeIf { it != new.tenantName }, new.tenantName.takeIf { it != old.tenantName })
    }

    /** Applies one never-before-accepted operation against the current canonical business state.
     * Independent edits merge; financial facts are append-only; exact additive fields accumulate. */
    fun merge(current: CloudSnapshot, delta: CloudDelta, schema: CloudSchema): CloudMergeResult {
        val normalized = normalize(current, schema)
        val tables = schema.tables.associate { table -> table.name to index(normalized.tables.getValue(table.name), table).toMutableMap() }
        val definitions = schema.tables.associateBy { it.name }
        val conflicts = mutableListOf<CloudConflict>()
        var name = normalized.tenantName
        if (delta.beforeName != null) {
            fieldSize(requireNotNull(delta.beforeName)); fieldSize(requireNotNull(delta.afterName))
            if (name == delta.beforeName || name == delta.afterName) name = requireNotNull(delta.afterName)
            else conflicts += CloudConflict("tenants", current.tenantId, listOf("name"), "The shop name changed on another device. Review both names.")
        }
        for (change in delta.changes) {
            val table = requireNotNull(definitions[change.table]) { "Cloud change uses an unsupported table." }
            val before = change.before?.let { normalizeRow(it, table, current.tenantId) }
            val after = change.after?.let { normalizeRow(it, table, current.tenantId) }
            validateChangeIdentity(change, table, before, after)
            val values = tables.getValue(table.name)
            val found = values[change.key]
            when {
                before == null -> {
                    when {
                        found == null -> {
                            try { validateNumericResult(table, requireNotNull(after)); values[change.key] = after }
                            catch (_: IllegalArgumentException) { conflicts += invalidNumbers(change) }
                        }
                        found != after -> conflicts += conflict(change, emptyList(), "This record identifier was already used with different details.")
                    }
                }
                after == null -> {
                    when {
                        !table.allowDelete -> conflicts += conflict(change, emptyList(), "Completed records cannot be removed. Use a recorded correction.")
                        found == null -> Unit
                        found != before -> conflicts += conflict(change, changedFields(before, found), "This unfinished record changed on another device before removal.")
                        else -> values.remove(change.key)
                    }
                }
                else -> {
                    val edits = changedFields(before, after)
                    val forbidden = edits.filterNot { it in table.mutableColumns }
                    if (forbidden.isNotEmpty()) {
                        conflicts += conflict(change, forbidden, "Completed record details cannot be rewritten. Keep the original and record a correction.")
                        continue
                    }
                    if (found == null) {
                        conflicts += conflict(change, edits, "This record is unavailable on the other device. Review its history.")
                        continue
                    }
                    val merged = found.toMutableMap()
                    val bad = mutableListOf<String>()
                    for (column in edits) {
                        val prior = before[column]; val next = after[column]; val actual = found[column]
                        try {
                            merged[column] = when {
                                actual == prior -> next
                                column in table.additiveColumns -> additive(actual, prior, next, table.columns.single { it.name == column })
                                actual == next -> actual
                                else -> { bad += column; actual }
                            }
                        } catch (_: IllegalArgumentException) { bad += column }
                        catch (_: ArithmeticException) { bad += column }
                    }
                    if (bad.isEmpty()) {
                        try { validateNumericResult(table, merged); values[change.key] = merged.toSortedMap() }
                        catch (_: IllegalArgumentException) { bad += table.additiveColumns }
                    }
                    if (bad.isNotEmpty()) conflicts += conflict(change, bad.distinct().sorted(), "Changes conflict or would exceed a supported stock or balance. All original records were kept.")
                }
            }
        }
        if (conflicts.isNotEmpty()) return CloudMergeResult(null, conflicts)
        val result = normalized.copy(tenantName = name, tables = schema.tables.associate { table -> table.name to tables.getValue(table.name).toSortedMap().values.toList() })
        return CloudMergeResult(normalize(result, schema), emptyList())
    }

    /** Ordered server revisions must match exactly. Callers commit the cursor with the imported data.
     * Replaying a revision without checking its cursor is an error, never a second additive update. */
    fun applyExact(before: CloudSnapshot, delta: CloudDelta, schema: CloudSchema): CloudSnapshot {
        val normalized = normalize(before, schema)
        val definitions = schema.tables.associateBy { it.name }
        val tables = schema.tables.associate { table -> table.name to index(normalized.tables.getValue(table.name), table).toMutableMap() }
        var name = normalized.tenantName
        if (delta.beforeName != null) {
            require(name == delta.beforeName) { "Cloud shop-name revision does not match the download checkpoint." }
            name = requireNotNull(delta.afterName); fieldSize(name)
        }
        for (change in delta.changes) {
            val table = requireNotNull(definitions[change.table]) { "Cloud revision uses an unsupported table." }
            val a = change.before?.let { normalizeRow(it, table, before.tenantId) }
            val b = change.after?.let { normalizeRow(it, table, before.tenantId) }
            validateChangeIdentity(change, table, a, b)
            val values = tables.getValue(table.name)
            require(values[change.key] == a) { "Cloud revision does not match its recorded before-image. The download cursor must not advance." }
            if (b == null) {
                require(table.allowDelete) { "Cloud revision cannot remove completed records." }
                values.remove(change.key)
            } else {
                if (a != null) require(changedFields(a, b).all { it in table.mutableColumns }) { "Cloud revision cannot rewrite completed records." }
                validateNumericResult(table, b)
                values[change.key] = b
            }
        }
        return normalize(normalized.copy(tenantName = name, tables = schema.tables.associate { table -> table.name to tables.getValue(table.name).toSortedMap().values.toList() }), schema)
    }

    private fun validateChangeIdentity(change: CloudRowChange, table: CloudTable, before: CloudRow?, after: CloudRow?) {
        require(before != after) { "Cloud change does not change a record." }
        listOfNotNull(before, after).forEach { require(rowKey(table, it) == change.key) { "Cloud change row key does not match its record." } }
    }
    private fun index(rows: List<CloudRow>, table: CloudTable): Map<String, CloudRow> = rows.associateBy { rowKey(table, it) }
    private fun changedFields(before: CloudRow, after: CloudRow): List<String> = before.keys.filter { before[it] != after[it] }.sorted()
    private fun conflict(change: CloudRowChange, fields: List<String>, message: String) = CloudConflict(change.table, change.key, fields, message)
    private fun invalidNumbers(change: CloudRowChange) = conflict(change, emptyList(), "This record has an unsupported stock or balance. No changes were applied.")

    private fun normalizeRow(row: CloudRow, table: CloudTable, tenantId: String?): CloudRow {
        val all = table.columns.map { it.name }.toSet()
        val retained = table.columns.filterNot { it.generated }
        require(row.keys.all { it in all } && row.keys.containsAll(retained.map { it.name })) { "Cloud record columns are incomplete or unsupported." }
        val result = retained.associate { column ->
            val value = row.getValue(column.name)
            if (value != null) fieldSize(value)
            column.name to when {
                value == null -> null
                column.isInteger() -> parseInteger(value).toString()
                column.isDecimal() -> parseDecimal(value).stripTrailingZeros().toPlainString()
                else -> value
            }
        }.toSortedMap()
        if (tenantId != null && "tenant_id" in result) require(result["tenant_id"] == tenantId) { "Cloud record belongs to another business." }
        return result
    }
    private fun fieldSize(value: String) { require(value.length <= CloudLimits.MAX_FIELD_BYTES && value.toByteArray(Charsets.UTF_8).size <= CloudLimits.MAX_FIELD_BYTES) { "Cloud field exceeds the supported size." } }
    private fun parseInteger(value: String): BigInteger {
        require(value.length <= 40 && value.matches(Regex("-?[0-9]+"))) { "Cloud integer is not a decimal integer." }
        return value.toBigInteger().also { require(it >= BigInteger.valueOf(Long.MIN_VALUE) && it <= BigInteger.valueOf(Long.MAX_VALUE)) { "Cloud integer exceeds its supported range." } }
    }
    private fun parseDecimal(value: String): BigDecimal {
        require(value.length <= 80 && value.matches(Regex("-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]{1,3})?"))) { "Cloud numeric field is not finite decimal data." }
        return value.toBigDecimal().also { require(it.precision() <= 100 && it.scale() in -100..100) { "Cloud numeric field exceeds its supported range." } }
    }
    private fun additive(current: String?, before: String?, after: String?, column: CloudColumn): String {
        require(current != null && before != null && after != null) { "Unknown numeric balances cannot be combined." }
        return if (column.isInteger()) {
            val result = parseInteger(current) + parseInteger(after) - parseInteger(before)
            require(result >= BigInteger.ZERO && result <= BigInteger.valueOf(Long.MAX_VALUE)) { "Combined balance exceeds its supported range." }
            result.toString()
        } else {
            val result = parseDecimal(current) + parseDecimal(after) - parseDecimal(before)
            require(result >= BigDecimal.ZERO && result <= safeStock) { "Combined quantity exceeds its supported range." }
            result.stripTrailingZeros().toPlainString()
        }
    }
    private fun validateNumericResult(table: CloudTable, row: CloudRow) {
        table.additiveColumns.forEach { column ->
            row[column]?.let { value ->
                val number = parseDecimal(value)
                require(number >= BigDecimal.ZERO) { "Stock or balance cannot be negative." }
                if (table.name == "products" && column in setOf("stock", "stock_scaled")) require(number <= safeStock) { "Stock exceeds its exact supported range." }
            }
        }
        if (table.name == "products" && row["stock_scaled"] != null && "stock" in row && "quantity_precision" in row) {
            val precision = requireNotNull(row["quantity_precision"]?.toIntOrNull()).also { require(it in 0..6) }
            require(parseDecimal(requireNotNull(row["stock"])).compareTo(parseDecimal(requireNotNull(row["stock_scaled"])).movePointLeft(precision)) == 0) { "Stock does not match its scaled precision." }
        }
    }
}
