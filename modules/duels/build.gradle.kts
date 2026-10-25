plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:vanilla"))
}
