package net.aechronis.utils.utils

import kotlin.math.ceil
import kotlin.math.max

class PagedList<T>(list: List<T>, private val itemsPerPage: Int, private val extra: Int = 0) {
    val list: List<T> = ArrayList(list)

    val totalPages: Int
        get() = max(1.0, ceil((list.size + extra).toDouble() / itemsPerPage)).toInt()

    fun getPage(page: Int): List<T> {
        val p = if (itemsPerPage <= 0 || page <= 0) 1 else page
        val fromIndex = (p - 1) * itemsPerPage
        if (list.size < fromIndex) return emptyList()
        return list.subList(fromIndex, minOf(fromIndex + itemsPerPage, list.size))
    }
}
