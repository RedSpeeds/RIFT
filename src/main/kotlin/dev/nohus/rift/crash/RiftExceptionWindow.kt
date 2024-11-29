package dev.nohus.rift.crash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.nohus.rift.BuildConfig
import dev.nohus.rift.about.UpdateController
import dev.nohus.rift.about.UpdateController.UpdateAvailability.UPDATE_AUTOMATIC
import dev.nohus.rift.about.UpdateController.UpdateAvailability.UPDATE_MANUAL
import dev.nohus.rift.compose.ButtonCornerCut
import dev.nohus.rift.compose.ButtonType
import dev.nohus.rift.compose.RiftButton
import dev.nohus.rift.compose.RiftWindow
import dev.nohus.rift.compose.ScrollbarColumn
import dev.nohus.rift.compose.UiScaleController
import dev.nohus.rift.compose.theme.RiftTheme
import dev.nohus.rift.compose.theme.Spacing
import dev.nohus.rift.di.koin
import dev.nohus.rift.generated.resources.Res
import dev.nohus.rift.generated.resources.window_log
import dev.nohus.rift.utils.openBrowser
import dev.nohus.rift.utils.toURIOrNull
import dev.nohus.rift.windowing.WindowManager.RiftWindowState

@Composable
fun RiftExceptionWindow(
    throwable: Throwable,
    errorId: String,
    onCloseRequest: () -> Unit,
) {
    val scale = try {
        koin.get<UiScaleController>().uiScale
    } catch (ignored: Exception) {
        1f
    }
    val windowState = rememberWindowState(width = (400 * scale).dp, height = (150 * scale).dp)
    var isUpdateAvailable: Boolean by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val updateAvailability = UpdateController().isUpdateAvailable()
            isUpdateAvailable = updateAvailability == UPDATE_MANUAL || updateAvailability == UPDATE_AUTOMATIC
        } catch (ignored: Exception) {}
    }
    RiftWindow(
        title = "Fatal Error",
        icon = Res.drawable.window_log,
        state = RiftWindowState(windowState = windowState, isVisible = true, minimumSize = (200 * scale).toInt() to (200 * scale).toInt()),
        onCloseClick = onCloseRequest,
    ) {
        Column {
            var areDetailsVisible by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                SelectionContainer {
                    val highlightStyle = SpanStyle(
                        color = RiftTheme.typography.bodySpecialHighlighted.color,
                        fontWeight = FontWeight.Bold,
                    )
                    AnimatedContent(isUpdateAvailable) { isUpdateAvailable ->
                        Text(
                            text = buildAnnotatedString {
                                appendLine("RIFT has encountered a problem and has to close.")
                                if (isUpdateAvailable) {
                                    appendLine()
                                    withStyle(highlightStyle) {
                                        appendLine("You are using an older version of RIFT.")
                                    }
                                    appendLine("The problem may already be fixed in the latest version.")
                                } else {
                                    append("Error code: ")
                                    withStyle(highlightStyle) {
                                        appendLine(errorId)
                                    }
                                    appendLine()
                                    append("Please report this issue and copy the code into a Discord ticket.")
                                }
                            },
                        )
                    }
                }
                if (areDetailsVisible) {
                    ScrollbarColumn(
                        modifier = Modifier
                            .padding(vertical = Spacing.medium)
                            .border(1.dp, RiftTheme.colors.borderGrey),
                        scrollbarModifier = Modifier.padding(vertical = Spacing.small),
                    ) {
                        val text = buildString {
                            appendLine(throwable.message)
                            throwable.stackTrace.forEach { element ->
                                appendLine(element.toString())
                            }
                        }
                        SelectionContainer {
                            Text(
                                text = text,
                                modifier = Modifier.padding(Spacing.medium),
                            )
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                modifier = Modifier.align(Alignment.End),
            ) {
                @Suppress("KotlinConstantConditions")
                if (BuildConfig.environment == "dev") {
                    val detailsButtonText = if (areDetailsVisible) "Hide details" else "Show details"
                    RiftButton(
                        text = detailsButtonText,
                        type = ButtonType.Secondary,
                        cornerCut = ButtonCornerCut.None,
                        onClick = {
                            areDetailsVisible = !areDetailsVisible
                            if (areDetailsVisible) {
                                windowState.size = DpSize(650.dp, 400.dp)
                            } else {
                                windowState.size = DpSize(400.dp, 150.dp)
                            }
                        },
                    )
                }
                RiftButton(
                    text = "Close",
                    type = ButtonType.Secondary,
                    cornerCut = ButtonCornerCut.None,
                    onClick = onCloseRequest,
                )
                AnimatedVisibility(!isUpdateAvailable) {
                    RiftButton(
                        text = "Report error",
                        type = ButtonType.Primary,
                        onClick = {
                            "https://discord.com/channels/1185575651575607458/1185579273583599676".toURIOrNull()?.openBrowser()
                        },
                    )
                }
            }
        }
    }
}
