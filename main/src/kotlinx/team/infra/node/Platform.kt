package kotlinx.team.infra.node

object Platform {
    private val props = System.getProperties()
    private fun property(name: String) = props.getProperty(name) ?: System.getProperty(name)

    val name: String = run {
        val name = property("os.name").toLowerCase()
        when {
            name.contains("windows") -> "win"
            name.contains("mac") -> "darwin"
            name.contains("linux") -> "linux"
            name.contains("freebsd") -> "linux"
            name.contains("sunos") -> "sunos"
            else -> throw IllegalArgumentException("Unsupported OS: $name")
        }
    }

    val architecture: String = run {
        val arch = property("os.arch").toLowerCase()
        when {
            arch.contains("64") -> "x64"
            arch == "arm" -> {
                // as Java just returns "arm" on all ARM variants, we need a system call to determine the exact arch
                // the node binaries for 'armv8l' are called 'arm64', so we need to distinguish here
                val process = Runtime.getRuntime().exec("uname -m")
                val systemArch = process.inputStream.bufferedReader().use { it.readText() }
                when (systemArch.trim()) {
                    "armv8l" -> "arm64"
                    else -> systemArch
                }
            }
            else -> "x86"
        }
    }
}
