package com.topjohnwu.magisk.utils

import com.topjohnwu.superuser.internal.UiThreadHandler
import java.io.FilterInputStream
import java.io.InputStream

class ProgInputStream(
    base: InputStream,
    val progressEmitter: (Long) -> Unit = {}
) : FilterInputStream(base) {

    private var bytesRead = 0L
    private var lastUpdate = 0L

    private fun emitProgress() {
        val cur = System.currentTimeMillis()
        if (cur - lastUpdate > 1000) {
            lastUpdate = cur
            UiThreadHandler.run { progressEmitter(bytesRead) }
        }
    }

    override fun read(): Int {
        val b = read()
        if (b >= 0) {
            bytesRead++
            emitProgress()
        }
        return b
    }

    override fun read(b: ByteArray): Int {
        return read(b, 0, b.size)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val sz = super.read(b, off, len)
        if (sz > 0) {
            bytesRead += sz
            emitProgress()
        }
        return sz
    }
}