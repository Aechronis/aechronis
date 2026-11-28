plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(project(":modules:economy"))
    compileOnly(project(":modules:utils"))
}
