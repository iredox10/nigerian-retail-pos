package pos.cloud.server

import org.springframework.stereotype.Component
import pos.cloud.protocol.*
import pos.data.*

@Component
class RepositoryCloudBusinessRules:CloudBusinessRules {
    private val trustedSchema=PosRepository(":memory:").use {it.cloudBusinessSchema()}
    override fun schema():CloudSchema=trustedSchema
    override fun validateInitial(snapshot:CloudSnapshot)=validateCloudBusinessSnapshot(snapshot)
    override fun validateMerge(before:CloudSnapshot,after:CloudSnapshot,delta:CloudDelta,baseRevision:Long,productVersions:Map<String,Long>):Set<String> =
        validateCloudBusinessMerge(before,after,delta,baseRevision,productVersions)
}
