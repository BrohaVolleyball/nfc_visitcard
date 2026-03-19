package com.example.nfc_visitcard.emulator

import android.nfc.NdefRecord
import java.nio.charset.Charset

/**
 * Утилиты для создания NDEF URI записей
 * NDEF URI Record Format Specification
 */
object NdefUriEmulator {

    /**
     * Префиксы URI для оптимизации размера (NDEF spec)
     */
    private val URI_PREFIXES = mapOf(
        0x00 to "",
        0x01 to "http://www.",
        0x02 to "https://www.",
        0x03 to "http://",
        0x04 to "https://",
        0x05 to "tel:",
        0x06 to "mailto:",
        0x07 to "ftp://anonymous:anonymous@",
        0x08 to "ftp://ftp.",
        0x09 to "ftps://",
        0x0A to "sftp://",
        0x0B to "smb://",
        0x0C to "nfs://",
        0x0D to "ftp://",
        0x0E to "dav://",
        0x0F to "news:",
        0x10 to "telnet://",
        0x11 to "imap:",
        0x12 to "rtsp://",
        0x13 to "urn:",
        0x14 to "pop:",
        0x15 to "sip:",
        0x16 to "sips:",
        0x17 to "tftp:",
        0x18 to "btspp://",
        0x19 to "btl2cap://",
        0x1A to "btgoep://",
        0x1B to "tcpobex://",
        0x1C to "irdaobex://",
        0x1D to "file://",
        0x1E to "urn:epc:id:",
        0x1F to "urn:epc:tag:",
        0x20 to "urn:epc:pat:",
        0x21 to "urn:epc:raw:",
        0x22 to "urn:epc:",
        0x23 to "urn:nfc:"
    )

    /**
     * Создаёт NDEF URI Record в бинарном формате
     * @param uri Полный URI (например, "https://example.com/user/123")
     * @return Байты NDEF записи
     */
    fun createUriRecord(uri: String): ByteArray {
        val (prefixCode, uriBody) = findBestPrefix(uri)

        // Payload = prefix code (1 byte) + URI body
        val payload = byteArrayOf(prefixCode) + uriBody.toByteArray(Charset.forName("UTF-8"))

        // Type = "U" (0x55) для URI Record
        val type = byteArrayOf(0x55)

        // NDEF Record Header
        // MB=1, ME=1, CF=0, SR=1, IL=0, TNF=001 (NFC Well-Known)
        val header = byteArrayOf(
            0xD1.toByte()  // 11010001: MB=1, ME=1, CF=0, SR=1, IL=0, TNF=001
        )

        // Type Length (1 byte)
        val typeLength = byteArrayOf(type.size.toByte())

        // Payload Length (1 byte, т.к. SR=1)
        val payloadLength = byteArrayOf(payload.size.toByte())

        // Combine all parts
        return header + typeLength + payloadLength + type + payload
    }

    /**
     * Создаёт полное NDEF сообщение с URI записью
     * Включает NDEF Message Header (NLEN)
     */
    fun createNdefMessage(uri: String): ByteArray {
        val record = createUriRecord(uri)

        // NDEF Message = NLEN (2 bytes, big-endian) + NDEF Record(s)
        val messageLength = record.size
        val nlen = byteArrayOf(
            ((messageLength shr 8) and 0xFF).toByte(),
            (messageLength and 0xFF).toByte()
        )

        return nlen + record
    }

    /**
     * Находит оптимальный префикс для URI
     */
    private fun findBestPrefix(uri: String): Pair<Byte, String> {
        for ((code, prefix) in URI_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return Pair(code.toByte(), uri.substring(prefix.length))
            }
        }
        // Если префикс не найден, используем 0x00 (без префикса)
        return Pair(0x00, uri)
    }

    /**
     * Создаёт vCard NDEF запись (альтернатива)
     */
    fun createVCardRecord(profile: com.example.nfc_visitcard.data.model.UserProfile): ByteArray {
        val vCardData = buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:${profile.fullName}")
            if (profile.phoneNumber.isNotEmpty()) {
                appendLine("TEL:${profile.phoneNumber}")
            }
            if (profile.socialUrl.isNotEmpty()) {
                appendLine("URL:${profile.socialUrl}")
            }
            appendLine("END:VCARD")
        }

        // MIME Type Record
        val mimeType = "text/x-vcard".toByteArray(Charset.forName("US-ASCII"))
        val payload = vCardData.toByteArray(Charset.forName("UTF-8"))

        // Header: MB=1, ME=1, CF=0, SR=0 (длинный payload), IL=0, TNF=001 (Media)
        val header = byteArrayOf(
            0xD2.toByte()  // 11010010: MB=1, ME=1, CF=0, SR=0, IL=0, TNF=001
        )

        val typeLength = byteArrayOf(mimeType.size.toByte())

        // Payload Length (2 bytes, т.к. SR=0)
        val payloadLength = byteArrayOf(
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte()
        )

        return header + typeLength + payloadLength + mimeType + payload
    }
}