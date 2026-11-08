package com.winlator.star.ui.deck

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.core.GPUInformation
import com.winlator.star.core.KeyValueSet
import com.winlator.star.core.VKD3DVersionItem
import com.winlator.star.core.WineThemeManager
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.screens.ContainerDetailViewModel
import com.winlator.star.ui.screens.adrenodownload.DriverSources
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverEntry
import com.winlator.star.ui.screens.adrenodownload.RemoteDriverRepository
import com.winlator.star.ui.screens.graphicsProbeMutex
import com.winlator.star.ui.screens.withGraphicsDriverVersion
import com.winlator.star.xenvironment.ImageFs
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.zip.ZipFile

/** One choice in a first-container list: a contents.json component, a bundled Box64, or a Banners-Turnip driver. */
internal class FcChoice(
    val label: String,
    val profile: ContentProfile? = null,
    val builtIn: String? = null,
    val driver: RemoteDriverEntry? = null,
)

/** One row of the step: its list, the recommended entry and the current pick. */
internal class FcPick(val title: String) {
    var options by mutableStateOf(emptyList<FcChoice>())
    var recommended by mutableStateOf<FcChoice?>(null)
    var chosen by mutableStateOf<FcChoice?>(null)

    fun fill(list: List<FcChoice>, rec: FcChoice?) {
        options = list
        recommended = rec ?: list.firstOrNull()
        chosen = recommended
    }
}

/**
 * The first-run "Your first container" step: lists from contents.json and the Banners-Turnip feed with a
 * recommended pick each, then downloads the picks one at a time (installs share one temp folder) and creates
 * the container through the editor's own create path, so it equals one made with "New container".
 */
internal class FirstContainerSetup(private val app: Application) {
    val proton = FcPick("Proton")
    val dxvk = FcPick("DXVK")
    val vkd3d = FcPick("VKD3D")
    val fex = FcPick("FEXCore")
    val box64 = FcPick("Box64")
    val driver = FcPick("Graphics driver")

    var gpuLabel by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var loadError by mutableStateOf<String?>(null); private set

