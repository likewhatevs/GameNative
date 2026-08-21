package app.gamenative.data

import kotlinx.serialization.Serializable

@Serializable
data class UFS(
    val quota: Long = 0L,
    val maxNumFiles: Int = 0,
    val saveFilePatterns: List<SaveFilePattern> = emptyList(),
)
