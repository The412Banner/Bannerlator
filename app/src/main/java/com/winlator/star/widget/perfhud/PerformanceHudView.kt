package com.winlator.star.widget.perfhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.star.container.Container
import com.winlator.star.core.KeyValueSet
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.widget.FpsCounter
import com.winlator.star.widget.HudMetrics
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.function.BiConsumer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * GameNative-style floating performance HUD — the third selectable in-game overlay style, alongside
 * the classic FrameRating and the GameHub-style PerfHudView. Ported from GameNative's
 * PerformanceHudView with these adaptations to fit our system:
 *
 *  - The FPS number comes from the shared FpsCounter via [fpsProvider] (single source of truth).
 *  - Metric collection is delegated to our hardened [HudMetrics] collector (no divergent sysfs
 *    readers live in the view).
 *  - Configuration is our [KeyValueSet] via [applyConfig] (GameNative's PerformanceHudConfig +
 *    PrefManager are dropped).
 *  - Public surface (applyConfig/setVertical/setEngineLabel/setGpuModel/setOnTapListener/
 *    setOnMovedListener + drag/tap) matches PerfHudView so the host can build/swap it symmetrically.
 *
 * Threading: the view self-refreshes on its own 1 s Main-dispatcher coroutine (independent of the
 * epoll present tick), so requestLayout()/invalidate() are always on the UI thread. The label
 * setters post their view mutation to the UI thread too, honoring the epoll-thread rule.
 */
