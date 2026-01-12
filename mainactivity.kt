package com.example.lab3

import android.Manifest
import android.bluetooth.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import java.io.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvInfo: TextView
    private lateinit var spinnerPaired: Spinner
    private lateinit var btnServer: Button
    private lateinit var btnClient: Button
    private lateinit var lvChat: ListView
    private lateinit var etMessage: EditText
    private lateinit var btnSend: Button
    private lateinit var swNotify: SwitchCompat

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var pairedDevices: Set<BluetoothDevice> = emptySet()
    private var targetDevice: BluetoothDevice? = null

    private var connectedSocket: BluetoothSocket? = null
    private var connectedThread: ConnectedThread? = null

    private val chatHistory = ArrayList<String>()
    private lateinit var chatAdapter: ArrayAdapter<String>

    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)

    private val APP_UUID: UUID =
        UUID.fromString("4bf0513e-1cda-4c3e-8ee3-4d09d4c7c336")

    // ===== Zad.1 permission (CONNECT + SCAN na Android 12+) =====
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val okConnect = perms[Manifest.permission.BLUETOOTH_CONNECT] == true
            val okScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms[Manifest.permission.BLUETOOTH_SCAN] == true
            } else true

            if (okConnect && okScan) initBluetooth()
            else tvInfo.text = "Status: brak BLUETOOTH_CONNECT/SCAN"
        }

    private val enableBtRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) loadPairedDevices()
            else tvInfo.text = "Status: Bluetooth niewłączony"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInfo = findViewById(R.id.tvInfo)
        spinnerPaired = findViewById(R.id.spinnerPaired)
        btnServer = findViewById(R.id.btnServer)
        btnClient = findViewById(R.id.btnClient)
        lvChat = findViewById(R.id.lvChat)
        etMessage = findViewById(R.id.etMessage)
        btnSend = findViewById(R.id.btnSend)
        swNotify = findViewById(R.id.swNotify)

        chatAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, chatHistory)
        lvChat.adapter = chatAdapter

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        checkPermissionAndStart()

        btnServer.setOnClickListener {
            tvInfo.text = "Status: serwer uruchomiony, czekam na klienta..."
            ServerThread().start()
        }

        btnClient.setOnClickListener {
            if (targetDevice == null) {
                tvInfo.text = "Status: wybierz urządzenie z listy"
                return@setOnClickListener
            }
            tvInfo.text = "Status: łączenie jako klient..."
            ClientThread(targetDevice!!).start()
        }

        btnSend.setOnClickListener {
            val msg = etMessage.text.toString()
            if (msg.isBlank()) return@setOnClickListener

            val ct = connectedThread
            if (ct == null) {
                tvInfo.text = "Status: brak połączenia (najpierw serwer/klient)"
                return@setOnClickListener
            }

            ct.writeLine(msg)
            appendChat("Ja: $msg")
            etMessage.text.clear()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { tone.release() } catch (_: Exception) {}
        connectedThread?.cancel()
        try { connectedSocket?.close() } catch (_: Exception) {}
    }

    private fun hasBtConnect(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasBtScan(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    // ===== Zad.1 =====
    private fun checkPermissionAndStart() {
        if (bluetoothAdapter == null) {
            tvInfo.text = "Status: brak wsparcia Bluetooth"
            spinnerPaired.isEnabled = false
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (!hasBtConnect() || !hasBtScan())
        ) {
            tvInfo.text = "Status: proszę o BLUETOOTH_CONNECT/SCAN..."
            permissionRequest.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
            )
        } else {
            initBluetooth()
        }
    }

    private fun initBluetooth() {
        val a = bluetoothAdapter ?: return

        if (!a.isEnabled) {
            tvInfo.text = "Status: Bluetooth wyłączony → proszę o włączenie"
            enableBtRequest.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            loadPairedDevices()
        }
    }

    // ===== Zad.2 =====
    private fun loadPairedDevices() {
        val a = bluetoothAdapter ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBtConnect()) return

        pairedDevices = a.bondedDevices ?: emptySet()
        val list = ArrayList<String>()

        for (d in pairedDevices) {
            list.add("${d.name ?: "Unknown"} [${d.address}]")
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            if (list.isEmpty()) arrayListOf("Brak sparowanych urządzeń") else list
        )

        spinnerPaired.adapter = adapter
        spinnerPaired.isEnabled = list.isNotEmpty()

        spinnerPaired.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: android.view.View,
                position: Int, id: Long
            ) {
                if (list.isEmpty()) return
                val desc = parent.getItemAtPosition(position).toString()
                val mac = desc.substring(desc.indexOf('[') + 1, desc.indexOf(']'))
                targetDevice = pairedDevices.firstOrNull { it.address == mac }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ===== Zad.4 SERWER =====
    private inner class ServerThread : Thread() {
        override fun run() {
            if (!hasBtConnect()) {
                runOnUiThread { tvInfo.text = "Status: brak BLUETOOTH_CONNECT" }
                return
            }

            val serverSocket: BluetoothServerSocket? = try {
                bluetoothAdapter?.listenUsingRfcommWithServiceRecord("Lab3Service", APP_UUID)
            } catch (se: SecurityException) {
                runOnUiThread { tvInfo.text = "Status: SecurityException (serwer)" }
                null
            }

            try {
                val socket = serverSocket?.accept()
                if (socket != null) onConnected(socket)
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { tvInfo.text = "Status: błąd serwera" }
            } finally {
                try { serverSocket?.close() } catch (_: IOException) {}
            }
        }
    }

    // ===== Zad.4 KLIENT =====
    private inner class ClientThread(private val device: BluetoothDevice) : Thread() {
        override fun run() {
            if (!hasBtConnect()) {
                runOnUiThread { tvInfo.text = "Status: brak BLUETOOTH_CONNECT" }
                return
            }

            // cancelDiscovery wymaga BLUETOOTH_SCAN na Android 12+
            if (hasBtScan()) {
                try { bluetoothAdapter?.cancelDiscovery() } catch (_: SecurityException) {}
            }

            try {
                val socket = device.createRfcommSocketToServiceRecord(APP_UUID)
                socket.connect()
                onConnected(socket)
            } catch (e: IOException) {
                e.printStackTrace()
                runOnUiThread { tvInfo.text = "Status: nie udało się połączyć" }
            } catch (se: SecurityException) {
                runOnUiThread { tvInfo.text = "Status: SecurityException (klient)" }
            }
        }
    }

    // ===== Zad.5: połączenie + wątek I/O =====
    private fun onConnected(socket: BluetoothSocket) {
        connectedSocket = socket
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket).also { it.start() }

        runOnUiThread {
            tvInfo.text = "Status: połączono ✅"
            appendChat("SYSTEM: Połączono")
        }
    }

    private fun appendChat(line: String) {
        chatHistory.add(line)
        chatAdapter.notifyDataSetChanged()
        lvChat.smoothScrollToPosition(chatHistory.size - 1)
    }

    private fun notifyOnReceive() {
        if (!swNotify.isChecked) return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream))
        private val writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.outputStream)), true)

        override fun run() {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    runOnUiThread {
                        notifyOnReceive()
                        appendChat("Druga strona: $line")
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                cancel()
                runOnUiThread {
                    tvInfo.text = "Status: rozłączono"
                    appendChat("SYSTEM: Rozłączono")
                }
            }
        }

        fun writeLine(msg: String) {
            writer.println(msg)
        }

        fun cancel() {
            try { socket.close() } catch (_: IOException) {}
        }
    }
}
