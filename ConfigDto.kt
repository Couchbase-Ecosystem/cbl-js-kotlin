package cbl.js.kotiln

data class ConfigDto (
    val channels: MutableSet<String>?,
    val documentIds: MutableSet<String>?,
    val pushFilter: String? = null,
    val pullFilter: String? = null,
    )
