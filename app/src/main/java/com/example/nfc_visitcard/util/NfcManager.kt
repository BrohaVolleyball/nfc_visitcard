package com.example.nfc_visitcard.util

import android.app.Activity
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.os.Build
import android.util.Log
import com.example.nfc_visitcard.data.model.UserProfile
import com.example.nfc_visitcard.service.HceNdefService  // ✅ ДОБАВИТЬ ЭТОТ ИМПОРТ!
import java.nio.charset.Charset

class NfcManager(private val activity: Activity) {

    companion object {
        private const val TAG = "NfcManager"
        // ✅ Можно удалить неиспользуемую константу
        // private const val MIME_TYPE_VCARD = "text/x-vcard"
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var listener: NfcP2PListener? = null

    interface NfcP2PListener {
        fun onProfileReceived(profile: UserProfile)
        fun onNfcStarted()
        fun onNfcComplete()
        fun onNfcError(error: String)
    }

    fun setP2PListener(listener: NfcP2PListener?) {
        this.listener = listener
    }

    fun isNfcAvailable(): Boolean = nfcAdapter != null
    fun isNfcEnabled(): Boolean = nfcAdapter?.isEnabled == true

    fun isHceSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
                activity.packageManager.hasSystemFeature("android.hardware.nfc.hce")
    }

    /**
     * ✅ Включаем HCE с полными данными профиля
     */
    fun enableHce(fullName: String, phoneNumber: String, socialUrl: String) {
        Log.d(TAG, "========== enableHCE ==========")
        Log.d(TAG, "Имя: $fullName")
        Log.d(TAG, "Телефон: $phoneNumber")
        Log.d(TAG, "URL: $socialUrl")

        if (!isNfcAvailable()) {
            listener?.onNfcError("NFC не доступен")
            return
        }

        if (!isNfcEnabled()) {
            listener?.onNfcError("Включите NFC в настройках")
            return
        }

        if (!isHceSupported()) {
            listener?.onNfcError("HCE не поддерживается")
            return
        }

        // ✅ Передаём ВСЕ данные в HCE службу
        HceNdefService.setVCardData(fullName, phoneNumber, socialUrl)

        Log.d(TAG, "✅ HCE включён")
        listener?.onNfcStarted()
    }

    fun disableHce() {
        HceNdefService.disableEmulation()
        Log.d(TAG, "HCE выключен")
    }

    /**
     * Обрабатывает полученное NDEF сообщение
     */
    fun handleReceivedIntent(intent: Intent?) {
        Log.d(TAG, "📥 handleReceivedIntent: ${intent?.action}")

        if (intent?.action != NfcAdapter.ACTION_NDEF_DISCOVERED) return

        val messages = getNdefMessages(intent)
        messages?.firstOrNull()?.let { ndefMessage ->
            parseNdefMessage(ndefMessage)
        }
    }

    @Suppress("DEPRECATION")
    private fun getNdefMessages(intent: Intent): Array<NdefMessage>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
        } else {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        }?.filterIsInstance<NdefMessage>()?.toTypedArray()
    }

    /**
     * ✅ Парсит NDEF сообщение и извлекает vCard + URI
     */
    private fun parseNdefMessage(message: NdefMessage) {
        var fullName = ""
        var phoneNumber = ""
        var socialUrl = ""

        Log.d(TAG, "📥 Записей в сообщении: ${message.records.size}")

        for (i in message.records.indices) {
            val record = message.records[i]
            try {
                Log.d(TAG, "📥 Запись $i:")
                Log.d(TAG,  "  TNF: ${record.tnf}")
                Log.d(TAG,  "  Type: ${String(record.type, Charset.forName("US-ASCII"))}")
                Log.d(TAG,  "  Payload size: ${record.payload.size}")
                Log.d(TAG,  "  Payload: ${String(record.payload, Charset.forName("UTF-8"))}")

                // ✅ Парсим URI запись (tel:)
                if (record.tnf == NdefRecord.TNF_WELL_KNOWN) {
                    val type = String(record.type, Charset.forName("US-ASCII"))

                    if (type == "U") { // URI Record
                        val uri = parseUriRecord(record)
                        Log.d(TAG, "📥 URI: $uri")

                        if (uri.startsWith("tel:")) {
                            phoneNumber = uri.substringAfter("tel:")
                            Log.d(TAG, "  ✅ Телефон: $phoneNumber")
                        } else if (uri.startsWith("http")) {
                            socialUrl = uri
                            Log.d(TAG, "  ✅ URL: $socialUrl")
                        }
                    }

                    // ✅ Парсим Text Record (имя)
                    if (type == "T") { // Text Record
                        val text = parseTextRecord(record)
                        fullName = text
                        Log.d(TAG, "  ✅ Имя: $fullName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка парсинга записи $i", e)
            }
        }

        Log.d(TAG, "📦 Итог: Имя=$fullName, Тел=$phoneNumber, URL=$socialUrl")

        // ✅ Создаём профиль ТОЛЬКО для сохранения в контакты
        val receivedProfile = UserProfile.newBuilder()
            .setFullName(fullName)
            .setPhoneNumber(phoneNumber)
            .setSocialUrl(socialUrl)
            .build()

        listener?.onProfileReceived(receivedProfile)
        listener?.onNfcComplete()
    }

    /**
     * ✅ Парсит Text Record
     */
    private fun parseTextRecord(record: NdefRecord): String {
        val payload = record.payload
        if (payload.isEmpty()) return ""

        // Первый байт: статус (бит 7-6 = 0, бит 5 = UTF-8/UTF-16, бит 4-0 = длина языка)
        val statusByte = payload[0].toInt() and 0xFF
        val languageCodeLength = statusByte and 0x3F

        // Язык начинается с байта 1
        val textStart = 1 + languageCodeLength

        if (textStart >= payload.size) return ""

        val textBytes = payload.copyOfRange(textStart, payload.size)
        return String(textBytes, Charset.forName("UTF-8"))
    }

    private fun parseUriRecord(record: NdefRecord): String {
        val payload = record.payload
        if (payload.isEmpty()) return ""

        val prefixCode = payload[0].toInt() and 0xFF
        val uriBody = String(payload.copyOfRange(1, payload.size), Charset.forName("UTF-8"))

        val prefixes = mapOf(
            0x00 to "",
            0x03 to "http://",
            0x04 to "https://",
            0x05 to "tel:",
            0x06 to "mailto:"
        )

        val prefix = prefixes[prefixCode] ?: ""
        return prefix + uriBody
    }

    private fun parseVCard(vCardData: String): ParsedContact {
        var fullName = ""
        var phoneNumber = ""
        var socialUrl = ""

        Log.d(TAG, "🔍 Парсим vCard:\n$vCardData")

        vCardData.lines().forEach { line ->
            val trimmed = line.trim()
            Log.d(TAG, "  Строка: $trimmed")

            when {
                trimmed.startsWith("FN:") -> {
                    fullName = trimmed.substringAfter("FN:").trim()
                    Log.d(TAG, "  ✅ Имя: $fullName")
                }
                trimmed.startsWith("TEL:") -> {
                    phoneNumber = trimmed.substringAfter("TEL:").trim()
                    Log.d(TAG, "  ✅ Телефон: $phoneNumber")
                }
                trimmed.startsWith("URL:") -> {
                    socialUrl = trimmed.substringAfter("URL:").trim()
                    Log.d(TAG, "  ✅ URL: $socialUrl")
                }
            }
        }

        return ParsedContact(fullName, phoneNumber, socialUrl)
    }

    data class ParsedContact(
        val fullName: String,
        val phoneNumber: String,
        val socialUrl: String
    )
}