plugins {
    `kotlin-dsl` apply false
    id("com.diffplug.spotless") version "8.10.2" apply false
}

tasks.register("printVersion") {
    val ver = project.version
    doFirst {
        println(ver)
    }
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}
