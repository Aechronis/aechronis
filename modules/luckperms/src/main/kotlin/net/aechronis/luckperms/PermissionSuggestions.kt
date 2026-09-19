package net.aechronis.luckperms

import me.lucko.luckperms.common.treeview.PermissionRegistry
import me.lucko.luckperms.common.treeview.TreeNode
import net.aechronis.server.modules.ModulePermissions

/** Keeps module declarations in LuckPerms' completion tree without clearing unrelated nodes. */
internal class PermissionSuggestions(
    private val registry: PermissionRegistry,
) : AutoCloseable {
    private var previous = emptySet<String>()
    private val subscription = ModulePermissions.subscribe(::update)

    private fun update(permissions: Set<String>) {
        val retainedPaths =
            permissions
                .flatMap { permission ->
                    val parts = permission.split('.')
                    parts.indices.map { parts.take(it + 1).joinToString(".") }
                }.toSet()
        (previous - permissions).sortedByDescending { it.count { char -> char == '.' } }.forEach {
            remove(registry.rootNode, it.split('.'), 0, retainedPaths)
        }
        (permissions - previous).forEach(registry::insert)
        previous = permissions
    }

    private fun remove(
        parent: TreeNode,
        parts: List<String>,
        index: Int,
        retainedPaths: Set<String>,
    ) {
        val children = parent.children.orElse(null) ?: return
        val child = children[parts[index]] ?: return
        if (index < parts.lastIndex) remove(child, parts, index + 1, retainedPaths)
        val path = parts.take(index + 1).joinToString(".")
        if (child.childrenSize == 0 && path !in retainedPaths) children.remove(parts[index], child)
    }

    override fun close() = subscription.close()
}
