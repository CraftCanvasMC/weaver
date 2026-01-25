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

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.JavaLauncherTask
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.cache
import io.papermc.paperweight.util.constants.paperTaskOutput
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.newInstance

@UntrackedTask(because = "PrepareForPatchGeneration doesn't have stable inputs.")
abstract class PrepareForPatchGeneration : JavaLauncherTask() {

    @get:Input
    abstract val forkName: Property<String>

    @get:Input
    abstract val repoName: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val workDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:Input
    abstract val validateAts: Property<Boolean>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalPatch: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    override fun init() {
        super.init()
        outputDir.set(layout.cache.resolve(paperTaskOutput()))
    }

    @TaskAction
    fun run() {
        val outputName = forkName.get()
        val workDirectory = workDir.get().asFile.toPath()

        val sourceDir = if (repoName.get() == "minecraft") {
            workDirectory.resolve("$outputName/$outputName-server/src/minecraft/java")
        } else {
            workDirectory.resolve("$outputName/${repoName.get()}")
        }
        val outputDir = outputDir.get().asFile.toPath().resolve(repoName.get())
        val outputDirPath = outputDir.cleanDir()
        sourceDir.copyRecursivelyTo(outputDirPath)

        if (additionalPatch.isPresent) {
            val git = Git(outputDirPath)
            git("am", "--3way", "--ignore-whitespace", additionalPatch.path.toString()).runSilently()
        }

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            ats.run(
                launcher.get(),
                outputDirPath,
                outputDirPath,
                atFile.path,
                temporaryDir.toPath(),
                validate = validateAts.get(),
            )
        }
    }
}
