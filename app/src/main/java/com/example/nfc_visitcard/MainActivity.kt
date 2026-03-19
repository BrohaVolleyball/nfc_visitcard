package com.example.nfc_visitcard

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Bundle
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
import com.example.nfc_visitcard.ui.viewmodel.ProfileViewModel
import com.example.nfc_visitcard.util.NfcManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: ProfileViewModel
    private lateinit var dataStoreManager: DataStoreManager
    private lateinit var nfcManager: NfcManager

    private lateinit var etUserName: EditText
    private lateinit var etUserPhoneNumber: EditText
    private lateinit var etUserURL: EditText
    private lateinit var ivUserAvatar: ImageView

    private var currentAvatarUri: Uri? = null
    private var currentProfile: UserProfile? = null

    // Launcher для выбора изображения
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

        // Инициализация View
        etUserName = findViewById(R.id.userName)
        etUserPhoneNumber = findViewById(R.id.userPhoneNumber)
        etUserURL = findViewById(R.id.userURL)
        ivUserAvatar = findViewById(R.id.userAvatar)

        // Инициализация менеджеров
        dataStoreManager = DataStoreManager(this)
        nfcManager = NfcManager(this)

        viewModel = ViewModelProvider(
            this,
            ProfileViewModel.Factory(dataStoreManager)
        )[ProfileViewModel::class.java]

        // Проверка NFC
        checkNfcStatus()

        // Наблюдение за профилем
        observeProfile()

        // Наблюдение за статусом сохранения
        observeSaveStatus()

        // Клик на аватар
        ivUserAvatar.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        // Обработка NFC Intent (если приложение открылось через NFC)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Включаем NFC когда приложение активно
        currentProfile?.let { profile ->
            if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                nfcManager.enableNfc(profile)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Отключаем NFC когда приложение в фоне
        nfcManager.disableNfc()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Обработка NFC тега когда приложение уже открыто
        handleNfcIntent(intent)
    }

    private fun checkNfcStatus() {
        if (!nfcManager.isNfcAvailable()) {
            Toast.makeText(this, "NFC is not available on this device", Toast.LENGTH_LONG).show()
        } else if (!nfcManager.isNfcEnabled()) {
            Toast.makeText(this, "Please enable NFC in settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
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

                // Автоматически включаем NFC для записи при загрузке профиля
                if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                    nfcManager.enableNfcWrite(profile)
                }
            }
        }
    }

    private fun observeSaveStatus() {
        lifecycleScope.launch {
            viewModel.saveStatus.collectLatest { status ->
                when (status) {
                    is ProfileViewModel.SaveStatus.Loading -> {
                        Toast.makeText(this@MainActivity, "Saving...", Toast.LENGTH_SHORT).show()
                    }
                    is ProfileViewModel.SaveStatus.Success -> {
                        Toast.makeText(this@MainActivity, status.message, Toast.LENGTH_SHORT).show()
                        // После сохранения обновляем NFC сообщение
                        currentProfile?.let { profile ->
                            if (nfcManager.isNfcAvailable() && nfcManager.isNfcEnabled()) {
                                nfcManager.enableNfcWrite(profile)
                            }
                        }
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
            // Приложение открылось через NFC тег
            Toast.makeText(this, "NFC tag detected!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProfile() {
        val fullName = etUserName.text.toString().trim()
        val phoneNumber = etUserPhoneNumber.text.toString().trim()
        val socialUrl = etUserURL.text.toString().trim()
        val avatarPath = currentAvatarUri?.toString() ?: ""

        if (fullName.isEmpty()) {
            etUserName.error = "Full Name is required"
            etUserName.requestFocus()
            return
        }

        if (phoneNumber.isEmpty()) {
            etUserPhoneNumber.error = "Phone number is required"
            etUserPhoneNumber.requestFocus()
            return
        }

        viewModel.saveProfile(
            fullName = fullName,
            phoneNumber = phoneNumber,
            socialUrl = socialUrl,
            avatarPath = avatarPath
        )
    }
}