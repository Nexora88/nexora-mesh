package org.nexora.mesh

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.app.Activity
import com.google.zxing.integration.android.IntentIntegrator

private val MeshBackground = Color(0xFF07110F)
private val MeshSurface = Color(0xFF10201C)
private val MeshGreen = Color(0xFF25C8FF)
private val MeshViolet = Color(0xFF7548E8)
private val MeshMuted = Color(0xFF91A69D)
private const val BLE_HOP_KEY_EPOCH = 1L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshTheme { MeshApp() } }
    }
}

@Composable
private fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = MeshGreen, onPrimary = Color(0xFF002114),
        background = MeshBackground, surface = MeshSurface,
        onSurface = Color(0xFFEAF7F0), secondary = MeshMuted
    ), content = content)
}

@Composable
private fun MeshApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = remember { BluetoothChatManager(context) }
    val bleTransport = remember { BleGattTransport(context) }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    val relayStore = remember { RelayEnvelopeStore(context) }
    var relayOptIn by remember { mutableStateOf(relayStore.isOptedIn()) }
    var blePeers by remember { mutableStateOf<List<BleGattTransport.Peer>>(emptyList()) }
    var bleStatus by remember { mutableStateOf("BLE taşıması henüz başlatılmadı.") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BluetoothChatManager.Peer>>(emptyList()) }
    var connectionName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Bluetooth bağlantısı henüz kurulmadı.") }
    var activePeer by remember { mutableStateOf<BluetoothChatManager.Peer?>(null) }
    var activeBlePeerId by remember { mutableStateOf<String?>(null) }
    var pendingContact by remember { mutableStateOf<ContactQr.Contact?>(null) }
    var secureChannelActive by remember { mutableStateOf(false) }
    var pendingBluetoothAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val messages = remember { mutableStateListOf<BluetoothChatManager.ChatMessage>() }
    val blePeerManagers = remember { mutableStateMapOf<String, BluetoothChatManager>() }
    val blePeerListeners = remember { mutableMapOf<String, BluetoothChatManager.Listener>() }
    val blePeerMessages = remember { mutableStateMapOf<String, SnapshotStateList<BluetoothChatManager.ChatMessage>>() }
    val relayOptInState = rememberUpdatedState(relayOptIn)

    fun activeChatManager(): BluetoothChatManager? =
        activeBlePeerId?.let { blePeerManagers[it] } ?: if (activeBlePeerId == null) manager else null

    fun forwardStoredToPeer(peerId: String) {
        val nextManager = blePeerManagers[peerId] ?: return
        val nextContext = nextManager.currentHopContext(BLE_HOP_KEY_EPOCH) ?: return
        val now = System.currentTimeMillis() / 1000L
        for (messageId in relayStore.pendingMessageIds()) {
            val previousHop = relayStore.lastHopNodeId(messageId) ?: continue
            if (previousHop.contentEquals(nextContext.remoteNodeId)) continue
            val forwarded = relayStore.peekForNextHop(messageId, now) ?: continue
            if (bleTransport.sendEnvelope(peerId, forwarded)) {
                bleStatus = "Saklanan zarf eşe özel BLE oturumundan iletildi."
                return
            }
        }
    }

    fun attachBlePeerManager(peerId: String, peerLabel: String) {
        val peerManager = blePeerManagers[peerId] ?: BluetoothChatManager(context).also {
            blePeerManagers[peerId] = it
        }
        blePeerListeners.remove(peerId)?.let(peerManager::removeListener)
        val peerMessages = blePeerMessages.getOrPut(peerId) { mutableStateListOf() }
        val peerListener = object : BluetoothChatManager.Listener {
            override fun onDevicesChanged(next: List<BluetoothChatManager.Peer>, isScanning: Boolean) {}
            override fun onConnectionChanged(connectedName: String?) {
                if (activeBlePeerId == peerId) connectionName = connectedName
            }
            override fun onMessage(message: BluetoothChatManager.ChatMessage) { peerMessages.add(message) }
            override fun onDeliveryConfirmed(messageId: String) {
                val index = peerMessages.indexOfFirst { it.messageId == messageId }
                if (index >= 0) peerMessages[index] = peerMessages[index].copy(delivered = true)
            }
            override fun onStatus(value: String) { if (activeBlePeerId == peerId) status = value }
            override fun onPeerIdentityPending(contact: ContactQr.Contact) {
                if (activeBlePeerId == peerId) pendingContact = contact
            }
            override fun onSecurityChanged(verified: Boolean, fingerprint: String?) {
                if (activeBlePeerId == peerId) secureChannelActive = verified
                if (verified) forwardStoredToPeer(peerId)
            }
            override fun onMediaReceived(fileName: String, savedPath: String, sizeBytes: Int) {
                peerMessages.add(BluetoothChatManager.ChatMessage(
                    "Dosya alındı ve bütünlüğü doğrulandı: $fileName ($sizeBytes bayt). Uygulama içindeki şifreli kasaya kaydedildi.", false
                ))
            }
        }
        blePeerListeners[peerId] = peerListener
        peerManager.addListener(peerListener)
        peerManager.attachBleTransport(peerLabel) { packet ->
            val outgoing = peerManager.wrapBlePacket(packet, System.currentTimeMillis() / 1000L) ?: return@attachBleTransport false
            if (relayOptInState.value && NativeMesh.inspectEnvelope(outgoing) != null) relayStore.enqueue(outgoing)
            bleTransport.sendEnvelope(peerId, outgoing)
        }
    }

    fun forwardStoredFrom(sourcePeerId: String, messageId: ByteArray) {
        val now = System.currentTimeMillis() / 1000L
        val previousHop = relayStore.lastHopNodeId(messageId) ?: return
        for (nextPeerId in bleTransport.connectedPeerIds()) {
            if (nextPeerId == sourcePeerId) continue
            val nextManager = blePeerManagers[nextPeerId] ?: continue
            val nextContext = nextManager.currentHopContext(BLE_HOP_KEY_EPOCH) ?: continue
            if (previousHop.contentEquals(nextContext.remoteNodeId)) continue
            val forwarded = relayStore.peekForNextHop(messageId, now) ?: return
            if (bleTransport.sendEnvelope(nextPeerId, forwarded)) {
                bleStatus = "Zarf, önceki eş dışındaki doğrulanmış BLE eşine aktarıldı."
                return
            }
        }
    }

    fun handleBlePacket(peerId: String, packet: ByteArray) {
        val peerManager = blePeerManagers[peerId] ?: return
        val now = System.currentTimeMillis() / 1000L
        val hopContext = peerManager.currentHopContext(BLE_HOP_KEY_EPOCH)

        if (packet.size == 105 && hopContext != null) {
            val verified = NativeMesh.verifyStoreAck(packet, hopContext.hopKey,
                hopContext.remoteNodeId, hopContext.localNodeId, now, 300L) == 0
            if (verified) {
                relayStore.acknowledgeStoredByNextHop(packet, hopContext.hopKey,
                    hopContext.remoteNodeId, hopContext.localNodeId, now, 300L)
                bleStatus = "Rust STORE_ACK doğrulandı; varsa yerel kuyruk kaydı kaldırıldı."
                forwardStoredToPeer(peerId)
                return
            }
        }

        val envelope = NativeMesh.inspectEnvelope(packet)
        if (envelope != null) {
            val inner = runCatching { org.json.JSONObject(String(envelope.ciphertext, Charsets.UTF_8)) }.getOrNull() ?: return
            val type = inner.optString("t")
            val innerId = if (type == "ACK") inner.optString("relayId") else inner.optString("id")
            val uuid = runCatching { java.util.UUID.fromString(innerId) }.getOrNull() ?: return
            val expectedMessageId = java.nio.ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
            val innerDestination = runCatching { android.util.Base64.decode(inner.optString("toNode"), android.util.Base64.NO_WRAP) }.getOrNull()
            val innerExpiry = inner.optLong("expiresAt", -1L)
            if (type !in setOf("DATA", "ACK") || !expectedMessageId.contentEquals(envelope.messageId) ||
                innerDestination?.contentEquals(envelope.destinationToken) != true || innerExpiry != envelope.expiresAtUnix) return
            if (envelope.expiresAtUnix <= now) {
                bleStatus = "Süresi dolmuş Rust zarfı reddedildi."
                return
            }
            val stored = if (relayOptInState.value && hopContext != null) {
                relayStore.enqueue(packet, hopContext.remoteNodeId)
                val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                val ack = relayStore.createStoredAck(packet, hopContext.localNodeId,
                    hopContext.remoteNodeId, hopContext.hopKey, now, nonce)
                if (ack != null) bleTransport.sendEnvelope(peerId, ack)
                ack != null
            } else false

            if (envelope.destinationToken.contentEquals(peerManager.localNodeId())) {
                val delivered = peerManager.receiveBlePacket(envelope.ciphertext)
                if (delivered && stored) relayStore.removeDeliveredLocally(envelope.messageId)
                if (delivered) bleStatus = "Rust zarfı hedef cihaza doğrulanarak teslim edildi."
            } else if (stored) {
                forwardStoredFrom(peerId, envelope.messageId)
            }
            return
        }

        peerManager.receiveBlePacket(packet)
    }

    DisposableEffect(manager) {
        val listener = object : BluetoothChatManager.Listener {
            override fun onDevicesChanged(next: List<BluetoothChatManager.Peer>, isScanning: Boolean) {
                devices = next
                scanning = isScanning
            }
            override fun onConnectionChanged(connectedName: String?) { connectionName = connectedName }
            override fun onMessage(message: BluetoothChatManager.ChatMessage) { messages.add(message) }
            override fun onDeliveryConfirmed(messageId: String) {
                val index = messages.indexOfFirst { it.messageId == messageId }
                if (index >= 0) messages[index] = messages[index].copy(delivered = true)
            }
            override fun onStatus(value: String) { status = value }
            override fun onPeerIdentityPending(contact: ContactQr.Contact) { pendingContact = contact }
            override fun onSecurityChanged(verified: Boolean, fingerprint: String?) { secureChannelActive = verified }
            override fun onMediaReceived(fileName: String, savedPath: String, sizeBytes: Int) {
                messages.add(BluetoothChatManager.ChatMessage("Dosya alındı ve bütünlüğü doğrulandı: " + fileName + " (" + sizeBytes + " bayt). Kaydedildi: " + savedPath, false))
            }
        }
        manager.addListener(listener)
        onDispose {
            manager.removeListener(listener)
            manager.dispose()
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            status = "Bluetooth etkinleştirildi."
            pendingBluetoothAction?.invoke()
        } else {
            status = "Bluetooth açılmadı. Cihaz aramak ve bağlanmak için Bluetooth gerekli."
        }
        pendingBluetoothAction = null
    }

    fun runWhenBluetoothEnabled(action: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            status = "Bu cihaz Bluetooth desteklemiyor."
        } else if (!adapter.isEnabled) {
            pendingBluetoothAction = action
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            action()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) runWhenBluetoothEnabled { manager.startDiscovery() }
        else status = "Cihaz keşfi için Bluetooth izinleri verilmedi."
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) runWhenBluetoothEnabled { bleTransport.start() }
        else bleStatus = "BLE keşfi/yayını için Bluetooth izinleri verilmedi."
    }

    DisposableEffect(lifecycleOwner, bleTransport) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // No Android background BLE scanning/advertising without a dedicated,
                // user-visible foreground-service design.
                bleTransport.stop()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    DisposableEffect(bleTransport) {
        bleTransport.setListener(object : BleGattTransport.Listener {
            override fun onPeers(peers: List<BleGattTransport.Peer>) { blePeers = peers }
            override fun onStatus(value: String) { bleStatus = value }
            override fun onPacket(packet: ByteArray) {
                manager.receiveBlePacket(packet)
            }
            override fun onConnected(peerLabel: String) {
                bleStatus = "BLE eş bağlantısı hazır: $peerLabel"
            }
            override fun onConnected(peerId: String, peerLabel: String) {
                activeBlePeerId = peerId
                attachBlePeerManager(peerId, peerLabel)
                connectionName = peerLabel
                bleStatus = "Bağımsız BLE oturumu hazır: $peerLabel"
                forwardStoredToPeer(peerId)
            }
            override fun onPacketFromPeer(peerId: String, packet: ByteArray) {
                handleBlePacket(peerId, packet)
            }
            override fun onDisconnected() {
                bleStatus = "BLE sunucu bağlantısı kesildi."
            }
            override fun onDisconnected(peerId: String) {
                val peerManager = blePeerManagers.remove(peerId)
                blePeerListeners.remove(peerId)?.let { listener -> peerManager?.removeListener(listener) }
                peerManager?.dispose()
                blePeerMessages.remove(peerId)
                if (activeBlePeerId == peerId) {
                    activeBlePeerId = null
                    connectionName = null
                    secureChannelActive = false
                    pendingContact = null
                }
                bleStatus = "BLE eş oturumu kapandı: $peerId"
            }
        })
        onDispose {
            bleTransport.setListener(null)
            bleTransport.stop()
            blePeerManagers.values.toList().forEach { it.dispose() }
            blePeerManagers.clear()
            blePeerListeners.clear()
            blePeerMessages.clear()
        }
    }

    fun startBleOrRequest() {
        val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            runWhenBluetoothEnabled { bleTransport.start() }
        } else {
            blePermissionLauncher.launch(required)
        }
    }

    val mediaPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Thread({
                try {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "media.bin"
                    val output = ByteArrayOutputStream()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MediaTransferProtocol.MAX_MEDIA_BYTES) { "File exceeds 64 MiB limit" }
                            output.write(buffer, 0, count)
                        }
                    } ?: throw IllegalStateException("Could not open selected file")
                    require(output.size() > 0) { "Empty files are not supported" }
                    val target = activeChatManager()
                    if (target?.sendMedia(output.toByteArray(), name) != true) {
                        (context as? Activity)?.runOnUiThread { status = "Dosya gönderilemedi; güvenli bağlantıyı ve 64 MiB sınırını kontrol et." }
                    }
                } catch (_: Exception) {
                    (context as? Activity)?.runOnUiThread { status = "Dosya okunamadı veya 64 MiB aktarım sınırını aşıyor." }
                }
            }, "mesh-media-send").start()
        }
    }
    fun scanOrRequest() {
        if (manager.hasPermissions()) runWhenBluetoothEnabled { manager.startDiscovery() }
        else {
            val requested = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(requested)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MeshBackground) {
        Scaffold(containerColor = MeshBackground, bottomBar = {
            NavigationBar(containerColor = MeshSurface) {
                NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Sohbet") })
                NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Icon(Icons.Default.Devices, null) }, label = { Text("Cihazlar") })
                NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Güvenlik") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header(connectionName)
                when (selectedTab) {
                    0 -> ChatScreen(
                        connectionName = connectionName,
                        messages = activeBlePeerId?.let { blePeerMessages[it] } ?: messages,
                        routingContacts = activeChatManager()?.trustedContactsForRouting().orEmpty(),
                        status = status,
                        onSend = { text, fingerprint ->
                            val target = activeChatManager()
                            val sent = if (fingerprint != null) target?.sendToTrustedContact(text, fingerprint) else target?.send(text)
                            if (sent != true) status = "Mesaj gönderilemedi: Bluetooth bağlantısı ve karşı tarafın doğrulanmış QR kimliği gerekli."
                        },
                        onSendMedia = { if (connectionName != null && activeChatManager() != null) mediaPickerLauncher.launch(arrayOf("*/*")) else status = "Dosya göndermek için önce eşe bağlan ve QR kimliğini doğrula." },
                        onListen = { if (manager.hasPermissions()) runWhenBluetoothEnabled { manager.startListening() } else scanOrRequest() }
                    )
                    1 -> DevicesScreen(
                        devices = devices,
                        scanning = scanning,
                        status = status,
                        onScan = ::scanOrRequest,
                        onListen = { if (manager.hasPermissions()) runWhenBluetoothEnabled { manager.startListening() } else scanOrRequest() },
                        blePeers = blePeers,
                        bleStatus = bleStatus,
                        onBleStart = ::startBleOrRequest,
                        onBleConnect = { peer ->
                            activeBlePeerId = peer.device.address
                            connectionName = peer.label
                            bleTransport.connect(peer)
                        },
                        relayOptIn = relayOptIn,
                        nativeFeaturesEnabled = BuildConfig.MESH_NATIVE_ENABLED,
                        relayQueueCount = relayStore.pendingCount(),
                        onRelayOptInChanged = { enabled ->
                            relayStore.setOptIn(enabled)
                            relayOptIn = enabled
                        },
                        onDevice = { peer ->
                            activeBlePeerId = null
                            activePeer = peer
                            runWhenBluetoothEnabled { if (peer.bonded) manager.connect(peer.device) else manager.pair(peer.device) }
                        }
                    )
                    else -> SecurityScreen(
                        connectionName = connectionName,
                        pendingContact = pendingContact,
                        secureChannelActive = secureChannelActive,
                        onContactSaved = { activeChatManager()?.refreshTrust() ?: manager.refreshTrust() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(connectionName: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(MeshViolet, MeshGreen)), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Text("N", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NEXORA MESH", color = MeshGreen, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text(if (connectionName == null) "İnternetsiz Bluetooth mesajlaşma" else "Bağlı: $connectionName", color = MeshMuted, fontSize = 12.sp)
            }
            Text(if (connectionName == null) "ÇEVRİMDIŞI" else "BAĞLI", color = MeshGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF18372A), CircleShape).padding(horizontal = 9.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(15.dp))
        HorizontalDivider(color = Color(0xFF1B3029))
    }
}

@Composable
private fun ChatScreen(
    connectionName: String?,
    messages: SnapshotStateList<BluetoothChatManager.ChatMessage>,
    routingContacts: List<ContactQr.Contact>,
    status: String,
    onSend: (String, String?) -> Unit,
    onSendMedia: () -> Unit,
    onListen: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var routeMenuExpanded by remember { mutableStateOf(false) }
    var routeFingerprint by remember { mutableStateOf<String?>(null) }
    val selectedRoute = routingContacts.firstOrNull { it.fingerprint == routeFingerprint }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Mesajlar", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(if (connectionName == null) "Henüz bağlı cihaz yok. Cihazlar sekmesinden eşleştirip bağlan veya burada gelen bağlantıyı bekle." else "Doğrudan Bluetooth bağlantısı · internet gerekmez", color = MeshMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        if (connectionName == null) {
            OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth()) { Text("Gelen bağlantıyı bekle") }
        }
        Text(status, color = MeshGreen, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        if (routingContacts.isNotEmpty()) {
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { routeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Alıcı: ${selectedRoute?.label ?: "Bağlı BLE eşi"}")
                }
                DropdownMenu(expanded = routeMenuExpanded, onDismissRequest = { routeMenuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Bağlı BLE eşi") }, onClick = {
                        routeFingerprint = null
                        routeMenuExpanded = false
                    })
                    routingContacts.forEach { contact ->
                        DropdownMenuItem(text = { Text(contact.label) }, onClick = {
                            routeFingerprint = contact.fingerprint
                            routeMenuExpanded = false
                        })
                    }
                }
            }
        }
        OutlinedButton(onClick = onSendMedia, enabled = connectionName != null, modifier = Modifier.fillMaxWidth()) {
            Text("Dosya / medya gönder (en fazla 64 MiB)")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 300.dp).background(if (message.outgoing) Color(0xFF16452F) else MeshSurface, RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Text(message.text, color = Color.White)
                        Text(
                            if (!message.outgoing) "Alındı"
                            else if (message.delivered) "Teslim edildi · ACK doğrulandı"
                            else "Teslim bekleniyor",
                            color = MeshMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Mesaj yaz…") }, shape = RoundedCornerShape(22.dp), maxLines = 4,
                enabled = connectionName != null
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { val text = draft.trim(); if (text.isNotEmpty()) { onSend(text, routeFingerprint); draft = "" } }, enabled = draft.isNotBlank() && connectionName != null) {
                Icon(Icons.Default.Send, contentDescription = "Mesaj gönder")
            }
        }
    }
}

