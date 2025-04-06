#include <M5StickCPlus.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include <math.h>

//-------------------------------------------
// Constants
//-------------------------------------------
#define WIFI_CHANNEL    13
#define BLINK_PERIOD    250  // Example LED blink period

// Out of Bounds Constraints
#define X_MIN 0.0
#define X_MAX 10.0
#define Y_MIN 0.0
#define Y_MAX 10.0

// Rolling average for RSSI
#define RSSI_SAMPLES 20
static int lastMilestone = 0;

// List of anchor SSIDs (or use BSSID if you prefer)
static const char* anchorSsids[3] = {"Anchor_A", "Anchor_B", "Anchor_C"};

// Hardcode anchor positions
static const float ax = 0.0,  ay = 0.0;    // Anchor A
static const float bx = 5.0, by = 0.0;    // Anchor B
static const float cx = 2.5,  cy = 5.0;   // Anchor C

// RSSI->distance parameters
static const float RSSI_REF = -54.0; // measured RSSI at 2.5m
static const float N_FACTOR = 4.30;  // path-loss exponent

// Ring buffers for each anchor's RSSI
static int rssiA_buf[RSSI_SAMPLES];
static int rssiB_buf[RSSI_SAMPLES];
static int rssiC_buf[RSSI_SAMPLES];
static int sampleCount= 0;

// Write indices
static int rssiA_index = 0;
static int rssiB_index = 0;
static int rssiC_index = 0;

// Sample counters (to know when we have 20 samples)
static int sampleCountA = 0;
static int sampleCountB = 0;
static int sampleCountC = 0;

// Global distances & position
volatile float distA = -1, distB = -1, distC = -1;
volatile float avgRssiA = -99, avgRssiB = -99, avgRssiC = -99;
volatile float xPos = 0, yPos = 0;

// Optional LED blink
static unsigned long lastBlinkMillis = 0;
static bool ledState = false;

//-------------------------------------------
// 802.11 Packet Structures (simplified)
//-------------------------------------------
typedef struct {
  uint16_t frame_ctrl;
  uint16_t duration_id;
  uint8_t  addr1[6];
  uint8_t  addr2[6];
  uint8_t  addr3[6];
  uint16_t seq_ctrl;
  // addr4[6] can appear, but not for typical beacons/probe responses
} wifi_ieee80211_mac_hdr_t;

typedef struct {
  wifi_ieee80211_mac_hdr_t hdr;
  uint8_t payload[0]; // variable-length payload
} wifi_ieee80211_packet_t;

//-------------------------------------------
// Utility Functions
//-------------------------------------------
float computeAverage(const int* buffer, int length) {
  long sum = 0;
  for (int i = 0; i < length; i++) {
    sum += buffer[i];
  }
  return (float)sum / (float)length;
}

float updateRssiRing(int* buf, int& index, int newRssi) {
  buf[index] = newRssi;
  index = (index + 1) % RSSI_SAMPLES;
  return computeAverage(buf, RSSI_SAMPLES);
}

float rssiToDistance(float rssi) {
  if (rssi < -120.0f) return -1.0f; // treat very low RSSI as invalid
  return powf(10.0f, ((RSSI_REF - rssi) / (10.0f * N_FACTOR)));
}

// Trilateration
bool trilateration(float ax, float ay, float r1,
                   float bx, float by, float r2,
                   float cx, float cy, float r3,
                   float* x, float* y) {
  float A = 2 * bx - 2 * ax;
  float B = 2 * by - 2 * ay;
  float C = r1*r1 - r2*r2 - ax*ax + bx*bx - ay*ay + by*by;
  float D = 2 * cx - 2 * ax;
  float E = 2 * cy - 2 * ay;
  float F = r1*r1 - r3*r3 - ax*ax + cx*cx - ay*ay + cy*cy;

  float denom = A*E - B*D;
  if (fabs(denom) < 1e-6) return false;

  *x = (C*E - B*F) / denom;
  *y = (A*F - C*D) / denom;
  return true;
}

//-------------------------------------------
// Beacon & Probe Response Parsing
//-------------------------------------------
String parseSSID(const uint8_t* payload, size_t len) {
  // Beacon/Probe Response frames typically:
  //   [ Timestamp(8), BeaconInterval(2), CapInfo(2) ] = 12 bytes
  // Then a series of Info Elements: [ID(1), Length(1), data(Length)]
  if (len < 12) return String();

  const uint8_t* iePtr = payload + 12; // skip fixed fields
  size_t offset = 12;

  while (offset + 2 <= len) {
    uint8_t ieID     = iePtr[0];
    uint8_t ieLength = iePtr[1];
    if (offset + 2 + ieLength > len) break; // safety check

    if (ieID == 0) {
      // SSID element
      if (ieLength == 0) {
        return String(); // hidden
      } else {
        return String((const char*)(iePtr + 2)).substring(0, ieLength);
      }
    }
    iePtr  += (2 + ieLength);
    offset += (2 + ieLength);
  }
  return String();
}

