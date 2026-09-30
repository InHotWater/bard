package com.stan.libbylight.screens

import android.util.Log
import android.app.Activity
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.viewinterop.AndroidView
import com.stan.libbylight.BuildConfig
import com.stan.libbylight.LibbyBridge
import com.stan.libbylight.LibbyPlaybackForegroundService
import com.stan.libbylight.LibbyWebPlayer
import com.stan.libbylight.library.Audiobook
import com.stan.libbylight.library.AudiobookProgressStore
import com.stan.libbylight.library.AudiobookSource
import com.stan.libbylight.library.LocalBookRepository
import com.stan.libbylight.library.LocalScanResult
import com.stan.libbylight.library.LoanItem
import com.stan.libbylight.library.PersistedActiveAudiobook
import com.stan.libbylight.library.RssFeed
import com.stan.libbylight.library.RssDownloadManager
import com.stan.libbylight.library.RssDownloadStatus
import com.stan.libbylight.library.RssFeedRepository
import com.stan.libbylight.library.RssFeedResult
import com.stan.libbylight.libby.LibbyConnectCoordinator
import com.stan.libbylight.libby.LibbyConnectState
import com.stan.libbylight.libby.LibbySessionState
import com.stan.libbylight.player.PlayerState
import com.stan.libbylight.player.PlayerReadiness
import com.stan.libbylight.player.LocalPlaybackController
import com.stan.libbylight.player.BardMediaSessionManager
import com.stan.libbylight.ui.LightBarButton
import com.stan.libbylight.ui.LightBottomBar
import com.stan.libbylight.ui.LightIcon
import com.stan.libbylight.ui.LightIcons
import com.stan.libbylight.ui.LightUrlInputEditor
import com.stan.libbylight.ui.LightLazyScrollView
import com.stan.libbylight.ui.LightScrollView
import com.stan.libbylight.ui.LightText
import com.stan.libbylight.ui.LightTextVariant
import com.stan.libbylight.ui.LightTheme
import com.stan.libbylight.ui.LightThemeTokens
import com.stan.libbylight.ui.LightTopBar
import com.stan.libbylight.ui.LightTopBarCenter
import com.stan.libbylight.ui.gridUnitsAsDp
import com.stan.libbylight.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlin.math.roundToInt

private const val TAG = "LibbyLight"

private enum class LibbyLibraryReadiness {
    NotStarted,
    Connecting,
    Hydrating,
    Ready,
    Failed,
    Disconnected,
}

