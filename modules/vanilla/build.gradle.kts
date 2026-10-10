plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>("moduleImplementation", libs.cronutils) {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    add("moduleImplementation", libs.profanity.filter)
    compileOnly(libs.blocksandstuff.blocks)
    compileOnly(libs.blocksandstuff.common)
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
}
