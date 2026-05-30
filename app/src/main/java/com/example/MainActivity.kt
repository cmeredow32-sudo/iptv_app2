package com.example

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme(dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IptvApp()
                }
            }
        }
    }
}

data class IptvChannel(
    val name: String,
    val logoUrl: String,
    val group: String,
    val streamUrl: String
)

sealed class AppState {
    object Idle : AppState()
    object Loading : AppState()
    data class Success(val channels: List<IptvChannel>) : AppState()
    data class Error(val message: String) : AppState()
}

data class PlaylistOption(val name: String, val url: String)

class IptvViewModel : ViewModel() {
    val playlistOptions = listOf(
        PlaylistOption("Türkmenistan", "https://iptv-org.github.io/iptv/countries/tm.m3u"),
        PlaylistOption("Sport", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
        PlaylistOption("Kino", "https://iptv-org.github.io/iptv/categories/movies.m3u"),
        PlaylistOption("Çagalar", "https://iptv-org.github.io/iptv/categories/kids.m3u"),
        PlaylistOption("Aýdym-Saz", "https://iptv-org.github.io/iptv/categories/music.m3u"),
        PlaylistOption("Dünýä", "https://iptv-org.github.io/iptv/index.m3u")
    )

    private val _currentOption = MutableStateFlow(playlistOptions[0])
    val currentOption: StateFlow<PlaylistOption> = _currentOption.asStateFlow()

    private val _state = MutableStateFlow<AppState>(AppState.Idle)
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _playlistUrl = MutableStateFlow(playlistOptions[0].url)
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val client = OkHttpClient()

    init {
        loadPlaylist()
    }

    fun selectOption(option: PlaylistOption) {
        _currentOption.value = option
        _playlistUrl.value = option.url
        loadPlaylist()
    }

    fun updateUrl(newUrl: String) {
        _playlistUrl.value = newUrl
        loadPlaylist()
    }

    fun loadPlaylist() {
        _state.value = AppState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = _playlistUrl.value
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    _state.value = AppState.Error("Playlist ýüklenmedi: (Kod ${response.code})")
                    return@launch
                }
                
                val channels = mutableListOf<IptvChannel>()
                var currentName = ""
                var currentLogo = ""
                var currentGroup = ""
                
                val body = response.body
                if (body != null) {
                    body.charStream().buffered().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) {
                                if (trimmed.startsWith("#EXTINF:")) {
                                    val logoMatch = """tvg-logo="([^"]+)"""".toRegex().find(trimmed)
                                    currentLogo = logoMatch?.groupValues?.get(1) ?: ""
                                    
                                    val groupMatch = """group-title="([^"]+)"""".toRegex().find(trimmed)
                                    currentGroup = groupMatch?.groupValues?.get(1) ?: "Köpçülikleýin"
                                    
                                    val commaIndex = trimmed.lastIndexOf(",")
                                    currentName = if (commaIndex != -1 && commaIndex + 1 < trimmed.length) {
                                        trimmed.substring(commaIndex + 1).trim()
                                    } else {
                                        "Täsin kanal"
                                    }
                                } else if (!trimmed.startsWith("#")) {
                                    if (currentName.isNotEmpty() && trimmed.isNotEmpty()) {
                                        channels.add(
                                            IptvChannel(
                                                name = currentName,
                                                logoUrl = currentLogo,
                                                group = currentGroup,
                                                streamUrl = trimmed
                                            )
                                        )
                                    }
                                    currentName = ""
                                    currentLogo = ""
                                    currentGroup = ""
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
                
                if (channels.isEmpty()) {
                    _state.value = AppState.Error("Kanal tapylmady. Ýalňyş M3U link.")
                } else {
                    _state.value = AppState.Success(channels)
                }
            } catch (e: Exception) {
                _state.value = AppState.Error("Internet ýalňyşlygy: ${e.localizedMessage}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IptvApp() {
    val viewModel: IptvViewModel = viewModel()
    val state by viewModel.state.collectAsState()
    val currentUrl by viewModel.playlistUrl.collectAsState()
    val currentOption by viewModel.currentOption.collectAsState()
    
    var playingChannel by remember { mutableStateOf<IptvChannel?>(null) }
    var configOpen by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf(currentUrl) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Handle Back Press for Player View
    BackHandler(enabled = playingChannel != null || configOpen || isSearchActive) {
        if (playingChannel != null) {
            playingChannel = null
        } else if (configOpen) {
            configOpen = false
        } else if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (playingChannel == null) {
                Column {
                    if (isSearchActive) {
                        TopAppBar(
                            title = {
                                TextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    placeholder = { Text("Kanal gözle...") },
                                    singleLine = true,
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { 
                                    isSearchActive = false
                                    searchQuery = "" 
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza")
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    } else {
                        TopAppBar(
                            title = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("TV", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("Mana IPTV", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                                } 
                            },
                            actions = {
                                IconButton(onClick = { isSearchActive = true }) {
                                    Icon(Icons.Default.Search, contentDescription = "Gözleg", tint = MaterialTheme.colorScheme.onSurface)
                                }
                                IconButton(onClick = { viewModel.loadPlaylist() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Täzele", tint = MaterialTheme.colorScheme.onSurface)
                                }
                                IconButton(onClick = { 
                                    urlInput = currentUrl
                                    configOpen = true 
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Sazlamalar", tint = MaterialTheme.colorScheme.onSurface)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                    ScrollableTabRow(
                        selectedTabIndex = viewModel.playlistOptions.indexOf(currentOption).coerceAtLeast(0),
                        edgePadding = 16.dp,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = {}
                    ) {
                        viewModel.playlistOptions.forEachIndexed { index, option ->
                            Tab(
                                selected = currentOption == option,
                                onClick = { viewModel.selectOption(option) },
                                text = { Text(option.name, fontWeight = FontWeight.Bold) },
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(if (playingChannel == null) innerPadding else PaddingValues(0.dp))

        Box(modifier = modifier) {
            if (playingChannel != null) {
                VideoPlayerScreen(
                    channel = playingChannel!!,
                    onBack = { playingChannel = null }
                )
            } else {
                // Main List UI
                Column(modifier = Modifier.fillMaxSize()) {
                    when (state) {
                        is AppState.Idle -> {}
                        is AppState.Loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        is AppState.Error -> {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = (state as AppState.Error).message,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { viewModel.loadPlaylist() }) {
                                    Text("Täzeden synan")
                                }
                            }
                        }
                        is AppState.Success -> {
                            val allChannels = (state as AppState.Success).channels
                            val channels = remember(allChannels, searchQuery) {
                                if (searchQuery.isBlank()) {
                                    allChannels
                                } else {
                                    allChannels.filter { it.name.contains(searchQuery, ignoreCase = true) }
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(32.dp)
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(32.dp)
                                    )
                                    .clip(RoundedCornerShape(32.dp))
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Kanal sanawy (${channels.size})".uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.5.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LazyColumn(
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(channels) { channel ->
                                        ChannelItem(channel = channel, onClick = { playingChannel = channel })
                                    }
                                }
                                Text(
                                    text = "Döreden Agajan Babamyradow",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Settings Dialog
        if (configOpen) {
            AlertDialog(
                onDismissRequest = { configOpen = false },
                title = { Text("IPTV M3U Linki (Kod)") },
                text = {
                    TextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://...") }
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        viewModel.updateUrl(urlInput)
                        configOpen = false
                    }) {
                        Text("Yatda sakla")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { configOpen = false }) {
                        Text("Goýbolsun")
                    }
                }
            )
        }
    }
}

@Composable
fun ChannelItem(channel: IptvChannel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("channel_${channel.name}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (channel.logoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = "Logo",
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = channel.name.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = channel.group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(channel: IptvChannel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    super.onPlayerError(error)
                    // Auto-reconnect after 3 seconds on error to ensure stream stability
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (playbackState == androidx.media3.common.Player.STATE_IDLE || playbackState == androidx.media3.common.Player.STATE_ENDED) {
                            prepare()
                            play()
                        }
                    }, 3000)
                }
            })
        }
    }

    LaunchedEffect(channel.streamUrl) {
        try {
            val mediaItem = MediaItem.fromUri(channel.streamUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    exoPlayer.play()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    keepScreenOn = true
                    // Replace the default Android picture with our custom Turkmen IPTV logo
                    defaultArtwork = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.turkmen_iptv_logo_1780126488708)
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
            },
            onRelease = { playerView ->
                playerView.player = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay Back Button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.5f), shape = RoundedCornerShape(50))
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Yza (Back)", tint = Color.White)
        }
    }
}

