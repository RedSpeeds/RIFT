package dev.nohus.rift.windowing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement.Maximized
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.nohus.rift.Event
import dev.nohus.rift.about.AboutWindow
import dev.nohus.rift.alerts.list.AlertsWindow
import dev.nohus.rift.assets.AssetsWindow
import dev.nohus.rift.characters.CharactersWindow
import dev.nohus.rift.charactersettings.CharacterSettingsWindow
import dev.nohus.rift.compose.UiScaleController
import dev.nohus.rift.configurationpack.ConfigurationPackReminderWindow
import dev.nohus.rift.contacts.ContactsWindow
import dev.nohus.rift.debug.DebugWindow
import dev.nohus.rift.fleet.FleetsWindow
import dev.nohus.rift.intel.feed.IntelFeedWindow
import dev.nohus.rift.intel.feed.settings.IntelFeedSettingsWindow
import dev.nohus.rift.intel.reports.IntelReportsWindow
import dev.nohus.rift.intel.reports.settings.IntelReportsSettingsWindow
import dev.nohus.rift.jabber.JabberInputModel
import dev.nohus.rift.jabber.JabberWindow
import dev.nohus.rift.map.MapWindow
import dev.nohus.rift.map.settings.MapSettingsWindow
import dev.nohus.rift.neocom.NeocomWindow
import dev.nohus.rift.pings.PingsWindow
import dev.nohus.rift.planetaryindustry.PlanetaryIndustryWindow
import dev.nohus.rift.push.PushWindow
import dev.nohus.rift.settings.SettingsInputModel
import dev.nohus.rift.settings.SettingsWindow
import dev.nohus.rift.settings.persistence.Settings
import dev.nohus.rift.settings.persistence.WindowPlacement
import dev.nohus.rift.startupwarning.StartupWarningInputModel
import dev.nohus.rift.startupwarning.StartupWarningWindow
import dev.nohus.rift.utils.Pos
import dev.nohus.rift.utils.Size
import dev.nohus.rift.whatsnew.WhatsNewWindow
import dev.nohus.rift.windowing.WindowManager.RiftWindowState
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.skiko.MainUIDispatcher
import org.koin.core.annotation.Single
import java.time.Instant

