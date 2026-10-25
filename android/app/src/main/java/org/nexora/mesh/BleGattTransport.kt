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
import android.os.Build
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
    }

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val peers = ConcurrentHashMap<String, Peer>()
    private val assemblies = ConcurrentHashMap<String, Assembly>()
    @Volatile private var listener: Listener? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var dataCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23
    private val pendingWrites = ArrayDeque<ByteArray>()
    private val pendingNotifications = ArrayDeque<ByteArray>()
    @Volatile private var serverPeer: BluetoothDevice? = null
    @Volatile private var serverPeerReady = false
    @Volatile private var notificationInFlight = false
    private val lock = Any()

    private data class Assembly(val count: Int, val createdAtMs: Long = android.os.SystemClock.elapsedRealtime(), val chunks: MutableMap<Int, ByteArray> = mutableMapOf(), var bytes: Int = 0)

    fun setListener(value: Listener?) { listener = value }

    fun start() {
        val a = adapter ?: return status("Bluetooth LE desteklenmiyor.")
        if (!a.isEnabled) return status("Önce Bluetooth'u aç.")
        startGattServer()
        startScan()
    }

    fun stop() {
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        assemblies.clear()
        synchronized(lock) { pendingWrites.clear(); pendingNotifications.clear() }
        serverPeer = null
        serverPeerReady = false
        notificationInFlight = false
        status("BLE taşıması durduruldu.")
    }

    fun connect(peer: Peer) {
        val a = adapter ?: return status("Bluetooth yok.")
        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = peer.device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            status("BLE GATT bağlantısı kuruluyor: ${peer.label}")
        } catch (_: SecurityException) { status("Bluetooth bağlantı izni gerekli.") }
    }

    fun sendEnvelope(envelope: ByteArray): Boolean {
        if (envelope.isEmpty() || envelope.size > MAX_ENVELOPE) {
            status("Zarf boş veya 256 KiB sınırını aşıyor.")
            return false
        }
        val characteristic = dataCharacteristic ?: return false
        val maxPayload = (mtu - 3 - HEADER_BYTES).coerceAtLeast(1)
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
        val current = gatt
        if (current != null) {
            synchronized(lock) { pendingWrites.clear(); pendingWrites.addAll(fragments) }
            return writeNext(current, characteristic)
        }
        synchronized(lock) {
            if (!serverPeerReady || serverPeer == null) return false
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
        dataCharacteristic = characteristic
        gattServer = manager.openGattServer(app, serverCallback)?.also { it.addService(service) }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return status("BLE yayıncısı kullanılamıyor.")
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return status("BLE tarayıcısı kullanılamıyor.")
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        status("Nexora Mesh BLE cihazları aranıyor.")
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
        override fun onMtuChanged(device: BluetoothDevice, mtuSize: Int) {
            if (mtuSize >= 23) mtu = mtuSize
        }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverPeer = device
                serverPeerReady = false
                status("BLE istemcisi bağlandı; bildirim izni bekleniyor.")
            } else {
                if (serverPeer?.address == device.address) {
                    serverPeer = null
                    serverPeerReady = false
                    synchronized(lock) { pendingNotifications.clear() }
                }
                status("BLE istemcisi ayrıldı.")
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            val valid = descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (valid) {
                descriptor.value = value
                serverPeer = device
                serverPeerReady = true
                listener?.onConnected(try { device.name ?: device.address } catch (_: Exception) { device.address })
                status("BLE çift yönlü kanal hazır; QR güven doğrulaması gerekli.")
            }
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
        }
        override fun onNotificationSent(device: BluetoothDevice, statusCode: Int) {
            notificationInFlight = false
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                synchronized(lock) { pendingNotifications.clear() }
                status("BLE bildirimi gönderilemedi; aktarım kuyruğu temizlendi.")
                return
            }
            notifyNext()
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray) {
            if (characteristic.uuid != DATA_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val accepted = acceptFragment(value)
            if (responseNeeded) gattServer?.sendResponse(device, requestId,
                if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, 0, null)
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                g.requestMtu(247)
                g.discoverServices()
                status("BLE bağlı; servisler keşfediliyor.")
            } else {
                g.close()
                if (gatt === g) gatt = null
                status("BLE bağlantısı kapandı.")
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, value: Int, statusCode: Int) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) mtu = value
        }
        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            val service = g.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DATA_UUID)
            if (characteristic == null) return status("Nexora BLE GATT servisi bulunamadı.")
            dataCharacteristic = characteristic
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
            listener?.onConnected(try { g.device.name ?: g.device.address } catch (_: Exception) { g.device.address })
            status("BLE çift yönlü kanal hazır; QR güven doğrulaması gerekli.")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == DATA_UUID) acceptFragment(value)
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == DATA_UUID) acceptFragment(value)
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, statusCode: Int) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) return status("BLE parçası gönderilemedi.")
            writeNext(g, characteristic)
        }
    }

    private fun acceptFragment(frame: ByteArray): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        assemblies.entries.removeIf { now - it.value.createdAtMs > ASSEMBLY_TIMEOUT_MS }
        if (frame.size < HEADER_BYTES) return false
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        val idBytes = ByteArray(16); buffer.get(idBytes)
        val id = idBytes.joinToString("") { "%02x".format(it) }
        val index = buffer.short.toInt() and 0xffff
        val total = buffer.short.toInt() and 0xffff
        val payloadSize = buffer.short.toInt() and 0xffff
        if (total == 0 || index >= total || payloadSize != frame.size - HEADER_BYTES) return false
        var assembly = assemblies[id]
        if (assembly == null) {
            if (assemblies.size >= MAX_INCOMPLETE) return false
            assembly = Assembly(total)
            assemblies[id] = assembly
        }
        if (assembly.count != total) { assemblies.remove(id); return false }
        val chunk = frame.copyOfRange(HEADER_BYTES, frame.size)
        val previous = assembly.chunks[index]
        if (previous != null) return previous.contentEquals(chunk)
        if (assembly.bytes + chunk.size > MAX_ENVELOPE) { assemblies.remove(id); return false }
        assembly.chunks[index] = chunk
        assembly.bytes += chunk.size
        if (assembly.chunks.size != assembly.count) return true
        val output = ByteArrayOutputStream(assembly.bytes)
        for (part in 0 until assembly.count) {
            val bytes = assembly.chunks[part] ?: return false
            output.write(bytes)
        }
        assemblies.remove(id)
        val packet = output.toByteArray()
        if (packet.isEmpty() || packet.size > MAX_ENVELOPE) return false
        listener?.onPacket(packet)
        return true
    }

    private fun notifyNext() {
        val peer = serverPeer ?: return
        val characteristic = dataCharacteristic ?: return
        val next = synchronized(lock) {
            if (!serverPeerReady || notificationInFlight || pendingNotifications.isEmpty()) null
            else pendingNotifications.removeFirst().also { notificationInFlight = true }
        } ?: return
        try {
            @Suppress("DEPRECATION")
            characteristic.value = next
            @Suppress("DEPRECATION")
            val accepted = gattServer?.notifyCharacteristicChanged(peer, characteristic, false) == true
            if (!accepted) {
                notificationInFlight = false
                synchronized(lock) { pendingNotifications.addFirst(next) }
                status("BLE bildirimi kuyruğa geri alındı; bağlantı hazır değil.")
            }
        } catch (_: Exception) {
            notificationInFlight = false
            status("BLE bildirimi gönderilirken hata oluştu.")
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
