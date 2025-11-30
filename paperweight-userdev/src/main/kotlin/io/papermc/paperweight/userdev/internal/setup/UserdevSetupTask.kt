/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.userdev.internal.setup

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.userdev.internal.util.formatNs
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import javax.inject.Inject
import kotlin.io.path.*
import kotlin.system.measureNanoTime
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.CompileClasspath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.logging.progress.ProgressLoggerFactory
import org.gradle.workers.WorkerExecutor

abstract class UserdevSetupTask : JavaLauncherTask() {
    @get:ServiceReference
    abstract val setupService: Property<UserdevSetup>

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    @get:InputFiles
    abstract val devBundle: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val decompilerConfig: ConfigurableFileCollection

    @get:InputFiles
    abstract val paramMappingsConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheDecompilerConfig: ConfigurableFileCollection

    @get:InputFiles
    abstract val macheConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val remapperConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheRemapperConfig: ConfigurableFileCollection

    @get:InputFiles
    abstract val macheParamMappingsConfig: ConfigurableFileCollection

    @get:InputFiles
    abstract val macheConstantsConfig: ConfigurableFileCollection

    @get:CompileClasspath
    abstract val macheCodebookConfig: ConfigurableFileCollection

    @get:OutputFile
    abstract val mappedServerJar: RegularFileProperty

    @get:OutputFile
    @get:Optional
    abstract val legacyPaperclipResult: RegularFileProperty

    @get:OutputFile
    abstract val reobfMappings: RegularFileProperty

    @get:Inject
    abstract val progressLoggerFactory: ProgressLoggerFactory

    @get:Input
    abstract val injectServerJar: Property<Boolean>

    @get:OutputFile
    abstract val processedServerJar: RegularFileProperty

    override fun init() {
        super.init()
        mappedServerJar.set(layout.cache.resolve(paperTaskOutput("mappedServerJar", "jar")))
        processedServerJar.set(layout.cache.resolve(paperTaskOutput("processedServerJar", "jar")))
        reobfMappings.set(layout.cache.resolve(paperTaskOutput("reobfMappings", "tiny")))
    }

    @TaskAction
    fun run() {
        val context = SetupHandler.ExecutionContext(
            workerExecutor,
            launcher.get(),
            layout,
            logger,
            progressLoggerFactory,
            decompilerConfig,
            paramMappingsConfig,
            macheDecompilerConfig,
            macheConfig,
            remapperConfig,
            macheRemapperConfig,
            macheParamMappingsConfig,
            macheConstantsConfig,
            macheCodebookConfig,
        )

        val result: SetupHandler.ArtifactsResult
        val generatedIn = measureNanoTime {
            result = setupService.get().generateArtifacts(context)
        }
        logger.lifecycle("Completed setup in ${formatNs(generatedIn)}")

        val copiedTime = measureNanoTime {
            // for horizon, a bit dirty
            if (injectServerJar.get()) {
                result.mainOutput.copyTo(mappedServerJar.path.createParentDirectories(), overwrite = true)
            } else {
                result.mainOutput.copyTo(processedServerJar.path.createParentDirectories(), overwrite = true)
                mappedServerJar.path.deleteForcefully()
            }
            result.legacyOutput?.copyTo(legacyPaperclipResult.path.createParentDirectories(), overwrite = true)
            reobfMappings.path.createParentDirectories().deleteForcefully()
            setupService.get().extractReobfMappings(reobfMappings.path.createParentDirectories())
        }
        logger.lifecycle("Copied artifacts to project cache in ${formatNs(copiedTime)}")
    }
}
