# VL53L0X BLE Distance Sensor App

This Android application allows users to connect to a VL53L0X Time-of-Flight distance sensor that is interfaced via a Bluetooth Low Energy (BLE) module. The app can display real-time distance readings and is configurable to work with various BLE module setups.

## Features

- Scan for nearby BLE devices.
- Filter scans by device name (optional).
- Connect to a selected BLE device.
- In-app configuration for:
    - BLE Service UUID
    - BLE Characteristic UUID (for distance data)
    - Target Device Name for scanning filter
    - Data format of the distance characteristic (e.g., 2-byte integer, 4-byte float, little/big endian)
- Display of received distance data.
- Status updates and error messages.

## Configuration

Before you can receive distance data, you MUST configure the app to match your VL53L0X BLE module's specifications:

1.  Launch the app.
2.  On the main screen, you will find input fields for:
    *   **Service UUID**: The GATT service UUID that exposes the distance characteristic.
    *   **Characteristic UUID**: The GATT characteristic UUID from which to read/notify the distance data.
    *   **Target Device Name (Optional)**: If you want the app to only show devices with a specific name during scanning, enter it here. Leave blank to see all devices.
    *   **Data Format**: Select the format in which your BLE module sends the distance data (e.g., "2-byte unsigned LE integer", "4-byte float LE").
3.  Tap the "Save BLE Settings" button to store your configuration. These settings will be remembered for future sessions.

## Building and Running

1.  Ensure you have Android Studio installed.
2.  Clone or download this project.
3.  Open the project in Android Studio.
4.  Let Android Studio sync Gradle dependencies.
5.  You can then build and run the app on a connected Android device or emulator (BLE functionality requires a physical device with Bluetooth).
    *   To build an APK: `Build > Build Bundle(s) / APK(s) > Build APK(s)`.
    *   Or run directly: `Run > Run 'app'`.

## Notes

- The app requires Bluetooth and Location permissions to function correctly.
- Ensure your Android device has Bluetooth enabled.
- For accurate distance readings, ensure the "Data Format" selected in the app settings precisely matches how your BLE module transmits the VL53L0X sensor data.
