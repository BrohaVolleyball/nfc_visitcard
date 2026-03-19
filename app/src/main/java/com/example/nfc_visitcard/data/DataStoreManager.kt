package com.example.nfc_visitcard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.example.nfc_visitcard.data.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.InputStream
import java.io.OutputStream

// Расширение для создания DataStore
val Context.userProfileDataStore: DataStore<UserProfile> by dataStore(
    fileName = "user_profile.pb",
    serializer = UserProfileSerializer
)

// Сериализатор для Proto
object UserProfileSerializer : Serializer<UserProfile> {
    override val defaultValue: UserProfile = UserProfile.newBuilder().build()

    override suspend fun readFrom(input: InputStream): UserProfile {
        return try {
            UserProfile.parseFrom(input)
        } catch (e: Exception) {
            e.printStackTrace()
            UserProfile.newBuilder().build()
        }
    }

    override suspend fun writeTo(t: UserProfile, output: OutputStream) {
        t.writeTo(output)
    }
}

// Менеджер для работы с DataStore
class DataStoreManager(private val context: Context) {

    val profileFlow: Flow<UserProfile> = context.userProfileDataStore.data
        .catch { exception ->
            exception.printStackTrace()
            emit(UserProfile.newBuilder().build())
        }

    suspend fun saveProfile(
        fullName: String,
        phoneNumber: String,
        socialUrl: String,
        avatarPath: String = ""
    ) {
        val profile = UserProfile.newBuilder()
            .setFullName(fullName)
            .setPhoneNumber(phoneNumber)
            .setSocialUrl(socialUrl)
            .setAvatarPath(avatarPath)
            .build()

        context.userProfileDataStore.updateData { profile }
    }

    suspend fun clearProfile() {
        context.userProfileDataStore.updateData {
            UserProfile.newBuilder().build()
        }
    }
}