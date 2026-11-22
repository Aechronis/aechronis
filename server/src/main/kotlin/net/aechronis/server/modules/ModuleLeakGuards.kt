package net.aechronis.server.modules

import io.netty.util.concurrent.FastThreadLocal
import java.util.logging.LogManager
import javax.imageio.ImageIO

/**
 * JVM-wide state that outlives the module which first touched it. Each piece keeps a reference to a
 * module class or classloader, and one reference is enough to keep every class and static of that
 * module, and of its dependencies, alive after a reload.
 */
internal object ModuleLeakGuards {
    // Each of these captures the caller's context classloader, or a stack trace whose frames name the
    // caller's classes, the first time it is initialized. Initializing them from core keeps modules out.
    private val coreInitializedClasses =
        listOf(
            // Shutdown hook thread created on first database open.
            "org.h2.engine.OnExitDatabaseCloser",
            // Preallocated out-of-memory exceptions.
            "org.h2.message.DbException",
            "org.h2.message.TraceObject",
            // Cached causes for an unavailable Unsafe and native transport.
            "io.netty.util.internal.PlatformDependent",
            "io.netty.channel.epoll.Epoll",
        )

    fun install() {
        val core = ModuleLeakGuards::class.java.classLoader
        withContextClassLoader(core) {
            // Creates the main AppContext and its EventQueue, which keep the current context classloader.
            runCatching { ImageIO.getCacheDirectory() }
            coreInitializedClasses.forEach { name -> runCatching { Class.forName(name, true, core) } }
        }
    }

    /**
     * Removes the logging handlers and filters [loader] added to loggers that every module shares, and
     * clears Netty's thread-local caches on the lifecycle thread, which key handler types by class.
     */
    fun release(loader: ClassLoader) {
        FastThreadLocal.removeAll()
        val logging = LogManager.getLogManager()
        for (name in logging.loggerNames.toList()) {
            val logger = logging.getLogger(name) ?: continue
            // Not closed: a handler may wrap a stream the module doesn't own, such as System.err.
            logger.handlers.filter { it.javaClass.classLoader === loader }.forEach(logger::removeHandler)
            if (logger.filter?.javaClass?.classLoader === loader) logger.filter = null
        }
    }
}
