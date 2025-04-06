package com.example.testbluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.testbluetooth.ui.theme.TestbluetoothTheme
import java.util.*

class RangeDetectionActivity : ComponentActivity() {
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var deviceAddress: String? = null

    // UI state
    private val rssiState = mutableStateOf(0)
    private val distanceState = mutableStateOf(0.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceAddress = intent.getStringExtra("device_address")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        checkPermissions()

        setContent {
            TestbluetoothTheme {
                RangeDetectionScreen(rssiState.value, distanceState.value)
            }
        }

        deviceAddress?.let { connectToDevice(it) }
    }

    private fun checkPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(address: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("Bluetooth", "Missing BLUETOOTH_CONNECT permission. Requesting now.")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
            return
        }

        val device = bluetoothAdapter.getRemoteDevice(address)
        Log.d("Bluetooth", "Connecting to device: $address")
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }


    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.d("Bluetooth", "Connected to GATT server. Discovering services...")

                runOnUiThread {
                    Toast.makeText(
                        this@RangeDetectionActivity,
                        "Connected",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // ✅ Check Permission Before Calling discoverServices()
                if (ActivityCompat.checkSelfPermission(
                        this@RangeDetectionActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    gatt?.discoverServices()
                } else {
                    Log.w("Bluetooth", "BLUETOOTH_CONNECT permission not granted. Cannot discover services.")
                    ActivityCompat.requestPermissions(
                        this@RangeDetectionActivity,
                        arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                        REQUEST_BLUETOOTH_CONNECT
                    )
                }

            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.d("Bluetooth", "Disconnected from GATT server.")

                runOnUiThread {
                    Toast.makeText(
                        this@RangeDetectionActivity,
                        "Disconnected",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // ✅ Close BluetoothGatt before setting to null
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("Bluetooth", "Services Discovered!")

                // ✅ Ensure `gatt` is not null before accessing services
                gatt?.services?.forEach { service ->
                    Log.d("Bluetooth", "Service UUID: ${service.uuid}")

                    service.characteristics.forEach { characteristic ->
                        Log.d("Bluetooth", "Characteristic UUID: ${characteristic.uuid}")

                        // ✅ Enable notifications for RSSI Characteristic
                        if (characteristic.uuid.toString() == RSSI_CHARACTERISTIC_UUID) {
                            enableCharacteristicNotifications(gatt, characteristic)
                        }
                    }
                }
            } else {
                Log.e("Bluetooth", "Service Discovery Failed")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic?.uuid?.toString() == RSSI_CHARACTERISTIC_UUID) {
                val rssiBytes = characteristic.value
                val rssiValue = rssiBytes?.let { it.toString(Charsets.UTF_8).trim().toIntOrNull() }

                if (rssiValue != null) {
                    Log.d("Bluetooth", "Received RSSI: $rssiValue")
                    runOnUiThread {
                        updateRSSIUI(rssiValue)
                    }
                } else {
                    Log.w("Bluetooth", "Failed to parse RSSI value")
                }
            } else {
                Log.w("Bluetooth", "Received unknown characteristic update")
            }
        }

    }

    @SuppressLint("MissingPermission")
    private fun enableCharacteristicNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("Bluetooth", "BLUETOOTH_CONNECT permission not granted.")
            return
        }

        Log.d("Bluetooth", "Enabling notifications for RSSI characteristic")
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
            Log.d("Bluetooth", "Descriptor written successfully")
        } else {
            Log.e("Bluetooth", "Descriptor not found for characteristic!")
        }
    }




    private fun updateRSSIUI(rssi: Int) {
        val distance = estimateDistance(rssi)
        rssiState.value = rssi
        distanceState.value = distance
    }

    private fun estimateDistance(rssi: Int): Double {
        val txPower = -59 // Typical RSSI at 1 meter
        return Math.pow(10.0, ((txPower - rssi) / 20.0))
    }

    override fun onDestroy() {
        super.onDestroy()

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothGatt?.close()
            bluetoothGatt = null
        } else {
            Log.w("Bluetooth", "BLUETOOTH_CONNECT permission not granted. Cannot close GATT.")
        }
    }

    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 101
        private const val RSSI_CHARACTERISTIC_UUID = "12345678-1234-5678-1234-56789abcdef0"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}

@Composable
fun RangeDetectionScreen(rssi: Int = 0, distance: Double = 0.0) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Bluetooth Range Detection", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "RSSI: $rssi dBm")
        Text(text = "Estimated Distance: ${String.format("%.2f", distance)} meters")
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewRangeDetectionScreen() {
    TestbluetoothTheme {
        RangeDetectionScreen(-50, 1.5)
    }
}
