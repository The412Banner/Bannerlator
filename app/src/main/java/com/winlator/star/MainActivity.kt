package com.winlator.star

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.winlator.star.core.UpdateManager
import androidx.compose.runtime.rememberCoroutineScope
import com.winlator.star.ui.LocalTopBarActions
import com.winlator.star.ui.topBarActionsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.preference.PreferenceManager
import com.winlator.star.BuildConfig
import com.winlator.star.core.ImageUtils
import com.winlator.star.core.PreloaderDialog
import com.winlator.star.core.WineThemeManager
import com.winlator.star.container.ContainerManager
import com.winlator.star.store.AmazonMainActivity
import com.winlator.star.store.EpicMainActivity
import com.winlator.star.store.GogMainActivity
import com.winlator.star.store.SteamMainActivity
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.AppDrawerContent
import com.winlator.star.ui.AppNavGraph
import com.winlator.star.ui.AppTopBar
import com.winlator.star.ui.PreloaderOverlay
import com.winlator.star.ui.Screen
import com.winlator.star.ui.screens.SplashScreen
import com.winlator.star.ui.screens.SplashViewModel
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE: Byte = 1
        const val OPEN_FILE_REQUEST_CODE: Byte = 2
        const val EDIT_INPUT_CONTROLS_REQUEST_CODE: Byte = 3
        const val OPEN_DIRECTORY_REQUEST_CODE: Byte = 4
        const val OPEN_IMAGE_REQUEST_CODE: Byte = 5

        /** String extra: a Screen route to open (e.g. Screen.Games.route). Used by
         *  the store activities' "Open Shortcuts" action to deep-link back here. */
        const val EXTRA_OPEN_SCREEN = "open_screen"
        @JvmField val CONTAINER_PATTERN_COMPRESSION_LEVEL: Byte = 9
        @JvmField var PACKAGE_NAME: String = ""
    }

    @JvmField val preloaderDialog: PreloaderDialog = PreloaderDialog(this)
    lateinit var containerManager: ContainerManager
        private set

    private val splashViewModel: SplashViewModel by lazy {
        ViewModelProvider(this)[SplashViewModel::class.java]
    }

    private var selectedProfileId: Int = 0
    private var editInputControls: Boolean = false

    private val showAllFilesDialog = mutableStateOf(false)
    private val showAboutDialog = mutableStateOf(false)

    // Route requested via EXTRA_OPEN_SCREEN on a relaunch (onNewIntent); consumed
    // by AppShell, which navigates to it and clears it.
    private val pendingRoute = mutableStateOf<String?>(null)

    private val openImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = result.data?.data?.let {
                ImageUtils.getBitmapFromUri(this, it, 1280)
            } ?: return@registerForActivityResult
            val file = WineThemeManager.getUserWallpaperFile(this)
            ImageUtils.save(bitmap, file, Bitmap.CompressFormat.PNG, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        super.onCreate(savedInstanceState)

        PACKAGE_NAME = applicationContext.packageName
        AppThemeState.init(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        if (prefs.getBoolean("enable_big_picture_mode", false)) {
            startActivity(Intent(this, BigPictureActivity::class.java))
        }

        val winlatorDir = File(SettingsFragment.DEFAULT_WINLATOR_PATH)
        if (!winlatorDir.exists()) winlatorDir.mkdirs()

        containerManager = ContainerManager(this)

        editInputControls = intent.getBooleanExtra("edit_input_controls", false)
        selectedProfileId = intent.getIntExtra("selected_profile_id", 0)

        val startRoute = when {
            editInputControls -> Screen.InputControls.route
            else -> {
                val selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0)
                validRouteOrNull(intent.getStringExtra(EXTRA_OPEN_SCREEN))
                    ?: menuItemIdToRoute(selectedMenuItemId)
                    // User-chosen default landing screen (Settings). Only applies when nothing else
                    // dictated the route (no deep-link / menu nav / edit-controls). Defaults to
                    // "games" = the Game Shortcuts page, i.e. the historical default.
                    ?: if (prefs.getString("default_landing_screen", "games") == "containers")
                        Screen.Containers.route else Screen.Games.route
            }
        }

        if (!editInputControls) {
            val willInstall = splashViewModel.installIfNeeded(this)
            if (!willInstall) {
                // Already installed — request permissions immediately
                requestAppPermissions()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                    showAllFilesDialog.value = true
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
            }
            // If willInstall == true: permissions are requested after user taps Proceed
        }

        setContent {
            WinlatorTheme {
                val isInstalling by splashViewModel.isInstalling.collectAsState()
                val installProgress by splashViewModel.progress.collectAsState()
                val showProceed by splashViewModel.showProceed.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    AppShell(
                        startRoute = startRoute,
                        pendingRoute = pendingRoute.value,
                        onPendingRouteConsumed = { pendingRoute.value = null },
                        editInputControls = editInputControls,
                        selectedInputProfileId = selectedProfileId,
                        showAllFilesDialog = showAllFilesDialog.value,
                        showAboutDialog = showAboutDialog.value,
                        onDismissAllFilesDialog = { showAllFilesDialog.value = false },
                        onConfirmAllFilesDialog = {
                            showAllFilesDialog.value = false
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        },
                        onDismissAboutDialog = { showAboutDialog.value = false },
                        onAboutRequested = { showAboutDialog.value = true },
                        onLaunchStore = { screen -> launchStore(screen) },
                    )

                    // Resume a mid-flight component installer (Phase 3b) after the app restarts.
                    com.winlator.star.ui.screens.ComponentInstallResume()

                    if (isInstalling) {
                        SplashScreen(
                            progress = installProgress,
                            showProceed = showProceed,
                            onProceed = {
                                splashViewModel.dismissSplash()
                                requestAppPermissions()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                    !Environment.isExternalStorageManager()
                                ) {
                                    showAllFilesDialog.value = true
                                }
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestPermissions(
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
                                    )
                                }
                            },
                        )
                    }

                    // Compose-based preloader overlay — replaces XML PreloaderDialog
                    PreloaderOverlay()
                }
            }
        }
    }

    private fun launchStore(screen: Screen) {
        val cls = when (screen) {
            Screen.Gog    -> GogMainActivity::class.java
            Screen.Epic   -> EpicMainActivity::class.java
            Screen.Amazon -> AmazonMainActivity::class.java
            Screen.Steam  -> SteamMainActivity::class.java
            else          -> return
        }
        startActivity(Intent(this, cls))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Install runs independently now; nothing to do after storage permission result.
    }

    private fun requestAppPermissions() {
        val hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val storageReady = hasWrite && hasRead || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (storageReady) return  // Already granted; install was already started separately.

        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
            PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE.toInt(),
        )
    }

    /** Called by DownloadProgressDialog after a download to re-request permissions if needed. */
    fun doPermissionsFlow() {
        requestAppPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            showAllFilesDialog.value = true
        }
    }


    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Deep-link from the store activities (CLEAR_TOP|SINGLE_TOP relaunch).
        validRouteOrNull(intent.getStringExtra(EXTRA_OPEN_SCREEN))?.let {
            pendingRoute.value = it
        }
    }

    /** Only accepts known drawer routes so a bad extra can't crash navigation. */
    private fun validRouteOrNull(route: String?): String? =
        route?.takeIf { r -> Screen.drawerItems.any { it.route == r } }

    private fun menuItemIdToRoute(itemId: Int): String? = when (itemId) {
        R.id.main_menu_containers -> Screen.Containers.route
        R.id.main_menu_shortcuts  -> Screen.Games.route
        R.id.main_menu_contents   -> Screen.Contents.route
        R.id.main_menu_input_controls -> Screen.InputControls.route
        R.id.main_menu_adrenotools_gpu_drivers -> Screen.AdrenoTools.route
        R.id.main_menu_settings   -> Screen.Settings.route
        else -> null
    }
}

