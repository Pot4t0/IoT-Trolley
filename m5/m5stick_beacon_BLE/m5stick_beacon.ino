#include "BLEDevice.h"
#include <M5StickCPlus.h>
#include "esp_gap_ble_api.h"

const char *BEACON_NAME = "M5StickC_Beacon_2";
const char *BEACON_UUID = "01234567-0123-4567-89ab-0123456789ab";  // Shared UUID
const int TX_POWER = -59;  // Calibrated RSSI at 1 meter

void setup() {
    Serial.begin(115200);
    M5.begin();
    M5.Lcd.setRotation(3);
    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setTextSize(2);
    M5.Lcd.setTextColor(WHITE);
    M5.Lcd.setCursor(0, 0);
    M5.Lcd.println("BLE Beacon 2 Active");

    Serial.println("\n=== Initializing BLE Beacon 2 ===");
    BLEDevice::init(BEACON_NAME);
    BLEDevice::setPower(ESP_PWR_LVL_N12);

    // Get and display MAC Address
    String macAddress = BLEDevice::getAddress().toString().c_str();
    Serial.println("MAC Address: " + macAddress);
    M5.Lcd.setCursor(0, 40);
    M5.Lcd.printf("MAC: %s", macAddress.c_str());

    // Create advertising object
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->setScanResponse(false);
    pAdvertising->setMinInterval(0x20);
    pAdvertising->setMaxInterval(0x40);

    // Prepare advertisement data
    BLEAdvertisementData oAdvertisementData = BLEAdvertisementData();
    oAdvertisementData.setName(BEACON_NAME);
    oAdvertisementData.setManufacturerData("M5Beacon");
    oAdvertisementData.setCompleteServices(BLEUUID(BEACON_UUID));

    // Set Major and Minor values
    oAdvertisementData.setServiceData(BLEUUID(BEACON_UUID), std::string("\x00\x01\x00\x02", 4));  // Major = 1, Minor = 2

    pAdvertising->setAdvertisementData(oAdvertisementData);
    pAdvertising->start();

    Serial.println("Beacon 2 advertising started successfully!");
    M5.Lcd.setCursor(0, 80);
    M5.Lcd.println("Advertising OK");

    // Print beacon details
    Serial.println("\n=== Beacon 2 Details ===");
    Serial.println("UUID: " + String(BEACON_UUID));
    Serial.println("Major: 1");
    Serial.println("Minor: 2");
    Serial.println("TX Power: " + String(TX_POWER) + " dBm");
    Serial.println("========================");
}

void loop() {
    Serial.println("Beacon 2 broadcasting...");
    delay(1000);
}
