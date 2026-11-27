package io.github.krax1337.gsync.protocol

/**
 * Feature ids of the Casio "all features" protocol.
 *
 * Every request, response and write starts with one of these bytes. Protocol knowledge is ported
 * from izivkov/GShockAPI (MIT), which in turn builds on Gadgetbridge.
 */
object Feature {
    const val TIME = 0x09
    const val TRIGGER = 0x10
    const val AUTO_SYNC = 0x11
    const val SETTINGS = 0x13
    const val ALARM = 0x15
    const val ALARMS = 0x16
    const val TIMER = 0x18
    const val DST_STATE = 0x1D
    const val ZONE = 0x1E
    const val CITY_NAME = 0x1F
    const val APP_INFO = 0x22
    const val HOME_TIME = 0x24
    const val CONDITION = 0x28
    const val ERROR = 0xFF
}

class ProtocolException(message: String) : Exception(message)

fun frameOf(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

/** Unsigned byte at [index]. */
fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

fun String.hexToFrame(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun ByteArray.requireFrame(feature: Int, minSize: Int): ByteArray {
    if (size < minSize || u(0) != feature) {
        throw ProtocolException("Expected frame %02X of at least %d bytes, got %s".format(feature, minSize, toHex()))
    }
    return this
}
