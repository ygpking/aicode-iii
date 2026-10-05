package com.aicode.feature.workspace.domain.remote.ftp

import com.aicode.feature.workspace.domain.remote.RemoteAuth
import com.aicode.feature.workspace.domain.remote.RemoteFileInfo
import com.aicode.feature.workspace.domain.remote.RemoteSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FtpSyncClient : RemoteSyncClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    private val ftpClient = FTPClient()
    private var isConnected = false

    // FTP 协议是请求/响应配对，同一连接的并发调用会让响应错配；串行化后同时只跑一个操作。
    private val mutex = Mutex()

    override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) =
        withContext(Dispatchers.IO) { mutex.withLock { doConnect(host, port, username, auth) } }

    private fun doConnect(host: String, port: Int, username: String, auth: RemoteAuth) {
        if (auth !is RemoteAuth.Password) {
            throw IllegalArgumentException("FTP only supports Password authentication")
        }

        ftpClient.connectTimeout = CONNECT_TIMEOUT_MS
        ftpClient.connect(host, port)
        val reply = ftpClient.replyCode
        if (!FTPReply.isPositiveCompletion(reply)) {
            runCatching { ftpClient.disconnect() }
            throw IllegalStateException("FTP server refused connection.")
        }

        if (!ftpClient.login(username, auth.password)) {
            runCatching { ftpClient.disconnect() }
            throw IllegalStateException("FTP login failed")
        }

        ftpClient.enterLocalPassiveMode()
        ftpClient.setFileType(FTP.BINARY_FILE_TYPE)
        isConnected = true
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ftpClient.isConnected) {
                runCatching { ftpClient.logout() }
                runCatching { ftpClient.disconnect() }
            }
            isConnected = false
        }
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = ftpClient.listFiles(remotePath) ?: emptyArray()
            files.map {
                RemoteFileInfo(
                    name = it.name,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    lastModified = it.timestamp.timeInMillis
                )
            }
        }
    }

    override suspend fun downloadFile(remotePath: String, localPath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val localFile = File(localPath)
            localFile.parentFile?.mkdirs()
            FileOutputStream(localFile).use { fos ->
                val success = ftpClient.retrieveFile(remotePath, fos)
                if (!success) {
                    throw IllegalStateException("Failed to download file from FTP: $remotePath")
                }
            }
        }
    }

    override suspend fun uploadFile(localPath: String, remotePath: String) = withContext(Dispatchers.IO) {
        val localFile = File(localPath)
        if (!localFile.exists()) return@withContext

        mutex.withLock {
            // 确保远程目录存在
            val remoteDir = remotePath.substringBeforeLast("/")
            if (remoteDir.isNotEmpty() && remoteDir != remotePath) {
                 // 递归创建目录较复杂，暂简化处理
                 ftpClient.makeDirectory(remoteDir)
            }

            FileInputStream(localFile).use { fis ->
                val success = ftpClient.storeFile(remotePath, fis)
                if (!success) {
                    throw IllegalStateException("Failed to upload file to FTP: $remotePath")
                }
            }
        }
    }

    override suspend fun createDirectory(remotePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            ftpClient.makeDirectory(remotePath)
            Unit
        }
    }

    override suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // 先尝试当做文件删除，如果失败则当做目录删除
            if (!ftpClient.deleteFile(remotePath)) {
                ftpClient.removeDirectory(remotePath)
            }
        }
    }

    override suspend fun isConnected(): Boolean = isConnected && ftpClient.isConnected

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!ftpClient.isConnected) return@withLock false
            try {
                ftpClient.sendNoOp()
            } catch (e: Exception) {
                false
            }
        }
    }
}
