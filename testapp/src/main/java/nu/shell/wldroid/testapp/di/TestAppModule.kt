package nu.shell.wldroid.testapp.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.proot.DistroTemplate
import nu.shell.wldroid.proot.EnvironmentRegistry
import nu.shell.wldroid.proot.ProotConfig
import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsDownloader
import nu.shell.wldroid.proot.RootfsExtractor
import nu.shell.wldroid.proot.RootfsManager
import nu.shell.wldroid.proot.RootfsStore
import nu.shell.wldroid.shims.ShimExtractor
import nu.shell.wldroid.virgl.GpuCapabilityDetector
import nu.shell.wldroid.virgl.GpuMode
import nu.shell.wldroid.virgl.GpuModeStore
import nu.shell.wldroid.virgl.VirglConfig
import nu.shell.wldroid.virgl.VirglSession

@Module
@InstallIn(SingletonComponent::class)
object TestAppModule {

    @Provides
    @Singleton
    fun provideCompositorConfig(@ApplicationContext context: Context): CompositorConfig =
        CompositorConfig(
            cacheDir = context.cacheDir.absolutePath,
            xkbBasePath = File(context.filesDir, "xkb").absolutePath,
            testClientEnabled = true,
            ahbRegistrySocketPath = File(context.cacheDir, "proot-tmp/.ahb_registry").absolutePath,
        )

    @Provides
    @Singleton
    fun provideProotConfig(@ApplicationContext context: Context): ProotConfig {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return ProotConfig(
            prootBinaryPath = "$nativeLibDir/libproot.so",
            prootLoaderPath = "$nativeLibDir/libproot-loader.so",
            defaultDistro = DistroTemplate.DEBIAN_TRIXIE,
            rootfsBaseDir = File(context.filesDir, "rootfs").absolutePath,
            cacheDir = context.cacheDir.absolutePath,
        )
    }

    @Provides
    @Singleton
    fun provideVirglConfig(@ApplicationContext context: Context): VirglConfig {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return VirglConfig(
            virglBinaryPath = "$nativeLibDir/libvirgl-test-server.so",
            socketPath = File(context.cacheDir, "proot-tmp/.virgl_test").absolutePath,
            gpuMode = GpuMode.AUTO,
        )
    }

    @Provides
    @Singleton
    fun provideGpuCapabilityDetector(@ApplicationContext context: Context): GpuCapabilityDetector =
        GpuCapabilityDetector(context)

    @Provides
    @Singleton
    fun provideGpuModeStore(@ApplicationContext context: Context): GpuModeStore =
        GpuModeStore(context)

    @Provides
    @Singleton
    fun provideShimExtractor(@ApplicationContext context: Context): ShimExtractor =
        ShimExtractor(context)

    @Provides
    @Singleton
    fun provideRootfsStore(@ApplicationContext context: Context): RootfsStore =
        RootfsStore(context)

    @Provides
    @Singleton
    fun provideRootfsDownloader(@ApplicationContext context: Context): RootfsDownloader =
        RootfsDownloader(context.cacheDir)

    @Provides
    @Singleton
    fun provideRootfsExtractor(): RootfsExtractor = RootfsExtractor()

    @Provides
    @Singleton
    fun provideRootfsManager(
        @ApplicationContext context: Context,
        rootfsStore: RootfsStore,
        downloader: RootfsDownloader,
        extractor: RootfsExtractor,
    ): RootfsManager =
        RootfsManager(
            rootfsBaseDir = File(context.filesDir, "rootfs"),
            rootfsStore = rootfsStore,
            downloader = downloader,
            extractor = extractor,
        )

    @Provides
    @Singleton
    fun provideEnvironmentRegistry(
        rootfsManager: RootfsManager,
        rootfsStore: RootfsStore,
    ): EnvironmentRegistry = EnvironmentRegistry(rootfsManager, rootfsStore)

    @Provides
    @Singleton
    fun provideVirglSession(
        virglConfig: VirglConfig,
        gpuDetector: GpuCapabilityDetector,
    ): VirglSession = VirglSession(virglConfig, gpuDetector = gpuDetector)

    @Provides
    @Singleton
    fun provideProotExecutor(prootConfig: ProotConfig): ProotExecutor =
        ProotExecutor(prootConfig)
}
