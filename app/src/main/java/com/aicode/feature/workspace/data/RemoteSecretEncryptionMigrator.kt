package com.aicode.feature.workspace.data

import com.aicode.core.security.SecretVault
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性把历史明文的远程连接敏感字段加密回写：密码（authData）与 passphrase。
 *
 * 这些字段此前明文存 Room，改用 [SecretVault] 加密后需补齐存量数据，否则用户在「编辑连接」页
 * 看到的是明文、且新写入与旧数据口径不一致。幂等：已加密的字段跳过（[SecretVault.isEncrypted]），
 * 可重复调用。只处理 PASSWORD 类型的 authData——PRIVATE_KEY 的 authData 是私钥**路径**，非敏感。
 */
@Singleton
class RemoteSecretEncryptionMigrator @Inject constructor(
    private val remoteConnectionDao: RemoteConnectionDao,
    private val secretVault: SecretVault
) {
    private companion object {
        const val TAG = "RemoteSecretMigrator"
    }

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        runCatching { migrateConnections() }
            .onFailure { FileLogger.w(TAG, "迁移远程连接敏感字段失败", it) }
    }

    /** 已加密则原样返回，未加密则加密（幂等）。 */
    private fun enc(value: String?): String? =
        value?.let { if (secretVault.isEncrypted(it)) it else secretVault.encrypt(it) }

    private suspend fun migrateConnections() {
        val connections = remoteConnectionDao.getAllConnectionsOnce()
        val migrated = connections.map { c ->
            c.copy(
                authData = if (c.authType.equals("PASSWORD", ignoreCase = true)) enc(c.authData).orEmpty() else c.authData,
                passphrase = enc(c.passphrase)
            )
        }
        if (migrated != connections) {
            remoteConnectionDao.insertAllConnections(migrated)
            FileLogger.i(TAG, "已加密 ${connections.size} 个远程连接的敏感字段")
        }
    }
}
