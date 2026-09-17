import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType

plugins {
    id("com.gradleup.shadow")
    id("com.gradle.plugin-publish")
}

fun version(): String = version.toString()
val noRelocate = project.hasProperty("disable-relocation")
if (noRelocate) {
    if (version().contains("-SNAPSHOT")) {
        version = version().substringBefore("-SNAPSHOT") + "-NO-RELOCATE-SNAPSHOT"
    } else {
        version = version() + "-NO-RELOCATE"
    }
}

val sourcesJar = configurations.dependencyScope("sourcesJar")
val sourcesJarResolvable = configurations.resolvable("sourcesJarResolvable") {
    extendsFrom(sourcesJar)
}

dependencies {
    sourcesJar(project(":paperweight-lib", "sourcesJar"))
}

val shade = configurations.dependencyScope("shade")
val shadeResolvable = configurations.resolvable("shadeResolvable") {
    extendsFrom(shade)
}

configurations.implementation {
    extendsFrom(shade)
}

configurations.shadowRuntimeElements {
    compatibilityAttributes()
}

fun ShadowJar.configureStandard() {
    configurations.setFrom(listOf(shadeResolvable))
    filesMatching("META-INF/**") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
        exclude(dependency("org.slf4j:.*:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "OSGI-INF/**", "*.profile", "module-info.class", "ant_tasks/**", "OSGI-OPT/**", "META-INF/*.pro")

    mergeServiceFiles()
}

private fun SetProperty<Configuration>.setFrom(configurations: List<NamedDomainObjectProvider<out Configuration>>) {
    empty()
    configurations.forEach { add(it) }
}

val libSourcesJar = tasks.named<AbstractArchiveTask>("sourcesJar") {
    from(zipTree(sourcesJarResolvable.map { it.singleFile })) {
        exclude("META-INF/**")
    }
}

gradlePlugin {
    website.set("https://github.com/CraftCanvasMC/weaver/")
    vcsUrl.set("https://github.com/CraftCanvasMC/weaver/")
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configureStandard()

    inputs.property("noRelocate", noRelocate)
    if (noRelocate) {
        return@named
    }

    val prefix = "paper.libs"
    listOf(
        "io.codechicken.diffpatch",
        /* -> */ "io.codechicken.repack",
        "com.github.salomonbrys.kotson",
        "com.google.gson",
        "dev.denwav.hypo",
        /* -> */ "org.jgrapht",
        /* -> */ "org.jheaps",
        /* -> */ "com.google.errorprone.annotations",
        /* -> */ "org.objectweb.asm",
        "io.sigpipe.jbsdiff",
        /* -> */ "org.tukaani.xz",
        /* -> */ "org.apache.commons",
        "net.fabricmc",
        "org.apache.hc",
        "org.cadixdev",
        /* -> */ "me.jamiemansfield",
        "org.eclipse.jgit",
        /* -> */ "com.googlecode.javaewah",
        /* -> */ "com.googlecode.javaewah32",
        "kotlinx.coroutines",
        //"org.slf4j",
        // used by multiple
        "org.intellij.lang",
        "org.jetbrains.annotations"
    ).forEach { pack ->
        relocate(pack, "$prefix.$pack")
    }
}

publishing {
    repositories {
        maven("https://maven.canvasmc.io/snapshots") {
            name = "CanvasMC_Snapshots"
            credentials {
                username = providers.environmentVariable("PUBLISH_USER").orNull
                password = providers.environmentVariable("PUBLISH_TOKEN").orNull
            }
        }
        maven("https://maven.canvasmc.io/releases") {
            name = "CanvasMC_Releases"
            credentials {
                username = providers.environmentVariable("PUBLISH_USER").orNull
                password = providers.environmentVariable("PUBLISH_TOKEN").orNull
            }
        }
    }

    publications {
        withType(MavenPublication::class).configureEach {
            pom {
                pomConfig()
            }
        }
    }
}

fun MavenPom.pomConfig() {
    val repoPath = "CraftCanvasMC/weaver"
    val repoUrl = "https://github.com/$repoPath"

    name.set("weaver")
    description.set("Gradle plugin for the CanvasMC project")
    url.set(repoUrl)
    inceptionYear.set("2025")

    licenses {
        license {
            name.set("LGPLv2.1")
            url.set("$repoUrl/blob/master/license/LGPLv2.1.txt")
            distribution.set("repo")
        }
    }

    issueManagement {
        system.set("GitHub")
        url.set("$repoUrl/issues")
    }

    developers {
        developer {
            id.set("CanvasMC")
            name.set("Canvas")
            url.set("https://github.com/CraftCanvasMC")
        }
    }

    scm {
        url.set(repoUrl)
        connection.set("scm:git:$repoUrl.git")
        developerConnection.set("scm:git:git@github.com:$repoPath.git")
    }
}
