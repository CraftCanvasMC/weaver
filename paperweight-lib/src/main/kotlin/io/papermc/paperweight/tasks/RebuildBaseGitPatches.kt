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

package io.papermc.paperweight.tasks

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javax.inject.Inject
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*

@UntrackedTask(because = "RebuildBaseGitPatches should always run when requested")
abstract class RebuildBaseGitPatches : ControllableOutputTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val base: DirectoryProperty // we dont need this at all but we use this to trick gradle into the correct task execution order

    @get:OutputDirectory
    abstract val patchDir: DirectoryProperty

    @get:Input
    abstract val filterPatches: Property<Boolean>

    @get:Inject
    abstract val providers: ProviderFactory

    @get:Input
    abstract val identifier: Property<String>

    override fun init() {
        printOutput.convention(true)
        filterPatches.convention(
            providers.gradleProperty("paperweight.filter-patches")
                .map { it.toBoolean() }
                .orElse(true)
        )
    }

    @TaskAction
    fun run() {
        val git = Git(inputDir.path)

        // fail fast if someone decides to name a patch with these names to avoid patch corruption
        val patchedBaseCommitCount = git(
            "rev-list",
            "--grep=${identifier.get()} Base Patches",
            "base..HEAD"
        ).getText().lines().filter { it.isNotBlank() }
        if (patchedBaseCommitCount.size > 1) {
            val count = patchedBaseCommitCount.size
            throw PaperweightException(
                "Exceeded the max amount of commits with the identifier: `${identifier.get()} Base Patches`!\nGot $count commits, expected: 1"
            )
        }
        val fileCommitCount = git("rev-list", "--grep=${identifier.get()} File Patches", "base..HEAD").getText().lines().filter { it.isNotBlank() }
        if (fileCommitCount.size > 1) {
            val count = fileCommitCount.size
            throw PaperweightException(
                "Exceeded the max amount of commits with the identifier: `${identifier.get()} File Patches`!\nGot $count commits, expected: 1"
            )
        }

        // these have to be retrieved dynamically
        val patchedBaseCommit = git("rev-list", "--grep=${identifier.get()} Base Patches", "--max-count=1", "base..HEAD").getText().trim()
        val fileCommit = git("rev-list", "--grep=${identifier.get()} File Patches", "--max-count=1", "base..HEAD").getText().trim()

        // we update the appropriate tags to reflect the new repo state
        git("tag", "-f", "patchedBase", patchedBaseCommit).executeSilently(silenceErr = true)
        git("tag", "-f", "file", fileCommit).executeSilently(silenceErr = true)

        val what = inputDir.path.name
        val patchFolder = patchDir.path
        if (!patchFolder.exists()) {
            patchFolder.createDirectories()
        }

        if (printOutput.get()) {
            logger.lifecycle("Formatting patches for $what...")
        }

        if (inputDir.path.resolve(".git/rebase-apply").exists()) {
            // in middle of a rebase, be smarter
            if (printOutput.get()) {
                logger.lifecycle("REBASE DETECTED - PARTIAL SAVE")
                val last = inputDir.path.resolve(".git/rebase-apply/last").readText().trim().toInt()
                val next = inputDir.path.resolve(".git/rebase-apply/next").readText().trim().toInt()
                val orderedFiles = patchFolder.useDirectoryEntries("*.patch") { it.toMutableList() }
                orderedFiles.sort()

                for (i in 1..last) {
                    if (i < next) {
                        orderedFiles[i].deleteForcefully()
                    }
                }
            }
        } else {
            patchFolder.deleteRecursive()
            patchFolder.createDirectories()
        }

        val base = "base"
        val stop = "patchedBase~1" // ~1 cuz we dont want to rebuild the marker commit
        val commitCount = git("rev-list", "--count", "$base..$stop").getText().trim().toInt()

        if (commitCount <= 0) return // nothing to rebuild

        val range = "$base..$stop"
        git("fetch", "--all", "--prune").runSilently(silenceErr = true)
        git(
            "format-patch",
            "--diff-algorithm=myers", "--zero-commit", "--full-index", "--no-signature", "--no-stat", "-N",
            "-o", patchFolder.absolutePathString(),
            range
        ).executeSilently()
        val patchDirGit = Git(patchFolder)
        patchDirGit("add", "-A", ".").executeSilently()

        if (filterPatches.get()) {
            cleanupPatches(patchDirGit)
        } else {
            if (printOutput.get()) {
                val saved = patchFolder.listDirectoryEntries("*.patch").size

                logger.lifecycle("Saved $saved patches for $what to ${layout.projectDirectory.path.relativize(patchFolder)}/")
            }
        }
    }

    private fun cleanupPatches(git: Git) {
        val patchFiles = patchDir.path.useDirectoryEntries("*.patch") { it.toMutableList() }
        if (patchFiles.isEmpty()) {
            return
        }
        patchFiles.sort()

        val noChangesPatches = ConcurrentLinkedQueue<Path>()
        val futures = mutableListOf<Future<*>>()

        // Calling out to git over and over again for each `git diff --staged` command is really slow from the JVM
        // so to mitigate this we do it parallel
        val executor = Executors.newWorkStealingPool()
        try {
            for (patch in patchFiles) {
                futures += executor.submit {
                    val hasNoChanges = git("diff", "--diff-algorithm=myers", "--staged", patch.name).getText().lineSequence()
                        .filter { it.startsWith('+') || it.startsWith('-') }
                        .filterNot { it.startsWith("+++") || it.startsWith("---") }
                        .all { it.startsWith("+index") || it.startsWith("-index") }

                    if (hasNoChanges) {
                        noChangesPatches.add(patch)
                    }
                }
            }

            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        if (noChangesPatches.isNotEmpty()) {
            for (chunk in noChangesPatches.chunked(50)) {
                git("reset", "HEAD", *chunk.map { it.name }.toTypedArray()).executeSilently()
                git("checkout", "--", *chunk.map { it.name }.toTypedArray()).executeSilently()
            }
        }

        if (printOutput.get()) {
            val saved = patchFiles.size - noChangesPatches.size
            val relDir = layout.projectDirectory.path.relativize(patchDir.path)
            logger.lifecycle("Saved modified patches ($saved/${patchFiles.size}) for ${inputDir.path.name} to $relDir/")
        }
    }
}
