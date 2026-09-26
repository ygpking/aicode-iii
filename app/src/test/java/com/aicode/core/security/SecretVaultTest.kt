package com.aicode.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SecretVault 的纯逻辑语义测试（JVM，无 Keystore）。
 *
 * 覆盖不依赖 AndroidKeyStore 的行为：前缀判定、旧明文回落、空值处理，以及
 * 「密文无法解密 → 返回 null / 拒写」这条防数据销毁的关键路径（本环境无 AndroidKeyStore，
 * 恰好等价于 Keystore 不可用的场景）。
 */
class SecretVaultTest {

    private val vault = SecretVault()

    @Test
    fun recognizesOwnCiphertextPrefix() {
        assertTrue(vault.isEncrypted("enc:v1:AAAA"))
        assertFalse(vault.isEncrypted("sk-plaintext"))
        assertFalse(vault.isEncrypted(""))
        assertFalse(vault.isEncrypted(null))
    }

    @Test
    fun nullAndEmptyPassThrough() {
        assertNull(vault.encrypt(null))
        assertNull(vault.decrypt(null))
        assertEquals("", vault.encrypt(""))
        assertEquals("", vault.decrypt(""))
    }

    @Test
    fun plaintextFallsBackVerbatim() {
        assertEquals("sk-legacy-plain", vault.decrypt("sk-legacy-plain"))
    }

    @Test
    fun decryptReturnsNullWhenKeystoreUnavailable() {
        // 本环境无 AndroidKeyStore：等价于 Keystore 故障/密钥变更。必须返回 null（fail closed），
        // 绝不能把密文原样当明文返回，否则会被当 Key 使用并覆盖真实值。
        assertNull(vault.decrypt("enc:v1:bm90LWEtcmVhbC1wYXlsb2Fk"))
    }

    @Test
    fun canSafelyOverwriteAllowsNullAndPlaintext() {
        assertTrue(vault.canSafelyOverwrite(null))
        assertTrue(vault.canSafelyOverwrite(""))
        assertTrue(vault.canSafelyOverwrite("sk-legacy-plain"))
    }

    @Test
    fun canSafelyOverwriteRejectsUndecryptableCiphertext() {
        assertFalse(vault.canSafelyOverwrite("enc:v1:bm90LWEtcmVhbC1wYXlsb2Fk"))
    }

    @Test
    fun encryptAlreadyEncryptedIsIdempotent() {
        // 已是密文的值原样返回（不再二次加密），避免嵌套前缀。
        assertEquals("enc:v1:AAAA", vault.encrypt("enc:v1:AAAA"))
    }
}
