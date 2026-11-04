package com.kieronquinn.app.ambientmusicmod.utils.extensions

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Big-endian, which the engine's ProxyAudioRecord decodes */
fun ShortArray.toByteArray(): ByteArray {
    return ByteBuffer.allocate(size * 2).order(ByteOrder.BIG_ENDIAN).apply {
        asShortBuffer().put(this@toByteArray)
    }.array()
}

fun ShortArray.applyGain(gain: Float): ShortArray {
    if (isNotEmpty()) {
        for (i in indices) {
            this[i] = (this[i] * gain).toInt().coerceAtMost(Short.MAX_VALUE.toInt()).toShort()
        }
    }
    return this
}