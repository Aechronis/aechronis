plugins {
    alias(libs.plugins.kotlin.jvm)
}

base {
    archivesName.set("a-new-millenium")
}

dependencies {
    compileOnly(project(":modules:utils"))
    compileOnly(project(":modules:combat"))
    compileOnly(project(":modules:vanilla"))
    compileOnly(project(":modules:worldedit"))
    compileOnly(project(":modules:nodes"))
    compileOnly(project(":modules:logger"))
    compileOnly(project(":modules:guard"))
    compileOnly(project(":modules:gems"))
}
