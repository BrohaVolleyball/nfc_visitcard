package com.example.nfc_visitcard.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nfc_visitcard.data.DataStoreManager
import com.example.nfc_visitcard.data.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(private val dataStoreManager: DataStoreManager) : ViewModel() {

    private val _profile = MutableStateFlow(UserProfile.newBuilder().build())
    val profile: StateFlow<UserProfile> = _profile.asStateFlow()

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Idle)
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            dataStoreManager.profileFlow.collect { profile ->
                _profile.value = profile
            }
        }
    }

    fun saveProfile(
        fullName: String,
        phoneNumber: String,
        socialUrl: String,
        avatarPath: String = ""
    ) {
        viewModelScope.launch {
            _saveStatus.value = SaveStatus.Loading
            try {
                dataStoreManager.saveProfile(
                    fullName = fullName,
                    phoneNumber = phoneNumber,
                    socialUrl = socialUrl,
                    avatarPath = avatarPath
                )
                _saveStatus.value = SaveStatus.Success("Profile saved successfully!")
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearProfile() {
        viewModelScope.launch {
            try {
                dataStoreManager.clearProfile()
                _saveStatus.value = SaveStatus.Success("Profile cleared!")
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    sealed class SaveStatus {
        object Idle : SaveStatus()
        object Loading : SaveStatus()
        data class Success(val message: String) : SaveStatus()
        data class Error(val message: String) : SaveStatus()
    }

    // Factory для создания ViewModel
    class Factory(private val dataStoreManager: DataStoreManager) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return ProfileViewModel(dataStoreManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}