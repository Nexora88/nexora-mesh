package org.nexora.mesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MeshBackground = Color(0xFF07110F)
private val MeshSurface = Color(0xFF10201C)
private val MeshGreen = Color(0xFF65F2AD)
private val MeshMuted = Color(0xFF91A69D)

data class ChatPreview(val id: Int, val name: String, val message: String, val time: String, val initials: String)

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
    var selectedTab by remember { mutableIntStateOf(0) }
    var activeChat by remember { mutableStateOf<ChatPreview?>(null) }
    val chats = remember { listOf(
        ChatPreview(1, "Yerel notlar", "Nexora Mesh'e hoş geldin", "Şimdi", "NM"),
        ChatPreview(2, "Yakındaki cihazlar", "Bağlantı bekleniyor", "—", "YC")
    ) }
    Surface(Modifier.fillMaxSize(), color = MeshBackground) {
        if (activeChat != null) ChatScreen(activeChat!!, { activeChat = null })
        else Scaffold(containerColor = MeshBackground, bottomBar = {
            NavigationBar(containerColor = MeshSurface) {
                NavigationBarItem(selectedTab == 0, { selectedTab = 0 }, icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("Sohbetler") })
                NavigationBarItem(selectedTab == 1, { selectedTab = 1 }, icon = { Icon(Icons.Default.Devices, null) }, label = { Text("Cihazlar") })
                NavigationBarItem(selectedTab == 2, { selectedTab = 2 }, icon = { Icon(Icons.Default.Person, null) }, label = { Text("Profil") })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Header()
                when (selectedTab) {
                    0 -> ChatsScreen(chats) { activeChat = it }
                    1 -> DevicesScreen()
                    else -> ProfileScreen()
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MeshGreen, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.NearMe, null, tint = Color(0xFF062017))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("NEXORA MESH", color = MeshGreen, fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp)
                Text("Yakın cihazlarla. İnternet olmadan.", color = MeshMuted, fontSize = 12.sp)
            }
            Text("PROTOTİP", color = MeshGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF18372A), CircleShape).padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Color(0xFF1B3029))
    }
}

@Composable
private fun ChatsScreen(chats: List<ChatPreview>, onOpen: (ChatPreview) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        Text("Mesajların", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Bağlantı kurulana kadar mesajlar yalnızca bu prototipte yereldir.", color = MeshMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            items(chats, key = { it.id }) { chat ->
                Row(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(18.dp)).clickable { onOpen(chat) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).background(Color(0xFF1C4535), CircleShape), contentAlignment = Alignment.Center) {
                        Text(chat.initials, color = MeshGreen, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(chat.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(chat.message, color = MeshMuted, fontSize = 13.sp, maxLines = 1)
                    }
                    Text(chat.time, color = MeshMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(chat: ChatPreview, onBack: () -> Unit) {
    val messages = remember(chat.id) { mutableStateListOf("Bu sohbet şu an yalnızca arayüz prototipidir.") }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MeshSurface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = MeshGreen, fontSize = 30.sp, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.width(14.dp))
            Column {
                Text(chat.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Yerel prototip · radyo bağlantısı yok", color = MeshMuted, fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            messages.forEachIndexed { index, message ->
                Box(Modifier.fillMaxWidth(if (index == 0) 1f else 0.88f)
                    .background(if (index == 0) MeshSurface else Color(0xFF16452F), RoundedCornerShape(16.dp)).padding(13.dp)) {
                    Text(message, color = Color.White)
                }
            }
        }
        Text("Gönderme işlemi başka cihaza iletmez; yalnızca bu ekranda gösterir.", color = MeshMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, modifier = Modifier.weight(1f), placeholder = { Text("Mesaj yaz…") }, shape = RoundedCornerShape(22.dp), maxLines = 4)
            Spacer(Modifier.width(8.dp))
            Button(onClick = { if (draft.isNotBlank()) { messages.add(draft.trim()); draft = "" } }, enabled = draft.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "Yerel prototipe ekle")
            }
        }
    }
}

@Composable
private fun DevicesScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Yakındaki cihazlar", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Text("Bluetooth ve Wi-Fi Direct keşfi sonraki geliştirme adımıdır.", color = MeshMuted)
        Column(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(20.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Devices, null, tint = MeshGreen, modifier = Modifier.size(32.dp))
            Text("Henüz tarama yapılmıyor", color = Color.White, fontWeight = FontWeight.Bold)
            Text("Bu prototip radyo bağlantısı başlatmaz ve yakındaki cihazları bulduğunu iddia etmez.", color = MeshMuted, fontSize = 13.sp)
        }
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Cihaz ara · yakında") }
    }
}

@Composable
private fun ProfileScreen() {
    Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Profil ve güvenlik", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Bu cihaz", color = MeshGreen, fontWeight = FontWeight.Bold)
            Text("Kimlik anahtarı ve cihaz eşleştirme henüz uygulanmadı.", color = Color.White)
        }
        Column(Modifier.fillMaxWidth().background(MeshSurface, RoundedCornerShape(18.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Çevrimdışı tasarım", color = MeshGreen, fontWeight = FontWeight.Bold)
            Text("Uygulamaya internet izni eklenmedi. Gerçek mesajlaşma için yerel radyo aktarımı ve uçtan uca şifreleme geliştirilmelidir.", color = Color.White)
        }
    }
}
