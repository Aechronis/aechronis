plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.blocksandstuff.blocks)
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
}
