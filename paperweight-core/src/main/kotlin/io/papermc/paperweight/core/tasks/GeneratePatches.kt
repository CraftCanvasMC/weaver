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

    @get:InputDirectory
    abstract val workDir: DirectoryProperty

    @get:Input
    abstract val apiDirs: ListProperty<String>

    @get:Input
    abstract val serverDirs: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val outputDir = outputDir.path.cleanDir()
        val name = upstreamName.get()

        val repositories: List<Path> =
            apiDirs.get().map { workDir.path.resolve(it) } +
                serverDirs.get().map { workDir.path.resolve(it) }

        for (repo in repositories) {
            if (!repo.exists()) continue

            val git = Git(repo)
            git("reset", "base", "--soft").runSilently(silenceErr = true)
            git("add", ".").runSilently(silenceErr = true)
            git("commit", "-m", "$name ${repo.fileName}", "--author=Generated Source <noreply+automated@papermc.io>").runSilently(silenceErr = true)
            git(
                "format-patch",
                "--diff-algorithm=myers", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
                "HEAD~1..HEAD", "-o", outputDir.absolutePathString()
            ).runSilently(silenceErr = true)
            cleanupPatch(name, repo.fileName.toString())
        }
        val additionalRepositories: List<Path> =
            listOf(workDir.path.resolve("$name/$name-api/src/main/java"), workDir.path.resolve("$name/$name-server/src/main/java"))
        for (repo in additionalRepositories) {
            val isApi = if (repo.toString().contains("-api")) true else false
            val sourceOutput = if (isApi) outputDir.resolve("api-sources") else outputDir.resolve("server-sources")
            repo.copyRecursivelyTo(sourceOutput)
        }
    }
    fun cleanupPatch(name: String, identifier: String) {
        val patchFile = outputDir.path.resolve("0001-$name-$identifier.patch")
        val text = patchFile.readText()
        val clean1 = text.replace("src/main/java/", "")
        val clean2 = clean1.replace("$name java", "$name minecraft")
        patchFile.writeText(clean2)
        if (patchFile.toString().contains("java")) {
            val new = outputDir.path.resolve("0001-$name-minecraft.patch")
            new.writeText(clean2)
            patchFile.deleteForcefully()
        }
    }
}
