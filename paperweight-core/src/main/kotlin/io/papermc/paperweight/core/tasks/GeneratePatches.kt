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

import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.paperTaskOutput
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.support.uppercaseFirstChar

@UntrackedTask(because = "GeneratePatches should always run when requested.")
abstract class GeneratePatches : BaseTask() {
    @get:Input
    abstract val forkName: Property<String>

    @get:Input
    abstract val forkUrl: Property<String>

    @get:Input
    abstract val commitHash: Property<String>

    @get:Input
    abstract val inputFrom: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparedSource: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val patchesDirOutput: Property<Boolean>

    @get:OutputDirectory
    abstract val serverProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val apiProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val tempOutput: DirectoryProperty

    override fun init() {
        tempOutput.set(layout.cache.resolve(paperTaskOutput()))
    }

    @TaskAction
    fun run() {
        val forkName = forkName.get()
        val commit = commitHash.get()
        val url = forkUrl.get()
        val repoTypes = inputFrom.get().split(",")
        val inputDirs = preparedSource.files.map { it.toPath() }

        val outputToPatchesDirectory = patchesDirOutput.getOrElse(true)
        val outputDir = outputDir.get()
        val cacheOutput = tempOutput.get().asFile.toPath()
        val tempOutput = cacheOutput.cleanFile()
        val outputDirPath = outputDir.asFile.toPath()

        for (inputDir in inputDirs) {
            inputDir.copyRecursivelyTo(tempOutput)
            for (repoType in repoTypes) {
                val repository = tempOutput.resolve(repoType)
                if (!repository.exists()) continue
                val dirName = repository.fileName.toString()
                val repoName = capitalizedName(dirName, dirName.isApi())
                val cutRepo = dirName.substringBefore("-")
                val git = Git(repository)
                git("reset", "base", "--soft").runSilently(silenceErr = true)
                if (git("status", "--porcelain").getText().trim().isBlank()) continue
                git("add", ".").runSilently(silenceErr = true)
                git.commit(forkName, repoName, url, commit)
                git(
                    "format-patch",
                    "--diff-algorithm=myers", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
                    "HEAD~1..HEAD", "-o",
                    outputPath(outputToPatchesDirectory, dirName.isApi(), outputDirPath, cutRepo)
                ).runSilently(silenceErr = true)
            }
        }
    }
    private fun Git.commit(name: String, repoName: String, url: String, commit: String) {
        this(
            "commit",
            "-m",
            "${name.capitalized()} $repoName Patches",
            "-m",
            "Patch generated from ${commitLink(url, commit)}",
            "--author=Generated <noreply+automated@papermc.io>"
        ).runSilently(silenceErr = true)
    }
    private fun capitalizedName(name: String, api: Boolean): String {
        val trimmed = name.split("-").joinToString("") { it.uppercaseFirstChar() }
        return when {
            api -> trimmed.replace("Api", "API")
            else -> trimmed
        }
    }
    private fun String.isApi() = contains("-api")
    private fun commitLink(url: String, hash: String): String {
        val cleanUrl = url.removeSuffix(".git")
        return "$cleanUrl/commit/$hash"
    }
    private fun outputPath(patchDir: Boolean, api: Boolean, outputDir: Path, repoName: String): String {
        val serverOutput = serverProjectDir.get().asFile.toPath()
        val apiOutput = apiProjectDir.get().asFile.toPath()
        return when {
            patchDir && api -> apiOutput.resolve("$repoName-patches/base").absolutePathString()
            patchDir && repoName == "minecraft" -> serverOutput.resolve("$repoName-patches/base").absolutePathString()
            patchDir -> serverOutput.resolve("$repoName-patches/base").absolutePathString()
            else -> outputDir.absolutePathString()
        }
    }
}
