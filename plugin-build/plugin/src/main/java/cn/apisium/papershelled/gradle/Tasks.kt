package cn.apisium.papershelled.gradle

import net.fabricmc.tinyremapper.NonClassCopyMode
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import net.fabricmc.tinyremapper.TinyUtils
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option
import org.gradle.workers.WorkerExecutor
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.*

private const val GROUP = "papershelled"

abstract class DownloadTask : DefaultTask() {
    init {
        description = "Download server jar"
        group = GROUP
    }

    @get:Input
    @get:Option(option = "jarUrl", description = "The url to download server jar")
    abstract val jarUrl: Property<String>

    @get:InputFile
    @get:Option(option = "jarFile", description = "The file path of server jar")
    abstract val jarFile: RegularFileProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:Internal
    abstract val downloader: Property<DownloadService>

    @TaskAction
    fun run() {
        logger.lifecycle("Downloading jar file: ${jarUrl.get()}")
        workerExecutor.noIsolation().submit(DownloadWorker::class.java) {
            it.downloader.set(downloader)
            it.source.set(jarUrl)
            it.target.set(jarFile)
        }
    }
}

private val LEFT = arrayOf("named", "mojang+yarn")
private val RIGHT = arrayOf("intermediary", "spigot")

abstract class GenerateMappedJarTask : DefaultTask() {
    init {
        description = "Generate mapped jar file"
        group = GROUP
    }

    @get:InputFile
    @get:Option(option = "jarFile", description = "The file path of server jar")
    @get:Optional
    abstract val jarFile: RegularFileProperty

    @get:InputFile
    @get:Option(option = "reobfFile", description = "The file path of reobf map")
    @get:Optional
    abstract val reobfFile: RegularFileProperty

    @get:Internal
    abstract val paperShelledJar: RegularFileProperty

    @get:Internal
    abstract val paperShelledLib: RegularFileProperty

    @ExperimentalPathApi
    @TaskAction
    fun run() {
        Files.createDirectories(project.layout.tmp)
        Files.createDirectories(project.layout.cache)
        val reobf = reobfFile.get().asFile.toPath()
        try {
            val path = URLClassLoader(arrayOf(jarFile.get().asFile.toURI().toURL())).use {
                val clipClazz = it.loadClass("io.papermc.paperclip.Paperclip")
                it.loadClass("io.papermc.paperclip.DownloadContext")
                val repoDir = Path.of(System.getProperty("bundlerRepoDir", project.layout.cache.toString()))
                val patches = clipClazz.getDeclaredMethod("findPatches").run {
                    isAccessible = true
                    invoke(null) as Array<*>
                }
                val downloadContext = clipClazz.getDeclaredMethod("findDownloadContext").run {
                    isAccessible = true
                    invoke(null)
                }
                require(!(patches.isNotEmpty() && downloadContext == null)) {
                    "patches.list file found without a corresponding original-url file"
                }
                val baseFile = if (downloadContext != null) {
                    try {
                        downloadContext::class.java.getDeclaredMethod("download", Path::class.java)
                            .apply { isAccessible = true }.invoke(downloadContext, repoDir)
                    } catch (e: IOException) {
                        throw Exception("Failed to download original jar", e)
                    }
                    downloadContext::class.java.getDeclaredMethod("getOutputFile", Path::class.java)
                        .apply { isAccessible = true }.invoke(downloadContext, repoDir) as Path
                } else {
                    null
                }
                clipClazz.declaredMethods.filter { it.name == "extractAndApplyPatches" }[0].run {
                    isAccessible = true
                    invoke(null, baseFile, patches, repoDir) as Map<String, Map<String, URL>>
                }
            }
            val paperDict = path["versions"]
            require(paperDict != null) {
                "Server jar output directory was not found."
            }
            val paperJar = paperDict.values.elementAt(0).toURI().toPath()
            if (Files.exists(reobf)) Files.delete(reobf)
            FileSystems.newFileSystem(paperJar, null as ClassLoader?).use { fs ->
                val data =
                    Files.readAllBytes(fs.getPath("/META-INF/mappings/reobf.tiny")).toString(StandardCharsets.UTF_8)
                Files.write(
                    reobf, data.replaceFirst(LEFT[1], LEFT[0]).replaceFirst(RIGHT[1], RIGHT[0])
                        .toByteArray(StandardCharsets.UTF_8)
                )
            }
            logger.lifecycle("Found org.bukkit.craftbukkit")
            val temp = paperShelledJar.get().asFile
            val remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createTinyMappingProvider(reobf as Path, RIGHT[0], LEFT[0]))
                .ignoreConflicts(true)
                .fixPackageAccess(true)
                .rebuildSourceFilenames(true)
                .renameInvalidLocals(true)
                .threads(-1)
                .build()
            try {
                OutputConsumerPath.Builder(temp.toPath()).build().use {
                    it.addNonClassFiles(paperJar, NonClassCopyMode.FIX_META_INF, remapper)
                    remapper.readInputs(paperJar)
                    remapper.apply(it)
                }
            } finally {
                remapper.finish()
            }
            //others, come up to our output folder!
            val allOther = path["libraries"]
            require(allOther != null) {
                "No libraries folder is found"
            }
            val libFolder = paperShelledLib.get().asFile.toPath()
            if (!libFolder.exists()) {
                libFolder.createDirectories()
            }
            allOther.values.map { it.toURI().toPath() }.forEach {
                it.copyTo(libFolder.resolve(it.fileName))
            }
        } catch (it: UnsupportedClassVersionError) { it.printStackTrace()
        } catch (it: NoSuchMethodException) { it.printStackTrace()
        } catch (it: ClassNotFoundException) { it.printStackTrace() }
    }

}
