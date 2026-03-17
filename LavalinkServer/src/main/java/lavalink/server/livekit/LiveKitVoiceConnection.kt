package lavalink.server.livekit

import dev.onvoid.webrtc.*
import dev.onvoid.webrtc.media.audio.AudioTrack
import dev.onvoid.webrtc.media.audio.AudioTrackSink
import dev.onvoid.webrtc.media.audio.CustomAudioSource
import org.json.JSONObject
import livekit.LivekitModels
import livekit.LivekitRtc
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages a single voice connection to a LiveKit room, including:
 * - WebSocket signaling via [LiveKitSignalClient]
 * - Publisher and subscriber WebRTC PeerConnections
 * - Audio track publishing via [CustomAudioSource]
 */
class LiveKitVoiceConnection(
    private val httpClient: OkHttpClient,
    private val peerConnectionFactory: PeerConnectionFactory
) {

    companion object {
        private val log = LoggerFactory.getLogger(LiveKitVoiceConnection::class.java)
        private const val AUDIO_TRACK_ID = "lavalink-audio"
        private const val STREAM_ID = "lavalink-stream"
        private const val PING_INTERVAL_SECONDS = 10L
    }

    interface EventListener {
        fun onConnected()
        fun onDisconnected(code: Int, reason: String)
        fun onError(cause: Throwable)
    }

    private var signalClient: LiveKitSignalClient? = null
    private var publisherPc: RTCPeerConnection? = null
    private var subscriberPc: RTCPeerConnection? = null
    private var audioSource: CustomAudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var eventListener: EventListener? = null
    private var connectionInfo: LiveKitConnectionInfo? = null
    private var pingFuture: ScheduledFuture<*>? = null
    private var statsFuture: ScheduledFuture<*>? = null
    private val sinkFrameCount = AtomicLong(0)

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "livekit-voice-ping").apply { isDaemon = true }
    }

    @Volatile
    var isOpen: Boolean = false
        private set

    @Volatile
    private var firstFramePushed = false

    @Volatile
    var ping: Long = -1L
        private set

    val voiceInfo: LiveKitConnectionInfo?
        get() = connectionInfo

    fun setEventListener(listener: EventListener) {
        this.eventListener = listener
    }

    /**
     * Connects to a LiveKit room and sets up the publisher PeerConnection for audio.
     */
    fun connect(info: LiveKitConnectionInfo): CompletableFuture<Void> {
        connectionInfo = info

        val signal = LiveKitSignalClient(httpClient)
        signalClient = signal

        val result = CompletableFuture<Void>()

        signal.connect(info.url, info.token, object : LiveKitSignalClient.Listener {
            override fun onJoin(response: LivekitRtc.JoinResponse) {
                try {
                    setupPeerConnections(response)
                    requestTrackPublication()
                    isOpen = true
                    startPingLoop()
                    eventListener?.onConnected()
                    result.complete(null)
                } catch (e: Exception) {
                    log.error("Failed to setup peer connections", e)
                    result.completeExceptionally(e)
                }
            }

            override fun onAnswer(sdp: LivekitRtc.SessionDescription) {
                handlePublisherAnswer(sdp)
            }

            override fun onOffer(sdp: LivekitRtc.SessionDescription) {
                handleSubscriberOffer(sdp)
            }

            override fun onTrickle(trickle: LivekitRtc.TrickleRequest) {
                handleTrickle(trickle)
            }

            override fun onTrackPublished(response: LivekitRtc.TrackPublishedResponse) {
                log.info("Audio track published to LiveKit room, sid={}", response.track.sid)
                negotiatePublisher()
            }

            override fun onClose(code: Int, reason: String) {
                isOpen = false
                eventListener?.onDisconnected(code, reason)
            }

            override fun onError(error: Throwable) {
                eventListener?.onError(error)
            }
        }).exceptionally { t ->
            result.completeExceptionally(t)
            null
        }

        return result
    }

    /**
     * Pushes a PCM audio frame to the LiveKit room.
     *
     * @param data PCM 16-bit signed little-endian audio data
     * @param sampleRate sample rate (e.g. 48000)
     * @param channels number of channels (e.g. 2 for stereo)
     * @param frameCount number of samples per channel in this frame
     */
    fun pushAudioFrame(data: ByteArray, sampleRate: Int, channels: Int, frameCount: Int) {
        if (!firstFramePushed) {
            firstFramePushed = true
            var nonZeroCount = 0
            var maxSample: Short = 0
            for (i in 0 until data.size - 1 step 2) {
                val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort()
                if (sample != 0.toShort()) nonZeroCount++
                if (kotlin.math.abs(sample.toInt()) > kotlin.math.abs(maxSample.toInt())) maxSample = sample
            }
            log.info("First audio frame: size={}, sampleRate={}, channels={}, frameCount={}, nonZeroSamples={}/{}, peakSample={}", 
                data.size, sampleRate, channels, frameCount, nonZeroCount, data.size / 2, maxSample)
        }
        audioSource?.pushAudio(data, 16, sampleRate, channels, frameCount)
    }

    fun close() {
        isOpen = false
        pingFuture?.cancel(false)
        statsFuture?.cancel(false)

        audioTrack?.dispose()
        audioTrack = null

        audioSource?.dispose()
        audioSource = null

        publisherPc?.close()
        publisherPc = null

        subscriberPc?.close()
        subscriberPc = null

        signalClient?.close()
        signalClient = null

        connectionInfo = null
    }

    private fun setupPeerConnections(joinResponse: LivekitRtc.JoinResponse) {
        val iceServers = joinResponse.iceServersList.map { server ->
            RTCIceServer().apply {
                urls.addAll(server.urlsList)
                username = server.username
                password = server.credential
            }
        }

        val rtcConfig = RTCConfiguration().apply {
            this.iceServers.addAll(iceServers)
            bundlePolicy = RTCBundlePolicy.MAX_BUNDLE
        }

        publisherPc = peerConnectionFactory.createPeerConnection(rtcConfig, PublisherObserver())

        audioSource = CustomAudioSource()
        audioTrack = peerConnectionFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource).also {
            it.setEnabled(true)
            it.addSink(AudioTrackSink { _, bitsPerSample, sampleRate, channels, frames ->
                val count = sinkFrameCount.incrementAndGet()
                if (count == 1L) {
                    log.info("AudioTrackSink: first frame received from track pipeline ({}bit, {}Hz, {}ch, {} frames)", 
                        bitsPerSample, sampleRate, channels, frames)
                }
            })
            log.info("Audio track created: id={}, enabled={}, state={}", it.id, it.isEnabled, it.state)
        }

        val transceiverInit = RTCRtpTransceiverInit().apply {
            direction = RTCRtpTransceiverDirection.SEND_ONLY
            streamIds = listOf(STREAM_ID)
        }
        publisherPc?.addTransceiver(audioTrack!!, transceiverInit)

        subscriberPc = peerConnectionFactory.createPeerConnection(rtcConfig, SubscriberObserver())
    }

    private fun requestTrackPublication() {
        val addTrack = LivekitRtc.AddTrackRequest.newBuilder()
            .setCid(AUDIO_TRACK_ID)
            .setName("audio")
            .setType(LivekitModels.TrackType.AUDIO)
            .setSource(LivekitModels.TrackSource.MICROPHONE)
            .build()

        signalClient?.sendAddTrack(addTrack)
    }

    private fun negotiatePublisher() {
        val pc = publisherPc ?: return

        val options = RTCOfferOptions()

        pc.createOffer(options, object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) {
                log.info("Publisher SDP offer (audio lines):\n{}", 
                    description.sdp.lines().filter { it.startsWith("m=audio") || it.startsWith("a=rtpmap:") || it.startsWith("a=fmtp:") }.joinToString("\n"))
                pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                    override fun onSuccess() {
                        val sdp = LivekitRtc.SessionDescription.newBuilder()
                            .setType(description.sdpType.name.lowercase())
                            .setSdp(description.sdp)
                            .build()
                        signalClient?.sendOffer(sdp)
                    }

                    override fun onFailure(error: String) {
                        log.error("Failed to set local description: {}", error)
                    }
                })
            }

            override fun onFailure(error: String) {
                log.error("Failed to create publisher offer: {}", error)
            }
        })
    }

    private fun handlePublisherAnswer(sdp: LivekitRtc.SessionDescription) {
        val pc = publisherPc ?: return
        log.info("Publisher SDP answer (audio lines):\n{}",
            sdp.sdp.lines().filter { it.startsWith("m=audio") || it.startsWith("a=rtpmap:") || it.startsWith("a=fmtp:") }.joinToString("\n"))
        val description = RTCSessionDescription(
            RTCSdpType.valueOf(sdp.type.uppercase()),
            sdp.sdp
        )
        pc.setRemoteDescription(description, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                log.info("Publisher remote description set, starting stats monitor")
                startStatsMonitor()
            }

            override fun onFailure(error: String) {
                log.error("Failed to set publisher remote description: {}", error)
            }
        })
    }

    private fun handleSubscriberOffer(sdp: LivekitRtc.SessionDescription) {
        val pc = subscriberPc ?: return
        val offer = RTCSessionDescription(
            RTCSdpType.valueOf(sdp.type.uppercase()),
            sdp.sdp
        )

        pc.setRemoteDescription(offer, object : SetSessionDescriptionObserver {
            override fun onSuccess() {
                val answerOptions = RTCAnswerOptions()
                pc.createAnswer(answerOptions, object : CreateSessionDescriptionObserver {
                    override fun onSuccess(description: RTCSessionDescription) {
                        pc.setLocalDescription(description, object : SetSessionDescriptionObserver {
                            override fun onSuccess() {
                                val answer = LivekitRtc.SessionDescription.newBuilder()
                                    .setType("answer")
                                    .setSdp(description.sdp)
                                    .build()
                                signalClient?.sendAnswer(answer)
                            }

                            override fun onFailure(error: String) {
                                log.error("Failed to set subscriber local description: {}", error)
                            }
                        })
                    }

                    override fun onFailure(error: String) {
                        log.error("Failed to create subscriber answer: {}", error)
                    }
                })
            }

            override fun onFailure(error: String) {
                log.error("Failed to set subscriber remote description: {}", error)
            }
        })
    }

    private fun handleTrickle(trickle: LivekitRtc.TrickleRequest) {
        val json = JSONObject(trickle.candidateInit)
        val sdp = json.optString("candidate", "")
        val sdpMid = json.optString("sdpMid", "0")
        val sdpMLineIndex = json.optInt("sdpMLineIndex", 0)

        val candidate = RTCIceCandidate(sdpMid, sdpMLineIndex, sdp)
        when (trickle.target) {
            LivekitRtc.SignalTarget.PUBLISHER -> publisherPc?.addIceCandidate(candidate)
            LivekitRtc.SignalTarget.SUBSCRIBER -> subscriberPc?.addIceCandidate(candidate)
            else -> log.warn("Unknown trickle target: {}", trickle.target)
        }
    }

    private fun sendIceCandidate(candidate: RTCIceCandidate, target: LivekitRtc.SignalTarget) {
        val json = JSONObject()
            .put("candidate", candidate.sdp)
            .put("sdpMid", candidate.sdpMid)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .toString()

        val trickle = LivekitRtc.TrickleRequest.newBuilder()
            .setCandidateInit(json)
            .setTarget(target)
            .build()
        signalClient?.sendTrickle(trickle)
    }

    private fun startStatsMonitor() {
        statsFuture = scheduler.scheduleAtFixedRate({
            try {
                val pc = publisherPc ?: return@scheduleAtFixedRate
                pc.getStats(RTCStatsCollectorCallback { report ->
                    for ((_, stats) in report.stats) {
                        when (stats.type) {
                            RTCStatsType.OUTBOUND_RTP -> {
                                val attrs = stats.attributes
                                log.info("OUTBOUND_RTP: kind={}, codec={}, packetsSent={}, bytesSent={}, " +
                                    "retransmittedPacketsSent={}, sinkFrames={}",
                                    attrs["kind"], attrs["codecId"], attrs["packetsSent"], 
                                    attrs["bytesSent"], attrs["retransmittedPacketsSent"],
                                    sinkFrameCount.get())
                            }
                            RTCStatsType.MEDIA_SOURCE -> {
                                val attrs = stats.attributes
                                if (attrs["kind"] == "audio") {
                                    log.info("MEDIA_SOURCE(audio): trackIdentifier={}, audioLevel={}, " +
                                        "totalAudioEnergy={}, totalSamplesDuration={}",
                                        attrs["trackIdentifier"], attrs["audioLevel"],
                                        attrs["totalAudioEnergy"], attrs["totalSamplesDuration"])
                                }
                            }
                            else -> {}
                        }
                    }
                })
            } catch (e: Exception) {
                log.trace("Stats error", e)
            }
        }, 5, 10, TimeUnit.SECONDS)
    }

    private fun startPingLoop() {
        pingFuture = scheduler.scheduleAtFixedRate({
            try {
                val signal = signalClient ?: return@scheduleAtFixedRate
                if (!signal.isConnected) return@scheduleAtFixedRate

                val timestamp = System.currentTimeMillis()
                val pingReq = LivekitRtc.SignalRequest.newBuilder()
                    .setPing(timestamp)
                    .build()
                signal.sendPing(pingReq)
                ping = 0L
            } catch (e: Exception) {
                log.trace("Ping error", e)
            }
        }, PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private inner class PublisherObserver : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            sendIceCandidate(candidate, LivekitRtc.SignalTarget.PUBLISHER)
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            log.info("Publisher connection state: {}", state)
            when (state) {
                RTCPeerConnectionState.CONNECTED -> {
                    isOpen = true
                }
                RTCPeerConnectionState.DISCONNECTED,
                RTCPeerConnectionState.FAILED,
                RTCPeerConnectionState.CLOSED -> {
                    isOpen = false
                }
                else -> {}
            }
        }

        override fun onIceConnectionChange(state: RTCIceConnectionState) {
            log.info("Publisher ICE connection state: {}", state)
        }
    }

    private inner class SubscriberObserver : PeerConnectionObserver {
        override fun onIceCandidate(candidate: RTCIceCandidate) {
            sendIceCandidate(candidate, LivekitRtc.SignalTarget.SUBSCRIBER)
        }

        override fun onConnectionChange(state: RTCPeerConnectionState) {
            log.info("Subscriber connection state: {}", state)
        }
    }
}
