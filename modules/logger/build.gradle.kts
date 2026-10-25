plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))

    compileOnly(libs.h2)
    compileOnly(libs.hikari)
}
