package dev.nohus.rift.map

import androidx.compose.ui.geometry.Offset
import dev.nohus.rift.ViewModel
import dev.nohus.rift.autopilot.AutopilotController
import dev.nohus.rift.compose.Tab
import dev.nohus.rift.generated.resources.Res
import dev.nohus.rift.generated.resources.region
import dev.nohus.rift.generated.resources.sun
import dev.nohus.rift.get
import dev.nohus.rift.intel.state.IntelStateController
import dev.nohus.rift.intel.state.SystemEntity
import dev.nohus.rift.location.GetOnlineCharactersLocationUseCase
import dev.nohus.rift.location.GetOnlineCharactersLocationUseCase.OnlineCharacterLocation
import dev.nohus.rift.map.MapExternalControl.MapExternalControlEvent
import dev.nohus.rift.map.MapJumpRangeController.MapJumpRangeState
import dev.nohus.rift.map.MapLayoutRepository.Layout
import dev.nohus.rift.map.MapLayoutRepository.Position
import dev.nohus.rift.map.MapPlanetsController.MapPlanetsState
import dev.nohus.rift.map.MapViewModel.MapType.ClusterRegionsMap
import dev.nohus.rift.map.MapViewModel.MapType.ClusterSystemsMap
import dev.nohus.rift.map.MapViewModel.MapType.RegionMap
import dev.nohus.rift.repositories.JumpBridgesRepository
import dev.nohus.rift.repositories.JumpBridgesRepository.JumpBridgeConnection
import dev.nohus.rift.repositories.MapGateConnectionsRepository
import dev.nohus.rift.repositories.MapGateConnectionsRepository.GateConnection
import dev.nohus.rift.repositories.MapStatusRepository
import dev.nohus.rift.repositories.MapStatusRepository.SolarSystemStatus
import dev.nohus.rift.repositories.PlanetTypes.PlanetType
import dev.nohus.rift.repositories.SolarSystemsRepository
import dev.nohus.rift.repositories.SolarSystemsRepository.MapConstellation
import dev.nohus.rift.repositories.SolarSystemsRepository.MapRegion
import dev.nohus.rift.repositories.SolarSystemsRepository.MapSolarSystem
import dev.nohus.rift.settings.persistence.IntelMap
import dev.nohus.rift.settings.persistence.MapSystemInfoType
import dev.nohus.rift.settings.persistence.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.triangulate.VoronoiDiagramBuilder
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import dev.nohus.rift.settings.persistence.MapType as SettingsMapType

