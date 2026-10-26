plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.h2)
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
}
