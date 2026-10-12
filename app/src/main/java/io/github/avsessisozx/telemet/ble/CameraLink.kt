package io.github.avsessisozx.telemet.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.avsessisozx.telemet.protocol.CameraStatus
import io.github.avsessisozx.telemet.protocol.Cmd
import io.github.avsessisozx.telemet.protocol.CmdType
import io.github.avsessisozx.telemet.protocol.ConnectionRequest
import io.github.avsessisozx.telemet.protocol.ConnectionResponse
import io.github.avsessisozx.telemet.protocol.DjiCamera
import io.github.avsessisozx.telemet.protocol.DjiFrame
import io.github.avsessisozx.telemet.protocol.FrameAssembler
import io.github.avsessisozx.telemet.protocol.KeyReport
import io.github.avsessisozx.telemet.protocol.StatusSubscription
import io.github.avsessisozx.telemet.util.AppLog
import java.util.UUID
import kotlin.random.Random

/**
 * Telefonu DJI "Osmo Action GPS Bluetooth Remote Controller" gibi davrandıran Bluetooth bağlantısı.
 *
 * Akış (DJI R SDK protokolüne göre):
 * 1. Reklam paketlerinden DJI kamerayı bul (üretici verisi AA 08 .. .. FA).
 * 2. GATT ile bağlan, MTU'yu büyüt, FFF0 servisini bul.
 * 3. FFF4 bildirimlerini aç (kamera -> telefon), FFF5'e yaz (telefon -> kamera).
 * 4. 0x0019 bağlantı isteği gönder; kamera yanıt verir, ardından kendi isteğini (verify_mode=2) gönderir.
 * 5. Kamera izin verdiyse yanıtla, durum aboneliğini aç ve GPS göndermeye başla.
 *
 * Tüm iş ana iş parçacığında yürür; Bluetooth geri çağrıları oraya aktarılır.
 */
