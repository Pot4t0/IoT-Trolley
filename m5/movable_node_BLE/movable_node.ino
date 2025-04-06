#include <M5StickCPlus.h>
#include <BLEDevice.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>
#include <math.h>

#define SCAN_TIME 1  // Increased scan duration for stability


// Define beacons with MAC addresses and positions
struct Beacon {
    String address;
    float x;
    float y;
} beacons[] = {
    {"10:06:1c:0a:10:4e", 0, 0},        // Beacon 1
    {"10:06:1c:0a:22:62", 5, 0},       // Beacon 2
    {"10:06:1c:0a:14:72", 2.5, 5}      // Beacon 3

};

const int NUM_BEACONS = 3;

const float RSSI_AT_1M = -60.0;  // Consistent RSSI at 1m for all beacons
const float PATH_LOSS_EXPONENT = 2.6;
float distances[NUM_BEACONS] = {-1, -1, -1};

// RSSI Smoothing
#define RSSI_SAMPLES 5
static int rssiBuf[NUM_BEACONS][RSSI_SAMPLES];
static int rssiIndex[NUM_BEACONS] = {0, 0, 0};

// Updated Proximity Function with Correct Thresholds
String getProximity(float smoothedRssi) {
    if (smoothedRssi >= -70) return "Close";          // Stronger signal (above -70 dBm)
    else if (smoothedRssi >= -84) return "Medium";     // Moderate signal (between -70 and -80 dBm)
    else return "Far";                                 // Weaker signal (below -80 dBm)
}

// Update RSSI Ring Buffer without Kalman Filter
float updateRssiRing(int beaconIndex, int newRssi) {
    rssiBuf[beaconIndex][rssiIndex[beaconIndex]] = newRssi;
    rssiIndex[beaconIndex] = (rssiIndex[beaconIndex] + 1) % RSSI_SAMPLES;

    float avgRssi = 0;
    for (int i = 0; i < RSSI_SAMPLES; i++) {
        avgRssi += rssiBuf[beaconIndex][i];
    }
    avgRssi /= RSSI_SAMPLES;

    return avgRssi;  // Directly return the average RSSI without Kalman filtering
}

// Distance Calculation
float calculateDistance(float smoothedRssi) {
    return pow(10, (RSSI_AT_1M - smoothedRssi) / (10 * PATH_LOSS_EXPONENT));
}

// Trilateration Function for Position (x, y)
bool trilateration(float ax, float ay, float r1,
                   float bx, float by, float r2,
                   float cx, float cy, float r3,
                   float* x, float* y) {
    float A = 2 * bx - 2 * ax;
    float B = 2 * by - 2 * ay;
    float C = r1 * r1 - r2 * r2 - ax * ax + bx * bx - ay * ay + by * by;
    float D = 2 * cx - 2 * ax;
    float E = 2 * cy - 2 * ay;
    float F = r1 * r1 - r3 * r3 - ax * ax + cx * cx - ay * ay + cy * cy;

    float denom = A * E - B * D;
    if (fabs(denom) < 1e-6) {
        return false;
    }

    *x = (C * E - F * B) / denom;
    *y = (A * F - C * D) / denom;
    return true;
}

void printTimestamp() {
  unsigned long ms = millis();
  int hours = (ms / 3600000) % 24;
  int minutes = (ms / 60000) % 60;
  int seconds = (ms / 1000) % 60;
  int millisecs = ms % 1000;
  Serial.printf("%02d:%02d:%02d.%03d -> ", hours, minutes, seconds, millisecs);
}


void setup() {
    M5.begin();
    Serial.begin(115200);
    M5.Lcd.setRotation(3);
    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setTextSize(1.8);
    M5.Lcd.setTextColor(WHITE);
    BLEDevice::init("M5StickCPlus");
}

void loop() {
    M5.update();
    BLEScan *pBLEScan = BLEDevice::getScan();
    pBLEScan->setActiveScan(true);
    BLEScanResults scanResults = pBLEScan->start(SCAN_TIME);

    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setCursor(0, 0);
    M5.Lcd.setTextFont(2);
    M5.Lcd.setTextColor(WHITE);
    M5.Lcd.println("Beacon Scan...");

    int displayY = 20;
    int detectedBeacons = 0;  // Counter for detected beacons

    // Reset distances
    for (int i = 0; i < NUM_BEACONS; i++) {
        distances[i] = -1;
    }

    for (int i = 0; i < scanResults.getCount(); ++i) {
        BLEAdvertisedDevice device = scanResults.getDevice(i);
        String addr = device.getAddress().toString().c_str();
        int rssi = device.getRSSI();

        for (int j = 0; j < NUM_BEACONS; ++j) {
            if (addr == beacons[j].address) {
                printTimestamp();
                Serial.printf("MGMT packet: Beacon %d, rssi=%d, addr=\"%s\"\n", j + 1, rssi, addr.c_str());
                float smoothedRssi = updateRssiRing(j, rssi);
                float distance = calculateDistance(smoothedRssi);
                distances[j] = distance;
                detectedBeacons++;  // Increase detected beacon count
                String proximity = getProximity(smoothedRssi);

                M5.Lcd.setCursor(0, displayY);
                M5.Lcd.printf("B%d: %.2f dBm, %.2f m, %s", j + 1, smoothedRssi, distance, proximity.c_str());
                displayY += 15;

                Serial.printf("Beacon %d: Smoothed RSSI=%.2f dBm, Dist=%.2f m, %s\n", j + 1, smoothedRssi, distance, proximity.c_str());
            }
        }
    }

    // If no beacons are detected, keep showing "Beacon Scan..."
    if (detectedBeacons == 0) {
        M5.Lcd.fillScreen(BLACK);  // Clear screen
        M5.Lcd.setCursor(10, 50);
        M5.Lcd.setTextFont(4);  // Large font
        M5.Lcd.setTextColor(WHITE);
        M5.Lcd.println("Beacon Scan...");
        Serial.println("No beacons detected. Scanning...");
    }

    else {
        if (distances[0] != -1 && distances[1] != -1 && distances[2] != -1) {
            float x = 0, y = 0;
            if (trilateration(beacons[0].x, beacons[0].y, distances[0],
                              beacons[1].x, beacons[1].y, distances[1],
                              beacons[2].x, beacons[2].y, distances[2],
                              &x, &y)) {
                M5.Lcd.setCursor(0, displayY);
                M5.Lcd.setTextFont(2);
                M5.Lcd.setTextColor(WHITE);
                M5.Lcd.printf("Pos: X=%.2f m, Y=%.2f m", x, y);
                // Serial.printf("Position: X=%.2f m, Y=%.2f m\n", x, y);
            } else {
                M5.Lcd.setCursor(0, displayY);
                M5.Lcd.println("Trilateration failed!");
            }
        } else {
            M5.Lcd.setCursor(0, displayY);
            M5.Lcd.setTextColor(RED);   
            M5.Lcd.println("PLEASE RETURN CART.");
        }
    }

    delay(0);  
}


