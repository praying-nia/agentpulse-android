package moe.gensoukyo.agentpulse.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.gensoukyo.agentpulse.protocol.UuidV7

private val Context.credentialDataStore by preferencesDataStore(name = "encrypted_credentials")

class CredentialVault(private val context: Context) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    suspend fun snapshot(): VaultSnapshot {
        val encoded = context.credentialDataStore.data.first()[PAYLOAD]
        if (encoded == null) {
            val fresh = VaultPayload(clientId = UuidV7.generate())
            persist(fresh)
            return VaultSnapshot(fresh.clientId, fresh.hosts)
        }
        return try {
            val payload = json.decodeFromString<VaultPayload>(decrypt(encoded))
            require(payload.schemaVersion == 1)
            UuidV7.require(payload.clientId, "client_id")
            VaultSnapshot(payload.clientId, payload.hosts)
        } catch (_: Exception) {
            context.credentialDataStore.edit { it.clear() }
            removeKey()
            val fresh = VaultPayload(clientId = UuidV7.generate())
            persist(fresh)
            VaultSnapshot(fresh.clientId, emptyList())
        }
    }

    suspend fun upsert(profile: HostProfile) {
        val current = snapshot()
        val hosts = current.hosts.filterNot { it.hostId == profile.hostId } + profile
        persist(VaultPayload(clientId = current.clientId, hosts = hosts.sortedBy { it.hostName.lowercase() }))
    }

    suspend fun forget(hostId: String) {
        val current = snapshot()
        persist(VaultPayload(clientId = current.clientId, hosts = current.hosts.filterNot { it.hostId == hostId }))
    }

    suspend fun host(hostId: String): HostProfile? = snapshot().hosts.firstOrNull { it.hostId == hostId }

    private suspend fun persist(payload: VaultPayload) {
        val encrypted = encrypt(json.encodeToString(payload))
        context.credentialDataStore.edit { it[PAYLOAD] = encrypted }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = cipher.iv + cipher.doFinal(plainText.encodeToByteArray())
        return android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val value = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        require(value.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(value.copyOfRange(IV_BYTES, value.size)).decodeToString()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun removeKey() {
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KEY_ALIAS) }
        }
    }

    companion object {
        private val PAYLOAD = stringPreferencesKey("payload_v1")
        private const val KEY_ALIAS = "agentpulse-host-credentials-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

data class VaultSnapshot(val clientId: String, val hosts: List<HostProfile>)
