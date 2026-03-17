package lavalink.server.livekit

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Global singleton managing the WebRTC factory and OkHttp client shared
 * across all LiveKit voice connections.
 * Equivalent to what [moe.kyokobot.koe.Koe] was for Discord voice.
 */
class LiveKitManager : AutoCloseable {

    companion object {
        private val log = LoggerFactory.getLogger(LiveKitManager::class.java)
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val audioModule: HeadlessAudioDeviceModule = HeadlessAudioDeviceModule()
	private val peerConnectionFactory: PeerConnectionFactory

    init {
	    audioModule.initRecording()
        audioModule.startRecording()
        peerConnectionFactory = PeerConnectionFactory(audioModule)
        log.info("LiveKit voice manager initialized (WebRTC PeerConnectionFactory ready)")
    }

    fun newClient(userId: Long): LiveKitClient {
        return LiveKitClient(userId, httpClient, peerConnectionFactory)
    }

    override fun close() {
        log.info("Shutting down LiveKit voice manager")
        peerConnectionFactory.dispose()
        try {
            audioModule.stopRecording()
        } catch (e: Throwable) {
            log.debug("Error stopping recording", e)
        } finally {
            audioModule.dispose()
        }
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
