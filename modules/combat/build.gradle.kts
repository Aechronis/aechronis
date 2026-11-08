plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(project(":modules:settings"))
    compileOnly(project(":modules:utils"))

    testImplementation(kotlin("test"))
    testImplementation(libs.minestom)
}

tasks.test {
    useJUnitPlatform()
}
