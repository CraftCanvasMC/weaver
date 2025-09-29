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

package io.papermc.paperweight.core.tasks.patching

import io.codechicken.diffpatch.cli.PatchOperation
import io.codechicken.diffpatch.match.FuzzyLineMatcher
import io.codechicken.diffpatch.util.Input as DiffInput
import io.codechicken.diffpatch.util.Output as DiffOutput
import io.codechicken.diffpatch.util.PatchMode
import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.api.tasks.options.Option

abstract class ApplyFilePatches : BaseTask() {

    @get:Input
    @get:Option(
        option = "verbose",
        description = "Prints out more info about the patching process",
    )
    abstract val verbose: Property<Boolean>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Internal
    abstract val rejectsDir: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val gitFilePatches: Property<Boolean>

    @get:OutputDirectory
    abstract val repo: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val base: DirectoryProperty

    @get:Internal
    abstract val ref: Property<String>

    @get:Input
    @get:Optional
    abstract val identifier: Property<String>

    @get:Input
    abstract val moveFailedGitPatchesToRejects: Property<Boolean>

    @get:Internal
    abstract val emitRejects: Property<Boolean>

    init {
        run {
            verbose.convention(false)
            gitFilePatches.convention(false)
            moveFailedGitPatchesToRejects.convention(false)
            emitRejects.convention(true)
            ref.convention("basepatches")
        }
    }

    @TaskAction
    open fun run() {
        io.papermc.paperweight.util.Git.checkForGit()

        val base = base.pathOrNull

        if (base != null && base.toAbsolutePath() != repo.path.toAbsolutePath()) {
            recreateCloneDirectory(repo.path)
            val checkoutFromJDs = hasJavadocs(base, "${identifier.get()}JDs")
            val newRef = if (checkoutFromJDs) "${identifier.get()}JDs" else ref.get()
            ref.set(newRef)

            val git = Git(repo.path.createDirectories())
            checkoutRepoFromUpstream(
                git,
                base,
                ref.get(),
                branchName = "main",
                ref = true,
            )
        }

        val repoPath = repo.path
        val git = Git(repoPath)

        if (git("checkout", "main").runSilently(silenceErr = true) != 0) {
            git("checkout", "-b", "main").runSilently(silenceErr = true)
        }
        git("reset", "--hard", ref.get()).runSilently(silenceErr = true)
        git("gc").runSilently(silenceErr = true)

        val result = if (!patches.isPresent) {
            commit()
            0
        } else if (gitFilePatches.get() && shouldApplyWithGit(repoPath)) {
            applyWithGit(repoPath)
        } else {
            applyWithDiffPatch()
        }

        if (!verbose.get()) {
            logger.lifecycle("Applied $result patches")
        }
    }

    private fun recreateCloneDirectory(target: Path) {
        if (target.exists()) {
            if (target.resolve(".git").isDirectory()) {
                val git = Git(target)
                git("clean", "-fxd").runSilently(silenceErr = true)
                git("reset", "--hard", "HEAD").runSilently(silenceErr = true)
            } else {
                for (entry in target.listDirectoryEntries()) {
                    entry.deleteRecursive()
                }
                target.createDirectories()
            }
        } else {
            target.createDirectories()
        }
    }

    private fun shouldApplyWithGit(repoPath: Path): Boolean {
        val patchFiles = patches.path.filesMatchingRecursive("*.patch")
        val git = Git(repoPath)
        val canApply = patchFiles.any { patch ->
            val result = git("apply", "--check", patch.absolutePathString()).getText(ignoreErr = true)
            result.contains("error: corrupt patch at line")
        }
        return !canApply
    }

    private fun applyWithGit(repoPath: Path): Int {
        val git = Git(repoPath)
        val patchFiles = patches.path.filesMatchingRecursive("*.patch")
        if (moveFailedGitPatchesToRejects.get() && rejectsDir.isPresent) {
            patchFiles.forEach { patch ->
                val patchPathFromGit = repoPath.relativize(patch)
                val responseCode =
                    git(
                        "-c",
                        "rerere.enabled=false",
                        "apply",
                        "--3way",
                        patchPathFromGit.pathString
                    ).runSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                when {
                    responseCode == 0 -> {}
                    responseCode > 1 -> throw PaperweightException("Failed to apply patch $patch: $responseCode")
                    responseCode == 1 -> {
                        val relativePatch = patches.path.relativize(patch)
                        val failedFile = relativePatch.parent.resolve(relativePatch.fileName.toString().substringBeforeLast(".patch"))
                        if (repoPath.resolve(failedFile).exists()) {
                            git("reset", "--", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                            git("restore", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                        }

                        val rejectFile = rejectsDir.path.resolve(relativePatch)
                        patch.moveTo(rejectFile.createParentDirectories(), overwrite = true)
                    }
                }
            }
        } else {
            val patchStrings = patchFiles.map { repoPath.relativize(it).pathString }
            patchStrings.chunked(12).forEach {
                git("apply", "--3way", *it.toTypedArray()).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
            }
        }

        commit()

        return patchFiles.size
    }

    private fun applyWithDiffPatch(): Int? {
        val builder = PatchOperation.builder()
            .logTo(logger::lifecycle)
            .baseInput(DiffInput.MultiInput.folder(repo.path))
            .patchesInput(DiffInput.MultiInput.folder(patches.path))
            .patchedOutput(DiffOutput.MultiOutput.folder(repo.path))
            .level(if (verbose.get()) io.codechicken.diffpatch.util.LogLevel.ALL else io.codechicken.diffpatch.util.LogLevel.INFO)
            .mode(mode())
            .minFuzz(minFuzz())
            .summary(verbose.get())
            .lineEnding("\n")
            .ignorePrefix(".git")
        if (rejectsDir.isPresent && emitRejects.get()) {
            builder.rejectsOutput(DiffOutput.MultiOutput.folder(rejectsDir.path))
        }

        val result = builder.build().operate()

        commit()

        if (result.exit != 0) {
            val total = (result.summary?.failedMatches ?: 0) + (result.summary?.exactMatches ?: 0) +
                (result.summary?.accessMatches ?: 0) + (result.summary?.offsetMatches ?: 0) + (result.summary?.fuzzyMatches ?: 0)
            throw Exception("Failed to apply ${result.summary?.failedMatches}/$total hunks")
        }

        return result.summary?.changedFiles
    }

    private fun commit() {
        val ident = PersonIdent(PersonIdent("File", "noreply+automated@papermc.io"), Instant.parse("1997-04-20T13:37:42.69Z"))
        val git = Git.open(repo.path.toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("${identifier.get()} File Patches")
            .setAuthor(ident)
            .setAllowEmpty(true)
            .setSign(false)
            .call()
        git.tagDelete().setTags("file").call()
        git.tag().setName("file").setTagger(ident).setSigned(false).call()
        git.close()
    }

    fun hasJavadocs(repoPath: Path, tag: String): Boolean {
        val git = Git(repoPath)
        val result = git("tag", "-l", tag).getText().trim()
        return result == tag
    }

    internal open fun mode(): PatchMode {
        return PatchMode.OFFSET
    }

    internal open fun minFuzz(): Float {
        return FuzzyLineMatcher.DEFAULT_MIN_MATCH_SCORE
    }
}
