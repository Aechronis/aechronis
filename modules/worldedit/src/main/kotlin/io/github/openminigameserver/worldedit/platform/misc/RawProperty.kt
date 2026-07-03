package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.registry.state.Property

class RawProperty(
    val propName: String,
    val propValues: MutableList<String>,
) : Property<Any> {
    override fun name(): String = propName

    override fun values(): MutableList<String> = propValues

    override fun getValueFor(string: String?): Any? = string
}