class PerformanceHudView(
    context: Context,
    private val fpsProvider: () -> Float,
) : FrameLayout(context) {

    /**
     * Java-friendly entry point, symmetric with PerfHudView/FrameRating: construct with just a
     * Context and feed the FPS number via [setFpsCounter]. The [fpsProvider] path stays intact and
     * is used only when no counter is attached.
     */
    constructor(context: Context) : this(context, { 0f })

    // When set, this shared counter is the authoritative FPS source (single source of truth); the
    // Kotlin [fpsProvider] lambda is the fallback for callers that use the primary constructor.
    private var fpsCounter: FpsCounter? = null

    fun setFpsCounter(counter: FpsCounter?) { fpsCounter = counter }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private val metrics = HudMetrics(context)

    // ---- Config (mapped from our KeyValueSet in applyConfig) ---------------
    private var showEngine = true
    private var showGpuModel = false
    private var showFrameRate = true
    private var showCpuUsage = true
    private var showGpuUsage = true
    private var showRamUsage = true
    private var showBatteryLevel = false
    private var showPowerDraw = true
    private var showBatteryRuntime = false
    private var showBatteryTemperature = false
    private var showClockTime = false
    private var showCpuTemperature = true
    private var showGpuTemperature = false
    private var tempDisplay = HudMetrics.TempDisplay.from(null)
    private var showFrameRateGraph = false
    private var showCpuUsageGraph = false
    private var showGpuUsageGraph = false
    private var backgroundOpacity = 0.72f
    private var colorIntensity = 1f
    private var outlineIntensity = 0.4f   // 0..1 (hudOutline 0..100 / 100); 0 = no border
    private var outlineFollowAccent = true   // outline colour: theme accent (true) or light grey (false)
    private var size = HudSize.MEDIUM

    private var isCompactMode = false
    private var appearance = appearanceFor(size)

    private var engineLabel = ""
    private var gpuModel = ""

    private var lastSnapshot: HudSnapshot? = null
    private var attachedMetricSignature: List<MetricSignature> = emptyList()

    // ---- Drag-to-move + tap-to-toggle -------------------------------------
    private var lastX = 0f
    private var lastY = 0f
    private var offsetX = 0f
    private var offsetY = 0f
    private var downTime = 0L
    private var moved = false
    private var onTapListener: Runnable? = null
    private var onMovedListener: BiConsumer<Float, Float>? = null
    private var onLockChangedListener: java.util.function.Consumer<Boolean>? = null

    fun setOnTapListener(r: Runnable?) { onTapListener = r }
    fun setOnMovedListener(l: BiConsumer<Float, Float>?) { onMovedListener = l }
    fun setOnLockChangedListener(l: java.util.function.Consumer<Boolean>?) { onLockChangedListener = l }

    // Shared lock / tap / drag behaviour (long-press toggles the position lock).
    private val lockController = com.winlator.star.widget.HudLockController(
        context, this,
        object : com.winlator.star.widget.HudLockController.Callbacks {
            override fun onTap() { onTapListener?.run() }
            override fun onMoved(x: Float, y: Float) { onMovedListener?.accept(x, y) }
            override fun onLockChanged(locked: Boolean) { onLockChangedListener?.accept(locked) }
        },
    )

    private val backgroundDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
    }

    private val stackedContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private val compactContainer = WrapLayout(context).apply {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    private val engineMetric = createMetricViews(MetricId.ENGINE, 0xFFFF50A0.toInt())
    private val gpuModelMetric = createMetricViews(MetricId.GPU_MODEL, 0xFFC8C8D2.toInt())
    private val fpsMetric = createMetricViews(
        id = MetricId.FPS,
        textColor = 0xFF4CAF50.toInt(),
        graphColor = 0xFF7CFF6B.toInt(),
        graphScaleMode = GraphScaleMode.FPS_DYNAMIC,
    )
    private val cpuMetric = createMetricViews(
        id = MetricId.CPU,
        textColor = 0xFF42A5F5.toInt(),
        graphColor = 0xFF42A5F5.toInt(),
        graphScaleMode = GraphScaleMode.PERCENT_100,
    )
    private val gpuMetric = createMetricViews(
        id = MetricId.GPU,
        textColor = 0xFFEF5350.toInt(),
        graphColor = 0xFFEF5350.toInt(),
        graphScaleMode = GraphScaleMode.PERCENT_100,
    )
    private val ramMetric = createMetricViews(MetricId.RAM, 0xFFFFEE58.toInt())
    private val batteryMetric = createMetricViews(MetricId.BATTERY, 0xFFFFFFFF.toInt())
    private val powerMetric = createMetricViews(MetricId.POWER, 0xFF4DD0E1.toInt())
    private val runtimeMetric = createMetricViews(MetricId.RUNTIME, 0xFFA5D6A7.toInt())
    private val clockMetric = createMetricViews(MetricId.CLOCK, 0xFFFFCC80.toInt())
    private val cpuTempMetric = createMetricViews(MetricId.CPU_TEMP, 0xFFBDBDBD.toInt())
    private val gpuTempMetric = createMetricViews(MetricId.GPU_TEMP, 0xFFBDBDBD.toInt())
    private val batteryTempMetric = createMetricViews(MetricId.BATTERY_TEMP, 0xFFBDBDBD.toInt())

    private val allMetrics = listOf(
        engineMetric,
        gpuModelMetric,
        fpsMetric,
        cpuMetric,
        gpuMetric,
        ramMetric,
        batteryMetric,
        powerMetric,
        runtimeMetric,
        clockMetric,
        cpuTempMetric,
        gpuTempMetric,
        batteryTempMetric,
    )

    private val allTextRows = allMetrics.flatMap { listOf(it.stackedText, it.compactText) }
    private val allGraphs = allMetrics.flatMap { listOfNotNull(it.stackedGraph, it.compactGraph) }

    init {
        background = backgroundDrawable
        addView(stackedContainer)
        addView(compactContainer)
        applyAppearance()
        applyLayoutMode()
        refreshVisibleMetrics()
    }

    // ---- Public surface (symmetric with PerfHudView) ----------------------
    fun setEngineLabel(s: String?) {
        engineLabel = s ?: ""
        postRefreshLabels()
    }

    fun setGpuModel(s: String?) {
        gpuModel = s ?: ""
        postRefreshLabels()
    }

    private fun postRefreshLabels() = post {
        updateMetricText(engineMetric, engineLabel.takeIf { it.isNotBlank() })
        updateMetricText(gpuModelMetric, gpuModel.takeIf { it.isNotBlank() })
        refreshVisibleMetrics()
    }

    fun isVertical(): Boolean = !isCompactMode

    /** true = vertical (stacked list with graphs), false = horizontal (compact wrap pill). */
    fun setVertical(vertical: Boolean) {
        val compact = !vertical
        if (isCompactMode == compact) return
        isCompactMode = compact
        applyLayoutMode()
        requestLayout()
    }

    fun applyConfig(configString: String?) {
        if (configString.isNullOrEmpty()) return
        val cfg = KeyValueSet(configString)

        showFrameRate = cfg.get("showFPS", "1") == "1"
        showCpuUsage = cfg.get("showCPUUsage", "1") == "1"
        showGpuUsage = cfg.get("showGPULoad", "1") == "1"
        showRamUsage = cfg.get("showRAM", "1") == "1"
        showPowerDraw = cfg.get("showPower", "1") == "1"
        showCpuTemperature = cfg.get("showTemp", "1") == "1"
        showBatteryTemperature = cfg.get("showBatteryTemp", "0") == "1"
        showEngine = cfg.get("showEngine", "1") == "1"
        showGpuModel = cfg.get("showGpuModel", "0") == "1"

        // Unit + danger-band settings, shared verbatim with the classic and GameHub overlays so
        // switching HUD style never silently switches your units or your thresholds.
        tempDisplay = HudMetrics.TempDisplay.from(cfg)

        // New keys for this style's extra metrics (wired into the config UI in P3).
        showGpuTemperature = cfg.get("showGpuTemp", "0") == "1"
        showBatteryLevel = cfg.get("showBattery", "0") == "1"
        showBatteryRuntime = cfg.get("showRuntime", "0") == "1"
        showClockTime = cfg.get("showClock", "0") == "1"
        showFrameRateGraph = cfg.get("showFPSGraph", "0") == "1"
        showCpuUsageGraph = cfg.get("showCPUGraph", "0") == "1"
        showGpuUsageGraph = cfg.get("showGPUGraph", "0") == "1"

        isCompactMode = cfg.get("hudMode", "horizontal") == "horizontal"

        colorIntensity = when (cfg.get("hudColor", "vivid")) {
            "soft" -> 0.72f
            "mid" -> 0.88f
            else -> 1.0f
        }
        outlineIntensity = parseHudOutline(cfg.get("hudOutline", "40")) / 100f
        outlineFollowAccent = cfg.get("hudOutlineAccent", "1") == "1"
        lockController.setLocked(cfg.get("hudLocked", "0") == "1")
        size = run {
            val scale = try {
                Integer.parseInt(cfg.get("hudScale", Container.DEFAULT_HUD_SCALE.toString()))
            } catch (e: Exception) { Container.DEFAULT_HUD_SCALE }
            when {
                scale < 85 -> HudSize.SMALL
                scale <= 110 -> HudSize.MEDIUM
                else -> HudSize.LARGE
            }
        }
        backgroundOpacity = try {
            (Integer.parseInt(cfg.get("hudOpacity", "72")) / 100f).coerceIn(0f, 1f)
        } catch (e: Exception) { 0.72f }

        applyAppearance()
        applyLayoutMode()
        lastSnapshot?.let(::applySnapshotText)
        refreshVisibleMetrics()
    }

    // ---- Lifecycle: self-refresh timer ------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdates()
    }

    override fun onDetachedFromWindow() {
        stopUpdates()
        super.onDetachedFromWindow()
    }

    private fun startUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            while (isActive) {
                val rawFps = fpsCounter?.currentFPS ?: fpsProvider()
                val currentFps = if (rawFps.isFinite()) rawFps.coerceAtLeast(0f) else 0f
                val snapshot = withContext(Dispatchers.IO) { collectSnapshot(currentFps) }
                renderSnapshot(snapshot)
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
    }

    // ---- Metric collection (delegated to HudMetrics) ----------------------
    private fun collectSnapshot(currentFps: Float): HudSnapshot {
        val cpu: Int? = metrics.cpuUsagePercent
        val gpu: Int? = metrics.gpuUsagePercent
        val battery = metrics.collectBattery()
        val cpuTemp: Int? = metrics.cpuTempC
        val gpuTemp: Int? = metrics.gpuTempC
        return HudSnapshot(
            fpsValue = currentFps,
            cpuValue = cpu?.toFloat(),
            gpuValue = gpu?.toFloat(),
            fps = String.format(Locale.US, "FPS %.1f", currentFps),
            cpu = cpu?.let { "CPU $it%" },
            gpu = gpu?.let { "GPU $it%" },
            ram = "RAM ${metrics.usedRamText}",
            battery = battery.percent?.let { "BAT $it%" },
            power = battery.watts.takeIf { it > 0f }?.let { String.format(Locale.US, "PWR %.1fW", it) },
            runtime = battery.runtimeText,
            batteryTemp = battery.tempC?.let { "BAT TEMP ${tempText(it)}" },
            clock = "TIME ${DateFormat.getTimeFormat(context).format(Date())}",
            cpuTemp = cpuTemp?.let { "CPU TEMP ${tempText(it)}" },
            gpuTemp = gpuTemp?.let { "GPU TEMP ${tempText(it)}" },
            cpuTempC = cpuTemp?.toFloat(),
            gpuTempC = gpuTemp?.toFloat(),
            batteryTempC = battery.tempC?.toFloat(),
        )
    }

    private fun tempText(celsius: Int): String =
        HudMetrics.formatTemp(celsius.toFloat(), tempDisplay, false)

    private fun thresholdsFor(sensor: HudMetrics.TempSensor): HudMetrics.Thresholds =
        metrics.resolveThresholds(sensor, tempDisplay)

    private fun renderSnapshot(snapshot: HudSnapshot) {
        lastSnapshot = snapshot
        recordGraphSamples(snapshot)
        applySnapshotText(snapshot)
        refreshVisibleMetrics()
    }

    private fun recordGraphSamples(snapshot: HudSnapshot) {
        fpsMetric.stackedGraph?.addSample(snapshot.fpsValue)
        fpsMetric.compactGraph?.addSample(snapshot.fpsValue)
        cpuMetric.stackedGraph?.addSample(snapshot.cpuValue)
        cpuMetric.compactGraph?.addSample(snapshot.cpuValue)
        gpuMetric.stackedGraph?.addSample(snapshot.gpuValue)
        gpuMetric.compactGraph?.addSample(snapshot.gpuValue)
    }

    private fun applySnapshotText(snapshot: HudSnapshot) {
        updateMetricText(engineMetric, engineLabel.takeIf { it.isNotBlank() })
        updateMetricText(gpuModelMetric, gpuModel.takeIf { it.isNotBlank() })
        updateMetricText(fpsMetric, snapshot.fps)
        updateMetricText(cpuMetric, snapshot.cpu)
        updateMetricText(gpuMetric, snapshot.gpu)
        updateMetricText(ramMetric, snapshot.ram)
        updateMetricText(batteryMetric, snapshot.battery)
        updateMetricText(powerMetric, snapshot.power)
        updateMetricText(runtimeMetric, snapshot.runtime)
        updateMetricText(batteryTempMetric, snapshot.batteryTemp)
        updateMetricText(clockMetric, snapshot.clock)
        updateMetricText(cpuTempMetric, snapshot.cpuTemp)
        updateMetricText(gpuTempMetric, snapshot.gpuTemp)
        applyTempBand(cpuTempMetric, snapshot.cpuTempC, HudMetrics.TempSensor.CPU)
        applyTempBand(gpuTempMetric, snapshot.gpuTempC, HudMetrics.TempSensor.GPU)
        applyTempBand(batteryTempMetric, snapshot.batteryTempC, HudMetrics.TempSensor.BATTERY)
    }

    /** Recolours a temperature row for its danger band, or restores its base colour when banding is off. */
    private fun applyTempBand(metric: MetricViews, celsius: Float?, sensor: HudMetrics.TempSensor) {
        val tint = if (celsius == null || !tempDisplay.colorBands) null
                   else HudMetrics.tempColor(celsius, thresholdsFor(sensor), tempDisplay, metric.baseTextColor)
        if (metric.tintOverride == tint) return   // colour only changes when the band does
        metric.tintOverride = tint
        applyMetricAppearance(metric)
    }

    private fun updateMetricText(metric: MetricViews, text: String?) {
        val safeText = text.orEmpty()
        metric.stackedText.text = safeText
        metric.compactText.text = safeText
    }

    private fun refreshVisibleMetrics() {
        val visibleMetrics = buildList {
            addMetricIfVisible(engineMetric, showEngine)
            addMetricIfVisible(gpuModelMetric, showGpuModel)
            addMetricIfVisible(fpsMetric, showFrameRate, showFrameRateGraph)
            addMetricIfVisible(cpuMetric, showCpuUsage, showCpuUsageGraph)
            addMetricIfVisible(gpuMetric, showGpuUsage, showGpuUsageGraph)
            addMetricIfVisible(ramMetric, showRamUsage)
            addMetricIfVisible(batteryMetric, showBatteryLevel)
            addMetricIfVisible(powerMetric, showPowerDraw)
            addMetricIfVisible(runtimeMetric, showBatteryRuntime)
            addMetricIfVisible(clockMetric, showClockTime)
            addMetricIfVisible(cpuTempMetric, showCpuTemperature)
            addMetricIfVisible(gpuTempMetric, showGpuTemperature)
            addMetricIfVisible(batteryTempMetric, showBatteryTemperature)
        }

        val signatures = visibleMetrics.map {
            MetricSignature(id = it.metric.id, showText = it.showText, showGraph = it.showGraph)
        }
        if (signatures != attachedMetricSignature) {
            rebuildVisibleMetrics(visibleMetrics)
            attachedMetricSignature = signatures
        }

        visibility = if (visibleMetrics.isEmpty()) GONE else VISIBLE
    }

    private fun MutableList<VisibleMetric>.addMetricIfVisible(
        metric: MetricViews,
        enabled: Boolean,
        showGraph: Boolean = false,
    ) {
        val hasText = metric.stackedText.text.isNotBlank()
        val shouldShowText = enabled && hasText
        val shouldShowGraph = showGraph && metric.supportsGraph

        metric.stackedText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.compactText.visibility = if (shouldShowText) VISIBLE else GONE
        metric.stackedGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE
        metric.compactGraph?.visibility = if (shouldShowGraph) VISIBLE else GONE

        if (shouldShowText || shouldShowGraph) {
            add(VisibleMetric(metric = metric, showText = shouldShowText, showGraph = shouldShowGraph))
        }
    }

    private fun rebuildVisibleMetrics(visibleMetrics: List<VisibleMetric>) {
        stackedContainer.removeAllViews()
        compactContainer.removeAllViews()

        visibleMetrics.forEachIndexed { index, visibleMetric ->
            stackedContainer.addView(
                visibleMetric.metric.stackedContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    if (index < visibleMetrics.lastIndex) bottomMargin = appearance.rowSpacingDp.dp
                },
            )
            compactContainer.addView(visibleMetric.metric.compactContainer)
        }
    }

    private fun applyLayoutMode() {
        stackedContainer.visibility = if (isCompactMode) GONE else VISIBLE
        compactContainer.visibility = if (isCompactMode) VISIBLE else GONE
    }

    private fun applyAppearance() {
        appearance = appearanceFor(size)
        val opacity = backgroundOpacity.coerceIn(0f, 1f)

        setPadding(
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
            appearance.containerHorizontalPaddingDp.dp,
            appearance.containerVerticalPaddingDp.dp,
        )

        backgroundDrawable.cornerRadius = appearance.cornerRadiusDp.dp.toFloat()
        backgroundDrawable.setColor(Color.argb((opacity * 255f).roundToInt(), 0, 0, 0))
        // HUD outline = an accent border around the box (game-card style), width from the outline
        // slider. Accent = the live theme primary; width 0 (slider at 0) = no border.
        backgroundDrawable.setStroke(
            (outlineIntensity * OUTLINE_BORDER_MAX_DP * resources.displayMetrics.density).roundToInt(),
            if (outlineFollowAccent) AppThemeState.getCurrentAccentArgb() else Color.rgb(200, 200, 200),
        )

        compactContainer.horizontalSpacing = appearance.columnSpacingDp.dp
        compactContainer.verticalSpacing = appearance.rowSpacingDp.dp

        allTextRows.forEach { textView ->
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, appearance.textSizeSp)
            textView.maxLines = 1
            textView.ellipsize = TextUtils.TruncateAt.END
            // Outline is now the accent box border, not a text shadow.
            textView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        allMetrics.forEach(::applyMetricAppearance)
        allGraphs.forEach { it.applyAppearance(appearance) }

        attachedMetricSignature = emptyList()
        requestLayout()
    }

    private fun applyMetricAppearance(metric: MetricViews) {
        val textColor = blendMetricColor(metric.tintOverride ?: metric.baseTextColor)
        metric.stackedText.setTextColor(textColor)
        metric.compactText.setTextColor(textColor)
        metric.stackedText.setPadding(0, appearance.rowVerticalPaddingDp.dp, 0, 0)
        metric.compactText.setPadding(0, 0, 0, 0)
        metric.stackedGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor))
        metric.compactGraph?.setLineColor(blendMetricColor(metric.baseGraphColor ?: metric.baseTextColor))

        metric.stackedGraph?.let { graph ->
            (graph.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = appearance.stackedGraphWidthDp.dp
                params.height = appearance.stackedGraphHeightDp.dp
                params.topMargin = appearance.stackedGraphTopMarginDp.dp
                params.bottomMargin = appearance.stackedGraphBottomMarginDp.dp
                graph.layoutParams = params
            }
        }
        metric.compactGraph?.let { graph ->
            (graph.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                params.width = appearance.compactGraphWidthDp.dp
                params.height = appearance.compactGraphHeightDp.dp
                params.marginStart = appearance.compactGraphStartMarginDp.dp
                graph.layoutParams = params
            }
        }
    }

    private fun blendMetricColor(color: Int): Int {
        val intensity = colorIntensity.coerceIn(0f, 1f)
        if (intensity >= 0.999f) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= intensity
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun createMetricViews(
        id: MetricId,
        textColor: Int,
        graphColor: Int? = null,
        graphScaleMode: GraphScaleMode? = null,
    ): MetricViews {
        val stackedText = createTextView(textColor)
        val compactText = createTextView(textColor)
        val stackedGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else null
        val compactGraph = if (graphColor != null && graphScaleMode != null) {
            MetricGraphView(context, graphColor, graphScaleMode)
        } else null

        val stackedContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                stackedText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            stackedGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(appearance.stackedGraphWidthDp.dp, appearance.stackedGraphHeightDp.dp),
                )
                it.visibility = GONE
            }
        }

        val compactContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                compactText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            compactGraph?.let {
                addView(
                    it,
                    LinearLayout.LayoutParams(appearance.compactGraphWidthDp.dp, appearance.compactGraphHeightDp.dp),
                )
                it.visibility = GONE
            }
        }

        return MetricViews(
            id = id,
            supportsGraph = stackedGraph != null && compactGraph != null,
            baseTextColor = textColor,
            baseGraphColor = graphColor,
            stackedText = stackedText,
            compactText = compactText,
            stackedContainer = stackedContainer,
            compactContainer = compactContainer,
            stackedGraph = stackedGraph,
            compactGraph = compactGraph,
        )
    }

    private fun createTextView(color: Int): TextView = TextView(context).apply {
        setTextColor(color)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private fun appearanceFor(s: HudSize): HudAppearance = when (s) {
        HudSize.SMALL -> HudAppearance(
            textSizeSp = 10f, containerHorizontalPaddingDp = 8, containerVerticalPaddingDp = 6,
            rowVerticalPaddingDp = 1, rowSpacingDp = 4, columnSpacingDp = 8, cornerRadiusDp = 8,
            strokeWidthDp = 1, stackedGraphWidthDp = 60, stackedGraphHeightDp = 12,
            compactGraphWidthDp = 38, compactGraphHeightDp = 10, stackedGraphTopMarginDp = 1,
            stackedGraphBottomMarginDp = 2, compactGraphStartMarginDp = 4,
        )
        HudSize.MEDIUM -> HudAppearance(
            textSizeSp = 11f, containerHorizontalPaddingDp = 10, containerVerticalPaddingDp = 8,
            rowVerticalPaddingDp = 2, rowSpacingDp = 6, columnSpacingDp = 12, cornerRadiusDp = 10,
            strokeWidthDp = 1, stackedGraphWidthDp = 72, stackedGraphHeightDp = 16,
            compactGraphWidthDp = 44, compactGraphHeightDp = 12, stackedGraphTopMarginDp = 1,
            stackedGraphBottomMarginDp = 3, compactGraphStartMarginDp = 6,
        )
        HudSize.LARGE -> HudAppearance(
            textSizeSp = 13f, containerHorizontalPaddingDp = 12, containerVerticalPaddingDp = 10,
            rowVerticalPaddingDp = 3, rowSpacingDp = 8, columnSpacingDp = 14, cornerRadiusDp = 12,
            strokeWidthDp = 1, stackedGraphWidthDp = 88, stackedGraphHeightDp = 20,
            compactGraphWidthDp = 56, compactGraphHeightDp = 16, stackedGraphTopMarginDp = 2,
            stackedGraphBottomMarginDp = 4, compactGraphStartMarginDp = 8,
        )
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private val Float.dpF: Float
        get() = this * resources.displayMetrics.density

    // ---- Touch: long-press locks, tap toggles orientation, drag moves -----
    override fun onTouchEvent(event: MotionEvent): Boolean = lockController.onTouchEvent(event)

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        lockController.drawBadge(canvas)
    }

    // ---- Views ------------------------------------------------------------
    private data class MetricViews(
        val id: MetricId,
        val supportsGraph: Boolean,
        val baseTextColor: Int,
        val baseGraphColor: Int?,
        // Set per-update by the temperature rows to colour by danger band; null keeps baseTextColor.
        // Still goes through blendMetricColor, so a banded row honours HUD opacity/theme like any other.
        var tintOverride: Int? = null,
        val stackedText: TextView,
        val compactText: TextView,
        val stackedContainer: LinearLayout,
        val compactContainer: LinearLayout,
        val stackedGraph: MetricGraphView? = null,
        val compactGraph: MetricGraphView? = null,
    )

    private data class VisibleMetric(
        val metric: MetricViews,
        val showText: Boolean,
        val showGraph: Boolean,
    )

    private inner class MetricGraphView(
        context: Context,
        lineColor: Int,
        private val scaleMode: GraphScaleMode,
    ) : View(context) {
        private val samples = ArrayDeque<Float>()
        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(102, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
            style = Paint.Style.STROKE
            strokeWidth = 2.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f.dpF
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()

        fun setLineColor(color: Int) {
            linePaint.color = color
            glowPaint.color = Color.argb(102, Color.red(color), Color.green(color), Color.blue(color))
            invalidate()
        }

        fun applyAppearance(appearance: HudAppearance) {
            linePaint.strokeWidth = when {
                appearance.textSizeSp <= 10.5f -> 1.2f.dpF
                appearance.textSizeSp <= 12f -> 1.5f.dpF
                else -> 1.8f.dpF
            }
            glowPaint.strokeWidth = linePaint.strokeWidth * 2f
            invalidate()
        }

        fun addSample(value: Float?) {
            val sample = value?.takeIf { it.isFinite() && it >= 0f } ?: Float.NaN
            if (samples.size >= GRAPH_SAMPLE_COUNT) samples.removeFirst()
            samples.addLast(sample)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0 || samples.isEmpty()) return

            val values = samples.toList()
            val validValues = values.filter { it.isFinite() }
            if (validValues.size < 2) return

            val chartWidth = width.toFloat()
            val chartHeight = height.toFloat()
            val maxValue = when (scaleMode) {
                GraphScaleMode.FPS_DYNAMIC ->
                    max(GRAPH_FPS_MIN_SCALE, (validValues.maxOrNull() ?: GRAPH_FPS_MIN_SCALE) * 1.05f)
                GraphScaleMode.PERCENT_100 -> 100f
            }
            val xStep = if (values.size > 1) chartWidth / (values.size - 1) else chartWidth

            path.reset()
            var hasActiveSegment = false
            values.forEachIndexed { index, value ->
                if (!value.isFinite()) {
                    hasActiveSegment = false
                    return@forEachIndexed
                }
                val px = index * xStep
                val normalized = (value / maxValue).coerceIn(0f, 1f)
                val py = chartHeight - (normalized * chartHeight)
                if (!hasActiveSegment) {
                    path.moveTo(px, py)
                    hasActiveSegment = true
                } else {
                    path.lineTo(px, py)
                }
            }

            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private inner class WrapLayout(context: Context) : ViewGroup(context) {
        var horizontalSpacing: Int = 0
            set(value) { field = value; requestLayout() }
        var verticalSpacing: Int = 0
            set(value) { field = value; requestLayout() }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val maxContentWidth = when (widthMode) {
                MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
                else -> (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
            }

            var lineWidth = 0
            var lineHeight = 0
            var maxLineWidthUsed = 0
            var totalHeight = paddingTop + paddingBottom
            var visibleChildCount = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                measureChild(child, widthMeasureSpec, heightMeasureSpec)
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedWidth = if (lineWidth == 0) childWidth else lineWidth + horizontalSpacing + childWidth
                if (lineWidth > 0 && proposedWidth > maxContentWidth) {
                    maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                    totalHeight += lineHeight + verticalSpacing
                    lineWidth = childWidth
                    lineHeight = childHeight
                } else {
                    lineWidth = proposedWidth
                    lineHeight = max(lineHeight, childHeight)
                }
                visibleChildCount++
            }

            if (visibleChildCount > 0) {
                maxLineWidthUsed = max(maxLineWidthUsed, lineWidth)
                totalHeight += lineHeight
            }

            setMeasuredDimension(
                resolveSize(maxLineWidthUsed + paddingLeft + paddingRight, widthMeasureSpec),
                resolveSize(totalHeight, heightMeasureSpec),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val maxContentWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
            var x = paddingLeft
            var y = paddingTop
            var lineHeight = 0

            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child.visibility == GONE) continue
                val childWidth = child.measuredWidth
                val childHeight = child.measuredHeight
                val proposedRight = if (x == paddingLeft) x + childWidth else x + horizontalSpacing + childWidth
                if (x != paddingLeft && proposedRight - paddingLeft > maxContentWidth) {
                    x = paddingLeft
                    y += lineHeight + verticalSpacing
                    lineHeight = 0
                }
                if (x != paddingLeft) x += horizontalSpacing
                child.layout(x, y, x + childWidth, y + childHeight)
                x += childWidth
                lineHeight = max(lineHeight, childHeight)
            }
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MS = 1_000L
        const val OUTLINE_BORDER_MAX_DP = 4f
        const val GRAPH_SAMPLE_COUNT = 30
        const val GRAPH_FPS_MIN_SCALE = 60f
        // Outline radius at intensity 1.0 (px); scaled down linearly by the hudOutline slider.
        const val MAX_HUD_TEXT_SHADOW_RADIUS_PX = 5f
        val HUD_TEXT_SHADOW_COLOR: Int = Color.BLACK   // fully opaque so the outline is actually visible
    }
}

/**
 * hudOutline is now a 0..100 intensity. Legacy string values map for backward compatibility
 * ("off"→0, "soft"→40, "strong"→70); a numeric string parses directly.
 */
internal fun parseHudOutline(v: String?): Int = when (v?.trim()?.lowercase()) {
    null -> 40
    "off" -> 0
    "soft" -> 40
    "strong" -> 70
    else -> v.trim().toIntOrNull()?.coerceIn(0, 100) ?: 40
}
