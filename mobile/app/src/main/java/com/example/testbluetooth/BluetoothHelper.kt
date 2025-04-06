package com.example.testbluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class BluetoothHelper(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private val _discoveredDevices = MutableLiveData<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: LiveData<List<BluetoothDevice>> get() = _discoveredDevices

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
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
                    val currentList = _discoveredDevices.value ?: emptyList()
                    if (!currentList.contains(it)) {
                        _discoveredDevices.postValue(currentList + it)
                    }
                }
            }
        }
    }

    fun registerReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(bluetoothReceiver, filter)
    }

    fun unregisterReceiver() {
        context.unregisterReceiver(bluetoothReceiver)
    }

    fun checkBluetoothPermission(requestPermissions: ActivityResultLauncher<Array<String>>) {
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
                    context,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }) {
            requestPermissions.launch(permissions.toTypedArray())
        } else {
            scanDevices()
        }
    }

    @SuppressLint("MissingPermission")
    fun scanDevices() {
        _discoveredDevices.postValue(emptyList())
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (bluetoothAdapter == null) {
                    Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
                    return
                }

                if (bluetoothAdapter?.isEnabled == false) {
                    Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
                    return
                }

                bluetoothAdapter?.startDiscovery()
            } else {
                Toast.makeText(context, "Missing Bluetooth permission", Toast.LENGTH_SHORT).show()
            }
        } catch (e: SecurityException) {
            Log.e("Bluetooth", "SecurityException: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(
                    context,
                    "Connecting to ${device.name ?: device.address}",
                    Toast.LENGTH_SHORT
                ).show()
                Log.d("Bluetooth", "Attempting connection to ${device.name ?: device.address}")

                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    device.createBond() // Try to bond
                } else {
                    Toast.makeText(context, "Already Paired", Toast.LENGTH_SHORT).show()
                }

                // Navigate to the Range Detection Screen
                val intent = Intent(context, RangeDetectionActivity::class.java).apply {
                    putExtra("device_address", device.address)
                }
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "Missing Bluetooth connection permission", Toast.LENGTH_SHORT)
                    .show()
            }
        } catch (e: SecurityException) {
            Log.e("Bluetooth", "SecurityException: ${e.message}")
        }
    }
}
