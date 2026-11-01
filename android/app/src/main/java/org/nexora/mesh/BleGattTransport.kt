package org.nexora.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.os.ParcelUuid
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared BLE GATT transport. Frames complete envelopes only after bounded reassembly.
 * It is intentionally transport-only: caller must verify QR identity and encrypt payloads.
 */
@SuppressLint("MissingPermission")
class BleGattTransport(context: Context) {
    data class Peer(val device: BluetoothDevice, val label: String)

    interface Listener {
        fun onPeers(peers: List<Peer>)
        fun onStatus(status: String)
        fun onPacket(packet: ByteArray)
        fun onConnected(peerLabel: String) {}
        fun onDisconnected() {}
        fun onConnected(peerId: String, peerLabel: String) { onConnected(peerLabel) }
        fun onDisconnected(peerId: String) { onDisconnected() }
        fun onPacketFromPeer(peerId: String, packet: ByteArray) { onPacket(packet) }
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val peers = ConcurrentHashMap<String, Peer>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    // Every central-side BLE connection owns independent GATT state and write ordering.
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val clientMtus = ConcurrentHashMap<String, Int>()
    private val clientWriteQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val clientWriteLocks = ConcurrentHashMap<String, Any>()
    private val peerAssemblies = ConcurrentHashMap<String, ConcurrentHashMap<String, Assembly>>()
    @Volatile private var activeClientPeerId: String? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var serverCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var clientCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var clientMtu = 23
    @Volatile private var serverMtu = 23
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val pendingNotifications = ArrayDeque<ByteArray>()
    // Peripheral-side links are independent: each central owns its MTU and queue.
    private val serverPeers = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverPeerMtus = ConcurrentHashMap<String, Int>()
    private val readyServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val serverNotificationQueues = ConcurrentHashMap<String, ArrayDeque<ByteArray>>()
    private val serverNotificationLocks = ConcurrentHashMap<String, Any>()
    private val serverNotificationsInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var serverNotificationInFlightPeer: String? = null
    @Volatile private var serverPeer: BluetoothDevice? = null
    @Volatile private var serverPeerReady = false
    @Volatile private var notificationInFlight = false
    private val lock = Any()
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var scanWindowRunnable: Runnable? = null
    @Volatile private var scanRestartRunnable: Runnable? = null

    private data class Assembly(val count: Int, val createdAtMs: Long = android.os.SystemClock.elapsedRealtime(), val chunks: MutableMap<Int, ByteArray> = mutableMapOf(), var bytes: Int = 0)

    fun setListener(value: Listener?) { listener = value }

    fun start() {
        val a = adapter ?: return status("Bluetooth LE desteklenmiyor.")
        if (!a.isEnabled) return status("Önce Bluetooth'u aç.")
        startGattServer()
        startScan()
    }

    /** Explicitly stop discovery when the user leaves the nearby-devices workflow. */
    fun stopDiscovery() {
        scanWindowRunnable?.let(scanHandler::removeCallbacks)
        scanRestartRunnable?.let(scanHandler::removeCallbacks)
        scanWindowRunnable = null
        scanRestartRunnable = null
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        status("BLE taraması durduruldu; arka planda sürekli tarama yapılmıyor.")
    }

    fun stop() {
        stopDiscovery()
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        clientGatts.values.forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }
        clientGatts.clear(); clientCharacteristics.clear(); clientMtus.clear()
        clientWriteQueues.clear(); clientWriteLocks.clear(); peerAssemblies.clear()
        activeClientPeerId = null
        gatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        assemblies.clear()
        synchronized(lock) { pendingWrites.clear(); pendingNotifications.clear() }
        serverPeer = null
        serverPeerReady = false
        notificationInFlight = false
        serverPeers.clear(); serverPeerMtus.clear(); readyServerPeers.clear()
        serverNotificationQueues.clear(); serverNotificationLocks.clear()
        serverNotificationsInFlight.clear()
        serverNotificationInFlightPeer = null
        status("BLE taşıması durduruldu.")
    }