    var busy by mutableStateOf(false); private set
    var done by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var fraction by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null); private set

    val cores: Int = Runtime.getRuntime().availableProcessors()
    val isArm64ec: Boolean get() = proton.chosen?.label?.contains("arm64ec", ignoreCase = true) == true

    fun load(scope: CoroutineScope) {
        if (loading || loaded) return
        loading = true
        loadError = null
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { Downloader.downloadString(ContentsManager.REMOTE_PROFILES) }
                    ?: throw IllegalStateException("No connection")
                val rows = withContext(Dispatchers.Default) { parseCatalog(json) }
                fun of(t: ContentProfile.ContentType) = rows.filter { it.type == t }
                fun choices(list: List<ContentProfile>) = list.map { FcChoice(it.verName, profile = it) }

                val protons = choices(of(ContentProfile.ContentType.CONTENT_TYPE_PROTON))
                proton.fill(protons, protons.filter { it.label.startsWith("DroidDeck-Proton", true) && it.label.contains("arm64ec", true) }
                    .maxWithOrNull(compareBy<FcChoice> { it.profile!!.verCode }.then(BY_NAME))
                    ?: protons.filter { it.label.contains("arm64ec", true) }.maxWithOrNull(BY_NAME))

                val dxvks = choices(of(ContentProfile.ContentType.CONTENT_TYPE_DXVK).filter { !it.verName.contains("arm64ec", true) })
                dxvk.fill(dxvks, dxvks.firstOrNull { it.label.equals("dxvk-gplasync-2.4.1-1-pre-reg", true) }
                    ?: dxvks.firstOrNull { it.label.contains("gplasync", true) && it.label.contains("2.4.1") && it.label.contains("pre-reg", true) })

                val vkd3ds = choices(of(ContentProfile.ContentType.CONTENT_TYPE_VKD3D).filter { !it.verName.contains("arm64ec", true) })
                vkd3d.fill(vkd3ds, vkd3ds.filter { STABLE_VKD3D.matches(it.label) }.maxWithOrNull(BY_NAME))

                val fexes = choices(of(ContentProfile.ContentType.CONTENT_TYPE_FEXCORE).filter { it.verName.endsWith("-unix", true) })
                fex.fill(fexes, fexes.filter { !it.label.contains("ppa", true) && !it.label.contains("nightly", true) }
                    .maxWithOrNull(BY_NAME))

                val bundled = app.resources.getStringArray(R.array.box64_version_entries).map { FcChoice("$it (built in)", builtIn = it) }
                val box64s = bundled + choices(of(ContentProfile.ContentType.CONTENT_TYPE_BOX64))
                box64.fill(box64s, bundled.firstOrNull())

                loadDrivers()
                loaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadError = e.message ?: "Couldn't load the lists"
            } finally {
                loading = false
            }
        }
    }

    /** The phone's GPU, then the Banners-Turnip builds made for it (Android builds only), newest first. */
    private suspend fun loadDrivers() {
        val renderer = withContext(Dispatchers.IO) {
            graphicsProbeMutex.withLock { runCatching { GPUInformation.getRenderer(null, null) }.getOrNull() }
        }
        gpuLabel = renderer?.let { GPUInformation.extractModelName(it) }
        val adreno = renderer?.let { Regex("Adreno\\D*(\\d{3})", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: return
        val variants = driverVariants(adreno)
        val source = DriverSources.BUILT_IN.firstOrNull { it.name == "Banners-Turnip" } ?: return
        val entries = RemoteDriverRepository(app).fetchEntries(source).getOrNull().orEmpty()
        val matched = entries.mapNotNull { e -> TURNIP.matchEntire(e.displayName)?.let { m -> e to m } }
            .filter { (_, m) -> m.groupValues[3] in variants }
            .sortedWith(compareByDescending<Pair<RemoteDriverEntry, MatchResult>> { it.second.groupValues[1] }
                .thenByDescending { it.second.groupValues[2].toIntOrNull() ?: 1 })
            .take(24)
        val list = matched.map { (e, _) -> FcChoice(e.displayName, driver = e) }
        driver.fill(list, list.firstOrNull { it.label.endsWith("-Banners-AIO") })
    }

    fun create(scope: CoroutineScope) {
        if (busy || done) return
        val protonRow = proton.chosen?.profile ?: return
        busy = true
        error = null
        scope.launch {
            try {
                val fs = ImageFs.find(app)
                if (!(fs.isValid && fs.version >= ImageFsInstaller.LATEST_VERSION)) throw IllegalStateException("The base system isn't installed yet")
                val arm = isArm64ec
                val wine = ContentsManager.getEntryName(install(protonRow, "Proton"))
                val dxvkValue = dxvk.chosen?.profile?.let { ContentsManager.getEntryName(install(it, "DXVK")).substringAfter('-') }
                val vkd3dValue = vkd3d.chosen?.profile?.let { p -> install(p, "VKD3D").let { VKD3DVersionItem(it.verName, it.verCode).identifier } }
                val fexValue = if (arm) fex.chosen?.profile?.let { ContentsManager.getEntryName(install(it, "FEXCore")).substringAfter('-') } else null
                val box64Value = if (arm) null else box64.chosen?.let { c ->
                    c.builtIn ?: c.profile?.let { ContentsManager.getEntryName(install(it, "Box64")).substringAfter('-') }
                }
                val driverId = driver.chosen?.driver?.let { installDriver(it) }
                status = "Creating the container…"
                fraction = 0f
                if (!createContainer(wine, dxvkValue, vkd3dValue, fexValue, box64Value, driverId)) throw IllegalStateException("The container couldn't be created")
                done = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Failed"
            } finally {
                busy = false
            }
        }
    }

    private fun installed(type: ContentProfile.ContentType): List<ContentProfile> =
        ContentsManager(app).apply { syncContents() }.getProfiles(type).orEmpty().filter { it.remoteUrl == null }

    /** Downloads and installs one catalog row (or finds it installed) and returns the installed profile. */
    private suspend fun install(row: ContentProfile, label: String): ContentProfile {
        val type = row.type
        val before = withContext(Dispatchers.IO) { installed(type).map { ContentsManager.getEntryName(it) }.toSet() }
        status = "Downloading $label…"
        fraction = 0f
        val key = ContentsManager.getEntryName(row)
        startContentDownload(app, row)
        val last = ContentDownloadRegistry.states.map { it[key] }
            .onEach { s ->
                if (s != null) {
                    status = (if (s.phase == ContentDownloadPhase.INSTALLING) "Installing" else "Downloading") + " $label…"
                    fraction = s.fraction
                }
            }
            .first { it == null || it.terminal }
        if (last != null && last.phase == ContentDownloadPhase.ERROR) throw IllegalStateException(if (last.cancelled) "$label cancelled" else "$label: ${last.error ?: "failed"}")
        val after = withContext(Dispatchers.IO) { installed(type) }
        return after.firstOrNull { ContentsManager.getEntryName(it) !in before }
            ?: after.firstOrNull { it.verName.equals(row.versionName ?: row.verName, true) && it.verCode == row.verCode }
            ?: after.firstOrNull { row.verName.contains(it.verName, true) && it.verCode == row.verCode }
            ?: throw IllegalStateException("$label wasn't found after installing")
    }

    /** Downloads one driver zip and installs it (or finds it installed); returns its adrenotools id. */
    private suspend fun installDriver(entry: RemoteDriverEntry): String {
        status = "Downloading the graphics driver…"
        fraction = 0f
        val file = RemoteDriverRepository(app).downloadEntry(entry) { p -> fraction = p / 100f }.getOrThrow()
        status = "Installing the graphics driver…"
        return withContext(Dispatchers.IO) {
            try {
                val name = runCatching {
                    ZipFile(file).use { z -> z.getEntry("meta.json")?.let { z.getInputStream(it).bufferedReader().readText() } }
                }.getOrNull()?.let { runCatching { JSONObject(it).optString("name") }.getOrNull() }.orEmpty()
                val manager = AdrenotoolsManager(app)
                if (name.isNotEmpty() && manager.enumarateInstalledDrivers().contains(name)) return@withContext name
                manager.installDriver(Uri.fromFile(file)).orEmpty().ifEmpty { throw IllegalStateException("The graphics driver couldn't be installed") }
            } finally {
                file.delete()
            }
        }
    }

    /** "New container" with these picks: the editor's view-model in create mode, so every other setting is its default. */
    private suspend fun createContainer(
        wine: String, dxvkValue: String?, vkd3dValue: String?, fexValue: String?, box64Value: String?, driverId: String?,
    ): Boolean = withContext(Dispatchers.Main) {
        val vm = ContainerDetailViewModel(app)
        vm.init(-1)
        vm.onWineVersionChanged(wine)
        if (dxvkValue != null || vkd3dValue != null) {
            val cfg = KeyValueSet(vm.dxWrapperConfig.ifEmpty { Container.DEFAULT_DXWRAPPERCONFIG })
            dxvkValue?.let { cfg.put("version", it) }
            vkd3dValue?.let { cfg.put("vkd3dVersion", it) }
            vm.dxWrapperConfig = cfg.toString()
        }
        fexValue?.let { vm.selectedFEXCoreVersion = it }
        box64Value?.let { vm.selectedBox64Version = it }
        driverId?.let { vm.graphicsDriverConfig = withGraphicsDriverVersion(vm.graphicsDriverConfig, it) }
        vm.cpuList = Container.getFallbackCPUList()
        val color = if (vm.desktopBgTypeIndex == WineThemeManager.BackgroundType.COLOR.ordinal)
            String.format(Locale.ENGLISH, "#%06X", 0x00ffffff and vm.desktopBgColorInt) else "#0277bd"
        val finished = CompletableDeferred<Unit>()
        vm.confirm(vm.graphicsDriverConfig, vm.dxWrapperConfig, vm.fpsCounterConfig, vm.envVarsStr, vm.cpuList, vm.cpuListWoW64, color) {
            finished.complete(Unit)
        }
        finished.await()
        vm.container != null
    }

    companion object {
        private val BY_NAME = Comparator<FcChoice> { a, b -> natural(a.label, b.label) }
        private val STABLE_VKD3D = Regex("^vkd3d-proton-[0-9.]+$", RegexOption.IGNORE_CASE)
        /** Banners-Turnip names: date, optional rebuild number, optional variant ("" = Standard). */
        private val TURNIP = Regex("^Turnip-v[0-9.]+-(\\d{8})(?:-r(\\d+))?(?:-(.+))?$")

        /** The Banners-Turnip variants built for an Adreno model; AIO covers every one. */
        private fun driverVariants(adreno: Int): Set<String> = when {
            adreno in 800..899 -> setOf("Banners-AIO", "A8xx")
            adreno in 710..722 -> setOf("Banners-AIO", "710-720-Test")
            adreno == 740 && Build.MANUFACTURER.equals("samsung", true) -> setOf("Banners-AIO", "8G2-OneUI", "")
            else -> setOf("Banners-AIO", "")
        }

        private fun parseCatalog(json: String): List<ContentProfile> {
            val out = ArrayList<ContentProfile>()
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val t = runCatching { ContentProfile.ContentType.getTypeByName(o.optString("type")) }.getOrNull() ?: continue
                val url = o.optString("remoteUrl")
                val name = o.optString("verName")
                if (url.isEmpty() || name.isEmpty()) continue
                out += ContentProfile().apply {
                    remoteUrl = url
                    type = t
                    verName = name
                    verCode = o.optInt("verCode", 0)
                    versionName = o.optString("versionName").ifEmpty { null }
                }
            }
            return out
        }

        /** Compares names with their digit runs as numbers ("3.10" after "3.9"). */
        private fun natural(a: String, b: String): Int {
            val ta = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
            val tb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(ta.size, tb.size)) {
                val x = ta[i]
                val y = tb[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
                if (c != 0) return c
            }
            return ta.size - tb.size
        }
    }
}

