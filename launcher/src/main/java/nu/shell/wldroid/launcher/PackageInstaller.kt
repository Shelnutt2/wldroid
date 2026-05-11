package nu.shell.wldroid.launcher

import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment

class PackageInstaller(private val prootExecutor: ProotExecutor) {

    suspend fun installPackages(
        environment: RootfsEnvironment,
        packages: List<String>,
        onOutput: ((String) -> Unit)? = null,
    ): Int {
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
