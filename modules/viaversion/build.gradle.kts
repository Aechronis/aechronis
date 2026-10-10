plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    compileOnly(libs.grim) { isTransitive = false }
    add("moduleImplementation", libs.viaversion)
    add("moduleImplementation", libs.viabackwards)
    compileOnly(platform(libs.netty.bom))
    compileOnly(libs.netty.handler)
    add("moduleImplementation", libs.guava)
    compileOnly(libs.slf4j.api)
}
