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
import io.github.avsessisozx.telemet.R
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
        private const val SETUP_TIMEOUT_MS = 12_000L
        /** Kamera kayıt durumunu değiştirirken art arda gelen basışlar yok sayılır. */
        private const val RECORD_DEBOUNCE_MS = 1_500L
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
    private var discoveryRetried = false
    private var reconnectAttempts = 0
    private var lastRecordPressAt = 0L

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
            AppLog.i("Closing connection")
            try {
                g.disconnect()
            } catch (e: Exception) {
                AppLog.w("disconnect error", e)
            }
            g.close()
        }
        gatt = null
        resetLinkState()
        setState(State.IDLE, str(R.string.link_not_connected))
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
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecordPressAt < RECORD_DEBOUNCE_MS) {
            AppLog.i("Record pressed twice in a row, second press ignored")
            return true
        }
        lastRecordPressAt = now
        AppLog.i("Sending record key")
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
            setState(State.IDLE, str(R.string.toast_no_bt))
            return
        }
        if (!a.isEnabled) {
            AppLog.w("Bluetooth is off, waiting for it to be turned on")
            setState(State.RECONNECTING, str(R.string.link_bt_off))
            main.postDelayed({ startScan() }, 5_000)
            return
        }
        val scanner = a.bluetoothLeScanner
        if (scanner == null) {
            scheduleRetryScan("Scanner not ready", str(R.string.link_retry_scanner))
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
            AppLog.w("No Bluetooth scan permission", e)
            setState(State.IDLE, str(R.string.link_no_bt_perm))
            wantConnected = false
            return
        }
        scanning = true
        scanStartedAt = SystemClock.elapsedRealtime()
        setState(State.SCANNING, str(R.string.link_scanning))
        AppLog.i(
            "Scan started (" + (if (useFilters) "filtered" else "unfiltered") + ")" +
                (preferredAddress?.let { ", preferred: $it" } ?: ""),
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
            AppLog.w("Could not stop scan", e)
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
                scheduleRetryScan("Camera not found", str(R.string.link_retry_not_found))
                return
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun scheduleRetryScan(reason: String, detail: String) {
        if (!wantConnected) return
        AppLog.i("$reason, scanning again in 3 s")
        setState(State.SCANNING, detail)
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
                AppLog.w("Scan failed (code $errorCode)")
                scheduleRetryScan("Scan error $errorCode", str(R.string.link_retry_error, errorCode))
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (!scanning) return
        if (!DjiCamera.isCameraAdvertisement(result.scanRecord?.bytes)) return
        val address = result.device.address
        val name = result.scanRecord?.deviceName ?: safeName(result.device)
        if (!candidates.containsKey(address)) {
            AppLog.i("Camera found: ${name ?: "unnamed"} [$address] signal ${result.rssi} dBm")
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
        discoveryRetried = false
        mtu = 23
        if (autoConnect) {
            setState(State.RECONNECTING, str(R.string.link_waiting_camera))
        } else {
            setState(State.CONNECTING, str(R.string.link_connecting_to, currentName ?: device.address))
        }
        AppLog.i("Opening GATT connection: ${device.address}")
        try {
            gatt = device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            AppLog.w("No Bluetooth connect permission", e)
            wantConnected = false
            setState(State.IDLE, str(R.string.link_no_bt_perm))
            return
        }
        if (!autoConnect) main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) {
            AppLog.w("Connection timed out")
            handleDisconnect(-1)
        }
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(setupTimeout)
        main.removeCallbacks(discoverAfterMtu)
        main.removeCallbacks(discoveryTimeout)
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
                    main.removeCallbacks(setupTimeout)
                    main.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
                    AppLog.i("Bluetooth connected, requesting MTU")
                    setState(State.CONNECTING, str(R.string.link_preparing))
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        AppLog.w("Could not send MTU request")
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
                if (servicesRequested) return@post
                // Yeniden bağlanırken MTU yanıtı iki kez gelebiliyor (önce kameranın, sonra bizim isteğimiz).
                // Servis keşfini ikinci yanıt gelene kadar kısa süre erteliyoruz; aksi halde Android
                // aynı anda iki işlemi kaldıramayıp keşfi sessizce düşürüyor ve bağlantı takılıyor.
                main.removeCallbacks(discoverAfterMtu)
                main.postDelayed(discoverAfterMtu, 600)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                main.removeCallbacks(discoveryTimeout)
                if (writeChar != null) return@post // tekrar denemenin geç gelen yanıtı
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    AppLog.w("Service discovery failed (status $status)")
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
                if (status != BluetoothGatt.GATT_SUCCESS) AppLog.w("Notification enable response: status $status")
                else AppLog.i("Camera notifications enabled")
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
                    AppLog.w("Write failed (status $status)")
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
            AppLog.w("No MTU response, continuing with default")
            discoverServices(it)
        }
    }

    private val discoverAfterMtu = Runnable { gatt?.let { discoverServices(it) } }

    /** Servis keşfi yanıtı gelmezse bir kez daha dener. */
    private val discoveryTimeout = Runnable {
        val g = gatt ?: return@Runnable
        if (writeChar != null) return@Runnable
        if (!discoveryRetried) {
            discoveryRetried = true
            AppLog.w("Service discovery did not respond, retrying")
            servicesRequested = false
            discoverServices(g)
        }
    }

    /**
     * Bluetooth bağlandıktan sonra kurulum (MTU, servis keşfi, bildirimler) belirli sürede
     * tamamlanmazsa bağlantıyı tamamen kapatıp baştan kurar. Takılı kalmayı önler.
     */
    private val setupTimeout = Runnable {
        if (gatt != null && state == State.CONNECTING) {
            AppLog.w("Connection setup not finished within ${SETUP_TIMEOUT_MS / 1000} s, starting over")
            handleDisconnect(-3)
        }
    }

    private val notifyTimeout = Runnable {
        if (gatt != null && state == State.CONNECTING) {
            AppLog.w("No notification enable response, continuing anyway")
            startHandshake()
        }
    }

    private fun discoverServices(g: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        main.removeCallbacks(discoverAfterMtu)
        if (!g.discoverServices()) {
            AppLog.w("Could not start service discovery")
            g.disconnect()
            return
        }
        main.removeCallbacks(discoveryTimeout)
        main.postDelayed(discoveryTimeout, 5_000)
    }

    private fun setupCharacteristics(g: BluetoothGatt) {
        val service = g.getService(SERVICE_UUID)
        if (service == null) {
            AppLog.w("FFF0 service not found. Services found: " + g.services.joinToString { it.uuid.toString().substring(4, 8) })
            g.disconnect()
            return
        }
        val notify = service.getCharacteristic(NOTIFY_UUID)
        val write = service.getCharacteristic(WRITE_UUID)
        if (notify == null || write == null) {
            AppLog.w("FFF4/FFF5 characteristics not found")
            g.disconnect()
            return
        }
        writeChar = write
        if (!g.setCharacteristicNotification(notify, true)) AppLog.w("Notification registration failed")
        val cccd = notify.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            AppLog.w("No CCCD, going straight to handshake")
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
        if (!started) AppLog.w("Could not send notification enable request")
        main.postDelayed(notifyTimeout, 3_000)
    }

    private fun handleDisconnect(status: Int) {
        val address = currentDevice?.address
        closeGatt()
        resetLinkState()
        if (!wantConnected) {
            setState(State.IDLE, str(R.string.link_not_connected))
            return
        }
        reconnectAttempts++
        val delay = (1_000L * reconnectAttempts).coerceAtMost(5_000L)
        AppLog.w("Connection lost (status $status). Retrying in ${delay / 1000} s (attempt $reconnectAttempts)")
        setState(State.RECONNECTING, str(R.string.link_reconnecting, reconnectAttempts))
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
                    AppLog.i("Will connect automatically when the camera becomes visible")
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
        main.removeCallbacks(setupTimeout)
        val code = Random.nextInt(1000, 10000)
        listener.onVerifyCode(code)
        setState(State.HANDSHAKE, str(R.string.link_approve, code))
        val request = ConnectionRequest(
            deviceId = REMOTE_DEVICE_ID,
            mac = remoteMac,
            fwVersion = 0, // 0: kamera kumandaya yazılım güncellemesi önermez
            verifyMode = 0, // kamera eşleşme geçmişine bakar, gerekirse onay ister
            verifyData = code,
        )
        handshakeSeq = nextSeq()
        AppLog.i("Connection request sent (code $code)")
        sendFrame(CmdType.WAIT_RESULT, handshakeSeq, Cmd.SET_GENERAL, Cmd.ID_CONNECT, request.toPayload(), withResponse = true)
        main.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    }

    private val handshakeTimeout = Runnable {
        if (state == State.HANDSHAKE) {
            AppLog.w("Camera did not approve the connection within ${HANDSHAKE_TIMEOUT_MS / 1000} s")
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
                AppLog.i(if (ret == 0) "Camera accepted key command" else "Camera rejected key command (code $ret)")
            }
            f.matches(Cmd.SET_GENERAL, Cmd.ID_GPS_PUSH) && f.isResponse -> {
                val ret = f.payload.firstOrNull()?.toInt() ?: 0
                if (ret != 0) AppLog.w("Camera rejected GPS packet (code $ret)")
            }
            f.matches(Cmd.SET_GENERAL, Cmd.ID_VERSION) && f.isResponse -> {
                if (f.payload.size > 2) {
                    val text = String(f.payload, 2, f.payload.size - 2, Charsets.US_ASCII)
                        .replace(Regex("[^\\x20-\\x7E]+"), " ").trim()
                    AppLog.i("Camera version: $text")
                }
            }
            f.matches(Cmd.SET_CAMERA, Cmd.ID_NEW_STATUS_PUSH) -> Unit
            else -> AppLog.i("From camera: $f")
        }
    }

    private fun handleConnectFrame(f: DjiFrame) {
        if (f.isResponse) {
            val resp = ConnectionResponse.parse(f.payload)
            if (resp == null) {
                AppLog.w("Could not parse connection response")
                return
            }
            if (resp.retCode != 0) {
                AppLog.w("Camera rejected the connection request (code ${resp.retCode})")
                setState(State.HANDSHAKE, str(R.string.link_rejected_code, resp.retCode))
            } else {
                AppLog.i("Camera received the request, waiting for approval")
            }
            return
        }
        val req = ConnectionRequest.parse(f.payload)
        if (req == null) {
            AppLog.w("Could not parse camera connection request")
            return
        }
        if (req.verifyMode != 2) {
            AppLog.w("Unexpected verify mode: ${req.verifyMode}")
            return
        }
        if (req.verifyData != 0) {
            AppLog.w("Camera rejected the connection")
            wantConnected = false
            closeGatt()
            resetLinkState()
            setState(State.IDLE, str(R.string.link_rejected))
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
        AppLog.i("Camera connected: ${model ?: "unknown model"} (id 0x%08X)".format(cameraDeviceId))
        listener.onVerifyCode(null)
        setState(State.READY, if (model != null) str(R.string.link_ready_model, model) else str(R.string.link_ready))
        currentDevice?.let { listener.onCameraReady(it.address, currentName, model) }
        if (mtu < DjiFrame.OVERHEAD + 48 + 3) {
            AppLog.w("MTU is small ($mtu). GPS packets may not be sent.")
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
            AppLog.i("Camera went to sleep, GPS sending paused")
            queue.removeAll { it.isGps }
        } else if (!cameraSleeping && wasSleeping) {
            AppLog.i("Camera woke up")
        }
        if (previous == null || previous.isRecording != status.isRecording || previous.mode != status.mode) {
            AppLog.i("Camera: ${status.modeName}, ${status.statusName}, battery ${status.batteryPercent}%")
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
            AppLog.w("Packet (${data.size} bytes) larger than MTU ($mtu)")
        }
        queue.addLast(PendingWrite(data, withResponse, isGps))
        pump()
    }

    private fun pump() {
        if (writeInFlight) {
            val limit = if (inFlightWithResponse) WRITE_TIMEOUT_MS else NO_RESPONSE_WRITE_TIMEOUT_MS
            if (SystemClock.elapsedRealtime() - writeStartedAt < limit) return
            AppLog.w("Write response late, continuing")
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
            AppLog.w("Write error", e)
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
                AppLog.w("Command could not be sent, giving up")
            }
            main.postDelayed({ pump() }, 30)
        }
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun setState(newState: State, detail: String) {
        state = newState
        listener.onLinkState(newState, detail)
    }
}
