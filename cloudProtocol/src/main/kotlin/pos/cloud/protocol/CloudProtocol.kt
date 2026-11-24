package pos.cloud.protocol

import kotlinx.serialization.Serializable

/** Every business scalar travels as its saved decimal/text representation, never a JSON double. */
typealias CloudRow = Map<String, String?>

@Serializable
data class CloudSnapshot(val tenantId: String, val tenantName: String, val tables: Map<String, List<CloudRow>>) {
    init { require(tenantId.isNotBlank() && tenantId.length <= 200); require(tenantName.isNotBlank()) }
}

@Serializable
data class CloudColumn(val name: String, val type: String, val primaryKeyPosition: Int, val generated: Boolean = false) {
    init {
        require(CloudLimits.identifier.matches(name)) { "Unsupported cloud column name." }
        require(type.length <= 80 && primaryKeyPosition >= 0) { "Unsupported cloud column definition." }
    }
}

@Serializable
data class CloudTable(
    val name: String,
    val columns: List<CloudColumn>,
    val identityColumns: List<String>,
    val mutableColumns: Set<String> = emptySet(),
    val additiveColumns: Set<String> = emptySet(),
    val allowDelete: Boolean = false,
) {
    init {
        require(CloudLimits.identifier.matches(name)) { "Unsupported cloud table name." }
        require(columns.isNotEmpty() && columns.size <= CloudLimits.MAX_COLUMNS && columns.map { it.name }.distinct().size == columns.size) { "Cloud columns are missing or duplicated." }
        val names = columns.filterNot { it.generated }.map { it.name }.toSet()
        require(names.isNotEmpty() && identityColumns.distinct().size == identityColumns.size && identityColumns.all { it in names }) { "Cloud row identity is invalid." }
        require(mutableColumns.all { it in names && it !in identityColumns && it != "tenant_id" }) { "Only declared non-identity columns can be edited." }
        require(additiveColumns.all { it in mutableColumns }) { "Additive fields must be explicitly mutable." }
        require(additiveColumns.all { column -> columns.single { it.name == column }.let { it.isInteger() || it.isDecimal() } }) { "Only numeric fields can be additive." }
        // A generated child identity is its complete immutable content. It cannot be renamed by a row edit.
        require(identityColumns.isNotEmpty() || mutableColumns.isEmpty()) { "Content-identified rows cannot have mutable fields." }
    }
}

@Serializable
data class CloudSchema(val version: Int = 1, val tables: List<CloudTable>) {
    init {
        require(version == 1) { "This cloud schema version is not supported. Update the app before synchronizing." }
        require(tables.isNotEmpty() && tables.size <= CloudLimits.MAX_TABLES && tables.map { it.name }.distinct().size == tables.size) { "Cloud tables are missing or duplicated." }
    }
    val fingerprint: String get() = CloudCodec.fingerprint(copy(tables = tables.sortedBy { it.name }.map { table ->
        table.copy(columns = table.columns.sortedBy { it.name }, mutableColumns = table.mutableColumns.sorted().toSet(), additiveColumns = table.additiveColumns.sorted().toSet())
    }))
}

@Serializable
data class CloudRowChange(val table: String, val key: String, val before: CloudRow?, val after: CloudRow?) {
    init {
        require(CloudLimits.identifier.matches(table) && key.matches(Regex("[0-9a-f]{64}"))) { "Cloud change identity is invalid." }
        require(before != null || after != null) { "A cloud change has no record." }
    }
}

@Serializable
data class CloudDelta(val changes: List<CloudRowChange>, val beforeName: String? = null, val afterName: String? = null) {
    init {
        require(changes.size <= CloudLimits.MAX_ROWS) { "Cloud change batch is too large." }
        require((beforeName == null) == (afterName == null) && (beforeName == null || beforeName.isNotBlank() && requireNotNull(afterName).isNotBlank())) { "Cloud shop-name change is incomplete." }
        require(changes.map { it.table to it.key }.distinct().size == changes.size) { "Cloud change batch repeats a record." }
    }
    val isEmpty: Boolean get() = changes.isEmpty() && beforeName == null
}

@Serializable
data class CloudConflict(val table: String, val key: String, val fields: List<String>, val message: String)

@Serializable
data class CloudPushRequest(val operationId: String, val baseRevision: Long, val schemaHash: String, val delta: CloudDelta) {
    init { require(operationId.isNotBlank() && operationId.length <= 200 && operationId.none { it.isISOControl() }); require(baseRevision >= 0); require(schemaHash.matches(Regex("[0-9a-f]{64}"))) }
}

@Serializable
data class CloudPushReply(val operationId: String, val status: String, val revision: Long, val conflicts: List<CloudConflict> = emptyList()) {
    init { require(operationId.isNotBlank()); require(revision >= 0 && status in setOf("accepted", "conflict")); require((status == "accepted") == conflicts.isEmpty()) }
}

@Serializable
data class CloudChangeEnvelope(val revision: Long, val operationId: String, val deviceId: String, val delta: CloudDelta) {
    init { require(revision > 0 && operationId.isNotBlank() && deviceId.isNotBlank()) }
}

@Serializable
data class CloudPullReply(val afterRevision: Long, val nextRevision: Long, val headRevision: Long, val changes: List<CloudChangeEnvelope>) {
    init {
        require(afterRevision >= 0 && nextRevision in afterRevision..headRevision)
        require(changes.size <= CloudLimits.MAX_PULL_CHANGES)
        require(nextRevision - afterRevision == changes.size.toLong() && changes.withIndex().all { (index, change) -> change.revision == afterRevision + index + 1 }) { "Cloud download revisions are not consecutive." }
    }
}

@Serializable
data class CloudBootstrapReply(val businessId: String, val revision: Long, val schema: CloudSchema, val snapshot: CloudSnapshot) {
    init { require(businessId == snapshot.tenantId && revision >= 0) }
}

data class CloudMergeResult(val snapshot: CloudSnapshot?, val conflicts: List<CloudConflict>) {
    init { require((snapshot == null) == conflicts.isNotEmpty()) }
}

object CloudLimits {
    const val MAX_TABLES = 128
    const val MAX_COLUMNS = 256
    const val MAX_ROWS = 200_000
    const val MAX_FIELD_BYTES = 2 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 96 * 1024 * 1024
    const val MAX_PULL_CHANGES = 100
    internal val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
}

internal fun CloudColumn.isInteger() = type.uppercase().contains("INT")
internal fun CloudColumn.isDecimal() = type.uppercase().let { it.contains("REAL") || it.contains("FLOA") || it.contains("DOUB") || it.contains("NUM") || it.contains("DEC") }
