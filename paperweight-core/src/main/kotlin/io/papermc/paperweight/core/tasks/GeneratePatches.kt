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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.*
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@UntrackedTask(because = "GeneratePatches should always run when requested")
abstract class GeneratePatches : BaseTask() {
    @get:Input
    abstract val upstreamName: Property<String>

    @get:Input
    abstract val upstreamLink: Property<String>

    @get:Input
    abstract val upstreamHash: Property<String>

    @get:InputDirectory
    abstract val workDir: DirectoryProperty

    @get:Input
    abstract val inputFrom: ListProperty<String>

    @get:OutputDirectory
    @get:Optional
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val rootName: Property<String>

    @get:InputDirectory
    abstract val rootDir: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val patchDirOutput: Property<Boolean>

    @TaskAction
    fun run() {
        val outputToPatches = patchDirOutput.getOrElse(false)
        val outputDir = if (!outputToPatches && outputDir.isPresent) {
            outputDir.path.cleanDir()
        } else if (!outputToPatches && !outputDir.isPresent) {
            throw PaperweightException(
                "Cannot find an output directory and patchDirOutput is set to false!"
            )
        } else {
            null
        }
        val name = upstreamName.get()
        val upstreamUrl = upstreamLink.get()
        val url = upstreamUrl.removeSuffix(".git")

        val repositories: List<Path> =
            inputFrom.get().map { workDir.path.resolve("$name/$it") }

        for (repo in repositories) {
            if (!repo.exists()) continue

            val isApi = if (repo.fileName.toString().contains("-api")) true else false
            val repoName = if (isApi) {
                repo.fileName.toString().split("-").joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }.replace("Api", "API")
            } else if (repo.fileName.toString().equals("java")) {
                "Minecraft"
            } else {
                repo.fileName.toString().split("-").joinToString("") { it.replaceFirstChar { char -> char.uppercase() } }
            }

            val cutRepo = repo.fileName.toString().substringBefore("-")
            val git = Git(repo)
            git("reset", "base", "--soft").runSilently(silenceErr = true)
            val toCommit = git("status", "--porcelain").getText().trim()
            if (toCommit.isBlank()) continue
            git("add", ".").runSilently(silenceErr = true)
            git(
                "commit",
                "-m",
                "${name.capitalized()} $repoName Patches",
                "-m",
                "Patch generated from $url/commit/${upstreamHash.get()}",
                "--author=Generated Source <noreply+automated@papermc.io>"
            ).runSilently(silenceErr = true)
            git(
                "format-patch",
                "--diff-algorithm=myers", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
                "HEAD~1..HEAD", "-o",
                if (outputToPatches && isApi) {
                    rootDir.convertToPath().resolve("${rootName.get()}-api/$cutRepo-patches/base").absolutePathString()
                } else if (outputToPatches && cutRepo.equals("java")) {
                    rootDir.convertToPath().resolve("${rootName.get()}-server/minecraft-patches/base").absolutePathString()
                } else if (outputToPatches) {
                    rootDir.convertToPath().resolve("${rootName.get()}-server/$cutRepo-patches/base").absolutePathString()
                } else {
                    if (outputDir != null) {
                        outputDir.absolutePathString()
                    } else {
                        throw PaperweightException(
                            "Cannot find an output directory and patchDirOutput is set to false!"
                        )
                    }
                }
            ).runSilently(silenceErr = true)
        }
        val additionalRepositories: List<Path> =
            listOf(workDir.path.resolve("$name/$name-api/src/main/java"), workDir.path.resolve("$name/$name-server/src/main/java"))
        for (repo in additionalRepositories) {
            val isApi = if (repo.toString().contains("-api")) true else false
            val sourceOutput = if (isApi) {
                rootDir.convertToPath().resolve("${rootName.get()}-api/src/generated/java")
            } else {
                rootDir.convertToPath().resolve("${rootName.get()}-server/src/generated/java")
            }
            sourceOutput.deleteRecursively()
            repo.copyRecursivelyTo(sourceOutput)

            sourceOutput.filesMatchingRecursive("*.java").forEach {
                val content = it.readText()
                it.writeText("// Generated from $url/commit/${upstreamHash.get()}\n$content")
            }
        }
    }
}