/** The step's body: one list per part, the cores, then Create with its progress. */
@Composable
internal fun FirstContainerStep(fc: FirstContainerSetup, scope: CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { fc.load(scope) }
    val locked = fc.busy || fc.done
    DeckCard {
        when {
            fc.loading -> DeckRow(title = "Loading…")
            fc.loadError != null -> DeckRow(
                title = "Couldn't load the lists",
                subtitle = fc.loadError,
                trailing = { DeckButton("Retry", null, { fc.load(scope) }) },
            )
            else -> {
                PickField(fc.proton, !locked)
                DeckRowDivider()
                PickField(fc.dxvk, !locked)
                DeckRowDivider()
                PickField(fc.vkd3d, !locked)
                DeckRowDivider()
                PickField(if (fc.isArm64ec) fc.fex else fc.box64, !locked)
                if (fc.driver.options.isNotEmpty()) {
                    DeckRowDivider()
                    PickField(fc.driver, !locked, title = fc.gpuLabel?.let { "Graphics driver ($it)" })
                }
                DeckRowDivider()
                DeckRow(title = "CPU cores", trailing = { Text("All ${fc.cores}", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) })
            }
        }
    }
    if (fc.loaded) {
        Spacer(Modifier.height(18.dp))
        DeckCard {
            DeckRow(
                title = when {
                    fc.done -> "Container created"
                    fc.busy -> fc.status
                    else -> "Create container"
                },
                subtitle = fc.error,
                trailing = {
                    when {
                        fc.done -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done", tint = cs.primary, modifier = Modifier.size(28.dp))
                        fc.busy -> Unit
                        else -> DeckButton(if (fc.error != null) "Retry" else "Create", null, { fc.create(scope) })
                    }
                },
            )
        }
        if (fc.busy) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { fc.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(deckShape(4.dp)),
                color = cs.primary,
                trackColor = cs.surfaceVariant,
            )
        }
    }
}

/** A row label over a drop-down of the list; the recommended entry is marked in the list. */
@Composable
private fun PickField(pick: FcPick, enabled: Boolean, title: String? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(14.dp)
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title ?: pick.title, color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
        Spacer(Modifier.height(6.dp))
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .deckFocusRing(shape, scaleTo = 1f)
                    .clip(shape)
                    .background(cs.surfaceVariant.copy(alpha = 0.55f))
                    .border(1.dp, deckLine(), shape)
                    .clickable(enabled = enabled && pick.options.isNotEmpty()) { open = true }
                    .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            ) {
                Text(
                    text = pick.chosen?.label ?: "—",
                    color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.6f),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                pick.options.forEach { o ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(o.label, modifier = Modifier.weight(1f, fill = false))
                                if (o === pick.recommended) {
                                    Spacer(Modifier.width(10.dp))
                                    Text("Recommended", color = cs.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        },
                        leadingIcon = {
                            if (o === pick.chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            open = false
                            pick.chosen = o
                        },
                    )
                }
            }
        }
    }
}