    fun connect(peer: Peer) {
        val a = adapter ?: return status("Bluetooth yok.")
        val peerId = peer.device.address
        activeClientPeerId = peerId
        if (clientGatts.containsKey(peerId)) return status("Bu BLE eşi zaten bağlı: ${peer.label}")
        try {
            val connection = peer.device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            clientGatts[peerId] = connection
            clientWriteQueues[peerId] = ArrayDeque()
            clientWriteLocks[peerId] = Any()
            peerAssemblies[peerId] = ConcurrentHashMap()
            activeClientPeerId = peerId
            status("Ayrı BLE GATT oturumu kuruluyor: ${peer.label}")
        } catch (_: SecurityException) { status("Bluetooth bağlantı izni gerekli.") }
    }

    fun connectedPeerIds(): Set<String> = clientGatts.keys.toSet() + serverPeers.keys

    fun disconnect(peerId: String) {
        val connection = clientGatts[peerId]
        if (connection != null) {
            try { connection.disconnect() } catch (_: Exception) { connection.close() }
            return
        }
        val incoming = serverPeers.remove(peerId)
        if (incoming != null) {
            readyServerPeers.remove(peerId); serverPeerMtus.remove(peerId)
            serverNotificationQueues.remove(peerId); serverNotificationLocks.remove(peerId)
            serverNotificationsInFlight.remove(peerId)
            if (serverPeer?.address == peerId) {
                serverPeer = serverPeers.values.firstOrNull()
                serverPeerReady = serverPeer?.address?.let { readyServerPeers.contains(it) } == true
            }
            try { gattServer?.cancelConnection(incoming) } catch (_: Exception) {}
        }
    }

