package dev.nohus.rift.settings.persistence

import dev.nohus.rift.alerts.Alert
import dev.nohus.rift.settings.persistence.MapSystemInfoType.Assets
import dev.nohus.rift.settings.persistence.MapSystemInfoType.Clones
import dev.nohus.rift.settings.persistence.MapSystemInfoType.Colonies
import dev.nohus.rift.settings.persistence.MapSystemInfoType.Incursions
import dev.nohus.rift.settings.persistence.MapSystemInfoType.MetaliminalStorms
import dev.nohus.rift.settings.persistence.MapSystemInfoType.Security
import dev.nohus.rift.standings.StandingsRepository.Standings
import dev.nohus.rift.utils.Pos
import dev.nohus.rift.utils.Size
import dev.nohus.rift.windowing.WindowManager.RiftWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingsModel(
    val eveLogsDirectory: String? = null,
    val eveSettingsDirectory: String? = null,
    val isLoadOldMessagesEnabled: Boolean = false,
    val intelMap: IntelMap = IntelMap(),
    val authenticatedCharacters: Map<Int, SsoAuthentication> = emptyMap(),
    val intelChannels: List<IntelChannel> = emptyList(),
    val isRememberOpenWindows: Boolean = false,
    val isRememberWindowPlacement: Boolean = true,
    val openWindows: Set<RiftWindow> = emptySet(),
    val windowPlacements: Map<RiftWindow, WindowPlacement> = emptyMap(),
    val alwaysOnTopWindows: Set<RiftWindow> = emptySet(),
    val lockedWindows: Set<RiftWindow> = emptySet(),
    val notificationEditPosition: Pos? = null,
    val notificationPosition: Pos? = null,
    val alerts: List<Alert> = emptyList(),
    val isSetupWizardFinished: Boolean = false,
    val isShowSetupWizardOnNextStart: Boolean = false,
    val isDisplayEveTime: Boolean = false,
    val jabberJidLocalPart: String? = null,
    val jabberPassword: String? = null,
    val jabberCollapsedGroups: List<String> = emptyList(),
    val jabberIsUsingBiggerFontSize: Boolean = false,
    val isDemoMode: Boolean = false,
    val isSettingsReadFailure: Boolean = false,
    val isUsingDarkTrayIcon: Boolean = false,
    val intelReports: IntelReports = IntelReports(),
    val intelFeed: IntelFeed = IntelFeed(),
    val soundsVolume: Int = 100,
    val alertGroups: Set<String> = emptySet(),
    val configurationPack: ConfigurationPack? = null,
    val isConfigurationPackReminderDismissed: Boolean = false,
    val hiddenCharacterIds: List<Int> = emptyList(),
    val jumpBridgeNetwork: Map<String, String>? = null,
    val isUsingRiftAutopilotRoute: Boolean = true,
    val isSettingAutopilotToAll: Boolean = false,
    val whatsNewVersion: String? = null,
    val jumpRange: JumpRange? = null,
    val selectedPlanetTypes: List<Int> = emptyList(),
    val installationId: String? = null,
    val isShowingSystemDistance: Boolean = true,
    val isUsingJumpBridgesForDistance: Boolean = false,
    val intelExpireSeconds: Int = 300,
    val standings: Standings = Standings(),
    val planetaryIndustry: PlanetaryIndustry = PlanetaryIndustry(),
    val isShowingCharactersClones: Boolean = true,
    val planetaryIndustryTriggeredAlerts: Map<String, Map<String, Long>> = emptyMap(),
    val pushover: Pushover = Pushover(),
    val ntfy: Ntfy = Ntfy(),
    val skipSplashScreen: Boolean = false,
    val dismissedWarnings: List<String> = emptyList(),
    val uiScale: Float = 1f,
    val accountAssociations: Map<Int, Int> = emptyMap(),
    val isTrayIconWorking: Boolean = false,
)

@Serializable
enum class MapType {
    NewEden, Region
}

@Serializable
enum class MapSystemInfoType {
    StarColor, Security, NullSecurity, IntelHostiles, Jumps, Kills, NpcKills, Assets, Clones, Incursions, Stations,
    FactionWarfare, Sovereignty, MetaliminalStorms, JumpRange, Planets, JoveObservatories, Colonies, Standings,
    RatsType, IndustryIndexCopying, IndustryIndexInvention, IndustryIndexManufacturing, IndustryIndexReaction,
    IndustryIndexMaterialEfficiency, IndustryIndexTimeEfficiency,
}

