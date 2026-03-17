package lavalink.server.livekit

/**
 * Holds the information needed to connect to a LiveKit room.
 * Room name and participant identity are embedded in the JWT token.
 *
 * @param url The LiveKit server WebSocket URL (e.g. wss://livekit.example.com)
 * @param token The LiveKit access token (JWT) containing room and identity grants
 */
data class LiveKitConnectionInfo(
    val url: String,
    val token: String
)