@SuppressLint("MissingPermission")
class CameraLink(
    private val context: Context,
    private val remoteMac: ByteArray,
    private val listener: Listener,
) {
    enum class State { IDLE, SCANNING, CONNECTING, HANDSHAKE, READY, RECONNECTING }

    interface Listener {
        fun onLinkState(state: State, detail: String)
        fun onCameraReady(address: String, name: String?, model: String?)
        fun onCameraStatus(status: CameraStatus)
        fun onVerifyCode(code: Int?)
        fun onMtu(mtu: Int)
    }

    companion object {
        /** DJI örnek kodundaki kumanda kimliği. */
        const val REMOTE_DEVICE_ID = 0x12345678

        val SERVICE_UUID: UUID = uuid16(0xFFF0)
        val NOTIFY_UUID: UUID = uuid16(0xFFF4)
        val WRITE_UUID: UUID = uuid16(0xFFF5)
        val CCCD_UUID: UUID = uuid16(0x2902)

        /** Reklam paketindeki üretici verisinin ilk iki baytı (AA 08), little-endian şirket kimliği. */
        private const val DJI_COMPANY_ID = 0x08AA

        private const val REQUESTED_MTU = 500
        private const val SCAN_PICK_DELAY_MS = 4_000L
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val HANDSHAKE_TIMEOUT_MS = 45_000L
        private const val WRITE_TIMEOUT_MS = 1_500L
        private const val NO_RESPONSE_WRITE_TIMEOUT_MS = 250L

        private fun uuid16(v: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(v))
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter?
        get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    var state = State.IDLE
        private set

    private var wantConnected = false
    private var preferredAddress: String? = null

    // Tarama
    private var scanning = false
    private val candidates = LinkedHashMap<String, ScanResult>()
    private var scanStartedAt = 0L
    private var scanAttempt = 0

    // GATT
    private var gatt: BluetoothGatt? = null
    private var currentDevice: BluetoothDevice? = null
    private var currentName: String? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private var servicesRequested = false
    private var reconnectAttempts = 0

    // Protokol
    private val assembler = FrameAssembler()
    private var seq = Random.nextInt(1, 0x7FFF)
    private var handshakeSeq = -1
    var cameraDeviceId = 0
        private set
    var cameraSleeping = false
        private set
    private var lastStatus: CameraStatus? = null

    // Yazma kuyruğu: Android aynı anda yalnızca bir GATT işlemine izin verir.
    private class PendingWrite(val data: ByteArray, val withResponse: Boolean, val isGps: Boolean) {
        var attempts = 0
    }

    private val queue = ArrayDeque<PendingWrite>()
    private var writeInFlight = false
    private var inFlightWithResponse = false
    private var writeStartedAt = 0L
    private var warnedMtu = false
    var writeFailures = 0L
        private set

    // ---------------------------------------------------------------- Genel arayüz

    fun connect(preferred: String?) {
        wantConnected = true
        preferredAddress = preferred
        reconnectAttempts = 0
        if (state == State.IDLE) startScan()
    }

    fun disconnect() {
        wantConnected = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        val g = gatt
        if (g != null) {
            AppLog.i("Bağlantı kapatılıyor")
            try {
                g.disconnect()
            } catch (e: Exception) {
                AppLog.w("disconnect hatası", e)
            }
            g.close()
        }
        gatt = null
        resetLinkState()
        setState(State.IDLE, "Bağlı değil")
    }

    /** GPS paketini kameraya gönderir. Kamera hazır değilse false döner. */
    fun sendGps(payload: ByteArray): Boolean {
        if (state != State.READY || cameraSleeping) return false
        val frame = DjiFrame.encode(CmdType.NO_RESPONSE, nextSeq(), Cmd.SET_GENERAL, Cmd.ID_GPS_PUSH, payload)
        enqueue(frame, withResponse = false, isGps = true)
        return true
    }

    /** Kameranın kayıt tuşuna kısa basmakla aynı: kaydı başlatır ya da durdurur. */
    fun pressRecord(): Boolean {
        if (state != State.READY) return false
        AppLog.i("Kayıt tuşu gönderiliyor")
        sendFrame(
            CmdType.RESPONSE_OR_NOT, nextSeq(), Cmd.SET_GENERAL, Cmd.ID_KEY_REPORT,
            KeyReport.payload(KeyReport.KEY_RECORD), withResponse = true,
        )
        return true
    }

    // ---------------------------------------------------------------- Tarama

    private fun startScan() {
        if (!wantConnected) return
        val a = adapter
        if (a == null) {
            setState(State.IDLE, "Bu telefonda Bluetooth yok")
            return
        }
        if (!a.isEnabled) {
            AppLog.w("Bluetooth kapalı, açılması bekleniyor")
            setState(State.RECONNECTING, "Bluetooth kapalı. Açıldığında otomatik bağlanılacak.")
            main.postDelayed({ startScan() }, 5_000)
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            scheduleRetryScan("Tarayıcı hazır değil")
            return
        }
        candidates.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // Android ekran kapalıyken filtresiz taramayı engeller. Bu yüzden önce DJI üretici kimliği
        // (ve biliniyorsa kameranın adresi) ile filtreli tarama yapılır; bulunamazsa sonraki denemede
        // filtresiz taramaya geçilir (bazı telefonlar boş veri filtresini desteklemiyor).
        scanAttempt++
        val useFilters = scanAttempt % 2 == 1
        val filters: List<ScanFilter>? = if (useFilters) {
            buildList {
                add(ScanFilter.Builder().setManufacturerData(DJI_COMPANY_ID, ByteArray(0)).build())
                preferredAddress?.let { addr ->
                    if (BluetoothAdapter.checkBluetoothAddress(addr)) {
                        add(ScanFilter.Builder().setDeviceAddress(addr).build())
                    }
                }
            }
        } else {
            null
        }
        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            AppLog.w("Bluetooth tarama izni yok", e)
            setState(State.IDLE, "Bluetooth izni verilmemiş")
            wantConnected = false
            return
        }
        scanning = true
        scanStartedAt = SystemClock.elapsedRealtime()
        setState(State.SCANNING, "Kamera aranıyor… Kamera açık olmalı ve DJI Mimo bağlı olmamalı.")
        AppLog.i(
            "Tarama başladı (" + (if (useFilters) "filtreli" else "filtresiz") + ")" +
                (preferredAddress?.let { ", öncelikli: $it" } ?: ""),
        )
        main.postDelayed(scanTick, SCAN_PICK_DELAY_MS)
    }

    private fun stopScan() {
        main.removeCallbacks(scanTick)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            AppLog.w("Tarama durdurulamadı", e)
        }
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!scanning) return
            val best = candidates.values.maxByOrNull { it.rssi }
            if (best != null) {
                stopScan()
                connectTo(best.device, best.scanRecord?.deviceName)
                return
            }
            if (SystemClock.elapsedRealtime() - scanStartedAt > SCAN_TIMEOUT_MS) {
                stopScan()
                scheduleRetryScan("Kamera bulunamadı")
                return
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun scheduleRetryScan(reason: String) {
        if (!wantConnected) return
        AppLog.i("$reason, 3 sn sonra tekrar aranacak")
        setState(State.SCANNING, "$reason. Tekrar aranıyor…")
        main.postDelayed({ startScan() }, 3_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { handleScanResult(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                AppLog.w("Tarama başarısız (kod $errorCode)")
                scheduleRetryScan("Tarama hatası $errorCode")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (!scanning) return
        if (!DjiCamera.isCameraAdvertisement(result.scanRecord?.bytes)) return
        val address = result.device.address
        val name = result.scanRecord?.deviceName ?: safeName(result.device)
        if (!candidates.containsKey(address)) {
            AppLog.i("Kamera bulundu: ${name ?: "isimsiz"} [$address] sinyal ${result.rssi} dBm")
        }
        candidates[address] = result
        if (address == preferredAddress) {
            stopScan()
            connectTo(result.device, name)
        }
    }

    private fun safeName(device: BluetoothDevice): String? = try {
        device.name
    } catch (e: SecurityException) {
        null
    }

    // ---------------------------------------------------------------- GATT bağlantısı

    private fun connectTo(device: BluetoothDevice, name: String?, autoConnect: Boolean = false) {
        closeGatt()
        currentDevice = device
        currentName = name ?: currentName
        servicesRequested = false
        mtu = 23
        if (autoConnect) {
            setState(State.RECONNECTING, "Kamera bekleniyor. Kamera açılınca ya da yaklaşınca otomatik bağlanılacak.")
        } else {
            setState(State.CONNECTING, "Bağlanılıyor: ${currentName ?: device.address}")
        }
        AppLog.i("GATT bağlantısı açılıyor: ${device.address}")
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            AppLog.w("Bluetooth bağlantı izni yok", e)
            wantConnected = false
            setState(State.IDLE, "Bluetooth izni verilmemiş")
            return
        }
        if (!autoConnect) main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) {
            AppLog.w("Bağlantı zaman aşımı")
            handleDisconnect(-1)
        }
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(mtuTimeout)
        main.removeCallbacks(notifyTimeout)
        main.removeCallbacks(handshakeTimeout)
        gatt?.let {
            try {
                it.disconnect()
            } catch (_: Exception) {
            }
            it.close()
        }
        gatt = null
        writeChar = null
    }

    private fun resetLinkState() {
        queue.clear()
        writeInFlight = false
        assembler.reset()
        handshakeSeq = -1
        cameraSleeping = false
        lastStatus = null
        listener.onVerifyCode(null)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) {
                    g.close()
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(connectTimeout)
                    AppLog.i("Bluetooth bağlandı, MTU isteniyor")
                    setState(State.CONNECTING, "Bağlandı, hazırlanıyor…")
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        AppLog.w("MTU isteği gönderilemedi")
                        discoverServices(g)
                    } else {
                        main.postDelayed(mtuTimeout, 3_000)
                    }
                } else {
                    handleDisconnect(status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            main.post {
                if (g != gatt) return@post
                main.removeCallbacks(mtuTimeout)
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
                AppLog.i("MTU: $mtu")
                listener.onMtu(mtu)
                discoverServices(g)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    AppLog.w("Servis keşfi başarısız (durum $status)")
                    g.disconnect()
                    return@post
                }
                setupCharacteristics(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g != gatt) return@post
                main.removeCallbacks(notifyTimeout)
                if (status != BluetoothGatt.GATT_SUCCESS) AppLog.w("Bildirim açma yanıtı: durum $status")
                else AppLog.i("Kamera bildirimleri açıldı")
                main.postDelayed({ if (gatt == g) startHandshake() }, 500)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            main.post {
                if (g != gatt) return@post
                writeInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    writeFailures++
                    AppLog.w("Yazma başarısız (durum $status)")
                }
                pump()
            }
        }

        // Android 13 ve üstü
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val copy = value.copyOf()
            main.post { if (g == gatt) handleIncoming(copy) }
        }

        // Android 12 ve altı
        @Deprecated("Android 13 öncesi için")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val copy = characteristic.value?.copyOf() ?: return
            main.post { if (g == gatt) handleIncoming(copy) }
        }
    }

    private val mtuTimeout = Runnable {
        gatt?.let {
            AppLog.w("MTU yanıtı gelmedi, varsayılanla devam")
            discoverServices(it)
        }
    }

    private val notifyTimeout = Runnable {
        if (gatt != null && state == State.CONNECTING) {
            AppLog.w("Bildirim açma yanıtı gelmedi, yine de devam ediliyor")
            startHandshake()
        }
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        if (!g.discoverServices()) {
            AppLog.w("Servis keşfi başlatılamadı")
            g.disconnect()
        }
    }

    private fun setupCharacteristics(g: BluetoothGatt) {
        val service = g.getService(SERVICE_UUID)
        if (service == null) {
            AppLog.w("FFF0 servisi bulunamadı. Bulunan servisler: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
            g.disconnect()
            return
        }
        val notify = service.getCharacteristic(NOTIFY_UUID)
        val write = service.getCharacteristic(WRITE_UUID)
        if (notify == null || write == null) {
            AppLog.w("FFF4/FFF5 karakteristikleri bulunamadı")
            g.disconnect()
            return
        }
        writeChar = write
        if (!g.setCharacteristicNotification(notify, true)) AppLog.w("Bildirim kaydı başarısız")
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            AppLog.w("CCCD yok, doğrudan el sıkışmaya geçiliyor")
            main.postDelayed({ startHandshake() }, 500)
            return
        }
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!started) AppLog.w("Bildirim açma isteği gönderilemedi")
        main.postDelayed(notifyTimeout, 3_000)
    }

    private fun handleDisconnect(status: Int) {
        val address = currentDevice?.address
        closeGatt()
        resetLinkState()
        if (!wantConnected) {
            setState(State.IDLE, "Bağlı değil")
            return
        }
        reconnectAttempts++
        val delay = (1_000L * reconnectAttempts).coerceAtMost(5_000L)
        AppLog.w("Bağlantı koptu (durum $status). ${delay / 1000} sn sonra yeniden denenecek (deneme $reconnectAttempts)")
        setState(State.RECONNECTING, "Bağlantı koptu, yeniden bağlanılıyor… (deneme $reconnectAttempts)")
        main.postDelayed({
            if (!wantConnected) return@postDelayed
            val a = adapter
            when {
                a == null || !a.isEnabled -> startScan()
                address == null -> {
                    reconnectAttempts = 0
                    startScan()
                }
                reconnectAttempts <= 3 -> connectTo(a.getRemoteDevice(address), currentName)
                else -> {
                    // Kamera kapalı ya da uzakta olabilir: Android'in otomatik bağlanmasıyla
                    // kamera tekrar görünür olana kadar bekle. Ekran kapalıyken de çalışır.
                    AppLog.i("Kamera görünür olunca otomatik bağlanılacak")
                    connectTo(a.getRemoteDevice(address), currentName, autoConnect = true)
                }
            }
        }, delay)
    }

    // ---------------------------------------------------------------- Protokol

    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xFFFF
        if (seq == 0) seq = 1
        return seq
    }

    private fun startHandshake() {
        if (gatt == null || writeChar == null) return
        if (state == State.HANDSHAKE || state == State.READY) return
        main.removeCallbacks(notifyTimeout)
        val code = Random.nextInt(1000, 10000)
        listener.onVerifyCode(code)
        setState(State.HANDSHAKE, "Kamerada çıkan bağlantı isteğini onaylayın (kod: $code)")
        val request = ConnectionRequest(
            deviceId = REMOTE_DEVICE_ID,
            mac = remoteMac,
            fwVersion = 0, // 0: kamera kumandaya yazılım güncellemesi önermez
            verifyMode = 0, // kamera eşleşme geçmişine bakar, gerekirse onay ister
            verifyData = code,
        )
        handshakeSeq = nextSeq()
        AppLog.i("Bağlantı isteği gönderildi (kod $code)")
        sendFrame(CmdType.WAIT_RESULT, handshakeSeq, Cmd.SET_GENERAL, Cmd.ID_CONNECT, request.toPayload(), withResponse = true)
        main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    }

    private val handshakeTimeout = Runnable {
        if (state == State.HANDSHAKE) {
            AppLog.w("Kamera ${HANDSHAKE_TIMEOUT_MS / 1000} sn içinde bağlantıyı onaylamadı")
            gatt?.disconnect() ?: handleDisconnect(-2)
        }
    }

    private fun handleIncoming(data: ByteArray) {
        for (frame in assembler.feed(data)) handleFrame(frame)
    }

    private fun handleFrame(f: DjiFrame) {
        when {
            f.matches(Cmd.SET_GENERAL, Cmd.ID_CONNECT) -> handleConnectFrame(f)
            f.matches(Cmd.SET_CAMERA, Cmd.ID_STATUS_PUSH) && !f.isResponse -> handleStatus(f)
            f.matches(Cmd.SET_GENERAL, Cmd.ID_KEY_REPORT) && f.isResponse -> {
                val ret = f.payload.firstOrNull()?.toInt() ?: -1
                AppLog.i(if (ret == 0) "Kamera tuş komutunu kabul etti" else "Kamera tuş komutunu reddetti (kod $ret)")
            }
            f.matches(Cmd.SET_GENERAL, Cmd.ID_GPS_PUSH) && f.isResponse -> {
                val ret = f.payload.firstOrNull()?.toInt() ?: 0
                if (ret != 0) AppLog.w("Kamera GPS paketini reddetti (kod $ret)")
            }
            f.matches(Cmd.SET_GENERAL, Cmd.ID_VERSION) && f.isResponse -> {
                if (f.payload.size > 2) {
                    val text = String(f.payload, 2, f.payload.size - 2, Charsets.US_ASCII)
                        .replace(Regex("[^\\x20-\\x7E]+"), " ").trim()
                    AppLog.i("Kamera sürümü: $text")
                }
            }
            f.matches(Cmd.SET_CAMERA, Cmd.ID_NEW_STATUS_PUSH) -> Unit
            else -> AppLog.i("Kameradan: $f")
        }
    }

    private fun handleConnectFrame(f: DjiFrame) {
        if (f.isResponse) {
            val resp = ConnectionResponse.parse(f.payload)
            if (resp == null) {
                AppLog.w("Bağlantı yanıtı okunamadı")
                return
            }
            if (resp.retCode != 0) {
                AppLog.w("Kamera bağlantı isteğini reddetti (kod ${resp.retCode})")
                setState(State.HANDSHAKE, "Kamera isteği reddetti (kod ${resp.retCode}). Kamerada kablosuz bağlantıyı sıfırlayıp tekrar deneyin.")
            } else {
                AppLog.i("Kamera isteği aldı, onay bekleniyor")
            }
            return
        }
        val req = ConnectionRequest.parse(f.payload)
        if (req == null) {
            AppLog.w("Kameranın bağlantı isteği okunamadı")
            return
        }
        if (req.verifyMode != 2) {
            AppLog.w("Beklenmeyen doğrulama modu: ${req.verifyMode}")
            return
        }
        if (req.verifyData != 0) {
            AppLog.w("Kamera bağlantıyı reddetti")
            wantConnected = false
            closeGatt()
            resetLinkState()
            setState(State.IDLE, "Kamera bağlantıyı reddetti. Kamerada kablosuz bağlantıyı sıfırlayıp tekrar deneyin.")
            return
        }
        cameraDeviceId = req.deviceId
        val response = ConnectionResponse(REMOTE_DEVICE_ID, retCode = 0, cameraIndex = 0)
        sendFrame(CmdType.ACK_NO_RESPONSE, f.seq, Cmd.SET_GENERAL, Cmd.ID_CONNECT, response.toPayload(), withResponse = false)
        onReady()
    }

    private fun onReady() {
        main.removeCallbacks(handshakeTimeout)
        reconnectAttempts = 0
        val model = DjiCamera.modelName(cameraDeviceId)
        AppLog.i("Kamera bağlandı: ${model ?: "bilinmeyen model"} (kimlik 0x%08X)".format(cameraDeviceId))
        listener.onVerifyCode(null)
        setState(State.READY, "Bağlı" + (model?.let { ": $it" } ?: ""))
        currentDevice?.let { listener.onCameraReady(it.address, currentName, model) }
        if (mtu < DjiFrame.OVERHEAD + 48 + 3) {
            AppLog.w("MTU küçük ($mtu). GPS paketleri gönderilemeyebilir.")
        }
        main.postDelayed({
            if (state != State.READY) return@postDelayed
            sendFrame(CmdType.NO_RESPONSE, nextSeq(), Cmd.SET_CAMERA, Cmd.ID_STATUS_SUBSCRIBE, StatusSubscription.payload(), withResponse = false)
            sendFrame(CmdType.WAIT_RESULT, nextSeq(), Cmd.SET_GENERAL, Cmd.ID_VERSION, ByteArray(0), withResponse = true)
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }, 300)
    }

    private fun handleStatus(f: DjiFrame) {
        val status = CameraStatus.parse(f.payload) ?: return
        val previous = lastStatus
        lastStatus = status
        val wasSleeping = cameraSleeping
        cameraSleeping = status.isSleeping
        if (cameraSleeping && !wasSleeping) {
            AppLog.i("Kamera uyku moduna geçti, GPS gönderimi duraklatıldı")
            queue.removeAll { it.isGps }
        } else if (!cameraSleeping && wasSleeping) {
            AppLog.i("Kamera uyandı")
        }
        if (previous == null || previous.isRecording != status.isRecording || previous.mode != status.mode) {
            AppLog.i("Kamera: ${status.modeName}, ${status.statusName}, pil %${status.batteryPercent}")
        }
        listener.onCameraStatus(status)
    }

    // ---------------------------------------------------------------- Yazma kuyruğu

    private fun sendFrame(cmdType: Int, seq: Int, set: Int, id: Int, payload: ByteArray, withResponse: Boolean) {
        enqueue(DjiFrame.encode(cmdType, seq, set, id, payload), withResponse, isGps = false)
    }

    private fun enqueue(data: ByteArray, withResponse: Boolean, isGps: Boolean) {
        if (isGps) {
            // Kuyrukta bekleyen eski GPS paketi varsa yenisiyle değiştir; gecikme birikmesin.
            queue.removeAll { it.isGps }
        }
        if (!warnedMtu && data.size > mtu - 3) {
            warnedMtu = true
            AppLog.w("Paket (${data.size} bayt) MTU'dan ($mtu) büyük")
        }
        queue.addLast(PendingWrite(data, withResponse, isGps))
        pump()
    }

    private fun pump() {
        if (writeInFlight) {
            val limit = if (inFlightWithResponse) WRITE_TIMEOUT_MS else NO_RESPONSE_WRITE_TIMEOUT_MS
            if (SystemClock.elapsedRealtime() - writeStartedAt < limit) return
            AppLog.w("Yazma yanıtı gecikti, devam ediliyor")
            writeInFlight = false
        }
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = queue.removeFirstOrNull() ?: return
        val type = if (next.withResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, next.data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = next.data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        } catch (e: Exception) {
            AppLog.w("Yazma hatası", e)
            false
        }
        if (ok) {
            writeInFlight = true
            inFlightWithResponse = next.withResponse
            writeStartedAt = SystemClock.elapsedRealtime()
        } else {
            writeFailures++
            // GPS paketleri zaten sürekli yenileniyor; diğerlerini kısa süre sonra tekrar dene.
            next.attempts++
            if (!next.isGps && next.attempts < 20) {
                queue.addFirst(next)
            } else if (!next.isGps) {
                AppLog.w("Komut gönderilemedi, vazgeçildi")
            }
            main.postDelayed({ pump() }, 30)
        }
    }

    private fun setState(newState: State, detail: String) {
        state = newState
        listener.onLinkState(newState, detail)
    }
}
