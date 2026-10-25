package org.nexora.mesh

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

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
    var selectedTab by remember { mutableIntStateOf(0) }
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BluetoothChatManager.Peer>>(emptyList()) }
    var connectionName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Bluetooth bağlantısı henüz kurulmadı.") }
    var activePeer by remember { mutableStateOf<BluetoothChatManager.Peer?>(null) }
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
                        onSend = { text -> if (!manager.send(text)) status = "Mesaj gönderilemedi: önce bir cihaza bağlan." },
                        onListen = { if (manager.hasPermissions()) manager.startListening() else scanOrRequest() }
                    )
                    1 -> DevicesScreen(
                        devices = devices,
                        scanning = scanning,
                        status = status,
                        onScan = ::scanOrRequest,
                        onListen = { if (manager.hasPermissions()) manager.startListening() else scanOrRequest() },
                        onDevice = { peer ->
                            activePeer = peer
                            if (peer.bonded) manager.connect(peer.device) else manager.pair(peer.device)
                        }
                    )
                    else -> SecurityScreen(connectionName)
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
        Text("Not: İlk sürüm Bluetooth Classic kullanır. Her iki cihazda uygulama açık olmalı; bir cihaz gelen bağlantıyı beklerken diğerinden eşleştirilmiş cihaza bağlan. Çok atlamalı mesh aktarımı ve uçtan uca şifreleme henüz eklenmedi.", color = MeshMuted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun SecurityScreen(connectionName: String?) {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Güvenlik ve kimlik", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        InfoCard("Cihaz kimliği", "İlk sürüm Android Bluetooth sistem eşleştirmesine dayanır. Cihaz adı tek başına kimlik kanıtı değildir; yalnızca tanıdığın cihazlarla eşleştir.")
        InfoCard("Bağlantı durumu", connectionName ?: "Şu anda bağlı cihaz yok.")
        InfoCard("Henüz tamamlanmayanlar", "Uygulama seviyesinde doğrulama kodu/QR, uçtan uca şifreleme, kalıcı sohbet geçmişi, Apple uyumluluğu ve ara cihazlarla mesh aktarımı henüz uygulanmadı.")
        InfoCard("Acil durum uyarısı", "Bu sürüm deneysel prototiptir; afet veya acil durumlarda güvenilir bir iletişim aracı olarak kullanma. Gerçek cihaz testleri ve güvenlik incelemesi gerekiyor.")
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Column(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = MeshGreen, fontWeight = FontWeight.Bold)
        Text(body, color = Color.White, fontSize = 13.sp)
    }
}
