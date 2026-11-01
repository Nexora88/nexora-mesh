package org.nexora.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct, internet-independent Bluetooth Classic chat transport.
 * Trust is deliberately limited to Android OS-bonded devices selected by the user.
 * This is a first transport milestone, not mesh routing or audited end-to-end encryption.
 */
class BluetoothChatManager(context: Context) {
    data class Peer(val device: BluetoothDevice, val name: String, val address: String, val bonded: Boolean)
    data class ChatMessage(val text: String, val outgoing: Boolean, val timestamp: Long = System.currentTimeMillis())

    interface Listener {
        fun onDevicesChanged(devices: List<Peer>, scanning: Boolean)
        fun onConnectionChanged(connectedName: String?)
        fun onMessage(message: ChatMessage)
        fun onStatus(status: String)
        fun onPeerIdentityPending(contact: ContactQr.Contact) {}
        fun onSecurityChanged(verified: Boolean, fingerprint: String?) {}
        fun onMediaReceived(fileName: String, savedPath: String, sizeBytes: Int) {}
    }

    private val appContext = context.applicationContext
    private val identity = IdentityStore(appContext).loadOrCreate()
    private val trustedContacts = TrustedContactStore(appContext)
    private val wireLock = Any()
    private val deliveryPrefs = appContext.getSharedPreferences("mesh_delivery_v1", Context.MODE_PRIVATE)
    private val replayWindow = ReplayWindow(
        initial = run {
            val stored = deliveryPrefs.getString("received_ids", null)
            if (stored.isNullOrBlank()) emptyList() else try {
                val array = org.json.JSONArray(stored)
                (0 until array.length()).map { array.getString(it) }
            } catch (_: Exception) { emptyList() }
        },
        maxSize = MAX_REPLAY_IDS,
        persist = { ids ->
            val array = org.json.JSONArray()
            ids.forEach { array.put(it) }
            deliveryPrefs.edit().putString("received_ids", array.toString()).commit()
        }
    )
    private val pendingLock = Any()
    private val retryAttempts = ConcurrentHashMap<String, Int>().apply {
        runCatching {
            val saved = deliveryPrefs.getString("retry_attempts", "[]")
            val rows = org.json.JSONArray(saved)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                put(row.getString("id"), row.getInt("attempts").coerceIn(0, MAX_DELIVERY_RETRIES))
            }
        }
    }
    private val retryScheduled = ConcurrentHashMap.newKeySet<String>()
    private val pendingMessages = PendingDeliveryQueue(
        initial = run {
            val restored = LinkedHashMap<String, String>()
            val stored = deliveryPrefs.getString("pending", null)
            if (!stored.isNullOrBlank()) try {
                val array = org.json.JSONArray(stored)
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    restored[item.getString("id")] = item.getString("frame")
                }
            } catch (_: Exception) { restored.clear() }
            restored
        },
        maxSize = MAX_PENDING_MESSAGES,
        persist = { snapshot ->
            val array = org.json.JSONArray()
            snapshot.forEach { (id, frame) -> array.put(JSONObject().put("id", id).put("frame", frame)) }
            deliveryPrefs.edit().putString("pending", array.toString()).commit()
        }
    )
    @Volatile private var secureChannel: SecureChannel? = null
    @Volatile private var candidateChannel: SecureChannel? = null
    @Volatile private var localProofNonce: String? = null
    @Volatile private var remoteProofReceived = false
    @Volatile private var localProofAcked = false
    @Volatile private var remoteIdentity: ContactQr.Contact? = null
    @Volatile private var remoteFingerprint: String? = null
    private val mediaReassemblers = ConcurrentHashMap<String, MediaTransferProtocol.Reassembler>()
    private val mediaNames = ConcurrentHashMap<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val found = linkedMapOf<String, BluetoothDevice>()
    @Volatile private var scanning = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var bleSender: ((ByteArray) -> Boolean)? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var connectedName: String? = null
    private val running = AtomicBoolean(false)
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
                    device?.let {
                        synchronized(found) { found[it.address] = it }
                        publishDevices()
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanning = false
                    publishDevices()
                    status("Cihaz araması tamamlandı.")
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> publishDevices()
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (adapter?.state != BluetoothAdapter.STATE_ON) {
                        scanning = false
                        closeConnection()
                        publishDevices()
                    } else publishDevices()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") (appContext.registerReceiver(receiver, filter))
        receiverRegistered = true
    }

    fun addListener(listener: Listener) { listeners.add(listener); publishDevices(); listener.onConnectionChanged(connectedName) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }
    }

    @SuppressLint("MissingPermission")
    fun isBluetoothSupported() = adapter != null
    @SuppressLint("MissingPermission")
    fun isBluetoothEnabled() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        if (!hasPermissions()) { status("Bluetooth izinleri gerekli."); return }
        val a = adapter ?: run { status("Bu cihaz Bluetooth desteklemiyor."); return }
        if (!a.isEnabled) { status("Önce Bluetooth'u sistem ayarlarından aç."); return }
        if (scanning) a.cancelDiscovery()
        synchronized(found) { found.clear() }
        scanning = a.startDiscovery()
        publishDevices()
        status(if (scanning) "Yakındaki cihazlar aranıyor. Eşleşme için Android sistem onayı gerekir." else "Arama başlatılamadı; Bluetooth izinlerini ve ayarlarını kontrol et.")
    }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<Peer> {
        if (!hasPermissions()) return emptyList()
        return try { adapter?.bondedDevices.orEmpty().map { Peer(it, safeName(it), it.address, true) } }
        catch (_: SecurityException) { emptyList() }
    }

    @SuppressLint("MissingPermission")
    fun discoveredDevices(): List<Peer> {
        val discovered = synchronized(found) { found.values.toList() }
        val bonded = bondedDevices()
        val all = (bonded.map { it.device } + discovered).distinctBy { it.address }
        return all.map { Peer(it, safeName(it), it.address, it.bondState == BluetoothDevice.BOND_BONDED) }
    }

    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice) {
        if (!hasPermissions()) { status("Bluetooth izinleri gerekli."); return }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            status("Bu cihaz Android tarafından zaten eşleştirilmiş. Bağlanmak için cihazı seç.")
            return
        }
        status("Android sistem eşleştirme isteği açılıyor. Eşleştirme kodunu iki cihazda da doğrula.")
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
            device.createBond()
        } catch (_: SecurityException) { status("Eşleştirme izni verilmedi.") }
    }

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasPermissions()) { status("Bluetooth izinleri gerekli."); return }
        val a = adapter ?: run { status("Bluetooth desteklenmiyor."); return }
        if (!a.isEnabled) { status("Önce Bluetooth'u aç."); return }
        if (!running.compareAndSet(false, true)) { status("Gelen bağlantı bekleniyor."); return }
        Thread({
            try {
                val server = a.listenUsingRfcommWithServiceRecord("Nexora Mesh", SERVICE_UUID)
                serverSocket = server
                status("Gelen güvenli Bluetooth bağlantısı bekleniyor.")
                val accepted = server.accept()
                serverSocket?.close()
                serverSocket = null
                attach(accepted)
            } catch (e: Exception) {
                running.set(false)
                if (serverSocket != null) status("Dinleme başarısız: Bluetooth ve izinleri kontrol et.")
                serverSocket = null
            }
        }, "mesh-bt-listen").start()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermissions()) { status("Bluetooth izinleri gerekli."); return }
        val a = adapter ?: return status("Bluetooth desteklenmiyor.")
        if (device.bondState != BluetoothDevice.BOND_BONDED) return status("Önce bu cihazı Android Bluetooth ayarlarından eşleştir.")
        if (!running.compareAndSet(false, true)) return status("Bağlantı kuruluyor veya zaten bağlı.")
        Thread({
            try {
                if (a.isDiscovering) a.cancelDiscovery()
                val client = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                client.connect()
                attach(client)
            } catch (e: Exception) {
                running.set(false)
                status("Bağlantı kurulamadı. İki cihazda Nexora Mesh dinlemeyi/bağlanmayı kontrol et.")
            }
        }, "mesh-bt-connect").start()
    }

    private fun attach(newSocket: BluetoothSocket) {
        closeSocketOnly()
        socket = newSocket
        connectedName = try { newSocket.remoteDevice.name ?: "Eşleştirilmiş cihaz" } catch (_: Exception) { "Eşleştirilmiş cihaz" }
        secureChannel = null
        candidateChannel = null
        localProofNonce = null
        remoteProofReceived = false
        localProofAcked = false
        remoteIdentity = null
        remoteFingerprint = null
        running.set(true)
        mainHandler.post { listeners.forEach { it.onConnectionChanged(connectedName); it.onSecurityChanged(false, null) }; status("Bluetooth bağlantısı kuruldu. E2EE için diğer kişinin QR kimliğini doğrula.") }
        sendHello()
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(newSocket.inputStream, StandardCharsets.UTF_8))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    handleWireLine(line)
                }
            } catch (_: Exception) { }
            closeConnection()
            status("Bluetooth bağlantısı kapandı.")
        }, "mesh-bt-reader").start()
    }

    /** Attach the existing QR-trust and encrypted wire protocol to an established BLE packet transport. */
    fun attachBleTransport(peerName: String, sender: (ByteArray) -> Boolean) {
        closeConnection()
        bleSender = sender
        connectedName = peerName.take(80).ifBlank { "Nexora BLE cihazı" }
        secureChannel = null
        candidateChannel = null
        localProofNonce = null
        remoteProofReceived = false
        localProofAcked = false
        remoteIdentity = null
        remoteFingerprint = null
        // Preserve replay history across reconnects to prevent duplicate delivery.
        mediaReassemblers.clear()
        mediaNames.clear()
        running.set(true)
        mainHandler.post {
            listeners.forEach { it.onConnectionChanged(connectedName); it.onSecurityChanged(false, null) }
            status("BLE bağlı. Mesajlaşma için karşı tarafın QR kimliği doğrulanmalı.")
        }
        sendHello()
    }

    /** Accept one complete bounded BLE packet and route it through the existing protocol verifier. */
    fun receiveBlePacket(packet: ByteArray) {
        if (packet.isEmpty() || packet.size > MAX_WIRE_BYTES) {
            status("BLE paketi boyut sınırını aştığı için reddedildi.")
            return
        }
        val line = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(packet)).toString()
        } catch (_: Exception) {
            status("BLE paketi geçerli UTF-8 değil; reddedildi.")
            return
        }
        if (line.contains('\n') || line.contains('\r')) {
            status("BLE paketinde beklenmeyen satır ayırıcı var; reddedildi.")
            return
        }
        handleWireLine(line)
    }

    fun send(text: String): Boolean {
        if (text.isBlank()) return false
        if (socket == null && bleSender == null) return false
        val channel = secureChannel ?: run {
            status("Mesaj engellendi: karşı tarafın QR parmak izi doğrulanmadan şifreli kanal açılmaz.")
            return false
        }
        return try {
            val plaintext = text.trim().toByteArray(StandardCharsets.UTF_8)
            val messageId = UUID.randomUUID().toString().lowercase()
            val sealed = channel.encrypt(plaintext, dataAad(messageId))
            val frame = JSONObject()
                .put("t", "DATA")
                .put("id", messageId)
                .put("n", Base64.encodeToString(sealed.nonce, Base64.NO_WRAP))
                .put("c", Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
            if (!enqueuePending(messageId, frame)) {
                status("Çevrimdışı kuyruk dolu veya kalıcı kaydedilemedi; mesaj gönderilmedi.")
                return false
            }
            val sent = sendWire(frame)
            mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(text.trim(), true)) } }
            if (!sent) status("Mesaj şifrelenip çevrimdışı kuyruğa kaydedildi; teslim onayı bekleniyor.")
            scheduleDeliveryRetry(messageId, 0)
            true
        } catch (_: Exception) {
            status("Şifreli mesaj gönderilemedi.")
            false
        }
    }

    /** Re-checks a pending Bluetooth identity after the user scans and saves its QR. */
    fun refreshTrust() {
        val remote = remoteIdentity ?: run {
            status("Henüz karşı tarafın Bluetooth kimliği alınmadı.")
            return
        }
        if (activateIfTrusted(remote, forceRestart = true)) {
            if (secureChannel != null) status("Karşı taraf doğrulanmış ve şifreli kanal etkin.")
            else status("Parmak izi eşleşti; kriptografik anahtar sahipliği el sıkışması bekleniyor.")
        } else {
            secureChannel = null
            candidateChannel = null
            localProofNonce = null
            remoteProofReceived = false
            localProofAcked = false
            mainHandler.post { listeners.forEach { it.onSecurityChanged(false, remote.fingerprint) } }
            status("Güvenli kanal kapalı: karşı tarafın QR kodunu tara ve parmak izini doğrula.")
        }
    }

    fun isEndToEndEncrypted(): Boolean = secureChannel != null

    private fun sendHello() {
        try {
            sendWire(JSONObject().put("t", "HELLO").put("k", identity.publicKeyBase64).put("f", identity.fingerprint))
        } catch (_: Exception) { status("Kimlik el sıkışması başlatılamadı.") }
    }

    private fun handleWireLine(line: String) {
        try {
            require(line.length <= 2 * 1024 * 1024) { "Frame too large" }
            val frame = JSONObject(line)
            when (frame.getString("t")) {
                "HELLO" -> {
                    val keyText = frame.getString("k")
                    val keyBytes = Base64.decode(keyText, Base64.DEFAULT)
                    val actualFingerprint = SecureChannel.fingerprint(keyBytes)
                    val claimedFingerprint = frame.getString("f")
                    require(MessageDigest.isEqual(actualFingerprint.toByteArray(), claimedFingerprint.toByteArray())) {
                        "Peer fingerprint mismatch"
                    }
                    SecureChannel.decodePublicKey(keyText)
                    val contact = ContactQr.Contact(
                        version = 1,
                        publicKeyBase64 = keyText,
                        fingerprint = actualFingerprint,
                        label = connectedName ?: "Nexora Mesh"
                    )
                    remoteIdentity = contact
                    if (!activateIfTrusted(contact)) {
                        mainHandler.post {
                            listeners.forEach { it.onPeerIdentityPending(contact); it.onSecurityChanged(false, contact.fingerprint) }
                            status("Karşı tarafın kimliği alındı; E2EE için onun QR kodunu tara ve kaydet.")
                        }
                    }
                }
                "PROOF" -> {
                    val channel = candidateChannel ?: secureChannel ?: throw SecurityException("No candidate session key")
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val control = JSONObject(channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), CONTROL_AAD).toString(StandardCharsets.UTF_8))
                    require(control.getString("type") == "PROOF")
                    val peerNonce = control.getString("nonce")
                    require(Base64.decode(peerNonce, Base64.DEFAULT).size == 32)
                    remoteProofReceived = true
                    val ack = JSONObject().put("type", "PROOF_ACK").put("nonce", peerNonce).toString().toByteArray(StandardCharsets.UTF_8)
                    sendEncryptedControl("PROOF_ACK", ack, channel)
                    maybeActivateSecureChannel()
                }
                "PROOF_ACK" -> {
                    val channel = candidateChannel ?: secureChannel ?: throw SecurityException("No candidate session key")
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val control = JSONObject(channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), CONTROL_AAD).toString(StandardCharsets.UTF_8))
                    require(control.getString("type") == "PROOF_ACK")
                    require(control.getString("nonce") == localProofNonce) { "Proof acknowledgement does not match this session" }
                    localProofAcked = true
                    maybeActivateSecureChannel()
                }
                "DATA" -> {
                    val channel = secureChannel ?: throw SecurityException("Unverified peer sent data")
                    val messageId = frame.getString("id")
                    require(messageId.length == 36 && UUID.fromString(messageId).toString() == messageId) { "Invalid message id" }
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val plaintext = channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), dataAad(messageId))
                    val isNew = rememberIncomingMessageId(messageId)
                    sendDeliveryAck(messageId, channel)
                    if (isNew) {
                        val text = plaintext.toString(StandardCharsets.UTF_8)
                        mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(text, false)) } }
                    }
                }
                "ACK" -> {
                    val channel = secureChannel ?: throw SecurityException("Unverified peer sent acknowledgement")
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val control = JSONObject(channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), CONTROL_AAD).toString(StandardCharsets.UTF_8))
                    require(control.getString("type") == "DELIVERY_ACK")
                    val messageId = control.getString("id")
                    require(messageId.length == 36 && UUID.fromString(messageId).toString() == messageId)
                    acknowledgePending(messageId)
                }
                "MEDIA" -> {
                    val channel = secureChannel ?: throw SecurityException("Unverified peer sent media")
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val plaintext = channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), MEDIA_AAD)
                    acceptMediaChunk(JSONObject(plaintext.toString(StandardCharsets.UTF_8)))
                }
                else -> throw IllegalArgumentException("Unknown wire frame")
            }
        } catch (error: Exception) {
            status("Gelen paket reddedildi: biçim, güven veya şifre doğrulaması başarısız.")
        }
    }

    private fun activateIfTrusted(contact: ContactQr.Contact, forceRestart: Boolean = false): Boolean {
        val trusted = trustedContacts.all().firstOrNull {
            it.fingerprint.equals(contact.fingerprint, ignoreCase = true)
        } ?: return false
        val storedBytes = Base64.decode(trusted.publicKeyBase64, Base64.DEFAULT)
        val remoteBytes = Base64.decode(contact.publicKeyBase64, Base64.DEFAULT)
        if (!MessageDigest.isEqual(storedBytes, remoteBytes)) return false
        return try {
            if (!forceRestart && candidateChannel != null && remoteFingerprint == contact.fingerprint) return true
            val channel = SecureChannel.derive(identity, SecureChannel.decodePublicKey(contact.publicKeyBase64))
            candidateChannel = channel
            secureChannel = null
            remoteFingerprint = contact.fingerprint
            remoteProofReceived = false
            localProofAcked = false
            localProofNonce = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
            val proof = JSONObject().put("type", "PROOF").put("nonce", localProofNonce)
                .toString().toByteArray(StandardCharsets.UTF_8)
            if (!sendEncryptedControl("PROOF", proof, channel)) {
                candidateChannel = null
                localProofNonce = null
                return false
            }
            mainHandler.post {
                listeners.forEach { it.onSecurityChanged(false, contact.fingerprint) }
                status("QR parmak izi eşleşti; karşı tarafın anahtar sahipliği şifreli el sıkışmasıyla doğrulanıyor.")
            }
            true
        } catch (_: Exception) {
            candidateChannel = null
            secureChannel = null
            false
        }
    }

    /** Sends bounded file/media chunks through the authenticated encrypted Bluetooth channel. */
    fun sendMedia(media: ByteArray, fileName: String): Boolean {
        val channel = secureChannel ?: run {
            status("Dosya gönderimi için önce QR parmak izi doğrulaması gerekir.")
            return false
        }
        if (socket == null && bleSender == null) return false
        return try {
            val safeName = fileName.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "media.bin" }
            val transferId = UUID.randomUUID().toString()
            val chunks = MediaTransferProtocol.split(transferId, media)
            for (chunk in chunks) {
                val payload = JSONObject()
                    .put("id", chunk.transferId)
                    .put("name", safeName)
                    .put("i", chunk.index)
                    .put("n", chunk.total)
                    .put("size", chunk.totalBytes)
                    .put("sha", Base64.encodeToString(chunk.sha256, Base64.NO_WRAP))
                    .put("data", Base64.encodeToString(chunk.payload, Base64.NO_WRAP))
                    .toString().toByteArray(StandardCharsets.UTF_8)
                val sealed = channel.encrypt(payload, MEDIA_AAD)
                val frame = JSONObject()
                    .put("t", "MEDIA")
                    .put("n", Base64.encodeToString(sealed.nonce, Base64.NO_WRAP))
                    .put("c", Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
                if (!sendWire(frame)) throw IllegalStateException("Bluetooth write failed")
            }
            status("Dosya parçaları şifreli Bluetooth bağlantısından gönderildi: " + safeName)
            true
        } catch (_: Exception) {
            status("Dosya gönderimi başarısız. Boyutu 1–64 MiB aralığında olmalı ve bağlantı açık kalmalı.")
            false
        }
    }

    private fun acceptMediaChunk(payload: JSONObject) {
        val id = payload.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid transfer id" }
        val name = payload.getString("name").take(100)
        val index = payload.getInt("i")
        val total = payload.getInt("n")
        val size = payload.getInt("size")
        val digest = Base64.decode(payload.getString("sha"), Base64.DEFAULT)
        val bytes = Base64.decode(payload.getString("data"), Base64.DEFAULT)
        // Bound memory use: only one incomplete transfer is accepted per connection.
        require(mediaReassemblers.containsKey(id) || mediaReassemblers.isEmpty()) {
            "Another media transfer is already in progress"
        }
        val reassembler = mediaReassemblers.computeIfAbsent(id) {
            mediaNames[id] = name
            MediaTransferProtocol.Reassembler(id, total, size, digest)
        }
        val completed = reassembler.accept(
            MediaTransferProtocol.Chunk(id, index, total, size, digest, bytes)
        ) ?: return
        val safeName = (mediaNames[id] ?: name).substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "media.bin" }
        val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "received-media")
        check(directory.exists() || directory.mkdirs()) { "Cannot create media directory" }
        val saved = File(directory, id + "_" + safeName)
        FileOutputStream(saved).use { it.write(completed) }
        mediaReassemblers.remove(id)
        mediaNames.remove(id)
        mainHandler.post {
            listeners.forEach { it.onMediaReceived(safeName, saved.absolutePath, completed.size) }
            status("Dosya alındı ve doğrulandı: " + safeName)
        }
    }

    private fun sendEncryptedControl(type: String, plaintext: ByteArray, channel: SecureChannel): Boolean {
        return try {
            val sealed = channel.encrypt(plaintext, CONTROL_AAD)
            sendWire(JSONObject()
                .put("t", type)
                .put("n", Base64.encodeToString(sealed.nonce, Base64.NO_WRAP))
                .put("c", Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP)))
        } catch (_: Exception) { false }
    }

    @Synchronized
    private fun maybeActivateSecureChannel() {
        val candidate = candidateChannel ?: return
        if (!remoteProofReceived || !localProofAcked) return
        secureChannel = candidate
        val fingerprint = remoteFingerprint
        flushPendingMessages(candidate)
        mainHandler.post {
            listeners.forEach { it.onSecurityChanged(true, fingerprint) }
            status("Anahtar sahipliği doğrulandı; ECDH + AES-GCM uçtan uca şifreli kanal etkin.")
        }
    }

    private fun rememberIncomingMessageId(id: String): Boolean = replayWindow.remember(id)

    private fun enqueuePending(id: String, frame: JSONObject): Boolean =
        synchronized(pendingLock) { pendingMessages.enqueue(id, frame.toString()) }

    private fun persistRetryAttempts() {
        synchronized(pendingLock) {
            val rows = org.json.JSONArray()
            retryAttempts.forEach { (id, attempts) ->
                rows.put(JSONObject().put("id", id).put("attempts", attempts))
            }
            deliveryPrefs.edit().putString("retry_attempts", rows.toString()).commit()
        }
    }

    private fun acknowledgePending(id: String) {
        val persisted = synchronized(pendingLock) { pendingMessages.acknowledge(id) }
        if (persisted) {
            retryAttempts.remove(id)
            persistRetryAttempts()
            status("Teslim onayı alındı: $id")
        } else {
            status("Teslim onayı kalıcı kaydedilemedi; mesaj kuyruğunda tutuluyor: $id")
        }
    }

    private fun scheduleDeliveryRetry(id: String, attempt: Int) {
        if (!retryScheduled.add(id)) return
        val currentAttempt = retryAttempts[id] ?: attempt.coerceIn(0, MAX_DELIVERY_RETRIES)
        retryAttempts.putIfAbsent(id, currentAttempt)
        persistRetryAttempts()
        if (currentAttempt >= MAX_DELIVERY_RETRIES) {
            retryScheduled.remove(id)
            status("Teslim onayı gelmedi; mesaj kalıcı kuyrukta tutuluyor: " + id)
            return
        }
        val shift = currentAttempt.coerceIn(0, 7)
        val delay = (DELIVERY_RETRY_BASE_MS * (1L shl shift)).coerceAtMost(DELIVERY_RETRY_MAX_MS)
        mainHandler.postDelayed({
            retryScheduled.remove(id)
            val raw = synchronized(pendingLock) { pendingMessages.get(id) }
            if (raw == null) {
                retryAttempts.remove(id)
                persistRetryAttempts()
                return@postDelayed
            }
            val channel = secureChannel
            if (channel != null) {
                try { sendWire(JSONObject(raw)) } catch (_: Exception) { }
            }
            retryAttempts[id] = currentAttempt + 1
            persistRetryAttempts()
            scheduleDeliveryRetry(id, currentAttempt + 1)
        }, delay)
    }

    private fun sendDeliveryAck(id: String, channel: SecureChannel) {
        val payload = JSONObject().put("type", "DELIVERY_ACK").put("id", id)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val sealed = channel.encrypt(payload, CONTROL_AAD)
        sendWire(JSONObject().put("t", "ACK")
            .put("n", Base64.encodeToString(sealed.nonce, Base64.NO_WRAP))
            .put("c", Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP)))
    }

    private fun flushPendingMessages(channel: SecureChannel) {
        val pending = synchronized(pendingLock) { pendingMessages.frames() }
        pending.forEach { raw ->
            try {
                val frame = JSONObject(raw)
                val id = frame.getString("id")
                if ((retryAttempts[id] ?: 0) >= MAX_DELIVERY_RETRIES) {
                    retryAttempts[id] = 0
                    persistRetryAttempts()
                }
                if (!sendWire(frame)) status("Kuyruktaki mesaj gönderilemedi; yeniden denenecek: " + id)
                scheduleDeliveryRetry(id, 0)
            } catch (_: Exception) { status("Çevrimdışı kuyruk kaydı bozuk; kayıt otomatik silinmedi.") }
        }
        if (pending.isNotEmpty()) status("Kuyruktaki " + pending.size + " mesaj için teslim onayı bekleniyor.")
    }

    private fun sendWire(frame: JSONObject): Boolean {
        val encoded = frame.toString().toByteArray(StandardCharsets.UTF_8)
        if (encoded.isEmpty() || encoded.size > MAX_WIRE_BYTES) return false
        val current = socket
        val ble = bleSender
        if (current == null && ble == null) return false
        return try {
            if (current != null) {
                synchronized(wireLock) {
                    current.outputStream.write(encoded)
                    current.outputStream.write('\n'.code)
                    current.outputStream.flush()
                }
                true
            } else {
                // GATT reassembly supplies the packet boundary; BLE frames do not use newline delimiters.
                ble?.invoke(encoded) == true
            }
        } catch (_: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String = try { device.name?.takeIf { it.isNotBlank() } ?: "Bilinmeyen cihaz" } catch (_: Exception) { "Bilinmeyen cihaz" }

    private fun publishDevices() {
        val peers = discoveredDevices()
        mainHandler.post { listeners.forEach { it.onDevicesChanged(peers, scanning) } }
    }
    private fun status(value: String) { mainHandler.post { listeners.forEach { it.onStatus(value) } } }

    fun closeConnection() {
        running.set(false)
        closeSocketOnly()
        bleSender = null
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        connectedName = null
        secureChannel = null
        candidateChannel = null
        localProofNonce = null
        remoteProofReceived = false
        localProofAcked = false
        remoteIdentity = null
        remoteFingerprint = null
        // Preserve replay history across reconnects to prevent duplicate delivery.
        mediaReassemblers.clear()
        mediaNames.clear()
        mainHandler.post { listeners.forEach { it.onConnectionChanged(null); it.onSecurityChanged(false, null) } }
    }

    private fun closeSocketOnly() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    fun dispose() {
        closeConnection()
        if (receiverRegistered) {
            try { appContext.unregisterReceiver(receiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
    }

    companion object {
        private fun dataAad(messageId: String) = "NexoraMesh/data/v1:$messageId".toByteArray(StandardCharsets.UTF_8)
        private const val MAX_REPLAY_IDS = 4096
        private const val MAX_PENDING_MESSAGES = 1000
        private const val MAX_DELIVERY_RETRIES = 8
        private const val DELIVERY_RETRY_BASE_MS = 2_000L
        private const val DELIVERY_RETRY_MAX_MS = 300_000L
        private val CONTROL_AAD = "NexoraMesh/control/v1".toByteArray(StandardCharsets.UTF_8)
        private val MEDIA_AAD = "NexoraMesh/media/v1".toByteArray(StandardCharsets.UTF_8)
        private const val MAX_WIRE_BYTES = 256 * 1024
        val SERVICE_UUID: UUID = UUID.fromString("0c4e6c7a-3b12-4b8d-a920-5f0c1a2d7e31")
    }
}
