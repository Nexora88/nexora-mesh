package org.nexora.mesh

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val MeshBackground = Color(0xFF0B141A)
private val MeshSurface = Color(0xFF111B21)
private val MeshGreen = Color(0xFF25D366)
private val MeshTeal = Color(0xFF1FA855)
private val MeshMuted = Color(0xFF8696A0)
private val MeshBubbleOut = Color(0xFF005C4B)
private val MeshBubbleIn = Color(0xFF1F2C34)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MeshTheme { MeshApp() } }
    }
}

@Composable
private fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MeshGreen,
            onPrimary = Color(0xFF052E16),
            background = MeshBackground,
            surface = MeshSurface,
            onSurface = Color(0xFFE9EDEF),
            secondary = MeshMuted
        ),
        content = content
    )
}

@Composable
private fun MeshApp() {
    val context = LocalContext.current
    val store = remember { MessageStore(context) }
    val conversations = remember { store.defaultConversations() }
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeChatId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val previews = remember(refreshTick) { store.previews(conversations) }
    val activeConversation = conversations.firstOrNull { it.id == activeChatId }

    Surface(Modifier = Modifier.fillMaxSize(), color = MeshBackground) {
        if (activeConversation != null) {
            ChatScreen(
                conversation = activeConversation,
                store = store,
                onBack = { activeChatId = null; refreshTick++ },
                onChanged = { refreshTick++ }
            )
        } else {
            Scaffold(
                containerColor = MeshBackground,
                bottomBar = {
                    NavigationBar(containerColor = MeshSurface) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.ChatBubbleOutline, null) },
                            label = { Text("Sohbetler") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Devices, null) },
                            label = { Text("Cihazlar") }
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = { Icon(Icons.Default.Person, null) },
                            label = { Text("Profil") }
                        )
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    HeaderBar()
                    when (selectedTab) {
                        0 -> ChatsScreen(previews) { activeChatId = it.conversation.id }
                        1 -> DevicesScreen()
                        else -> ProfileScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderBar() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MeshGreen, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.NearMe, null, tint = Color(0xFF052E16))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Nexora", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Çevrimdışı mesh mesajlaşma", color = MeshMuted, fontSize = 12.sp)
            }
            Text(
                "v0.2",
                color = MeshGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(Color(0xFF1A3A2A), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun ChatsScreen(previews: List<ConversationPreview>, onOpen: (ConversationPreview) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Sohbetler", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        LazyColumn {
            items(previews, key = { it.conversation.id }) { preview ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(preview) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(52.dp).background(Color(0xFF2A3942), CircleShape), contentAlignment = Alignment.Center) {
                        Text(preview.conversation.initials, color = MeshGreen, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(preview.conversation.title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(preview.lastTimeLabel, color = MeshMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(preview.lastMessage, color = MeshMuted, fontSize = 14.sp, maxLines = 1)
                    }
                }
                HorizontalDivider(color = Color(0xFF222E35), modifier = Modifier.padding(start = 82.dp))
            }
        }
    }
}

@Composable
private fun ChatScreen(
    conversation: Conversation,
    store: MessageStore,
    onBack: () -> Unit,
    onChanged: () -> Unit
) {
    var messages by remember(conversation.id) { mutableStateOf(store.loadMessages(conversation.id)) }
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val handler = remember { Handler(Looper.getMainLooper()) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun send() {
        val text = draft.trim()
        if (text.isEmpty()) return
        val msg = MessageStore.newOutgoing(conversation.id, text)
        messages = store.append(conversation.id, msg)
        draft = ""
        onChanged()
        handler.postDelayed({
            messages = store.updateStatus(conversation.id, msg.id, DeliveryStatus.SENT)
            onChanged()
        }, 350)
        handler.postDelayed({
            messages = store.updateStatus(conversation.id, msg.id, DeliveryStatus.DELIVERED)
            onChanged()
        }, 900)
    }

    Column(Modifier.fillMaxSize().background(MeshBackground)) {
        Row(Modifier.fillMaxWidth().background(MeshSurface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = MeshGreen, fontSize = 28.sp, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(40.dp).background(Color(0xFF2A3942), CircleShape), contentAlignment = Alignment.Center) {
                Text(conversation.initials, color = MeshGreen, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(conversation.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    if (conversation.isLocalOnly) "Yalnızca bu cihaz" else "Mesh · radyo bağlanınca gerçek iletim",
                    color = MeshMuted, fontSize = 12.sp
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { message -> MessageBubble(message) }
        }

        Row(Modifier.fillMaxWidth().background(MeshSurface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mesaj", color = MeshMuted) },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF2A3942),
                    unfocusedBorderColor = Color(0xFF2A3942),
                    focusedContainerColor = Color(0xFF2A3942),
                    unfocusedContainerColor = Color(0xFF2A3942)
                )
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { send() },
                enabled = draft.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MeshTeal)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gönder", tint = Color.White)
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val mine = message.isOutgoing
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp)
                .background(
                    if (mine) MeshBubbleOut else MeshBubbleIn,
                    RoundedCornerShape(
                        topStart = 12.dp, topEnd = 12.dp,
                        bottomStart = if (mine) 12.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 12.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(message.body, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(MessageStore.formatTime(message.timestampMs), color = MeshMuted, fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    StatusTicks(message.status)
                }
            }
        }
    }
}

@Composable
private fun StatusTicks(status: DeliveryStatus) {
    when (status) {
        DeliveryStatus.QUEUED -> Icon(Icons.Default.Schedule, contentDescription = "Kuyrukta", tint = MeshMuted, modifier = Modifier.size(14.dp))
        DeliveryStatus.SENT -> Icon(Icons.Default.Check, contentDescription = "Gönderildi", tint = MeshMuted, modifier = Modifier.size(14.dp))
        DeliveryStatus.DELIVERED -> Row {
            Icon(Icons.Default.Check, null, tint = Color(0xFF53BDEB), modifier = Modifier.size(14.dp))
            Icon(Icons.Default.Check, null, tint = Color(0xFF53BDEB), modifier = Modifier.size(14.dp).offset(x = (-6).dp))
        }
        DeliveryStatus.FAILED -> Text("!", color = Color(0xFFFF6B6B), fontSize = 12.sp)
        DeliveryStatus.RECEIVED -> {}
    }
}

@Composable
private fun DevicesScreen() {
    var discovering by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }

    LaunchedEffect(discovering) {
        if (discovering) {
            secondsLeft = 60
            while (secondsLeft > 0 && discovering) {
                delay(1000)
                secondsLeft--
            }
            discovering = false
        }
    }

    val power = MeshCoreBridge.evaluatePower(LocalContext.current)

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Cihazlar", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Keşif varsayılan olarak kapalıdır. Yalnızca sen başlattığında ve süre dolana kadar yakındaki cihazlar görülebilir.", color = MeshMuted, fontSize = 14.sp)

        Card(colors = CardDefaults.cardColors(containerColor = MeshSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Güç politikası", color = MeshGreen, fontWeight = FontWeight.SemiBold)
                Text("Pil %${power.batteryPercent} · tarama aralığı ${power.recommendedScanIntervalSecs}s · iletme ${if (power.mayForward) "açık" else "kapalı"}", color = Color.White, fontSize = 13.sp)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MeshSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (discovering) "Keşif açık · ${secondsLeft}s" else "Keşif kapalı", color = Color.White, fontWeight = FontWeight.Bold)
                Text("Gerçek BLE tarama bir sonraki adımda nm_process_incoming ile bağlanacak.", color = MeshMuted, fontSize = 13.sp)
                Button(
                    onClick = { discovering = !discovering },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (discovering) Color(0xFF3A2A2A) else MeshTeal)
                ) {
                    Text(if (discovering) "Keşfi durdur" else "60 sn güvenli keşif başlat")
                }
            }
        }

        Text("Güvenilir kişiler", color = Color.White, fontWeight = FontWeight.SemiBold)
        Text("Eşleştirme (QR / PIN) tamamlanınca burada listelenir. Keşif ≠ güven.", color = MeshMuted, fontSize = 13.sp)
    }
}

@Composable
private fun ProfileScreen() {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Profil", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = MeshSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Bu cihaz", color = MeshGreen, fontWeight = FontWeight.SemiBold)
                Text("Yerel mesajlar cihazda saklanır. Kimlik anahtarı ve ephemeral ID Rust çekirdeğinde döner.", color = Color.White, fontSize = 14.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MeshSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Gizlilik", color = MeshGreen, fontWeight = FontWeight.SemiBold)
                Text("• Keşif kapalı başlar\n• Eşleştirme iki taraflı onay ister\n• Ara düğümler kör paket taşır\n• Acil durum aracı değildir", color = Color.White, fontSize = 14.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = MeshSurface), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ne çalışıyor / ne yok", color = MeshGreen, fontWeight = FontWeight.SemiBold)
                Text("Çalışıyor: sohbet listesi, baloncuklar, teslim durumları, yerel kalıcılık, güç politikası UI.\nYok: gerçek BLE, E2E şifreleme, medya, arama.", color = Color.White, fontSize = 14.sp)
            }
        }
    }
}
