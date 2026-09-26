package com.aicode.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aicode.core.util.FileLogger
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 敏感字段（provider API Key、SSH/FTP 密码）的透明加解密。
 *
 * 方案：AndroidKeyStore 中生成/持有 AES-256-GCM 密钥，密文以 `enc:v1:` 前缀 + Base64(iv‖cipher) 落盘。
 *
 * 平滑迁移：读侧对无前缀的值原样返回（旧明文），下次写入自动升级为密文；
 * 因此加密上线不需要一次性数据迁移。
 *
 * 防数据销毁：写入前先用当前状态尝试解密既有值，解不开则**拒写**（宁可保留旧密文/明文，
 * 也不要在 Keystore 短暂不可用时把真实 Key 覆盖成空）。
 *
 * 所有方法都不抛异常：加密失败时 [encrypt] 返回原文（退化为不加密，不阻断主流程）；
 * 解密失败时 [decrypt] 返回 null，由调用方决定「保留原值」还是「视为空」。
 */
@Singleton
class SecretVault @Inject constructor() {

    private companion object {
        const val TAG = "SecretVault"
        const val PREFIX = "enc:v1:"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "aicode_secret_vault_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    /** 是否为本工具产出的密文。 */
    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    /**
     * 加密敏感值。空串原样返回（不必加密空值）；已是密文的原样返回（幂等）；
     * 加密失败返回原文（不阻断流程，但会记日志）。
     */
    fun encrypt(plain: String?): String? {
        if (plain.isNullOrEmpty()) return plain
        if (isEncrypted(plain)) return plain
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            }
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            PREFIX + Base64.getEncoder().encodeToString(payload)
        } catch (e: Exception) {
            FileLogger.e(TAG, "加密敏感值失败，退化为明文存储", e)
            plain
        }
    }

    /**
     * 解密敏感值。无前缀视为旧明文原样返回；解密失败返回 null
     * （不要静默返回原密文，否则会把密文当 Key 用并覆盖真实值）。
     */
    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return stored
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (payload.size <= IV_LENGTH) {
                FileLogger.w(TAG, "密文载荷长度异常，解密失败")
                return null
            }
            val iv = payload.copyOfRange(0, IV_LENGTH)
            val body = payload.copyOfRange(IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            FileLogger.e(TAG, "解密敏感值失败（Keystore 不可用或密钥变更）", e)
            null
        }
    }

    /**
     * 写入前置校验：确认「已存储的值」仍可读，避免 Keystore 故障时把真实值覆盖成不可恢复的密文。
     *
     * @return true 表示可安全写入；false 表示既有密文解不开，调用方应拒写。
     */
    fun canSafelyOverwrite(existingStored: String?): Boolean {
        if (existingStored.isNullOrEmpty()) return true
        if (!isEncrypted(existingStored)) return true
        return decrypt(existingStored) != null
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