//-------------------------------------------
// Promiscuous Callback
//-------------------------------------------
static void wifiPromiscuousCallback(void* buf, wifi_promiscuous_pkt_type_t type) {
  // Only handle MGMT packets (beacon/probe resp)
  if (type != WIFI_PKT_MGMT) {
    return;
  }

  wifi_promiscuous_pkt_t* ppkt = (wifi_promiscuous_pkt_t*)buf;
  int rssi = ppkt->rx_ctrl.rssi;

  const wifi_ieee80211_packet_t* ipkt =
      (wifi_ieee80211_packet_t*)ppkt->payload;
  uint16_t fc = ipkt->hdr.frame_ctrl;
  uint8_t subtype = (fc >> 4) & 0xF; // bits 4..7 are the subtype

  bool isBeacon    = (subtype == 0x8);
  bool isProbeResp = (subtype == 0x5);

  if (!isBeacon && !isProbeResp) {
    return;
  }

  // The mgmt payload is after the 24-byte MAC header
  const uint8_t* payload = ipkt->payload;
  size_t payloadLen = ppkt->rx_ctrl.sig_len - sizeof(wifi_ieee80211_mac_hdr_t);
  if (payloadLen <= 0) {
    return;
  }

  // Parse out SSID
  String ssidFound = parseSSID(payload, payloadLen);

  // Debug Print
  // Serial.print("MGMT packet: ");
  // if (isBeacon)    Serial.print("Beacon, ");
  // if (isProbeResp) Serial.print("ProbeResp, ");
  // Serial.printf("rssi=%d, SSID=\"%s\"\n", rssi, ssidFound.c_str());

  // If SSID is empty, it might be hidden; skip
  if (ssidFound.length() == 0) {
    return;
  }

  // Compare with our anchors
  if (ssidFound.equals(anchorSsids[0])) {
    avgRssiA = updateRssiRing(rssiA_buf, rssiA_index, rssi);
    distA    = rssiToDistance(avgRssiA);
    if (sampleCountA < RSSI_SAMPLES) sampleCountA++;
    // Serial.printf(" Anchor A: RSSI=%.1f, dist=%.2f\n", avgRssiA, distA);
  } else if (ssidFound.equals(anchorSsids[1])) {
    avgRssiB = updateRssiRing(rssiB_buf, rssiB_index, rssi);
    distB    = rssiToDistance(avgRssiB);
    if (sampleCountB < RSSI_SAMPLES) sampleCountB++;
    // Serial.printf(" Anchor B: RSSI=%.1f, dist=%.2f\n", avgRssiB, distB);
  } else if (ssidFound.equals(anchorSsids[2])) {
    avgRssiC = updateRssiRing(rssiC_buf, rssiC_index, rssi);
    distC    = rssiToDistance(avgRssiC);
    if (sampleCountC < RSSI_SAMPLES) sampleCountC++;
    // Serial.printf(" Anchor C: RSSI=%.1f, dist=%.2f\n", avgRssiC, distC);
  }
  int completeSampleSets = min(sampleCountA, min(sampleCountB, sampleCountC));

  // Only log when a new multiple of 20 is reached
  if (completeSampleSets % 20 == 0 && completeSampleSets != lastMilestone && completeSampleSets <= RSSI_SAMPLES) {
    unsigned long timestamp = millis();  // Milliseconds since start
    float seconds = timestamp / 1000.0;
    Serial.printf("[SAMPLES] Complete Sample Sets Collected: %d / %d at %.2f s\n", 
                  completeSampleSets, RSSI_SAMPLES, seconds);
    lastMilestone = completeSampleSets;
  }
}

//-------------------------------------------
// Setup & Loop
//-------------------------------------------
void doTrilaterationAndDisplay();

