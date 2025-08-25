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

abstract class GeneratePatches : BaseTask() {
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

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparedSource: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val patchesDirOutput: Property<Boolean>

    @get:Internal
    abstract val serverSourceInput: DirectoryProperty

    @get:Internal
    abstract val apiSourceInput: DirectoryProperty

    @get:Internal
    abstract val serverTestSourceInput: DirectoryProperty

    @get:Internal
    abstract val apiTestSourceInput: DirectoryProperty

    @get:Internal
    abstract val serverResourcesInput: DirectoryProperty

    @get:Internal
    abstract val apiResourcesInput: DirectoryProperty

    @get:Internal
    abstract val serverTestResourcesInput: DirectoryProperty

    @get:Internal
    abstract val apiTestResourcesInput: DirectoryProperty

    @get:OutputDirectory
    abstract val serverSourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val apiSourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val serverTestSourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val apiTestSourceOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val serverResourcesOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val apiResourcesOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val serverTestResourcesOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val apiTestResourcesOutput: DirectoryProperty

    @get:OutputDirectory
    abstract val serverProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val apiProjectDir: DirectoryProperty

    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val tempOutput: DirectoryProperty

    override fun init() {
        tempOutput.set(layout.buildDirectory.dir(paperTaskOutput(name)))
        serverSourceInput.convention(forkName.flatMap { workDir.dir("$it/$it-server/src/main/java") })
        serverTestSourceInput.convention(forkName.flatMap { workDir.dir("$it/$it-server/src/test/java") })
        serverResourcesInput.convention(forkName.flatMap { workDir.dir("$it/$it-server/src/main/resources") })
        serverTestResourcesInput.convention(forkName.flatMap { workDir.dir("$it/$it-server/src/test/resources") })
        apiSourceInput.convention(forkName.flatMap { workDir.dir("$it/$it-api/src/main/java") })
        apiTestSourceInput.convention(forkName.flatMap { workDir.dir("$it/$it-api/src/test/java") })
        apiResourcesInput.convention(forkName.flatMap { workDir.dir("$it/$it-api/src/main/resources") })
        apiTestResourcesInput.convention(forkName.flatMap { workDir.dir("$it/$it-api/src/test/resources") })
    }

    @TaskAction
    fun run() {
        val forkName = forkName.get()
        val commit = commitHash.get()
        val url = forkUrl.get()
        val repoTypes = inputFrom.get().split(",")
        val serverGeneratedOutput = serverSourceOutput.get()
        val apiGeneratedOutput = apiSourceOutput.get()
        val serverTestGeneratedOutput = serverTestSourceOutput.get()
        val apiTestGeneratedOutput = apiTestSourceOutput.get()
        val apiResourceGeneratedOutput = apiResourcesOutput.get()
        val serverResourceGeneratedOutput = serverResourcesOutput.get()
        val apiTestResourceGeneratedOutput = apiTestResourcesOutput.get()
        val serverTestResourceGeneratedOutput = serverTestResourcesOutput.get()
        val inputDirs = preparedSource.files.map { it.toPath() }

        val outputToPatchesDirectory = patchesDirOutput.getOrElse(true)
        val outputDir = outputDir.getOrElse(layout.buildDirectory.dir(paperTaskOutput(name)).get())
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
        val serverJavaRepo = serverSourceInput.get()
        val apiJavaRepo = apiSourceInput.get()
        val serverTestRepo = serverTestSourceInput.get()
        val apiTestRepo = apiTestSourceInput.get()
        val serverResourcesRepo = serverResourcesInput.get()
        val apiResourcesRepo = apiResourcesInput.get()
        val serverTestResourcesRepo = serverTestResourcesInput.get()
        val apiTestResourcesRepo = apiTestResourcesInput.get()

        val additionalRepositories = listOfNotNull(
            apiJavaRepo,
            apiTestRepo,
            apiResourcesRepo,
            apiTestResourcesRepo,
            serverJavaRepo,
            serverTestRepo,
            serverResourcesRepo,
            serverTestResourcesRepo
        )

        for (repo in additionalRepositories) {
            if (!repo.asFile.toPath().exists()) continue
            val string = repo.toString()
            val sourceOutputPath = when {
                string.isApi() && string.isTest() -> apiTestGeneratedOutput.asFile.toPath()
                !string.isApi() && string.isTest() -> serverTestGeneratedOutput.asFile.toPath()
                string.isApi() && string.isResources() && string.isTest() -> apiTestResourceGeneratedOutput.asFile.toPath()
                !string.isApi() && string.isResources() && string.isTest() -> serverTestResourceGeneratedOutput.asFile.toPath()
                string.isApi() && !string.isResources() -> apiGeneratedOutput.asFile.toPath()
                string.isApi() && string.isResources() -> apiResourceGeneratedOutput.asFile.toPath()
                !string.isApi() && !string.isResources() -> serverGeneratedOutput.asFile.toPath()
                else -> serverResourceGeneratedOutput.asFile.toPath()
            }
            sourceOutputPath.deleteRecursively()
            repo.asFile.toPath().copyRecursivelyTo(sourceOutputPath)

            sourceOutputPath.filesMatchingRecursive("*.java").forEach {
                val content = it.readText()
                it.writeText("// Generated from ${commitLink(url, commit)}\n$content")
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
            "--author=Generated Source <noreply+automated@papermc.io>"
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
    private fun String.isResources() = endsWith("resources")
    private fun String.isTest() = contains("test/")
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
