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
import kotlin.io.path.*
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.newInstance

@UntrackedTask(because = "PrepareForPatchGeneration should always run when requested")
abstract class PrepareForPatchGeneration : JavaLauncherTask() {

    @get:InputDirectory
    abstract val workDir: DirectoryProperty

    @get:Input
    abstract val upstreamName: Property<String>

    @get:Input
    abstract val repoName: Property<String>

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance<ApplySourceATs>().apply {
        jst.from(project.configurations.named(JST_CONFIG))
    }

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:InputFile
    @get:Optional
    abstract val additionalPatch: RegularFileProperty

    @TaskAction
    fun run() {
        val name = if (repoName.get().equals("minecraft")) "${upstreamName.get()}-server/src/minecraft/java" else repoName.get()
        val outputDir = workDir.path.resolve("${upstreamName.get()}/$name")

        if (additionalPatch.isPresent) {
            val git = Git(outputDir)
            git("am", "--3way", "--ignore-whitespace", additionalPatch.path.toString()).captureOut(false)
        }

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            ats.run(
                launcher.get(),
                outputDir,
                outputDir,
                atFile.path,
                temporaryDir.toPath()
            )
        }
    }
}
