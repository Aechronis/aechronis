plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

tasks.processResources {
    from("LICENSE") {
        into("META-INF/licenses/votifier")
    }
}

dependencies {
    compileOnly(project(":modules:gems"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(libs.kotlinx.serialization.json)
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("moduleImplementation", libs.nuvotifier.api) {
        exclude(group = "io.netty")
    }
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("moduleImplementation", libs.nuvotifier.common) {
        exclude(group = "io.netty")
    }
    compileOnly(libs.netty.handler.versioned)
}
