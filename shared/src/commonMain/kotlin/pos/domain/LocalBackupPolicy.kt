package pos.domain

/** Local device preferences only. Backup passwords and encryption keys must never be serialized here. */
data class LocalBackupPolicy(
    val destinationDirectory:String,
    val intervalMinutes:Int=60,
    val retainedVersions:Int=10,
    val enabled:Boolean=true,
) {
    init {
        require(destinationDirectory.isNotBlank() && destinationDirectory.length<=4096 && destinationDirectory.none {it.isISOControl()}) {"Choose a local backup folder."}
        require(intervalMinutes in 5..1440) {"Choose a backup interval from 5 to 1440 minutes."}
        require(retainedVersions in 2..100) {"Keep between 2 and 100 backup versions."}
    }
}
