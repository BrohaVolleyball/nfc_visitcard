package com.example.nfc_visitcard.emulator

import java.nio.charset.Charset

object NdefUriEmulator {

    fun createNdefMessage(uri: String): ByteArray {
        val record = createUriRecord(uri)

        val messageLength = record.size
        val nlen = byteArrayOf(
            ((messageLength shr 8) and 0xFF).toByte(),
            (messageLength and 0xFF).toByte()
        )

        return nlen + record
    }

    private fun createUriRecord(uri: String): ByteArray {
        val (prefixCode, uriBody) = findBestPrefix(uri)

        val payload = byteArrayOf(prefixCode) + uriBody.toByteArray(Charset.forName("UTF-8"))
        val type = byteArrayOf(0x55) // "U" для URI
        val header = byteArrayOf(0xD1.toByte())
        val typeLength = byteArrayOf(type.size.toByte())
        val payloadLength = byteArrayOf(payload.size.toByte())

        return header + typeLength + payloadLength + type + payload
    }

    private fun findBestPrefix(uri: String): Pair<Byte, String> {
        val prefixes = mapOf(
            0x03 to "http://",
            0x04 to "https://",
            0x05 to "tel:",
            0x06 to "mailto:"
        )

        for ((code, prefix) in prefixes) {
            if (uri.startsWith(prefix, ignoreCase = true)) {
                return Pair(code.toByte(), uri.substring(prefix.length))
            }
        }
        return Pair(0x00.toByte(), uri)
    }
}