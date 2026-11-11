plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:settings"))
}