@Serializable
data class IntelMap(
    val isUsingCompactMode: Boolean = false,
    val mapTypeStarInfoTypes: Map<MapType, MapSystemInfoType> = emptyMap(),
    val mapTypeCellInfoTypes: Map<MapType, MapSystemInfoType?> = emptyMap(),
    val mapTypeIndicatorInfoTypes: Map<MapType, List<MapSystemInfoType>> = mapOf(
        MapType.NewEden to listOf(Assets, Clones, Incursions, MetaliminalStorms, Colonies),
        MapType.Region to listOf(Assets, Clones, Incursions, MetaliminalStorms, Colonies),
    ),
    val mapTypeInfoBoxInfoTypes: Map<MapType, List<MapSystemInfoType>> = mapOf(
        MapType.NewEden to listOf(Security, Assets, Clones, Incursions, MetaliminalStorms, Colonies),
        MapType.Region to listOf(Security, Assets, Clones, Incursions, MetaliminalStorms, Colonies),
    ),
    val intelPopupTimeoutSeconds: Int = 60,
    val isCharacterFollowing: Boolean = true,
    val isInvertZoom: Boolean = false,
    val isJumpBridgeNetworkShown: Boolean = true,
    val jumpBridgeNetworkOpacity: Int = 100,
    val openedLayoutId: Int? = null,
    val isAlwaysShowingSystems: Boolean = false,
)

@Serializable
data class SsoAuthentication(
    val accessToken: String,
    val refreshToken: String,
    val expiration: Long,
)

@Serializable
data class IntelChannel(
    val name: String,
    val region: String,
)

@Serializable
data class WindowPlacement(
    val position: Pos,
    val size: Size,
)

@Serializable
data class IntelReports(
    val isUsingCompactMode: Boolean = false,
    val isShowingReporter: Boolean = true,
    val isShowingChannel: Boolean = true,
    val isShowingRegion: Boolean = false,
)

@Serializable
data class IntelFeed(
    val isUsingCompactMode: Boolean = false,
    val locationFilters: List<LocationFilter> = listOf(
        LocationFilter.KnownSpace,
        LocationFilter.WormholeSpace,
        LocationFilter.AbyssalSpace,
    ),
    val distanceFilter: DistanceFilter = DistanceFilter.All,
    val entityFilters: List<EntityFilter> = listOf(
        EntityFilter.Killmails,
        EntityFilter.Characters,
        EntityFilter.Other,
    ),
    val sortingFilter: SortingFilter = SortingFilter.Time,
)

@Serializable
data class PlanetaryIndustry(
    val view: ColonyView = ColonyView.List,
    val sortingFilter: ColonySortingFilter = ColonySortingFilter.Character,
)

@Serializable
sealed interface LocationFilter {
    @Serializable
    @SerialName("KnownSpace")
    data object KnownSpace : LocationFilter

    @Serializable
    @SerialName("WormholeSpace")
    data object WormholeSpace : LocationFilter

    @Serializable
    @SerialName("AbyssalSpace")
    data object AbyssalSpace : LocationFilter

    @Serializable
    @SerialName("CurrentMapRegion")
    data object CurrentMapRegion : LocationFilter
}

@Serializable
sealed interface DistanceFilter {
    @Serializable
    @SerialName("All")
    data object All : DistanceFilter

    @Serializable
    @SerialName("CharacterLocationRegions")
    data object CharacterLocationRegions : DistanceFilter

    @Serializable
    @SerialName("WithinDistance")
    data class WithinDistance(val jumps: Int) : DistanceFilter
}

@Serializable
sealed interface EntityFilter {
    @Serializable
    @SerialName("Killmails")
    data object Killmails : EntityFilter

    @Serializable
    @SerialName("Characters")
    data object Characters : EntityFilter

    @Serializable
    @SerialName("Other")
    data object Other : EntityFilter
}

@Serializable
sealed interface SortingFilter {
    @Serializable
    @SerialName("Time")
    data object Time : SortingFilter

    @Serializable
    @SerialName("Distance")
    data object Distance : SortingFilter
}

@Serializable
sealed interface ColonyView {
    @Serializable
    @SerialName("List")
    data object List : ColonyView

    @Serializable
    @SerialName("Grid")
    data object Grid : ColonyView

    @Serializable
    @SerialName("Rows")
    data object Rows : ColonyView
}

@Serializable
sealed interface ColonySortingFilter {
    @Serializable
    @SerialName("Status")
    data object Status : ColonySortingFilter

    @Serializable
    @SerialName("Character")
    data object Character : ColonySortingFilter

    @Serializable
    @SerialName("ExpiryTime")
    data object ExpiryTime : ColonySortingFilter
}

@Serializable
enum class ConfigurationPack {
    Imperium,
    TheInitiative,
}

@Serializable
data class JumpRange(
    val fromId: Int,
    val distanceLy: Double,
)

@Serializable
data class Pushover(
    val apiToken: String? = null,
    val userKey: String? = null,
)

@Serializable
data class Ntfy(
    val topic: String? = null,
)