@Composable
private fun DevicesScreen(
    devices: List<BluetoothChatManager.Peer>,
    scanning: Boolean,
    status: String,
    onScan: () -> Unit,
    onListen: () -> Unit,
    blePeers: List<BleGattTransport.Peer>,
    bleStatus: String,
    onBleStart: () -> Unit,
    onBleConnect: (BleGattTransport.Peer) -> Unit,
    relayOptIn: Boolean,
    nativeFeaturesEnabled: Boolean,
    relayQueueCount: Int,
    onRelayOptInChanged: (Boolean) -> Unit,
    onDevice: (BluetoothChatManager.Peer) -> Unit
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("Cihaz keşfi", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Arama yalnızca sen başlattığında yapılır. Bilinmeyen cihazı seçmek Android sistem eşleştirmesini başlatır; tanımadığın kişiyi eşleştirme.", color = MeshMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !scanning) { Text(if (scanning) "Aranıyor…" else "Yakındaki cihazları ara") }
            OutlinedButton(onClick = onListen, modifier = Modifier.weight(1f)) { Text("Bağlantı bekle") }
        }
        Text(status, color = MeshGreen, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            devices.forEach { peer ->
                Row(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(16.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Color(0xFF1C4535), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Devices, null, tint = MeshGreen)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(peer.address, color = MeshMuted, fontSize = 11.sp)
                    }
                    Button(onClick = { onDevice(peer) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(if (peer.bonded) "BAĞLAN" else "EŞLEŞTİR", fontSize = 10.sp)
                    }
                }
            }
        }
        if (nativeFeaturesEnabled) {
            HorizontalDivider(color = Color(0xFF1B3029), modifier = Modifier.padding(vertical = 8.dp))
            Text("Ortak BLE GATT", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("BLE keşif/bağlantı taşıması. Bu ekran henüz sohbet metnini BLE zarfına bağlamaz.", color = MeshMuted, fontSize = 12.sp)
            Button(onClick = onBleStart, modifier = Modifier.fillMaxWidth()) { Text("BLE taşımasını başlat") }
            Text(bleStatus, color = MeshGreen, fontSize = 12.sp)
            Row(
                Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(14.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Store-and-forward izni", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("Yalnızca doğrulanmış Rust zarflarını cihazda saklar. Canlı çoklu atlama henüz etkin değil. Kuyruk: $relayQueueCount", color = MeshMuted, fontSize = 11.sp)
                }
                Switch(checked = relayOptIn, onCheckedChange = onRelayOptInChanged)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                blePeers.forEach { peer ->
                    Row(
                        Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Devices, null, tint = MeshGreen)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(peer.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(peer.device.address, color = MeshMuted, fontSize = 10.sp)
                        }
                        Button(onClick = { onBleConnect(peer) }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            Text("BAĞLAN", fontSize = 10.sp)
                        }
                    }
                }
            }
            Text("Bluetooth Classic sohbeti ve BLE GATT taşıması şu anda ayrı prototiplerdir. BLE paketleri Rust zarf doğrulamasına aktarılabilir; uygulama düzeyinde mesaj, QR kimlik doğrulaması ve E2EE henüz BLE üzerinden tamamlanmamıştır.", color = MeshMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            InfoCard(
                "Bluetooth Classic test derlemesi",
                "Bu paket doğrudan Bluetooth Classic sohbetini ve QR ile kişi doğrulamayı içerir. BLE ve çoklu atlama özellikleri bu test paketinde kapalıdır."
            )
        }
    }
}

