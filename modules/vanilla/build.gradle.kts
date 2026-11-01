plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.blocksandstuff.blocks)
    compileOnly(libs.blocksandstuff.common)
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
}
