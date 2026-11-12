package pos.data

import pos.domain.Tenant

/** Business records only; device credentials and application configuration never enter this payload. */
data class TenantSnapshot(val tenant: Tenant, val tables: Map<String, List<Map<String, String?>>>)
