package com.example.appmind

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.*
import android.os.*
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.facebook.login.LoginManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.navigation.NavigationView
import com.google.firebase.auth.FirebaseAuth
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.*

class HomeActivity : AppCompatActivity() {

    // ---- UI ----
    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var navView: NavigationView
    private lateinit var toggle: ActionBarDrawerToggle

    private lateinit var chipStatus: Chip
    private lateinit var btnPair: MaterialButton
    private lateinit var tvMetricMain: TextView
    private lateinit var tvMetricSub: TextView
    private lateinit var tvLastConnection: TextView
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var ivMindfulness: ImageView

    // ---- BT/HC-05 ----
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val hc05Names = listOf("HC-05", "HC05", "HC 05")

    private val btManager by lazy { getSystemService(BluetoothManager::class.java) }
    private val btAdapter: BluetoothAdapter? by lazy { btManager?.adapter }

    private var connectThread: Thread? = null
    private var readThread: Thread? = null
    private var btSocket: BluetoothSocket? = null
    private var connectedDevice: BluetoothDevice? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Qué hacer después de conceder permisos
    private enum class NextStep { NONE, ENABLE, SCAN_OR_CONNECT }
    private var nextAfterPerms: NextStep = NextStep.NONE

    // ---- Permisos (Android 12+) ----
    private val btPermsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grant ->
        val required = needsBtRuntimePerms()
        val ok = required.isEmpty() || required.all { grant[it] == true }
        if (!ok) {
            toast("Permisos Bluetooth denegados")
            return@registerForActivityResult
        }
        when (nextAfterPerms) {
            NextStep.ENABLE -> enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            NextStep.SCAN_OR_CONNECT, NextStep.NONE -> startScanOrConnect()
        }
        nextAfterPerms = NextStep.NONE
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Tras volver del diálogo del sistema, seguimos
        startScanOrConnect()
    }

    // ---- Scan / Pair receivers ----
    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (dev?.name != null && hc05Names.any { dev.name.contains(it, true) }) {
                        stopDiscovery()
                        connectedDevice = dev
                        if (dev.bondState != BluetoothDevice.BOND_BONDED) {
                            updateStatus("Emparejando…", false)
                            dev.createBond() // PIN típico 1234 o 0000
                        } else connectToDevice(dev)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (btSocket == null || !isConnected()) {
                        updateStatus("No se encontró HC-05", false)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val dev: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (dev != null && connectedDevice?.address == dev.address) {
                        when (dev.bondState) {
                            BluetoothDevice.BOND_BONDED -> connectToDevice(dev)
                            BluetoothDevice.BOND_NONE -> updateStatus("Emparejamiento cancelado", false)
                        }
                    }
                }
            }
        }
    }

    // ---- Ciclo de vida ----
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        // Drawer + toolbar
        drawer = findViewById(R.id.drawer_layout)
        toolbar = findViewById(R.id.toolbar)
        navView = findViewById(R.id.navigation_view)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.nav_open, R.string.nav_close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        // Header
        val header = navView.getHeaderView(0)
        val tvName = header.findViewById<TextView>(R.id.headerName)
        val tvEmail = header.findViewById<TextView>(R.id.headerEmail)
        val user = FirebaseAuth.getInstance().currentUser
        tvName.text = user?.displayName ?: "Usuario"
        tvEmail.text = user?.email ?: ""

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {}
                R.id.nav_perfil -> {}
                R.id.nav_ajustes -> {}
                R.id.nav_logout -> { signOutAll(); goToLogin() }
            }
            drawer.closeDrawers(); true
        }

        // ---- Bind vistas del layout ----
        chipStatus = findViewById(R.id.chipStatus)
        btnPair = findViewById(R.id.btnPair)
        tvMetricMain = findViewById(R.id.tvMetricMain)
        tvMetricSub = findViewById(R.id.tvMetricSub)
        tvLastConnection = findViewById(R.id.tvLastConnection)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        ivMindfulness = findViewById(R.id.ivMindfulness)

        // Estado inicial
        updateStatus("Desconectado", false)
        tvMetricMain.text = "--"
        tvMetricSub.text = "BPM / Ritmo"
        tvLastConnection.text = "Última conexión: --"

        // Acciones
        btnPair.setOnClickListener { ensureBtReadyThenConnect() }
        chipStatus.setOnClickListener { ensureBtReadyThenConnect() }
        btnCalibrate.setOnClickListener {
            toast("Iniciando rutina de calibración…")
            sendCommand("CAL\n")
        }

        // Receivers
        registerBtReceivers()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSafe(btReceiver)
        closeConnection()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (toggle.onOptionsItemSelected(item)) return true
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(navView)) drawer.closeDrawers() else super.onBackPressed()
    }

    // ---- Sesión ----
    private fun signOutAll() {
        FirebaseAuth.getInstance().signOut()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        GoogleSignIn.getClient(this, gso).signOut()
        LoginManager.getInstance().logOut()
        closeConnection()
    }

    private fun goToLogin() {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(i); finish()
    }

    // ---- Permisos + flujo correcto ----
    private fun needsBtRuntimePerms(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_CONNECT
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) need += Manifest.permission.BLUETOOTH_SCAN
            need.toTypedArray()
        } else emptyArray()
    }

    private fun ensureBtReadyThenConnect() {
        val adapter = btAdapter ?: run {
            toast("Este dispositivo no soporta Bluetooth"); return
        }

        // Primero: en Android 12+ pide permisos antes de cualquier acción
        val missing = needsBtRuntimePerms()
        if (missing.isNotEmpty()) {
            nextAfterPerms = if (!adapter.isEnabled) NextStep.ENABLE else NextStep.SCAN_OR_CONNECT
            btPermsLauncher.launch(missing)
            return
        }

        // Segundo: si BT está apagado, solicita encenderlo (ya tenemos CONNECT)
        if (!adapter.isEnabled) {
            nextAfterPerms = NextStep.SCAN_OR_CONNECT
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        // Tercero: ya todo listo
        startScanOrConnect()
    }

    // ---- BT core ----
    private fun registerBtReceivers() {
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        registerReceiver(btReceiver, f)
    }

    private fun unregisterReceiverSafe(r: BroadcastReceiver) {
        try { unregisterReceiver(r) } catch (_: Exception) { }
    }

    @SuppressLint("MissingPermission")
    private fun startScanOrConnect() {
        val bonded = btAdapter?.bondedDevices?.firstOrNull { d ->
            d.name != null && hc05Names.any { d.name.contains(it, true) }
        }
        if (bonded != null) {
            connectedDevice = bonded
            connectToDevice(bonded)
            return
        }
        updateStatus("Buscando HC-05…", false)
        btAdapter?.cancelDiscovery()
        btAdapter?.startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscovery() {
        try { btAdapter?.cancelDiscovery() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        stopDiscovery()
        updateStatus("Conectando…", false)

        connectThread?.interrupt()
        connectThread = Thread {
            try {
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                btSocket = socket
                socket.connect()
                connectedDevice = device
                onConnected()
                startReader(socket)
            } catch (e: Exception) {
                onConnectionError(e)
            }
        }.also { it.start() }
    }

    private fun startReader(socket: BluetoothSocket) {
        readThread?.interrupt()
        readThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                var line: String?
                while (!Thread.currentThread().isInterrupted && socket.isConnected) {
                    line = reader.readLine() ?: break
                    parseAndUpdate(line)
                }
            } catch (_: Exception) {
            } finally {
                onDisconnected()
            }
        }.also { it.start() }
    }

    private fun isConnected(): Boolean = btSocket?.isConnected == true

    private fun closeConnection() {
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        connectThread?.interrupt(); connectThread = null
        readThread?.interrupt(); readThread = null
        updateStatus("Desconectado", false)
    }

    // ---- UI updates ----
    private fun onConnected() {
        mainHandler.post {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            tvLastConnection.text = "Última conexión: $time"
            updateStatus("Conectado", true)
            toast("HC-05 conectado")
        }
    }

    private fun onConnectionError(e: Exception) {
        mainHandler.post {
            updateStatus("Error de conexión", false)
            toast("No se pudo conectar: ${e.message ?: "desconocido"}")
        }
    }

    private fun onDisconnected() {
        mainHandler.post {
            if (!isFinishing) updateStatus("Desconectado", false)
        }
    }

    private fun parseAndUpdate(raw: String) {
        val line = raw.trim()
        var bpm: Int? = null
        var spo2: Int? = null

        val lower = line.lowercase(Locale.getDefault())
        val nums = Regex("""\d{2,3}""").findAll(lower).map { it.value.toInt() }.toList()

        when {
            "spo" in lower && ("bpm" in lower || "hr" in lower) -> {
                val bpmMatch = Regex("""(bpm|hr)\D*(\d{2,3})""").find(lower)
                val spoMatch = Regex("""(spo2|spo)\D*(\d{2,3})""").find(lower)
                bpm = bpmMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                spo2 = spoMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
            }
            nums.size >= 2 -> { bpm = nums[0]; spo2 = nums[1] }
            nums.size == 1 -> { bpm = nums[0] }
        }

        mainHandler.post {
            bpm?.let { tvMetricMain.text = it.toString() }
            spo2?.let { tvMetricSub.text = "BPM / Ritmo  •  SpO₂ ${it}%" }
        }
    }

    private fun updateStatus(text: String, connected: Boolean) {
        chipStatus.text = "Estado: $text"
        if (connected) {
            chipStatus.setChipBackgroundColorResource(safeColorRes("status_connected_bg", android.R.color.holo_green_light))
            chipStatus.setTextColor(ContextCompat.getColor(this, safeColorRes("status_connected_text", android.R.color.white)))
            chipStatus.chipIconTint = ContextCompat.getColorStateList(this, safeColorRes("status_connected_text", android.R.color.white))
        } else {
            chipStatus.setChipBackgroundColorResource(safeColorRes("status_disconnected_bg", android.R.color.darker_gray))
            chipStatus.setTextColor(ContextCompat.getColor(this, safeColorRes("status_disconnected_text", android.R.color.white)))
            chipStatus.chipIconTint = ContextCompat.getColorStateList(this, safeColorRes("status_disconnected_text", android.R.color.white))
        }
    }

    private fun safeColorRes(name: String, fallbackAndroidColor: Int): Int {
        val resId = resources.getIdentifier(name, "color", packageName)
        return if (resId != 0) resId else fallbackAndroidColor
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun sendCommand(cmd: String) {
        try {
            val out = btSocket?.outputStream ?: return
            out.write(cmd.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) { }
    }
}
