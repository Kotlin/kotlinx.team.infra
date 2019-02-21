package kotlinx.team.infra.node

import java.io.*

internal data class Variant(
    val nodeDir: File,
    val nodeBinDir: File,
    val nodeExec: String,
    val npmExec: String,
    val windows: Boolean,
    val dependency: String
) {
}
