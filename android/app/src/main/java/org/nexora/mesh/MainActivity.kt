package org.nexora.mesh

import android.Manifest
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
private val MeshGreen = Color(0xFF65F2AD)
private val MeshMuted = Color(0xFF91A69D)

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
    var pendingContact by remember { mutableStateOf<ContactQr.Contact?>(null) }
    var secureChannelActive by remember { mutableStateOf(false) }
    val messages = remember { mutableStateListOf<BluetoothChatManager.ChatMessage>() }

    DisposableEffect(manager) {
        val listener = object : BluetoothChatManager.Listener {
            override fun onDevicesChanged(next: List<BluetoothChatManager.Peer>, isScanning: Boolean) {
                devices = next
                scanning = isScanning
            }
            override fun onConnectionChanged(connectedName: String?) { connectionName = connectedName }
            override fun onMessage(message: BluetoothChatManager.ChatMessage) { messages.add(message) }
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

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) manager.startDiscovery()
        else status = "Cihaz keşfi için Bluetooth izinleri verilmedi."
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.all { it }) bleTransport.start()
        else bleStatus = "BLE keşfi/yayını için Bluetooth izinleri verilmedi."
    }

    DisposableEffect(bleTransport) {
        bleTransport.setListener(object : BleGattTransport.Listener {
            override fun onPeers(peers: List<BleGattTransport.Peer>) { blePeers = peers }
            override fun onStatus(value: String) { bleStatus = value }
            override fun onPacket(packet: ByteArray) {
                // GATT packet boundaries feed the same QR-trust, proof-handshake, and AEAD verifier
                // used by the existing chat protocol. Receiving a packet alone never marks a peer trusted.
                if (relayOptIn && relayStore.enqueue(packet)) {
                    bleStatus = "Şifreli Rust zarfı yerel relay kuyruğuna kaydedildi."
                }
                manager.receiveBlePacket(packet)
            }
            override fun onConnected(peerLabel: String) {
                manager.attachBleTransport(peerLabel) { packet -> bleTransport.sendEnvelope(packet) }
                bleStatus = "BLE çift yönlü kanal hazır; QR ile güven doğrulaması bekleniyor."
            }
            override fun onDisconnected() {
                manager.closeConnection()
                bleStatus = "BLE bağlantısı kesildi; güvenli oturum kapatıldı."
            }
        })
        onDispose {
            bleTransport.setListener(null)
            bleTransport.stop()
        }
    }

    fun startBleOrRequest() {
        val required = if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            bleTransport.start()
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
                    if (!manager.sendMedia(output.toByteArray(), name)) {
                        (context as? Activity)?.runOnUiThread { status = "Dosya gönderilemedi; güvenli bağlantıyı ve 64 MiB sınırını kontrol et." }
                    }
                } catch (_: Exception) {
                    (context as? Activity)?.runOnUiThread { status = "Dosya okunamadı veya 64 MiB aktarım sınırını aşıyor." }
                }
            }, "mesh-media-send").start()
        }
    }
    fun scanOrRequest() {
        if (manager.hasPermissions()) manager.startDiscovery()
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
                        messages = messages,
                        status = status,
                        onSend = { text -> if (!manager.send(text)) status = "Mesaj gönderilemedi: QR doğrulamalı şifreli bağlantı gerekli." },
                        onSendMedia = { if (connectionName != null) mediaPickerLauncher.launch(arrayOf("*/*")) else status = "Dosya göndermek için önce bağlan ve QR kimliğini doğrula." },
                        onListen = { if (manager.hasPermissions()) manager.startListening() else scanOrRequest() }
                    )
                    1 -> DevicesScreen(
                        devices = devices,
                        scanning = scanning,
                        status = status,
                        onScan = ::scanOrRequest,
                        onListen = { if (manager.hasPermissions()) manager.startListening() else scanOrRequest() },
                        blePeers = blePeers,
                        bleStatus = bleStatus,
                        onBleStart = ::startBleOrRequest,
                        onBleConnect = { peer -> bleTransport.connect(peer) },
                        relayOptIn = relayOptIn,
                        relayQueueCount = relayStore.pendingCount(),
                        onRelayOptInChanged = { enabled ->
                            relayStore.setOptIn(enabled)
                            relayOptIn = enabled
                        },
                        onDevice = { peer ->
                            activePeer = peer
                            if (peer.bonded) manager.connect(peer.device) else manager.pair(peer.device)
                        }
                    )
                    else -> SecurityScreen(
                        connectionName = connectionName,
                        pendingContact = pendingContact,
                        secureChannelActive = secureChannelActive,
                        onContactSaved = { manager.refreshTrust() }
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
            Box(Modifier.size(42.dp).background(MeshGreen, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.NearMe, null, tint = Color(0xFF062017))
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
    status: String,
    onSend: (String) -> Unit,
    onSendMedia: () -> Unit,
    onListen: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text("Mesajlar", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text(if (connectionName == null) "Henüz bağlı cihaz yok. Cihazlar sekmesinden eşleştirip bağlan veya burada gelen bağlantıyı bekle." else "Doğrudan Bluetooth bağlantısı · internet gerekmez", color = MeshMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp, bottom = 10.dp))
        if (connectionName == null) {
            OutlinedButton(onClick = onListen, modifier = Modifier.fillMaxWidth()) { Text("Gelen bağlantıyı bekle") }
        }
        Text(status, color = MeshGreen, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        OutlinedButton(onClick = onSendMedia, enabled = connectionName != null, modifier = Modifier.fillMaxWidth()) {
            Text("Dosya / medya gönder (en fazla 64 MiB)")
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 300.dp).background(if (message.outgoing) Color(0xFF16452F) else MeshSurface, RoundedCornerShape(16.dp)).padding(12.dp)) {
                        Text(message.text, color = Color.White)
                        Text(if (message.outgoing) "Gönderildi · yerel bağlantı" else "Alındı", color = MeshMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
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
            Button(onClick = { val text = draft.trim(); if (text.isNotEmpty()) { onSend(text); draft = "" } }, enabled = draft.isNotBlank() && connectionName != null) {
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
    relayQueueCount: Int,
    onRelayOptInChanged: (Boolean) -> Unit,
    onDevice: (BluetoothChatManager.Peer) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("Cihaz keşfi", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Arama yalnızca sen başlattığında yapılır. Bilinmeyen cihazı seçmek Android sistem eşleştirmesini başlatır; tanımadığın kişiyi eşleştirme.", color = MeshMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onScan, modifier = Modifier.weight(1f), enabled = !scanning) { Text(if (scanning) "Aranıyor…" else "Yakındaki cihazları ara") }
            OutlinedButton(onClick = onListen, modifier = Modifier.weight(1f)) { Text("Bağlantı bekle") }
        }
        Text(status, color = MeshGreen, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.address }) { peer ->
                Row(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(16.dp)).clickable { onDevice(peer) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(Color(0xFF1C4535), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Devices, null, tint = MeshGreen)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(peer.address, color = MeshMuted, fontSize = 11.sp)
                    }
                    Text(if (peer.bonded) "EŞLEŞTİ" else "EŞLEŞTİR", color = MeshGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
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
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blePeers, key = { it.device.address }) { peer ->
                Row(
                    Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(14.dp))
                        .clickable { onBleConnect(peer) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Devices, null, tint = MeshGreen)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(peer.label, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(peer.device.address, color = MeshMuted, fontSize = 10.sp)
                    }
                    Text("BAĞLAN", color = MeshGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text("Bluetooth Classic sohbeti ve BLE GATT taşıması şu anda ayrı prototiplerdir. BLE paketleri Rust zarf doğrulamasına aktarılabilir; uygulama düzeyinde mesaj, QR kimlik doğrulaması ve E2EE henüz BLE üzerinden tamamlanmamıştır.", color = MeshMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
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
