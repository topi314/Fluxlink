/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server.io

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.ISocketContext
import dev.arbjerg.lavalink.api.PluginEventHandler
import dev.arbjerg.lavalink.protocol.v4.Message
import dev.arbjerg.lavalink.protocol.v4.json
import io.undertow.websockets.core.WebSocketCallback
import io.undertow.websockets.core.WebSocketChannel
import io.undertow.websockets.core.WebSockets
import io.undertow.websockets.jsr.UndertowSession
import kotlinx.serialization.SerializationStrategy
import lavalink.server.config.ServerConfig
import lavalink.server.livekit.LiveKitClient
import lavalink.server.livekit.LiveKitVoiceConnection
import lavalink.server.player.LavalinkPlayer
import org.slf4j.LoggerFactory
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession
import java.util.*
import java.util.concurrent.*

class SocketContext(
    override val sessionId: String,
    val audioPlayerManager: AudioPlayerManager,
    private val serverConfig: ServerConfig,
    private var session: WebSocketSession,
    private val socketServer: SocketServer,
    statsCollector: StatsCollector,
    override val userId: Long,
    override val clientName: String?,
    val liveKit: LiveKitClient,
    eventHandlers: Collection<PluginEventHandler>,
    private val pluginInfoModifiers: List<AudioPluginInfoModifier>,
) : ISocketContext {

    companion object {
        private val log = LoggerFactory.getLogger(SocketContext::class.java)
    }

    //guildId <-> LavalinkPlayer
    override val players = ConcurrentHashMap<Long, LavalinkPlayer>()

    val eventEmitter = EventEmitter(this, eventHandlers)

    @Volatile
    var sessionPaused = false
    private val resumeEventQueue = ConcurrentLinkedQueue<String>()

    /** Null means disabled. See implementation notes */
    var resumable: Boolean = false
    var resumeTimeout = 60L // Seconds
    private var sessionTimeoutFuture: ScheduledFuture<Unit>? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    val playerUpdateService: ScheduledExecutorService

    override val state: ISocketContext.State
        get() = when {
            session.isOpen -> ISocketContext.State.OPEN
            sessionPaused -> ISocketContext.State.RESUMABLE
            else -> ISocketContext.State.DESTROYED
        }

    val playingPlayers: List<LavalinkPlayer>
        get() {
            val newList = LinkedList<LavalinkPlayer>()
            players.values.forEach { player -> if (player.isPlaying) newList.add(player) }
            return newList
        }


    init {
        val task = statsCollector.createTask(this)
        executor.scheduleAtFixedRate(task, 0, 1, TimeUnit.MINUTES)
        playerUpdateService = Executors.newScheduledThreadPool(2) { r ->
            val thread = Thread(r)
            thread.name = "player-update"
            thread.isDaemon = true
            thread
        }
    }

    override fun getPlayer(guildId: Long) = players.computeIfAbsent(guildId) {
        val player = LavalinkPlayer(this, guildId, serverConfig, audioPlayerManager, pluginInfoModifiers)
        eventEmitter.onNewPlayer(player)
        player
    }

    /**
     * Gets or creates a LiveKit voice connection for the given player's guild.
     */
    fun getVoiceConnection(player: LavalinkPlayer): LiveKitVoiceConnection {
        val guildId = player.guildId
        var conn = liveKit.getConnection(guildId)
        if (conn == null) {
            conn = liveKit.createConnection(guildId)
            conn.setEventListener(VoiceEventHandler(player))
        }
        return conn
    }

    /**
     * Disposes of a voice connection
     */
    override fun destroyPlayer(guildId: Long) {
        val player = players.remove(guildId)
        if (player != null) {
            eventEmitter.onDestroyPlayer(player)
            player.destroy()
        }
        liveKit.destroyConnection(guildId)
    }

    fun pause() {
        sessionPaused = true
        sessionTimeoutFuture = executor.schedule<Unit>({
            socketServer.onSessionResumeTimeout(this)
        }, resumeTimeout, TimeUnit.SECONDS)
        eventEmitter.onSocketContextPaused()
    }

    override fun <T : Any?> sendMessage(serializer: SerializationStrategy<T>, message: T) =
        send(json.encodeToString(serializer, message))

    /**
     * Either sends the payload now or queues it up
     */
    private fun send(payload: String) {
        eventEmitter.onWebSocketMessageOut(payload)

        if (sessionPaused) {
            resumeEventQueue.add(payload)
            return
        }

        if (!session.isOpen) return

        val undertowSession = (session as StandardWebSocketSession).nativeSession as UndertowSession
        WebSockets.sendText(payload, undertowSession.webSocketChannel,
            object : WebSocketCallback<Void> {
                override fun complete(channel: WebSocketChannel, context: Void?) {
                    log.trace("Sent $payload")
                }

                override fun onError(channel: WebSocketChannel, context: Void?, throwable: Throwable) {
                    log.error("Error", throwable)
                }
            })
    }

    /**
     * @return true if we can resume, false otherwise
     */
    fun stopResumeTimeout() = sessionTimeoutFuture?.cancel(false) ?: false

    fun resume(session: WebSocketSession) {
        sessionPaused = false
        this.session = session
        sendMessage(Message.Serializer, Message.ReadyEvent(true, sessionId))
        log.info("Replaying ${resumeEventQueue.size} events")

        // Bulk actions are not guaranteed to be atomic, so we need to do this imperatively
        while (resumeEventQueue.isNotEmpty()) {
            send(resumeEventQueue.remove())
        }

        players.values.forEach { SocketServer.sendPlayerUpdate(this, it) }
    }

    internal fun shutdown() {
        log.info("Shutting down ${playingPlayers.size} playing players.")
        executor.shutdown()
        playerUpdateService.shutdown()
        players.values.forEach {
            this.destroyPlayer(it.guildId)
        }
        liveKit.close()
        eventEmitter.onSocketContextDestroyed()
    }

    override fun closeWebSocket(closeCode: Int, reason: String?) {
        session.close(CloseStatus(closeCode, reason))
    }

    override fun closeWebSocket(closeCode: Int) {
        closeWebSocket(closeCode, null)
    }

    override fun closeWebSocket() {
        session.close()
    }

    private inner class VoiceEventHandler(private val player: LavalinkPlayer) : LiveKitVoiceConnection.EventListener {
        override fun onConnected() {
            SocketServer.sendPlayerUpdate(this@SocketContext, player)
        }

        override fun onDisconnected(code: Int, reason: String) {
            val event = Message.EmittedEvent.WebSocketClosedEvent(
                player.guildId.toString(),
                code,
                reason,
                true
            )
            sendMessage(Message.Serializer, event)
            SocketServer.sendPlayerUpdate(this@SocketContext, player)
        }

        override fun onError(cause: Throwable) {
            log.error("LiveKit voice connection error for guild ${player.guildId}", cause)
        }
    }
}
