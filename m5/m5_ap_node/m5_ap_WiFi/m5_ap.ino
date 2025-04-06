#include <M5StickCPlus.h>
#include <WiFi.h>
#include <math.h>
#include <esp_wifi.h>  // Include ESP-IDF WiFi API


#define WIFI_CHANNEL 13

const char* AP_SSID = "Anchor_A";       
const char* AP_PASSWORD = "passwordA";   

// Battery capacity in mAh (assumed full charge capacity)
const float BATTERY_CAPACITY = 120.0;

// Global variable to track remaining battery capacity (in mAh)
float remainingCapacity = BATTERY_CAPACITY;

// Timing for coulomb counting
unsigned long previousMillis = 0;
const float SAMPLE_INTERVAL = 1000.0; // Sample every 1 second

// Weight factor for combining the two estimation methods
// ALPHA is the weight for coulomb counting and (1 - ALPHA) for voltage-based estimation
const float ALPHA = 0.8;

// Returns a rough voltage-based percentage using a linear mapping.
// Assumes 3.0 V is empty and 4.2 V is full.
float getVoltageBasedPercentage(float voltage) {
  float percent = (voltage - 3.0) / (4.2 - 3.0) * 100.0;
  if (percent > 100.0) percent = 100.0;
  if (percent < 0.0) percent = 0.0;
  return percent;
}

void setup() {
  M5.begin();
  Serial.begin(115200);
  M5.Lcd.begin();
  M5.Lcd.fillScreen(BLACK);
  M5.Lcd.setTextColor(WHITE);
  M5.Lcd.setTextSize(2);

  WiFi.mode(WIFI_AP);
  bool apStarted = WiFi.softAP(AP_SSID, AP_PASSWORD, WIFI_CHANNEL);
  if (apStarted) {
    Serial.println("Access Point started successfully");
  } else {
    Serial.println("Access Point failed to start");
  }
  esp_wifi_set_max_tx_power(78);

  IPAddress apIP = WiFi.softAPIP();
  Serial.print("AP IP address: ");
  Serial.println(apIP);

  M5.Lcd.println("AP Mode");
  M5.Lcd.print("SSID: ");
  M5.Lcd.println(AP_SSID);
  M5.Lcd.print("IP: ");
  M5.Lcd.println(apIP);
  M5.Lcd.print("Channel: ");
  M5.Lcd.println(WIFI_CHANNEL);
  
  previousMillis = millis();
}

void loop() {
  unsigned long currentMillis = millis();
  float elapsedSeconds = (currentMillis - previousMillis) / 1000.0;
  
  // Sample roughly every second
  if (elapsedSeconds >= 1.0) {
    previousMillis = currentMillis;
    
    // Get instantaneous battery current in mA
    // Negative current indicates discharge; positive indicates charge.
    float batCurrent = M5.Axp.GetBatCurrent();
    
    // Calculate the charge change (in mAh) during this interval.
    // Using the absolute value since we integrate the magnitude of current.
    float delta_mAh = fabs(batCurrent) * (elapsedSeconds / 3600.0);
    
    // Update remaining capacity based on current direction.
    if (batCurrent < 0) {
      // Discharging: subtract the consumed charge.
      remainingCapacity -= delta_mAh;
    } else if (batCurrent > 0) {
      // Charging: add the charge.
      remainingCapacity += delta_mAh;
    }
    
    // Constrain remaining capacity within 0 and full capacity.
    if (remainingCapacity > BATTERY_CAPACITY) remainingCapacity = BATTERY_CAPACITY;
    if (remainingCapacity < 0) remainingCapacity = 0;
    
    // Coulomb counting based battery percentage.
    float coulombPercentage = (remainingCapacity / BATTERY_CAPACITY) * 100.0;
    
    // Voltage-based estimation.
    float batteryVoltage = M5.Axp.GetBatVoltage();
    float voltagePercentage = getVoltageBasedPercentage(batteryVoltage);
    
    // Combine both methods to get a more accurate estimation.
    float batteryPercentage = ALPHA * coulombPercentage + (1.0 - ALPHA) * voltagePercentage;
    
    // Output the details to Serial Monitor.
    Serial.print("Voltage: ");
    Serial.print(batteryVoltage, 2);
    Serial.print(" V, Current: ");
    Serial.print(batCurrent, 2);
    Serial.print(" mA, Coulomb %: ");
    Serial.print(coulombPercentage, 0);
    Serial.print("%, Voltage %: ");
    Serial.print(voltagePercentage, 0);
    Serial.print("%, Final %: ");
    Serial.print(batteryPercentage, 0);
    Serial.println("%");
    
    // Update LCD display.
    int batteryInfoY = 120;
    int batteryInfoHeight = 50;
    int batteryInfoWidth = M5.Lcd.width();
    
    // Clear the display area for battery info.
    M5.Lcd.fillRect(0, batteryInfoY, batteryInfoWidth, batteryInfoHeight, BLACK);
    M5.Lcd.setCursor(0, batteryInfoY);
    
    String batteryDisplay = "Batt: " + String(batteryVoltage, 2) + " V, " +
                            String((int)batteryPercentage) + "%";
    M5.Lcd.print(batteryDisplay);
  }
}