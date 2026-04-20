package net.aechronis.library

object Main {
    fun init() {
        // measure load time
        val timeStart = System.currentTimeMillis()

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("Enabled in ${timeLoad}ms")
    }
}
