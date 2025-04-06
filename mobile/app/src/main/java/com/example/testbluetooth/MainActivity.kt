package com.example.testbluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.testbluetooth.ui.theme.TestbluetoothTheme

class MainActivity : ComponentActivity() {
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                scanDevices()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth and Location permissions required",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkBluetoothPermission()

        // Register the receiver once
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(bluetoothReceiver, filter)
    }


    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            val action = intent?.action
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    if (!discoveredDevices.contains(it)) {
                        discoveredDevices.add(it)
                    }
                }
            }
        }
    }

    private fun checkBluetoothPermission() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(
                listOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            )
        }
        if (permissions.any {
                ActivityCompat.checkSelfPermission(
                    this,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions.launch(permissions.toTypedArray())
        } else {
            scanDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun scanDevices() {
        discoveredDevices.clear()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (bluetoothAdapter == null) {
                    Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
                    return
                }

                if (bluetoothAdapter?.isEnabled == false) {
                    Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
                    return
                }

                bluetoothAdapter?.startDiscovery()
            } else {
                Toast.makeText(this, "Missing Bluetooth permission", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: SecurityException) {
            Log.e("Bluetooth", "SecurityException: ${e.message}")
            return
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                val action = intent?.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_FOUND))
        setContent {
            TestbluetoothTheme {
                DeviceList(discoveredDevices) { device -> connectToDevice(device) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    this,
                    "Connecting to ${device.name ?: device.address}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.d("Bluetooth", "Attempting connection to ${device.name ?: device.address}")

                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    device.createBond() // Try to bond
                } else {
                    Toast.makeText(this, "Already Paired", Toast.LENGTH_SHORT).show()
                }
                // Navigate to the Range Detection Screen
                val intent = Intent(this, RangeDetectionActivity::class.java).apply {
                    putExtra("device_address", device.address)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Missing Bluetooth connection permission", Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: SecurityException) {
            Log.e("Bluetooth", "SecurityException: ${e.message}")
        }
    }

    @Composable
    fun DeviceList(devices: List<BluetoothDevice>, onClick: (BluetoothDevice) -> Unit) {
        val context = LocalContext.current
        LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(devices) { device ->
                val deviceName =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(
                            context, Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        device.name ?: "Unknown Device"
                    } else {
                        "Permission Required"
                    }
                Text(
                    text = deviceName,
                    modifier = Modifier.fillMaxWidth().clickable { onClick(device) }.padding(8.dp)
                )
            }
        }
    }



    @Preview(showBackground = true)
    @Composable
    fun PreviewDeviceList() {
        TestbluetoothTheme {
            DeviceList(devices = listOf(), onClick = {})
        }
    }
}