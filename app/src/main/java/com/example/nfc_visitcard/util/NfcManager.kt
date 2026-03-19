package com.example.nfc_visitcard.util

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.widget.Toast
import com.example.nfc_visitcard.data.model.UserProfile

class NfcManager(private val activity: Activity) {

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    private var pendingIntent: PendingIntent? = null
    private var ndefMessage: android.nfc.NdefMessage? = null

    // Проверка наличия NFC
    fun isNfcAvailable(): Boolean {
        return nfcAdapter != null
    }

    // Проверка включён ли NFC
    fun isNfcEnabled(): Boolean {
        return nfcAdapter?.isEnabled == true
    }

    // Создание vCard из профиля
    fun createVCard(profile: UserProfile): String {
        val fullName = profile.fullName
        val phone = profile.phoneNumber
        val socialUrl = profile.socialUrl

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("FN:$fullName")
            appendLine("N:;$fullName;;;")
            if (phone.isNotEmpty()) {
                appendLine("TEL;TYPE=CELL:$phone")
            }
            if (socialUrl.isNotEmpty()) {
                appendLine("URL:$socialUrl")
            }
            appendLine("END:VCARD")
        }
    }

    // Создание NDEF сообщения из vCard
    fun createNdefMessage(vCard: String): android.nfc.NdefMessage {
        val record = android.nfc.NdefRecord(
            android.nfc.NdefRecord.TNF_MIME_MEDIA,
            "text/vcard".toByteArray(),
            ByteArray(0),
            vCard.toByteArray(Charsets.UTF_8)
        )
        return android.nfc.NdefMessage(arrayOf(record))
    }

    // Настройка NFC для записи
    fun enableNfcWrite(profile: UserProfile) {
        if (!isNfcAvailable()) {
            Toast.makeText(activity, "NFC not available on this device", Toast.LENGTH_LONG).show()
            return
        }

        if (!isNfcEnabled()) {
            Toast.makeText(activity, "Please enable NFC in settings", Toast.LENGTH_LONG).show()
            return
        }

        // Создаём vCard и NDEF сообщение
        val vCard = createVCard(profile)
        ndefMessage = createNdefMessage(vCard)

        // Настраиваем PendingIntent для обработки тега
        val intent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(
            activity,
            0,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Включаем Reader Mode
        nfcAdapter?.enableReaderMode(
            activity,
            { tag -> onTagDiscovered(tag, profile) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B,
            null
        )

        Toast.makeText(activity, "Ready to write! Tap NFC tag or another phone", Toast.LENGTH_LONG).show()
    }

    // Обработка обнаруженного тега
    private fun onTagDiscovered(tag: Tag, profile: UserProfile) {
        try {
            val ndef = Ndef.get(tag)

            if (ndef != null) {
                // Тег уже отформатирован как NDEF
                ndef.connect()

                if (!ndef.isWritable) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Tag is not writable", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                ndef.writeNdefMessage(ndefMessage)
                ndef.close()

                activity.runOnUiThread {
                    Toast.makeText(activity, "Profile written successfully! 🎉", Toast.LENGTH_LONG).show()
                }
            } else {
                // Тег нужно отформатировать
                val formatable = NdefFormatable.get(tag)
                formatable?.connect()
                formatable?.format(ndefMessage)
                formatable?.close()

                activity.runOnUiThread {
                    Toast.makeText(activity, "Profile written successfully! 🎉", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            activity.runOnUiThread {
                Toast.makeText(activity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Отключение NFC при паузе
    fun disableNfc() {
        nfcAdapter?.disableReaderMode(activity)
    }

    // Включение NFC при возобновлении
    fun enableNfc(profile: UserProfile) {
        if (isNfcAvailable() && isNfcEnabled()) {
            enableNfcWrite(profile)
        }
    }
}