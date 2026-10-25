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
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
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
    }

    private val appContext = context.applicationContext
    private val identity = IdentityStore(appContext).loadOrCreate()
    private val trustedContacts = TrustedContactStore(appContext)
    private val wireLock = Any()
    @Volatile private var secureChannel: SecureChannel? = null
    @Volatile private var remoteIdentity: ContactQr.Contact? = null
    @Volatile private var remoteFingerprint: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val found = linkedMapOf<String, BluetoothDevice>()
    @Volatile private var scanning = false
    @Volatile private var socket: BluetoothSocket? = null
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

    fun send(text: String): Boolean {
        if (text.isBlank()) return false
        if (socket == null) return false
        val channel = secureChannel ?: run {
            status("Mesaj engellendi: karşı tarafın QR parmak izi doğrulanmadan şifreli kanal açılmaz.")
            return false
        }
        return try {
            val plaintext = text.trim().toByteArray(StandardCharsets.UTF_8)
            val sealed = channel.encrypt(plaintext, DATA_AAD)
            val frame = JSONObject()
                .put("t", "DATA")
                .put("n", Base64.encodeToString(sealed.nonce, Base64.NO_WRAP))
                .put("c", Base64.encodeToString(sealed.ciphertext, Base64.NO_WRAP))
            if (!sendWire(frame)) false else {
                mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(text.trim(), true)) } }
                true
            }
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
        if (activateIfTrusted(remote)) status("Parmak izi eşleşti. Uçtan uca şifreli kanal etkin.")
        else status("Güvenli kanal kapalı: karşı tarafın QR kodunu tara ve parmak izini doğrula.")
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
                "DATA" -> {
                    val channel = secureChannel ?: throw SecurityException("Unverified peer sent data")
                    val nonce = Base64.decode(frame.getString("n"), Base64.DEFAULT)
                    val ciphertext = Base64.decode(frame.getString("c"), Base64.DEFAULT)
                    val plaintext = channel.decrypt(SecureChannel.Sealed(nonce, ciphertext), DATA_AAD)
                    val text = plaintext.toString(StandardCharsets.UTF_8)
                    mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(text, false)) } }
                }
                else -> throw IllegalArgumentException("Unknown wire frame")
            }
        } catch (error: Exception) {
            status("Gelen paket reddedildi: biçim, güven veya şifre doğrulaması başarısız.")
        }
    }

    private fun activateIfTrusted(contact: ContactQr.Contact): Boolean {
        val trusted = trustedContacts.all().firstOrNull {
            it.fingerprint.equals(contact.fingerprint, ignoreCase = true)
        } ?: return false
        val storedBytes = Base64.decode(trusted.publicKeyBase64, Base64.DEFAULT)
        val remoteBytes = Base64.decode(contact.publicKeyBase64, Base64.DEFAULT)
        if (!MessageDigest.isEqual(storedBytes, remoteBytes)) return false
        return try {
            val channel = SecureChannel.derive(identity, SecureChannel.decodePublicKey(contact.publicKeyBase64))
            secureChannel = channel
            remoteFingerprint = contact.fingerprint
            mainHandler.post {
                listeners.forEach { it.onSecurityChanged(true, contact.fingerprint) }
                status("QR parmak izi doğrulandı; ECDH + AES-GCM uçtan uca şifreli kanal etkin.")
            }
            true
        } catch (_: Exception) {
            secureChannel = null
            false
        }
    }

    private fun sendWire(frame: JSONObject): Boolean {
        val current = socket ?: return false
        return try {
            synchronized(wireLock) {
                val bytes = (frame.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                current.outputStream.write(bytes)
                current.outputStream.flush()
            }
            true
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
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        connectedName = null
        secureChannel = null
        remoteIdentity = null
        remoteFingerprint = null
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
        private val DATA_AAD = "NexoraMesh/data/v1".toByteArray(StandardCharsets.UTF_8)
        val SERVICE_UUID: UUID = UUID.fromString("0c4e6c7a-3b12-4b8d-a920-5f0c1a2d7e31")
    }
}
