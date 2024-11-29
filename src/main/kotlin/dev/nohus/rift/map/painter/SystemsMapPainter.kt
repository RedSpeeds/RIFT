package dev.nohus.rift.map.painter

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.nohus.rift.compose.theme.RiftTheme
import dev.nohus.rift.map.DoubleOffset
import dev.nohus.rift.map.MapLayoutRepository
import dev.nohus.rift.map.MapViewModel.Cluster
import dev.nohus.rift.map.MapViewModel.MapType
import dev.nohus.rift.map.MapViewModel.MapType.RegionMap
import dev.nohus.rift.map.MapViewModel.VoronoiLayout
import dev.nohus.rift.map.SOLAR_SYSTEM_NODE_BACKGROUND_CIRCLE_MAX_SCALE
import dev.nohus.rift.map.systemcolor.SystemColorStrategy
import dev.nohus.rift.repositories.MapGateConnectionsRepository
import dev.nohus.rift.repositories.MapGateConnectionsRepository.GateConnection
import dev.nohus.rift.repositories.SolarSystemsRepository.MapRegion
import dev.nohus.rift.repositories.SolarSystemsRepository.MapSolarSystem
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt

class SystemsMapPainter(
    private val cluster: Cluster,
    private val layout: Map<Int, VoronoiLayout>,
    private val jumpBridgeAdditionalSystems: Set<Int>,
    private val mapType: MapType,
    private val isJumpBridgeNetworkShown: Boolean,
    private val jumpBridgeNetworkOpacity: Int,
    private val autopilotConnections: List<Pair<Int, Int>>,
) : MapPainter {

    private lateinit var textMeasurer: TextMeasurer
    private lateinit var regionNameStyle: TextStyle
    private var mapBackground: Color = Color.Unspecified
    private var nodeSafeZoneFraction: Float = 0f
    private var cellGradientRadius: Float = 0f
    private var maxCellScale: Float = 0f
    private var density: Float = 1f
    private val systemsIdsInLayout = layout.keys
    private val systemsInLayout = cluster.systems.filter {
        it.id in systemsIdsInLayout
    }
    private val systemsWithGateConnections = systemsIdsInLayout - jumpBridgeAdditionalSystems
    private val connectionsInLayout = cluster.connections.filter {
        it.from.id in systemsWithGateConnections && it.to.id in systemsWithGateConnections
    }
    data class JumpBridgeConnectionLine(val from: MapSolarSystem, val to: MapSolarSystem, val bidirectional: Boolean)
    private val jumpBridgeConnectionsInLayout = cluster.jumpBridgeConnections?.filter {
        it.from.id in systemsIdsInLayout || it.to.id in systemsIdsInLayout
    } ?: emptyList()
    private val jumpBridgeConnectionLines = jumpBridgeConnectionsInLayout.map { connection ->
        val (from, to) = listOf(connection.from, connection.to).sortedBy { it.id }
        val isBidirectional = jumpBridgeConnectionsInLayout.find { it.from == to && it.to == from } != null
        JumpBridgeConnectionLine(from, to, isBidirectional)
    }.distinct()
    private val drawCache = DrawCache()
    private val offsetComparator = compareBy<Offset>({ it.x }, { it.y })

    @Composable
    override fun initializeComposed() {
        textMeasurer = rememberTextMeasurer()
        regionNameStyle = RiftTheme.typography.captionPrimary.copy(letterSpacing = 3.sp)
        mapBackground = RiftTheme.colors.mapBackground
        maxCellScale = 6f / LocalDensity.current.density
        density = LocalDensity.current.density
        val nodeSafeZoneRadius = if (mapType is RegionMap) {
            LocalDensity.current.run { 20.dp.toPx() }
        } else {
            LocalDensity.current.run { 5.dp.toPx() }
        }
        cellGradientRadius = LocalDensity.current.run { 100.dp.toPx() }
        nodeSafeZoneFraction = nodeSafeZoneRadius / (cellGradientRadius / (SOLAR_SYSTEM_NODE_BACKGROUND_CIRCLE_MAX_SCALE / LocalDensity.current.density))
    }

    override fun drawStatic(
        scope: DrawScope,
        center: DoubleOffset,
        scale: Float,
        zoom: Float,
        systemColorStrategy: SystemColorStrategy,
        cellColorStrategy: SystemColorStrategy?,
    ) = with(scope) {
        drawCache.updateScale(scale)
        if (cellColorStrategy != null && scale <= maxCellScale) {
            systemsInLayout.forEach { system ->
                drawSystemCell(system, center, scale, cellColorStrategy)
            }
        }
        if (mapType is MapType.ClusterSystemsMap) {
            systemsInLayout.forEach { system ->
                drawSystem(system, center, scale, zoom, systemColorStrategy)
            }
        }
    }

    override fun drawAnimated(
        scope: DrawScope,
        center: DoubleOffset,
        scale: Float,
        zoom: Float,
        animationPercentage: Float,
        systemColorStrategy: SystemColorStrategy,
    ) = with(scope) {
        drawCache.updateScale(scale)
        connectionsInLayout.forEach { connection ->
            drawSystemConnection(connection, mapType, center, scale, zoom, animationPercentage, systemColorStrategy)
        }
        if (isJumpBridgeNetworkShown) {
            jumpBridgeConnectionLines.forEach { connection ->
                drawJumpBridgeConnection(connection, mapType, center, scale, zoom, animationPercentage, systemColorStrategy)
            }
        }
        if (mapType is MapType.ClusterSystemsMap) {
            cluster.regions.forEach { region ->
                drawRegion(region, center, scale)
            }
        }
    }

    private fun DrawScope.drawSystemCell(
        system: MapSolarSystem,
        center: DoubleOffset,
        scale: Float,
        cellColorStrategy: SystemColorStrategy?,
    ) {
        val layout = layout[system.id] ?: return
        val offset = layout.position.let { getCanvasCoordinates(it.x, it.y, center, scale) }
        val polygon = layout.polygon.map { getCanvasCoordinates(it.x, it.y, center, scale) - offset }
        if (polygon.any { isOnCanvas(offset + it, 100) }) {
            if (cellColorStrategy?.hasData(system.id) != true) return
            val cellColor = cellColorStrategy.getActiveColor(system.id)
            val path = Path().apply {
                moveTo(polygon[0].x, polygon[0].y)
                polygon.drop(1).forEach { point -> lineTo(point.x, point.y) }
                close()
            }
            val alphaModifier = (maxCellScale - scale).coerceIn(0f, 1f)
            val brush = drawCache.getSystemCellGradient(
                cellColor,
                nodeSafeZoneFraction,
                cellGradientRadius,
                alphaModifier,
                scale,
            )

            translate(offset.x, offset.y) {
                drawPath(path, brush, style = Fill)
                val width = (0.5f / scale).coerceAtMost(0.5f) * density
                drawPath(path, mapBackground, style = Stroke(width), blendMode = BlendMode.Src)
            }
        }
    }

    private fun DrawScope.drawRegion(
        region: MapRegion,
        center: DoubleOffset,
        scale: Float,
    ) {
        val position = MapLayoutRepository.transformNewEdenCoordinate(region.x, region.z)
        val offset = getCanvasCoordinates(position.x, position.y, center, scale)
        if (isOnCanvas(offset, 100)) {
            val textLayout = textMeasurer.measure(region.name.uppercase(), style = regionNameStyle, softWrap = false)
            val topLeft = Offset(offset.x - textLayout.size.width.toFloat() / 2, offset.y - textLayout.size.height.toFloat() / 2)
            drawText(textLayout, topLeft = topLeft)
        }
    }

    private fun DrawScope.drawSystem(
        system: MapSolarSystem,
        center: DoubleOffset,
        scale: Float,
        zoom: Float,
        systemColorStrategy: SystemColorStrategy,
    ) {
        val position = MapLayoutRepository.transformNewEdenCoordinate(system.x, system.z)
        val offset = getCanvasCoordinates(position.x, position.y, center, scale)
        if (isOnCanvas(offset, 100)) {
            val systemColor = systemColorStrategy.getActiveColor(system.id)
            val radius = 4f * density
            val brush = drawCache.getSystemRadialGradient(systemColor, radius)
            translate(offset.x, offset.y) {
                drawCircle(brush, radius = radius, center = Offset.Zero)
                drawCircle(systemColor, radius = (0.2f * density * zoom / 2), center = Offset.Zero)
            }
        }
    }

    private fun DrawScope.drawSystemConnection(
        connection: GateConnection,
        mapType: MapType,
        center: DoubleOffset,
        scale: Float,
        zoom: Float,
        animation: Float,
        systemColorStrategy: SystemColorStrategy,
    ) {
        val fromLayoutPosition = layout[connection.from.id]!!.position
        val toLayoutPosition = layout[connection.to.id]!!.position
        val from = getCanvasCoordinates(fromLayoutPosition.x, fromLayoutPosition.y, center, scale)
        val to = getCanvasCoordinates(toLayoutPosition.x, toLayoutPosition.y, center, scale)
        val deltaOffset = to - from

        val autopilotPathEffect = getAutopilotPathEffect(connection.from.id, connection.to.id, animation, zoom)
        if (connection.type == MapGateConnectionsRepository.ConnectionType.Region || scale < 4 || mapType is RegionMap) {
            translate(from.x, from.y) {
                val width = (1f / scale).coerceAtMost(2f) * density
                if (autopilotPathEffect != null) {
                    val fromColor = systemColorStrategy.getActiveColor(connection.from.id)
                    val toColor = systemColorStrategy.getActiveColor(connection.to.id)
                    val brush1 = drawCache.getSystemConnectionLinearGradient(fromColor.copy(alpha = 0.25f), toColor.copy(alpha = 0.25f), deltaOffset)
                    val brush2 = drawCache.getSystemConnectionLinearGradient(fromColor, toColor, deltaOffset)
                    drawLine(
                        brush = brush1,
                        start = Offset.Zero,
                        end = deltaOffset,
                        strokeWidth = width * 2,
                    )
                    drawLine(
                        brush = brush2,
                        start = Offset.Zero,
                        end = deltaOffset,
                        strokeWidth = width * 2,
                        pathEffect = autopilotPathEffect,
                    )
                } else {
                    val (fromColor, toColor) = if (mapType is RegionMap || scale < 0.5) {
                        systemColorStrategy.getActiveColor(connection.from.id) to
                            systemColorStrategy.getActiveColor(connection.to.id)
                    } else {
                        systemColorStrategy.getInactiveColor(connection.from.id) to
                            systemColorStrategy.getInactiveColor(connection.to.id)
                    }
                    val brush = drawCache.getSystemConnectionLinearGradient(fromColor, toColor, deltaOffset)
                    val effect = when (connection.type) {
                        MapGateConnectionsRepository.ConnectionType.System -> null
                        MapGateConnectionsRepository.ConnectionType.Constellation -> PathEffect.dashPathEffect(floatArrayOf(6.0f * zoom * density, 2.0f * zoom * density))
                        MapGateConnectionsRepository.ConnectionType.Region -> PathEffect.dashPathEffect(floatArrayOf(15.0f * zoom * density, 5.0f * zoom * density))
                    }
                    drawLine(
                        brush = brush,
                        start = Offset.Zero,
                        end = deltaOffset,
                        strokeWidth = width,
                        pathEffect = effect,
                    )
                }
            }
        }
    }

    private fun DrawScope.drawJumpBridgeConnection(
        connection: JumpBridgeConnectionLine,
        mapType: MapType,
        center: DoubleOffset,
        scale: Float,
        zoom: Float,
        animation: Float,
        systemColorStrategy: SystemColorStrategy,
    ) {
        val fromLayoutPosition = layout[connection.from.id]?.position ?: return
        val toLayoutPosition = layout[connection.to.id]?.position ?: return
        val from = getCanvasCoordinates(fromLayoutPosition.x, fromLayoutPosition.y, center, scale)
        val to = getCanvasCoordinates(toLayoutPosition.x, toLayoutPosition.y, center, scale)
        val distance = sqrt((from.x - to.x).toDouble().pow(2.0) + (from.y - to.y).toDouble().pow(2.0)).toFloat()

        val isReversed = offsetComparator.compare(from, to) > 0
        val p1 = if (isReversed) to else from
        val p2 = if (isReversed) from else to
        val autopilotFrom = if (isReversed) connection.to.id else connection.from.id
        val autopilotTo = if (isReversed) connection.from.id else connection.to.id

        val width = (1f / scale).coerceAtMost(2f) * density
        val autopilotPathEffect = getAutopilotPathEffect(autopilotFrom, autopilotTo, animation, zoom)
        val vector = p2 - p1
        if (autopilotPathEffect != null) {
            val fromColor = systemColorStrategy.getActiveColor(connection.from.id)
            val toColor = systemColorStrategy.getActiveColor(connection.to.id)
            val bridgeColor = Color(0xFF75D25A)
            val colors = listOf(fromColor, bridgeColor, toColor)
            val brush1 = drawCache.getJumpBridgeBrush(
                colors = colors.map { it.copy(alpha = 0.25f) },
                start = if (isReversed) vector else Offset.Zero,
                end = if (isReversed) Offset.Zero else vector,
            )
            val brush2 = drawCache.getJumpBridgeBrush(
                colors = colors,
                start = if (isReversed) vector else Offset.Zero,
                end = if (isReversed) Offset.Zero else vector,
            )
            translate(p1.x, p1.y) {
                val (stroke1, stroke2) = drawCache.getJumpBridgeAutopilotStrokes(width, density, autopilotPathEffect)
                drawArcBetweenTwoPoints(Offset.Zero, vector, distance * 0.75f, brush1, stroke1)
                drawArcBetweenTwoPoints(Offset.Zero, vector, distance * 0.75f, brush2, stroke2)
            }
        } else {
            val alphaModifier = jumpBridgeNetworkOpacity / 100f
            val toColorFilter: Color.(isBidirectional: Boolean) -> Color = { if (it) this else this.copy(alpha = 0.1f) }
            val colors = if (mapType is RegionMap || scale < 0.5) {
                val fromColor = systemColorStrategy.getActiveColor(connection.from.id)
                val toColor = systemColorStrategy.getActiveColor(connection.to.id)
                val bridgeColor = Color(0xFF75D25A)
                drawCache.getColorList(fromColor, bridgeColor, bridgeColor.toColorFilter(connection.bidirectional), toColor.toColorFilter(connection.bidirectional))
            } else {
                val fromColor = systemColorStrategy.getInactiveColor(connection.from.id)
                val toColor = systemColorStrategy.getInactiveColor(connection.to.id)
                val bridgeColor = Color(0xFF75D25A).copy(alpha = 0.1f)
                drawCache.getColorList(fromColor, bridgeColor, bridgeColor.toColorFilter(connection.bidirectional), toColor.toColorFilter(connection.bidirectional))
            }.map { it.copy(alpha = it.alpha * alphaModifier) }
            val brush = drawCache.getJumpBridgeBrush(
                colors = colors,
                start = if (isReversed) vector else Offset.Zero,
                end = if (isReversed) Offset.Zero else vector,
            )
            val stroke = drawCache.getJumpBridgeStroke(width, zoom, density)
            translate(p1.x, p1.y) {
                drawArcBetweenTwoPoints(Offset.Zero, vector, distance * 0.75f, brush, stroke)
            }
        }
    }

    private fun DrawScope.getAutopilotPathEffect(from: Int, to: Int, animation: Float, zoom: Float): PathEffect? {
        val factor = animation * 96f * density
        val phase = when {
            autopilotConnections.any { it.first == from && it.second == to } -> (1f - factor) * zoom
            autopilotConnections.any { it.first == to && it.second == from } -> factor * zoom
            else -> return null
        }
        return drawCache.getAutopilotPathEffect(zoom, density, phase)
    }

    private fun DrawScope.drawArcBetweenTwoPoints(
        a: Offset,
        b: Offset,
        radius: Float,
        brush: Brush,
        style: DrawStyle,
    ) {
        val x = b.x - a.x
        val y = b.y - a.y
        val angle = atan2(y, x)
        val l = sqrt((x * x + y * y))
        if (2 * radius >= l) {
            val sweep = asin(l / (2 * radius))
            val h = radius * cos(sweep)
            val c = Offset(
                x = (a.x + x / 2 - h * (y / l)),
                y = (a.y + y / 2 + h * (x / l)),
            )
            val sweepAngle = Math.toDegrees((2 * sweep).toDouble()).toFloat()
            drawArc(
                brush = brush,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2, radius * 2),
                startAngle = (Math.toDegrees((angle - sweep).toDouble()) - 90).toFloat(),
                sweepAngle = sweepAngle,
                useCenter = false,
                style = style,
            )
        }
    }

    private fun DrawScope.getCanvasCoordinates(x: Int, y: Int, center: DoubleOffset, scale: Float): Offset {
        val canvasX = (x - center.x) / scale + size.center.x
        val canvasY = (y - center.y) / scale + size.center.y
        return Offset(canvasX.toFloat(), canvasY.toFloat())
    }

    private fun DrawScope.isOnCanvas(offset: Offset, margin: Int = 0): Boolean {
        return offset.x >= -margin && offset.y >= -margin && offset.x < size.width + margin && offset.y < size.height + margin
    }
}