@Composable
private fun AppShell(
    startRoute: String,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
    editInputControls: Boolean,
    selectedInputProfileId: Int,
    showAllFilesDialog: Boolean,
    showAboutDialog: Boolean,
    onDismissAllFilesDialog: () -> Unit,
    onConfirmAllFilesDialog: () -> Unit,
    onDismissAboutDialog: () -> Unit,
    onAboutRequested: () -> Unit,
    onLaunchStore: (Screen) -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val topBarActionsState = remember { topBarActionsState() }

    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route ?: startRoute

    // In-app update banner: only when a newer stable exists, notify is on, and
    // this version wasn't skipped.
    var bannerUpdate by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info ->
            (context as? MainActivity)?.runOnUiThread {
                if (info != null && info.isNewer &&
                    UpdateManager.isNotifyEnabled(context) &&
                    info.versionCode != UpdateManager.skippedVersionCode(context)
                ) {
                    bannerUpdate = info
                }
            }
        }
    }

    // Navigate to a route requested by a relaunch intent (store "Open Shortcuts").
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onPendingRouteConsumed()
        }
    }

    // Clear top bar actions on navigation so stale actions from a previous screen don't persist.
    // Screens that need actions re-set them via SideEffect on each recomposition.
    // Also re-read the optional signed-in account so the ☰→avatar swap / drawer header reflect a login
    // or logout that happened on the screen we're returning from.
    LaunchedEffect(currentRoute) {
        topBarActionsState.value = {}
        AccountUiBus.refresh(context)
    }
    // Reactive mirror of the signed-in account (null = logged out / anonymous UX unchanged).
    val account = AccountUiBus.account

    val screenTitle = when {
        currentRoute.startsWith("container_detail") -> {
            val id = backstackEntry?.arguments?.getInt("id") ?: -1
            if (id > 0) context.getString(R.string.edit_container) else context.getString(R.string.new_container)
        }
        else -> Screen.drawerItems.firstOrNull { it.route == currentRoute }?.label ?: "Winlator"
    }

    CompositionLocalProvider(LocalTopBarActions provides topBarActionsState) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !editInputControls && !currentRoute.startsWith("container_detail"),
        drawerContent = {
            AppDrawerContent(
                currentRoute = currentRoute,
                account = account,
                onNavigate = { screen ->
                    scope.launch { drawerState.close() }
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onLaunchStore = { screen ->
                    scope.launch { drawerState.close() }
                    onLaunchStore(screen)
                },
                onAbout = {
                    scope.launch { drawerState.close() }
                    onAboutRequested()
                },
                // The My-account sheet lives on the Shortcuts screen; land there, then ask it to open.
                onMyAccount = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Screen.Games.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    AccountUiBus.requestMyAccount()
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                AppTopBar(
                    title = screenTitle,
                    showBack = editInputControls,
                    // Signed-in + has a picture → the ☰ becomes their avatar (still opens the drawer).
                    // Versioned URL so a live picture change refreshes the swap in lockstep with the drawer.
                    avatarUrl = account?.displayAvatarUrl,
                    onNavClick = {
                        if (editInputControls) {
                            navController.popBackStack()
                        } else {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close() else drawerState.open()
                            }
                        }
                    },
                    actions = topBarActionsState.value,
                )
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                val upd = bannerUpdate
                if (upd != null && !bannerDismissed && !editInputControls) {
                    UpdateBanner(
                        versionName = upd.versionName,
                        onUpdate = {
                            (context as? MainActivity)?.let { UpdateManager.downloadAndInstall(it, upd) {} }
                        },
                        onDismiss = {
                            bannerDismissed = true
                            UpdateManager.skipVersion(context, upd.versionCode)
                        },
                    )
                }
                AppNavGraph(
                    navController = navController,
                    selectedInputProfileId = selectedInputProfileId,
                    startRoute = startRoute,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    } // end CompositionLocalProvider

    if (showAllFilesDialog) {
        AllFilesAccessDialog(
            onConfirm = onConfirmAllFilesDialog,
            onDismiss = onDismissAllFilesDialog,
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = onDismissAboutDialog)
    }
}

@Composable
private fun AllFilesAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("All Files Access Required") },
        text = {
            Text(
                "In order to grant access to additional storage devices such as USB storage, " +
                "the All Files Access permission must be granted. Press OK to open Android Settings."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UpdateBanner(versionName: String, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val ink = androidx.compose.ui.graphics.Color(0xFF1A1A2E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(0xFFFFC107))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Update available — V $versionName",
            color = ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpdate) {
            Text("Update", color = ink, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onDismiss) {
            Text("Skip", color = ink)
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var update by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info -> activity?.runOnUiThread { update = info } }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline),
            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Logo + name
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "Bannerlator Bionic",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
                val newer = update?.takeIf { it.isNewer }
                Text(
                    // Read from BuildConfig so it tracks the gradle versionName automatically
                    // and never drifts from the real app version again.
                    text = "V ${BuildConfig.VERSION_NAME}" +
                        (newer?.let { " · latest V ${it.versionName}" } ?: ""),
                    fontSize = 13.sp,
                    color = com.winlator.star.ui.theme.OnSurfaceVariant
                )
                if (newer != null) {
                    Button(
                        onClick = { activity?.let { UpdateManager.downloadAndInstall(it, newer) {} } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        ),
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    ) { Text("Update now", color = androidx.compose.ui.graphics.Color.White) }
                }

                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Divider(color = com.winlator.star.ui.theme.Divider)
                Spacer(androidx.compose.ui.Modifier.height(4.dp))

                // Powered by
                AboutSection(title = "Powered By") {
                    AboutRow("Wine",    "Windows compatibility layer")
                    AboutRow("Box64",   "x86_64 emulation on ARM")
                    AboutRow("FEX-Emu", "Fast x86 emulator")
                    AboutRow("Turnip",  "Open-source Vulkan driver")
                }

                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Divider(color = com.winlator.star.ui.theme.Divider)
                Spacer(androidx.compose.ui.Modifier.height(4.dp))

                // Credits
                AboutSection(title = "Credits") {
                    AboutRow("brunodev85",      "Winlator — original project")
                    AboutRow("MishaMixXx",      "Winlator Bionic")
                    AboutRow("The412Banner",    "Bannerlator")
                    AboutRow("ptitSeb",         "Box64")
                    AboutRow("WineHQ",          "Wine project")
                    AboutRow("Mesa / Freedreno","Turnip Vulkan driver")
                }

                Spacer(androidx.compose.ui.Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = androidx.compose.ui.Modifier.padding(bottom = 2.dp)
        )
        content()
    }
}

@Composable
private fun AboutRow(name: String, description: String) {
    Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        Spacer(androidx.compose.ui.Modifier.width(8.dp))
        Text(text = description, fontSize = 12.sp, color = com.winlator.star.ui.theme.OnSurfaceVariant)
    }
}
