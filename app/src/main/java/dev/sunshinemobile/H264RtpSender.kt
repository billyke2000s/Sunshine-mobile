package dev.sunshinemobile

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * H.264 RTP payload sender (RFC 6184), useful for protocol testing.
 * This is NOT the proprietary GameStream video packet protocol.
 */
class H264RtpSender(
    destination: InetAddress,
    private val port: Int,
    private val maxPayload: Int = 1200
) : Closeable {
    private val address = destination
    private val socket = DatagramSocket()
    private val sequence = AtomicInteger(0)
    private val ssrc = java.security.SecureRandom().nextInt()

    init { require(maxPayload in 256..1400) }

    @Synchronized
    fun sendAnnexB(accessUnit: ByteArray, presentationTimeUs: Long) {
        val nalus = splitAnnexB(accessUnit)
        val timestamp = ((presentationTimeUs * 90L) / 1000L).toInt()
        for ((index, nalu) in nalus.withIndex()) {
            if (nalu.isEmpty()) continue
            val lastNalu = index == nalus.lastIndex
            if (nalu.size <= maxPayload) {
                sendPacket(nalu, timestamp, lastNalu)
            } else {
                val header = nalu[0].toInt() and 0xff
                val indicator = (header and 0xe0) or 28
                val type = header and 0x1f
                var offset = 1
                while (offset < nalu.size) {
                    val amount = minOf(maxPayload - 2, nalu.size - offset)
                    val first = offset == 1
                    val end = offset + amount == nalu.size
                    val fragment = ByteArray(amount + 2)
                    fragment[0] = indicator.toByte()
                    fragment[1] = (type or (if (first) 0x80 else 0) or (if (end) 0x40 else 0)).toByte()
                    System.arraycopy(nalu, offset, fragment, 2, amount)
                    sendPacket(fragment, timestamp, lastNalu && end)
                    offset += amount
                }
            }
        }
    }

    private fun sendPacket(payload: ByteArray, timestamp: Int, marker: Boolean) {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        val seq = sequence.getAndIncrement() and 0xffff
        packet[2] = (seq ushr 8).toByte()
        packet[3] = seq.toByte()
        for (i in 0..3) {
            packet[4 + i] = (timestamp ushr (24 - 8 * i)).toByte()
            packet[8 + i] = (ssrc ushr (24 - 8 * i)).toByte()
        }
        System.arraycopy(payload, 0, packet, 12, payload.size)
        socket.send(DatagramPacket(packet, packet.size, address, port))
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        fun prefixAt(i: Int): Int {
            if (i + 2 >= data.size || data[i] != 0.toByte() || data[i + 1] != 0.toByte()) return 0
            return if (data[i + 2] == 1.toByte()) 3
                else if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) 4
                else 0
        }
        val starts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < data.size) {
            val len = prefixAt(i)
            if (len > 0) { starts.add(i to len); i += len } else i++
        }
        if (starts.isEmpty()) return listOf(data)
        return starts.mapIndexedNotNull { index, (start, len) ->
            val end = if (index + 1 < starts.size) starts[index + 1].first else data.size
            if (start + len >= end) null else data.copyOfRange(start + len, end)
        }
    }

    override fun close() { socket.close() }
}
