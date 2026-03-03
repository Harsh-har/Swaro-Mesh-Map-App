# Android-nRF-Mesh-Library
[ ![Download](https://maven-badges.herokuapp.com/maven-central/no.nordicsemi.android/mesh/badge.svg?style=plastic) ](https://search.maven.org/artifact/no.nordicsemi.android/mesh)

About (Updated) This project is a customized and extended implementation of the Android-nRF-Mesh-Library, built on top of Nordic Semiconductor’s official Bluetooth Mesh stack. In addition to standard provisioning and mesh control, this application introduces advanced usability, automation, and device-level visibility features tailored for real-world deployments and large mesh networks. ✨ Custom Enhancements Added

Short & Long Command Support Implemented short commands for quick mesh operations (e.g. fast On/Off, Level changes). Added long commands for advanced configuration, diagnostics, and detailed node control. Improves response time and flexibility for different use cases.

Search View for Nodes & Devices Integrated SearchView to quickly find: Devices by Node Name MAC Address UUID Extremely useful for large mesh networks with many provisioned nodes.

MAC Address Visibility Extended mesh database and UI layers to: Display BLE MAC Address alongside Node Name and UUID Persist MAC address mapping after provisioning Helps in: no.nordicsemi.android.swaromesh.Device identification Debugging Field installation & maintenance

Auto Proxy Connect Implemented automatic Proxy Node connection logic: App automatically connects to the best available Proxy node Reduces manual intervention Improves reliability after app restart or disconnection Handles reconnection seamlessly during network changes.

Improved Network & Node Handling Enhanced handling of: Proxy Filter updates Node binding and re-binding Network refresh scenarios Better stability during large network operations.

UI & UX Improvements Cleaner node listing with: no.nordicsemi.android.swaromesh.Device Name MAC Address UUID Faster navigation and improved control screens. Optimized workflows for provisioning and configuration.

🔗 Base Compatibility (Unchanged Core) Bluetooth Mesh Profile 1.0.1 Mesh Model Specification 1.0.1 Mesh Configuration Database 1.0 Fully compatible with Nordic’s Android BLE Library

📌 Use Case This customized mesh application is suitable for: Smart Lighting Industrial Automation Building Management Systems Large-scale BLE Mesh deployments
# Swaro-Mesh-Map-App
# Swaro-Mesh-Map-App
