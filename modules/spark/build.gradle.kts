plugins {
    alias(libs.plugins.kotlin.jvm)
}

val sparkVersion = libs.versions.spark.get()

dependencies {
    compileOnly(libs.slf4j.api)
    add("moduleImplementation", libs.spark)
    // Upstream Spark expects its platform adapter to provide these libraries
    add("moduleImplementation", libs.guava)
    add("moduleImplementation", libs.gson)
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = sparkVersion
}
