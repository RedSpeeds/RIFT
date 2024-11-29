package dev.nohus.rift.settings.persistence

import dev.nohus.rift.utils.directories.AppDirectories
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.time.Instant
import kotlin.io.path.createParentDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

@Single
class SettingsPersistence(
    appDirectories: AppDirectories,
    @Named("settings") private val json: Json,
) {

    private val configFile = appDirectories.getAppDataDirectory().resolve("settings.json")
    private val scope = CoroutineScope(Job())
    private val mutex = Mutex()

    init {
        configFile.createParentDirectories()
        cleanupTempFiles()
    }

    fun load(): SettingsModel {
        return try {
            val serialized = configFile.readText()
            json.decodeFromString<SettingsModel>(serialized)
        } catch (e: NoSuchFileException) {
            logger.info { "Settings file not found" }
            SettingsModel()
        } catch (e: FileSystemException) {
            logger.error(e) { "Settings file could not be read" }
            backupSettingsFile()
            SettingsModel(
                isSettingsReadFailure = true,
            )
        } catch (e: SerializationException) {
            logger.error(e) { "Could not deserialize settings" }
            backupSettingsFile()
            SettingsModel(
                isSettingsReadFailure = true,
            )
        }
    }

    fun save(model: SettingsModel) = scope.launch(Dispatchers.IO) {
        mutex.withLock {
            try {
                val serialized = json.encodeToString(model)
                val temp = configFile.parent.resolve("settings-${Instant.now().toEpochMilli()}.tmp")
                temp.writeText(serialized)
                val readBack = temp.readText()
                val readBackModel = json.decodeFromString<SettingsModel>(readBack)
                if (readBackModel == model) {
                    temp.moveTo(configFile, overwrite = true)
                } else {
                    logger.error { "Could not write settings, reading back validation failed to return the same model" }
                }
            } catch (e: SerializationException) {
                logger.error { "Could not write settings, reading back validation failed to deserialize: $e" }
            } catch (e: FileSystemException) {
                logger.error { "Could not write settings: $e" }
            } catch (e: IOException) {
                logger.error { "Could not write settings: $e" }
            }
        }
    }

    private fun backupSettingsFile() {
        val target = configFile.parent.resolve("settingsBackup-${Instant.now().toEpochMilli()}.json")
        Files.move(configFile, target)
    }

    private fun cleanupTempFiles() {
        try {
            configFile.parent.listDirectoryEntries("*.tmp").forEach { path ->
                path.deleteExisting()
            }
        } catch (e: FileSystemException) {
            logger.error { "Could not delete temporary settings file: $e" }
        }
    }
}
