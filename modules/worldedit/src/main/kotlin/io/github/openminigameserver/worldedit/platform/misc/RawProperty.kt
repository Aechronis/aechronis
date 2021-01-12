package io.github.openminigameserver.worldedit.platform.misc

import com.sk89q.worldedit.registry.state.Property

class RawProperty(val propName: String, val propValues: MutableList<String>) : Property<Any> {
    override fun getName(): String {
        return propName
    }

    override fun getValues(): MutableList<String> {
        return propValues
    }

    override fun getValueFor(string: String?): Any? {
        return string
    }

}