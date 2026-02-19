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
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.IPlayer
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer.Companion.sendPlayerUpdate
import lavalink.server.livekit.LiveKitVoiceConnection
import lavalink.server.player.filters.FilterChain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val SAMPLE_RATE = 48000
private const val CHANNELS = 2
private const val FRAME_SAMPLES = 480 // 10ms at 48kHz (webrtc-java recommended chunk size)
private const val FRAME_SIZE_BYTES = FRAME_SAMPLES * CHANNELS * 2
private const val FRAME_DURATION_MS = 10L

class LavalinkPlayer(
    override val socketContext: SocketContext,
    override val guildId: Long,
    private val serverConfig: ServerConfig,
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
) : AudioEventAdapter(), IPlayer {

	companion object {
		private val log = org.slf4j.LoggerFactory.getLogger(LavalinkPlayer::class.java)
	}

    private val buffer = ByteBuffer.allocate(FRAME_SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    private val mutableFrame = MutableAudioFrame().apply { setBuffer(buffer) }

    val audioLossCounter = AudioLossCounter()
    var endMarkerHit = false
    var filters: FilterChain = FilterChain()
        set(value) {
            audioPlayer.setFilterFactory(value.takeIf { it.isEnabled })
            field = value
        }

    override val audioPlayer: AudioPlayer = audioPlayerManager.createPlayer().also {
        it.addListener(this)
        it.addListener(EventEmitter(audioPlayerManager, this, pluginInfoModifiers))
        it.addListener(audioLossCounter)
    }

    private var updateFuture: ScheduledFuture<*>? = null
    private var audioSendFuture: ScheduledFuture<*>? = null
    @Volatile
    private var voiceConnection: LiveKitVoiceConnection? = null

    private val audioSendExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "lk-audio-$guildId").apply { isDaemon = true }
    }

    override val isPlaying: Boolean
        get() = audioPlayer.playingTrack != null && !audioPlayer.isPaused

    override val track: AudioTrack?
        get() = audioPlayer.playingTrack

    fun destroy() {
        stopAudioSendLoop()
        audioSendExecutor.shutdown()
        audioPlayer.destroy()
    }

    fun provideTo(connection: LiveKitVoiceConnection) {
        log.info("Binding player for guild {} to voice connection (isOpen={}, track={})",
            guildId, connection.isOpen, audioPlayer.playingTrack?.info?.title)
        voiceConnection = connection
        startAudioSendLoop()
    }

    override fun play(track: AudioTrack) {
        audioPlayer.playTrack(track)
        sendPlayerUpdate(socketContext, this)
    }

    override fun stop() {
        audioPlayer.stopTrack()
    }

    override fun setPause(pause: Boolean) {
        audioPlayer.isPaused = pause
    }

    override fun seekTo(position: Long) {
        val track = audioPlayer.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        track.position = position
    }

    override fun setVolume(volume: Int) {
        audioPlayer.volume = volume
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        updateFuture?.cancel(false)
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        if (updateFuture?.isCancelled == false) {
            return
        }

        updateFuture = socketContext.playerUpdateService.scheduleAtFixedRate(
            { sendPlayerUpdate(socketContext, this) },
            0,
            serverConfig.playerUpdateInterval.toLong(),
            TimeUnit.SECONDS
        )
    }

    private fun startAudioSendLoop() {
        stopAudioSendLoop()
        audioSendFuture = audioSendExecutor.scheduleAtFixedRate(
            ::sendAudioFrame,
            0,
            FRAME_DURATION_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopAudioSendLoop() {
        audioSendFuture?.cancel(false)
        audioSendFuture = null
    }

    @Volatile
    private var lastDiagLogMs = 0L

    private fun sendAudioFrame() {
        val conn = voiceConnection ?: return
        if (!conn.isOpen) {
            logDiag("Send loop: connection not open for guild $guildId")
            return
        }

        val provided = audioPlayer.provide(mutableFrame)
        if (provided) {
            audioLossCounter.onSuccess()
            buffer.flip()
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            buffer.clear()
            conn.pushAudioFrame(data, SAMPLE_RATE, CHANNELS, FRAME_SAMPLES)
	        logDiag("Sent audio frame (guild=$guildId, track=${audioPlayer.playingTrack?.info?.title}, paused=${audioPlayer.isPaused})")
        } else {
            audioLossCounter.onLoss()
            logDiag("Send loop: no frame (guild=$guildId, track=${audioPlayer.playingTrack?.info?.title}, paused=${audioPlayer.isPaused})")
        }
    }

    private fun logDiag(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastDiagLogMs > 5000) {
            lastDiagLogMs = now
            log.info("{}", msg)
        }
    }
}
