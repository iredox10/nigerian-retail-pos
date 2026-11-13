package pos.domain

/** Frozen identity at the time an event was saved; no credential or session token is retained. */
data class AuditActor(
    val tenantId: String,
    val id: String?,
    val name: String,
    val role: StaffRole,
) {
    init {
        require(tenantId.isNotBlank())
        require(id == null || id.isNotBlank())
        require(role == StaffRole.OWNER || id != null) { "A staff actor needs its original account identifier." }
        require(name == name.trim() && name.length in 1..100 && name.none { it.isISOControl() }) {
            "Use an actor name from 1 to 100 printable characters."
        }
    }
}

/** Null actor means attribution was unavailable, rather than an invented owner identity. */
data class AuditEvent(
    val id: String,
    val tenantId: String,
    val kind: String,
    val entityId: String,
    val createdAt: String,
    val actor: AuditActor?,
    val description: String? = null,
) {
    init {
        require(listOf(id, tenantId, kind, entityId, createdAt).all { it.isNotBlank() })
        require(actor == null || actor.tenantId == tenantId) { "Activity and actor belong to different shops." }
        require(description == null || (description.length <= 1000 && description.none { it.isISOControl() })) {
            "Activity description must contain at most 1000 printable characters."
        }
    }
}