@Composable
fun PlayerDebugScreen() {
    val persistedActiveAtStartup = remember { AudiobookProgressStore.lastActiveAudiobook() }
    val persistedActiveRecord by AudiobookProgressStore.activeAudiobook.collectAsState()
    var state by remember {
        mutableStateOf(
            persistedActiveAtStartup
                ?.takeIf { it.source == AudiobookSource.Libby }
                ?.toRememberedPlayerState()
                ?: PlayerState(),
        )
    }
    val localPlayerState by LocalPlaybackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    var currentUrl by remember { mutableStateOf("") }
    val connectState by LibbyConnectCoordinator.connectState.collectAsState()
    val libbySessionState by LibbyConnectCoordinator.sessionState.collectAsState()
    val libbyRendererFailed by LibbyWebPlayer.rendererFailed.collectAsState()
    val libbyPlaybackHostFailed by LibbyPlaybackForegroundService.startFailed.collectAsState()
    val sessionReady = libbySessionState == LibbySessionState.Connected
    val currentSessionReady by rememberUpdatedState(sessionReady)
    var showBooks by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showLibbySource by remember { mutableStateOf(false) }
    var showLibbyConnect by remember { mutableStateOf(false) }
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showLocalBooks by remember { mutableStateOf(false) }
    var showRssFeeds by remember { mutableStateOf(false) }
    var showAddRssFeed by remember { mutableStateOf(false) }
    var selectedRssFeedId by remember { mutableStateOf<String?>(null) }
    var rssFeedMessage by remember { mutableStateOf("") }
    var showVersion by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    var downloadsEditMode by remember { mutableStateOf(false) }
    var pendingDownloadRemoval by remember { mutableStateOf<Audiobook?>(null) }
    var downloadRemovalMessage by remember { mutableStateOf("") }

    var loans by remember { mutableStateOf<List<LoanItem>>(emptyList()) }
    val localBooks by LocalBookRepository.books.collectAsState()
    val localScanResult by LocalBookRepository.scanResult.collectAsState()
    val localScanning by LocalBookRepository.scanning.collectAsState()
    val rssFeeds by RssFeedRepository.feeds.collectAsState()
    val rssReady by RssFeedRepository.ready.collectAsState()
    val rssRefreshingIds by RssFeedRepository.refreshingIds.collectAsState()
    val rssDownloadStates by RssDownloadManager.states.collectAsState()
    var progressRevision by remember { mutableStateOf(0) }
    var activeLoan by remember { mutableStateOf<LoanItem?>(null) }
    var activeBook by remember {
        mutableStateOf(persistedActiveAtStartup?.toAudiobook())
    }
    val context = LocalContext.current
    var pauseLibbyWhenReady by remember { mutableStateOf(false) }
    var lastActiveLoanUrl by remember { mutableStateOf("") }
    var booksLoading by remember { mutableStateOf(false) }
    var booksMessage by remember { mutableStateOf("") }
    var libbyLibraryReadiness by remember { mutableStateOf(LibbyLibraryReadiness.NotStarted) }
    var pendingNowPlaying by remember { mutableStateOf<PersistedActiveAudiobook?>(null) }
    var showNowPlayingLoading by remember { mutableStateOf(false) }
    var showCurrentBookUnavailable by remember { mutableStateOf(false) }
    var lastBridgeUrl by remember { mutableStateOf("") }
    var hydrationStartedAt by remember { mutableStateOf(0L) }
    var lastHydratedLoanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var stableShelfSnapshots by remember { mutableStateOf(0) }
    // Libby loan refresh: a manual request counter plus a tick for each return to Bard.
    var libbyRefreshRequest by remember { mutableStateOf(0) }
    var handledLibbyRefreshRequest by remember { mutableStateOf(0) }
    var libbyRefreshMessage by remember { mutableStateOf("") }
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var sawFirstResume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (sawFirstResume) resumeTick++ else sawFirstResume = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val localDeleteConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val book = pendingDownloadRemoval
        if (result.resultCode == Activity.RESULT_OK && book?.source == AudiobookSource.Local) {
            scope.launch { LocalBookRepository.scan() }
            if (activeBook?.source == book.source && activeBook?.id == book.id) {
                LocalPlaybackController.close()
                activeBook = null
            }
            pendingDownloadRemoval = null
            downloadRemovalMessage = ""
        } else if (book != null) {
            downloadRemovalMessage = "Could not remove audiobook."
        }
    }
    val liveLibbyBooks = remember(loans, progressRevision) { loans.map(LoanItem::toAudiobook) }
    val rememberedLibbyBooks = remember(progressRevision) {
        val persistedActive = persistedActiveAtStartup
            ?.takeIf { it.source == AudiobookSource.Libby }
            ?.toAudiobook()
        (AudiobookProgressStore.rememberedLibbyBooks() + listOfNotNull(persistedActive))
            .associateBy { it.id }
            .values
            .toList()
    }
    val libbyBooks = remember(liveLibbyBooks, rememberedLibbyBooks, libbyLibraryReadiness) {
        if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready) {
            liveLibbyBooks
        } else {
            (rememberedLibbyBooks + liveLibbyBooks)
                .associateBy { AudiobookProgressStore.qualifiedId(it.source, it.id) }
                .values
                .toList()
        }
    }
    val rssBooks = remember(rssFeeds, progressRevision) {
        rssFeeds.flatMap { feed ->
            feed.books.map { book ->
                val saved = AudiobookProgressStore.read(AudiobookSource.Rss, book.id)
                book.copy(
                    positionMilliseconds = saved.positionMilliseconds,
                    durationMilliseconds = saved.durationMilliseconds.takeIf { it > 0 }
                        ?: book.durationMilliseconds,
                    playbackSpeed = saved.playbackSpeed,
                    completed = saved.completed,
                    lastPlayedAtMilliseconds = saved.lastPlayedAtMilliseconds,
                    lastUpdatedAtMilliseconds = saved.lastUpdatedAtMilliseconds,
                )
            }
        }
    }
    val displayedLocalBooks = localBooks.map { book ->
        if (activeBook?.id == book.id) {
            book.copy(
                positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                    .takeIf { it > 0 } ?: book.durationMilliseconds,
            )
        } else {
            book
        }
    }
    val displayedRssBooks = rssBooks.map { book ->
        if (activeBook?.source == AudiobookSource.Rss && activeBook?.id == book.id) {
            book.copy(
                positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                    .takeIf { it > 0 } ?: book.durationMilliseconds,
            )
        } else {
            book
        }
    }
    val allBooks = (displayedLocalBooks + libbyBooks + displayedRssBooks).sortedWith(
        compareByDescending<Audiobook> { it.lastPlayedAtMilliseconds }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title },
    )
    val downloadedBooks = (
        localBooks + rssBooks.mapNotNull { book ->
            RssDownloadManager.verifiedLocalReference(book)?.let {
                book.copy(fileSizeBytes = RssDownloadManager.downloadedSizeBytes(book))
            }
        }
    ).sortedBy { it.title.lowercase() }

    fun publishLibbyShelfSnapshot(found: List<LoanItem>) {
        if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready && found == loans) return
        val ids = found.map { it.loanUrl }.toSet()
        stableShelfSnapshots = if (ids == lastHydratedLoanIds) stableShelfSnapshots + 1 else 1
        lastHydratedLoanIds = ids
        loans = found

        val hydratedLongEnough = System.currentTimeMillis() - hydrationStartedAt >= 2_000L
        if (stableShelfSnapshots < 2 || !hydratedLongEnough) return

        val live = found.map(LoanItem::toAudiobook)
        AudiobookProgressStore.reconcileRememberedLibbyBooks(live)
        libbyLibraryReadiness = LibbyLibraryReadiness.Ready
        progressRevision++
        booksLoading = false
        booksMessage = if (found.isEmpty()) "No Libby audiobooks found." else ""

        val current = activeBook?.takeIf { it.source == AudiobookSource.Libby } ?: return
        val authoritative = live.firstOrNull { it.id == current.id }
        if (authoritative != null) {
            activeBook = authoritative
        } else {
            AudiobookProgressStore.clearLastActiveIfMatches(current.source, current.id)
            activeBook = null
            if (!showBooks) showCurrentBookUnavailable = true
        }
    }

    fun performLocalScan() {
        scope.launch {
            LocalBookRepository.scan()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) performLocalScan() else LocalBookRepository.publishPermissionRequired()
    }

    fun scanLocalBooks() {
        if (LocalBookRepository.hasReadPermission()) {
            performLocalScan()
        } else {
            permissionLauncher.launch(LocalBookRepository.requiredPermission())
        }
    }

    fun playNativeExclusively() {
        val libbyWasKnownPlaying = state.isPlaying
        LibbyBridge.pause()
        scope.launch {
            var pauseConfirmed = !libbyWasKnownPlaying
            repeat(6) {
                if (pauseConfirmed) return@repeat
                delay(150)
                val observed = suspendCancellableCoroutine { continuation ->
                    LibbyBridge.getPlayerState { snapshot ->
                        if (continuation.isActive) continuation.resume(snapshot)
                    }
                }
                state = observed
                if (observed.controlsFound && !observed.isPlaying) pauseConfirmed = true
            }
            if (pauseConfirmed) LocalPlaybackController.play()
        }
    }

    fun openBook(book: Audiobook, autoPlay: Boolean = false) {
        val previous = activeBook
        val requestedId = AudiobookProgressStore.qualifiedId(book.source, book.id)
        val activeId = previous?.let { AudiobookProgressStore.qualifiedId(it.source, it.id) }
        if (requestedId == activeId) {
            AudiobookProgressStore.markOpened(book)
            progressRevision++
            showBooks = false
            if (
                book.source == AudiobookSource.Libby &&
                (!state.controlsFound || !LibbyBridge.hasActivePlayerFrame())
            ) {
                LocalPlaybackController.pause()
                pauseLibbyWhenReady = true
                val saved = AudiobookProgressStore.lastActiveAudiobook()
                state = saved?.toRememberedPlayerState() ?: state.copy(
                    isPlaying = false,
                    controlsFound = false,
                    diagnostic = "Connecting to Libby",
                    readiness = PlayerReadiness.Preparing,
                )
                LibbyWebPlayer.openLoan(book.playbackReference)
            }
            return
        }
        if (previous?.source == AudiobookSource.Local || previous?.source == AudiobookSource.Rss) {
            LocalPlaybackController.persistProgress()
            if (previous.source == AudiobookSource.Local) {
                LocalBookRepository.updateBook(
                    previous.copy(
                        positionMilliseconds = (localPlayerState.positionSeconds * 1000).toLong(),
                        durationMilliseconds = (localPlayerState.durationSeconds * 1000).toLong()
                            .takeIf { it > 0 } ?: previous.durationMilliseconds,
                    ),
                )
            }
        } else if (previous?.source == AudiobookSource.Libby && state.durationSeconds > 0) {
            AudiobookProgressStore.recordSnapshot(previous, state, force = true)
        }
        val opened = AudiobookProgressStore.markOpened(book)
        val openedBook = book.copy(
            lastPlayedAtMilliseconds = opened.lastPlayedAtMilliseconds,
            lastUpdatedAtMilliseconds = opened.lastUpdatedAtMilliseconds,
        )
        activeBook = openedBook
        progressRevision++
        showBooks = false
        when (book.source) {
            AudiobookSource.Local, AudiobookSource.Rss -> {
                LibbyBridge.pause()
                if (book.source == AudiobookSource.Local) LocalBookRepository.updateBook(openedBook)
                LocalPlaybackController.open(openedBook, autoPlay = autoPlay)
            }
            AudiobookSource.Libby -> {
                LocalPlaybackController.pause()
                pauseLibbyWhenReady = true
                activeLoan = loans.firstOrNull { it.loanUrl == book.playbackReference }
                lastActiveLoanUrl = book.playbackReference
                val saved = AudiobookProgressStore.read(book.source, book.id)
                state = PlayerState(
                    title = book.title,
                    positionSeconds = saved.positionMilliseconds / 1000.0,
                    durationSeconds = saved.durationMilliseconds / 1000.0,
                    playbackSpeed = saved.playbackSpeed.toDouble(),
                    diagnostic = "Connecting to audiobook player…",
                )
                LibbyWebPlayer.openLoan(book.playbackReference)
            }
        }
    }

    fun restoreLastPlayer() {
        val persisted = AudiobookProgressStore.lastActiveAudiobook()
        if (persisted == null) {
            showCurrentBookUnavailable = true
            return
        }
        val current = activeBook
        if (current != null && AudiobookProgressStore.qualifiedId(current.source, current.id) == persisted.qualifiedId) {
            showBooks = false
            if (current.source == AudiobookSource.Libby) {
                val liveRoute = LibbyWebPlayer.requireWebView().url.orEmpty()
                    .substringBefore('?')
                    .substringBefore('#')
                val rememberedRoute = current.playbackReference
                    .substringBefore('?')
                    .substringBefore('#')
                if (
                    liveRoute != rememberedRoute ||
                    !state.controlsFound ||
                    !LibbyBridge.hasActivePlayerFrame()
                ) {
                    LocalPlaybackController.pause()
                    pauseLibbyWhenReady = true
                    state = persisted.toRememberedPlayerState()
                    LibbyWebPlayer.openLoan(current.playbackReference)
                }
            }
            return
        }
        pendingNowPlaying = persisted
        showNowPlayingLoading = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Libby remains mounted and rendered beneath Bard's native UI.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                LibbyWebPlayer.detachFromCurrentParent()
                LibbyWebPlayer.attachToActivity(context).also { webView ->
                    val url = webView.url.orEmpty()
                    if (url.isBlank() || url == "about:blank") {
                        Log.d(TAG, "WebView was blank; loading Libby")
                        LibbyWebPlayer.loadLoginPage()
                    }
                }
            },
            update = { webView -> webView.clearFocus() },
        )

        when {
            pendingDownloadRemoval != null -> {
                val book = pendingDownloadRemoval!!
                DownloadRemovalConfirmationScreen(
                    book = book,
                    message = downloadRemovalMessage,
                    onBack = {
                        pendingDownloadRemoval = null
                        downloadRemovalMessage = ""
                    },
                    onConfirm = {
                        downloadRemovalMessage = ""
                        scope.launch {
                            val removed = when (book.source) {
                                AudiobookSource.Local -> LocalBookRepository.deleteBook(book)
                                AudiobookSource.Rss -> {
                                    RssDownloadManager.removeDownload(book)
                                    true
                                }
                                AudiobookSource.Libby -> false
                            }
                            if (removed) {
                                if (
                                    activeBook?.source == book.source &&
                                    activeBook?.id == book.id &&
                                    book.source == AudiobookSource.Local
                                ) {
                                    LocalPlaybackController.close()
                                    activeBook = null
                                }
                                pendingDownloadRemoval = null
                            } else if (book.source == AudiobookSource.Local) {
                                val request = runCatching {
                                    MediaStore.createDeleteRequest(
                                        context.contentResolver,
                                        book.parts.map { Uri.parse(it.playbackReference) }
                                            .ifEmpty { listOf(Uri.parse(book.playbackReference)) },
                                    )
                                }.getOrNull()
                                if (request != null) {
                                    localDeleteConsentLauncher.launch(
                                        IntentSenderRequest.Builder(request.intentSender).build(),
                                    )
                                } else {
                                    downloadRemovalMessage = "Could not remove audiobook."
                                }
                            } else {
                                downloadRemovalMessage = "Could not remove audiobook."
                            }
                        }
                    },
                )
            }

            showCurrentBookUnavailable -> {
                CurrentBookStatusScreen(
                    message = "No current open book",
                    onBack = {
                        showCurrentBookUnavailable = false
                        showBooks = true
                    },
                )
            }

            showNowPlayingLoading -> {
                CurrentBookStatusScreen(
                    message = "Opening current book…",
                    onBack = {
                        pendingNowPlaying = null
                        showNowPlayingLoading = false
                        showBooks = true
                    },
                )
            }

            showLibbyConnect -> {
                LibbyConnectScreen(
                    state = connectState,
                    onBack = {
                        LibbyConnectCoordinator.cancelConnection()
                        showLibbyConnect = false
                    },
                    onNewCode = LibbyConnectCoordinator::requestNewCode,
                    onRetry = LibbyConnectCoordinator::startConnection,
                    onCancel = {
                        LibbyConnectCoordinator.cancelConnection()
                        showLibbyConnect = false
                    },
                )
            }

            showDisconnectConfirmation -> {
                DisconnectLibbyScreen(
                    onBack = { showDisconnectConfirmation = false },
                    onDisconnect = {
                        LibbyConnectCoordinator.disconnect()
                        loans = emptyList()
                        activeLoan = null
                        lastActiveLoanUrl = ""
                        state = PlayerState()
                        showDisconnectConfirmation = false
                        showLibbySource = false
                        showSettings = false
                        showBooks = true
                    },
                    onCancel = { showDisconnectConfirmation = false },
                )
            }

            showLocalBooks -> {
                LocalBooksScreen(
                    result = localScanResult,
                    scanning = localScanning,
                    onBack = { showLocalBooks = false },
                    onScan = ::scanLocalBooks,
                )
            }

            showAddRssFeed -> {
                AddRssFeedScreen(
                    onBack = { showAddRssFeed = false },
                    onSubmit = { url ->
                        when (val result = RssFeedRepository.addFeed(url)) {
                            is RssFeedResult.Success -> {
                                showAddRssFeed = false
                                null
                            }
                            is RssFeedResult.Error -> result.userMessage
                        }
                    },
                )
            }

            selectedRssFeedId != null -> {
                val feed = rssFeeds.firstOrNull { it.id == selectedRssFeedId }
                if (feed == null) {
                    selectedRssFeedId = null
                } else {
                    RssFeedDetailScreen(
                        feed = feed,
                        refreshing = feed.id in rssRefreshingIds,
                        message = rssFeedMessage,
                        onBack = { selectedRssFeedId = null },
                        onRefresh = {
                            rssFeedMessage = ""
                            scope.launch {
                                rssFeedMessage = when (val result = RssFeedRepository.refreshFeed(feed.id)) {
                                    is RssFeedResult.Success -> "Feed refreshed."
                                    is RssFeedResult.Error -> result.userMessage
                                }
                            }
                        },
                        onRemove = {
                            scope.launch {
                                RssFeedRepository.removeFeed(feed.id)
                                selectedRssFeedId = null
                            }
                        },
                    )
                }
            }

            showRssFeeds -> {
                RssFeedsScreen(
                    feeds = rssFeeds,
                    onBack = { showRssFeeds = false },
                    onAddFeed = { showAddRssFeed = true },
                    onOpenFeed = {
                        rssFeedMessage = ""
                        selectedRssFeedId = it.id
                    },
                )
            }

            showLibbySource -> {
                LibbySourceScreen(
                    connected = sessionReady,
                    refreshMessage = libbyRefreshMessage,
                    onRefresh = {
                        libbyRefreshMessage = "Refreshing loans…"
                        libbyRefreshRequest++
                    },
                    onBack = {
                        libbyRefreshMessage = ""
                        showLibbySource = false
                    },
                    onConnect = {
                        showLibbyConnect = true
                        LibbyConnectCoordinator.startConnection()
                    },
                    onDisconnect = { showDisconnectConfirmation = true },
                )
            }

            showVersion -> {
                VersionScreen(onBack = { showVersion = false })
            }

            showDownloads -> {
                DownloadsScreen(
                    books = downloadedBooks,
                    editMode = downloadsEditMode,
                    onBack = {
                        downloadsEditMode = false
                        showDownloads = false
                    },
                    onToggleEdit = { downloadsEditMode = !downloadsEditMode },
                    onOpenBook = ::openBook,
                    onRemoveBook = { pendingDownloadRemoval = it },
                )
            }

            showSettings -> {
                SettingsScreen(
                    onBack = { showSettings = false },
                    onOpenLibby = { showLibbySource = true },
                    onOpenLocalBooks = { showLocalBooks = true },
                    onOpenRssFeeds = { showRssFeeds = true },
                    onOpenVersion = { showVersion = true },
                )
            }

            showBooks -> {
                BooksScreen(
                    books = allBooks,
                    loading = booksLoading,
                    message = booksMessage,
                    onSettings = { showSettings = true },
                    onDownloads = { showDownloads = true },
                    onPlayer = persistedActiveRecord?.let { ::restoreLastPlayer },
                    onOpenBook = ::openBook,
                )
            }

            else -> {
                val nativeActive = activeBook?.source != AudiobookSource.Libby
                val rssDownloadState = activeBook
                    ?.takeIf { it.source == AudiobookSource.Rss }
                    ?.let { rssDownloadStates[it.id] }
                val activeRssFeedId = activeBook
                    ?.takeIf { it.source == AudiobookSource.Rss }
                    ?.let { book -> rssFeeds.firstOrNull { feed -> feed.books.any { it.id == book.id } }?.id }
                PlayerScreen(
                    state = if (nativeActive) localPlayerState else state,
                    book = activeBook,
                    onPlay = if (nativeActive) {
                        ::playNativeExclusively
                    } else {
                        {
                            LocalPlaybackController.pause()
                            LibbyBridge.play()
                        }
                    },
                    onPause = if (nativeActive) {
                        LocalPlaybackController::pause
                    } else {
                        {
                            LibbyBridge.pause()
                            activeBook?.takeIf { it.source == AudiobookSource.Libby }?.let {
                                AudiobookProgressStore.recordSnapshot(it, state, force = true)
                            }
                            Unit
                        }
                    },
                    onBack15 = if (nativeActive) {
                        { LocalPlaybackController.seekBy(-15_000) }
                    } else LibbyBridge::back15,
                    onForward15 = if (nativeActive) {
                        { LocalPlaybackController.seekBy(15_000) }
                    } else LibbyBridge::forward15,
                    onSeekTo = if (nativeActive) {
                        LocalPlaybackController::seekTo
                    } else {
                        { position ->
                            LibbyBridge.seekTo(position)
                            activeBook?.takeIf { it.source == AudiobookSource.Libby }?.let {
                                AudiobookProgressStore.recordSnapshot(
                                    it,
                                    state.copy(positionSeconds = position / 1000.0),
                                    force = true,
                                )
                            }
                            Unit
                        }
                    },
                    onSetSpeed = if (nativeActive) LocalPlaybackController::setSpeed else LibbyBridge::setSpeed,
                    downloadStatus = when (activeBook?.source) {
                        AudiobookSource.Local -> null
                        AudiobookSource.Rss -> rssDownloadState?.status ?: RssDownloadStatus.NotDownloaded
                        AudiobookSource.Libby, null -> null
                    },
                    onDownload = activeBook
                        ?.takeIf { it.source == AudiobookSource.Rss && activeRssFeedId != null }
                        ?.let { book -> { RssDownloadManager.start(book, activeRssFeedId!!) } },
                    statusText = when {
                        activeBook?.source == AudiobookSource.Libby && libbyPlaybackHostFailed ->
                            "Could not keep Libby playback active"
                        activeBook?.source == AudiobookSource.Libby && libbyRendererFailed ->
                            "Libby player disconnected"
                        activeBook?.source == AudiobookSource.Libby &&
                            state.readiness != PlayerReadiness.Ready -> "Connecting to Libby"
                        localPlayerState.readiness == PlayerReadiness.Preparing &&
                            activeBook?.source == AudiobookSource.Rss -> "Preparing…"
                        localPlayerState.readiness == PlayerReadiness.Buffering &&
                            activeBook?.source == AudiobookSource.Rss -> "Buffering"
                        rssDownloadState?.status == RssDownloadStatus.Downloading -> "Downloading"
                        rssDownloadState?.status == RssDownloadStatus.Downloaded -> "Downloaded"
                        rssDownloadState?.lastError == "Not enough storage" -> "Not enough storage"
                        localPlayerState.readiness == PlayerReadiness.Error &&
                            activeBook?.source == AudiobookSource.Rss -> "Not available offline"
                        rssDownloadState?.status == RssDownloadStatus.Failed -> "Download failed"
                        else -> ""
                    },
                    onBooks = {
                        showBooks = true
                        booksLoading = false
                        booksMessage = ""
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        LibbyConnectCoordinator.refreshSessionState()
        while (true) {
            val webView = LibbyWebPlayer.requireWebView()
            currentUrl = webView.url.orEmpty()

            if (currentUrl != lastBridgeUrl) {
                lastBridgeUrl = currentUrl
                LibbyBridge.onTopLevelUrlChanged(currentUrl)

                if (currentUrl.contains("/open/loan/")) {
                    showBooks = false
                    lastActiveLoanUrl = currentUrl.substringBefore('?').substringBefore('#')
                    activeLoan = loans.firstOrNull { currentUrl.startsWith(it.loanUrl) } ?: activeLoan
                    activeBook = activeLoan?.toAudiobook()
                        ?: activeBook
                    state = state.copy(
                        isPlaying = false,
                        controlsFound = false,
                        diagnostic = "Connecting to Libby",
                        readiness = PlayerReadiness.Preparing,
                    )
                }
            }

            if (currentSessionReady && currentUrl.contains("/open/loan/")) {
                LibbyBridge.getPlayerState { newState ->
                    state = newState
                    if (pauseLibbyWhenReady && (newState.controlsFound || newState.durationSeconds > 0)) {
                        // A freshly opened Libby loan may report a ready-but-paused snapshot before
                        // its own delayed autoplay begins. Send one unconditional pause while the
                        // player frame is authoritative, then retire the cold-restore guard.
                        LibbyBridge.pause()
                        pauseLibbyWhenReady = false
                    }
                    activeBook?.takeIf {
                        it.source == AudiobookSource.Libby && newState.durationSeconds > 0
                    }?.let {
                        AudiobookProgressStore.recordSnapshot(it, newState)
                    }
                }
            }

            if (currentSessionReady && currentUrl.contains("/shelf")) {
                LibbyWebPlayer.scrapeAudiobookLoans { found ->
                    publishLibbyShelfSnapshot(found)
                }
            }

            delay(700)
        }
    }

    LaunchedEffect(activeBook, state, localPlayerState) {
        val current = activeBook
        BardMediaSessionManager.update(
            current,
            if (current?.source == AudiobookSource.Libby) state else localPlayerState,
        )
    }

    LaunchedEffect(libbySessionState) {
        libbyLibraryReadiness = when (libbySessionState) {
            LibbySessionState.Checking -> LibbyLibraryReadiness.Connecting
            LibbySessionState.Disconnected -> LibbyLibraryReadiness.Disconnected
            LibbySessionState.Connected -> when (libbyLibraryReadiness) {
                LibbyLibraryReadiness.Ready,
                LibbyLibraryReadiness.Hydrating -> libbyLibraryReadiness
                else -> LibbyLibraryReadiness.Connecting
            }
        }
    }

    LaunchedEffect(connectState) {
        if (connectState is LibbyConnectState.Connected) {
            delay(500)
            showLibbyConnect = false
            showLibbySource = false
            showSettings = false
            showBooks = true
            booksLoading = true
            booksMessage = "Loading books…"
            libbyLibraryReadiness = LibbyLibraryReadiness.Hydrating
            hydrationStartedAt = System.currentTimeMillis()
            stableShelfSnapshots = 0
            lastHydratedLoanIds = emptySet()
            LibbyWebPlayer.loadLibraryPage()
        }
    }

    LaunchedEffect(Unit) {
        if (LocalBookRepository.hasReadPermission()) performLocalScan()
    }

    LaunchedEffect(Unit) {
        RssFeedRepository.refreshAll()
    }

    LaunchedEffect(rssDownloadStates, localPlayerState.readiness, activeBook) {
        val book = activeBook?.takeIf { it.source == AudiobookSource.Rss } ?: return@LaunchedEffect
        if (
            localPlayerState.readiness == PlayerReadiness.Error &&
            RssDownloadManager.verifiedLocalReference(book) != null
        ) {
            LocalPlaybackController.open(book, autoPlay = false)
        }
    }

    LaunchedEffect(showBooks, sessionReady, resumeTick, libbyRefreshRequest) {
        if (!showBooks || !sessionReady) return@LaunchedEffect
        val manualRefresh = libbyRefreshRequest != handledLibbyRefreshRequest
        handledLibbyRefreshRequest = libbyRefreshRequest

        // The WebView route is authoritative. Never replace an active loan page merely
        // because the native shelf snapshot is temporarily empty during refresh/startup.
        val webViewUrl = LibbyWebPlayer.requireWebView().url.orEmpty()
        if (webViewUrl.contains("/open/loan/")) {
            val libbyPlaying = activeBook?.source == AudiobookSource.Libby && state.isPlaying
            if (!manualRefresh || libbyPlaying) {
                if (manualRefresh) libbyRefreshMessage = "Pause Libby playback, then refresh."
                currentUrl = webViewUrl
                booksLoading = false
                booksMessage = ""
                return@LaunchedEffect
            }
            // Explicit refresh while a Libby book is paused: keep its progress, then leave the
            // player page. Opening the book again reconnects Libby's player.
            activeBook?.takeIf {
                it.source == AudiobookSource.Libby && state.durationSeconds > 0
            }?.let { AudiobookProgressStore.recordSnapshot(it, state, force = true) }
        }

        booksLoading = true
        booksMessage = "Loading books…"
        libbyLibraryReadiness = LibbyLibraryReadiness.Hydrating
        hydrationStartedAt = System.currentTimeMillis()
        stableShelfSnapshots = 0
        lastHydratedLoanIds = emptySet()

        // Re-boot Libby's web app (which re-syncs loans) when asked, or when Bard returns after
        // the shelf has been open a while; otherwise only re-read the live shelf.
        val onShelf = LibbyWebPlayer.requireWebView().url.orEmpty().contains("/shelf")
        val reloaded = when {
            manualRefresh || (onShelf && LibbyWebPlayer.shelfAgeMillis() > LIBBY_SHELF_AUTO_REFRESH_MILLISECONDS) -> {
                LibbyWebPlayer.refreshLibraryPage()
                true
            }
            !onShelf -> {
                LibbyWebPlayer.loadLibraryPage()
                true
            }
            else -> false
        }
        if (reloaded) {
            // Let the reload replace the old DOM before sampling so a stale shelf is not
            // mistaken for the refreshed one.
            delay(1_200)
            hydrationStartedAt = System.currentTimeMillis()
        }

        repeat(if (reloaded) 30 else 16) {
            delay(400)
            currentUrl = LibbyWebPlayer.requireWebView().url.orEmpty()
            if (currentUrl.contains("/shelf")) {
                LibbyWebPlayer.scrapeAudiobookLoans { found ->
                    publishLibbyShelfSnapshot(found)
                }
                if (libbyLibraryReadiness == LibbyLibraryReadiness.Ready) {
                    if (manualRefresh) libbyRefreshMessage = "Loans refreshed."
                    return@LaunchedEffect
                }
            }
        }

        if (libbyLibraryReadiness != LibbyLibraryReadiness.Ready) {
            libbyLibraryReadiness = LibbyLibraryReadiness.Failed
            booksLoading = false
            booksMessage = "Could not load Libby books."
            if (manualRefresh) libbyRefreshMessage = "Could not refresh loans."
        }
    }

    LaunchedEffect(
        pendingNowPlaying,
        allBooks,
        localScanResult,
        localScanning,
        libbySessionState,
        libbyLibraryReadiness,
    ) {
        val persisted = pendingNowPlaying ?: return@LaunchedEffect
        val resolved = allBooks.firstOrNull {
            AudiobookProgressStore.qualifiedId(it.source, it.id) == persisted.qualifiedId
        }
        if (resolved != null) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            openBook(resolved, autoPlay = false)
            return@LaunchedEffect
        }

        val resolutionFinished = when (persisted.source) {
            AudiobookSource.Local -> !LocalBookRepository.hasReadPermission() ||
                (!localScanning && localScanResult != null)
            AudiobookSource.Libby -> when (libbySessionState) {
                LibbySessionState.Checking -> false
                LibbySessionState.Connected ->
                    libbyLibraryReadiness == LibbyLibraryReadiness.Ready
                LibbySessionState.Disconnected -> true
            }
            AudiobookSource.Rss -> rssReady
        }
        if (resolutionFinished) {
            pendingNowPlaying = null
            showNowPlayingLoading = false
            showCurrentBookUnavailable = true
        }
    }

    DisposableEffect(Unit) {
        onDispose { LibbyWebPlayer.detachFromCurrentParent() }
    }
}

@Composable
private fun CurrentBookStatusScreen(message: String, onBack: () -> Unit) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                contentAlignment = Alignment.Center,
            ) {
                LightText(
                    text = message,
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BardSurface(content: @Composable () -> Unit) {
    LightTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            content()
        }
    }
}

