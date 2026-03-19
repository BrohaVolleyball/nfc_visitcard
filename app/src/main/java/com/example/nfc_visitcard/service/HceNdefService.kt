package com.example.nfc_visitcard.service

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class HceNdefService : HostApduService() {

    companion object {
        private const val TAG = "HceNdefService"

        // NDEF AID (NFC Forum Type 4 Tag)
        private val NDEF_AID: ByteArray = byteArrayOf(
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x85.toByte(), 0x01.toByte(), 0x01.toByte()
        )

        // SELECT Command
        private const val CLA_SELECT: Byte = 0x00.toByte()
        private const val INS_SELECT: Byte = 0xA4.toByte()
        private const val P1_SELECT_BY_NAME: Byte = 0x04.toByte()

        // READ Command
        private const val INS_READ: Byte = 0xB0.toByte()

        // Status Words
        private val SW_SUCCESS: ByteArray = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND: ByteArray = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED: ByteArray = byteArrayOf(0x6D.toByte(), 0x00.toByte())

        // Capability Container (CC) - Type 4 Tag
        private val CC_FILE: ByteArray = byteArrayOf(
            0x00.toByte(), 0x0F.toByte(),  // CC Len
            0x20.toByte(),                  // Version 2.0
            0x00.toByte(), 0x7F.toByte(),  // Max R-APDU
            0x00.toByte(), 0x7F.toByte(),  // Max W-APDU
            0x04.toByte(),                  // T (NDEF)
            0x06.toByte(),                  // L
            0xE1.toByte(), 0x04.toByte(),  // NDEF File ID
            0x00.toByte(), 0x00.toByte(),  // NDEF Size (update dynamically)
            0x00.toByte(), 0x00.toByte()   // Read/Write access
        )

        // NDEF File ID
        private val NDEF_FILE_ID: ByteArray = byteArrayOf(0xE1.toByte(), 0x04.toByte())

        @Volatile
        private var currentUri: String = ""

        @Volatile
        private var ndefMessage: ByteArray = byteArrayOf()

        @JvmStatic
        fun setCurrentUri(uri: String) {
            currentUri = uri
            ndefMessage = if (uri.isNotEmpty()) {
                com.example.nfc_visitcard.emulator.NdefUriEmulator.createNdefMessage(uri)
            } else {
                byteArrayOf()
            }
            Log.d(TAG, "URI updated: $uri, NDEF size: ${ndefMessage.size}")
        }

        @JvmStatic
        fun disableEmulation() {
            currentUri = ""
            ndefMessage = byteArrayOf()
            Log.d(TAG, "Emulation disabled")
        }
    }

    private var selectedFile: Int = 0 // 0=none, 1=CC, 2=NDEF

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        Log.d(TAG, "=== APDU IN: ${commandApdu.toHexString()}")

        try {
            // SELECT Command (00 A4 04 00)
            if (commandApdu.size >= 5 &&
                commandApdu[0] == CLA_SELECT &&
                commandApdu[1] == INS_SELECT
            ) {
                val response = handleSelect(commandApdu)
                Log.d(TAG, "SELECT response: ${response.toHexString()}")
                return response
            }

            // READ Command (00 B0)
            if (commandApdu.size >= 5 &&
                commandApdu[0] == CLA_SELECT &&
                commandApdu[1] == INS_READ
            ) {
                val response = handleRead(commandApdu)
                Log.d(TAG, "READ response: ${response.toHexString()}")
                return response
            }

            Log.w(TAG, "Unknown command")
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

        // SELECT by AID name
        if (p1 == P1_SELECT_BY_NAME && lc > 0) {
            val aid = commandApdu.copyOfRange(5, 5 + lc)
            Log.d(TAG, "SELECT AID: ${aid.toHexString()}")

            when {
                aid.contentEquals(NDEF_AID) -> {
                    Log.d(TAG, "NDEF AID selected")
                    selectedFile = 0
                    return SW_SUCCESS
                }
                aid.contentEquals(NDEF_FILE_ID) -> {
                    Log.d(TAG, "NDEF File selected")
                    selectedFile = 2
                    return SW_SUCCESS
                }
            }
        }

        // SELECT CC File (00 A4 00 0C)
        if (p1 == 0x00.toByte() && p2 == 0x0C.toByte()) {
            Log.d(TAG, "CC File selected")
            selectedFile = 1
            return SW_SUCCESS
        }

        // SELECT NDEF File (00 A4 00 E1 04)
        if (p1 == 0x00.toByte() && commandApdu.size >= 7) {
            if (commandApdu[5] == 0xE1.toByte() && commandApdu[6] == 0x04.toByte()) {
                Log.d(TAG, "NDEF File selected (short)")
                selectedFile = 2
                return SW_SUCCESS
            }
        }

        return SW_FILE_NOT_FOUND
    }

    private fun handleRead(commandApdu: ByteArray): ByteArray {
        val offset: Int = ((commandApdu[1].toInt() and 0xFF) shl 8) or (commandApdu[2].toInt() and 0xFF)
        val length: Int = commandApdu[4].toInt() and 0xFF

        Log.d(TAG, "READ: file=$selectedFile, offset=$offset, length=$length")

        // Read CC File
        if (selectedFile == 1 || offset < 0x10) {
            Log.d(TAG, "Reading CC File")

            // Update NDEF size in CC
            val ccWithSize = CC_FILE.copyOf()
            if (ndefMessage.isNotEmpty()) {
                ccWithSize[10] = ((ndefMessage.size shr 8) and 0xFF).toByte()
                ccWithSize[11] = (ndefMessage.size and 0xFF).toByte()
            }

            if (offset < ccWithSize.size) {
                val end = minOf(offset + length, ccWithSize.size)
                return ccWithSize.copyOfRange(offset, end) + SW_SUCCESS
            }
            return SW_FILE_NOT_FOUND
        }

        // Read NDEF File
        if (selectedFile == 2) {
            if (ndefMessage.isEmpty()) {
                Log.w(TAG, "NDEF message is empty")
                return SW_FILE_NOT_FOUND
            }

            // NDEF file starts at offset 0x10 (after CC)
            val ndefOffset = offset - 0x10

            if (ndefOffset < 0) {
                // Reading CC + NDEF
                val ccWithSize = CC_FILE.copyOf()
                if (ndefMessage.isNotEmpty()) {
                    ccWithSize[10] = ((ndefMessage.size shr 8) and 0xFF).toByte()
                    ccWithSize[11] = (ndefMessage.size and 0xFF).toByte()
                }

                val ccPart = ccWithSize.copyOfRange(offset, minOf(offset + length, ccWithSize.size))
                val remainingLength = length - ccPart.size

                if (remainingLength > 0 && ndefOffset + remainingLength <= ndefMessage.size) {
                    val ndefPart = ndefMessage.copyOfRange(0, minOf(remainingLength, ndefMessage.size))
                    return ccPart + ndefPart + SW_SUCCESS
                }
                return ccPart + SW_SUCCESS
            }

            if (ndefOffset >= ndefMessage.size) {
                return byteArrayOf(0x00.toByte()) + SW_SUCCESS
            }

            val end = minOf(ndefOffset + length, ndefMessage.size)
            val data = ndefMessage.copyOfRange(ndefOffset, end)

            Log.d(TAG, "Returning NDEF data: ${data.size} bytes")
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