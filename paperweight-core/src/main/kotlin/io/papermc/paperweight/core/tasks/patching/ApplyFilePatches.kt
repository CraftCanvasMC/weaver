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
    abstract val output: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val input: DirectoryProperty

    @get:Internal
    abstract val baseRef: Property<String>

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
            baseRef.convention("basepatches")
        }
    }

    @TaskAction
    open fun run() {
        io.papermc.paperweight.util.Git.checkForGit()

        val input = input.pathOrNull
        val outputPath = output.path

        // special handling for resource patches
        if (input != null && baseRef.get() == "main") {
            recreateCloneDirectory(outputPath)
            checkoutRepoFromUpstream(
                Git(outputPath),
                input,
                "main",
                "upstream",
                baseRef.get(),
                false, // we need to set ref to false for patching to properly work
            )
            setupGitHook(outputPath)
            tagBase()
        } else {
            // rest of the original logic
            if (input != null && input.toAbsolutePath() != outputPath.toAbsolutePath()) {
                recreateCloneDirectory(outputPath)
                val checkoutFromJDs = hasJavadocs(input, "${identifier.get()}JDs")
                val newRef = if (checkoutFromJDs) "${identifier.get()}JDs" else baseRef.get()
                baseRef.set(newRef)

                val git = Git(outputPath.createDirectories())
                checkoutRepoFromUpstream(
                    git,
                    input,
                    baseRef.get(),
                    branchName = "main",
                    ref = true,
                )
            } else {
                val checkoutFromJDs = hasJavadocs(outputPath, "${identifier.get()}JDs")
                val newRef = if (checkoutFromJDs) "${identifier.get()}JDs" else baseRef.get()
                baseRef.set(newRef)
            }
            val git = Git(outputPath)
            if (git("checkout", "main").runSilently(silenceErr = true) != 0) {
                git("checkout", "-b", "main").runSilently(silenceErr = true)
            }
            git("reset", "--hard", baseRef.get()).runSilently(silenceErr = true)
            git("gc").runSilently(silenceErr = true)
        }

        val result = if (!patches.isPresent) {
            commit()
            0
        } else if (gitFilePatches.get() && shouldApplyWithGit(outputPath)) {
            applyWithGit(outputPath)
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

    private fun tagBase() {
        val git = Git.open(output.path.toFile())
        val ident = PersonIdent("base", "noreply+automated@papermc.io")
        git.tagDelete().setTags("base").call()
        git.tag().setName("base").setTagger(ident).setSigned(false).call()
        git.close()
    }

    private fun shouldApplyWithGit(outputPath: Path): Boolean {
        val patchFiles = patches.path.filesMatchingRecursive("*.patch")
        val git = Git(outputPath)
        val canApply = patchFiles.any { patch ->
            val result = git("apply", "--check", patch.absolutePathString()).getText(ignoreErr = true)
            result.contains("error: corrupt patch at line")
        }
        return !canApply
    }

    private fun applyWithGit(outputPath: Path): Int {
        val git = Git(outputPath)
        val patchFiles = patches.path.filesMatchingRecursive("*.patch")
        if (moveFailedGitPatchesToRejects.get() && rejectsDir.isPresent) {
            patchFiles.forEach { patch ->
                val patchPathFromGit = outputPath.relativize(patch)
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
                        if (outputPath.resolve(failedFile).exists()) {
                            git("reset", "--", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                            git("restore", failedFile.pathString).executeSilently(silenceOut = !verbose.get(), silenceErr = !verbose.get())
                        }

                        val rejectFile = rejectsDir.path.resolve(relativePatch)
                        patch.moveTo(rejectFile.createParentDirectories(), overwrite = true)
                    }
                }
            }
        } else {
            val patchStrings = patchFiles.map { outputPath.relativize(it).pathString }
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
            .baseInput(DiffInput.MultiInput.folder(output.path))
            .patchesInput(DiffInput.MultiInput.folder(patches.path))
            .patchedOutput(DiffOutput.MultiOutput.folder(output.path))
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

    private fun setupGitHook(outputPath: Path) {
        val hook = outputPath.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
    }

    private fun commit() {
        val ident = PersonIdent(PersonIdent("File", "noreply+automated@papermc.io"), Instant.parse("1997-04-20T13:37:42.69Z"))
        val git = Git.open(output.path.toFile())
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

    fun hasJavadocs(outputPath: Path, tag: String): Boolean {
        val git = Git(outputPath)
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
