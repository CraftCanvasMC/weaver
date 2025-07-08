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

import io.papermc.paperweight.PaperweightException
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.Git
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*
import org.eclipse.jgit.api.Git as JGit
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.URIish
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class ApplyBaseFeaturePatches : ControllableOutputTask() {

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    abstract val input: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    abstract val base: DirectoryProperty

    @get:Optional
    @get:Input
    abstract val baseRef: Property<String>

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputDirectory
    @get:Optional
    abstract val patches: DirectoryProperty

    @get:Input
    abstract val verbose: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val identifier: Property<String>

    // An additional remote to add and fetch from before applying patches (to bring in objects for 3-way merge).
    @get:Input
    @get:Optional
    abstract val additionalRemote: Property<String>

    @get:Input
    abstract val additionalRemoteName: Property<String>

    override fun init() {
        printOutput.convention(false).finalizeValueOnRead()
        additionalRemoteName.convention("old")
        verbose.convention(false)
    }

    @TaskAction
    fun run() {
        Git.checkForGit()

        val outputPath = output.path
        recreateCloneDirectory(outputPath)

        val git = Git(outputPath)

        checkoutRepoFromUpstream(
            Git(outputPath),
            input.path,
            baseRef.getOrElse("patchedBase"),
            "upstream",
            "patchedBase",
            baseRef.isPresent,
        )
        if (additionalRemote.isPresent) {
            val jgit = JGit.open(outputPath.toFile())
            jgit.remoteRemove().setRemoteName(additionalRemoteName.get()).call()
            jgit.remoteAdd().setName(additionalRemoteName.get()).setUri(URIish(additionalRemote.get())).call()
            jgit.fetch().setRemote(additionalRemoteName.get()).call()
        }

        setupGitHook(outputPath)

        tagBase()

        if (!patches.isPresent) {
        } else {
            applyGitPatches(git, "server repo", outputPath, patches.path, printOutput.get(), verbose.get())
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
        val git = JGit.open(output.path.toFile())
        val ident = PersonIdent("base", "noreply+automated@papermc.io")
        git.tagDelete().setTags("base").call()
        git.tag().setName("base").setTagger(ident).setSigned(false).call()
        git.close()
    }

    private fun setupGitHook(outputPath: Path) {
        val hook = outputPath.resolve(".git/hooks/post-rewrite")
        hook.parent.createDirectories()
        hook.writeText(javaClass.getResource("/post-rewrite.sh")!!.readText())
        hook.toFile().setExecutable(true)
    }

    private fun commit() {
        val ident = PersonIdent(PersonIdent("Patched Base", "noreply+automated@papermc.io"), Instant.parse("1997-04-20T13:37:42.69Z"))
        val git = JGit.open(output.path.toFile())
        git.add().addFilepattern(".").call()
        git.commit()
            .setMessage("${identifier.get()} Base Patches")
            .setAuthor(ident)
            .setAllowEmpty(true)
            .setSign(false)
            .call()
        git.tagDelete().setTags("patchedBase").call()
        git.tag().setName("patchedBase").setTagger(ident).setSigned(false).call()
        git.close()
    }

    private fun applyGitPatches(
        git: Git,
        target: String,
        outputDir: Path,
        patchDir: Path?,
        printOutput: Boolean,
        verbose: Boolean,
    ) {
        if (printOutput) {
            logger.lifecycle("Applying patches to $target...")
        }

        val statusFile = outputDir.resolve(".git/patch-apply-failed")
        statusFile.deleteForcefully()

        git("am", "--abort").runSilently(silenceErr = true)

        val patches = patchDir?.useDirectoryEntries("*.patch") { it.toMutableList() } ?: mutableListOf()
        if (patches.isEmpty()) {
            if (printOutput) {
                logger.lifecycle("No patches found")
            }
            return
        }

        // This prevents the `git am` command line from getting too big with too many patches
        // mostly an issue with Windows
        layout.cache.createDirectories()
        val tempDir = createTempDirectory(layout.cache, "paperweight")
        try {
            val mailDir = tempDir.resolve("new")
            mailDir.createDirectories()

            for (patch in patches) {
                patch.copyTo(mailDir.resolve(patch.fileName))
            }

            val gitOut = printOutput && verbose
            val result = git("am", "--3way", "--ignore-whitespace", tempDir.absolutePathString()).captureOut(gitOut)
            if (result.exit != 0) {
                statusFile.writeText("1")

                if (!gitOut) {
                    // Log the output anyway on failure
                    logger.lifecycle(result.out)
                }
                logger.error("***   Please review above details and finish the apply then")
                logger.error("***   save the changes with `./gradlew rebuildPatches`")

                throw PaperweightException("Failed to apply patches")
            } else {
                statusFile.deleteForcefully()
                if (printOutput) {
                    logger.lifecycle("${patches.size} patches applied cleanly to $target")
                }
            }
        } finally {
            tempDir.deleteRecursive()
        }

        commit()
    }
}
