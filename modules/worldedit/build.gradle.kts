plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(project(":modules:utils"))
    add("moduleApi", libs.worldedit)
    compileOnly(libs.guava)
}