@Single
class MapViewModel(
    private val solarSystemsRepository: SolarSystemsRepository,
    private val gateConnectionsRepository: MapGateConnectionsRepository,
    private val layoutRepository: MapLayoutRepository,
    private val getOnlineCharactersLocationUseCase: GetOnlineCharactersLocationUseCase,
    private val intelStateController: IntelStateController,
    private val mapExternalControl: MapExternalControl,
    private val jumpBridgesRepository: JumpBridgesRepository,
    private val autopilotController: AutopilotController,
    private val mapStatusRepository: MapStatusRepository,
    private val mapJumpRangeController: MapJumpRangeController,
    private val mapPlanetsController: MapPlanetsController,
    private val settings: Settings,
) : ViewModel() {

    data class Cluster(
        val systems: List<MapSolarSystem>,
        val constellations: List<MapConstellation>,
        val regions: List<MapRegion>,
        val connections: List<GateConnection>,
        val jumpBridgeConnections: List<JumpBridgeConnection>?,
    )

    data class MapState(
        val hoveredSystem: Int? = null,
        val selectedSystem: Int? = null,
        val searchResults: List<Int> = emptyList(),
        val intel: Map<Int, List<IntelStateController.Dated<SystemEntity>>> = emptyMap(),
        val intelPopupSystems: List<Int> = emptyList(),
        val onlineCharacterLocations: Map<Int, List<OnlineCharacterLocation>> = emptyMap(),
        val systemStatus: Map<Int, SolarSystemStatus> = emptyMap(),
        val contextMenuSystem: Int? = null,
        val initialTransform: Transform? = null,
        val autopilotConnections: List<Pair<Int, Int>> = emptyList(),
    )

    sealed interface MapType {
        data object ClusterSystemsMap : MapType
        data object ClusterRegionsMap : MapType
        data class RegionMap(val layoutId: Int, val regionIds: List<Int>) : MapType
    }

    data class VoronoiLayout(
        val position: Position,
        val polygon: List<Position>,
    )

    data class SystemInfoTypes(
        val starSelected: Map<SettingsMapType, MapSystemInfoType>,
        val starApplied: Map<SettingsMapType, MapSystemInfoType>,
        val cellSelected: Map<SettingsMapType, MapSystemInfoType?>,
        val cellApplied: Map<SettingsMapType, MapSystemInfoType?>,
        val indicators: Map<SettingsMapType, List<MapSystemInfoType>>,
        val infoBox: Map<SettingsMapType, List<MapSystemInfoType>>,
    )

    data class UiState(
        val tabs: List<Tab>,
        val selectedTab: Int,
        val search: String?,
        val systemInfoTypes: SystemInfoTypes,
        val mapJumpRangeState: MapJumpRangeState,
        val mapPlanetsState: MapPlanetsState,
        val cluster: Cluster,
        val mapType: MapType,
        val layout: Map<Int, VoronoiLayout>,
        val jumpBridgeAdditionalSystems: Set<Int>,
        val mapState: MapState = MapState(),
        val alternativeLayouts: List<Layout>,
        val settings: IntelMap,
    )

    private val openLayouts = mutableSetOf<Int>()

    data class Transform(val center: Offset, val zoom: Double)
    private val mapTransforms = mutableMapOf<MapType, Transform>()

    private val _state = MutableStateFlow(
        UiState(
            tabs = createTabs(),
            selectedTab = 0,
            search = null,
            systemInfoTypes = getColorModes(),
            mapJumpRangeState = mapJumpRangeController.state.value,
            mapPlanetsState = mapPlanetsController.state.value,
            cluster = Cluster(
                systems = solarSystemsRepository.getSystems(knownSpace = true),
                constellations = solarSystemsRepository.mapConstellations,
                regions = solarSystemsRepository.mapRegions,
                connections = gateConnectionsRepository.gateConnections,
                jumpBridgeConnections = jumpBridgesRepository.getConnections(),
            ),
            mapType = ClusterSystemsMap,
            layout = emptyMap(),
            jumpBridgeAdditionalSystems = emptySet(),
            alternativeLayouts = emptyList(),
            settings = settings.intelMap,
        ),
    )
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            intelStateController.state.collect { updateIntel() }
        }
        viewModelScope.launch {
            getOnlineCharactersLocationUseCase().collect(::onOnlineCharacterLocationsUpdated)
        }
        viewModelScope.launch {
            mapStatusRepository.status.collect { status -> updateMapState { copy(systemStatus = status) } }
        }
        viewModelScope.launch {
            mapJumpRangeController.state.collect { state -> _state.update { it.copy(mapJumpRangeState = state) } }
        }
        viewModelScope.launch {
            mapPlanetsController.state.collect { state -> _state.update { it.copy(mapPlanetsState = state) } }
        }
        viewModelScope.launch {
            settings.updateFlow.collect {
                _state.update {
                    it.copy(
                        systemInfoTypes = getColorModes(),
                        settings = settings.intelMap,
                        cluster = it.cluster.copy(jumpBridgeConnections = jumpBridgesRepository.getConnections()),
                    )
                }
            }
        }
        viewModelScope.launch {
            autopilotController.activeRoutes.map { map ->
                map.flatMap { (characterId, route) ->
                    route.systems.windowed(2).map { (from, to) -> from to to }
                }
            }.collect {
                updateMapState { copy(autopilotConnections = it) }
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(1000)
                updateIntel()
            }
        }
        viewModelScope.launch {
            mapExternalControl.event.collect {
                delay(50) // If this event comes from a context menu, let the menu disappear
                when (val event = it.get()) {
                    is MapExternalControlEvent.ShowSystem -> {
                        openTab(0, event.solarSystemId)
                    }
                    is MapExternalControlEvent.ShowSystemOnRegionMap -> {
                        val regionId = solarSystemsRepository.getRegionIdBySystemId(event.solarSystemId) ?: return@collect
                        if (regionId in solarSystemsRepository.getKnownSpaceRegions().map { it.id }) {
                            openRegionMap(regionId, event.solarSystemId)
                        }
                    }
                    null -> {}
                }
            }
        }

        openInitialTab()
    }

    private fun openInitialTab() {
        val openedLayoutId = settings.intelMap.openedLayoutId
        if (openedLayoutId != null) {
            openLayoutMap(openedLayoutId, focusedId = null)
        } else {
            openTab(_state.value.selectedTab, focusedId = null)
        }
    }

    private fun getColorModes(): SystemInfoTypes {
        val star = mapOf(
            SettingsMapType.NewEden to MapSystemInfoType.Security,
            SettingsMapType.Region to MapSystemInfoType.Security,
        ) + settings.intelMap.mapTypeStarInfoTypes
        return SystemInfoTypes(
            starSelected = star,
            starApplied = star,
            cellSelected = settings.intelMap.mapTypeCellInfoTypes,
            cellApplied = settings.intelMap.mapTypeCellInfoTypes,
            indicators = settings.intelMap.mapTypeIndicatorInfoTypes,
            infoBox = settings.intelMap.mapTypeInfoBoxInfoTypes,
        )
    }

    fun onMapHover(offset: Offset, mapScale: Float) {
        if (_state.value.mapType == ClusterRegionsMap) return
        val (closestSystemId, closestSystemLayout) = _state.value.layout.minBy { (_, layout) ->
            val position = layout.position
            (offset.x - position.x).pow(2) + (offset.y - position.y).pow(2)
        }
        val closestSystem = solarSystemsRepository.getSystems(knownSpace = true).first { it.id == closestSystemId }
        val closestSystemLayoutPosition = closestSystemLayout.position
        val distanceInPixels = sqrt((offset.x - closestSystemLayoutPosition.x).pow(2) + (offset.y - closestSystemLayoutPosition.y).pow(2)) / mapScale
        val hoveredSystem = if (distanceInPixels < 10) closestSystem.id else null
        updateMapState { copy(hoveredSystem = hoveredSystem) }
    }

    fun onRegionPointerEnter(regionId: Int) {
        updateMapState { copy(hoveredSystem = regionId) }
    }

    fun onRegionPointerExit(regionId: Int) {
        updateMapState { copy(hoveredSystem = if (hoveredSystem == regionId) null else hoveredSystem) }
    }

    fun onRegionClick(regionId: Int, systemId: Int) {
        openRegionMap(regionId, focusedId = systemId)
    }

    fun onTabClose(tabId: Int) {
        val tab = _state.value.tabs.firstOrNull { it.id == tabId } ?: return
        val layoutId = (tab.payload as? RegionMap)?.layoutId ?: return
        closeRegionMap(layoutId)
    }

    fun onMapClick(button: Int) {
        val hoveredSystem = _state.value.mapState.hoveredSystem
        if (button == 1) { // Left click
            updateMapState { copy(selectedSystem = hoveredSystem, contextMenuSystem = null) }
            if (state.value.mapType == ClusterRegionsMap && hoveredSystem != null) {
                openRegionMap(hoveredSystem, focusedId = null)
            }
        } else if (button == 3) { // Right click
            updateMapState { copy(contextMenuSystem = hoveredSystem) }
        }
    }

    private fun closeRegionMap(layoutId: Int) {
        openLayouts -= layoutId
        val tabs = createTabs()
        val tabIndex = _state.value.selectedTab.coerceAtMost(tabs.last().id)
        _state.update { it.copy(tabs = tabs) }
        openTab(tabIndex, focusedId = null)
    }

    private fun openRegionMap(regionId: Int, focusedId: Int?) {
        val layout = layoutRepository.getLayouts(regionId).first()
        openLayoutMap(layout.layoutId, focusedId)
    }

    private fun openLayoutMap(layoutId: Int, focusedId: Int?) {
        openLayouts += layoutId
        val tabs = createTabs()
        val tabIndex = tabs.reversed().firstOrNull { (it.payload as? RegionMap)?.layoutId == layoutId }?.id ?: return
        _state.update { it.copy(tabs = tabs) }
        openTab(tabIndex, focusedId)
    }

    fun onContextMenuDismiss() {
        updateMapState { copy(contextMenuSystem = null) }
    }

    fun onTabSelect(id: Int) {
        openTab(id, focusedId = null)
    }

    fun onMapTransformChanged(mapType: MapType, transform: Transform) {
        mapTransforms[mapType] = transform
    }

    fun onSearchChange(text: String) {
        val search = text.takeIf { it.isNotBlank() }?.trim()
        if (search != null && search.length >= 2) {
            val systemIds = _state.value.cluster.systems
                .filter { text.lowercase() in it.name.lowercase() }
                .map { it.id }
            val regionsIds = _state.value.cluster.regions
                .filter { text.lowercase() in it.name.lowercase() }
                .map { it.id }
            val resultIds = systemIds + regionsIds
            updateMapState { copy(searchResults = resultIds) }
        } else {
            updateMapState { copy(searchResults = emptyList()) }
        }
        _state.update { it.copy(search = search) }
    }

    fun onSearchSubmit() {
        val resultIds = _state.value.mapState.searchResults
        val visibleIds = _state.value.layout.keys
        val visibleResultIds = resultIds.intersect(visibleIds).toList()
        if (visibleResultIds.isEmpty()) return

        val selected = _state.value.mapState.selectedSystem
        var index = visibleResultIds.indexOf(selected) + 1
        if (index > visibleResultIds.lastIndex) index = 0

        updateMapState { copy(selectedSystem = visibleResultIds[index]) }
    }

    fun onSystemColorChange(mapType: SettingsMapType, selected: MapSystemInfoType) {
        val new = settings.intelMap.mapTypeStarInfoTypes + (mapType to selected)
        settings.intelMap = settings.intelMap.copy(mapTypeStarInfoTypes = new)
    }

    fun onSystemColorHover(mapType: SettingsMapType, selected: MapSystemInfoType, isHovered: Boolean) {
        val new = if (isHovered) _state.value.systemInfoTypes.starSelected + (mapType to selected) else getColorModes().starSelected
        _state.update { it.copy(systemInfoTypes = it.systemInfoTypes.copy(starApplied = new)) }
    }

    fun onCellColorChange(mapType: SettingsMapType, selected: MapSystemInfoType?) {
        val new = settings.intelMap.mapTypeCellInfoTypes + (mapType to selected)
        settings.intelMap = settings.intelMap.copy(mapTypeCellInfoTypes = new)
    }

    fun onCellColorHover(mapType: SettingsMapType, selected: MapSystemInfoType?, isHovered: Boolean) {
        val new = if (isHovered) _state.value.systemInfoTypes.cellSelected + (mapType to selected) else getColorModes().cellSelected
        _state.update { it.copy(systemInfoTypes = it.systemInfoTypes.copy(cellApplied = new)) }
    }

    fun onIndicatorChange(mapType: SettingsMapType, selected: MapSystemInfoType) {
        val newMap = toggleType(mapType, settings.intelMap.mapTypeIndicatorInfoTypes, selected)
        settings.intelMap = settings.intelMap.copy(mapTypeIndicatorInfoTypes = newMap)
    }

    fun onInfoBoxChange(mapType: SettingsMapType, selected: MapSystemInfoType) {
        val newMap = toggleType(mapType, settings.intelMap.mapTypeInfoBoxInfoTypes, selected)
        settings.intelMap = settings.intelMap.copy(mapTypeInfoBoxInfoTypes = newMap)
    }

    private fun toggleType(mapType: SettingsMapType, currentMap: Map<SettingsMapType, List<MapSystemInfoType>>, selected: MapSystemInfoType): Map<SettingsMapType, List<MapSystemInfoType>> {
        val currentTypes = currentMap[mapType].orEmpty()
        val newTypes = if (selected in currentTypes) currentTypes - selected else currentTypes + selected
        return currentMap + (mapType to newTypes.sortedBy { it.ordinal })
    }

    fun onJumpRangeTargetUpdate(target: String) {
        mapJumpRangeController.onTargetUpdate(target)
    }

    fun onJumpRangeDistanceUpdate(distanceLy: Double) {
        mapJumpRangeController.onRangeUpdate(distanceLy)
    }

    fun onPlanetTypesUpdate(types: List<PlanetType>) {
        mapPlanetsController.onPlanetTypesUpdate(types)
    }

    fun onLayoutSelected(layoutId: Int) {
        openLayoutMap(layoutId, _state.value.mapState.selectedSystem)
    }

    private fun openTab(id: Int, focusedId: Int?) {
        val tab = _state.value.tabs.firstOrNull { it.id == id } ?: return
        val mapType = tab.payload as? MapType ?: return
        rememberOpenedLayout(mapType)
        mapExternalControl.openedRegions.update { (mapType as? RegionMap)?.regionIds }

        val layout = when (mapType) {
            ClusterSystemsMap -> layoutRepository.getNewEdenSystemPosition()
            ClusterRegionsMap -> layoutRepository.getRegionsPositions()
            is RegionMap -> layoutRepository.getLayoutSystemPositions(mapType.layoutId) ?: throw IllegalArgumentException("No such layout: ${mapType.layoutId}")
        }
        val jumpBridgeAdditionalSystemsLayout = if (mapType is RegionMap) getJumpBridgeDestinationsLayout(layout) else emptyMap()
        val combined = calculateVoronoi(layout + jumpBridgeAdditionalSystemsLayout)

        val alternativeLayouts = getAlternativeLayouts(mapType)

        var selectedId = focusedId ?: getOnlineCharacterLocationId(mapType)
        if (selectedId !in layout.keys) selectedId = null
        val initialTransform = mapTransforms[mapType]

        updateMapState { copy(hoveredSystem = null, selectedSystem = selectedId, contextMenuSystem = null, initialTransform = initialTransform) }
        _state.update {
            it.copy(
                selectedTab = id,
                mapType = mapType,
                layout = combined,
                jumpBridgeAdditionalSystems = jumpBridgeAdditionalSystemsLayout.keys,
                alternativeLayouts = alternativeLayouts,
            )
        }
    }

    private fun getAlternativeLayouts(mapType: MapType): List<Layout> {
        return when (mapType) {
            ClusterRegionsMap -> emptyList()
            ClusterSystemsMap -> emptyList()
            is RegionMap -> {
                mapType.regionIds.flatMap { layoutRepository.getLayouts(it) }.distinct()
            }
        }
    }

    private fun rememberOpenedLayout(mapType: MapType) {
        val openedLayoutId = if (mapType is RegionMap) mapType.layoutId else null
        settings.intelMap = settings.intelMap.copy(openedLayoutId = openedLayoutId)
    }

    private fun calculateVoronoi(systems: Map<Int, Position>): Map<Int, VoronoiLayout> {
        val coordinates = systems.map { (system, position) ->
            Coordinate(position.x.toDouble(), position.y.toDouble()) to system
        }.toMap()
        val builder = VoronoiDiagramBuilder()
        builder.setSites(coordinates.keys)
        val diagram = builder.getDiagram(GeometryFactory()) as GeometryCollection
        return List(diagram.numGeometries) {
            val polygon = diagram.getGeometryN(it) as Polygon
            val points = polygon.exteriorRing.coordinates.map {
                Position(it.x.toInt(), it.y.toInt())
            }
            val coordinate = polygon.userData as Coordinate
            val system = coordinates[coordinate]!!
            system to VoronoiLayout(systems[system]!!, points)
        }.toMap()
    }

    /**
     * Layout of systems that are connected by jump bridges from systems in this layout
     */
    private fun getJumpBridgeDestinationsLayout(originsLayout: Map<Int, Position>): Map<Int, Position> {
        val outgoingJumpBridgeConnectionsInLayout = jumpBridgesRepository.getConnections()?.filter {
            (it.from.id in originsLayout.keys) xor (it.to.id in originsLayout.keys)
        }.orEmpty()
        return outgoingJumpBridgeConnectionsInLayout.fold(emptyMap()) { layout, connection ->
            val (origin, destination) = if (connection.from.id in originsLayout.keys) connection.from to connection.to else connection.to to connection.from
            if (destination.id in layout.keys) return@fold layout // Already in layout
            val entry = destination.id to getOutOfRegionLayoutPosition(originsLayout, layout, origin.id)
            layout + entry
        }
    }

    private fun getOutOfRegionLayoutPosition(
        originsLayout: Map<Int, Position>,
        outOfRegionLayout: Map<Int, Position>,
        system: Int,
    ): Position {
        val layoutCenter = Position(originsLayout.maxOf { it.value.x } / 2, originsLayout.maxOf { it.value.y } / 2)
        val position = originsLayout[system]!!
        val slope = if (position.x != layoutCenter.x) {
            (position.y - layoutCenter.y) / (position.x - layoutCenter.x).toFloat()
        } else {
            if (position.y > layoutCenter.y) 1000f else -1000f
        }
        val b = position.y - (position.x * slope)

        val xDelta = if (position.x > layoutCenter.x) 1 else -1
        var x = position.x + xDelta
        val combinedLayout = originsLayout + outOfRegionLayout
        val minDistance = 80
        while (combinedLayout.minOf { Position(x, (x * slope + b).toInt()).distanceSquared(it.value) } < (minDistance * minDistance)) {
            x += xDelta
        }

        val y = x * slope + b
        return Position(x, y.roundToInt())
    }

    private fun getOnlineCharacterLocationId(mapType: MapType): Int? {
        return _state.value.mapState.onlineCharacterLocations.values
            .flatten()
            .filter {
                when (mapType) {
                    ClusterRegionsMap, ClusterSystemsMap -> true
                    is RegionMap -> it.location.regionId in mapType.regionIds
                }
            }
            .map {
                when (mapType) {
                    ClusterRegionsMap -> it.location.regionId
                    ClusterSystemsMap, is RegionMap -> it.location.solarSystemId
                }
            }
            .firstOrNull()
    }

    private fun onOnlineCharacterLocationsUpdated(onlineCharacterLocations: List<OnlineCharacterLocation>) {
        if (settings.intelMap.isCharacterFollowing) {
            val current = _state.value.mapState.onlineCharacterLocations.values.flatten()
            onlineCharacterLocations.forEach { onlineCharacterLocation ->
                val previous = current.firstOrNull { it.id == onlineCharacterLocation.id }
                if (previous?.location?.solarSystemId == onlineCharacterLocation.location.solarSystemId) return@forEach

                val systemId = onlineCharacterLocation.location.solarSystemId
                val regionId = onlineCharacterLocation.location.regionId ?: return@forEach

                when (val mapType = _state.value.mapType) {
                    ClusterRegionsMap -> {
                        updateMapState { copy(selectedSystem = regionId) }
                    }
                    ClusterSystemsMap -> {
                        updateMapState { copy(selectedSystem = systemId) }
                    }
                    is RegionMap -> {
                        if (regionId in mapType.regionIds) {
                            updateMapState { copy(selectedSystem = systemId) }
                        } else {
                            if (layoutRepository.getLayouts(regionId).isNotEmpty()) {
                                openRegionMap(regionId, systemId)
                            }
                        }
                    }
                }
            }
        }

        val locations = onlineCharacterLocations.groupBy { it.location.solarSystemId }
        _state.update { it.copy(mapState = it.mapState.copy(onlineCharacterLocations = locations)) }
    }

    private fun createTabs(): List<Tab> {
        return listOf(
            Tab(id = 0, title = "New Eden", isCloseable = false, icon = Res.drawable.sun, payload = ClusterSystemsMap),
            Tab(id = 1, title = "Regions", isCloseable = false, icon = Res.drawable.region, payload = ClusterRegionsMap),
        ) + openLayouts.mapIndexed { index, layoutId ->
            val layout = layoutRepository.getLayout(layoutId)
            val name = layout?.name ?: "$layoutId"
            val regionIds = layout?.regionIds ?: emptyList()
            Tab(id = 2 + index, title = name, isCloseable = true, payload = RegionMap(layoutId, regionIds))
        }
    }

    private fun updateMapState(update: MapState.() -> MapState) {
        _state.update { state -> state.copy(mapState = state.mapState.update()) }
    }

    private fun updateIntel() = viewModelScope.launch {
        val intelBySystemName = intelStateController.state.value
        val intelBySystemId = intelBySystemName.mapKeys { (key, _) ->
            solarSystemsRepository.getSystemId(key)!!
        }

        val popupMinTimestamp = Instant.now() - Duration.ofSeconds(settings.intelMap.intelPopupTimeoutSeconds.toLong())
        val filtered = intelBySystemId.filter { (_, datedEntities) ->
            datedEntities.isNotEmpty() // Remove systems that no longer have any intel to show
        }
        val popupSystems = filtered.mapNotNull { (systemId, datedEntities) ->
            val showPopup =
                datedEntities.any { it.timestamp >= popupMinTimestamp } || // Only show system if within popup timeout setting
                    systemId == _state.value.mapState.hoveredSystem // Or is hovered
            systemId.takeIf { showPopup }
        }

        updateMapState { copy(intel = filtered, intelPopupSystems = popupSystems) }
    }
}
