plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:staff"))
    compileOnly(libs.luckperms.api)
    compileOnly("org.checkerframework:checker-qual:3.42.0")
}
