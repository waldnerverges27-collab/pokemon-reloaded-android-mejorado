package com.ahjin.pr.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ahjin.pr.Emulator
import com.ahjin.pr.GameRepository
import com.ahjin.pr.Preferences
import com.ahjin.pr.Update
import com.ahjin.pr.ZipIntake
import com.ahjin.pr.installUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.ahjin.pr.SaveBackups

/** Screens reached from a tab rather than being tabs themselves. */
private enum class Pushed { About, Controls, Layout, MySaves, Extras, Backups, Help }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun App(repo: GameRepository, prefs: Preferences, incoming: android.net.Uri? = null, onIncomingClosed: () -> Unit = {}) {
    var onboarded by remember { mutableStateOf(prefs.onboardingDone) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var pushed by remember { mutableStateOf<Pushed?>(null) }
    // Bumped whenever a tab comes into view, so the pages re-read the game's state. Without it,
    // deleting the game in Ajustes left "Jugar" enabled back in Inicio.
    var revision by remember { mutableStateOf(0) }
    // The engine spends several seconds loading the game's data behind a window the size of a stamp.
    // This covers that gap with something that says what is happening, and goes away by itself when
    // the launcher comes back to the front.
    var launching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // A zip opened from a file manager ("Abrir con"). Its dialog sits above everything, the first-run
    // screens included, and when an import ends the screens behind it read the game again.
    val imports by ZipIntake.changes.collectAsState()
    LaunchedEffect(imports) { if (imports > 0) revision++ }
    incoming?.let { IncomingZipDialog(it, repo, onClose = onIncomingClosed) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) launching = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Swiping between the tabs is more comfortable than aiming at a button, so the three of them
    // are pages of a pager. The bar and the pager are kept in step in both directions.
    val pagerState = rememberPagerState { Tab.entries.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            tab = Tab.entries[page]
            revision++
        }
    }

    // Android 17 does not auto-grant INTERNET, so it has to be requested at runtime or the app
    // cannot open a single socket (socket() fails with EPERM).
    val context = LocalContext.current
    var network by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.INTERNET) == PackageManager.PERMISSION_GRANTED)
    }
    val requestNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        network = granted
    }
    LaunchedEffect(Unit) {
        if (!network) requestNetwork.launch(Manifest.permission.INTERNET)
        // Make sure the bundled MIDI soundfont is next to the game (copied on first run).
        repo.prepare()
    }

    // A newer build, offered once per run. Nothing is downloaded until the player says so.
    var update by remember { mutableStateOf<Update.Release?>(null) }
    var updating by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(0L) }
    var total by remember { mutableStateOf(0L) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        if (installed.isNotEmpty()) {
            Update.check(installed).getOrNull()?.let { update = it }
        }
    }

    update?.let { release ->
        UpdateDialog(
            release = release,
            busy = updating,
            written = written,
            total = total,
            problem = problem,
            onDownload = {
                val url = release.apkUrl
                if (url == null) {
                    // No APK attached to the release: the page is the honest fallback.
                    openInBrowser(context, release.pageUrl)
                    update = null
                } else {
                    updating = true
                    written = 0
                    total = 0
                    problem = null
                    scope.launch {
                        val result = Update.download(url, java.io.File(context.cacheDir, "update.apk")) { done, size ->
                            written = done
                            total = size
                        }
                        updating = false
                        val file = result.getOrNull()
                        if (file == null) {
                            // It used to fail in silence and open the browser, which looked exactly
                            // like nothing happening. Now it says so.
                            problem = "No pude descargar la actualizacion: ${result.exceptionOrNull()?.message}"
                        } else {
                            problem = Update.verify(context, file, release)
                            if (problem == null) {
                                // The installer replaces the app; the saves are copied first, just in case.
                                runCatching { repo.backup(SaveBackups.Reason.BEFORE_UPDATE) }
                                problem = installUpdate(context, file)
                            }
                            if (problem == null) {
                                update = null
                            } else if (problem!!.startsWith("La descarga") || problem!!.startsWith("El archivo")) {
                                file.delete()
                            }
                        }
                    }
                }
            },
            onDismiss = {
                update = null
                problem = null
            },
            onOpenReleases = {
                openInBrowser(context, Update.RELEASES_PAGE)
                update = null
            },
        )
    }

    if (!onboarded) {
        Onboarding(
            repo = repo,
            prefs = prefs,
            refresh = imports,
            onFinish = { onboarded = true },
        )
        return
    }

    BackHandler(enabled = pushed != null) { pushed = null }

    when (pushed) {
        Pushed.About -> AboutScreen(onBack = { pushed = null })
        Pushed.Controls -> ControlsScreen(
            prefs = prefs,
            filesDir = context.filesDir,
            onBack = { pushed = null },
        )
        Pushed.Layout -> ControlsLayoutScreen(filesDir = context.filesDir, onBack = { pushed = null })
        Pushed.MySaves -> MySavesScreen(repo = repo, onBack = { pushed = null })
        Pushed.Extras -> ExtrasScreen(prefs = prefs, filesDir = context.filesDir, onBack = { pushed = null })
        Pushed.Backups -> BackupsScreen(repo = repo, prefs = prefs, onBack = { pushed = null })
        Pushed.Help -> HelpScreen(onBack = { pushed = null })
        null -> Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (Tab.entries[page]) {
                    Tab.Home -> HomeScreen(
                        repo,
                        revision = revision,
                        networkGranted = network,
                        onRequestNetwork = { requestNetwork.launch(Manifest.permission.INTERNET) },
                        onLog = { scope.launch { pagerState.animateScrollToPage(Tab.Log.ordinal) } },
                        onAbout = { pushed = Pushed.About },
                        onLaunch = {
                            launching = true
                            scope.launch {
                                // The copy is quick (the saves are small) and is only made when they
                                // changed. Whatever happens to it, the game starts.
                                if (prefs.autoBackup) {
                                    runCatching { withTimeoutOrNull(4_000) { repo.backup(SaveBackups.Reason.PLAY) } }
                                }
                                Emulator.start(context)
                            }
                        },
                        launching = launching,
                        onSettings = { scope.launch { pagerState.animateScrollToPage(Tab.Settings.ordinal) } },
                    )
                    Tab.Log -> LogScreen(repo)
                    Tab.Settings -> SettingsScreen(
                        repo,
                        prefs,
                        revision = revision,
                        onLog = { scope.launch { pagerState.animateScrollToPage(Tab.Log.ordinal) } },
                        onAbout = { pushed = Pushed.About },
                        onControls = { pushed = Pushed.Controls },
                        onLayout = { pushed = Pushed.Layout },
                        onExtras = { pushed = Pushed.Extras },
                        onMySaves = { pushed = Pushed.MySaves },
                        onBackups = { pushed = Pushed.Backups },
                        onHelp = { pushed = Pushed.Help },
                        onUpdateFound = { update = it },
                    )
                }
            }
            // The bar floats over the pages instead of taking a strip of its own, so nothing is
            // cut off at the bottom and there is no band of a different colour behind it.
            ReloadedBottomNav(
                current = tab,
                onSelect = { target ->
                    // A tap animates across instead of jumping, so it reads the same as a swipe.
                    scope.launch { pagerState.animateScrollToPage(target.ordinal) }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
