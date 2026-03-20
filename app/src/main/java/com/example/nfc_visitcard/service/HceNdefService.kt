package com.example.nfc_visitcard.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.Charset

class HceNdefService : HostApduService() {

    companion object {
        private const val TAG = "HceNdefService"

        private val NDEF_AID: ByteArray = byteArrayOf(
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x01.toByte()
        )

        private const val CLA_SELECT: Byte = 0x00.toByte()
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val P1_SELECT_BY_NAME: Byte = 0x04.toByte()
        private const val INS_READ: Byte = 0xB0.toByte()

        private val SW_SUCCESS: ByteArray = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D.toByte(), 0x00.toByte())

        private val CC_FILE: ByteArray = byteArrayOf(
            0x00.toByte(), 0x0F.toByte(),
            0x20.toByte(),
            0x00.toByte(), 0x7F.toByte(),
            0x00.toByte(), 0x7F.toByte(),
            0x04.toByte(),
            0x06.toByte(),
            0xE1.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte()
        )

        @Volatile
        private var ndefMessage: ByteArray = byteArrayOf()

        @JvmStatic
        fun setVCardData(fullName: String, phoneNumber: String, socialUrl: String) {
            Log.d(TAG, "========== setVCardData ==========")
            Log.d(TAG, "Имя: '$fullName'")
            Log.d(TAG, "Телефон: '$phoneNumber'")
            Log.d(TAG, "URL: '$socialUrl'")

            ndefMessage = createNdefMessage(fullName, phoneNumber)

            Log.d(TAG, "✅ NDEF создан: ${ndefMessage.size} байт")
            Log.d(TAG, "NDEF HEX: ${ndefMessage.toHexString()}")

            // 🔍 Декодируем для проверки
            Log.d(TAG, "NDEF расшифровка:")
            decodeNdefMessage(ndefMessage)
        }

        @JvmStatic
        fun disableEmulation() {
            ndefMessage = byteArrayOf()
            Log.d(TAG, "HCE disabled")
        }

        /**
         * 🔍 Декодирует NDEF для отладки
         */
        private fun decodeNdefMessage(data: ByteArray) {
            if (data.size < 4) {
                Log.w(TAG, "Слишком короткое сообщение")
                return
            }

            // Пропускаем NLEN (2 байта)
            var offset = 2

            while (offset < data.size) {
                val header = data[offset].toInt() and 0xFF
                val mb = (header shr 7) and 1
                val me = (header shr 6) and 1
                val sr = (header shr 4) and 1
                val tnf = header and 0x07

                offset++

                val typeLength = data[offset].toInt() and 0xFF
                offset++

                val payloadLength = if (sr == 1) {
                    data[offset].toInt() and 0xFF
                } else {
                    ((data[offset].toInt() and 0xFF) shl 24) or
                            ((data[offset + 1].toInt() and 0xFF) shl 16) or
                            ((data[offset + 2].toInt() and 0xFF) shl 8) or
                            (data[offset + 3].toInt() and 0xFF)
                }
                if (sr == 0) offset += 4

                offset++ // typeLength

                val type = String(data.copyOfRange(offset, offset + typeLength), Charset.forName("US-ASCII"))
                offset += typeLength

                val payload = String(data.copyOfRange(offset, offset + payloadLength), Charset.forName("UTF-8"))

                Log.d(TAG, "  Запись: MB=$mb, ME=$me, SR=$sr, TNF=$tnf, Type='$type', Payload='$payload'")

                offset += payloadLength

                if (me == 1) break
            }
        }

        private fun createNdefMessage(fullName: String, phoneNumber: String): ByteArray {
            // URI запись (tel:)
            val uriRecord = createUriRecord("tel:$phoneNumber")

            // Text запись (имя)
            val textRecord = createTextRecord(fullName)

            val totalLength = uriRecord.size + textRecord.size
            val nlen = byteArrayOf(
                ((totalLength shr 8) and 0xFF).toByte(),
                (totalLength and 0xFF).toByte()
            )

            // Вторая запись: MB=0, ME=1 (0x51)
            val textRecordWithHeader = textRecord.copyOfRange(1, textRecord.size)
            textRecordWithHeader[0] = 0x51.toByte()

            return nlen + uriRecord + textRecordWithHeader
        }

        private fun createUriRecord(uri: String): ByteArray {
            val (prefixCode, uriBody) = findBestPrefix(uri)

            val payload = byteArrayOf(prefixCode) + uriBody.toByteArray(Charset.forName("UTF-8"))
            val type = byteArrayOf(0x55)
            val header = byteArrayOf(0xD1.toByte())
            val typeLength = byteArrayOf(type.size.toByte())
            val payloadLength = byteArrayOf(payload.size.toByte())

            Log.d(TAG, "URI Record: prefix=$prefixCode, body='$uriBody'")
            return header + typeLength + payloadLength + type + payload
        }

