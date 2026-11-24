package com.example

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.stream.StreamPipelineState
import com.example.ui.screens.PcSetupScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.StreamScreen
import com.example.ui.theme.CamLinkTheme
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ObsidianCanvas
import com.example.ui.theme.StudioSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.viewmodel.StreamViewModel

private const val ROUTE_STREAM = "stream"
private const val ROUTE_PC_SETUP = "pc_setup"
private const val ROUTE_SETTINGS = "settings"

private data class NavDestinationItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navItems = listOf(
    NavDestinationItem(
        route = ROUTE_STREAM,
        labelRes = R.string.nav_stream,
        selectedIcon = Icons.Filled.Videocam,
        unselectedIcon = Icons.Outlined.Videocam,
        testTag = "nav_tab_stream"
    ),
    NavDestinationItem(
        route = ROUTE_PC_SETUP,
        labelRes = R.string.nav_pc_setup,
        selectedIcon = Icons.Filled.Usb,
        unselectedIcon = Icons.Outlined.Usb,
        testTag = "nav_tab_pc"
    ),
    NavDestinationItem(
        route = ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_tab_settings"
    )
)

class MainActivity : ComponentActivity() {

    private var streamViewModel: StreamViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CamLinkTheme {
                val vm: StreamViewModel = viewModel()
                streamViewModel = vm

                LaunchedEffect(Unit) {
                    vm.updateDisplayRotation(readDisplayRotationDegrees())
                }

                val settings by vm.settings.collectAsStateWithLifecycle()
                val telemetry by vm.telemetry.collectAsStateWithLifecycle()
                val usbState by vm.usbState.collectAsStateWithLifecycle()

                val isStreaming = telemetry.pipelineState == StreamPipelineState.STREAMING
                val shouldKeepScreenOn = settings.keepScreenOn || isStreaming

                DisposableEffect(shouldKeepScreenOn) {
                    if (shouldKeepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose { }
                }

                CamLinkMainShell(
                    viewModel = vm,
                    settings = settings,
                    telemetry = telemetry,
                    usbState = usbState
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        streamViewModel?.updateDisplayRotation(readDisplayRotationDegrees())
    }

    private fun readDisplayRotationDegrees(): Int {
        val rot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rot) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}

@Composable
private fun CamLinkMainShell(
    viewModel: StreamViewModel,
    settings: com.example.data.StreamSettings,
    telemetry: com.example.stream.StreamTelemetry,
    usbState: com.example.stream.UsbLinkState
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianCanvas)
    ) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = StudioSurface,
                    contentColor = TextPrimary
                ) {
                    navItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTo(item.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = stringResource(item.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(item.labelRes),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricCyan,
                                selectedTextColor = ElectricCyan,
                                indicatorColor = ElectricCyan.copy(alpha = 0.16f),
                                unselectedIconColor = TextMuted,
                                unselectedTextColor = TextMuted
                            ),
                            modifier = Modifier.testTag(item.testTag)
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    CamLinkNavHost(
                        navController = navController,
                        viewModel = viewModel,
                        settings = settings,
                        telemetry = telemetry,
                        usbState = usbState,
                        onNavigatePc = { navigateTo(ROUTE_PC_SETUP) }
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = ObsidianCanvas,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    NavigationBar(
                        containerColor = StudioSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = stringResource(item.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(item.labelRes),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = ElectricCyan,
                                    selectedTextColor = ElectricCyan,
                                    indicatorColor = ElectricCyan.copy(alpha = 0.16f),
                                    unselectedIconColor = TextMuted,
                                    unselectedTextColor = TextMuted
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    CamLinkNavHost(
                        navController = navController,
                        viewModel = viewModel,
                        settings = settings,
                        telemetry = telemetry,
                        usbState = usbState,
                        onNavigatePc = { navigateTo(ROUTE_PC_SETUP) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CamLinkNavHost(
    navController: androidx.navigation.NavHostController,
    viewModel: StreamViewModel,
    settings: com.example.data.StreamSettings,
    telemetry: com.example.stream.StreamTelemetry,
    usbState: com.example.stream.UsbLinkState,
    onNavigatePc: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = ROUTE_STREAM,
        modifier = Modifier.fillMaxSize()
    ) {
        composable(ROUTE_STREAM) {
            StreamScreen(
                settings = settings,
                telemetry = telemetry,
                usbState = usbState,
                onAttachSurface = viewModel::attachPreviewSurface,
                onDetachSurface = viewModel::detachPreviewSurface,
                onPermissionGranted = viewModel::onCameraPermissionGranted,
                onToggleStream = viewModel::toggleStream,
                onFlipCamera = viewModel::flipCamera,
                onToggleTorch = viewModel::toggleTorch,
                onToggleFocusLock = viewModel::toggleFocusLock,
                onExposureChange = viewModel::setExposureEv,
                onZoomChange = viewModel::setZoomRatio,
                onTapToFocus = viewModel::triggerTapToFocus,
                onToggleGrid = viewModel::toggleGrid,
                onOpenPcSetup = onNavigatePc
            )
        }
        composable(ROUTE_PC_SETUP) {
            PcSetupScreen(
                settings = settings,
                telemetry = telemetry,
                usbState = usbState,
                onSelectProtocol = viewModel::updateProtocol,
                onToggleAutoStreamOnUsb = viewModel::updateAutoStreamOnUsb,
                onRefreshUsb = viewModel::refreshUsbDiagnostics,
                onRequestKeyFrame = viewModel::requestKeyFrame
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                settings = settings,
                telemetry = telemetry,
                onSelectResolution = viewModel::updateResolution,
                onSelectFps = viewModel::updateTargetFps,
                onBitrateChange = viewModel::updateBitrateMbps,
                onJpegQualityChange = viewModel::updateJpegQuality,
                onKeyframeIntervalChange = viewModel::updateKeyframeInterval,
                onPortApply = viewModel::updatePort,
                onLowLatencyHardwareChange = viewModel::updateLowLatencyHardware,
                onBackgroundServiceChange = viewModel::updateBackgroundService,
                onKeepScreenOnChange = viewModel::updateKeepScreenOn,
                onStabilizationChange = viewModel::updateStabilization,
                onMirrorFrontChange = viewModel::updateMirrorFront,
                onAutoStreamOnUsbChange = viewModel::updateAutoStreamOnUsb
            )
        }
    }
}
