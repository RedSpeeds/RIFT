package dev.nohus.rift.utils.sound

import dev.nohus.rift.generated.resources.Res
import dev.nohus.rift.settings.persistence.Settings
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import java.io.IOException
import java.io.InputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isReadable

private val logger = KotlinLogging.logger {}

@Single
class SoundPlayer(
    private val settings: Settings,
) {

    private val dispatcher = Executors.newCachedThreadPool().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val playSubmission = Channel<InputStream>()

    fun start() = scope.launch {
        while (true) {
            val inputStream = playSubmission.receive()
            val gain = settings.soundsVolume / 100f
            val player = OpenAlPlayer()

            val playingJobs = mutableListOf<Job>()
            playingJobs += launch {
                player.play(inputStream, gain)
            }
            val concurrentJobsListener = launch {
                while (true) {
                    val inputStream = playSubmission.receive()
                    playingJobs += launch {
                        player.play(inputStream, gain)
                    }
                }
            }
            while (playingJobs.any { it.isActive }) {
                playingJobs.filter { it.isActive }.forEach { it.join() }
            }
            concurrentJobsListener.cancelAndJoin()
            player.shutdown()
        }
    }

    fun play(soundResource: String) = scope.launch {
        val inputStream = Res.readBytes("files/sounds/$soundResource").inputStream()
        play(inputStream)
    }

    fun playFile(path: String) = scope.launch {
        try {
            val file = Path.of(path)
            if (!file.exists() || !file.isReadable()) return@launch
            play(file.inputStream())
        } catch (e: InvalidPathException) {
            logger.error { "Could not play file with in invalid path: \"$path\"" }
        } catch (e: IOException) {
            logger.error(e) { "Could not play file due to IO exception" }
        }
    }

    private suspend fun play(inputStream: InputStream) {
        playSubmission.send(inputStream)
    }
}
