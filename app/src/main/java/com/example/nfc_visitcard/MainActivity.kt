package com.example.nfc_visitcard

import android.net.Uri
import android.os.Bundle
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
import com.example.nfc_visitcard.ui.viewmodel.ProfileViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: ProfileViewModel
    private lateinit var dataStoreManager: DataStoreManager

    private lateinit var etUserName: EditText
    private lateinit var etUserPhoneNumber: EditText
    private lateinit var etUserURL: EditText
    private lateinit var ivUserAvatar: ImageView
    private lateinit var btnSave: Button

    private var currentAvatarUri: Uri? = null

    // Launcher для выбора изображения
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            currentAvatarUri = uri
            // Показываем выбранное изображение
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
        btnSave = findViewById(R.id.button)

        // Инициализация DataStore и ViewModel
        dataStoreManager = DataStoreManager(this)
        viewModel = ViewModelProvider(
            this,
            ProfileViewModel.Factory(dataStoreManager)
        )[ProfileViewModel::class.java]

        // Наблюдение за профилем
        observeProfile()

        // Наблюдение за статусом сохранения
        observeSaveStatus()

        // Обработчик кнопки Save
        btnSave.setOnClickListener {
            saveProfile()
        }

        // Клик на аватар для изменения фото
        ivUserAvatar.setOnClickListener {
            pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            viewModel.profile.collectLatest { profile ->
                etUserName.setText(profile.fullName)
                etUserPhoneNumber.setText(profile.phoneNumber)
                etUserURL.setText(profile.socialUrl)

                // Загрузка аватара если есть путь
                if (profile.avatarPath.isNotEmpty()) {
                    val uri = Uri.parse(profile.avatarPath)
                    Glide.with(this@MainActivity)
                        .load(uri)
                        .circleCrop()
                        .into(ivUserAvatar)
                    currentAvatarUri = uri
                }
            }
        }
    }

    private fun observeSaveStatus() {
        lifecycleScope.launch {
            viewModel.saveStatus.collectLatest { status ->
                when (status) {
                    is ProfileViewModel.SaveStatus.Loading -> {
                        btnSave.isEnabled = false
                        btnSave.text = "Saving..."
                    }
                    is ProfileViewModel.SaveStatus.Success -> {
                        btnSave.isEnabled = true
                        btnSave.text = "Save"
                        Toast.makeText(this@MainActivity, status.message, Toast.LENGTH_SHORT).show()
                    }
                    is ProfileViewModel.SaveStatus.Error -> {
                        btnSave.isEnabled = true
                        btnSave.text = "Save"
                        Toast.makeText(this@MainActivity, "Error: ${status.message}", Toast.LENGTH_LONG).show()
                    }
                    is ProfileViewModel.SaveStatus.Idle -> {
                        // Ничего не делаем
                    }
                }
            }
        }
    }

    private fun saveProfile() {
        val fullName = etUserName.text.toString().trim()
        val phoneNumber = etUserPhoneNumber.text.toString().trim()
        val socialUrl = etUserURL.text.toString().trim()

        // Конвертируем Uri в строку для сохранения
        val avatarPath = currentAvatarUri?.toString() ?: ""

        // Валидация
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

        // Сохранение
        viewModel.saveProfile(
            fullName = fullName,
            phoneNumber = phoneNumber,
            socialUrl = socialUrl,
            avatarPath = avatarPath
        )
    }
}