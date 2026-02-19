package lavalink.server.livekit

import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebSocket-based signaling client for the LiveKit SFU.
 *
 * Handles the protobuf signaling protocol: join, SDP exchange, ICE trickle,
 * and track publication.
 */
class LiveKitSignalClient(
    private val httpClient: OkHttpClient
) {
    companion object {
        private val log = LoggerFactory.getLogger(LiveKitSignalClient::class.java)
        private const val PROTOCOL_VERSION = 13
    }

    interface Listener {
        fun onJoin(response: LivekitRtc.JoinResponse)
        fun onAnswer(sdp: LivekitRtc.SessionDescription)
        fun onOffer(sdp: LivekitRtc.SessionDescription)
        fun onTrickle(trickle: LivekitRtc.TrickleRequest)
        fun onTrackPublished(response: LivekitRtc.TrackPublishedResponse)
        fun onClose(code: Int, reason: String)
        fun onError(error: Throwable)
    }

    private var webSocket: WebSocket? = null
    private var listener: Listener? = null
    private val connected = AtomicBoolean(false)
    private val joinFuture = CompletableFuture<LivekitRtc.JoinResponse>()
    private val pendingMessages = ConcurrentLinkedQueue<LivekitRtc.SignalRequest>()

    val isConnected: Boolean
        get() = connected.get()

    fun connect(url: String, token: String, listener: Listener): CompletableFuture<LivekitRtc.JoinResponse> {
        this.listener = listener

        val wsUrl = buildWsUrl(url, token)
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = httpClient.newWebSocket(request, SignalWebSocketListener())
        return joinFuture
    }

    fun sendOffer(sdp: LivekitRtc.SessionDescription) {
        send(LivekitRtc.SignalRequest.newBuilder().setOffer(sdp).build())
    }

    fun sendAnswer(sdp: LivekitRtc.SessionDescription) {
        send(LivekitRtc.SignalRequest.newBuilder().setAnswer(sdp).build())
    }

    fun sendTrickle(trickle: LivekitRtc.TrickleRequest) {
        send(LivekitRtc.SignalRequest.newBuilder().setTrickle(trickle).build())
    }

    fun sendAddTrack(request: LivekitRtc.AddTrackRequest) {
        send(LivekitRtc.SignalRequest.newBuilder().setAddTrack(request).build())
    }

    fun sendPing(request: LivekitRtc.SignalRequest) {
        send(request)
    }

    fun close() {
        connected.set(false)
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }

    private fun send(request: LivekitRtc.SignalRequest) {
        val ws = webSocket
        if (ws == null || !connected.get()) {
            pendingMessages.add(request)
            return
        }
        val bytes = request.toByteArray()
        ws.send(bytes.toByteString(0, bytes.size))
    }

    private fun flushPendingMessages() {
        while (pendingMessages.isNotEmpty()) {
            val msg = pendingMessages.poll() ?: break
            send(msg)
        }
    }

    private fun buildWsUrl(url: String, token: String): String {
        val base = url.trimEnd('/')
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .let { if (!it.startsWith("ws://") && !it.startsWith("wss://")) "wss://$it" else it }

        return "$base/rtc?access_token=$token&auto_subscribe=0&protocol=$PROTOCOL_VERSION"
    }

    private inner class SignalWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.debug("LiveKit signaling WebSocket opened")
            connected.set(true)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            try {
                val response = LivekitRtc.SignalResponse.parseFrom(bytes.toByteArray())
                handleSignalResponse(response)
            } catch (e: Exception) {
                log.error("Failed to parse LiveKit signal response", e)
                listener?.onError(e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log.debug("LiveKit signaling WebSocket closing: {} {}", code, reason)
            connected.set(false)
            listener?.onClose(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.error("LiveKit signaling WebSocket failure", t)
            connected.set(false)
            if (!joinFuture.isDone) {
                joinFuture.completeExceptionally(t)
            }
            listener?.onError(t)
        }
    }

    private fun handleSignalResponse(response: LivekitRtc.SignalResponse) {
        when (response.messageCase) {
            LivekitRtc.SignalResponse.MessageCase.JOIN -> {
                val joinResponse = response.join
                log.debug("Received JoinResponse for room: {}", joinResponse.room.name)
                flushPendingMessages()
                joinFuture.complete(joinResponse)
                listener?.onJoin(joinResponse)
            }

            LivekitRtc.SignalResponse.MessageCase.ANSWER -> {
                log.debug("Received SDP answer")
                listener?.onAnswer(response.answer)
            }

            LivekitRtc.SignalResponse.MessageCase.OFFER -> {
                log.debug("Received SDP offer (subscriber)")
                listener?.onOffer(response.offer)
            }

            LivekitRtc.SignalResponse.MessageCase.TRICKLE -> {
                log.debug("Received ICE trickle for target: {}", response.trickle.target)
                listener?.onTrickle(response.trickle)
            }

            LivekitRtc.SignalResponse.MessageCase.TRACK_PUBLISHED -> {
                log.debug("Track published: sid={}", response.trackPublished.track.sid)
                listener?.onTrackPublished(response.trackPublished)
            }

            LivekitRtc.SignalResponse.MessageCase.LEAVE -> {
                log.info("Server requested leave")
                close()
            }

            LivekitRtc.SignalResponse.MessageCase.PONG,
            LivekitRtc.SignalResponse.MessageCase.PONG_RESP -> {
                // Heartbeat response, nothing to do
            }

            else -> {
                log.trace("Unhandled signal response: {}", response.messageCase)
            }
        }
    }
}
