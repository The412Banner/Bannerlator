package com.winlator.star.widget.fusionhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import com.winlator.star.container.Container
import com.winlator.star.core.KeyValueSet
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.widget.FpsCounter
import com.winlator.star.widget.HudLockController
import com.winlator.star.widget.HudMetrics
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Consumer
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
 * Fusion HUD — the 4th selectable in-game overlay. One color-coded visual language rendered at four
 * SIZE modes (Full / Tiles / Pill / Minimal), all driven by the shared [FpsCounter] (incl. the new
 * percentile lows) + a single cached [HudMetrics.Snapshot]. Pure Canvas drawing.
 *
 * Threading mirrors the GameNative [com.winlator.star.widget.perfhud.PerformanceHudView]: the view
 * self-refreshes on its OWN ~1 s Main-dispatcher coroutine (never on a present tick), collecting the
 * snapshot on Dispatchers.IO so no sysfs read touches the UI thread and nothing runs on the epoll /
 * render path. Only [FpsCounter.tick] runs on the epoll thread (elsewhere).
 *
 * Gestures go through [HudLockController]: long-press toggles the position lock (with a lock/unlock
 * badge fade), a tap cycles the size (Full→Tiles→Pill→Minimal→Full), and a drag repositions — tap +
 * drag are frozen while locked.
 */
