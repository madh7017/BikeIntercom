package com.madhu.bikeintercom

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.SphericalUtil
import com.madhu.bikeintercom.ui.theme.*
import kotlinx.coroutines.launch
import java.net.Socket
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var receiver: WiFiDirectReceiver
    private lateinit var intentFilter: IntentFilter
    
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var myCurrentLocation: LatLng? = null
    private lateinit var updateManager: UpdateManager

    private val viewModel: IntercomViewModel by viewModels()
    private var voiceChatService: VoiceChatService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceChatService.LocalBinder
            voiceChatService = binder.getService()
            isBound = true
            
            // Sync UI state with Service state
            if (voiceChatService?.isServiceRunning == true) {
                viewModel.setStatus(ConnectionStatus.CONNECTED)
            }
            
            voiceChatService?.onLocationUpdate = { lat, lng ->
                val otherLocation = LatLng(lat, lng)
                myCurrentLocation?.let { myLoc ->
                    val distanceInMeters = SphericalUtil.computeDistanceBetween(myLoc, otherLocation)
                    viewModel.currentDeviceDistance = if (distanceInMeters < 1000) {
                        "${distanceInMeters.toInt()}m"
                    } else {
                        String.format(Locale.US, "%.1fkm", distanceInMeters / 1000)
                    }
                }
            }

            voiceChatService?.onBatteryUpdate = { level ->
                runOnUiThread {
                    viewModel.partnerBattery = level
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceChatService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        updateManager = UpdateManager(this)
        
        // Get current version from PackageInfo
        val currentVersion = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).longVersionCode.toInt()
            } else {
                packageManager.getPackageInfo(packageName, 0).versionCode
            }
        } catch (e: Exception) { 1 }

        // Check for updates on startup
        updateManager.checkForUpdates(currentVersion) { apkUrl ->
            runOnUiThread {
                viewModel.updateUrl = apkUrl
            }
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startLocationUpdates()

        // Bind the service on startup to check for existing connections
        val intent = Intent(this, VoiceChatService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            BikeIntercomTheme {
                MainScreen(viewModel)
            }
        }

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = WiFiDirectReceiver(manager, channel, this)

        changeDeviceName(viewModel.riderName)

        intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        requestPermissions()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()
        
        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    myCurrentLocation = LatLng(location.latitude, location.longitude)
                    if (viewModel.connectionStatus == ConnectionStatus.CONNECTED) {
                        voiceChatService?.sendLocation(location.latitude, location.longitude)
                    }
                }
            }
        }, mainLooper)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(viewModel: IntercomViewModel) {
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        Scaffold(
            snackbarHost = { 
                SnackbarHost(hostState = snackbarHostState) { data ->
                    GlassCard(
                        modifier = Modifier.padding(16.dp),
                        cornerRadius = 12.dp
                    ) {
                        Text(
                            text = data.visuals.message,
                            color = PureWhite,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            },
            topBar = {
                GlassTopBar(viewModel.connectionStatus)
            },
            containerColor = Color.Transparent,
            floatingActionButtonPosition = FabPosition.Center,
            floatingActionButton = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (viewModel.updateUrl != null) {
                        GlassCard(
                            modifier = Modifier
                                .padding(bottom = 16.dp)
                                .clickable {
                                    viewModel.updateUrl?.let { url ->
                                        updateManager.downloadAndInstall(url)
                                        viewModel.updateUrl = null
                                    }
                                },
                            cornerRadius = 12.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = StatusGreen, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("New Version Available - Tap to Install", color = PureWhite, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    
                    GlassMicButton(
                        isActive = viewModel.isVoiceActive && viewModel.connectionStatus == ConnectionStatus.CONNECTED,
                        isEnabled = viewModel.connectionStatus == ConnectionStatus.CONNECTED,
                        onClick = { 
                            if (viewModel.connectionStatus == ConnectionStatus.CONNECTED) {
                                viewModel.isVoiceActive = !viewModel.isVoiceActive
                                voiceChatService?.setMicMuted(!viewModel.isVoiceActive)
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("Link Required for Comms") }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepNavy)
            ) {
                // Background Depth Layer
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(StatusBlue.copy(alpha = 0.2f), Color.Transparent),
                            center = Offset(canvasWidth * 0.1f, canvasHeight * 0.2f),
                            radius = canvasWidth * 0.8f
                        ),
                        radius = canvasWidth * 0.8f,
                        center = Offset(canvasWidth * 0.1f, canvasHeight * 0.2f)
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(StatusBlue.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(canvasWidth * 0.9f, canvasHeight * 0.8f),
                            radius = canvasWidth * 0.7f
                        ),
                        radius = canvasWidth * 0.7f,
                        center = Offset(canvasWidth * 0.9f, canvasHeight * 0.8f)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // State for Dialogs moved outside to be accessible by LazyColumn
                    var showCreateGroupDialog by remember { mutableStateOf(false) }
                    var tempGroupName by remember { mutableStateOf(viewModel.riderName) }
                    var tempGroupPassword by remember { mutableStateOf("") }
                    
                    var showJoinPasswordDialog by remember { mutableStateOf<WifiP2pDevice?>(null) }
                    var joinPasswordInput by remember { mutableStateOf("") }

                    // Back Handler to minimize instead of closing
                    BackHandler(enabled = viewModel.connectionStatus == ConnectionStatus.CONNECTED && !viewModel.isMinimized) {
                        viewModel.isMinimized = true
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "Nearby Riders",
                                style = MaterialTheme.typography.titleLarge,
                                color = PureWhite,
                                fontWeight = FontWeight.Light
                            )
                            Text(
                                when(viewModel.connectionStatus) {
                                    ConnectionStatus.SEARCHING -> "Scanning local mesh..."
                                    ConnectionStatus.CONNECTED -> "Active Link Established"
                                    else -> "System Standby"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = SoftGray
                            )
                            Spacer(modifier = Modifier.height(20.dp))

                            // State for Dialogs used here

                            if (showCreateGroupDialog) {
                                AlertDialog(
                                    onDismissRequest = { showCreateGroupDialog = false },
                                    containerColor = DeepNavy,
                                    title = { Text("Create Intercom Space", color = PureWhite) },
                                    text = {
                                        Column {
                                            OutlinedTextField(
                                                value = tempGroupName,
                                                onValueChange = { tempGroupName = it },
                                                label = { Text("Space Name") },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PureWhite, unfocusedTextColor = PureWhite)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = tempGroupPassword,
                                                onValueChange = { tempGroupPassword = it },
                                                label = { Text("Space Password") },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PureWhite, unfocusedTextColor = PureWhite)
                                            )
                                        }
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            viewModel.riderName = tempGroupName
                                            viewModel.groupPassword = tempGroupPassword
                                            changeDeviceName(tempGroupName)
                                            createGroup()
                                            showCreateGroupDialog = false
                                        }) { Text("Create") }
                                    }
                                )
                            }

                            if (showJoinPasswordDialog != null) {
                                AlertDialog(
                                    onDismissRequest = { showJoinPasswordDialog = null },
                                    containerColor = DeepNavy,
                                    title = { Text("Join Space", color = PureWhite) },
                                    text = {
                                        OutlinedTextField(
                                            value = joinPasswordInput,
                                            onValueChange = { joinPasswordInput = it },
                                            label = { Text("Enter Password") },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = PureWhite, unfocusedTextColor = PureWhite)
                                        )
                                    },
                                    confirmButton = {
                                        Button(onClick = {
                                            viewModel.groupPassword = joinPasswordInput
                                            showJoinPasswordDialog?.let { connectToDevice(it) }
                                            showJoinPasswordDialog = null
                                        }) { Text("Join") }
                                    }
                                )
                            }
                            
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                Button(
                                    onClick = { showCreateGroupDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = StatusBlue.copy(alpha = 0.3f)),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Create", fontSize = 12.sp)
                                }
                                
                                Spacer(modifier = Modifier.width(8.dp))

                                Button(
                                    onClick = { 
                                        discoverDevices()
                                        scope.launch { snackbarHostState.showSnackbar("Scanning...") }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GlassWhiteHigh),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Default.WifiTethering, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Join", fontSize = 12.sp)
                                }
                            }
                            
                            if (viewModel.connectionStatus == ConnectionStatus.SEARCHING) {
                                Spacer(modifier = Modifier.height(16.dp))
                                CircularProgressIndicator(color = PureWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        "AVAILABLE SPACES",
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = SoftGray,
                        letterSpacing = 1.5.sp
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(viewModel.devices) { device ->
                            GlassDeviceItem(
                                device = device,
                                isSelected = viewModel.selectedDeviceAddress == device.deviceAddress,
                                onClick = {
                                    if (device.status == WifiP2pDevice.CONNECTED) {
                                        requestConnectionInfo()
                                    } else {
                                        viewModel.selectedDeviceAddress = device.deviceAddress
                                        showJoinPasswordDialog = device
                                    }
                                }
                            )
                        }
                    }
                }

                // Voice Overlay
                AnimatedVisibility(
                    visible = viewModel.connectionStatus == ConnectionStatus.CONNECTED,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(500))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(if (viewModel.isMinimized) Color.Transparent else DeepNavy.copy(alpha = 0.85f))
                            .padding(if (viewModel.isMinimized) 16.dp else 0.dp),
                        contentAlignment = if (viewModel.isMinimized) Alignment.BottomEnd else Alignment.Center
                    ) {
                        val cardScale by animateFloatAsState(if (viewModel.isMinimized) 0.6f else 1f, label = "cardScale")
                        
                        GlassCard(
                            modifier = Modifier
                                .then(if (viewModel.isMinimized) Modifier.size(280.dp) else Modifier.padding(24.dp))
                                .graphicsLayer {
                                    scaleX = cardScale
                                    scaleY = cardScale
                                }
                                .clickable { if (viewModel.isMinimized) viewModel.isMinimized = false }
                        ) {
                            Column(
                                modifier = Modifier.padding(if (viewModel.isMinimized) 16.dp else 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                                    val pulseScale by infiniteTransition.animateFloat(
                                        initialValue = 1f, targetValue = 1.2f,
                                        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "scale"
                                    )
                                    Box(Modifier.size(if (viewModel.isMinimized) 40.dp else 100.dp).graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }.background(PureWhite.copy(alpha = 0.05f), CircleShape))
                                    Icon(
                                        Icons.Default.BluetoothAudio,
                                        contentDescription = null,
                                        tint = PureWhite,
                                        modifier = Modifier.size(if (viewModel.isMinimized) 24.dp else 56.dp)
                                    )
                                }
                                
                                if (!viewModel.isMinimized) {
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Text(
                                        viewModel.currentDeviceDistance ?: "LOCKING SIGNAL...",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = PureWhite,
                                        letterSpacing = 2.sp
                                    )
                                    viewModel.partnerBattery?.let { battery ->
                                        Text(
                                            "PARTNER BATTERY: $battery%",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (battery < 20) StatusRed else SoftGray,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                    
                                    Spacer(modifier = Modifier.height(24.dp))
                                    
                                    // Output Controls Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Speaker Toggle (Call vs Normal)
                                        IconButton(
                                            onClick = {
                                                viewModel.isSpeakerphoneOn = !viewModel.isSpeakerphoneOn
                                                voiceChatService?.setSpeakerphoneOn(viewModel.isSpeakerphoneOn)
                                            },
                                            modifier = Modifier.background(if (viewModel.isSpeakerphoneOn) PureWhite.copy(alpha = 0.2f) else Color.Transparent, CircleShape)
                                        ) {
                                            Icon(
                                                if (viewModel.isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.Hearing,
                                                contentDescription = null,
                                                tint = PureWhite
                                            )
                                        }

                                        // Master Mute Output (Off button)
                                        IconButton(
                                            onClick = {
                                                viewModel.isAudioOutputEnabled = !viewModel.isAudioOutputEnabled
                                                voiceChatService?.setAudioOutputEnabled(viewModel.isAudioOutputEnabled)
                                            },
                                            modifier = Modifier.background(if (!viewModel.isAudioOutputEnabled) StatusRed.copy(alpha = 0.4f) else Color.Transparent, CircleShape)
                                        ) {
                                            Icon(
                                                if (viewModel.isAudioOutputEnabled) Icons.Default.Headset else Icons.Default.HeadsetOff,
                                                contentDescription = null,
                                                tint = if (viewModel.isAudioOutputEnabled) PureWhite else StatusRed
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(40.dp))
                                    Button(
                                        onClick = { 
                                            stopVoiceChat()
                                            disconnectFromDevice()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = StatusRed.copy(alpha = 0.2f)),
                                        border = BorderStroke(1.dp, StatusRed.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().height(54.dp)
                                    ) {
                                        Text("TERMINATE LINK", color = StatusRed, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("LIVE", color = StatusGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GlassTopBar(status: ConnectionStatus) {
        CenterAlignedTopAppBar(
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Bike Intercom",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Light,
                            letterSpacing = 2.sp
                        ),
                        color = PureWhite
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    when(status) {
                                        ConnectionStatus.CONNECTED -> StatusGreen
                                        ConnectionStatus.SEARCHING -> StatusBlue
                                        else -> SoftGray
                                    },
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            status.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftGray
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent)
        )
    }

    @Composable
    fun GlassCard(
        modifier: Modifier = Modifier,
        cornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
        content: @Composable () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.04f)
                        ),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    ),
                    shape = RoundedCornerShape(cornerRadius)
                )
        ) {
            content()
        }
    }

    @Composable
    fun GlassDeviceItem(device: WifiP2pDevice, isSelected: Boolean, onClick: () -> Unit) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() },
            cornerRadius = 16.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(GlassWhiteHigh, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.DirectionsBike,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        device.deviceName ?: "Unknown Rider",
                        color = PureWhite,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        device.deviceAddress,
                        color = SoftGray,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (isSelected) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = PureWhite, strokeWidth = 2.dp)
                }
            }
        }
    }

    @Composable
    fun GlassMicButton(isActive: Boolean, isEnabled: Boolean, onClick: () -> Unit) {
        val infiniteTransition = rememberInfiniteTransition(label = "micPulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
            label = "alpha"
        )

        Box(contentAlignment = Alignment.Center) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .background(
                            Brush.radialGradient(
                                listOf(PureWhite.copy(alpha = alpha), Color.Transparent)
                            ),
                            CircleShape
                        )
                )
            }
            
            FloatingActionButton(
                onClick = onClick,
                containerColor = Color.Transparent,
                contentColor = if (isActive) DeepNavy else PureWhite,
                shape = CircleShape,
                modifier = Modifier
                    .size(68.dp)
                    .graphicsLayer { this.alpha = if (isEnabled) 1f else 0.5f }
                    .clip(CircleShape)
                    .background(
                        brush = if (isActive) SolidColor(PureWhite) 
                        else Brush.linearGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.05f))
                        ),
                        shape = CircleShape
                    )
                    .border(1.dp, GlassBorder, CircleShape)
            ) {
                Icon(
                    if (isActive) Icons.Default.Mic else Icons.Default.MicOff,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp)
                )
            }
        }
    }

    private fun changeDeviceName(newName: String) {
        try {
            val method = manager.javaClass.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(manager, channel, newName, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d("WiFiDirect", "Name changed to $newName") }
                override fun onFailure(reason: Int) { Log.e("WiFiDirect", "Failed: $reason") }
            })
        } catch (e: Exception) { Log.e("WiFiDirect", "Error setDeviceName", e) }
    }

    private fun discoverDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        viewModel.setStatus(ConnectionStatus.SEARCHING)
        try {
            manager.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { startPeerDiscovery() }
                override fun onFailure(reason: Int) { startPeerDiscovery() }
            })
        } catch (e: SecurityException) { viewModel.setStatus(ConnectionStatus.FAILED) }
    }

    private fun createGroup() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        // Remove existing groups first to ensure a clean start
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { executeCreateGroup() }
            override fun onFailure(reason: Int) { executeCreateGroup() }
        })
    }

    private fun executeCreateGroup() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        manager.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                viewModel.isGroupCreated = true
                viewModel.isGroupOwner = true
                viewModel.setStatus(ConnectionStatus.CONNECTED)
            }
            override fun onFailure(reason: Int) {
                viewModel.setStatus(ConnectionStatus.FAILED)
            }
        })
    }

    private fun startPeerDiscovery() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) { viewModel.setStatus(ConnectionStatus.FAILED) }
        })
    }

    fun updateDeviceList(deviceList: List<WifiP2pDevice>) { viewModel.updateDevices(deviceList) }

    fun connectToDevice(device: WifiP2pDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        
        viewModel.setStatus(ConnectionStatus.PAIRING)
        
        // Force a group removal first to ensure a clean slate
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { proceedWithConnection(device) }
            override fun onFailure(reason: Int) { proceedWithConnection(device) }
        })
    }

    private fun proceedWithConnection(device: WifiP2pDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 15 // Highest priority to be group owner if needed
        }
        
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                // Connection initiated, wait for broadcast receiver
            }
            override fun onFailure(reason: Int) {
                viewModel.setStatus(ConnectionStatus.FAILED)
            }
        })
    }

    private fun disconnectFromDevice() {
        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                viewModel.setStatus(ConnectionStatus.DISCONNECTED)
                viewModel.currentDeviceDistance = null
            }
            override fun onFailure(reason: Int) {}
        })
    }

    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info ->
            if (info.groupFormed) {
                val hostAddress = info.groupOwnerAddress?.hostAddress
                if (info.isGroupOwner) {
                    ServerClass { socket -> startVoiceChat(socket) }.start()
                } else if (hostAddress != null) {
                    ClientClass(hostAddress) { socket -> startVoiceChat(socket) }.start()
                }
            }
        }
    }

    fun startVoiceChat(socket: Socket) {
        voiceChatService?.startVoiceChat(socket, viewModel.isGroupOwner, viewModel.groupPassword)
        runOnUiThread {
            viewModel.setStatus(ConnectionStatus.CONNECTED)
            viewModel.isVoiceActive = true
            voiceChatService?.setMicMuted(false)
        }
    }

    private fun stopVoiceChat() {
        voiceChatService?.stopVoiceChat()
        viewModel.setStatus(ConnectionStatus.DISCONNECTED)
        viewModel.currentDeviceDistance = null
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
    }
}
