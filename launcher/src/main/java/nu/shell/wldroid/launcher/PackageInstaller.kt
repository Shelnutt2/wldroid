package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment

class PackageInstaller(private val prootExecutor: ProotExecutor) {

    private val VALID_PACKAGE_NAME = Regex("^[a-zA-Z0-9][a-zA-Z0-9.+\\-]*$")

    suspend fun installPackages(
        environment: RootfsEnvironment,
        packages: List<String>,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
        // Validate package names to prevent shell injection
        for (pkg in packages) {
            require(VALID_PACKAGE_NAME.matches(pkg)) {
                "Invalid package name: $pkg"
            }
        }
        val command = listOf(
            "bash", "-c",
            "apt-get update -qq && apt-get install -y --no-install-recommends ${packages.joinToString(" ")}",
        )
        return prootExecutor.runInProot(
            environment = environment,
            command = command,
            onOutput = onOutput,
        )
    }

    suspend fun isCommandAvailable(
        environment: RootfsEnvironment,
        command: String,
    ): Boolean {
        val exitCode = prootExecutor.runInProot(
            environment = environment,
            command = listOf("which", command),
        )
        return exitCode == 0
    }
}
