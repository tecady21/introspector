package com.boyz.introspector.data.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

object DemoValue {
    // Direct buffer lives in native memory (outside JVM heap) at a stable address.
    // scanmem can find and overwrite it; getInt() reflects the patch immediately.
    private val buffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())

    init { buffer.putInt(0, nextRandom()) }

    val current: Int get() = buffer.getInt(0)

    fun randomize(): Int {
        val v = nextRandom()
        buffer.putInt(0, v)
        return v
    }

    private fun nextRandom() = (10_000..99_999).random()
}
