package com.aicode.feature.credentials.data.repository

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.credentials.domain.model.GitCredential
import com.aicode.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * git 凭据的文件实现（真源 = `filesDir/aicode/git-credentials`，容器内 `/root/.aicode/git-credentials`）。
 *
 * 文件采用 git-credential-store 标准格式：每行 `https://user:token@host`，每主机一条（保存同主机覆盖旧值），
 * 由容器/远程服务器的 `credential.helper=store` 直接读取，UI 与 git 共用同一份。
 * 内存以 StateFlow 缓存，写操作（save/delete）原子落盘后刷新。
 */
@Singleton
class FileCredentialRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialRepository {

    private companion object {
        const val TAG = "FileCredentialRepo"
        const val AICODE_DIR = "aicode"
        const val CREDENTIALS_NAME = "git-credentials"
    }

    private val credentialsFile: File get() = File(File(context.filesDir, AICODE_DIR), CREDENTIALS_NAME)

    private val mutex = Mutex()

    private val _credentials = MutableStateFlow(parse(credentialsFile))
    override fun getAll(): Flow<List<GitCredential>> = _credentials

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return _credentials.value.firstOrNull { it.host == normalized }
    }

    override suspend fun save(credential: GitCredential) = mutex.withLock {
        val host = credential.host.trim().lowercase()
        if (host.isEmpty()) return@withLock
        val updated = _credentials.value
            .filterNot { it.host == host } +
            GitCredential(id = host, host = host, username = credential.username.trim(), token = credential.token)
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "保存凭据 host=$host user=${credential.username}")
    }

    override suspend fun delete(id: String) = mutex.withLock {
        try {
            val updated = _credentials.value.filterNot { it.id == id }
            writeFile(updated)
            _credentials.value = updated
            FileLogger.i(TAG, "删除凭据 id=$id")
        } catch (e: Exception) {
            FileLogger.w(TAG, "删除凭据失败 id=$id", e)
            throw e
        }
    }

    /** 解析 git-credentials 文件；格式异常的行跳过。 */
    private fun parse(file: File): List<GitCredential> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                // https://user:token@host
                val at = trimmed.lastIndexOf('@')
                if (at < 0) return@mapNotNull null
                val host = trimmed.substring(at + 1).trim().lowercase()
                val cred = trimmed.substring(0, at)
                val rest = cred.substringAfter("://", "")
                val sep = rest.indexOf(':')
                if (sep < 0) return@mapNotNull null
                val username = URLDecoder.decode(rest.substring(0, sep), "UTF-8")
                val token = URLDecoder.decode(rest.substring(sep + 1), "UTF-8")
                if (host.isEmpty() || username.isEmpty()) null
                else GitCredential(id = host, host = host, username = username, token = token)
            }
        }.getOrElse {
            FileLogger.w(TAG, "解析 git-credentials 失败: ${it.message}")
            emptyList()
        }
    }

    /** 按 git-credentials 格式写文件（先写 .tmp 再 rename，避免 git 读到半截文件）。 */
    private fun writeFile(list: List<GitCredential>) {
        val sb = StringBuilder()
        list.sortedBy { it.host }.forEach { c ->
            sb.append("https://")
                .append(enc(c.username)).append(':').append(enc(c.token))
                .append('@').append(c.host)
                .append('\n')
        }
        credentialsFile.parentFile?.mkdirs()
        val tmp = File(credentialsFile.parentFile, "${credentialsFile.name}.tmp")
        // 写完必须 fsync 再 rename：rename 只保证「文件名切换原子」，不保证内容已落盘。
        // 缺少 fsync 时掉电/崩溃可能得到「文件存在但内容为空/截断」的凭据文件，
        // 表现为认证莫名失败且难排查。
        FileOutputStream(tmp).use { fos ->
            fos.write(sb.toString().toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
        // 直接 rename 覆盖（rename 本就原子地替换已存在目标）；不要先 delete，
        // 否则删除与改名之间会出现「文件不存在」窗口，并发读的容器 git 拿不到凭据。
        if (!tmp.renameTo(credentialsFile)) {
            credentialsFile.writeText(sb.toString())
            tmp.delete()
        }
    }

    // git 凭据文件的百分号编码：与 git 的 `store` helper 一致，空格用 %20（而非 URLEncoder 的 `+`）。
    private fun enc(part: String): String =
        URLEncoder.encode(part, "UTF-8").replace("+", "%20")
}