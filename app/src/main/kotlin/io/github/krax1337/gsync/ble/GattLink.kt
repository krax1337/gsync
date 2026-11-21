package io.github.krax1337.gsync.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import io.github.krax1337.gsync.protocol.Feature
import io.github.krax1337.gsync.protocol.ProtocolException
import io.github.krax1337.gsync.protocol.toHex
import io.github.krax1337.gsync.protocol.u
import io.github.krax1337.gsync.watch.WatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** The link went away, or never came up. */
class LinkLostException(message: String) : IOException(message)

/**
 * GATT connection to the watch's "all features" service.
 *
 * Two pairs of characteristics, each a request side and a data side:
 * - features: requests go to one characteristic, the answer is a notification on the other, which
 *   also takes the writes;
 * - clock blocks: the same arrangement for the large time tables, whose answers arrive in pieces.
 *   Only watches that keep their clock in blocks have this pair; the others are served without it.
 *
 * One operation is in flight at a time.
 */
@SuppressLint("MissingPermission") // The service only runs once Bluetooth permissions are granted.
class GattLink private constructor() : WatchLink, AutoCloseable {
    private lateinit var gatt: BluetoothGatt
    private lateinit var requestChar: BluetoothGattCharacteristic
    private lateinit var featureChar: BluetoothGattCharacteristic
    private var blockRequestChar: BluetoothGattCharacteristic? = null
    private var blockChar: BluetoothGattCharacteristic? = null

    private val ready = CompletableDeferred<Unit>()
    private val lost = CompletableDeferred<String>()
    private val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    private val blockPieces = Channel<ByteArray>(Channel.UNLIMITED)
    private val writeAcks = Channel<Int>(Channel.UNLIMITED)
    private val operation = Mutex()

    /** Characteristics still waiting to have their notifications switched on. */
    private val toSubscribe = ArrayDeque<BluetoothGattCharacteristic>()

    override suspend fun request(vararg request: Int): ByteArray = operation.withLock {
        notifications.drain() // Anything the watch sent unasked.
        val bytes = ByteArray(request.size) { request[it].toByte() }
        send(requestChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { awaitResponse(bytes) }
            ?: throw LinkLostException("No answer to request ${bytes.toHex()}")
    }

    override suspend fun write(frame: ByteArray) = operation.withLock {
        send(featureChar, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    override suspend fun requestBlock(request: ByteArray, size: Int): ByteArray = operation.withLock {
        val characteristic = blockRequestChar ?: throw ProtocolException(NO_BLOCKS)
        blockPieces.drain()
        send(characteristic, request, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        withTimeoutOrNull(BLOCK_TIMEOUT_MS) {
            var block = ByteArray(0)
            while (block.size < size) block += blockPieces.receive()
            block
        } ?: throw LinkLostException("Incomplete answer to block request ${request.toHex()}")
    }

    override suspend fun writeBlock(block: ByteArray) = operation.withLock {
        send(blockChar ?: throw ProtocolException(NO_BLOCKS), block, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    /** Suspends until the connection ends and returns why. */
    suspend fun awaitLost(): String = lost.await()

    fun disconnect() = gatt.disconnect()

    override fun close() {
        fail("Closed")
        gatt.disconnect()
        gatt.close()
    }

    private suspend fun awaitResponse(request: ByteArray): ByteArray {
        while (true) {
            val frame = notifications.receive()
            if (frame.startsWith(request)) return frame
            if (frame.isNotEmpty() && frame.u(0) == Feature.ERROR) {
                throw ProtocolException("Watch rejected request ${request.toHex()}: ${frame.toHex()}")
            }
        }
    }

    private suspend fun send(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, writeType: Int) {
        val started = gatt.writeCharacteristic(characteristic, bytes, writeType)
        if (started != BluetoothStatusCodes.SUCCESS) throw LinkLostException("Write refused by Bluetooth stack ($started)")
        val status = withTimeoutOrNull(BLOCK_TIMEOUT_MS) { writeAcks.receive() }
            ?: throw LinkLostException("Write of ${bytes.size} bytes was not acknowledged")
        if (status != BluetoothGatt.GATT_SUCCESS) throw ProtocolException("Watch refused ${bytes.take(2).toByteArray().toHex()}… (status $status)")
    }

    private fun fail(reason: String) {
        if (!lost.complete(reason)) return
        val cause = LinkLostException(reason)
        ready.completeExceptionally(cause)
        notifications.close(cause)
        blockPieces.close(cause)
        writeAcks.close(cause)
    }

    private fun subscribeNext(gatt: BluetoothGatt) {
        val characteristic = toSubscribe.removeFirstOrNull()
        if (characteristic == null) {
            // The clock blocks are up to 133 bytes: with the default 23-byte MTU every write of one
            // would take a dozen round trips.
            if (!gatt.requestMtu(MTU)) ready.complete(Unit)
            return
        }
        val config = characteristic.getDescriptor(CLIENT_CONFIG)
        gatt.setCharacteristicNotification(characteristic, true)
        val started = config?.let { gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
        if (started != BluetoothStatusCodes.SUCCESS) fail("Could not subscribe to the watch ($started)")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Short connection interval: the whole exchange takes about a second, and the
                // time write lands within a few milliseconds of when it was issued.
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.discoverServices()) fail("Service discovery did not start")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("Disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE)
            val request = service?.getCharacteristic(REQUEST)
            val feature = service?.getCharacteristic(FEATURE)
            if (status != BluetoothGatt.GATT_SUCCESS || request == null || feature == null) {
                return fail("Not a supported Casio watch (status $status)")
            }
            requestChar = request
            featureChar = feature
            blockRequestChar = service.getCharacteristic(BLOCK_REQUEST)
            blockChar = service.getCharacteristic(BLOCK)
            toSubscribe += feature
            blockChar?.let { toSubscribe += it }
            subscribeNext(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) subscribeNext(gatt) else fail("Could not subscribe to the watch (status $status)")
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // A refused MTU is not fatal: Android then splits long writes itself.
            ready.complete(Unit)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                FEATURE -> notifications.trySend(value)
                BLOCK -> blockPieces.trySend(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeAcks.trySend(status)
        }
    }

    companion object {
        private val SERVICE: UUID = UUID.fromString("26eb000d-b012-49a8-b1f8-394fb2032b0f")
        private val REQUEST: UUID = UUID.fromString("26eb002c-b012-49a8-b1f8-394fb2032b0f")
        private val FEATURE: UUID = UUID.fromString("26eb002d-b012-49a8-b1f8-394fb2032b0f")
        private val BLOCK_REQUEST: UUID = UUID.fromString("26eb002e-b012-49a8-b1f8-394fb2032b0f")
        private val BLOCK: UUID = UUID.fromString("26eb002f-b012-49a8-b1f8-394fb2032b0f")
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MTU = 256
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val RESPONSE_TIMEOUT_MS = 3_000L
        private const val BLOCK_TIMEOUT_MS = 5_000L
        private const val NO_BLOCKS = "This watch has no clock block characteristics"

        /** Connects to an advertising watch and returns once it is ready for requests. */
        suspend fun open(context: Context, device: BluetoothDevice): GattLink {
            val link = GattLink()
            // The replacement (BluetoothGattConnectionSettings) only exists from API 37.
            @Suppress("DEPRECATION")
            link.gatt = device.connectGatt(context, false, link.callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { link.ready.await() }
                    ?: throw LinkLostException("Connection timed out")
            } catch (e: Throwable) {
                link.close()
                throw e
            }
            return link
        }
    }
}

private fun Channel<ByteArray>.drain() {
    while (tryReceive().isSuccess) {
        // Discard.
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
