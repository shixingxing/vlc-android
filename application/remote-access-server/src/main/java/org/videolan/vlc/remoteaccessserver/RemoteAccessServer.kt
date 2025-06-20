/*
 * ************************************************************************
 *  RemoteAccessServer.kt
 * *************************************************************************
 * Copyright © 2025 VLC authors and VideoLAN
 * Author: Nicolas POMEPUY
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 * **************************************************************************
 *
 *
 */

package org.videolan.vlc.remoteaccessserver

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.squareup.moshi.Json
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.CachingOptions
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.session
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.cachingheaders.CachingHeaders
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.matchContentType
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerEncrypt
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.directorySessionStorage
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.util.hex
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.resources.AppContextProvider
import org.videolan.resources.VLCInstance
import org.videolan.tools.AppScope
import org.videolan.tools.KEYSTORE_PASSWORD
import org.videolan.tools.KEY_REMOTE_ACCESS_LAST_STATE_STOPPED
import org.videolan.tools.REMOTE_ACCESS_NETWORK_BROWSER_CONTENT
import org.videolan.tools.Settings
import org.videolan.tools.SingletonHolder
import org.videolan.tools.putSingle
import org.videolan.vlc.DebugLogService
import org.videolan.vlc.PlaybackService
import org.videolan.vlc.PlaybackService.Companion.playerSleepTime
import org.videolan.vlc.gui.DialogActivity
import org.videolan.vlc.media.PlaylistManager
import org.videolan.vlc.remoteaccessserver.ssl.SecretGenerator
import org.videolan.vlc.remoteaccessserver.websockets.RemoteAccessWebSockets
import org.videolan.vlc.remoteaccessserver.websockets.RemoteAccessWebSockets.setupWebSockets
import org.videolan.vlc.util.FileUtils
import org.videolan.vlc.util.Permissions
import org.videolan.vlc.util.isSchemeSMB
import org.videolan.vlc.viewmodels.CallBackDelegate
import org.videolan.vlc.viewmodels.ICallBackHandler
import org.videolan.vlc.viewmodels.browser.IPathOperationDelegate
import org.videolan.vlc.viewmodels.browser.PathOperationDelegate
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean


private const val TAG = "VLC/HttpSharingServer"
private const val NOW_PLAYING_TIMEOUT = 500

class RemoteAccessServer(private val context: Context) : PlaybackService.Callback, IPathOperationDelegate by PathOperationDelegate(), ICallBackHandler by CallBackDelegate()  {
    private var lastNowPlayingSendTime: Long = 0L
    private var lastWasPlaying: Boolean = false
    private var settings: SharedPreferences
    private lateinit var engine: NettyApplicationEngine
    var service: PlaybackService? = null
    private val networkSharesResult = ArrayList<MediaLibraryItem>()
    private val networkDiscoveryRunning = AtomicBoolean(false)
    private var lastPlayedLocation = ""


    private val _serverStatus = MutableLiveData(ServerStatus.NOT_INIT)
    val serverStatus: LiveData<ServerStatus>
        get() = _serverStatus

    private val _serverConnections = MutableLiveData(listOf<RemoteAccessConnection>())
    val serverConnections: LiveData<List<RemoteAccessConnection>>
        get() = _serverConnections

    private val otgDevice = context.getString(org.videolan.vlc.R.string.otg_device_title)

    var client: DebugLogService.Client? = null


    private val miniPlayerObserver = androidx.lifecycle.Observer<Boolean> { playing ->
        AppScope.launch {
            val isPlaying = service?.isPlaying == true || playing
            RemoteAccessWebSockets.sendToAll(PlayerStatus(isPlaying))
        }
    }

