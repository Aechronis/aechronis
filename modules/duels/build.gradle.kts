plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(libs.kotlinx.serialization.json)
}
