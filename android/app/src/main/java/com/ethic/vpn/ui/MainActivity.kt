package com.ethic.vpn.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.journeyapps.barcodescanner.ScanContract
import kotlinx.coroutines.flow.MutableStateFlow
import com.journeyapps.barcodescanner.ScanOptions
import com.ethic.vpn.BuildConfig
import com.ethic.vpn.R
import com.ethic.vpn.core.DeepLink
import com.ethic.vpn.core.ServerProfile
import com.ethic.vpn.data.AppSettings
import com.ethic.vpn.data.ProfileRepository
import com.ethic.vpn.vpn.VpnState
import com.ethic.vpn.vpn.VpnStatus

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Android answers "no" without showing its dialog when another app holds always-on VPN,
        // so say where to look instead of a bare "permission needed".
        if (it.resultCode == RESULT_OK) vm.connect() else permissionDenied.value = true
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::addInput)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Links shared from a browser or messenger land here.
        if (savedInstanceState == null) intent?.dataString?.let(::handleLink)

        VpnStatus.appContext = applicationContext
        val settings = AppSettings.get(this)
        setContent {
            val lightTheme by settings.lightTheme.collectAsStateWithLifecycle()
            val devMode by settings.devMode.collectAsStateWithLifecycle()
            val russiaDirect by settings.russiaDirect.collectAsStateWithLifecycle()
            val blockAds by settings.blockAds.collectAsStateWithLifecycle()
            val dns by settings.dns.collectAsStateWithLifecycle()
            val customDns by settings.customDns.collectAsStateWithLifecycle()
            val trafficHistory by settings.traffic.collectAsStateWithLifecycle()
            val bypassApps by settings.bypassApps.collectAsStateWithLifecycle()
            val betaUpdates by settings.betaUpdates.collectAsStateWithLifecycle()
            val directSites by settings.directSites.collectAsStateWithLifecycle()
            val autoFastest by settings.autoFastest.collectAsStateWithLifecycle()
            val proxySites by settings.proxySites.collectAsStateWithLifecycle()
            EthicTheme(light = lightTheme) {
                val profiles by vm.profiles.collectAsStateWithLifecycle()
                val vpn by vm.vpn.collectAsStateWithLifecycle()
                val pings by vm.pings.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                val message by vm.message.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableIntStateOf(0) }
                var showAdd by remember { mutableStateOf(false) }

                LaunchedEffect(message) {
                    message?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_SHORT).show()
                        vm.consumeMessage()
                    }
                }

                val homeLocation by vm.homeLocation.collectAsStateWithLifecycle()
                val visibleLocation by vm.visibleLocation.collectAsStateWithLifecycle()
                val serverLocations by vm.serverLocations.collectAsStateWithLifecycle()
                val traffic by vm.traffic.collectAsStateWithLifecycle()

                // Servers and settings open from the panel on the home screen; Back returns to it.
                BackHandler(enabled = tab != 0) { tab = 0 }
                when (tab) {
                    0 -> HomeScreen(
                        state = profiles,
                        vpn = vpn,
                        homeLocation = homeLocation,
                        visibleLocation = visibleLocation,
                        serverLocations = serverLocations,
                        pings = pings,
                        traffic = traffic,
                        onPing = { vm.pingAll() },
                        onToggle = ::toggle,
                        onAdd = { showAdd = true },
                        onSelect = vm::select,
                        onOpenServers = { tab = 1 },
                        onOpenSettings = { tab = 2 },
                    )
                    else -> Column(Modifier.fillMaxSize().background(Brand.Background).systemBarsPadding()) {
                        TextButton(onClick = { tab = 0 }, modifier = Modifier.padding(start = 4.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                            Text(stringResource(R.string.tab_home))
                        }
                        if (tab == 2) {
                            SettingsScreen(
                                lightTheme = lightTheme,
                                devMode = devMode,
                                hwid = ProfileRepository.get(this@MainActivity).hwid,
                                selected = profiles.selected,
                                russiaDirect = russiaDirect,
                                blockAds = blockAds,
                                dns = dns,
                                customDns = customDns,
                                traffic = trafficHistory,
                                onDns = { choice, custom ->
                                    if (choice != dns || custom != customDns) {
                                        settings.setDns(choice, custom)
                                        vm.reconnectIfActive()
                                    }
                                },
                                bypassApps = bypassApps,
                                betaUpdates = betaUpdates,
                                directSites = directSites,
                                autoFastest = autoFastest,
                                onAutoFastest = settings::setAutoFastest,
                                proxySites = proxySites,
                                onTheme = settings::setLightTheme,
                                onRussiaDirect = {
                                    settings.setRussiaDirect(it)
                                    vm.reconnectIfActive()
                                },
                                onBlockAds = {
                                    settings.setBlockAds(it)
                                    vm.reconnectIfActive()
                                },
                                onBypassApps = {
                                    if (it != bypassApps) {
                                        settings.setBypassApps(it)
                                        vm.reconnectIfActive()
                                    }
                                },
                                onCheckUpdate = { vm.checkForUpdate(manual = true) },
                                onBetaUpdates = settings::setBetaUpdates,
                                onDirectSites = {
                                    settings.setDirectSites(it)
                                    vm.reconnectIfActive()
                                },
                                onProxySites = {
                                    settings.setProxySites(it)
                                    vm.reconnectIfActive()
                                },
                                onUnlock = settings::unlockDevMode,
                                onDisableDevMode = { settings.setDevMode(false) },
                            )
                        } else {
                            ServersScreen(
                                state = profiles,
                                pings = pings,
                                busy = busy,
                                onSelect = vm::select,
                                onRemove = vm::remove,
                                onPing = vm::pingAll,
                                onRefresh = { vm.refreshSubscription() },
                                onRemoveSubscription = vm::removeSubscription,
                                onAdd = { showAdd = true },
                            )
                        }
                    }
                }

                val offer by clipboardOffer.collectAsStateWithLifecycle()
                offer?.let { text ->
                    AlertDialog(
                        onDismissRequest = { clipboardOffer.value = null },
                        title = { Text(stringResource(R.string.clipboard_offer_title)) },
                        text = { Text(stringResource(R.string.clipboard_offer_text, text.take(80))) },
                        confirmButton = {
                            TextButton(onClick = { clipboardOffer.value = null; vm.addInput(text) }) {
                                Text(stringResource(R.string.clipboard_offer_add))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { clipboardOffer.value = null }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }

                val denied by permissionDenied.collectAsStateWithLifecycle()
                if (denied) {
                    AlertDialog(
                        onDismissRequest = { permissionDenied.value = false },
                        title = { Text(stringResource(R.string.vpn_denied_title)) },
                        text = { Text(stringResource(R.string.vpn_denied_text)) },
                        confirmButton = {
                            TextButton(onClick = {
                                permissionDenied.value = false
                                runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                            }) { Text(stringResource(R.string.vpn_denied_settings)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { permissionDenied.value = false }) { Text(stringResource(R.string.cancel)) }
                        },
                    )
                }

                val whatsNew by vm.whatsNew.collectAsStateWithLifecycle()
                whatsNew?.let { notes -> WhatsNewDialog(notes, onClose = vm::dismissWhatsNew) }

                // First launch with no servers yet: a short introduction over everything else.
                var onboarding by remember { mutableStateOf(!settings.onboardingSeen && profiles.profiles.isEmpty()) }
                if (onboarding) {
                    BackHandler { }
                    OnboardingScreen {
                        settings.onboardingSeen = true
                        onboarding = false
                    }
                }

                val update by vm.update.collectAsStateWithLifecycle()
                val updateProgress by vm.updateProgress.collectAsStateWithLifecycle()
                update?.let { release ->
                    val downloading = updateProgress != null
                    AlertDialog(
                        onDismissRequest = { if (!downloading) vm.dismissUpdate() },
                        title = { Text(stringResource(R.string.update_title)) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.update_text, release.version, BuildConfig.VERSION_NAME))
                                updateProgress?.let { progress ->
                                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                                    Text(stringResource(R.string.update_downloading, (progress * 100).toInt()))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { vm.installUpdate(release) }, enabled = !downloading) {
                                Text(stringResource(R.string.update_download))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = vm::dismissUpdate, enabled = !downloading) { Text(stringResource(R.string.update_later)) }
                        },
                    )
                }

                // Launch animation over everything; shown once per start of the app.
                var splash by rememberSaveable { mutableStateOf(true) }
                if (splash) SplashOverlay { splash = false }

                if (showAdd) {
                    AddSheet(
                        busy = busy,
                        onDismiss = { showAdd = false },
                        onSubmit = { text ->
                            vm.addInput(text)
                            showAdd = false
                        },
                        onScan = {
                            showAdd = false
                            qrScanner.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt(getString(R.string.scan_prompt))
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(::handleLink)
    }

    /** Set by ethicvpn://add without a link: the subscription is in the clipboard. */
    private var importFromClipboard = false

    /** Shows why the VPN consent failed and a way to the system VPN settings. */
    private val permissionDenied = MutableStateFlow(false)

    /** The clipboard text the "add subscription?" dialog offers; null hides it. */
    private val clipboardOffer = MutableStateFlow<String?>(null)

    private fun handleLink(link: String) {
        if (DeepLink.wantsClipboard(link)) {
            importFromClipboard = true
            if (hasWindowFocus()) onWindowFocusChanged(true)
        } else {
            vm.addInput(link)
        }
    }

    // Android 10+ only lets the focused app read the clipboard, so this waits for focus. After
    // the bot's link the copied text is always offered; otherwise only a link, only while there
    // are no servers yet, and each text once.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val force = importFromClipboard
        importFromClipboard = false
        // Reading the clipboard makes Android 12+ show a "pasted" notice, so skip it when there is nothing to offer.
        if (!force && vm.profiles.value.profiles.isNotEmpty()) return
        val clip = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (clip.isNullOrEmpty()) {
            if (force) Toast.makeText(this, R.string.msg_clipboard_empty, Toast.LENGTH_LONG).show()
            return
        }
        if (!force) {
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            val looksLikeLink = clip.startsWith("http", ignoreCase = true) || ServerProfile.isShareLink(clip)
            if (!looksLikeLink || prefs.getString("clipboardOffered", null) == clip) return
            prefs.edit().putString("clipboardOffered", clip).apply()
        }
        clipboardOffer.value = clip
    }

    private fun toggle() {
        when (vm.vpn.value) {
            is VpnState.Connected, is VpnState.Connecting -> vm.disconnect()
            else -> {
                if (vm.profiles.value.selected == null) {
                    Toast.makeText(this, R.string.error_no_server, Toast.LENGTH_SHORT).show()
                    return
                }
                lifecycleScope.launch {
                    if (AppSettings.get(this@MainActivity).autoFastest.value) vm.selectFastest()
                    val consent = VpnService.prepare(this@MainActivity)
                    if (consent != null) vpnPermission.launch(consent) else vm.connect()
                }
            }
        }
    }
}
