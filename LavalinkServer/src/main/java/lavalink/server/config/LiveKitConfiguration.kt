package lavalink.server.config

import lavalink.server.livekit.LiveKitManager
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class LiveKitConfiguration {

    private val log = LoggerFactory.getLogger(LiveKitConfiguration::class.java)

    @Bean
    fun liveKitManager(): LiveKitManager {
        log.info("Initializing LiveKit voice manager")
        return LiveKitManager()
    }
}
