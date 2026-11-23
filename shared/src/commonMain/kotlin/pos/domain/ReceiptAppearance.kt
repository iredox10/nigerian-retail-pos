package pos.domain

/** Saved document presentation. Financial content is always supplied by the recorded sale. */
data class ReceiptAppearance(
    val style: String = "classic",
    val frame: String = "none",
    val accentHex: String = "#087F73",
    val headerAlignment: String = "left",
    val showLogo: Boolean = true,
    val artworkAssetId: String? = null,
    val tagline: String? = null,
    val policy: String? = null,
) {
    init {
        require(style in setOf("classic", "modern", "compact")) { "Choose a supported receipt style." }
        require(frame in setOf("none", "line", "double")) { "Choose a supported receipt frame." }
        require(accentHex.matches(Regex("#[0-9A-Fa-f]{6}"))) { "Use a colour in #RRGGBB format." }
        require(headerAlignment in setOf("left", "center")) { "Choose left or centre heading alignment." }
        require(artworkAssetId == null || artworkAssetId.matches(Regex("[a-f0-9]{64}"))) { "Choose an imported receipt image." }
        listOf(tagline to 150, policy to 500).forEach { (value, limit) ->
            require(value == null || (value == value.trim() && value.isNotEmpty() && value.length <= limit && value.none { it.isISOControl() })) {
                "Receipt text must be trimmed printable text within its field limit."
            }
        }
    }
}
