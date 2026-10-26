plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.blocksandstuff.blocks)
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:economy"))
    compileOnly(project(":modules:regenerating-ores"))
    compileOnly(project(":modules:stats"))
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
}
