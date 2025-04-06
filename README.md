# SmartCart: IoT-Enabled Shopping Cart Management System

## Project Overview

SmartCart is an innovative solution that integrates IoT technology with shopping cart management to address operational challenges while enhancing the customer shopping experience. The system combines cart security, customer navigation, and data analytics in a single integrated platform.

## Team Members

- Ho Zhen Xian, Student ID: 2300933
- Tran Hoang Minh, Student ID: 2300922
- Benjamin Loh Qing Kai, Student ID: 2301019
- Tan De Wei, Student ID: 2301140
- Lee Wen Qiang, Student ID: 2300905

Department of Computing Science, Singapore Institute of Technology & University of Glasgow (UofG), Singapore

## Key Features

- Real-time cart tracking and positioning
- Multi-protocol support (BLE, Wi-Fi, LoRa)
- Indoor positioning with trilateration algorithms
- Mobile application for customer navigation
- Analytics for store layout optimization

## Repository Structure

```
.
├── m5/
│   ├── m5_ap_node/m5_ap_WiFi/
│   │   └── m5_ap.ino                # Fixed WiFi access point node
│   ├── m5_trolley/m5_trolley_WiFi/
│   │   └── m5_trolley.ino           # WiFi-enabled cart tracking module
│   ├── m5stick_beacon_BLE/
│   │   └── m5stick_beacon.ino       # BLE beacon implementation for fixed nodes
│   └── movable_node_BLE/
│       └── movable_node.ino         # BLE-based movable cart tracking module
├── LoRa/
│   ├── Receive_Interrupt/
│   │   ├── Receive_Interrupt.ino    # LoRa receiver with interrupt handling
│   │   ├── LoRaBoards.cpp
│   │   ├── LoRaBoards.h
│   │   └── utilities.h
│   └── Transmit_Interrupt/
│       ├── Transmit_Interrupt.ino   # LoRa transmitter with interrupt handling
│       ├── LoRaBoards.cpp
│       ├── LoRaBoards.h
│       └── utilities.h
├── IOT_WiFi_Application/            # Android WiFi application
├── mobile/                          # Android BLE application
└── README.md
```

## Firmware Implementation Details

### BLE Implementation

- **m5stick_beacon.ino**: Configures M5StickC Plus as a BLE beacon that broadcasts at -12 dBm power with manufacturer-specific data containing location identifiers
- **movable_node.ino**: Implements scanning functionality for BLE advertisements, RSSI filtering, and trilateration algorithms for position calculation

### WiFi Implementation

- **m5_ap.ino**: Sets up fixed nodes as WiFi access points with unique SSIDs for positioning reference
- **m5_trolley.ino**: Performs WiFi scanning, RSSI measurement, and position calculation using the path loss model and trilateration

### LoRa Implementation

- **Transmit_Interrupt.ino**: Configures LilyGo T3S3 ESP32 boards with SX1280 LoRa modules to broadcast on 2.4 GHz band
- **Receive_Interrupt.ino**: Handles long-range signal detection and distance estimation for geofencing applications

## Technologies Used

- **Hardware**: M5StickC Plus, LilyGo T3S3 ESP32
- **Protocols**: BLE, Wi-Fi, LoRa
- **Development**: Android Studio, Arduino IDE
- **Languages**: Kotlin, C++

## Positioning Algorithm

- RSSI filtering and averaging to reduce noise
- Log-distance path loss model for distance estimation
- Trilateration using matrix operations to solve position equations
- Least-squares method for optimal position estimation

## Performance Metrics

- **Wi-Fi**: 0.19-0.86m mean positioning error, ~2s latency
- **BLE**: 1.5-2.6m mean positioning error, ~4.7s latency
- **LoRa**: Effective for geofencing with ~5s latency

## Setup and Installation

1. Clone this repository
2. Upload the appropriate firmware (.ino files) to your devices:
   - m5stick_beacon.ino or m5_ap.ino to fixed nodes
   - movable_node.ino or m5_trolley.ino to cart-mounted devices
   - Transmit_Interrupt.ino and Receive_Interrupt.ino for extended range tracking
3. Open the Android project in Android Studio
4. Position fixed nodes according to the deployment methodology
5. Configure the mobile application with your environment parameters

## Future Improvements

- Refining BLE-Wi-Fi handovers
- Implementing advanced security measures
- Enhancing the customer interface with personalized recommendations
- Optimizing for multi-cart environments
