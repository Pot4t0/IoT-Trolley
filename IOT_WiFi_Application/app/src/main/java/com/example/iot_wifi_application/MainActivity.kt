package com.example.iot_wifi_application

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.os.Bundle
import android.util.Log
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.android.volley.toolbox.Volley
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.example.iot_wifi_application.ui.theme.IOT_WiFi_ApplicationTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.pow

class MainActivity : ComponentActivity() {
    private val wifiManager by lazy { getSystemService(Context.WIFI_SERVICE) as WifiManager }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val results = wifiManager.scanResults
            val filteredResults = results.filter { it.SSID == "M5Stick_WiFi" }
            Log.d("WiFiScan", "Filtered Results: ${filteredResults.size}")
            CoroutineScope(Dispatchers.Main).launch {
                wifiResults = filteredResults.map {
                    WifiResult(it.SSID, it.BSSID, it.level)
                }
                updatePosition()
            }
        }
    }

    private var wifiResults by mutableStateOf<List<WifiResult>>(emptyList())
    private var estimatedPosition by mutableStateOf<Point?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()
        registerReceiver(wifiScanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        setContent {
            IOT_WiFi_ApplicationTheme {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Wi-Fi Scan Results:")
                    LazyColumn {
                        items(wifiResults) { result ->
                            Text("SSID: ${result.ssid}, BSSID: ${result.bssid}, RSSI: ${result.rssi} dBm")
                        }
                    }

                    Text("Estimated Position: ${estimatedPosition?.let { "(${it.x}, ${it.y})" } ?: "Unknown"}")
                }
            }
        }

        // Start periodic Wi-Fi scanning
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    wifiManager.startScan()
                }
                kotlinx.coroutines.delay(1000) // Scan every 1 seconds
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            1
        )
    }

    private fun updatePosition() {
        val knownAPs = listOf(
            KnownAP("ac:0b:fb:6f:9d:51", Point(0.0, 0.0)), //M5Stick
            KnownAP("52:eb:71:57:2e:e8", Point(10.0, 0.0)), //Laptop
            KnownAP("0e:ae:b2:86:11:c8", Point(5.0, 10.0)) //Iphone
        )

        val distances = wifiResults.mapNotNull { result ->
            val knownAP = knownAPs.find { it.bssid.equals(result.bssid, ignoreCase = true) }
            if (knownAP != null) {
                val distance = calculateDistanceFromRSSI(result.rssi)
                Log.d("WiFiScan", "Matched BSSID: ${result.bssid}, RSSI: ${result.rssi}, Distance: $distance")
                Pair(knownAP.position, distance)
            } else {
                Log.d("WiFiScan", "No match found for BSSID: ${result.bssid}")
                null
            }
        }

        Log.d("WiFiScan", "Number of valid distances: ${distances.size}")

        if (distances.size >= 3) {
            estimatedPosition = trilaterate(
                distances[0].first, distances[0].second,
                distances[1].first, distances[1].second,
                distances[2].first, distances[2].second
            )
            Log.d("WiFiScan", "Estimated Position: ${estimatedPosition}")
        } else {
            estimatedPosition = null
            Log.d("WiFiScan", "Not enough results for trilateration.")
        }
    }

    private fun calculateDistanceFromRSSI(rssi: Int): Double {
        val txPower = -59 // RSSI at 1 meter (calibrate this for your environment)
        val n = 2.0 // Path loss exponent (typically 2-4 for indoor environments)
        return 10.0.pow((txPower - rssi) / (10 * n))
    }

    private fun trilaterate(
        ap1: Point, r1: Double,
        ap2: Point, r2: Double,
        ap3: Point, r3: Double
    ): Point {
        val A = 2 * ap2.x - 2 * ap1.x
        val B = 2 * ap2.y - 2 * ap1.y
        val C = r1.pow(2) - r2.pow(2) - ap1.x.pow(2) + ap2.x.pow(2) - ap1.y.pow(2) + ap2.y.pow(2)
        val D = 2 * ap3.x - 2 * ap2.x
        val E = 2 * ap3.y - 2 * ap2.y
        val F = r2.pow(2) - r3.pow(2) - ap2.x.pow(2) + ap3.x.pow(2) - ap2.y.pow(2) + ap3.y.pow(2)

        val x = (C * E - F * B) / (E * A - B * D)
        val y = (C * D - A * F) / (B * D - A * E)
        return Point(x, y)
    }
}

data class WifiResult(val ssid: String, val bssid: String, val rssi: Int)
data class Point(val x: Double, val y: Double)
data class KnownAP(val bssid: String, val position: Point)

