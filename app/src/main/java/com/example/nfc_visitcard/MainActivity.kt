package com.example.nfc_visitcard

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.nfc_visitcard.data.DataStoreManager
import com.example.nfc_visitcard.data.model.UserProfile
import com.example.nfc_visitcard.service.HceNdefService  // ← ИМПОРТ HCE!
import com.example.nfc_visitcard.ui.viewmodel.ProfileViewModel
import com.example.nfc_visitcard.util.NfcManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.pm.PackageManager

private const val TAG = "NFC_DEBUG"

class MainActivity : AppCompatActivity() {

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
            Glide.with(this)
                .load(uri)
                .circleCrop()
                .into(ivUserAvatar)
        } else {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "========== onCreate STARTED ==========")

        // Инициализация View
        etUserName = findViewById(R.id.userName)
        etUserPhoneNumber = findViewById(R.id.userPhoneNumber)
        etUserURL = findViewById(R.id.userURL)
        ivUserAvatar = findViewById(R.id.userAvatar)
        btnSave = findViewById(R.id.button)

        // Инициализация менеджеров
        dataStoreManager = DataStoreManager(this)
        nfcManager = NfcManager(this)

        viewModel = ViewModelProvider(
            this,
            ProfileViewModel.Factory(dataStoreManager)
        )[ProfileViewModel::class.java]

        // Проверка NFC + HCE
        checkNfcStatus()

        // Наблюдение за профилем
        observeProfile()

        // Наблюдение за статусом сохранения
        observeSaveStatus()

        // Клик на кнопку Save
        btnSave.setOnClickListener {
            Log.d(TAG, "========== SAVE BUTTON CLICKED ==========")
            saveProfile()
        }

        // Клик на аватар
        ivUserAvatar.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // Обработка NFC Intent
        handleNfcIntent(intent)

        Log.d(TAG, "========== onCreate COMPLETED ==========")
    }

    override fun onResume() {
        super.onResume()
        // ← HCE: Активируем эмуляцию при возврате в приложение
        currentProfile?.let { profile ->
            if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                enableHceEmulation(profile)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // HCE можно оставить включённым или отключить
        // HceNdefService.disableEmulation()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    private fun checkNfcStatus() {
        when {
            !nfcManager.isNfcAvailable() -> {
                Toast.makeText(this, "NFC is not available on this device", Toast.LENGTH_LONG).show()
            }
            !nfcManager.isNfcEnabled() -> {
                Toast.makeText(this, "Please enable NFC in settings", Toast.LENGTH_LONG).show()
            }
            // ← УДАЛИТЕ проверку FEATURE_NFC_HCE
            else -> {
                Log.d(TAG, "NFC + HCE ready!")
            }
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            Log.d(TAG, "observeProfile started")
            viewModel.profile.collectLatest { profile ->
                currentProfile = profile
                etUserName.setText(profile.fullName)
                etUserPhoneNumber.setText(profile.phoneNumber)
                etUserURL.setText(profile.socialUrl)

                if (profile.avatarPath.isNotEmpty()) {
                    val uri = Uri.parse(profile.avatarPath)
                    Glide.with(this@MainActivity)
                        .load(uri)
                        .circleCrop()
                        .into(ivUserAvatar)
                    currentAvatarUri = uri
                }

                // ← HCE: Обновляем эмуляцию при изменении профиля
                if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                    enableHceEmulation(profile)
                }

                Log.d(TAG, "Profile loaded: ${profile.fullName}")
            }
        }
    }

    private fun observeSaveStatus() {
        lifecycleScope.launch {
            Log.d(TAG, "observeSaveStatus started")
            viewModel.saveStatus.collectLatest { status ->
                Log.d(TAG, "Save status: $status")
                when (status) {
                    is ProfileViewModel.SaveStatus.Loading -> {
                        Toast.makeText(this@MainActivity, "Saving...", Toast.LENGTH_SHORT).show()
                    }
                    is ProfileViewModel.SaveStatus.Success -> {
                        Toast.makeText(this@MainActivity, status.message, Toast.LENGTH_SHORT).show()
                        // HCE уже обновляется через observeProfile
                    }
                    is ProfileViewModel.SaveStatus.Error -> {
                        Toast.makeText(this@MainActivity, "Error: ${status.message}", Toast.LENGTH_LONG).show()
                    }
                    is ProfileViewModel.SaveStatus.Idle -> {}
                }
            }
        }
    }

    private fun handleNfcIntent(intent: Intent?) {
        if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            Toast.makeText(this, "NFC tag detected!", Toast.LENGTH_SHORT).show()
        }
    }

    // ← HCE: Включаем эмуляцию NFC Tag с URI
    private fun enableHceEmulation(profile: UserProfile) {
        if (profile.fullName.isEmpty()) return

        // Создаём ссылку на профиль
        val profileUrl = buildProfileUrl(profile)

        Log.d(TAG, "HCE: Setting URI = $profileUrl")
        HceNdefService.setCurrentUri(profileUrl)

        Toast.makeText(
            this,
            "NFC визитка активна!\nПриложите телефон к другому устройству",
            Toast.LENGTH_LONG
        ).show()
    }

    // ← HCE: Формируем URL для передачи
    private fun buildProfileUrl(profile: UserProfile): String {
        // Вариант 1: HTTPS ссылка (лучшая совместимость)
        // Замените на ваш реальный домен
        val domain = "yourdomain.com"
        val username = profile.fullName.replace(" ", "_").replace("[^a-zA-Z0-9_]".toRegex(), "")
        return "https://$domain/u/$username"

        // Вариант 2: Tel ссылка (открывает набор номера)
        // return "tel:${profile.phoneNumber}"

        // Вариант 3: WhatsApp
        // return "https://wa.me/${profile.phoneNumber}"

        // Вариант 4: Deep link в ваше приложение
        // return "nfcvisitcard://profile/${profile.id}"
    }

    private fun saveProfile() {
        Log.d(TAG, "========== saveProfile() CALLED ==========")

        val fullName = etUserName.text.toString().trim()
        val phoneNumber = etUserPhoneNumber.text.toString().trim()
        val socialUrl = etUserURL.text.toString().trim()
        val avatarPath = currentAvatarUri?.toString() ?: ""

        Log.d(TAG, "Full Name: '$fullName'")
        Log.d(TAG, "Phone: '$phoneNumber'")
        Log.d(TAG, "URL: '$socialUrl'")
        Log.d(TAG, "Avatar: '$avatarPath'")

        if (fullName.isEmpty()) {
            Log.e(TAG, "Validation failed: Empty name")
            etUserName.error = "Full Name is required"
            etUserName.requestFocus()
            return
        }

        if (phoneNumber.isEmpty()) {
            Log.e(TAG, "Validation failed: Empty phone")
            etUserPhoneNumber.error = "Phone number is required"
            etUserPhoneNumber.requestFocus()
            return
        }

        Log.d(TAG, "Calling viewModel.saveProfile()...")

        viewModel.saveProfile(
            fullName = fullName,
            phoneNumber = phoneNumber,
            socialUrl = socialUrl,
            avatarPath = avatarPath
        )

        Log.d(TAG, "viewModel.saveProfile() completed")
    }
}