void setup() {
  M5.begin();
  Serial.begin(115200);

  pinMode(10, OUTPUT);

  M5.Lcd.begin();
  M5.Lcd.setRotation(3);
  M5.Lcd.fillScreen(BLACK);
  M5.Lcd.setTextColor(WHITE);
  M5.Lcd.setTextSize(2);

  // --------------------------------------------------
  // 1) Initialize Wi-Fi driver with default config
  // --------------------------------------------------
  wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
  esp_err_t ret = esp_wifi_init(&cfg);
  if (ret != ESP_OK) {
    Serial.printf("esp_wifi_init failed! Error: %d\n", ret);
    return;
  }

  // (Optional) Increase logging verbosity to see errors
  esp_log_level_set("*", ESP_LOG_VERBOSE);  

  // --------------------------------------------------
  // 2) Set Wi-Fi mode to NULL (no station/AP)
  // --------------------------------------------------
  ret = esp_wifi_set_mode(WIFI_MODE_NULL);
  if (ret != ESP_OK) {
    Serial.printf("esp_wifi_set_mode failed! Error: %d\n", ret);
    return;
  }

  // --------------------------------------------------
  // 3) Start Wi-Fi driver
  // --------------------------------------------------
  ret = esp_wifi_start();
  if (ret != ESP_OK) {
    Serial.printf("esp_wifi_start failed! Error: %d\n", ret);
    return;
  }

  // --------------------------------------------------
  // 4) Now enable promiscuous mode
  // --------------------------------------------------
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(WIFI_CHANNEL, WIFI_SECOND_CHAN_NONE);

  // --------------------------------------------------
  // 5) Filter for MGMT frames only
  // --------------------------------------------------
  wifi_promiscuous_filter_t filt;
  filt.filter_mask = WIFI_PROMIS_FILTER_MASK_MGMT;
  esp_wifi_set_promiscuous_filter(&filt);

  // --------------------------------------------------
  // 6) Set the promiscuous callback
  // --------------------------------------------------
  esp_wifi_set_promiscuous_rx_cb(&wifiPromiscuousCallback);

  // Init ring buffers
  for (int i = 0; i < RSSI_SAMPLES; i++) {
    rssiA_buf[i] = -80;
    rssiB_buf[i] = -80;
    rssiC_buf[i] = -80;
  }

  M5.Lcd.println("Trolley Node");
  Serial.printf("Free heap: %d bytes\n", esp_get_free_heap_size());
}

void loop() {
  // Optional LED blink
  // unsigned long currentMillis = millis();
  // if ((currentMillis - lastBlinkMillis) > BLINK_PERIOD) {
  //   digitalWrite(10, ledState);
  //   ledState = !ledState;
  //   lastBlinkMillis = currentMillis;
  // }

  // Periodically do trilateration & update display
  static unsigned long lastUpdate = 0;
  if (millis() - lastUpdate > 500) {
    lastUpdate = millis();
    M5.Lcd.fillRect(0, 40, M5.Lcd.width(), 120, BLACK);
    M5.Lcd.setCursor(0, 40);
    doTrilaterationAndDisplay();
  }
}

// Helper function to return distance status as a string
String getDistanceStatus(float d) {
  if (d < 2.0) {
    return "Close";
  } else if (d < 5.0) {
    return "Medium";
  } else {
    return "Far";
  }
}

void doTrilaterationAndDisplay() {
  // Print distances for each anchor with status (Close, Medium, Far)
  M5.Lcd.println("Distances (m):");
  
  if (sampleCountA >= RSSI_SAMPLES) {
    M5.Lcd.printf("A: %.2f (%s)\n", distA, getDistanceStatus(distA).c_str());
  } else {
    M5.Lcd.println("A: collecting...");
  }

  if (sampleCountB >= RSSI_SAMPLES) {
    M5.Lcd.printf("B: %.2f (%s)\n", distB, getDistanceStatus(distB).c_str());
  } else {
    M5.Lcd.println("B: collecting...");
  }

  if (sampleCountC >= RSSI_SAMPLES) {
    M5.Lcd.printf("C: %.2f (%s)\n", distC, getDistanceStatus(distC).c_str());
  } else {
    M5.Lcd.println("C: collecting...");
  }

  // Perform trilateration and display position if all distances are valid
  if (distA > 0 && distB > 0 && distC > 0) {
    float localX, localY;
    if (trilateration(ax, ay, distA,
                      bx, by, distB,
                      cx, cy, distC,
                      &localX, &localY)) {
      xPos = localX;
      yPos = localY;
      if (xPos < X_MIN || xPos > X_MAX || yPos < Y_MIN || yPos > Y_MAX) {
        M5.Lcd.setTextColor(RED, BLACK);
        M5.Lcd.println("PLEASE RETURN CART");
        M5.Lcd.setTextColor(WHITE, BLACK);
        Serial.printf("[TRI] Out of bounds! X=%.2f Y=%.2f\n", xPos, yPos);
      } else {
        M5.Lcd.printf("Pos: (%.2f, %.2f)\n", xPos, yPos);
        // Serial.printf("[TRI] DistA=%.2f, DistB=%.2f, DistC=%.2f => X=%.2f, Y=%.2f\n",
                      // distA, distB, distC, xPos, yPos);
      }
    } else {
      M5.Lcd.println("Tri error");
      Serial.println("[TRI] Trilateration failed - check distances");
    }
  } else {
    M5.Lcd.setTextColor(RED, BLACK);
    M5.Lcd.println("PLEASE RETURN CART");
    M5.Lcd.setTextColor(WHITE, BLACK);
    Serial.println("[TRI] Not connected to all anchors - returning cart");
  }
}