@Composable
private fun BooksScreen(
    books: List<Audiobook>,
    loading: Boolean,
    message: String,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onPlayer: (() -> Unit)?,
    onOpenBook: (Audiobook) -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            when {
                loading && books.isEmpty() -> BooksStatus("Loading books…", Modifier.weight(1f))
                message.isNotBlank() && books.isEmpty() -> BooksStatus(message, Modifier.weight(1f))
                books.isEmpty() -> BooksStatus("No audiobooks", Modifier.weight(1f))
                else -> {
                    LightLazyScrollView(
                        uniformItemHeightGridUnits = 6.5f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(
                                start = 1.5f.gridUnitsAsDp(),
                                top = 0.75f.gridUnitsAsDp(),
                            ),
                    ) {
                        items(books, key = { "${it.source}:${it.id}" }) { book ->
                            BookRow(
                                book = book,
                                rssDownloaded = book.source == AudiobookSource.Rss &&
                                    RssDownloadManager.verifiedLocalReference(book) != null,
                                onClick = { onOpenBook(book) },
                            )
                        }
                    }
                }
            }

            LightBottomBar(
                horizontalPaddingUnits = 0.5f,
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = onSettings,
                        contentDescription = "Settings",
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.DOWNLOADED_ARROW,
                        onClick = onDownloads,
                        contentDescription = "Downloads",
                    ),
                    onPlayer?.let {
                        LightBarButton.LightIcon(
                            icon = LightIcons.AUDIO_MESSAGE,
                            onClick = it,
                            contentDescription = "Now Playing",
                        )
                    },
                ),
            )
        }
    }
}

