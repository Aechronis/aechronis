plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:worldedit"))
    compileOnly(project(":modules:combat"))
    compileOnly(libs.kotlinx.serialization.json)
}
