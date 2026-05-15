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

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.api.tasks.options.Option

@UntrackedTask(because = "Always fixup when requested")
abstract class CreateBasePatch : BaseTask() {

    @get:InputDirectory
    abstract val repo: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "message", description = "Commit message")
    abstract val message: Property<String>

    @get:Input
    @get:Optional
    @get:Option(option = "description", description = "Commit description")
    abstract val description: Property<String>

    @get:Input
    abstract val identifier: Property<String>

    @TaskAction
    fun run() {
        val git = Git(repo)
        if (message.isPresent) {
            val additionalArgs = if (description.isPresent) arrayOf("-m", description.get()) else arrayOf()
            git("add", ".").executeOut()
            git("commit", "-m", message.get(), *additionalArgs).executeOut()
        }
        val baseCommit = git("rev-parse", "basepatches").getText().trim()
        val headCommit = git("rev-parse", "HEAD").getText().trim()
        git("branch", "-f", "fixup/basepatches").executeOut()
        git("reset", "basepatches~1", "--hard").executeOut()
        git("cherry-pick", headCommit).executeOut()
        git("cherry-pick", "$baseCommit~1..$headCommit~1", "--keep-redundant-commits").executeOut()
        git("switch", "-C", "main", "HEAD").executeOut()
        git("branch", "-D", "fixup/basepatches").executeOut()
        tagCommits(git)
    }

    private fun tagCommits(git: Git) {
        val baseCommit = git(
            "log",
            "--format=%H %s",
            "--grep=^${identifier.get()} Base Patches$",
            "base..HEAD"
        ).getText()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.substringBefore(" ") }
            .toList()

        val fileCommit = git(
            "log",
            "--format=%H %s",
            "--grep=^${identifier.get()} File Patches$",
            "base..HEAD"
        ).getText()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { it.substringBefore(" ") }
            .toList()

        // throw if false, since that means the repo state is corrupted
        validateSingleCommit(identifier, "Base", baseCommit)
        validateSingleOrNullCommit(identifier, "File", fileCommit)

        // retag everything
        git("tag", "-f", "basepatches", baseCommit.joinToString()).executeOut()
        if (fileCommit.size == 1) {
            git("tag", "-f", "file", fileCommit.joinToString()).executeOut()
        }
    }
}