@Composable
private fun BooksStatus(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(
            text = message,
            variant = LightTextVariant.Paragraph,
            align = TextAlign.Center,
            lighten = true,
        )
    }
}

@Composable
private fun DownloadsScreen(
    books: List<Audiobook>,
    editMode: Boolean,
    onBack: () -> Unit,
    onToggleEdit: () -> Unit,
    onOpenBook: (Audiobook) -> Unit,
    onRemoveBook: (Audiobook) -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Books",
                ),
                center = LightTopBarCenter.Text("Downloads"),
                rightButton = LightBarButton.Text(
                    text = if (editMode) "DONE" else "EDIT",
                    onClick = onToggleEdit,
                ),
            )
            if (books.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 2f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "There are no audiobooks downloaded",
                        variant = LightTextVariant.Heading,
                        align = TextAlign.Center,
                    )
                }
            } else {
                LightLazyScrollView(
                    uniformItemHeightGridUnits = 7f,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(
                            start = if (editMode) 0.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                            top = 0.75f.gridUnitsAsDp(),
                        ),
                ) {
                    items(books, key = { "download:${it.source}:${it.id}" }) { book ->
                        DownloadRow(
                            book = book,
                            editMode = editMode,
                            onOpen = { onOpenBook(book) },
                            onRemove = { onRemoveBook(book) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    book: Audiobook,
    editMode: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(7f.gridUnitsAsDp())
            .then(
                if (editMode) Modifier else Modifier.lightClickable(
                    onClickLabel = "Open ${book.title}",
                    role = Role.Button,
                    onClick = onOpen,
                ),
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editMode) {
            Box(
                modifier = Modifier
                    .size(4f.gridUnitsAsDp())
                    .lightClickable(
                        onClickLabel = "Remove ${book.title}",
                        role = Role.Button,
                        onClick = onRemove,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LightIcon(
                    icon = LightIcons.CLOSE,
                    width = 1.5f,
                    height = 1.5f,
                    contentDescription = "Remove download",
                )
            }
            Spacer(Modifier.size(0.25f.gridUnitsAsDp()))
        }
        Column(modifier = Modifier.weight(1f)) {
            LightText(
                text = book.title,
                variant = LightTextVariant.Subheading,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = book.author.ifBlank { "Unknown Author" },
                variant = LightTextVariant.Detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val source = when (book.source) {
                AudiobookSource.Local -> "Local storage"
                AudiobookSource.Rss -> "RSS feed"
                AudiobookSource.Libby -> ""
            }
            val size = formatDownloadSize(book.fileSizeBytes)
            LightText(
                text = listOf(source, size).filter { it.isNotBlank() }.joinToString(" · "),
                variant = LightTextVariant.Superfine,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lighten = true,
            )
        }
    }
}

private fun formatDownloadSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val gibibyte = 1024.0 * 1024.0 * 1024.0
    val mebibyte = 1024.0 * 1024.0
    return if (bytes >= gibibyte) {
        String.format(java.util.Locale.US, "%.1f GB", bytes / gibibyte)
    } else if (bytes < mebibyte) {
        "<1 MB"
    } else {
        "${(bytes / mebibyte).roundToInt()} MB"
    }
}

@Composable
private fun DownloadRemovalConfirmationScreen(
    book: Audiobook,
    message: String,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Downloads",
                ),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = if (book.source == AudiobookSource.Local) {
                        "Remove this audiobook file from the device?"
                    } else {
                        "Remove this downloaded audiobook?"
                    },
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                    LightText(
                        text = message,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        lighten = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            LightBottomBar(
                items = listOf(
                    LightBarButton.Text("REMOVE", onClick = onConfirm),
                    LightBarButton.Text("CANCEL", onClick = onBack),
                ),
            )
        }
    }
}

@Composable
private fun BookRow(book: Audiobook, rssDownloaded: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Play ${book.title}",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                end = 1f.gridUnitsAsDp(),
                top = 0.25f.gridUnitsAsDp(),
                bottom = 0.25f.gridUnitsAsDp(),
            ),
        verticalArrangement = Arrangement.Top,
    ) {
        LightText(
            text = book.title,
            variant = LightTextVariant.Subheading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LightText(
            text = book.author.ifBlank { "Unknown Author" },
            variant = LightTextVariant.Detail,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val progress = book.progressPercent.takeIf { it > 0 }?.let { "$it%" } ?: "Not started"
        val sourceStatus = when (book.source) {
            AudiobookSource.Libby -> book.dueText
            AudiobookSource.Local -> "Local storage"
            AudiobookSource.Rss -> if (rssDownloaded) "Downloaded" else "Not downloaded"
        }
        val status = listOf(progress, sourceStatus)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        LightText(
            text = status,
            variant = LightTextVariant.Superfine,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lighten = true,
        )
    }
}

@Composable
private fun PlayerScreen(
    state: PlayerState,
    book: Audiobook?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onSeekTo: (positionMilliseconds: Long) -> Unit,
    onSetSpeed: (Double) -> Unit,
    downloadStatus: RssDownloadStatus?,
    onDownload: (() -> Unit)?,
    statusText: String,
    onBooks: () -> Unit,
) {
    var showSpeedPicker by remember { mutableStateOf(false) }
    var requestedSpeed by remember { mutableStateOf<Double?>(null) }
    var scrubProgress by remember { mutableStateOf<Float?>(null) }
    var pendingSeekMilliseconds by remember { mutableStateOf<Long?>(null) }
    val title = book?.title?.takeIf { it.isNotBlank() }
        ?: state.title.takeIf { it.isNotBlank() && it != "Libby" }
        ?: "Audiobook"
    val authoritativePositionSeconds = when {
        state.positionSeconds > 0 || state.readiness == PlayerReadiness.Ready -> state.positionSeconds
        book?.source == AudiobookSource.Rss && book.positionMilliseconds > 0 ->
            book.positionMilliseconds / 1000.0
        else -> state.positionSeconds
    }
    val liveProgress = if (state.durationSeconds > 0) {
        (authoritativePositionSeconds / state.durationSeconds).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val hasTiming = state.durationSeconds > 0
    val pendingProgress = pendingSeekMilliseconds?.let { pending ->
        if (hasTiming) (pending / (state.durationSeconds * 1000.0)).toFloat().coerceIn(0f, 1f)
        else null
    }
    val displayedProgress = scrubProgress ?: pendingProgress ?: liveProgress
    val displayedPositionSeconds = when {
        scrubProgress != null -> scrubProgress!!.toDouble() * state.durationSeconds
        pendingSeekMilliseconds != null -> pendingSeekMilliseconds!! / 1000.0
        else -> authoritativePositionSeconds
    }
    val displayedSpeed = requestedSpeed ?: state.playbackSpeed

    LaunchedEffect(state.playbackSpeed, requestedSpeed) {
        val requested = requestedSpeed ?: return@LaunchedEffect
        if (kotlin.math.abs(state.playbackSpeed - requested) < 0.01) {
            requestedSpeed = null
        }
    }

    LaunchedEffect(state.positionSeconds, pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        val actualMilliseconds = (state.positionSeconds * 1000.0).roundToLong()
        if (kotlin.math.abs(actualMilliseconds - pending) <= 2_000L) {
            pendingSeekMilliseconds = null
        }
    }

    LaunchedEffect(pendingSeekMilliseconds) {
        val pending = pendingSeekMilliseconds ?: return@LaunchedEffect
        delay(3_000)
        if (pendingSeekMilliseconds == pending) pendingSeekMilliseconds = null
    }

    BardSurface {
        if (showSpeedPicker) {
            SpeedPicker(
                currentSpeed = displayedSpeed,
                onSelect = { speed ->
                    requestedSpeed = speed
                    onSetSpeed(speed)
                    showSpeedPicker = false
                },
                onClose = { showSpeedPicker = false },
            )
            return@BardSurface
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBooks,
                    contentDescription = "Back to Books",
                ),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                val author = book?.author.orEmpty()
                if (book?.source != AudiobookSource.Rss || author.isNotBlank()) {
                    LightText(
                        text = author,
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                }
                LightText(
                    text = title,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    text = when {
                        hasTiming -> formatPlaybackTime(state.durationSeconds)
                        state.diagnostic == "This audiobook could not be played." -> state.diagnostic
                        else -> "--:--"
                    },
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.25f.gridUnitsAsDp()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (statusText.isNotBlank()) {
                        LightText(
                            text = statusText,
                            variant = LightTextVariant.Superfine,
                            align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                            lighten = true,
                        )
                    }
                }

                Spacer(
                    Modifier.height(
                        if (book?.source == AudiobookSource.Rss) {
                            0.75f.gridUnitsAsDp()
                        } else {
                            2.5f.gridUnitsAsDp()
                        },
                    ),
                )
                PlaybackProgress(
                    progress = displayedProgress,
                    enabled = hasTiming && state.readiness == PlayerReadiness.Ready,
                    onScrub = { scrubProgress = it },
                    onScrubCancelled = { scrubProgress = null },
                    onSeek = { fraction ->
                        val targetMilliseconds =
                            (state.durationSeconds * 1000.0 * fraction).roundToLong()
                                .coerceIn(0L, (state.durationSeconds * 1000.0).roundToLong())
                        scrubProgress = null
                        pendingSeekMilliseconds = targetMilliseconds
                        onSeekTo(targetMilliseconds)
                    },
                )

                if (book?.source == AudiobookSource.Rss) {
                    Spacer(Modifier.height(0.25f.gridUnitsAsDp()))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerIconAction(
                        icon = LightIcons.SKIP_BACKWARD_FIFTEEN,
                        description = "Rewind 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onBack15,
                    )
                    PlayerIconAction(
                        icon = if (state.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                        description = if (state.isPlaying) "Pause" else "Play",
                        iconWidth = 2.5f,
                        iconHeight = 2.5f,
                        touchSize = 6f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = {
                            if (state.isPlaying) onPause() else onPlay()
                        },
                    )
                    PlayerIconAction(
                        icon = LightIcons.SKIP_FORWARD_FIFTEEN,
                        description = "Forward 15 seconds",
                        iconWidth = 3f,
                        iconHeight = 3.25f,
                        enabled = state.readiness == PlayerReadiness.Ready,
                        onClick = onForward15,
                    )
                }
                Spacer(
                    Modifier.height(
                        if (book?.source == AudiobookSource.Rss) {
                            0.25f.gridUnitsAsDp()
                        } else {
                            1f.gridUnitsAsDp()
                        },
                    ),
                )
                LightText(
                    text = if (hasTiming || authoritativePositionSeconds > 0) {
                        formatPlaybackTime(displayedPositionSeconds)
                    } else {
                        "--:--"
                    },
                    variant = LightTextVariant.Fine,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
            }

            LightBottomBar(
                items = listOfNotNull(
                    downloadStatus?.let { download ->
                        when (download) {
                            RssDownloadStatus.Downloading -> LightBarButton.Text("Downloading", onClick = {})
                            RssDownloadStatus.Downloaded -> LightBarButton.LightIcon(
                                icon = LightIcons.DOWNLOADED_ARROW,
                                onClick = {},
                                contentDescription = "Downloaded",
                            )
                            RssDownloadStatus.NotDownloaded, RssDownloadStatus.Failed ->
                                LightBarButton.LightIcon(
                                    icon = LightIcons.DOWNLOAD_ARROW,
                                    onClick = onDownload ?: {},
                                    contentDescription = "Download",
                                )
                        }
                    },
                    LightBarButton.Text(
                        "${trimSpeed(displayedSpeed)}×",
                        onClick = { showSpeedPicker = true },
                    ),
                ),
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    progress: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubCancelled: () -> Unit,
    onSeek: (Float) -> Unit,
) {
    var trackWidthPixels by remember { mutableStateOf(0) }
    fun progressAt(offset: Offset): Float =
        if (trackWidthPixels > 0) (offset.x / trackWidthPixels).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3f.gridUnitsAsDp())
            .onSizeChanged { trackWidthPixels = it.width }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                detectTapGestures { offset -> onSeek(progressAt(offset)) }
            }
            .pointerInput(enabled, trackWidthPixels) {
                if (!enabled || trackWidthPixels <= 0) return@pointerInput
                var proposedProgress = progress
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        proposedProgress = progressAt(offset)
                        onScrub(proposedProgress)
                    },
                    onDragCancel = onScrubCancelled,
                    onDragEnd = { onSeek(proposedProgress) },
                ) { change, _ ->
                    proposedProgress = progressAt(change.position)
                    onScrub(proposedProgress)
                    change.consume()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .height(0.08f.gridUnitsAsDp())
                .fillMaxWidth()
                .background(LightThemeTokens.colors.contentSecondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(progress)
                .height(0.35f.gridUnitsAsDp())
                .background(LightThemeTokens.colors.content),
        )
    }
}

@Composable
private fun PlayerIconAction(
    icon: com.stan.libbylight.ui.LightIconConfiguration,
    description: String,
    iconWidth: Float = 2f,
    iconHeight: Float = 2f,
    touchSize: Float = 5f,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(touchSize.gridUnitsAsDp())
            .then(
                if (enabled) {
                    Modifier.lightClickable(
                        onClickLabel = description,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        LightIcon(
            icon = icon,
            width = iconWidth,
            height = iconHeight,
            contentDescription = description,
        )
    }
}

@Composable
private fun SpeedPicker(
    currentSpeed: Double,
    onSelect: (Double) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onClose,
                contentDescription = "Back to Player",
            ),
            center = LightTopBarCenter.Text("Playback Speed"),
        )
        LightScrollView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 2f.gridUnitsAsDp()),
        ) {
            Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
            listOf(1.0, 1.25, 1.5, 1.75, 2.0).forEach { speed ->
                val isSelected = kotlin.math.abs(currentSpeed - speed) < 0.01
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.5f.gridUnitsAsDp())
                        .semantics { selected = isSelected }
                        .lightClickable(
                            onClickLabel = "Set speed to ${trimSpeed(speed)} times",
                            role = Role.RadioButton,
                        ) { onSelect(speed) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LightText(
                        text = "${trimSpeed(speed)}×",
                        variant = LightTextVariant.Heading,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibbySourceScreen(
    connected: Boolean,
    refreshMessage: String,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Libby"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                if (connected) {
                    SourceTextRow("Connected")
                    SettingsRow(title = "Refresh Loans", onClick = onRefresh)
                    if (refreshMessage.isNotBlank()) SourceTextRow(refreshMessage)
                    SettingsRow(title = "Disconnect", onClick = onDisconnect)
                } else {
                    SettingsRow(title = "Connect to Libby", onClick = onConnect)
                }
            }
        }
    }
}

@Composable
private fun LibbyConnectScreen(
    state: LibbyConnectState,
    onBack: () -> Unit,
    onNewCode: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    val keepAwake = state is LibbyConnectState.Code
    DisposableEffect(view, keepAwake) {
        val previous = view.keepScreenOn
        if (keepAwake) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }

    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Libby settings",
                ),
                center = LightTopBarCenter.Text("Connect to Libby"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1.5f.gridUnitsAsDp()))
                when (state) {
                    LibbyConnectState.Idle,
                    LibbyConnectState.Loading -> {
                        LightText(
                            text = "Preparing code…",
                            variant = LightTextVariant.Paragraph,
                            lighten = true,
                        )
                    }

                    is LibbyConnectState.Code -> {
                        LightText(
                            text = "On your other device:",
                            variant = LightTextVariant.Detail,
                        )
                        Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                        LightText(
                            text = "Menu → Copy To Another Device",
                            variant = LightTextVariant.Paragraph,
                        )
                        Spacer(Modifier.height(2f.gridUnitsAsDp()))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            state.digits.forEach { digit ->
                                LightText(
                                    text = digit.toString(),
                                    variant = LightTextVariant.Heading,
                                    monospace = true,
                                )
                            }
                        }
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        LightText(
                            text = "${state.secondsRemaining} seconds remaining",
                            variant = LightTextVariant.Detail,
                            lighten = true,
                        )
                    }

                    LibbyConnectState.Expired -> {
                        LightText(text = "Code expired", variant = LightTextVariant.Heading)
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SettingsRow(title = "New Code", onClick = onNewCode)
                    }

                    LibbyConnectState.Connected -> {
                        LightText(text = "Connected", variant = LightTextVariant.Heading)
                    }

                    is LibbyConnectState.Error -> {
                        LightText(
                            text = state.userMessage,
                            variant = LightTextVariant.Paragraph,
                        )
                        Spacer(Modifier.height(1f.gridUnitsAsDp()))
                        SettingsRow(title = "Retry", onClick = onRetry)
                        SettingsRow(title = "Cancel", onClick = onCancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectLibbyScreen(
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Libby settings",
                ),
                center = LightTopBarCenter.Text("Disconnect Libby"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "Disconnect Libby from Bard?",
                    variant = LightTextVariant.Paragraph,
                    modifier = Modifier.padding(end = 1.5f.gridUnitsAsDp()),
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Disconnect", onClick = onDisconnect)
                SettingsRow(title = "Cancel", onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SourceTextRow(title: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.CenterStart,
    ) {
        LightText(text = title, variant = LightTextVariant.Heading)
    }
}

@Composable
private fun LocalBooksScreen(
    result: LocalScanResult?,
    scanning: Boolean,
    onBack: () -> Unit,
    onScan: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Local Books"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "Place audiobook files or folders inside the Light Phone III/Audiobooks folder (create one if none exists).",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(0.75f.gridUnitsAsDp()))
                LightText(
                    "MP3 and M4B files supported.",
                    variant = LightTextVariant.Paragraph,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))

                val status = if (scanning) {
                    "Scanning…"
                } else when (result) {
                    null -> ""
                    LocalScanResult.PermissionRequired -> "Bard needs permission to read audio files."
                    LocalScanResult.FolderMissing -> "No Audiobooks folder found."
                    LocalScanResult.Empty -> "No audiobook files found."
                    is LocalScanResult.Success -> "${result.books.size} books found"
                }
                if (status.isNotBlank()) {
                    LightText(status, variant = LightTextVariant.Detail, lighten = true)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                }
                SettingsRow(
                    title = if (result == null) "Scan for Books" else "Scan Again",
                    onClick = if (scanning) ({}) else onScan,
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLibby: () -> Unit,
    onOpenLocalBooks: () -> Unit,
    onOpenRssFeeds: () -> Unit,
    onOpenVersion: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back",
                ),
                center = LightTopBarCenter.Text("Settings"),
            )

            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(
                    title = "Libby",
                    onClick = onOpenLibby,
                )
                SettingsRow(title = "Local Books", onClick = onOpenLocalBooks)
                SettingsRow(title = "RSS Feeds", onClick = onOpenRssFeeds)
                SettingsRow(title = "Version", onClick = onOpenVersion)
            }
        }
    }
}