@Composable
private fun SecurityScreen(
    connectionName: String?,
    pendingContact: ContactQr.Contact?,
    secureChannelActive: Boolean,
    onContactSaved: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val identity = remember { IdentityStore(context).loadOrCreate() }
    val contactStore = remember { TrustedContactStore(context) }
    var savedContacts by remember { mutableStateOf(contactStore.all()) }
    var candidate by remember { mutableStateOf<ContactQr.Contact?>(null) }
    var scanStatus by remember { mutableStateOf("Güvenilir kişi eklemek için diğer kişinin QR kodunu tara.") }
    val qrBitmap = remember(identity.fingerprint) {
        val payload = ContactQr.encode(identity.publicKeyBase64)
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640)
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (x in 0 until 640) for (y in 0 until 640) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    val scanOptions = remember {
        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Nexora Mesh kişi QR kodunu tara")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val raw = result.contents
        if (raw.isNullOrBlank()) {
            scanStatus = "QR taraması iptal edildi."
        } else {
            try {
                candidate = ContactQr.decode(raw)
                scanStatus = "QR biçimi ve açık anahtar parmak izi doğrulandı. Kişiyi kaydetmeden önce karşılaştır."
            } catch (_: Exception) {
                candidate = null
                scanStatus = "QR geçersiz veya parmak izi tutarsız. Kişi kaydedilmedi."
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanLauncher.launch(scanOptions)
        else scanStatus = "QR taramak için kamera izni gerekiyor."
    }
    fun beginScan() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanLauncher.launch(scanOptions)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Güvenlik ve kimlik", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        InfoCard(
            "Bu cihazın kalıcı kimliği",
            "Açık anahtar Android Keystore ile korunan cihaz kimliğine bağlıdır. Özel anahtar, Android Keystore AES-GCM anahtarıyla şifrelenerek yerel depoda tutulur."
        )
        Image(
            bitmap = qrBitmap.asImageBitmap(),
            contentDescription = "Nexora Mesh açık anahtar QR kodu",
            modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally)
        )
        Text("Bu cihazın parmak izi", color = MeshGreen, fontWeight = FontWeight.Bold)
        Text(identity.fingerprint, color = Color.White, fontSize = 11.sp)
        Button(onClick = ::beginScan, modifier = Modifier.fillMaxWidth()) {
            Text("Güvenilir kişi QR kodunu tara")
        }
        Text(scanStatus, color = MeshMuted, fontSize = 12.sp)
        candidate?.let { scanned ->
            InfoCard("Taranan kişi", scanned.label + "\nParmak izi: " + scanned.fingerprint)
            Text(
                "Bu parmak izini diğer kişinin cihazında gösterilen değerle karşılaştır. Yalnızca eşleşiyorsa kaydet.",
                color = MeshMuted,
                fontSize = 12.sp
            )
            Button(
                onClick = {
                    try {
                        contactStore.save(scanned)
                        savedContacts = contactStore.all()
                        candidate = null
                        scanStatus = "QR doğrulanmış kişi güvenilir kişi listesine kaydedildi."
                        onContactSaved()
                    } catch (_: Exception) {
                        scanStatus = "Kişi kaydedilemedi; QR verisini tekrar kontrol et."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Parmak izini kontrol ettim — kişiyi kaydet") }
            OutlinedButton(onClick = { candidate = null }, modifier = Modifier.fillMaxWidth()) {
                Text("İptal")
            }
        }
        pendingContact?.let { pending ->
            InfoCard(
                "Bluetooth karşı tarafı doğrulanmayı bekliyor",
                pending.label + "\nParmak izi: " + pending.fingerprint +
                    "\nBu cihazın parmak izini karşı tarafla QR üzerinden karşılaştırıp onun QR kodunu da bu cihaza kaydet."
            )
        }
        InfoCard(
            "Canlı bağlantı güvenliği",
            if (secureChannelActive) {
                "QR'da kaydedilmiş açık anahtar eşleşti. ECDH + AES-GCM ile şifreli mesaj kanalı etkin."
            } else {
                "Şifreli mesaj gönderimi kapalı. Karşı tarafın QR kimliği bu cihazdaki güvenilir kişiler listesiyle eşleşmeden mesaj gönderilmez."
            }
        )
        InfoCard(
            "Güvenilir kişiler (" + savedContacts.size + ")",
            if (savedContacts.isEmpty()) "Henüz güvenilir kişi kaydedilmedi."
            else savedContacts.joinToString("\n\n") { it.label + "\n" + it.fingerprint }
        )
        if (savedContacts.isNotEmpty()) {
            OutlinedButton(
                onClick = {
                    savedContacts.firstOrNull()?.let { contactStore.remove(it.fingerprint) }
                    savedContacts = contactStore.all()
                    onContactSaved()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Listeden ilk kişiyi kaldır") }
        }
        InfoCard("Bağlantı durumu", connectionName ?: "Şu anda bağlı cihaz yok.")
        InfoCard(
            "Henüz tamamlanmayanlar",
            "Bu değişiklik QR tarama/kayıt ve doğrudan Bluetooth metninde E2EE el sıkışmasını bağlar. Üç cihazlı A→B→C röle, dosya/medya aktarımı ve iPhone–Android radyo taşıması ayrı entegrasyon ve fiziksel cihaz testleri gerektirir."
        )
        InfoCard(
            "Acil durum uyarısı",
            "Deneysel prototiptir; afet veya acil durumlarda güvenilir bir iletişim aracı olarak kullanma. Gerçek cihaz testleri ve bağımsız güvenlik incelemesi gerekiyor."
        )
    }
}
@Composable
private fun InfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = MeshGreen, fontWeight = FontWeight.Bold)
        Text(body, color = Color.White, fontSize = 13.sp)
    }
}
