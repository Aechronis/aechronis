package net.aechronis.server.modules

import java.net.URL
import java.net.URLClassLoader
import java.util.Collections
import java.util.Enumeration

/** Core API identity is shared; module code can see only its declared dependency graph. */
internal class ModuleClassLoader(
    jar: URL,
    private val dependencies: List<ModuleClassLoader>,
) : URLClassLoader(arrayOf(jar), AechronisModule::class.java.classLoader) {
    override fun loadClass(
        name: String,
        resolve: Boolean,
    ): Class<*> =
        synchronized(getClassLoadingLock(name)) {
            val type =
                findLoadedClass(name) ?: run {
                    try {
                        parent.loadClass(name)
                    } catch (_: ClassNotFoundException) {
                        dependencies.firstNotNullOfOrNull { dependency ->
                            try {
                                dependency.loadClass(name)
                            } catch (_: ClassNotFoundException) {
                                null
                            }
                        } ?: findClass(name)
                    }
                }
            if (resolve && type.classLoader === this) resolveClass(type)
            type
        }

    override fun getResource(name: String): URL? =
        parent.getResource(name) ?: findResource(name) ?: dependencies.firstNotNullOfOrNull { it.getResource(name) }

    override fun getResources(name: String): Enumeration<URL> {
        val resources =
            parent.getResources(name).toList() + findResources(name).toList() +
                dependencies.flatMap { it.getResources(name).toList() }
        return Collections.enumeration(resources.distinct())
    }
}