        private fun createTextRecord(text: String): ByteArray {
            val langCode = "en".toByteArray(Charset.forName("US-ASCII"))
            val textBytes = text.toByteArray(Charset.forName("UTF-8"))
            val payload = byteArrayOf(0x02) + langCode + textBytes

            val type = byteArrayOf(0x54)
            val header = byteArrayOf(0xD1.toByte())
            val typeLength = byteArrayOf(type.size.toByte())
            val payloadLength = byteArrayOf(payload.size.toByte())

            Log.d(TAG, "Text Record: text='$text', payload size=${payload.size}")
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

    private var selectedFile: Int = 0

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        try {
            if (commandApdu.size >= 5 &&
                commandApdu[0] == CLA_SELECT &&
                commandApdu[1] == INS_SELECT
            ) {
                return handleSelect(commandApdu)
            }

            if (commandApdu.size >= 5 &&
                commandApdu[0] == CLA_SELECT &&
                commandApdu[1] == INS_READ
            ) {
                return handleRead(commandApdu)
            }

            return SW_INS_NOT_SUPPORTED

        } catch (e: Exception) {
            Log.e(TAG, "APDU error", e)
            return SW_FILE_NOT_FOUND
        }
    }

    private fun handleSelect(commandApdu: ByteArray): ByteArray {
        val p1: Byte = commandApdu[2]
        val p2: Byte = commandApdu[3]
        val lc: Int = commandApdu[4].toInt() and 0xFF

        if (p1 == P1_SELECT_BY_NAME && lc > 0 && commandApdu.size >= 5 + lc) {
            val aid = commandApdu.copyOfRange(5, 5 + lc)
            Log.d(TAG, "📥 SELECT AID: ${aid.toHexString()}")

            when {
                aid.contentEquals(NDEF_AID) -> {
                    Log.d(TAG, "✅ NDEF AID selected")
                    selectedFile = 0
                    return SW_SUCCESS
                }
                aid.contentEquals(byteArrayOf(0xE1.toByte(), 0x04.toByte())) -> {
                    Log.d(TAG, "✅ NDEF File selected")
                    selectedFile = 2
                    return SW_SUCCESS
                }
            }
        }

        if (p1 == 0x00.toByte() && p2 == 0x0C.toByte()) {
            Log.d(TAG, "✅ CC File selected")
            selectedFile = 1
            return SW_SUCCESS
        }

        Log.w(TAG, "SELECT failed")
        return SW_FILE_NOT_FOUND
    }

    private fun handleRead(commandApdu: ByteArray): ByteArray {
        val offset: Int = ((commandApdu[1].toInt() and 0xFF) shl 8) or (commandApdu[2].toInt() and 0xFF)
        val length: Int = commandApdu[4].toInt() and 0xFF

        Log.d(TAG, "📥 READ: file=$selectedFile, offset=$offset, length=$length")
        Log.d(TAG, "NDEF size: ${ndefMessage.size}")

        if (selectedFile == 1 || offset < 0x10) {
            val ccWithSize = CC_FILE.copyOf()
            if (ndefMessage.isNotEmpty()) {
                ccWithSize[10] = ((ndefMessage.size shr 8) and 0xFF).toByte()
                ccWithSize[11] = (ndefMessage.size and 0xFF).toByte()
            }

            if (offset < ccWithSize.size) {
                val end = minOf(offset + length, ccWithSize.size)
                val response = ccWithSize.copyOfRange(offset, end) + SW_SUCCESS
                Log.d(TAG, "📤 CC response: ${response.toHexString()}")
                return response
            }
        }

        if (selectedFile == 2) {
            if (ndefMessage.isEmpty()) {
                Log.w(TAG, "❌ NDEF message is empty!")
                return SW_FILE_NOT_FOUND
            }

            val ndefOffset = offset - 0x10
            if (ndefOffset < 0) {
                Log.w(TAG, "Invalid offset: $ndefOffset")
                return SW_FILE_NOT_FOUND
            }

            if (ndefOffset >= ndefMessage.size) {
                Log.d(TAG, "Offset beyond size")
                return byteArrayOf(0x00.toByte()) + SW_SUCCESS
            }

            val end = minOf(ndefOffset + length, ndefMessage.size)
            val data = ndefMessage.copyOfRange(ndefOffset, end)

            Log.d(TAG, "📤 NDEF data: ${data.size} bytes")
            Log.d(TAG, "📤 NDEF HEX: ${data.toHexString()}")
            return data + SW_SUCCESS
        }

        return SW_FILE_NOT_FOUND
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "Deactivated: $reason")
        selectedFile = 0
    }
}

private fun ByteArray.toHexString(): String =
    joinToString(" ") { "%02X".format(it) }