@Single
class WindowManager(
    private val settings: Settings,
    private val uiScaleController: UiScaleController,
) {

    @Serializable
    enum class RiftWindow {
        @SerialName("Neocom")
        Neocom,

        @SerialName("Intel")
        IntelReports,

        @SerialName("IntelSettings")
        IntelReportsSettings,

        @SerialName("IntelFeed")
        IntelFeed,

        @SerialName("IntelFeedSettings")
        IntelFeedSettings,

        @SerialName("Settings")
        Settings,

        @SerialName("Map")
        Map,

        @SerialName("MapSettings")
        MapSettings,

        @SerialName("Characters")
        Characters,

        @SerialName("Pings")
        Pings,

        @SerialName("Alerts")
        Alerts,

        @SerialName("About")
        About,

        @SerialName("Jabber")
        Jabber,

        @SerialName("ConfigurationPackReminder")
        ConfigurationPackReminder,

        @SerialName("Assets")
        Assets,

        @SerialName("WhatsNew")
        WhatsNew,

        @SerialName("Debug")
        Debug,

        @SerialName("Fleets")
        Fleets,

        @SerialName("PlanetaryIndustry")
        PlanetaryIndustry,

        @SerialName("StartupWarning")
        StartupWarning,

        @SerialName("Push")
        Push,

        @SerialName("Contacts")
        Contacts,

        @SerialName("CharacterSettings")
        CharacterSettings,

        @Deprecated("Removed")
        @SerialName("NonEnglishEveClientWarning")
        NonEnglishEveClientWarning,

        @Deprecated("Removed")
        @SerialName("Pushover")
        Pushover,
    }

    data class RiftWindowState(
        val window: RiftWindow? = null,
        val inputModel: Any? = null,
        val windowState: WindowState,
        val isVisible: Boolean,
        val minimumSize: Pair<Int?, Int?>,
        val openTimestamp: Instant = Instant.now(),
        val bringToFrontEvent: Event? = null,
    )

    private data class WindowSizing(
        val defaultSize: Pair<Int?, Int?>,
        val minimumSize: Pair<Int?, Int?>,
    )

    private val nonSavedWindows = listOf(
        RiftWindow.IntelReportsSettings,
        RiftWindow.IntelFeedSettings,
        RiftWindow.MapSettings,
        RiftWindow.About,
        RiftWindow.ConfigurationPackReminder,
        RiftWindow.WhatsNew,
        RiftWindow.StartupWarning,
        RiftWindow.Pushover,
        RiftWindow.CharacterSettings,
    )
    private val persistentWindows = listOf(
        RiftWindow.Neocom,
        RiftWindow.IntelReports,
        RiftWindow.IntelFeed,
        RiftWindow.Map,
        RiftWindow.Characters,
        RiftWindow.Alerts,
        RiftWindow.Pings,
        RiftWindow.Jabber,
    )
    private var states: MutableState<Map<RiftWindow, RiftWindowState>> = mutableStateOf(emptyMap())

    fun openInitialWindows() {
        if (settings.isSetupWizardFinished) {
            if (settings.isRememberOpenWindows) {
                settings.openWindows.filter { it !in nonSavedWindows }.forEach { onWindowOpen(it) }
            }
            if (!settings.isRememberOpenWindows || !settings.isTrayIconWorking) {
                onWindowOpen(RiftWindow.Neocom, ifClosed = true)
            }
        }
    }

    suspend fun saveWindowPlacements() = withContext(MainUIDispatcher) {
        rememberWindowPlacements()
    }

    @Composable
    fun composeWindows() {
        for ((window, state) in states.value) {
            CompositionLocalProvider(LocalRiftWindowState provides state) {
                when (window) {
                    RiftWindow.Neocom -> NeocomWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Neocom) })
                    RiftWindow.IntelReports -> IntelReportsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.IntelReports) }, onTuneClick = { onWindowOpen(RiftWindow.IntelReportsSettings) })
                    RiftWindow.IntelReportsSettings -> IntelReportsSettingsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.IntelReportsSettings) })
                    RiftWindow.IntelFeed -> IntelFeedWindow(state, onCloseRequest = { onWindowClose(RiftWindow.IntelFeed) }, onTuneClick = { onWindowOpen(RiftWindow.IntelFeedSettings) })
                    RiftWindow.IntelFeedSettings -> IntelFeedSettingsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.IntelFeedSettings) })
                    RiftWindow.Settings -> SettingsWindow(state.inputModel as? SettingsInputModel ?: SettingsInputModel.Normal, state, onCloseRequest = { onWindowClose(RiftWindow.Settings) })
                    RiftWindow.Map -> MapWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Map) }, onTuneClick = { onWindowOpen(RiftWindow.MapSettings) })
                    RiftWindow.MapSettings -> MapSettingsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.MapSettings) })
                    RiftWindow.Characters -> CharactersWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Characters) })
                    RiftWindow.Alerts -> AlertsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Alerts) })
                    RiftWindow.About -> AboutWindow(state, onCloseRequest = { onWindowClose(RiftWindow.About) })
                    RiftWindow.Jabber -> JabberWindow(state.inputModel as? JabberInputModel ?: JabberInputModel.None, state, onCloseRequest = { onWindowClose(RiftWindow.Jabber) })
                    RiftWindow.Pings -> PingsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Pings) })
                    RiftWindow.ConfigurationPackReminder -> ConfigurationPackReminderWindow(state, onCloseRequest = { onWindowClose(RiftWindow.ConfigurationPackReminder) })
                    RiftWindow.Assets -> AssetsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Assets) })
                    RiftWindow.WhatsNew -> WhatsNewWindow(state, onCloseRequest = { onWindowClose(RiftWindow.WhatsNew) })
                    RiftWindow.Debug -> DebugWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Debug) })
                    RiftWindow.Fleets -> FleetsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Fleets) })
                    RiftWindow.PlanetaryIndustry -> PlanetaryIndustryWindow(state, onCloseRequest = { onWindowClose(RiftWindow.PlanetaryIndustry) })
                    RiftWindow.StartupWarning -> StartupWarningWindow(state.inputModel as? StartupWarningInputModel, state, onCloseRequest = { onWindowClose(RiftWindow.StartupWarning) })
                    RiftWindow.Push -> PushWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Push) })
                    RiftWindow.Contacts -> ContactsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.Contacts) })
                    RiftWindow.CharacterSettings -> CharacterSettingsWindow(state, onCloseRequest = { onWindowClose(RiftWindow.CharacterSettings) })
                    RiftWindow.NonEnglishEveClientWarning -> {}
                    RiftWindow.Pushover -> {}
                }
            }
        }
    }

    fun onWindowOpen(window: RiftWindow, inputModel: Any? = null, ifClosed: Boolean = false) {
        if (ifClosed && states.value[window]?.isVisible == true) return
        val state = states.value[window]?.copy(
            inputModel = inputModel,
            isVisible = true,
            openTimestamp = Instant.now(),
            bringToFrontEvent = Event(),
        ) ?: run {
            val sizing = getWindowOpenSizing(window)
            val state = WindowState(
                width = sizing.defaultSize.first?.dp ?: Dp.Unspecified,
                height = sizing.defaultSize.second?.dp ?: Dp.Unspecified,
                position = getWindowOpenPosition(window),
            )
            RiftWindowState(
                window = window,
                inputModel = inputModel,
                windowState = state,
                isVisible = true,
                minimumSize = sizing.minimumSize,
            )
        }
        settings.openWindows += window
        states.value += window to state
    }

    fun onWindowClose(window: RiftWindow) {
        settings.openWindows -= window
        if (window in persistentWindows) {
            states.value[window]?.let {
                states.value += window to it.copy(isVisible = false)
            }
        } else {
            states.value -= window
        }
    }

    private fun getWindowOpenSizing(window: RiftWindow): WindowSizing {
        val saved = if (settings.isRememberWindowPlacement) {
            settings.windowPlacements[window]?.size?.let { it.width to it.height }
        } else {
            null
        }
        val windowSizing = when (window) {
            RiftWindow.Neocom -> WindowSizing(defaultSize = saved ?: (160 to 650), minimumSize = 143 to 106)
            RiftWindow.IntelReports -> WindowSizing(defaultSize = saved ?: (800 to 500), minimumSize = 400 to 200)
            RiftWindow.IntelReportsSettings -> WindowSizing(defaultSize = (400 to null), minimumSize = 400 to null)
            RiftWindow.IntelFeed -> WindowSizing(defaultSize = saved ?: (500 to 600), minimumSize = (500 to 250))
            RiftWindow.IntelFeedSettings -> WindowSizing(defaultSize = (400 to null), minimumSize = 400 to null)
            RiftWindow.Settings -> WindowSizing(defaultSize = (820 to null), minimumSize = 820 to null)
            RiftWindow.Map -> WindowSizing(defaultSize = saved ?: (800 to 800), minimumSize = 350 to 300)
            RiftWindow.MapSettings -> WindowSizing(defaultSize = (400 to 450), minimumSize = 400 to 450)
            RiftWindow.Characters -> WindowSizing(defaultSize = saved ?: (420 to 400), minimumSize = 400 to 300)
            RiftWindow.Alerts -> WindowSizing(defaultSize = saved ?: (500 to 500), minimumSize = 500 to 500)
            RiftWindow.About -> WindowSizing(defaultSize = (500 to null), minimumSize = (500 to null))
            RiftWindow.Jabber -> WindowSizing(defaultSize = saved ?: (400 to 500), minimumSize = (200 to 200))
            RiftWindow.Pings -> WindowSizing(defaultSize = saved ?: (440 to 500), minimumSize = (440 to 300))
            RiftWindow.ConfigurationPackReminder -> WindowSizing(defaultSize = (450 to null), minimumSize = (450 to null))
            RiftWindow.Assets -> WindowSizing(defaultSize = saved ?: (500 to 500), minimumSize = (500 to 300))
            RiftWindow.WhatsNew -> WindowSizing(defaultSize = (450 to 600), minimumSize = (450 to 600))
            RiftWindow.Debug -> WindowSizing(defaultSize = saved ?: (450 to 500), minimumSize = (450 to 500))
            RiftWindow.Fleets -> WindowSizing(defaultSize = saved ?: (300 to 300), minimumSize = 300 to 300)
            RiftWindow.PlanetaryIndustry -> WindowSizing(defaultSize = saved ?: (540 to 800), minimumSize = 540 to 360)
            RiftWindow.StartupWarning -> WindowSizing(defaultSize = (450 to null), minimumSize = (450 to null))
            RiftWindow.Push -> WindowSizing(defaultSize = (350 to 435), minimumSize = 350 to 435)
            RiftWindow.Contacts -> WindowSizing(defaultSize = saved ?: (650 to 600), minimumSize = 650 to 600)
            RiftWindow.CharacterSettings -> WindowSizing(defaultSize = (420 to 500), minimumSize = 400 to 300)
            RiftWindow.NonEnglishEveClientWarning -> WindowSizing(defaultSize = (200 to 200), minimumSize = (200 to 200))
            RiftWindow.Pushover -> WindowSizing(defaultSize = (200 to 200), minimumSize = (200 to 200))
        }
        return windowSizing.scaled(uiScaleController.uiScale)
    }

    private fun WindowSizing.scaled(scale: Float): WindowSizing {
        return WindowSizing(
            defaultSize = defaultSize.first?.let { (it * scale).toInt() } to defaultSize.second?.let { (it * scale).toInt() },
            minimumSize = minimumSize.first?.let { (it * scale).toInt() } to minimumSize.second?.let { (it * scale).toInt() },
        )
    }

    private fun getWindowOpenPosition(window: RiftWindow): WindowPosition {
        val saved = if (settings.isRememberWindowPlacement) { settings.windowPlacements[window]?.position } else null
        saved ?: return WindowPosition.PlatformDefault
        return WindowPosition(saved.x.dp, saved.y.dp)
    }

    private fun rememberWindowPlacements() {
        val scale = uiScaleController.uiScale
        states.value
            .filter { (_, state) -> state.window !in nonSavedWindows }
            .filter { (_, state) -> state.windowState.placement != Maximized }
            .forEach { (window, state) ->
                settings.windowPlacements += window to WindowPlacement(
                    position = state.windowState.position.let { Pos(it.x.value.toInt(), it.y.value.toInt()) },
                    size = state.windowState.size.let { Size((it.width.value / scale).toInt(), (it.height.value / scale).toInt()) },
                )
            }
    }
}

val LocalRiftWindowState: ProvidableCompositionLocal<RiftWindowState?> = staticCompositionLocalOf { null }
val LocalRiftWindow: ProvidableCompositionLocal<ComposeWindow?> = staticCompositionLocalOf { null }