    private fun sendEnvelopeToServerPeer(peerId: String, envelope: ByteArray): Boolean {
        val peer = serverPeers[peerId] ?: return false
        if (peerId !in readyServerPeers || serverCharacteristic == null) return false
        if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE) return false
        val mtu = serverPeerMtus[peerId] ?: 23
        val maxPayload = mtu - 3 - HEADER_BYTES
        if (maxPayload <= 0) return false
        val transfer = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val total = (envelope.size + maxPayload - 1) / maxPayload
        if (total > 65535) return false
        val fragments = (0 until total).map { index ->
            val start = index * maxPayload
            val end = minOf(envelope.size, start + maxPayload)
            ByteBuffer.allocate(HEADER_BYTES + end - start).order(ByteOrder.BIG_ENDIAN)
                .put(transfer).putShort(index.toShort()).putShort(total.toShort())
                .putShort((end - start).toShort()).put(envelope, start, end - start).array()
        }
        val queue = serverNotificationQueues.computeIfAbsent(peerId) { ArrayDeque() }
        val peerLock = serverNotificationLocks.computeIfAbsent(peerId) { Any() }
        synchronized(peerLock) {
            if (queue.size + fragments.size > 8192) return false
            queue.addAll(fragments)
        }
        notifyNext(peerId)
        return true
    }

    /** Send through one explicit peer session; queues and MTU are never shared across peers. */
    fun sendEnvelope(peerId: String, envelope: ByteArray): Boolean {
        val current = clientGatts[peerId] ?: return sendEnvelopeToServerPeer(peerId, envelope)
        if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE) return false
        val characteristic = clientCharacteristics[peerId] ?: return false
        val mtu = clientMtus[peerId] ?: 23
        val maxPayload = mtu - 3 - HEADER_BYTES
        if (maxPayload <= 0) return false
        val transfer = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val total = (envelope.size + maxPayload - 1) / maxPayload
        if (total > 65535) return false
        val fragments = (0 until total).map { index ->
            val start = index * maxPayload
            val end = minOf(envelope.size, start + maxPayload)
            ByteBuffer.allocate(HEADER_BYTES + end - start).order(ByteOrder.BIG_ENDIAN)
                .put(transfer).putShort(index.toShort()).putShort(total.toShort())
                .putShort((end - start).toShort()).put(envelope, start, end - start).array()
        }
        val queue = clientWriteQueues[peerId] ?: return false
        val peerLock = clientWriteLocks[peerId] ?: return false
        synchronized(peerLock) {
            if (queue.size + fragments.size > 8192) return false
            queue.addAll(fragments)
        }
        return writeNextForPeer(peerId, current, characteristic)
    }

    private fun writeNextForPeer(peerId: String, current: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val queue = clientWriteQueues[peerId] ?: return false
        val peerLock = clientWriteLocks[peerId] ?: return false
        val next = synchronized(peerLock) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return true
        return try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(characteristic, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = next
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
            if (!started) synchronized(peerLock) { queue.addFirst(next) }
            started
        } catch (_: Exception) {
            synchronized(peerLock) { queue.addFirst(next) }
            false
        }
    }

    fun sendEnvelope(envelope: ByteArray): Boolean {
        val peerId = activeClientPeerId ?: serverPeer?.address
        if (peerId != null) return sendEnvelope(peerId, envelope)
        if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE) {
            status("Zarf boş veya 256 KiB sınırını aşıyor.")
            return false
        }
        val current = gatt
        val characteristic = if (current != null) clientCharacteristic else serverCharacteristic
        if (characteristic == null) return false
        if (current == null && (!serverPeerReady || serverPeer == null)) return false
        val linkMtu = if (current != null) clientMtu else serverMtu
        val maxPayload = linkMtu - 3 - HEADER_BYTES
        if (maxPayload <= 0) {
            status("BLE MTU yetersiz: parça başlığı ve içerik sığmıyor.")
            return false
        }
        val transfer = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val total = (envelope.size + maxPayload - 1) / maxPayload
        if (total > 65535) return false
        val fragments = (0 until total).map { index ->
            val start = index * maxPayload
            val end = minOf(envelope.size, start + maxPayload)
            ByteBuffer.allocate(HEADER_BYTES + end - start).order(ByteOrder.BIG_ENDIAN)
                .put(transfer).putShort(index.toShort()).putShort(total.toShort())
                .putShort((end - start).toShort()).put(envelope, start, end - start).array()
        }
        if (current != null) {
            synchronized(lock) { pendingWrites.clear(); pendingWrites.addAll(fragments) }
            return writeNext(current, characteristic)
        }
        synchronized(lock) {
            if (pendingNotifications.size + fragments.size > 8192) {
                status("BLE bildirim kuyruğu dolu; yeni paket kabul edilmedi.")
                return false
            }
            pendingNotifications.addAll(fragments)
        }
        notifyNext()
        return true
    }

    private fun writeNext(current: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        val next = synchronized(lock) { if (pendingWrites.isEmpty()) null else pendingWrites.removeFirst() } ?: return true
        return try {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                current.writeCharacteristic(characteristic, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = next
                @Suppress("DEPRECATION")
                current.writeCharacteristic(characteristic)
            }
            if (!started) {
                synchronized(lock) { pendingWrites.addFirst(next) }
                status("BLE parçası yazılamadı; yeniden denenecek.")
            }
            started
        } catch (_: Exception) { false }
    }

    private fun startGattServer() {
        if (gattServer != null) return
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        characteristic.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(characteristic)
        serverCharacteristic = characteristic
        gattServer = manager.openGattServer(app, serverCallback)?.also { it.addService(service) }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return status("BLE yayıncısı kullanılamıyor.")
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return status("BLE tarayıcısı kullanılamıyor.")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val battery = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPercent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lowPower = power.isPowerSaveMode
        val batteryIntent = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
            batteryStatus == BatteryManager.BATTERY_STATUS_FULL
        val interval = runCatching {
            NativeMesh.recommendedScanIntervalSeconds(
                batteryPercent, charging = charging, lowPowerMode = lowPower,
                appForeground = true, recentlyConnected = false, urgentPending = false
            )
        }.getOrDefault(45L)
        val scanMode = if (lowPower || batteryPercent <= 15) {
            ScanSettings.SCAN_MODE_LOW_POWER
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }
        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()
        scanWindowRunnable?.let(scanHandler::removeCallbacks)
        scanRestartRunnable?.let(scanHandler::removeCallbacks)
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            status("BLE kısa tarama penceresi açık; sonraki pencere yaklaşık ${interval}s sonra.")
            val stopWindow = Runnable {
                try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
                val restart = Runnable { startScan() }
                scanRestartRunnable = restart
                scanHandler.postDelayed(restart, interval.coerceAtLeast(5L) * 1000L)
            }
            scanWindowRunnable = stopWindow
            scanHandler.postDelayed(stopWindow, 3_000L)
        } catch (_: SecurityException) {
            status("BLE tarama izni yok; tarama durduruldu.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val peer = Peer(device, result.scanRecord?.deviceName ?: "Nexora Mesh cihazı")
            peers[device.address] = peer
            listener?.onPeers(peers.values.toList())
        }
        override fun onScanFailed(errorCode: Int) { status("BLE taraması başarısız: $errorCode") }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { status("BLE GATT servisi yayınlanıyor.") }
        override fun onStartFailure(errorCode: Int) { status("BLE yayını başlatılamadı: $errorCode") }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(statusCode: Int, service: BluetoothGattService) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else status("BLE GATT servisi eklenemedi: $statusCode")
        }
        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val peerId = device.address
                serverPeers[peerId] = device
                serverNotificationQueues.putIfAbsent(peerId, ArrayDeque())
                serverNotificationLocks.putIfAbsent(peerId, Any())
                serverPeer = device
                serverPeerReady = false
                status("BLE istemcisi bağlandı; bildirim izni bekleniyor.")
            } else {
                val peerId = device.address
                serverPeers.remove(peerId); readyServerPeers.remove(peerId)
                serverPeerMtus.remove(peerId); serverNotificationQueues.remove(peerId)
                serverNotificationLocks.remove(peerId); serverNotificationsInFlight.remove(peerId)
                if (serverPeer?.address == peerId) {
                    serverPeer = serverPeers.values.firstOrNull()
                    serverPeerReady = serverPeer?.address?.let { readyServerPeers.contains(it) } == true
                }
                listener?.onDisconnected(device.address)
                status("BLE istemcisi ayrıldı: ${device.address}")
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, mtuSize: Int) {
            if (mtuSize >= 23) {
                serverPeerMtus[device.address] = mtuSize
                if (serverPeer?.address == device.address) serverMtu = mtuSize
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            val valid = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (valid) {
                descriptor.value = value
                serverPeers[device.address] = device
                readyServerPeers.add(device.address)
                serverPeer = device
                serverPeerReady = true
                listener?.onConnected(device.address, try { device.name ?: device.address } catch (_: Exception) { device.address })
                status("BLE çift yönlü kanal hazır; QR güven doğrulaması gerekli.")
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }
        override fun onNotificationSent(device: BluetoothDevice, statusCode: Int) {
            val peerId = device.address
            serverNotificationsInFlight.remove(peerId)
            synchronized(lock) {
                if (serverNotificationInFlightPeer == peerId) serverNotificationInFlightPeer = null
            }
            if (serverPeer?.address == peerId) notificationInFlight = false
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                serverNotificationQueues.remove(peerId)
                status("BLE bildirimi g?nderilemedi; yaln?zca $peerId e?inin kuyru?u durduruldu.")
                return
            }
            notifyNext(peerId)
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            if (characteristic.uuid != DATA_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val accepted = acceptFragment(value, device.address)
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            val peerId = g.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clientGatts[peerId] = g
                gatt = g
                activeClientPeerId = peerId
                g.requestMtu(247)
                g.discoverServices()
                status("BLE eş oturumu bağlı; servisler keşfediliyor: $peerId")
            } else {
                g.close()
                clientGatts.remove(peerId, g)
                clientCharacteristics.remove(peerId)
                clientMtus.remove(peerId)
                clientWriteQueues.remove(peerId)
                clientWriteLocks.remove(peerId)
                peerAssemblies.remove(peerId)
                if (gatt === g) gatt = clientGatts.values.firstOrNull()
                if (activeClientPeerId == peerId) activeClientPeerId = clientGatts.keys.firstOrNull()
                listener?.onDisconnected(peerId)
                status("BLE eş oturumu kapandı: $peerId")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                clientMtus[g.device.address] = value
                if (gatt === g) clientMtu = value
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service = g.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DATA_UUID)
            if (characteristic == null) return status("Nexora BLE GATT servisi bulunamadı.")
            clientCharacteristics[g.device.address] = characteristic
            clientCharacteristic = characteristic
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                g.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CCCD_UUID)
                if (descriptor == null) return status("BLE bildirim tanımlayıcısı eksik.")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (!g.writeDescriptor(descriptor)) return status("BLE bildirim aboneliği başlatılamadı.")
            } else {
                status("Karşı cihaz çift yönlü BLE bildirimi desteklemiyor.")
            }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS || descriptor.uuid != CCCD_UUID) {
                return status("BLE bildirim aboneliği başarısız.")
            }
            val peerId = g.device.address
            listener?.onConnected(peerId, try { g.device.name ?: peerId } catch (_: Exception) { peerId })
            status("BLE çift yönlü kanal hazır; QR güven doğrulaması gerekli.")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == DATA_UUID) acceptFragment(value, g.device.address)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == DATA_UUID) acceptFragment(value, g.device.address)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) return status("BLE parçası gönderilemedi.")
            writeNextForPeer(g.device.address, g, characteristic)
        }
    }

    private fun acceptFragment(frame: ByteArray, peerId: String = "server"): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val peerMap = peerAssemblies.computeIfAbsent(peerId) { ConcurrentHashMap() }
        peerMap.entries.removeIf { now - it.value.createdAtMs > ASSEMBLY_TIMEOUT_MS }
        if (frame.size < HEADER_BYTES) return false
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val idBytes = ByteArray(16)
        buffer.get(idBytes)
        val id = idBytes.joinToString("") { "%02x".format(it) }
        val index = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        val payloadSize = buffer.short.toInt() and 0xffff
        if (total == 0 || index >= total || payloadSize != frame.size - HEADER_BYTES) return false
        var assembly = peerMap[id]
        if (assembly == null) {
            if (peerMap.size >= MAX_INCOMPLETE) return false
            assembly = Assembly(total)
            peerMap[id] = assembly
        }
        if (assembly.count != total) { peerMap.remove(id); return false }
        val chunk = frame.copyOfRange(HEADER_BYTES, frame.size)
        val previous = assembly.chunks[index]
        if (previous != null) return previous.contentEquals(chunk)
        if (assembly.bytes + chunk.size > MAX_ENVELOPE) { peerMap.remove(id); return false }
        assembly.chunks[index] = chunk
        assembly.bytes += chunk.size
        if (assembly.chunks.size != assembly.count) return true
        val output = ByteArrayOutputStream(assembly.bytes)
        for (part in 0 until assembly.count) {
            val bytes = assembly.chunks[part] ?: return false
            output.write(bytes)
        }
        peerMap.remove(id)
        val packet = output.toByteArray()
        if (packet.isEmpty() || packet.size > MAX_ENVELOPE) return false
        listener?.onPacketFromPeer(peerId, packet)
        return true
    }

    private fun notifyNext(peerId: String? = null) {
        val targetId: String
        val peer: BluetoothDevice
        val characteristic: BluetoothGattCharacteristic
        val queue: ArrayDeque<ByteArray>
        val peerLock: Any
        val next: ByteArray
        synchronized(lock) {
            if (serverNotificationInFlightPeer != null) return
            val preferred = peerId?.takeIf { id ->
                id in readyServerPeers && serverNotificationQueues[id]?.isNotEmpty() == true
            }
            val selected = preferred ?: readyServerPeers.firstOrNull { id ->
                serverNotificationQueues[id]?.isNotEmpty() == true
            } ?: return
            targetId = selected
            peer = serverPeers[targetId] ?: return
            characteristic = serverCharacteristic ?: return
            queue = serverNotificationQueues[targetId] ?: return
            peerLock = serverNotificationLocks[targetId] ?: return
            next = synchronized(peerLock) { if (queue.isEmpty()) null else queue.removeFirst() } ?: return
            serverNotificationInFlightPeer = targetId
            serverNotificationsInFlight.add(targetId)
            notificationInFlight = true
        }
        try {
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            val accepted = gattServer?.notifyCharacteristicChanged(peer, characteristic, false) == true
            if (!accepted) {
                synchronized(lock) {
                    serverNotificationInFlightPeer = null
                    notificationInFlight = false
                    serverNotificationsInFlight.remove(targetId)
                }
                synchronized(peerLock) { queue.addFirst(next) }
                status("BLE bildirimi $targetId kuyru?una geri al?nd?; ba?lant? haz?r de?il.")
            }
        } catch (_: Exception) {
            synchronized(lock) {
                serverNotificationInFlightPeer = null
                notificationInFlight = false
                serverNotificationsInFlight.remove(targetId)
            }
            synchronized(peerLock) { queue.addFirst(next) }
            status("BLE bildirimi g?nderilirken hata olu?tu: $targetId")
        }
    }

    private fun status(value: String) { listener?.onStatus(value) }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7d2a0001-5b6c-4f10-9a7d-4e45584f5241")
        val DATA_UUID: UUID = UUID.fromString("7d2a0002-5b6c-4f10-9a7d-4e45584f5241")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val HEADER_BYTES = 22
        private const val MAX_ENVELOPE = 256 * 1024
        private const val MAX_INCOMPLETE = 4
        private const val ASSEMBLY_TIMEOUT_MS = 30_000L
    }
}
