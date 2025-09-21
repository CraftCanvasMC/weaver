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

import io.papermc.paperweight.core.util.ApplyJavadocMappings
import io.papermc.paperweight.core.util.ApplySourceATs
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import kotlin.io.path.*
import org.eclipse.jgit.api.Git
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.*

abstract class SetupForkMinecraftSources : JavaLauncherTask() {

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Nested
    val ats: ApplySourceATs = objects.newInstance()

    @get:Nested
    val mapping: ApplyJavadocMappings = objects.newInstance()

    @get:InputFile
    @get:Optional
    abstract val atFile: RegularFileProperty

    @get:PathSensitive(PathSensitivity.NONE)
    @get:InputFiles
    @get:Optional
    abstract val mappingFile: ConfigurableFileCollection

    @get:Optional
    @get:InputDirectory
    abstract val libraryImports: DirectoryProperty

    @get:Input
    abstract val identifier: Property<String>

    @TaskAction
    fun run() {
        val out = outputDir.path.cleanDir()
        inputDir.path.copyRecursivelyTo(out)

        val git = Git.open(outputDir.path.toFile())

        if (libraryImports.isPresent) {
            libraryImports.path.walk().forEach {
                val outFile = out.resolve(it.relativeTo(libraryImports.path).invariantSeparatorsPathString)
                // The file may already exist if upstream imported it
                if (!outFile.exists()) {
                    it.copyTo(outFile.createParentDirectories())
                }
            }
            commitAndTag(git, "Imports", "${identifier.get()} Imports")
        }

        if (atFile.isPresent && atFile.path.readText().isNotBlank()) {
            println("Applying access transformers...")
            ats.run(
                launcher.get(),
                outputDir.path,
                outputDir.path,
                atFile.path,
                temporaryDir.toPath(),
            )
            commitAndTag(git, "ATs", "${identifier.get()} ATs")
        }
        if (!mappingFile.isEmpty) {
            println("Applying javadoc mappings...")
            mapping.run(
                launcher.get(),
                inputDir.path,
                outputDir.path,
                mappingFile.singleFile.toPath(),
                temporaryDir.toPath(),
            )
            commitAndTag(git, "JDs", "${identifier.get()} Javadoc Mappings")
        }

        git.close()
    }
}