    private val browserHandler by lazy {
        val handlerThread = HandlerThread("vlc-provider-remote-access", Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
        handlerThread.start()
        Handler(handlerThread.looper)
    }

    /**
     * Observes the need to login (for the browser) and display a warning on the website
     */
    private val loginObserver = androidx.lifecycle.Observer<Boolean> { showed ->
        AppScope.launch {
            RemoteAccessWebSockets.sendToAll(LoginNeeded(showed))
        }
    }

    /**
     * Observes the resume confirmation and display a dialog on the website
     */
    private val confirmationObserver = androidx.lifecycle.Observer<String?> { mediaTitle ->
        AppScope.launch {
            RemoteAccessWebSockets.sendToAll(ResumeConfirmationNeeded(mediaTitle, mediaTitle == null))
        }
    }

    private val scope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, throwable.message, throwable)
                _serverStatus.postValue(ServerStatus.ERROR)
            })


    val downloadFolder by lazy { "${context.getExternalFilesDir(null)!!.absolutePath}/downloads" }

    init {
        Security.removeProvider("BC")
        Security.addProvider(BouncyCastleProvider())
        copyWebServer()
        PlaybackService.serviceFlow.onEach { onServiceChanged(it) }
                .onCompletion {
                    service?.removeCallback(this@RemoteAccessServer)
                    releaseCallbacks()
                }
                .launchIn(AppScope)
        Log.i(TAG, "Server stopped")
        _serverStatus.postValue(ServerStatus.STOPPED)
        settings = Settings.getInstance(context)
    }


    /**
     * Start the server. Refresh the authentication settings before
     * Also start monitoring the network shares for the web browser
     */
    suspend fun start() {
        Settings.getInstance(context).putSingle(
            KEY_REMOTE_ACCESS_LAST_STATE_STOPPED, false)
        clearFileDownloads()
        Log.i(TAG, "Server connecting")
        _serverStatus.postValue(ServerStatus.CONNECTING)
        scope.launch {
            engine = generateServer()
            engine.start()
        }
    }

    /**
     * Launch a network discovery. It directly uses a dedicated [MediaBrowser] and doesn't instantiate a Provider
     * It only launches once. It has a timeout. I sends the result back using websockets
     *
     */
    fun launchNetworkDiscovery() = scope.launch(Dispatchers.IO) {
        if (!settings.getBoolean(REMOTE_ACCESS_NETWORK_BROWSER_CONTENT, false)) {
            Log.i(TAG, "Preventing the network monitor to be collected as the network browsing is disabled")
            return@launch
        }
        if (networkDiscoveryRunning.getAndSet(true)) {
            //Already running only send current results
            if (BuildConfig.DEBUG) Log.w(TAG, "Already running")
            sendNetworkShares()
            return@launch
        }
        val job = launch {
            var finished = false
            networkSharesResult.clear()
            val mediaBrowser = MediaBrowser(VLCInstance.getInstance(context), object : MediaBrowser.EventListener {
                override fun onMediaAdded(index: Int, media: IMedia?) {
                    try {
                        MLServiceLocator.getAbstractMediaWrapper(media)
                    } catch (e: Exception) {
                        Log.e(TAG, "Unable to generate the media wrapper. It usually happen when the IMedia fields have some encoding issues", e)
                        null
                    }?.let {
                        scope.launch(Dispatchers.Main) {
                            networkSharesResult.add(it)
                            sendNetworkShares()
                        }
                    }
                    media?.release()
                }

                override fun onMediaRemoved(index: Int, media: IMedia?) {}

                override fun onBrowseEnd() {
                    if (BuildConfig.DEBUG) Log.i(TAG, "Discovery is finished")
                    finished = true
                }

            }, browserHandler)
            try {
                mediaBrowser.discoverNetworkShares()
                while (!finished) {
                    delay(1000)
                }
            } finally {
                if (BuildConfig.DEBUG) Log.i(TAG, "Discovery job releasing everything")
                mediaBrowser.changeEventListener(null)
                mediaBrowser.release()
                sendNetworkShares()
            }
        }
        // The discovery job is 30s max
        delay(30000L)
        if (BuildConfig.DEBUG) Log.i(TAG, "Discovery job timer exhausted")
        job.cancelAndJoin()
        if (BuildConfig.DEBUG) Log.i(TAG, "Discovery job quit")
        networkDiscoveryRunning.set(false)
    }

    private suspend fun sendNetworkShares() {
        if (BuildConfig.DEBUG) Log.i(TAG, "Sending network shares: ${networkSharesResult.size}")
        val list = ArrayList<PlayQueueItem>(networkSharesResult.size)
        networkSharesResult.forEachIndexed { index, mediaLibraryItem ->
            list.add(
                PlayQueueItem(
                    3000L + index, mediaLibraryItem.title, " ", 0, mediaLibraryItem.artworkMrl
                        ?: "", false, "", (mediaLibraryItem as MediaWrapper).uri.toString(), true, favorite = mediaLibraryItem.isFavorite
                )
            )
        }
        RemoteAccessWebSockets.sendToAll(NetworkShares(list))
    }

    /**
     * Stop the server and all the websocket connections
     *
     */
    suspend fun stop() {
        clearFileDownloads()
        Log.i(TAG, "Server stopping")
        _serverStatus.postValue(ServerStatus.STOPPING)
        withContext(Dispatchers.IO) {
            RemoteAccessWebSockets.closeAllSessions()
            if (::engine.isInitialized) engine.stop()
            client?.release()
        }
    }

    /**
     * Clears the folder that's used to store download zips
     * If the download is paused / aborted, it will make sure the files will be
     * deleted at server start/stop
     */
    private fun clearFileDownloads() = AppScope.launch(Dispatchers.IO) {
        val downloadDir = File(downloadFolder)
        if (downloadDir.isDirectory) downloadDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Listen to the [PlaybackService] connection
     *
     * @param service the service to listen
     */
    private fun onServiceChanged(service: PlaybackService?) {
        if (service !== null) {
            this.service = service
            service.addCallback(this)
        } else this.service?.let {
            it.removeCallback(this)
            this.service = null
        }
    }

    /**
     * Copy the web server files to serve
     *
     */
    private fun copyWebServer() {
        File(getServerFiles(context)).mkdirs()
        FileUtils.copyAssetFolder(context.assets, "dist", "${context.filesDir.path}/server", true)
    }

    /**
     * Ktor plugin intercepting all the requests
     * Used to manage a connected device list
     */
    private val InterceptorPlugin = createApplicationPlugin(name = "VLCInterceptorPlugin") {
        onCall { call ->
            call.request.origin.apply {
                val oldConnections = _serverConnections.value
                if ((oldConnections?.filter { it.ip == remoteHost }?.size ?: 0) == 0) {
                    val connection = RemoteAccessConnection(remoteHost)
                    withContext(Dispatchers.Main) {
                        try {
                            _serverConnections.value = oldConnections!!.toMutableList().apply { add(connection) }
                        } catch (e: Exception) {
                            Log.e("InterceptorPlugin", e.message, e)
                        }
                    }
                }
            }

        }
    }


    /**
     * Generate a self signed certificate and a private key
     *
     * @return a [Pair] of an X509 certificate and a private key
     */
    private fun selfSignedCertificate(): Pair<X509Certificate?, PrivateKey?>? {
        //Generate a public and private keys
        val random = SecureRandom()
        val keyGen: KeyPairGenerator = try {
            KeyPairGenerator.getInstance("RSA", "BC")
        } catch (e: java.lang.Exception) {
            Log.e(TAG, e.message, e)
            return null
        }
        keyGen.initialize(2048, random)
        val keypair = keyGen.generateKeyPair()
        val privateKey = keypair.private
        //Generate the certificate. Renewing is useless in our case as generating a different certificate will re-trigger the certificate warning in the browsers
        //That's why we set the validity to 25yrs which exceeds the device lifetime
        //If needed, we can add a setting to let the user revoking the certificate by deleting the keystore file to start all this process over
        val cert: X509Certificate
        try {
            val cal = Calendar.getInstance()
            // Start Date
            // Roll back the date by one day to prevent issues with client clock time sync
            cal.roll(Calendar.DAY_OF_MONTH, false)
            val notBefore = cal.time
            // Expiration Date
            cal.add(Calendar.YEAR, 25)
            val notAfter = cal.time
            // Build Certificate
            val owner = X500Name("CN=vlc-android, O=VideoLAN, L=Paris, C=France")
            val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(owner, BigInteger(64, random), notBefore, notAfter, owner, keypair.public)

            val signer: ContentSigner = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(privateKey)
            val certHolder: X509CertificateHolder = builder.build(signer)
            cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(certHolder)
            cert.verify(keypair.public)
        } catch (t: Throwable) {
            return null
        }
        return Pair(cert, privateKey)
    }

    /**
     * Try to retrieve the keystore password. Tries two times. If both fail, try to start over
     *
     * @param attempts the number of attempts already made
     * @return the saved keystore password else throws a RuntimeException
     */
    @SuppressLint("ApplySharedPref")
    private fun retrieveKeystorePassword(attempts: Int = 0): CharArray {

        try {
            // securely use a random password to store the key in BC keystore
            if (settings.getString(KEYSTORE_PASSWORD, "").isNullOrBlank()) {
                // No password saved. generate a password, encrypt it and save it to the preferences
                settings.putSingle(KEYSTORE_PASSWORD, SecretGenerator.encryptData(context, SecretGenerator.generateRandomString()))
            }
            // retrieve the password from the saved preferences
            return SecretGenerator.decryptData(context, settings.getString(KEYSTORE_PASSWORD, "")!!).toCharArray()
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            if (attempts > 2) throw RuntimeException("Cannot retrieve the keystore password", e)
        }
        if (attempts > 1) {
            //failed more than once. Let's try again by resetting everything to default
            SecretGenerator.removeKeys(context)
            settings.edit().remove(KEYSTORE_PASSWORD).commit()
        }
        return retrieveKeystorePassword(attempts + 1)
    }

    /**
     * Finds a free port to use
     *
     * @param default the default port to test
     * @return a port number
     */
    private fun getFreePort(default:Int): Int {
        if (BuildConfig.DEBUG) Log.d(this::class.java.simpleName, "Testing port: $default")
        var socket: ServerSocket? = null
        val port = try {
            socket = ServerSocket(default)
            socket.localPort
        } catch (e: Exception) {
            if (default == 0) throw IllegalStateException("Cannot find a free port to use")
            return getFreePort(0)
        } finally {
            socket?.close()
        }
        return port
    }

    /**
     * Generate the server
     *
     * @return the server engine
     */
    private fun generateServer(): NettyApplicationEngine {
        //retrieve the private key from the FS

        val keyStoreFile = File(context.filesDir.path, ".keystore")
        val store: KeyStore = KeyStore.getInstance("BKS", "BC")

        val password = retrieveKeystorePassword()

        //load the keystore either from disk if file exists or create a blank one
        try {
            store.load(keyStoreFile.inputStream(), "store_pass".toCharArray())
        } catch (e: Exception) {
            store.load(null, null)
        }
        val key = try {
            store.getKey("vlc-android", password)
        } catch (e: Exception) {
            Log.e(TAG, e.message, e)
            null
        }
        //try loading the certificate from the store. It will fail the first time, then reuse the stored one
        val cert = try {
            store.getCertificate("vlc-android")
        } catch (e: Exception) {
            null
        }
        // Retrieve a pair of certificate/key (load existing one if available, generate new ones if not)
        val ssc = if (cert != null && key != null)
            Pair(cert, key)
        else
            selfSignedCertificate()
        store.setKeyEntry("vlc-android", ssc!!.second, password, arrayOf(ssc.first))

        //Save the certificate to the disk
        val out = keyStoreFile.outputStream()
        store.store(out, "store_pass".toCharArray())

        val environment = applicationEngineEnvironment {
            log = LoggerFactory.getLogger("ktor.application")
            connector {
                port = getFreePort(8080)
            }
            sslConnector(
                    store,
                    "vlc-android",
                    { password },
                    { password }
            ) {
                this.port = getFreePort(8443)
            }
            module {

                install(Sessions) {

                    //get the encryption / signing keys and generate them if they don't exist
                    var encryptKey = settings.getString("cookie_encrypt_key", "") ?: ""
                    if (encryptKey.isBlank()) {
                        encryptKey = SecretGenerator.generateRandomAlphanumericString(32)
                        settings.putSingle("cookie_encrypt_key", encryptKey)
                    }
                    var signkey = settings.getString("cookie_sign_key", "") ?: ""
                    if (signkey.isBlank()) {
                        signkey = SecretGenerator.generateRandomAlphanumericString(32)
                        settings.putSingle("cookie_sign_key", signkey)
                    }

                    cookie<UserSession>("user_session", directorySessionStorage(File("${context.filesDir.path}/server/cache"), true)) {
                        cookie.maxAgeInSeconds = RemoteAccessSession.maxAge
                        transform(SessionTransportTransformerEncrypt(hex(encryptKey), hex(signkey)))
                    }
                }
                install(Authentication) {
                    session<UserSession>("user_session") {
                        validate { session ->
                            session
                        }
                        challenge {
                            call.respond(HttpStatusCode.Unauthorized)
                        }
                    }
                }
                install(InterceptorPlugin)
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(15)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }
                install(Compression) {
                    default()
                    matchContentType(
                        ContentType.Text.Plain,
                        ContentType.Image.SVG,
                        ContentType.Text.CSS,
                        ContentType.Text.Xml,
                        ContentType.Text.Html,
                        ContentType.Text.JavaScript,
                        ContentType.Application.JavaScript
                    )
                }
                install(CachingHeaders) {
                    options { _, content ->
                        when (content.contentType?.withoutParameters()) {
                            ContentType.Text.Plain,
                            ContentType.Application.Json -> {
                                CachingOptions(CacheControl.NoStore(CacheControl.Visibility.Private))
                            }
                            ContentType.Image.GIF,
                            ContentType.Image.PNG,
                            ContentType.Image.SVG,
                            ContentType.Image.JPEG,
                            ContentType.Image.XIcon,
                            ContentType.Text.CSS,
                            ContentType.Text.Xml,
                            ContentType.Text.Html,
                            ContentType.Text.JavaScript,
                            ContentType.Application.JavaScript -> {
                                CachingOptions(CacheControl.MaxAge(maxAgeSeconds = 86400, visibility = CacheControl.Visibility.Private))
                            }
                            else -> null
                        }
                    }
                }
                install(CORS) {
                    allowMethod(HttpMethod.Options)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Get)
                    allowHeader(HttpHeaders.AccessControlAllowOrigin)
                    allowHeader(HttpHeaders.ContentType)
                    anyHost()
                }
                install(PartialContent)
                if (BuildConfig.DEBUG) install(CallLogging) {
                    format { call ->
                        val status = call.response.status()
                        val httpMethod = call.request.httpMethod.value
                        val path = call.request.uri
                        val headers = call.request.headers.entries()
                                .map { it.key to it.value.joinToString(",") }
                                .joinToString("\n\t") { "${it.first}:${it.second}" }
                        "$httpMethod - $path -> $status\nheaders:\n\t$headers"
                    }
                }
                routing {
                    setupRouting(context, scope)
                    setupWebSockets(context, settings)
                }
            }
        }
        return embeddedServer(Netty, environment) {
        }.apply {
            environment.monitor.subscribe(ApplicationStarted) {
                _serverStatus.postValue(ServerStatus.STARTED)
                Log.i(TAG, "Server started")
                AppScope.launch(Dispatchers.Main) {
                    PlaylistManager.showAudioPlayer.observeForever(miniPlayerObserver)
                    DialogActivity.loginDialogShown.observeForever(loginObserver)
                    PlaybackService.waitConfirmation.observeForever(confirmationObserver)
                }
            }
            environment.monitor.subscribe(ApplicationStopped) {
                AppScope.launch(Dispatchers.Main) {
                    PlaylistManager.showAudioPlayer.removeObserver(miniPlayerObserver)
                    DialogActivity.loginDialogShown.removeObserver(loginObserver)
                    PlaybackService.waitConfirmation.removeObserver(confirmationObserver)
                }
                _serverStatus.postValue(ServerStatus.STOPPED)
            }
            watchMedia()
            scope.registerCallBacks {
                scope.launch {
                    RemoteAccessWebSockets.sendToAll(MLRefreshNeeded())
                }
            }
        }
    }

    /**
     * update callback of the [PlaybackService]
     *
     */
    override fun update() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Send now playing from update")
        if (System.currentTimeMillis() - lastNowPlayingSendTime < NOW_PLAYING_TIMEOUT && lastWasPlaying == service?.isPlaying) return
        lastNowPlayingSendTime = System.currentTimeMillis()
        lastWasPlaying = service?.isPlaying == true

        scope.launch {
            generateNowPlaying()?.let { nowPlaying ->
                AppScope.launch { RemoteAccessWebSockets.sendToAll(nowPlaying) }
            }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch { RemoteAccessWebSockets.sendToAll(playQueue) }
        }
    }

    /**
     * onMediaEvent callback of the [PlaybackService]
     *
     * @param event the event sent
     */
    override fun onMediaEvent(event: IMedia.Event) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Send now playing from onMediaEvent")
        if (event.type == IMedia.Event.ParsedChanged) {
            AppScope.launch {  RemoteAccessWebSockets.sendToAll(MLRefreshNeeded()) }
        }
        if (System.currentTimeMillis() - lastNowPlayingSendTime < NOW_PLAYING_TIMEOUT) return
        lastNowPlayingSendTime = System.currentTimeMillis()
        scope.launch {
            generateNowPlaying()?.let { nowPlaying ->
                AppScope.launch { RemoteAccessWebSockets.sendToAll(nowPlaying) }
            }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch { RemoteAccessWebSockets.sendToAll(playQueue) }
        }
    }

    /**
     * onMediaPlayerEvent callback of the [PlaybackService]
     *
     * @param event the event sent
     */
    override fun onMediaPlayerEvent(event: MediaPlayer.Event) {
        service?.currentMediaLocation?.let {
            if (it.isNotEmpty() && lastPlayedLocation != service?.currentMediaLocation) {
                lastPlayedLocation = it
            }
        }
        if (event.type == MediaPlayer.Event.EncounteredError) {
            AppScope.launch {  RemoteAccessWebSockets.sendToAll(GenericError(context.getString(R.string.invalid_location, lastPlayedLocation))) }
        }
        if (event.type != MediaPlayer.Event.TimeChanged) return
        if (System.currentTimeMillis() - lastNowPlayingSendTime < NOW_PLAYING_TIMEOUT) return
        lastNowPlayingSendTime = System.currentTimeMillis()
        scope.launch {
            generateNowPlaying()?.let { nowPlaying ->
                AppScope.launch { RemoteAccessWebSockets.sendToAll(messageObj = nowPlaying) }
            }
        }
        generatePlayQueue()?.let { playQueue ->
            AppScope.launch { RemoteAccessWebSockets.sendToAll(playQueue) }
        }
    }

    /**
     * Generate the now playing data to be sent to the client
     *
     * @return a [NowPlaying] describing the now playing
     */
    suspend fun generateNowPlaying(): NowPlaying? {
        service?.let { service ->
            service.currentMediaWrapper?.let { media ->
                val bookmarks = withContext(Dispatchers.IO) { media.bookmarks ?: arrayOf() }
                val chapters = withContext(Dispatchers.IO) { service.getChapters(-1) ?: arrayOf() }
                val speed = String.format(Locale.US, "%.2f", service.speed).toFloat()
                var sleepTimer = 0L
                withContext(Dispatchers.Main) {
                    sleepTimer = playerSleepTime.value?.time?.time ?: 0L
                }
                val isVideoPlaying = service.playlistManager.player.isVideoPlaying()
                val waitForMediaEnd = service.waitForMediaEnd
                val resetOnInteraction = service.resetOnInteraction
                val nowPlaying = NowPlaying(media.title ?: "", media.artistName
                        ?: "", service.isPlaying, isVideoPlaying, service.getTime(), service.length, media.id, media.artworkURL
                        ?: "", media.uri.toString(), getVolume(), speed, sleepTimer, waitForMediaEnd, resetOnInteraction, service.isShuffling, service.repeatType, bookmarks = bookmarks.map { WSBookmark(it.id, it.title, it.time) }, chapters = chapters.map { WSChapter(it.name, it.duration) })
                return nowPlaying

            }
        }
        return null
    }

    /**
     * Generate the play queue data to be sent to the client
     *
     * @return a [PlayQueue] describing the play queue
     */
    fun generatePlayQueue(): PlayQueue? {
        service?.let { service ->
            val list = ArrayList<PlayQueueItem>()
            service.playlistManager.getMediaList().forEachIndexed { index, mediaWrapper ->
                list.add(PlayQueueItem(mediaWrapper.id, mediaWrapper.title, mediaWrapper.artistName
                        ?: "", mediaWrapper.length, mediaWrapper.artworkMrl
                        ?: "", service.playlistManager.currentIndex == index, favorite = mediaWrapper.isFavorite))
            }
            return PlayQueue(list)
        }
        return null
    }

    /**
     * Get the volume to be sent to the client
     *
     * @return an [Int] describing the volume
     */
    private fun getVolume(): Int = when {
        service?.isVideoPlaying == true && service!!.volume > 100 -> service!!.volume
        else -> {
            (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let {
                val vol = it.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                (vol.toFloat() / max * 100).toInt()
            }
                    ?: 0
        }
    }

    /**
     * Returns the server addresses as a list
     *
     * @return the server addresses
     */
    fun getServerAddresses(): List<String> = buildList {
        getIPAddresses(true).forEach {
            add(buildString {
                if (::engine.isInitialized) {
                        append("http://")
                    append(it)
                    append(":")
                        append(engine.environment.connectors[0].port)
                }
            })
        }
    }

    fun sslEnabled():Boolean {
        if (::engine.isInitialized) {
            return engine.environment.connectors.firstOrNull { it.type.name == "HTTPS" } != null
        }
        return false
    }

    /**
     * Get IP address from first non-localhost interface
     * @param useIPv4   true=return ipv4, false=return ipv6
     * @return  address or empty string
     */
    private fun getIPAddresses(useIPv4: Boolean): List<String> {
        val results = arrayListOf<String>()
        try {
            val interfaces: List<NetworkInterface> =
                    Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                val inetAddresses: List<InetAddress> =
                        Collections.list(networkInterface.inetAddresses)
                for (inetAddress in inetAddresses) {
                    if (!inetAddress.isLoopbackAddress) {
                        val hostAddress = inetAddress.hostAddress ?: continue
                        val isIPv4 = hostAddress.indexOf(':') < 0
                        if (useIPv4) {
                            if (isIPv4) results.add(hostAddress)
                        } else {
                            if (!isIPv4) {
                                val delim = hostAddress.indexOf('%')
                                if (delim < 0)
                                    results.add(hostAddress.uppercase(Locale.getDefault()))
                                else
                                    results.add(
                                            hostAddress.substring(0, delim)
                                                    .uppercase(Locale.getDefault())
                                    )
                            }
                        }
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        return results
    }

    /**
     * Splits an [Uri] in a list of string used as the adapter items
     * Each item is a string representing a valid path
     *
     * @param uri the [Uri] that has to be split
     * @return a list of strings representing the items
     */
    fun prepareSegments(uri: Uri): MutableList<Pair<String, String>> {
        val path = Uri.decode(uri.path)
        val isOtg = path.startsWith("/tree/")
        val string = when {
            isOtg -> if (path.endsWith(':')) "" else path.substringAfterLast(':')
            else -> replaceStoragePath(path)
        }
        val list: MutableList<Pair<String, String>> = mutableListOf()
        if (isOtg) list.add(Pair(otgDevice, "root"))
        if (uri.scheme.isSchemeSMB()) {
            networkSharesResult.forEach {
                (it as? MediaWrapper)?.let { share ->
                    if (share.uri.scheme == uri.scheme && share.uri.authority == uri.authority) {
                        list.add(Pair(share.title, Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.authority).build().toString()))
                    }
                }
            }
        }

        //list of all the path chunks
        val pathParts = string.split('/').filter { it.isNotEmpty() }
        for (index in pathParts.indices) {
            //start creating the Uri
            val currentPathUri = Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.authority)
            //append all the previous paths and the current one
            for (i in 0..index) appendPathToUri(pathParts[i], currentPathUri)
            val currentUri = currentPathUri.build()
            val text: String = when {
                //substitute a storage path to its name. See [replaceStoragePath]
                PathOperationDelegate.storages.containsKey(currentUri.path) -> retrieveSafePath(PathOperationDelegate.storages.valueAt(PathOperationDelegate.storages.indexOfKey(currentUri.path)))
                else -> currentUri.lastPathSegment ?: "root"
            }
            list.add(Pair(text, currentPathUri.toString()))
        }
        return list
    }

    suspend fun gatherLogs(logcatZipPath: String) {
        var waitForClient = true
        var started = false
        val gatheringLogsStart = System.currentTimeMillis()
        //initiate a log to wait for
        val logMessage = "Starting collecting logs at ${System.currentTimeMillis()}"

        client = DebugLogService.Client(context, object : DebugLogService.Client.Callback {
            override fun onStarted(logList: List<String>) {
                started = true
                Log.d("LogsGathering", logMessage)
            }

            override fun onStopped() {
            }

            override fun onLog(msg: String) {
                //Wait for the log to initiate a save to avoid ANR
                if (msg.contains(logMessage)) {
                    if (AndroidUtil.isOOrLater && !Permissions.canWriteStorage())
                        waitForClient = false
                    else
                        client?.save()
                }
            }

            override fun onSaved(success: Boolean, path: String) {
                if (!success) {
                    client?.stop()
                    waitForClient = false
                    return
                }
                client?.stop()
                val filesToAdd = mutableListOf(path)
                //add previous crash logs
                try {
                    AppContextProvider.appContext.getExternalFilesDir(null)?.absolutePath?.let { folder ->
                        File(folder).listFiles()?.forEach {
                            if (it.isFile && (it.name.contains("crash_") || it.name.contains("logcat_"))) filesToAdd.add(it.path)
                        }
                    }
                } catch (exception: IOException) {
                    Log.w("LogsGathering", exception.message, exception)
                    client?.stop()
                    return
                }

                if (!FileUtils.zip(filesToAdd.toTypedArray(), logcatZipPath)) {
                    client?.stop()
                    return
                }
                try {
                    filesToAdd.forEach { FileUtils.deleteFile(it) }
                } catch (exception: IOException) {
                    Log.w("LogsGathering", exception.message, exception)
                    client?.stop()
                    return
                }

                waitForClient = false

            }

        })
        while (!started) {
            delay(100)
            if (System.currentTimeMillis() > gatheringLogsStart + 4000) {
                Log.w("LogsGathering", "Failed to start log gathering")
                started = true
                waitForClient = false
            }
            client?.start() == true
        }
        while (waitForClient) {
            delay(100)
            if (System.currentTimeMillis() > gatheringLogsStart + 20000) {
                Log.w("LogsGathering", "Cannot complete log gathering in time")
                waitForClient = false
            }
        }
        client?.release()
        client = null
    }

    abstract class WSMessage(val type: WSMessageType)
    data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val isVideoPlaying: Boolean, val progress: Long,
                          val duration: Long, val id: Long, val artworkURL: String, val uri: String, val volume: Int, val speed: Float,
                          val sleepTimer: Long, val waitForMediaEnd:Boolean, val resetOnInteraction:Boolean, val shuffle: Boolean, val repeat: Int,
                          val shouldShow: Boolean = PlaylistManager.playingState.value ?: false,
                          val bookmarks: List<WSBookmark> = listOf(), val chapters: List<WSChapter> = listOf()) : WSMessage(WSMessageType.NOW_PLAYING)

    data class WSBookmark(val id:Long, val title: String, val time: Long)
    data class WSChapter(val title: String, val time: Long)

    data class PlayQueue(val medias: List<PlayQueueItem>) : WSMessage(WSMessageType.PLAY_QUEUE)
    data class PlayQueueItem(val id: Long, val title: String, val artist: String, val duration: Long, val artworkURL: String, val playing: Boolean, val resolution: String = "", val path: String = "", val isFolder: Boolean = false, val progress: Long = 0L, val played: Boolean = false, var fileType: String = "", val favorite: Boolean = false)
    data class WebSocketAuthorization(val status:String, val initialMessage:String) : WSMessage(WSMessageType.AUTH)
    data class Volume(val volume: Int) : WSMessage(WSMessageType.VOLUME)
    data class PlayerStatus(val playing: Boolean) : WSMessage(WSMessageType.PLAYER_STATUS)
    data class LoginNeeded(val dialogOpened: Boolean) : WSMessage(WSMessageType.LOGIN_NEEDED)
    data class ResumeConfirmationNeeded(val mediaTitle: String?, val consumed: Boolean) : WSMessage(WSMessageType.RESUME_CONFIRMATION)
    data class MLRefreshNeeded(val refreshNeeded: Boolean = true) : WSMessage(WSMessageType.ML_REFRESH_NEEDED)
    data class BrowserDescription(val path: String, val description: String) : WSMessage(WSMessageType.BROWSER_DESCRIPTION)
    data class PlaybackControlForbidden(val forbidden: Boolean = true): WSMessage(WSMessageType.PLAYBACK_CONTROL_FORBIDDEN)
    data class GenericError(val text: String): WSMessage(WSMessageType.ERROR)
    data class NetworkShares(val shares: List<PlayQueueItem>): WSMessage(WSMessageType.NETWORK_SHARES)
    data class SearchResults(val albums: List<PlayQueueItem>, val artists: List<PlayQueueItem>, val genres: List<PlayQueueItem>, val playlists: List<PlayQueueItem>, val videos: List<PlayQueueItem>, val tracks: List<PlayQueueItem>)
    data class BreadcrumbItem(val title: String, val path: String)
    data class BrowsingResult(val content: List<PlayQueueItem>, val breadcrumb: List<BreadcrumbItem>)
    data class VideoListResult(val content: List<PlayQueueItem>, val item: String)
    data class ArtistResult(val albums: List<PlayQueueItem>, val tracks: List<PlayQueueItem>, val name: String)
    data class AlbumResult(val tracks: List<PlayQueueItem>, val name: String)
    data class PlaylistResult(val tracks: List<PlayQueueItem>, val name: String)
    data class FeedbackResult(val mail: String, val subject: String, val message: String, val file: String?)

    fun getSecureUrl(call: ApplicationCall) = "https://${call.request.host()}:${engine.environment.connectors.first { it.type.name == "HTTPS" }.port}"


    companion object : SingletonHolder<RemoteAccessServer, Context>({ RemoteAccessServer(it.applicationContext) }) {
        val byPassAuth: Boolean = BuildConfig.VLC_REMOTE_ACCESS_DEBUG

        /**
         * Get the server files path
         *
         * @return the server file poath
         */
        fun getServerFiles(context:Context): String {
            return "${context.filesDir.path}/server/"
        }
    }

    data class RemoteAccessConnection(val ip: String)

    enum class WSMessageType {
        @Json(name = "now-playing")
        NOW_PLAYING,
        @Json(name = "play-queue")
        PLAY_QUEUE,
        @Json(name = "auth")
        AUTH,
        @Json(name = "volume")
        VOLUME,
        @Json(name = "player-status")
        PLAYER_STATUS,
        @Json(name = "login-needed")
        LOGIN_NEEDED,
        @Json(name = "resume-confirmation")
        RESUME_CONFIRMATION,
        @Json(name = "ml-refresh-needed")
        ML_REFRESH_NEEDED,
        @Json(name = "browser-description")
        BROWSER_DESCRIPTION,
        @Json(name = "playback-control-forbidden")
        PLAYBACK_CONTROL_FORBIDDEN,
        @Json(name = "error")
        ERROR,
        @Json(name = "network-shares")
        NETWORK_SHARES
    }
}