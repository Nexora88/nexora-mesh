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
    }

    private val appContext = context.applicationContext
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
        running.set(true)
        mainHandler.post { listeners.forEach { it.onConnectionChanged(connectedName) }; status("Bluetooth bağlantısı kuruldu; internet gerekmez.") }
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(newSocket.inputStream, StandardCharsets.UTF_8))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(line, false)) } }
                }
            } catch (_: Exception) { }
            closeConnection()
            status("Bluetooth bağlantısı kapandı.")
        }, "mesh-bt-reader").start()
    }

    fun send(text: String): Boolean {
        if (text.isBlank()) return false
        val current = socket ?: return false
        return try {
            val writer = PrintWriter(OutputStreamWriter(current.outputStream, StandardCharsets.UTF_8), true)
            writer.println(text.replace("\\n", " "))
            if (writer.checkError()) false else {
                mainHandler.post { listeners.forEach { it.onMessage(ChatMessage(text.trim(), true)) } }
                true
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
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
        connectedName = null
        mainHandler.post { listeners.forEach { it.onConnectionChanged(null) } }
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
        val SERVICE_UUID: UUID = UUID.fromString("0c4e6c7a-3b12-4b8d-a920-5f0c1a2d7e31")
    }
}
