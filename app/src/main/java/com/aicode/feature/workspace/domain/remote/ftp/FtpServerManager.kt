package com.aicode.feature.workspace.domain.remote.ftp

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import com.aicode.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ftpServerDataStore by preferencesDataStore(
    name = "ftp_server_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

@Singleton
class FtpServerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secretVault: com.aicode.core.security.SecretVault
) {
    private companion object {
        const val TAG = "FtpServerManager"
        val PORT_KEY = intPreferencesKey("port")
        val USERNAME_KEY = stringPreferencesKey("username")
        val PASSWORD_KEY = stringPreferencesKey("password")
        val ANONYMOUS_KEY = booleanPreferencesKey("anonymous")
        val AUTO_START_KEY = booleanPreferencesKey("auto_start")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ftpServer: FtpServer? = null

    /**
     * 启停互斥。仅 `withContext(Dispatchers.IO)` 只切线程池、不提供互斥：
     * `init` 块自动启动与用户 UI 操作天然并发，两个 start 会先后建出两个 server 实例，
     * 后写覆盖前一个引用，而先那个仍在监听端口且已无引用可停——UI 显示「已停止」
     * 而 FTP 仍在对外提供工作区读写。
     */
    private val lifecycleMutex = Mutex()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _port = MutableStateFlow(2121)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _username = MutableStateFlow("aicode")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isAnonymous = MutableStateFlow(false)
    val isAnonymous: StateFlow<Boolean> = _isAnonymous.asStateFlow()

    private val _autoStart = MutableStateFlow(false)
    val autoStart: StateFlow<Boolean> = _autoStart.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val defaultSharedPath: String by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }.absolutePath
    }

    init {
        scope.launch {
            val prefs = context.ftpServerDataStore.data.first()
            _port.value = prefs[PORT_KEY] ?: 2121
            _username.value = prefs[USERNAME_KEY] ?: "aicode"
            val storedPassword = secretVault.decrypt(prefs[PASSWORD_KEY])
            if (storedPassword.isNullOrBlank()) {
                // 首次使用生成随机强口令并持久化：避免默认弱口令 123456 在局域网暴露工作区。
                val generated = generatePassword()
                _password.value = generated
                context.ftpServerDataStore.edit { it[PASSWORD_KEY] = secretVault.encrypt(generated).orEmpty() }
            } else {
                _password.value = storedPassword
            }
            _isAnonymous.value = prefs[ANONYMOUS_KEY] ?: false
            _autoStart.value = prefs[AUTO_START_KEY] ?: false

            updateServerUrl()

            if (_autoStart.value) {
                startServer()
            }
        }
    }

    private fun generatePassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..12).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "获取本机 IP 失败: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    private fun updateServerUrl() {
        val ip = getLocalIpAddress()
        _serverUrl.value = "ftp://$ip:${_port.value}"
    }

    suspend fun saveConfig(port: Int, username: String, password: String, isAnonymous: Boolean, autoStart: Boolean) = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val wasRunning = _isRunning.value
            if (wasRunning) {
                stopServerInternal()
            }

            _port.value = port
            _username.value = username
            _password.value = password
            _isAnonymous.value = isAnonymous
            _autoStart.value = autoStart
            updateServerUrl()

            context.ftpServerDataStore.edit { prefs ->
                prefs[PORT_KEY] = port
                prefs[USERNAME_KEY] = username
                prefs[PASSWORD_KEY] = secretVault.encrypt(password).orEmpty()
                prefs[ANONYMOUS_KEY] = isAnonymous
                prefs[AUTO_START_KEY] = autoStart
            }

            if (wasRunning) {
                startServerInternal()
            }
        }
    }

    suspend fun toggleServer(): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (_isRunning.value) {
                stopServerInternal()
                false
            } else {
                startServerInternal()
            }
        }
    }

    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (_isRunning.value) return@withLock true
            startServerInternal()
        }
    }

    suspend fun stopServer() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (!_isRunning.value) return@withLock
            stopServerInternal()
        }
    }

    private fun startServerInternal(): Boolean {
        try {
            _errorMessage.value = null
            stopServerInternal()

            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            listenerFactory.port = _port.value
            serverFactory.addListener("default", listenerFactory.createListener())

            val userManagerFactory = PropertiesUserManagerFactory()
            val propFile = File(context.cacheDir, "ftp_users.properties")
            // 启动前整文件重建白名单：清空用户名/匿名时旧账号会永久留在这个固定路径的文件里，
            // 用户以为已关闭共享，旧凭据仍能登录并写全部工作区。
            if (propFile.exists() && !propFile.delete()) {
                FileLogger.w(TAG, "旧 FTP 账号文件删除失败，旧账号可能仍可登录: ${propFile.absolutePath}")
            }
            propFile.createNewFile()
            userManagerFactory.file = propFile
            userManagerFactory.passwordEncryptor = ClearTextPasswordEncryptor()
            val userManager = userManagerFactory.createUserManager()

            val authorities = ArrayList<Authority>()
            authorities.add(WritePermission())
            authorities.add(ConcurrentLoginPermission(20, 20))

            // 正常用户
            if (_username.value.isNotBlank()) {
                val user = BaseUser()
                user.name = _username.value
                user.password = _password.value
                user.homeDirectory = defaultSharedPath
                user.authorities = authorities
                userManager.save(user)
            }

            // 匿名用户：**只读**。匿名登录无身份，若给写权限，局域网内任何人可改写/删除
            // 全部工作区（homeDirectory 即 filesDir/projects，含所有工作区）。只给读写权限中的读，
            // 并限制并发。需要写入请用实名账号。
            if (_isAnonymous.value) {
                val anonUser = BaseUser()
                anonUser.name = "anonymous"
                anonUser.homeDirectory = defaultSharedPath
                anonUser.authorities = listOf(ConcurrentLoginPermission(5, 5))
                userManager.save(anonUser)
            }

            serverFactory.userManager = userManager
            val server = serverFactory.createServer()
            server.start()
            ftpServer = server
            _isRunning.value = true
            updateServerUrl()
            FileLogger.i(TAG, "内置 FTP 服务端已启动: ${_serverUrl.value}, 共享目录: $defaultSharedPath")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "启动内置 FTP 服务端失败: ${e.message}", e)
            _errorMessage.value = e.message ?: "启动失败，可能端口被占用"
            _isRunning.value = false
            return false
        }
    }

    private fun stopServerInternal() {
        try {
            ftpServer?.stop()
            ftpServer = null
            _isRunning.value = false
            FileLogger.i(TAG, "内置 FTP 服务端已停止")
        } catch (e: Exception) {
            FileLogger.e(TAG, "停止内置 FTP 服务端出错: ${e.message}", e)
        }
    }
}
