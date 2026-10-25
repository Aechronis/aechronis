plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    add("moduleImplementation", libs.profanity.filter)
    compileOnly(libs.blocksandstuff.blocks)
    compileOnly(libs.blocksandstuff.common)
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
}
