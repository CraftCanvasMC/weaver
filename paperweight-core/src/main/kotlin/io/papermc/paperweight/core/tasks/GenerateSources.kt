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
import io.papermc.paperweight.util.constants.JST_CONFIG
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.newInstance

abstract class GenerateSources : JavaLauncherTask() {
    @get:Input
    abstract val forkName: Property<String>

    @get:Input
    abstract val forkUrl: Property<String>

    @get:Input
    abstract val commitHash: Property<String>

    @get:Input
    abstract val inputFrom: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val tempOutput: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance<ApplySourceATs>().apply {
        jst.from(project.configurations.named(JST_CONFIG))
    }

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val atFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val additionalPatch: RegularFileProperty

    @get:Input
    abstract val generateSources: Property<Boolean>

    @get:Input
    abstract val generateResources: Property<Boolean>

    @get:Input
    abstract val generateTestSources: Property<Boolean>

    @get:Input
    abstract val generateTestResources: Property<Boolean>

    @get:OutputDirectory
    abstract val sourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val testSourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val testResourceOutput: DirectoryProperty

    override fun init() {
        super.init()
        tempOutput.set(layout.buildDirectory.dir(paperTaskOutput(name)))
    }

    @TaskAction
    fun run() {
        val commit = commitHash.get()
        val url = forkUrl.get()
        val generatedOutput = sourceOutput.get().asFile.toPath()
        val generatedResourcesOutput = resourceOutput.get().asFile.toPath()
        val generatedTestOutput = testSourceOutput.get().asFile.toPath()
        val generatedTestResourcesOutput = testResourceOutput.get().asFile.toPath()

        val cacheOutput = tempOutput.get().asFile.toPath()
        val tempOutput = cacheOutput.cleanFile()

        val outputName = inputFrom.get().substringBefore("-")
        val workDirectory = workDir.get().asFile.toPath()

        val sourceDir = workDirectory.resolve("$outputName/${inputFrom.get()}")
        if (!sourceDir.exists()) return
        sourceDir.copyRecursivelyTo(tempOutput)

        val atDirPath = tempOutput.resolve("src/main/java")
        val patchDir = tempOutput

        if (additionalPatch.isPresent) {
            val git = Git(patchDir)
            git("am", "--3way", "--ignore-whitespace", additionalPatch.path.toString()).runSilently()
        }

        if (atFile.isPresent && atFile.path.readText().isNotBlank() && atDirPath.exists()) {
            ats.run(
                launcher.get(),
                atDirPath,
                atDirPath,
                atFile.path,
                temporaryDir.toPath()
            )
        }

        val outputs: List<Path> = listOfNotNull(
            generatedOutput.takeIf { generateSources.get() },
            generatedResourcesOutput.takeIf { generateResources.get() },
            generatedTestOutput.takeIf { generateTestSources.get() },
            generatedTestResourcesOutput.takeIf { generateTestResources.get() }
        )

        for (output in outputs) {
            output.deleteRecursively()
            when (output) {
                generatedOutput -> {
                    tempOutput.resolve("src/main/java").copyRecursivelyTo(output)
                }

                generatedResourcesOutput -> {
                    tempOutput.resolve("src/main/resources").copyRecursivelyTo(output)
                }

                generatedTestOutput -> {
                    tempOutput.resolve("src/test/java").copyRecursivelyTo(output)
                }

                generatedTestResourcesOutput -> {
                    tempOutput.resolve("src/test/resources").copyRecursivelyTo(output)
                }
            }

            output.filesMatchingRecursive("*.java").forEach {
                val content = it.readText()
                it.writeText("// Generated from ${commitLink(url, commit)}\n$content")
            }
        }
    }

    private fun commitLink(url: String, hash: String): String {
        val cleanUrl = url.removeSuffix(".git")
        return "$cleanUrl/commit/$hash"
    }
}