class FusionHudView(
    context: Context,
    private val fpsProvider: () -> Float,
) : View(context) {

    /** Java-friendly entry point, symmetric with the other overlays. */
    constructor(context: Context) : this(context, { 0f })

    private var fpsCounter: FpsCounter? = null
    fun setFpsCounter(counter: FpsCounter?) { fpsCounter = counter }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private val metrics = HudMetrics(context)

    private val density = resources.displayMetrics.density

    // ---- Config -----------------------------------------------------------
    private var size = FusionSize.FULL
    private var scale = Container.DEFAULT_HUD_SCALE / 100f
    private var bgOpacity = 0.8f
    private var outlineIntensity = 0.4f
    private var outlineFollowAccent = true
    private var colorIntensity = 1f
    private var tempDisplay = HudMetrics.TempDisplay.from(null)

    private var showFPS = true
    private var showEngine = true
    private var showGpuModel = true
    private var showCPU = true
    private var showGPU = true
    private var showCpuTemp = true
    private var showGpuTemp = false
    private var showVram = true
    private var showRAM = true
    private var showBattery = true
    private var showPower = true
    private var showBatteryTemp = false
    private var showGraph = false
    private var showLow001 = true
    private var fpsDecimal = true
    private var showClockTime = true   // subtle corner/footer clock, available in EVERY size
    // Mega-only rows
    private var showPerCore = true
    private var showSwap = true
    private var showNet = true
    private var showResolution = true
    private var showProton = true
    private var showWrapper = true
    private var showDxVer = true
    private var showSession = true

    private var engineLabel = ""
    private var gpuModel = ""
    // Stack-layer version strings (Mega bottom band + DX version on the engine row); fed by the host.
    private var wineVersion = ""       // "Proton 10.0-4"
    private var graphicsWrapper = ""   // graphics-driver wrapper package, e.g. "GameNative", "bcn_layer 20260719"
    private var dxvkVersion = ""       // DXVK version, appended to a DXVK engine row
    private var vkd3dVersion = ""      // VKD3D version, appended to a VKD3D engine row

    // ---- Live data (updated on the refresh coroutine, read on draw) --------
    private var snap: HudMetrics.Snapshot? = null
    private var fpsNow = 0f
    private var fpsAvg = 0f
    private var lows: FpsCounter.FrametimeLows = FpsCounter.FrametimeLows.EMPTY
    private val graphSamples = ArrayDeque<Float>()

    // ---- Colors (mockup) --------------------------------------------------
    private val colGpu = 0xFF5EE08A.toInt()
    private val colCpu = 0xFF58A6FF.toInt()
    private val colVram = 0xFFC98BFF.toInt()
    private val colRam = 0xFFFF7BC0.toInt()
    private val colBat = 0xFFFFAB5E.toInt()
    private val colFps = 0xFFFF6B6B.toInt()
    private val colGraph = 0xFF5EE08A.toInt()
    private val colValue = 0xFFF2F5F9.toInt()
    private val colDim = 0xFF9AA4B2.toInt()
    private val colLo = 0xFFE4E8EE.toInt()

    // ---- Paints -----------------------------------------------------------
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = colGraph
    }

    // ---- Built geometry ---------------------------------------------------
    private class Glyph(val x: Float, val baseline: Float, val text: String, val color: Int, val sizePx: Float)
    private class Span(val text: String, val color: Int, val sizePx: Float)
    /**
     * A "label + value-run" row for the aligned grid sizes (Full / Mega). When [inline] is true the row
     * opts OUT of the shared label column — its value run is placed directly after the label — so a long
     * label (the graphics-wrapper on the FPS row) can't widen the column and shove every value right.
     */
    private class HudRow(val label: Span, val vals: List<Span>, val inline: Boolean = false)
    private val glyphs = ArrayList<Glyph>()
    private val tileRects = ArrayList<RectF>()
    private var pillBorder: RectF? = null
    private var graphRect: RectF? = null
    private var contentW = 0f
    private var contentH = 0f

    // ---- Listeners --------------------------------------------------------
    private var onMovedListener: BiConsumer<Float, Float>? = null
    private var onSizeCycledListener: Consumer<String>? = null
    private var onLockChangedListener: Consumer<Boolean>? = null
    fun setOnMovedListener(l: BiConsumer<Float, Float>?) { onMovedListener = l }
    fun setOnSizeCycledListener(l: Consumer<String>?) { onSizeCycledListener = l }
    fun setOnLockChangedListener(l: Consumer<Boolean>?) { onLockChangedListener = l }

    private val lockController = HudLockController(context, this, object : HudLockController.Callbacks {
        override fun onTap() { cycleSize() }
        override fun onMoved(x: Float, y: Float) { onMovedListener?.accept(x, y) }
        override fun onLockChanged(locked: Boolean) { onLockChangedListener?.accept(locked) }
    })

    // ---- Public surface (symmetric with the other overlays) ---------------
    fun setEngineLabel(s: String?) { engineLabel = s ?: ""; post { rebuildAndInvalidate() } }
    fun setGpuModel(s: String?) { gpuModel = s ?: ""; post { rebuildAndInvalidate() } }
    fun setWineVersion(s: String?) { wineVersion = s ?: ""; post { rebuildAndInvalidate() } }
    fun setGraphicsWrapper(s: String?) { graphicsWrapper = s ?: ""; post { rebuildAndInvalidate() } }
    fun setDxWrapper(dxvk: String?, vkd3d: String?) {
        dxvkVersion = dxvk ?: ""; vkd3dVersion = vkd3d ?: ""; post { rebuildAndInvalidate() }
    }

    /** Fusion has no orientation (tap cycles size instead); kept for a symmetric host surface. */
    fun setVertical(vertical: Boolean) { /* no-op */ }
    fun isVertical(): Boolean = false

    private fun cycleSize() {
        size = size.next()
        onSizeCycledListener?.accept(size.token)
        rebuildAndInvalidate()
    }

    fun applyConfig(configString: String?) {
        if (configString.isNullOrEmpty()) return
        val cfg = KeyValueSet(configString)

        size = FusionSize.from(cfg.get("hudSize", "pill"))
        showFPS = cfg.get("showFPS", "1") == "1"
        showEngine = cfg.get("showEngine", "1") == "1"
        showGpuModel = cfg.get("showGpuModel", "1") == "1"
        showCPU = cfg.get("showCPUUsage", cfg.get("showCPULoad", "1")) == "1"
        showGPU = cfg.get("showGPULoad", "1") == "1"
        showCpuTemp = cfg.get("showTemp", "1") == "1"
        showGpuTemp = cfg.get("showGpuTemp", "0") == "1"
        showVram = cfg.get("showVram", "1") == "1"
        showRAM = cfg.get("showRAM", "1") == "1"
        showBattery = cfg.get("showBattery", "1") == "1"
        showPower = cfg.get("showPower", "1") == "1"
        showBatteryTemp = cfg.get("showBatteryTemp", "0") == "1"
        showGraph = cfg.get("showFPSGraph", "0") == "1"
        showLow001 = cfg.get("showLow001", "1") == "1"
        fpsDecimal = cfg.get("fpsDecimal", "1") == "1"
        showClockTime = cfg.get("showClock", "1") == "1"   // Fusion clock defaults ON
        showPerCore = cfg.get("showPerCore", "1") == "1"
        showSwap = cfg.get("showSwap", "1") == "1"
        showNet = cfg.get("showNet", "1") == "1"
        showResolution = cfg.get("showResolution", "1") == "1"
        showProton = cfg.get("showProton", "1") == "1"
        showWrapper = cfg.get("showWrapper", "1") == "1"
        showDxVer = cfg.get("showDxVer", "1") == "1"
        showSession = cfg.get("showSession", "1") == "1"
        tempDisplay = HudMetrics.TempDisplay.from(cfg)
        lockController.setLocked(cfg.get("hudLocked", "0") == "1")

        colorIntensity = when (cfg.get("hudColor", "vivid")) {
            "soft" -> 0.72f; "mid" -> 0.88f; else -> 1.0f
        }
        outlineIntensity = (parseOutline(cfg.get("hudOutline", "40")) / 100f).coerceIn(0f, 1f)
        outlineFollowAccent = cfg.get("hudOutlineAccent", "1") == "1"
        scale = (parseIntOr(cfg.get("hudScale", "100"), 100).coerceIn(50, 200)) / 100f
        bgOpacity = (parseIntOr(cfg.get("hudOpacity", "80"), 80).coerceIn(0, 100)) / 100f

        rebuildAndInvalidate()
    }

    // ---- Lifecycle: self-refresh ------------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startUpdates()
    }

    override fun onDetachedFromWindow() {
        updateJob?.cancel(); updateJob = null
        super.onDetachedFromWindow()
    }

    private fun startUpdates() {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            while (isActive) {
                val rawFps = fpsCounter?.currentFPS ?: fpsProvider()
                val fps = if (rawFps.isFinite()) rawFps.coerceAtLeast(0f) else 0f
                val counter = fpsCounter
                val collected = withContext(Dispatchers.IO) {
                    Triple(
                        metrics.snapshot(),
                        counter?.frametimeLows ?: FpsCounter.FrametimeLows.EMPTY,
                        counter?.avgFPS ?: 0f,
                    )
                }
                snap = collected.first
                lows = collected.second
                fpsAvg = collected.third
                fpsNow = fps
                appendGraphSample(1000f / max(fps, 1f))
                rebuildAndInvalidate()
                delay(1000)
            }
        }
    }

    private fun appendGraphSample(ms: Float) {
        if (graphSamples.size >= GRAPH_CAP) graphSamples.removeFirst()
        graphSamples.addLast(if (ms.isFinite() && ms > 0f) ms else Float.NaN)
    }

    private fun rebuildAndInvalidate() {
        rebuild()
        requestLayout()
        invalidate()
    }

    // ---- Text helpers -----------------------------------------------------
    private fun blend(color: Int): Int {
        val i = colorIntensity.coerceIn(0f, 1f)
        if (i >= 0.999f) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= i
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun sp(v: Float) = v * density * scale
    private fun measure(text: String, sizePx: Float): Float { measurePaint.textSize = sizePx; return measurePaint.measureText(text) }
    private fun lineH(sizePx: Float): Float { measurePaint.textSize = sizePx; val fm = measurePaint.fontMetrics; return fm.descent - fm.ascent }
    private fun ascent(sizePx: Float): Float { measurePaint.textSize = sizePx; return measurePaint.fontMetrics.ascent }

    private fun runWidth(spans: List<Span>): Float {
        var w = 0f
        for (s in spans) w += measure(s.text, s.sizePx)
        return w
    }

    private fun placeRun(startX: Float, baseline: Float, spans: List<Span>): Float {
        var x = startX
        for (s in spans) {
            glyphs.add(Glyph(x, baseline, s.text, blend(s.color), s.sizePx))
            x += measure(s.text, s.sizePx)
        }
        return x
    }

    private fun fpsText(v: Float): String =
        if (fpsDecimal) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun lowText(v: Float): String? = if (v <= 0f) null else fmt1(v)

    // number (white, or "—" muted when null) + unit (muted, smaller)
    private fun numUnit(num: String?, unit: String, numPx: Float, unitPx: Float): List<Span> =
        if (num == null) listOf(Span("—", colDim, numPx), Span(unit, colDim, unitPx))
        else listOf(Span(num, colValue, numPx), Span(unit, colDim, unitPx))

    // "0.2GiB"/"75%"/"1938MHz" → white number + muted suffix
    private fun valueUnit(text: String, numPx: Float, unitPx: Float): List<Span> {
        val i = text.indexOfFirst { !(it.isDigit() || it == '.' || it == '-') }
        return if (i <= 0) listOf(Span(text, colValue, numPx))
        else listOf(Span(text.substring(0, i), colValue, numPx), Span(text.substring(i), colDim, unitPx))
    }

    private fun tempSpans(c: Int?, sensor: HudMetrics.TempSensor, numPx: Float, unitPx: Float): List<Span> {
        if (c == null) return emptyList()
        val txt = HudMetrics.formatTemp(c.toFloat(), tempDisplay, false) // "81°C"
        val col = HudMetrics.tempColor(
            c.toFloat(), metrics.resolveThresholds(sensor, tempDisplay), tempDisplay, colValue)
        val i = txt.indexOf('°')
        return if (i <= 0) listOf(Span(txt, col, numPx))
        else listOf(Span(txt.substring(0, i), col, numPx), Span(txt.substring(i), colDim, unitPx))
    }

    private fun gap(unitPx: Float) = Span("  ", colDim, unitPx)

    // ---- Layout builders --------------------------------------------------
    private fun rebuild() {
        glyphs.clear(); tileRects.clear(); pillBorder = null; graphRect = null
        val s = snap
        if (s == null) { contentW = 0f; contentH = 0f; return }
        when (size) {
            FusionSize.FULL -> buildFull(s)
            FusionSize.TILES -> buildTiles(s)
            FusionSize.PILL -> buildPill(s)
            FusionSize.MINIMAL -> buildMinimal(s)
            FusionSize.MEGA -> buildMega(s)
        }
    }

    // ---- Shared small-info helpers ----------------------------------------
    private fun clockTimeString(): String = DateFormat.getTimeFormat(context).format(Date())

    private fun elapsedString(): String {
        val sec = (fpsCounter?.sessionLengthSec ?: 0f).toInt().coerceAtLeast(0)
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    /**
     * The FPS row's API/engine label — the DX→Vulkan translator ([engineLabel]: "DXVK"/"VKD3D"/"Zink"),
     * gated by the "Engine" chip. This is short and shows in EVERY size. Falls back to a bare "FPS" when
     * the toggle is off or no engine label is set. (The graphics WRAPPER is separate — Mega-only, drawn
     * below the frametime graph — not on the FPS row.)
     */
    private fun apiLabel(): String =
        if (showEngine && engineLabel.isNotBlank()) engineLabel else "FPS"

    /** The DX-wrapper version WITH its engine name (e.g. "DXVK 2.4.1", "VKD3D 2.14"), or "" if none.
     *  Used for the Mega stack line below the graph. */
    private fun dxVersionLabeled(): String = when {
        engineLabel.contains("VKD3D", true) && vkd3dVersion.isNotBlank() -> "VKD3D $vkd3dVersion"
        engineLabel.contains("DXVK", true) && dxvkVersion.isNotBlank() -> "DXVK $dxvkVersion"
        else -> ""
    }

    /** The DX-wrapper version for the current engine, or "" (used for the Tiles "API" tile sub-line). */
    private fun dxVersion(): String = when {
        engineLabel.contains("VKD3D", true) -> vkd3dVersion
        engineLabel.contains("DXVK", true) -> dxvkVersion
        else -> ""
    }

    /** Resolution + refresh, e.g. "1280x720 @ 120Hz". Read from the current display (Main thread). */
    private fun resolutionString(): String {
        val dm = resources.displayMetrics
        val hz = (display?.refreshRate ?: 0f)
        val base = "${dm.widthPixels}x${dm.heightPixels}"
        return if (hz >= 1f) "$base @ ${hz.roundToInt()}Hz" else base
    }

    /** A subtle, low-emphasis time-of-day tucked into the bottom-right of any size. Grows contentH slightly. */
    private fun addSubtleClock(pad: Float, centerX: Float? = null) {
        if (!showClockTime) return
        val px = sp(9.5f)
        val t = clockTimeString()
        val w = measure(t, px)
        val footerTop = contentH - pad            // reuse the bottom padding band as the footer line
        val baseline = footerTop - ascent(px)
        // Default: right-aligned footer. When [centerX] is given (the pill) the clock centres on it —
        // placed under the FPS / slightly left of the pill's centre — clamped inside the panel.
        val x = if (centerX != null) (centerX - w / 2f).coerceIn(pad, (contentW - pad - w).coerceAtLeast(pad))
                else (contentW - pad - w).coerceAtLeast(pad)
        glyphs.add(Glyph(x, baseline, t, blend(colDim), px))
        contentH = footerTop + lineH(px) + pad * 0.5f
        contentW = max(contentW, pad + w + pad)
    }

    private fun buildFull(s: HudMetrics.Snapshot) {
        val rowPx = sp(12f); val unitPx = rowPx * 0.62f
        val pad = sp(10f); val lineGap = sp(4f); val lvGap = sp(8f)
        val rows = ArrayList<HudRow>()

        if (showGpuModel && gpuModel.isNotBlank())
            rows.add(HudRow(Span("GPU", colGpu, rowPx), listOf(Span(gpuModel, colValue, rowPx))))
        if (showGPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.gpuPercent?.toString(), "%", rowPx, unitPx)
            if (showGpuTemp) tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.gpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            rows.add(HudRow(Span("GPU", colGpu, rowPx), v))
        }
        if (showCPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.cpuPercent?.toString(), "%", rowPx, unitPx)
            if (showCpuTemp) tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.cpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            rows.add(HudRow(Span("CPU", colCpu, rowPx), v))
        }
        if (showVram && s.vramText() != null)
            rows.add(HudRow(Span("VRAM", colVram, rowPx), valueUnit(s.vramText()!!, rowPx, unitPx)))
        if (showRAM) {
            val v = ArrayList<Span>()
            v += valueUnit(s.ramUsedText(), rowPx, unitPx)
            v += gap(unitPx); v += numUnit(s.ramPercent.roundToInt().toString(), "%", rowPx, unitPx)
            rows.add(HudRow(Span("RAM", colRam, rowPx), v))
        }
        if (showBattery || showPower || showBatteryTemp) {
            val v = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { v += numUnit(s.battery.percent.toString(), "%", rowPx, unitPx); any = true }
            if (showBatteryTemp) tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { if (any) v += gap(unitPx); v += it; any = true } }
            if (showPower && s.battery.watts > 0f) { if (any) v += gap(unitPx); v += numUnit(fmt1(s.battery.watts), "W", rowPx, unitPx); any = true }
            if (any) rows.add(HudRow(Span("BAT", colBat, rowPx), v))
        }
        if (showFPS) {
            val v = ArrayList<Span>()
            v += numUnit(fpsText(fpsNow), "FPS", rowPx, unitPx)
            v += gap(unitPx); v += numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", rowPx, unitPx)
            // Short API/engine label ("DXVK"/"VKD3D"/"Zink", else "FPS") — a normal aligned row.
            rows.add(HudRow(Span(apiLabel(), colFps, rowPx), v))
            rows.add(HudRow(Span("AVG", colLo, rowPx), numUnit(fmt1(fpsAvg), "FPS", rowPx, unitPx)))
            rows.add(HudRow(Span("1%", colLo, rowPx), numUnit(lowText(lows.low1Fps), "FPS", rowPx, unitPx)))
            rows.add(HudRow(Span("0.1%", colLo, rowPx), numUnit(lowText(lows.low01Fps), "FPS", rowPx, unitPx)))
            if (showLow001)
                rows.add(HudRow(Span("0.01%", colLo, rowPx), numUnit(lowText(lows.low001Fps), "FPS", rowPx, unitPx)))
        }

        var labelCol = 0f
        for (r in rows) if (!r.inline) labelCol = max(labelCol, measure(r.label.text, rowPx))
        val h = lineH(rowPx); val asc = ascent(rowPx)
        var y = pad; var maxRight = pad
        for (r in rows) {
            val baseline = y - asc
            placeRun(pad, baseline, listOf(r.label))
            val valX = if (r.inline) pad + measure(r.label.text, rowPx) + lvGap
                       else pad + labelCol + lvGap
            val end = placeRun(valX, baseline, r.vals)
            maxRight = max(maxRight, end)
            y += h + lineGap
        }

        if (showFPS && showGraph) {
            // Frametime min/max line + green graph. "Frametime" is far wider than the short-label grid
            // column, so place the stat AFTER the ACTUAL label width (not labelCol) — otherwise the two
            // overlap and render as "Frametimemin:.. max:..".
            val ftPx = unitPx * 1.15f
            val baseline = y - asc
            placeRun(pad, baseline, listOf(Span("Frametime", colFps, ftPx)))
            val stat = "min:${fmt1(lows.minMs)} max:${fmt1(lows.maxMs)}"
            val statX = pad + max(labelCol, measure("Frametime", ftPx)) + lvGap
            val end = placeRun(statX, baseline, listOf(Span(stat, colDim, unitPx)))
            maxRight = max(maxRight, end)
            y += h + lineGap
            val gh = sp(22f)
            val right = max(maxRight, pad + sp(160f))
            graphRect = RectF(pad, y, right, y + gh)
            maxRight = max(maxRight, right)
            y += gh
        }

        contentW = maxRight + pad
        contentH = y + pad
        addSubtleClock(pad)
    }

    private fun buildTiles(s: HudMetrics.Snapshot) {
        val keyPx = sp(10f); val valPx = sp(18f); val subPx = sp(10f); val unitPx = sp(11f)
        val pad = sp(9f); val innerPad = sp(8f); val tileGap = sp(6f); val lineGap = sp(4f)

        class Tile(val key: String, val keyColor: Int, val value: List<Span>, val sub: String?, val wide: Boolean)
        val tiles = ArrayList<Tile>()

        if (showFPS) tiles.add(Tile("FPS", colFps, listOf(Span(fpsText(fpsNow), colValue, valPx)),
            "${fmt1(fpsAvg)} avg · ${lowText(lows.low1Fps) ?: "—"} 1%", false))
        if (showFPS) tiles.add(Tile("FRAME", colDim, numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", valPx, unitPx),
            "${fmt1(lows.minMs)} – ${fmt1(lows.maxMs)}", false))
        if (showGPU) tiles.add(Tile("GPU", colGpu, numUnit(s.gpuPercent?.toString(), "%", valPx, unitPx),
            if (showGpuTemp && s.gpuTempC != null) "${s.gpuTempC}°C"
            else s.gpuClockMhz?.let { "${it}MHz" }, false))
        if (showCPU) tiles.add(Tile("CPU", colCpu, numUnit(s.cpuPercent?.toString(), "%", valPx, unitPx),
            listOfNotNull(if (showCpuTemp) s.cpuTempC?.let { "${it}°C" } else null,
                s.cpuClockMhz?.toString()).joinToString(" · ").ifBlank { null }, false))
        if (showVram && s.vramText() != null) tiles.add(Tile("VRAM", colVram, valueUnit(s.vramText()!!, valPx, unitPx), null, false))
        if (showRAM) tiles.add(Tile("RAM", colRam, numUnit(s.ramPercent.roundToInt().toString(), "%", valPx, unitPx),
            "${s.ramUsedText()} / ${s.ramTotalText()}", false))
        // API/engine tile — short translator name (DXVK/VKD3D/Zink) with the DX version on the sub-line;
        // a normal half-width tile that naturally fills the empty slot beside RAM. Gated by "Engine".
        if (showEngine && engineLabel.isNotBlank())
            tiles.add(Tile("API", colFps, listOf(Span(engineLabel, colValue, valPx)),
                dxVersion().ifBlank { null }, false))
        if (showGpuModel && gpuModel.isNotBlank())
            tiles.add(Tile("GPU", colGpu, listOf(Span(gpuModel, colValue, valPx)), null, true))
        if (showBattery || showPower || showBatteryTemp) {
            val parts = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { parts += numUnit(s.battery.percent.toString(), "%", valPx, unitPx); any = true }
            if (showBatteryTemp && s.battery.tempC != null) { if (any) parts += gap(unitPx); parts += Span(" · ", colDim, subPx); parts += tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, valPx, unitPx); any = true }
            if (showPower && s.battery.watts > 0f) { if (any) parts += Span(" · ", colDim, subPx); parts += numUnit(fmt1(s.battery.watts), "W", valPx, unitPx); any = true }
            if (any) tiles.add(Tile("BAT", colBat, parts, null, true))
        }
        if (tiles.isEmpty()) { contentW = 0f; contentH = 0f; return }

        val keyH = lineH(keyPx); val valH = lineH(valPx); val subH = lineH(subPx)
        fun tileW(t: Tile) = max(max(measure(t.key, keyPx), runWidth(t.value)),
            t.sub?.let { measure(it, subPx) } ?: 0f) + innerPad * 2
        var normalW = 0f
        for (t in tiles) if (!t.wide) normalW = max(normalW, tileW(t))
        if (normalW == 0f) for (t in tiles) normalW = max(normalW, tileW(t) / 2f)
        val tileH = innerPad * 2 + keyH + lineGap + valH + lineGap + subH // uniform (room for a sub line)
        val fullW = normalW * 2 + tileGap

        var x = pad; var y = pad; var col = 0
        fun place(t: Tile, tx: Float, ty: Float, tw: Float) {
            tileRects.add(RectF(tx, ty, tx + tw, ty + tileH))
            var by = ty + innerPad - ascent(keyPx)
            placeRun(tx + innerPad, by, listOf(Span(t.key, t.keyColor, keyPx)))
            by = ty + innerPad + keyH + lineGap - ascent(valPx)
            placeRun(tx + innerPad, by, t.value)
            if (t.sub != null) {
                by = ty + innerPad + keyH + lineGap + valH + lineGap - ascent(subPx)
                placeRun(tx + innerPad, by, listOf(Span(t.sub, colDim, subPx)))
            }
        }
        for (t in tiles) {
            if (t.wide) {
                if (col != 0) { y += tileH + tileGap; col = 0; x = pad }
                place(t, pad, y, fullW)
                y += tileH + tileGap; col = 0; x = pad
            } else {
                place(t, x, y, normalW)
                col++; x += normalW + tileGap
                if (col == 2) { col = 0; x = pad; y += tileH + tileGap }
            }
        }
        if (col != 0) y += tileH + tileGap
        contentW = pad + fullW + pad
        contentH = y + (pad - tileGap)
        addSubtleClock(pad)
    }

    private fun buildPill(s: HudMetrics.Snapshot) {
        val bigPx = sp(30f); val bigUnitPx = bigPx * 0.36f; val stkPx = sp(11.5f)
        val pad = sp(10f); val midGap = sp(12f); val stkLineGap = sp(3f)

        val left = ArrayList<Span>()
        left += Span(fpsText(fpsNow), colValue, bigPx)
        left += Span("fps", colDim, bigUnitPx)

        val stack = ArrayList<List<Span>>()
        if (showGpuModel && gpuModel.isNotBlank()) stack.add(listOf(Span(gpuModel, colDim, stkPx)))
        run {
            val l = ArrayList<Span>()
            if (showGPU) { l += Span("GPU ${s.gpuPercent ?: "—"}%", colGpu, stkPx) }
            if (showCPU) { if (l.isNotEmpty()) l += Span(" · ", colDim, stkPx); l += Span("CPU ${s.cpuPercent ?: "—"}%", colCpu, stkPx) }
            if (l.isNotEmpty()) stack.add(l)
        }
        // RAM % (system-wide) takes the slot the latency row used to occupy, matching the GPU/CPU idiom
        // on the row above it. colRam keeps RAM's identity colour consistent with the Full/Tiles sizes.
        if (showRAM) {
            val l = ArrayList<Span>()
            l += Span("RAM ${s.ramPercent.roundToInt()}%", colRam, stkPx)
            // Battery temperature as a "BAT" chip right after RAM (pill-only placement, per user
            // request). Colour-banded warm→red via tempSpans/TempSensor.BATTERY — the exact same
            // danger bands the GPU/CPU temps use. Only shown when the battery-temp toggle is on
            // and a reading is available; the other sizes keep battery temp in the battery row.
            if (showBatteryTemp && s.battery.tempC != null) {
                l += Span(" · ", colDim, stkPx)
                l += Span("BAT ", colBat, stkPx)
                l += tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, stkPx, stkPx * 0.62f)
            }
            stack.add(l)
        }
        if (showBattery || showPower) {
            val l = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { l += Span("BAT ${s.battery.percent}%", colBat, stkPx); any = true }
            if (showPower && s.battery.watts > 0f) { if (any) l += Span(" · ", colDim, stkPx); l += Span("${fmt1(s.battery.watts)}W", colDim, stkPx) }
            if (any) stack.add(l)
        }
        // Latency (+ optional VRAM) drops to the bottom of the stack, under the battery row.
        run {
            val l = ArrayList<Span>()
            l += Span("${fmt1(1000f / max(fpsNow, 1f))}ms", colDim, stkPx)
            if (showVram && s.vramText() != null) { l += Span(" · ", colDim, stkPx); l += Span("${s.vramText()} vram", colDim, stkPx) }
            stack.add(l)
        }

        // Left column: a small API/engine caption (DXVK/VKD3D/Zink) centred ABOVE the big FPS — mirroring
        // the clock centred BELOW it, so the left reads API · FPS · clock top-to-bottom.
        val apiStr = apiLabel()
        val hasApi = apiStr != "FPS"
        val apiH = if (hasApi) lineH(stkPx) else 0f
        val apiGap = if (hasApi) stkLineGap else 0f

        val leftW = runWidth(left); val leftH = lineH(bigPx)
        val apiW = if (hasApi) measure(apiStr, stkPx) else 0f
        val leftBlockW = max(leftW, apiW)
        val leftColH = apiH + apiGap + leftH
        val stkH = lineH(stkPx)
        var stackW = 0f
        for (l in stack) stackW = max(stackW, runWidth(l))
        val stackTotalH = stack.size * stkH + (stack.size - 1).coerceAtLeast(0) * stkLineGap
        val innerH = max(leftColH, stackTotalH)
        contentW = pad + leftBlockW + midGap + stackW + pad
        contentH = pad + innerH + pad

        // left column (API caption + big FPS), vertically centered as a block
        var ly = pad + (innerH - leftColH) / 2f
        if (hasApi) {
            placeRun(pad + (leftBlockW - apiW) / 2f, ly - ascent(stkPx), listOf(Span(apiStr, colFps, stkPx)))
            ly += apiH + apiGap
        }
        placeRun(pad + (leftBlockW - leftW) / 2f, ly - ascent(bigPx), left)
        // stack, vertically centered
        var sy = pad + (innerH - stackTotalH) / 2f
        for (l in stack) {
            placeRun(pad + leftBlockW + midGap, sy - ascent(stkPx), l)
            sy += stkH + stkLineGap
        }
        addSubtleClock(pad, pad + leftBlockW / 2f)   // clock centred under the FPS (left of the pill centre)
        // Capsule border captured AFTER the footer clock so the pill encloses it too.
        pillBorder = RectF(0f, 0f, contentW, contentH)
    }

    private fun buildMinimal(s: HudMetrics.Snapshot) {
        val bigPx = sp(34f); val bigUnitPx = bigPx * 0.32f; val subPx = sp(11.5f)
        val pad = sp(10f); val lineGap = sp(6f)

        val big = listOf(Span(fpsText(fpsNow), colValue, bigPx), Span("fps", colDim, bigUnitPx))
        val sub = ArrayList<Span>()
        sub += Span("1% ", colDim, subPx); sub += Span(lowText(lows.low1Fps) ?: "—", colFps, subPx)
        sub += Span("  ·  0.1% ", colDim, subPx); sub += Span(lowText(lows.low01Fps) ?: "—", colFps, subPx)
        if (showLow001) {
            sub += Span("  ·  0.01% ", colDim, subPx); sub += Span(lowText(lows.low001Fps) ?: "—", colFps, subPx)
        }
        val bigW = runWidth(big); val bigH = lineH(bigPx)
        val subW = runWidth(sub); val subH = lineH(subPx)
        val gw = sp(120f); val gh = sp(22f)
        val inner = max(max(bigW, subW), if (showGraph) gw else 0f)
        contentW = inner + pad * 2
        var y = pad
        // big (centered)
        placeRun(pad + (inner - bigW) / 2f, y - ascent(bigPx), big)
        y += bigH + lineGap
        // sub (centered)
        placeRun(pad + (inner - subW) / 2f, y - ascent(subPx), sub)
        y += subH + lineGap
        // graph (centered) — toggled by the FPS-graph chip
        if (showGraph) {
            graphRect = RectF(pad + (inner - gw) / 2f, y, pad + (inner - gw) / 2f + gw, y + gh)
            y += gh
        }
        contentH = y + pad
        // Small API/engine label (DXVK/VKD3D/Zink), bottom-LEFT, near the graph — balances the subtle
        // clock on the bottom-right (both share the same footer line). Gated by the "Engine" chip. Placed
        // BEFORE addSubtleClock so both read the same footerTop (= contentH - pad).
        if (showEngine && engineLabel.isNotBlank()) {
            val apiPx = sp(9.5f)
            val footerTop = contentH - pad
            glyphs.add(Glyph(pad, footerTop - ascent(apiPx), engineLabel, blend(colDim), apiPx))
            val apiW = measure(engineLabel, apiPx)
            val clockW = if (showClockTime) measure(clockTimeString(), apiPx) else 0f
            contentW = max(contentW, pad + apiW + sp(12f) + clockW + pad)
            // The clock (when shown) grows contentH for the footer line; when it's hidden, do it here.
            if (!showClockTime) contentH = footerTop + lineH(apiPx) + pad * 0.5f
        }
        addSubtleClock(pad)
    }

    /** Places a column of "label + value" rows; returns (bottomY, rightX). Appends glyphs. Rows flagged
     *  [HudRow.inline] opt out of the shared label column (value follows the label directly). */
    private fun layoutColumn(rows: List<HudRow>, x: Float, y0: Float,
                             rowPx: Float, lineGap: Float, lvGap: Float): Pair<Float, Float> {
        if (rows.isEmpty()) return Pair(y0, x)
        var labelCol = 0f
        for (r in rows) if (!r.inline) labelCol = max(labelCol, measure(r.label.text, rowPx))
        val h = lineH(rowPx); val asc = ascent(rowPx)
        var y = y0; var right = x
        for (r in rows) {
            val baseline = y - asc
            placeRun(x, baseline, listOf(r.label))
            val valX = if (r.inline) x + measure(r.label.text, rowPx) + lvGap
                       else x + labelCol + lvGap
            val end = placeRun(valX, baseline, r.vals)
            right = max(right, end)
            y += h + lineGap
        }
        return Pair(y - lineGap, right)
    }

    /** Places wrapping " · "-separated info fragments within maxWidth; returns (bottomY, rightX). */
    private fun layoutWrap(fragments: List<List<Span>>, x: Float, y0: Float, maxWidth: Float,
                           px: Float, lineGap: Float): Pair<Float, Float> {
        if (fragments.isEmpty()) return Pair(y0, x)
        val h = lineH(px); val asc = ascent(px)
        val sepW = measure(" · ", px)
        var cx = x; var y = y0; var right = x; var firstOnLine = true
        for (frag in fragments) {
            val w = runWidth(frag)
            if (!firstOnLine && (cx + sepW + w - x) > maxWidth) { y += h + lineGap; cx = x; firstOnLine = true }
            if (!firstOnLine) { placeRun(cx, y - asc, listOf(Span(" · ", colDim, px))); cx += sepW }
            val end = placeRun(cx, y - asc, frag)
            cx = end; right = max(right, end); firstOnLine = false
        }
        return Pair(y + h, right)
    }

    private fun buildMega(s: HudMetrics.Snapshot) {
        val rowPx = sp(11.5f); val unitPx = rowPx * 0.62f; val bandPx = sp(9.5f)
        val pad = sp(10f); val lineGap = sp(3.5f); val lvGap = sp(7f); val gutter = sp(16f)

        // ---- LEFT column: GPU, aggregate CPU, then per-core rows ----
        val left = ArrayList<HudRow>()
        if (showGpuModel && gpuModel.isNotBlank())
            left.add(HudRow(Span("GPU", colGpu, rowPx), listOf(Span(gpuModel, colValue, rowPx))))
        if (showGPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.gpuPercent?.toString(), "%", rowPx, unitPx)
            if (showGpuTemp) tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.gpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            left.add(HudRow(Span("GPU", colGpu, rowPx), v))
        }
        if (showCPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.cpuPercent?.toString(), "%", rowPx, unitPx)
            if (showCpuTemp) tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.cpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            left.add(HudRow(Span("CPU", colCpu, rowPx), v))
        }
        if (showPerCore) {
            val pct = s.perCorePercent; val clk = s.perCoreClockMhz
            val n = max(pct.size, clk.size)
            for (i in 0 until n) {
                val v = ArrayList<Span>()
                v += numUnit(if (i < pct.size && pct[i] >= 0) pct[i].toString() else null, "%", rowPx, unitPx)
                v += gap(unitPx)
                v += numUnit(if (i < clk.size && clk[i] > 0) clk[i].toString() else null, "MHz", rowPx, unitPx)
                left.add(HudRow(Span("C$i", colCpu, rowPx), v))
            }
        }

        // ---- RIGHT column: VRAM, RAM, SWP, NET, BAT, engine, lows ----
        val right = ArrayList<HudRow>()
        if (showVram && s.vramText() != null)
            right.add(HudRow(Span("VRAM", colVram, rowPx), valueUnit(s.vramText()!!, rowPx, unitPx)))
        if (showRAM) {
            val v = ArrayList<Span>()
            v += valueUnit(s.ramUsedText(), rowPx, unitPx)
            v += Span("/", colDim, unitPx); v += valueUnit(s.ramTotalText(), rowPx, unitPx)
            v += gap(unitPx); v += numUnit(s.ramPercent.roundToInt().toString(), "%", rowPx, unitPx)
            right.add(HudRow(Span("RAM", colRam, rowPx), v))
        }
        if (showSwap && s.swapUsedText() != null) {
            val v = ArrayList<Span>()
            v += valueUnit(s.swapUsedText()!!, rowPx, unitPx)
            s.swapTotalText()?.let { v += Span("/", colDim, unitPx); v += valueUnit(it, rowPx, unitPx) }
            right.add(HudRow(Span("SWP", colRam, rowPx), v))
        }
        if (showNet && s.netDownBps != null) {
            val d = (s.netDownBps ?: 0L) / 1024L
            val u = (s.netUpBps ?: 0L) / 1024L
            val v = ArrayList<Span>()
            v += Span("↓", colDim, unitPx); v += Span(d.toString(), colValue, rowPx)
            v += Span(" ↑", colDim, unitPx); v += Span(u.toString(), colValue, rowPx)
            v += Span("KB/s", colDim, unitPx)
            right.add(HudRow(Span("NET", colFps, rowPx), v))
        }
        if (showBattery || showPower || showBatteryTemp) {
            val v = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { v += numUnit(s.battery.percent.toString(), "%", rowPx, unitPx); any = true }
            if (showBatteryTemp) tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { if (any) v += gap(unitPx); v += it; any = true } }
            if (showPower && s.battery.watts > 0f) { if (any) v += gap(unitPx); v += numUnit(fmt1(s.battery.watts), "W", rowPx, unitPx); any = true }
            if (any) right.add(HudRow(Span("BAT", colBat, rowPx), v))
        }
        if (showFPS) {
            val v = ArrayList<Span>()
            v += numUnit(fpsText(fpsNow), "FPS", rowPx, unitPx)
            v += gap(unitPx); v += numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", rowPx, unitPx)
            // FPS/engine row: just the short API label. The DX version moved to the stack lines
            // below the frametime graph (see below), so this row stays compact.
            right.add(HudRow(Span(apiLabel(), colFps, rowPx), v))
            right.add(HudRow(Span("AVG", colLo, rowPx), numUnit(fmt1(fpsAvg), "FPS", rowPx, unitPx)))
            right.add(HudRow(Span("1%", colLo, rowPx), numUnit(lowText(lows.low1Fps), "FPS", rowPx, unitPx)))
            right.add(HudRow(Span("0.1%", colLo, rowPx), numUnit(lowText(lows.low01Fps), "FPS", rowPx, unitPx)))
            if (showLow001)
                right.add(HudRow(Span("0.01%", colLo, rowPx), numUnit(lowText(lows.low001Fps), "FPS", rowPx, unitPx)))
        }

        // ---- Two columns side by side (each reflows independently) ----
        val (leftBottom, leftRight) = layoutColumn(left, pad, pad, rowPx, lineGap, lvGap)
        val rightX = (if (left.isEmpty()) pad else leftRight + gutter)
        val (rightBottom, rightRight) = layoutColumn(right, rightX, pad, rowPx, lineGap, lvGap)
        var y = max(leftBottom, rightBottom)
        var maxRight = max(leftRight, rightRight)

        // ---- Bottom band spanning both columns: res · Proton · elapsed ----
        // (The graphics wrapper moved to the FPS-row label above, so it's no longer in this band.)
        val band = ArrayList<List<Span>>()
        if (showResolution) band.add(listOf(Span("RES ", colDim, bandPx), Span(resolutionString(), colValue, bandPx)))
        if (showProton && wineVersion.isNotBlank()) band.add(listOf(Span(wineVersion, colVram, bandPx)))
        if (showSession) band.add(listOf(Span("elapsed ", colDim, bandPx), Span(elapsedString(), colValue, bandPx)))
        if (band.isNotEmpty()) {
            y += sp(4f)
            val (bBottom, bRight) = layoutWrap(band, pad, y, max(maxRight - pad, sp(180f)), bandPx, lineGap)
            y = bBottom; maxRight = max(maxRight, bRight)
        }

        // ---- Full-width frametime graph ----
        if (showGraph) {
            y += sp(3f)
            val gh = sp(22f)
            val gr = max(maxRight, pad + sp(220f))
            graphRect = RectF(pad, y, gr, y + gh)
            maxRight = max(maxRight, gr)
            y += gh
        }

        // ---- Stack-layer lines BELOW the frametime graph, subtle (same size/style as the bottom
        // band): the DX-wrapper version first (e.g. "DXVK 2.4.1-…", gated by "DX ver"), then the
        // graphics wrapper beneath it (e.g. "Original", gated by "Wrapper"). ----
        val dxLine = dxVersionLabeled()
        val hasDx = showDxVer && dxLine.isNotBlank()
        if (hasDx) {
            y += sp(4f)
            maxRight = max(maxRight, placeRun(pad, y - ascent(bandPx), listOf(Span(dxLine, colDim, bandPx))))
            y += lineH(bandPx)
        }
        if (showWrapper && graphicsWrapper.isNotBlank()) {
            y += if (hasDx) sp(1f) else sp(4f)
            maxRight = max(maxRight, placeRun(pad, y - ascent(bandPx), listOf(Span(graphicsWrapper, colDim, bandPx))))
            y += lineH(bandPx)
        }

        contentW = maxRight + pad
        contentH = y + pad
        addSubtleClock(pad)   // subtle time-of-day, consistent with the other sizes
    }

    // ---- Measure / draw ---------------------------------------------------
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(contentW.roundToInt(), widthMeasureSpec),
            resolveSize(contentH.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (contentW <= 0f || contentH <= 0f) { lockController.drawBadge(canvas); return }

        // The Pill is a capsule — its background must use the SAME height/2 radius as the outline, or the
        // boxier 8sp background corners poke out past the accent border (the "bg larger than outline" bug).
        val pill = pillBorder
        val radius = if (pill != null) height / 2f else sp(8f)
        bgPaint.color = Color.argb((bgOpacity.coerceIn(0f, 1f) * 255f).roundToInt(), 0, 0, 0)
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, bgPaint)

        // Outline follows the settings for EVERY size (incl. the Pill): width from the outline slider
        // (0 = none), colour = accent or grey per the "Outline color" toggle. The Pill only differs in
        // its capsule radius (height/2), which is baked into `radius` above.
        val strokeW = if (outlineIntensity > 0f) outlineIntensity * sp(3.5f) else 0f
        if (strokeW > 0f) {
            strokePaint.strokeWidth = strokeW
            strokePaint.color = if (outlineFollowAccent) AppThemeState.getCurrentAccentArgb()
                                else Color.rgb(200, 200, 200)
            val h = strokeW / 2f
            canvas.drawRoundRect(h, h, width - h, height - h, radius, radius, strokePaint)
        }

        // Tile backings
        if (tileRects.isNotEmpty()) {
            tilePaint.color = Color.argb((14 * bgOpacity).roundToInt().coerceIn(8, 40), 255, 255, 255)
            val tr = sp(6f)
            for (rect in tileRects) canvas.drawRoundRect(rect, tr, tr, tilePaint)
        }

        // Text
        for (g in glyphs) {
            drawPaint.textSize = g.sizePx
            drawPaint.color = g.color
            canvas.drawText(g.text, g.x, g.baseline, drawPaint)
        }

        // Frametime graph
        graphRect?.let { drawGraph(canvas, it) }

        lockController.drawBadge(canvas)
    }

    private fun drawGraph(canvas: Canvas, rect: RectF) {
        val values = graphSamples.toList().filter { it.isFinite() }
        if (values.size < 2) return
        var peak = 1f
        for (v in values) peak = max(peak, v)
        graphPaint.strokeWidth = sp(1.6f)
        graphPaint.color = blend(colGraph)
        val step = rect.width() / (values.size - 1)
        val path = Path()
        for (i in values.indices) {
            val px = rect.left + i * step
            val py = rect.bottom - (values[i] / peak).coerceIn(0f, 1f) * rect.height()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, graphPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = lockController.onTouchEvent(event)

    private companion object {
        const val GRAPH_CAP = 60

        fun parseIntOr(s: String?, d: Int): Int = try { s?.trim()?.toInt() ?: d } catch (e: Exception) { d }

        fun parseOutline(v: String?): Int = when (v?.trim()?.lowercase(Locale.US)) {
            null -> 40; "off" -> 0; "soft" -> 40; "strong" -> 70
            else -> v.trim().toIntOrNull()?.coerceIn(0, 100) ?: 40
        }
    }
}
