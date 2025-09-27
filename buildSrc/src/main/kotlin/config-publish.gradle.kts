import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.gradleup.shadow")
    id("com.gradle.plugin-publish")
}

val noRelocate = project.hasProperty("disable-relocation")

val shade: Configuration by configurations.creating
configurations.implementation {
    extendsFrom(shade)
}

configurations.shadowRuntimeElements {
    compatibilityAttributes(objects)
}

fun ShadowJar.configureStandard() {
    configurations = listOf(shade)

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*:.*"))
        exclude(dependency("org.slf4j:.*:.*"))
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "OSGI-INF/**", "*.profile", "module-info.class", "ant_tasks/**", "OSGI-OPT/**", "META-INF/*.pro")

    mergeServiceFiles()
}

val sourcesJar by tasks.existing(AbstractArchiveTask::class) {
    from(
        zipTree(project(":paperweight-lib").tasks
            .named("sourcesJar", AbstractArchiveTask::class)
            .flatMap { it.archiveFile })
    ) {
        exclude("META-INF/**")
    }
}

gradlePlugin {
    website.set("https://github.com/CraftCanvasMC/weaver/")
    vcsUrl.set("https://github.com/CraftCanvasMC/weaver/")
}

val shadowJar by tasks.existing(ShadowJar::class) {
    archiveClassifier.set(null as String?)
    configureStandard()

    inputs.property("noRelocate", noRelocate)
    if (noRelocate) {
        return@existing
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
        "net.fabricmc",
        "org.apache.http",
        /* -> */ "org.apache.commons",
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
                username=System.getenv("PUBLISH_USER")
                password=System.getenv("PUBLISH_TOKEN")
            }
        }
        maven("https://maven.canvasmc.io/releases") {
            name = "CanvasMC_Releases"
            credentials {
                username=System.getenv("PUBLISH_USER")
                password=System.getenv("PUBLISH_TOKEN")
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
