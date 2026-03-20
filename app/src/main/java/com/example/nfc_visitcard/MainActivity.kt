package com.example.nfc_visitcard

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.nfc_visitcard.data.DataStoreManager
import com.example.nfc_visitcard.data.model.UserProfile
import com.example.nfc_visitcard.ui.viewmodel.ProfileViewModel
import com.example.nfc_visitcard.util.NfcManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val TAG = "NFC_DEBUG"
private const val PERMISSIONS_REQUEST_CODE = 100

class MainActivity : AppCompatActivity(), NfcManager.NfcP2PListener {

    private lateinit var viewModel: ProfileViewModel
    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var nfcManager: NfcManager

    private lateinit var etUserName: EditText
    private lateinit var etUserPhoneNumber: EditText
    private lateinit var etUserURL: EditText
    private lateinit var ivUserAvatar: ImageView
    private lateinit var btnSave: Button

    private var currentAvatarUri: Uri? = null
    private var currentProfile: UserProfile? = null

    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            currentAvatarUri = uri
            Glide.with(this).load(uri).circleCrop().into(ivUserAvatar)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "========== onCreate ==========")
        Log.d(TAG, "Android: ${Build.VERSION.SDK_INT}")
        Log.d(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")

        etUserName = findViewById(R.id.userName)
        etUserPhoneNumber = findViewById(R.id.userPhoneNumber)
        etUserURL = findViewById(R.id.userURL)
        ivUserAvatar = findViewById(R.id.userAvatar)
        btnSave = findViewById(R.id.button)

        dataStoreManager = DataStoreManager(this)
        nfcManager = NfcManager(this).also { it.setP2PListener(this) }

        viewModel = ViewModelProvider(
            this,
            ProfileViewModel.Factory(dataStoreManager)
        )[ProfileViewModel::class.java]

        requestContactsPermission()
        checkNfcStatus()
        observeProfile()
        observeSaveStatus()

        btnSave.setOnClickListener { saveProfile() }
        ivUserAvatar.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        currentProfile?.let { profile ->
            nfcManager.enableHce(
                fullName = profile.fullName,
                phoneNumber = profile.phoneNumber,
                socialUrl = profile.socialUrl
            )
        }
    }

    //override fun onPause() {
    //    super.onPause()
    //    nfcManager.disableHce()
    //}

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun requestContactsPermission() {
        val permissions = arrayOf(
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CONTACTS
        )

        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needRequest) {
            requestPermissions(permissions, PERMISSIONS_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "✅ Разрешение на контакты получено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Контакты не сохранятся", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkNfcStatus() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        when {
            nfcAdapter == null -> {
                Toast.makeText(this, "❌ NFC не доступен", Toast.LENGTH_LONG).show()
            }
            !nfcAdapter.isEnabled -> {
                Toast.makeText(this, "⚠️ Включите NFC", Toast.LENGTH_LONG).show()
            }
            !nfcManager.isHceSupported() -> {
                Toast.makeText(this, "⚠️ HCE не поддерживается", Toast.LENGTH_LONG).show()
            }
            else -> {
                Toast.makeText(this, "✅ NFC + HCE готовы", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            viewModel.profile.collectLatest { profile ->
                currentProfile = profile

                // ✅ Загружаем данные в UI
                etUserName.setText(profile.fullName)
                etUserPhoneNumber.setText(profile.phoneNumber)
                etUserURL.setText(profile.socialUrl)

                // ✅ Включаем HCE с текущими данными
                if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                    nfcManager.enableHce(
                        fullName = profile.fullName,
                        phoneNumber = profile.phoneNumber,
                        socialUrl = profile.socialUrl
                    )
                }
            }
        }
    }

    private fun observeSaveStatus() {
        lifecycleScope.launch {
            viewModel.saveStatus.collectLatest { status ->
                when (status) {
                    is ProfileViewModel.SaveStatus.Success -> {
                        Toast.makeText(this@MainActivity, status.message, Toast.LENGTH_SHORT).show()
                    }
                    is ProfileViewModel.SaveStatus.Error -> {
                        Toast.makeText(this@MainActivity, "Ошибка: ${status.message}", Toast.LENGTH_LONG).show()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            Log.d(TAG, "📥 NDEF_DISCOVERED")
            nfcManager.handleReceivedIntent(intent)
        }
    }

    private fun saveProfile() {
        val fullName = etUserName.text.toString().trim()
        val phoneNumber = etUserPhoneNumber.text.toString().trim()
        val socialUrl = etUserURL.text.toString().trim()
        val avatarPath = currentAvatarUri?.toString() ?: ""

        if (fullName.isEmpty()) {
            etUserName.error = "Требуется имя"
            return
        }
        if (phoneNumber.isEmpty()) {
            etUserPhoneNumber.error = "Требуется телефон"
            return
        }

        viewModel.saveProfile(fullName, phoneNumber, socialUrl, avatarPath)
    }

    // ==================== NfcP2PListener ====================

    override fun onProfileReceived(profile: UserProfile) {
        Log.d(TAG, "📥 Получен: '${profile.fullName}', '${profile.phoneNumber}'")

        // ✅ Проверяем что данные не пустые
        if (profile.fullName.isEmpty() && profile.phoneNumber.isEmpty()) {
            Log.e(TAG, "❌ Пустые данные! Не показываем диалог")
            Toast.makeText(this, "❌ Не удалось получить данные контакта", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch {
            // ✅ НЕ сохраняем в DataStore - только в контакты
        }

        showSaveContactDialog(profile)
    }

    private fun showSaveContactDialog(profile: UserProfile) {
        Log.d(TAG, "Показываем диалог: ${profile.fullName}, ${profile.phoneNumber}")

        AlertDialog.Builder(this)
            .setTitle("💾 Сохранить контакт?")
            .setMessage("Имя: ${profile.fullName.ifEmpty { "Неизвестно" }}\nТелефон: ${profile.phoneNumber.ifEmpty { "Не указан" }}")
            .setPositiveButton("Сохранить") { _, _ ->
                saveToContacts(profile)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveToContacts(profile: UserProfile) {
        try {
            val rawContactValues = ContentValues().apply {
                putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            }

            val rawContactUri: Uri? = contentResolver.insert(
                ContactsContract.RawContacts.CONTENT_URI,
                rawContactValues
            )

            if (rawContactUri == null) {
                Toast.makeText(this, "❌ Ошибка сохранения", Toast.LENGTH_LONG).show()
                return
            }

            val rawContactId = rawContactUri.lastPathSegment ?: return

            // Имя
            ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, profile.fullName)
                contentResolver.insert(ContactsContract.Data.CONTENT_URI, this)
            }

            // Телефон
            if (profile.phoneNumber.isNotEmpty()) {
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, profile.phoneNumber)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    contentResolver.insert(ContactsContract.Data.CONTENT_URI, this)
                }
            }

            Log.d(TAG, "✅ Контакт сохранён: ${profile.fullName}")
            Toast.makeText(this, "✅ Контакт сохранён", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка: ${e.message}", e)
            Toast.makeText(this, "❌ ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onNfcStarted() {
        Toast.makeText(this, "📱 Приложите телефон к другому устройству", Toast.LENGTH_LONG).show()
    }

    override fun onNfcComplete() {
        Toast.makeText(this, "✅ Передача завершена!", Toast.LENGTH_SHORT).show()
    }

    override fun onNfcError(error: String) {
        Toast.makeText(this, error, Toast.LENGTH_LONG).show()
    }
}