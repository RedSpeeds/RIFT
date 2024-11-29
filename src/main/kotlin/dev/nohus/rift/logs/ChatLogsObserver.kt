package dev.nohus.rift.logs

import dev.nohus.rift.logs.DirectoryObserver.DirectoryObserverEvent.FileEvent
import dev.nohus.rift.logs.DirectoryObserver.DirectoryObserverEvent.OverflowEvent
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Created
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Deleted
import dev.nohus.rift.logs.DirectoryObserver.FileEventType.Modified
import dev.nohus.rift.logs.parse.ChannelChatMessage
import dev.nohus.rift.logs.parse.ChatLogFileMetadata
import dev.nohus.rift.logs.parse.ChatLogFileParser
import dev.nohus.rift.logs.parse.ChatMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

@Single
class ChatLogsObserver(
    private val directoryObserver: DirectoryObserver,
    private val matchChatLogFilenameUseCase: MatchChatLogFilenameUseCase,
    private val logFileParser: ChatLogFileParser,
) {
    private val logFiles = mutableListOf<ChatLogFile>()
    private val logFilesMutex = Mutex()
    private var activeLogFiles: Map<String, ChatLogFileMetadata> = emptyMap() // String is the filename
    private var onMessageCallback: ((ChannelChatMessage) -> Unit)? = null
    private val handledMessagesSet = mutableSetOf<ChatMessage>()
    private val handledMessagesList = mutableSetOf<ChatMessage>()
    private val handlingNewMessageMutex = Mutex()

    suspend fun observe(
        directory: Path,
        onMessage: (ChannelChatMessage) -> Unit,
    ) {
        logFilesMutex.withLock {
            logFiles.clear()
        }
        activeLogFiles = emptyMap()
        onMessageCallback = onMessage

        logger.info { "Observing chat logs: $directory" }
        reloadLogFiles(directory)
        logger.debug { "Starting directory observer for chat logs: $directory" }
        directoryObserver.observe(directory) { event ->
            when (event) {
                is FileEvent -> {
                    val logFile = matchChatLogFilenameUseCase(event.file)
                    if (logFile != null) {
                        when (event.type) {
                            Created -> {
                                logFilesMutex.withLock {
                                    logFiles += logFile
                                }
                                updateActiveLogFiles()
                            }
                            Deleted -> {
                                logFilesMutex.withLock {
                                    val file = logFiles.find { it.file.name == logFile.file.name }
                                    if (file != null) logFiles -= file
                                }
                                updateActiveLogFiles()
                            }
                            Modified -> {
                                activeLogFiles[logFile.file.name]?.let { metadata ->
                                    readLogFile(logFile, metadata) // TODO: Optimise, we don't need to reread the file in full
                                }
                            }
                        }
                    }
                }
                OverflowEvent -> reloadLogFiles(directory)
            }
        }
        logger.info { "Stopped observing" }
    }

    fun stop() {
        directoryObserver.stop()
    }

    private suspend fun reloadLogFiles(directory: Path) {
        val logFiles = try {
            val entries = directory.listDirectoryEntries()
            coroutineScope {
                entries.map { file ->
                    async {
                        matchChatLogFilenameUseCase(file)
                    }
                }.awaitAll().filterNotNull()
            }
        } catch (e: FileSystemException) {
            logger.error(e) { "Failed reloading chat log files" }
            emptyList()
        }
        logFilesMutex.withLock {
            this.logFiles.clear()
            this.logFiles.addAll(logFiles)
        }
        updateActiveLogFiles()
    }

    private suspend fun updateActiveLogFiles() {
        try {
            logger.debug { "Updating active chat log files: ${logFiles.size}" }
            val minTime = Instant.now() - Duration.ofDays(7)
            val currentActiveLogFiles = logFilesMutex.withLock { logFiles.toList() }
                .filter { it.dateTime.toInstant(ZoneOffset.UTC).isAfter(minTime) }
                .also {
                    if (it.isEmpty()) logger.info { "No chat log files within the last week" }
                }
                .groupBy { it.characterId }
                .flatMap { (characterId, playerLogFiles) ->
                    playerLogFiles
                        .groupBy { it.channelName }
                        .mapNotNull { (channelName, playerChannelLogFiles) ->
                            // Take the latest file for this player / channel combination
                            val logFile = playerChannelLogFiles
                                .sortedBy { it.lastModified }
                                .lastOrNull { it.file.exists() }
                                ?: return@mapNotNull null
                            val existingMetadata = activeLogFiles[logFile.file.name]
                            val metadata = existingMetadata ?: logFileParser.parseHeader(characterId, logFile.file)
                            if (metadata != null) {
                                logFile to metadata
                            } else {
                                logger.error { "Could not parse metadata for $logFile" }
                                null
                            }
                        }
                }

            val newActiveLogFiles = currentActiveLogFiles.filter { it.first.file.name !in activeLogFiles.keys }
            activeLogFiles = currentActiveLogFiles.associate { (logFile, metadata) -> logFile.file.name to metadata }
            logger.debug { "Active chat log files: ${newActiveLogFiles.size}" }

            newActiveLogFiles.forEach { (logFile, metadata) ->
                readLogFile(logFile, metadata)
            }
        } catch (e: IOException) {
            logger.error(e) { "Could not update active chat log files" }
        }
    }

    private suspend fun readLogFile(logFile: ChatLogFile, metadata: ChatLogFileMetadata) {
        try {
            val newMessages = logFileParser.parse(logFile.file)
                .filter { it !in handledMessagesSet }
            if (newMessages.isEmpty()) return
            newMessages.forEach { handleNewMessage(it, metadata) }
        } catch (e: IOException) {
            logger.error(e) { "Could not read chat log file" }
        }
    }

    private suspend fun handleNewMessage(message: ChatMessage, metadata: ChatLogFileMetadata) {
        handlingNewMessageMutex.withLock {
            val now = Instant.now()
            val recentMessages = handledMessagesList.reversed().takeWhile { handledMessage ->
                val age = Duration.between(handledMessage.timestamp, now)
                age < Duration.ofSeconds(2)
            }
            val isDuplicated = recentMessages.any { it.author == message.author && it.message == message.message }
            handledMessagesSet += message
            handledMessagesList += message
            if (!isDuplicated) {
                onMessageCallback?.invoke(ChannelChatMessage(message, metadata))
            }
        }
    }
}
