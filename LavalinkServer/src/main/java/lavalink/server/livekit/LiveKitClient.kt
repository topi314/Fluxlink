package lavalink.server.livekit

import dev.onvoid.webrtc.PeerConnectionFactory
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session LiveKit client managing voice connections for multiple guilds.
 * Equivalent to what KoeClient was for the Discord voice layer.
 */
class LiveKitClient(
    private val userId: Long,
    private val httpClient: OkHttpClient,
    private val peerConnectionFactory: PeerConnectionFactory
) {
    companion object {
        private val log = LoggerFactory.getLogger(LiveKitClient::class.java)
    }

    private val connections = ConcurrentHashMap<Long, LiveKitVoiceConnection>()

    fun getConnection(guildId: Long): LiveKitVoiceConnection? = connections[guildId]

    fun createConnection(guildId: Long): LiveKitVoiceConnection {
        val existing = connections[guildId]
        if (existing != null) return existing

        val connection = LiveKitVoiceConnection(httpClient, peerConnectionFactory)
        connections[guildId] = connection
        log.debug("Created LiveKit voice connection for guild {} (user {})", guildId, userId)
        return connection
    }

    fun destroyConnection(guildId: Long) {
        val connection = connections.remove(guildId) ?: return
        log.debug("Destroying LiveKit voice connection for guild {} (user {})", guildId, userId)
        connection.close()
    }

    fun close() {
        log.debug("Closing all LiveKit voice connections for user {}", userId)
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
