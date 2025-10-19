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
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.gradle.kotlin.dsl.*
import org.junit.jupiter.api.io.TempDir

class ApplyFilePatchesTest : TaskTest() {
    private lateinit var task: ApplyBasePatches
    private lateinit var task2: ApplyFilePatches

    @BeforeTest
    fun setup() {
        val project = setupProject()
        task = project.tasks.register("applyBasePatches", ApplyBasePatches::class).get()
        task2 = project.tasks.register("applyPatches", ApplyFilePatches::class) {
            dependsOn(task)
        }
            .get()
    }

    @Test
    fun `should apply patches`(@TempDir tempDir: Path) {
        val testResource = Path.of("src/test/resources/apply_patches")
        val testInput = testResource.resolve("input")

        val input = setupDir(tempDir, testInput, "base").toFile()
        val output = tempDir.resolve("source").toFile()
        val patches = testInput.resolve("patches").toFile()

        setupGitRepo(input, "main")

        task.input.set(input)
        task.output.set(output)
        task.identifier.set("test")

        task2.base.set(task.output)
        task2.repo.set(output)
        task2.patches.set(patches)
        task2.verbose.set(true)
        task2.identifier.set("test")

        task.run()
        task2.run()

        val testOutput = testResource.resolve("output")
        compareDir(tempDir, testOutput, "source")
    }
}