@Composable
private fun RssFeedsScreen(
    feeds: List<RssFeed>,
    onBack: () -> Unit,
    onAddFeed: () -> Unit,
    onOpenFeed: (RssFeed) -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("RSS Feeds"),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                SettingsRow(title = "Add Feed", onClick = onAddFeed)
                feeds.forEach { feed ->
                    SettingsRow(
                        title = RssFeedRepository.displayName(feed),
                        onClick = { onOpenFeed(feed) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddRssFeedScreen(
    onBack: () -> Unit,
    onSubmit: suspend (String) -> String?,
) {
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BardSurface {
        LightUrlInputEditor(
            value = url,
            onValueChange = { url = it },
            message = message,
            submitting = submitting,
            onBack = onBack,
            onDone = {
                submitting = true
                message = ""
                scope.launch {
                    message = onSubmit(url.trim()).orEmpty()
                    submitting = false
                }
            },
        )
    }
}

@Composable
private fun RssFeedDetailScreen(
    feed: RssFeed,
    refreshing: Boolean,
    message: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to RSS Feeds",
                ),
                center = LightTopBarCenter.Text(RssFeedRepository.displayName(feed)),
            )
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 1.5f.gridUnitsAsDp()),
            ) {
                if (message.isNotBlank()) {
                    LightText(message, variant = LightTextVariant.Detail, lighten = true)
                    Spacer(Modifier.height(0.5f.gridUnitsAsDp()))
                }
                SettingsRow(
                    title = if (refreshing) "Refreshing…" else "Refresh",
                    onClick = if (refreshing) ({}) else onRefresh,
                )
                SettingsRow(title = "Remove Feed", onClick = onRemove)
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.5f.gridUnitsAsDp())
            .lightClickable(
                onClickLabel = "Open $title settings",
                role = Role.Button,
                onClick = onClick,
            )
            .padding(end = 1f.gridUnitsAsDp()),
        verticalArrangement = Arrangement.Center,
    ) {
        LightText(
            text = title,
            variant = LightTextVariant.Heading,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VersionScreen(onBack: () -> Unit) {
    BardSurface {
        Column(modifier = Modifier.fillMaxSize()) {
            LightTopBar(
                leftButton = LightBarButton.LightIcon(
                    icon = LightIcons.BACK,
                    onClick = onBack,
                    contentDescription = "Back to Settings",
                ),
                center = LightTopBarCenter.Text("Version"),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 2f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LightText(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                )
                Spacer(Modifier.height(1f.gridUnitsAsDp()))
                LightText(
                    text = "While Libby is playing, Bard shows a notification to keep playback active.",
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = true,
                )
            }
        }
    }
}

private fun formatPlaybackTime(seconds: Double): String {
    val total = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0
    return if (total >= 3600) {
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val remainingSeconds = total % 60
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        val minutes = total / 60
        val remainingSeconds = total % 60
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

private fun normalizeProgress(progressText: String): String =
    Regex("(\\d+(?:\\.\\d+)?)\\s*%")
        .find(progressText)
        ?.groupValues
        ?.get(1)
        ?.let { "$it%" }
        .orEmpty()

private fun progressPercent(progressText: String): Int? =
    normalizeProgress(progressText).removeSuffix("%").toDoubleOrNull()?.toInt()?.coerceIn(0, 100)

private fun LoanItem.toAudiobook(): Audiobook {
    val saved = AudiobookProgressStore.read(AudiobookSource.Libby, loanUrl)
    return Audiobook(
        id = loanUrl,
        source = AudiobookSource.Libby,
        title = title,
        author = author,
        playbackReference = loanUrl,
        durationMilliseconds = saved.durationMilliseconds,
        positionMilliseconds = saved.positionMilliseconds,
        playbackSpeed = saved.playbackSpeed,
        completed = saved.completed,
        lastPlayedAtMilliseconds = saved.lastPlayedAtMilliseconds,
        lastUpdatedAtMilliseconds = saved.lastUpdatedAtMilliseconds,
        progressPercentOverride = progressPercent(progressText),
        dueText = normalizeDueText(dueText),
        coverUrl = coverUrl,
    )
}

private fun PersistedActiveAudiobook.toAudiobook(): Audiobook = Audiobook(
    id = id,
    source = source,
    title = title.ifBlank { progress.title },
    author = author.ifBlank { progress.author },
    playbackReference = playbackReference.ifBlank { progress.playbackReference },
    durationMilliseconds = progress.durationMilliseconds,
    positionMilliseconds = progress.positionMilliseconds,
    playbackSpeed = progress.playbackSpeed,
    completed = progress.completed,
    lastPlayedAtMilliseconds = progress.lastPlayedAtMilliseconds,
    lastUpdatedAtMilliseconds = progress.lastUpdatedAtMilliseconds,
    progressPercentOverride = progress.progressPercentOverride,
    dueText = progress.dueText,
)

private fun PersistedActiveAudiobook.toRememberedPlayerState(): PlayerState = PlayerState(
    title = title.ifBlank { progress.title },
    chapter = author.ifBlank { progress.author }.takeIf { it.isNotBlank() },
    positionSeconds = progress.positionMilliseconds / 1000.0,
    durationSeconds = progress.durationMilliseconds / 1000.0,
    playbackSpeed = progress.playbackSpeed.toDouble(),
    diagnostic = "Connecting to Libby",
    readiness = PlayerReadiness.Preparing,
)

private fun normalizeDueText(dueText: String): String {
    val clean = dueText.replace(Regex("\\s+"), " ").trim()
    if (clean.isBlank()) return ""

    val duration = Regex("(\\d+\\s+days?)", RegexOption.IGNORE_CASE)
        .find(clean)
        ?.value
    if (duration != null) return "Due in $duration"

    return if (clean.startsWith("due", ignoreCase = true)) clean else "Due $clean"
}

private fun trimSpeed(speed: Double): String =
    if (speed % 1.0 == 0.0) speed.toInt().toString() else speed.toString()

/** Returning to Bard re-syncs the Libby shelf when it was last loaded longer ago than this. */
private const val LIBBY_SHELF_AUTO_REFRESH_MILLISECONDS = 2 * 60 * 1000L
