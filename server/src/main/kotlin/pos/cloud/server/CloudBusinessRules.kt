package pos.cloud.server

import pos.cloud.protocol.*

/** Implemented by the server's trusted local-domain adapter, never supplied by a client. */
interface CloudBusinessRules {
    fun schema():CloudSchema
    fun validateInitial(snapshot:CloudSnapshot)
    /** Validate all financial/source invariants and return products with new inventory movements. */
    fun validateMerge(before:CloudSnapshot,after:CloudSnapshot,delta:CloudDelta,baseRevision:Long,productVersions:Map<String,Long>):Set<String>
}
