package dev.nohus.rift.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import dev.nohus.rift.alerts.AlertsTriggerController.AlertLocationMatch
import dev.nohus.rift.intel.state.SystemEntity
import dev.nohus.rift.notifications.NotificationsController.Notification.ChatMessageNotification
import dev.nohus.rift.repositories.TypesRepository.Type
import dev.nohus.rift.settings.persistence.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.exposed.sql.not
import org.koin.core.annotation.Single

@Single
class NotificationsController(
    private val settings: Settings,
) {

    sealed interface Notification {
        data class TextNotification(
            val title: String,
            val message: AnnotatedString,
            val characterId: Int?, // Associated character
            val type: Type?, // Associated type ID
        ) : Notification {
            companion object {
                const val styleTag = "Style"
                const val styleValue = "Primary"
            }
        }

        data class ChatMessageNotification(
            val channel: String,
            val messages: List<ChatMessage>,
        ) : Notification

        data class ChatMessage(
            val message: String,
            val highlight: String?,
            val sender: String,
            val senderCharacterId: Int?,
        )

        data class JabberMessageNotification(
            val chat: String,
            val message: String,
            val highlight: String?,
            val sender: String,
        ) : Notification

        data class IntelNotification(
            val title: String,
            val locationMatch: AlertLocationMatch,
            val systemEntities: List<SystemEntity>,
            val solarSystem: String,
        ) : Notification
    }

    private val maxShownNotifications = 25
    private val notificationDuration = 8000L
    private val notificationsStateFlow = MutableStateFlow<List<Notification>>(emptyList())

    fun show(notification: Notification) {
        notificationsStateFlow.update {
            collapseChatNotifications(it + notification).takeLast(maxShownNotifications)
        }
    }

    private fun collapseChatNotifications(notifications: List<Notification>): List<Notification> {
        return mutableListOf<Notification>().apply {
            for (notification in notifications) {
                val previous = lastOrNull()
                if (previous is ChatMessageNotification && notification is ChatMessageNotification &&
                    previous.channel == notification.channel
                ) {
                    this[lastIndex] = ChatMessageNotification(
                        channel = notification.channel,
                        messages = previous.messages + notification.messages,
                    )
                } else {
                    this += notification
                }
            }
        }
    }

    private fun hide(notification: Notification) {
        notificationsStateFlow.update { it - notification }
    }

    @Composable
    fun composeNotification() {
        val notifications by notificationsStateFlow.collectAsState()
        if (notifications.isNotEmpty()) {
            key(notifications) {
                var isHeld by remember { mutableStateOf(false) }
                LaunchedEffect(notifications, isHeld) {
                    if (!isHeld) {
                        delay(notificationDuration)
                        notificationsStateFlow.update { emptyList() }
                    }
                }
                NotificationWindow(
                    notifications = notifications,
                    position = settings.notificationPosition,
                    onHoldDisappearance = { isHeld = it },
                    onCloseClick = { hide(it) },
                )
            }
        }
    }
}
