package com.example.vl53l0xbleapp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import java.util.UUID

// Example UUIDs - Replace with actual UUIDs for the VL53L0X device.
// Using Environmental Sensing Service as an example service that might contain a distance-like measurement.
// These global UUIDs will be removed as they will be passed dynamically or configured.
// val VL53L0X_SERVICE_UUID: UUID = UUID.fromString("0000181A-0000-1000-8000-00805f9b34fb") 
// val VL53L0X_DISTANCE_CHARACTERISTIC_UUID: UUID = UUID.fromString("00002A67-0000-1000-8000-00805f9b34fb") 

private const val TAG = "BleManager" // Logcat tag for this class

/**
 * Manages BLE scanning, connection, and GATT operations.
 * This class encapsulates the core Bluetooth Low Energy interactions.
 * @param context The application context.
 */
class BleManager(private val context: Context) {

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    // Holds the current GATT connection. Null if not connected.
    internal var bluetoothGatt: BluetoothGatt? = null // Made internal for MainActivity to check for connection status

    /**
     * Checks if Bluetooth Low Energy is supported on the device.
     * @return True if BLE is supported, false otherwise.
     */
    fun isBleSupported(): Boolean = bluetoothAdapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /**
     * Checks if all necessary permissions for BLE operations are granted.
     * Permissions vary based on Android SDK version.
     * For API 31+: BLUETOOTH_SCAN, BLUETOOTH_CONNECT.
     * For API 30 and below: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION.
     * ACCESS_FINE_LOCATION is always checked as it's often needed for reliable scan results.
     * @return True if all required permissions are granted, false otherwise.
     */
    fun hasRequiredPermissions(): Boolean {
        if (bluetoothAdapter == null) {
            Log.w(TAG, "BluetoothAdapter not available, cannot check permissions effectively.")
            return false
        }
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else { // API 30 and below
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        // Location permissions are generally needed for BLE scanning, especially on older APIs
        requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        // ACCESS_COARSE_LOCATION can also be checked if fine is not strictly necessary for your use case
        // requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)


        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Starts a BLE scan for nearby devices.
     * Requires BLUETOOTH_SCAN permission on API 31+.
     * Requires BLUETOOTH_ADMIN and location permissions on older APIs.
     * Callbacks for scan results or failures are handled by the provided [scanCallback].
     *
     * @param scanCallback The callback to handle scan results and failures.
     * @param deviceNameFilter Optional: A device name to filter scan results. Only devices matching this name will be reported.
     */
    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the caller using hasRequiredPermissions()
    fun startScan(scanCallback: ScanCallback, deviceNameFilter: String? = null) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized. Cannot start BLE scan.")
            scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_BLUETOOTH_DISABLED) // Or a more specific error
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot start scan.")
            scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_BLUETOOTH_DISABLED)
            return
        }
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Cannot start scan, required permissions missing.")
            scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) // More fitting error for permissions
            return
        }

        val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available. This might indicate a broader Bluetooth issue.")
            scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // Example: Create a filter for a specific service UUID if known
        // val filter = ScanFilter.Builder()
        //     .setServiceUuid(ParcelUuid(VL53L0X_SERVICE_UUID)) // Would need serviceUUID as param if filtering by it
        //     .build()
        // val filters = listOf(filter)
        val filters = mutableListOf<ScanFilter>()
        if (!deviceNameFilter.isNullOrEmpty()) {
            val nameFilter = ScanFilter.Builder().setDeviceName(deviceNameFilter).build()
            filters.add(nameFilter)
            Log.i(TAG, "Starting BLE scan with device name filter: $deviceNameFilter")
        } else {
            Log.i(TAG, "Starting BLE scan without device name filter...")
        }
        try {
            bluetoothLeScanner.startScan(filters, scanSettings, scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during startScan: ${e.message}")
            scanCallback.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
        }
    }

    /**
     * Stops an ongoing BLE scan.
     * Requires BLUETOOTH_SCAN permission on API 31+.
     * Requires BLUETOOTH_ADMIN on older APIs.
     *
     * @param scanCallback The same callback instance used to start the scan.
     */
    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the caller
    fun stopScan(scanCallback: ScanCallback) {
         if (!hasRequiredPermissions()) { // This check is more of a safeguard here.
            Log.w(TAG, "Attempting to stop scan without all required permissions. This might not be effective on all OS versions.")
            // Still attempt to stop, as the primary permission (e.g. BLUETOOTH_SCAN) might be granted.
        }
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available for stopping scan. Bluetooth might be off or adapter is null.")
            return
        }
        Log.i(TAG, "Stopping BLE scan...")
        try {
            bluetoothLeScanner.stopScan(scanCallback)
        } catch (e: Exception) {
             Log.e(TAG, "Exception during stopScan: ${e.message}")
        }
    }

    /**
     * Connects to a specified BLE device.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     *
     * @param device The [BluetoothDevice] to connect to.
     * @param gattCallback The [BluetoothGattCallback] to handle GATT events (connection changes, services discovered, etc.).
     */
    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the caller
    fun connect(device: BluetoothDevice, gattCallback: BluetoothGattCallback) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized. Cannot connect.")
            // Consider invoking a failure callback on gattCallback if it were to accept one for connection setup
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot connect.")
            return
        }
         // BLUETOOTH_CONNECT permission is checked by hasRequiredPermissions() before calling connect usually
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission missing. Cannot connect.")
            // Potentially trigger a specific callback in gattCallback
            return
        }

        Log.i(TAG, "Attempting to connect to device: ${device.address} (${device.name ?: "Unknown Name"})")
        // Ensure any previous GATT connection is closed before starting a new one.
        // This helps prevent "Too many open GATT connections" errors.
        bluetoothGatt?.close() 
        bluetoothGatt = null

        // connectGatt parameters: context, autoConnect (boolean), callback, transport (optional)
        // TRANSPORT_LE is preferred for BLE devices for explicit BLE connection.
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    /**
     * Disconnects from the currently connected GATT server and closes the connection.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     */
    @SuppressLint("MissingPermission") 
    fun disconnect() {
        if (bluetoothGatt == null) {
            Log.w(TAG, "BluetoothGatt not initialized or already disconnected, cannot disconnect.")
            return
        }
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            Log.e(TAG, "BluetoothLeScanner not available for stopping scan.")
            return
        }
        Log.i(TAG, "Stopping BLE scan...")
        try {
            bluetoothLeScanner.stopScan(scanCallback)
        } catch (e: Exception) {
             Log.e(TAG, "Exception during stopScan: ${e.message}")
        }
    }

    // --- Connection ---
    @SuppressLint("MissingPermission") // Permissions are checked before calling this
    fun connect(device: BluetoothDevice, gattCallback: BluetoothGattCallback) {
        if (bluetoothAdapter == null) {
            Log.e(TAG, "BluetoothAdapter not initialized. Cannot connect.")
            // Consider invoking a failure callback on gattCallback if it were to accept one for connection setup
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth is not enabled. Cannot connect.")
            return
        }
         // BLUETOOTH_CONNECT permission is checked by hasRequiredPermissions() before calling connect usually
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission missing. Cannot connect.")
            // Potentially trigger a specific callback in gattCallback
            return
        }

        Log.i(TAG, "Attempting to connect to device: ${device.address} (${device.name ?: "Unknown Name"})")
        // connectGatt parameters: context, autoConnect (boolean), callback, transport (optional)
        // TRANSPORT_LE is preferred for BLE devices
        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission") 
    fun disconnect() {
        if (bluetoothGatt == null) {
            Log.w(TAG, "BluetoothGatt not initialized, cannot disconnect.")
            return
        }
        // BLUETOOTH_CONNECT permission is needed for gatt.disconnect() and gatt.close() on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted, cannot disconnect GATT.")
            // Even if we can't call disconnect/close, nullify the gatt object to prevent further use
            bluetoothGatt = null // Nullify to prevent further operations
            return
        }
        Log.i(TAG, "Disconnecting from GATT server.")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close() // Close should be called after disconnect to release resources
        bluetoothGatt = null
        Log.i(TAG, "GATT server disconnected and closed.")
    }

    // --- GATT Operation Helper Functions (intended to be called from the client's BluetoothGattCallback) ---

    /**
     * Initiates service discovery on the connected GATT server.
     * This should be called from [BluetoothGattCallback.onConnectionStateChange] when `newState` is `BluetoothProfile.STATE_CONNECTED`.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     */
    @SuppressLint("MissingPermission")
    fun discoverServices() {
        // BLUETOOTH_CONNECT permission is needed for discoverServices on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted, cannot discover services.")
            return
        }

        if (bluetoothGatt != null) {
             Log.i(TAG, "Discovering services for device: ${bluetoothGatt?.device?.address}")
             val discoveryInitiated = bluetoothGatt?.discoverServices()
             if (discoveryInitiated == true) {
                 Log.i(TAG, "Service discovery process initiated successfully.")
             } else {
                 Log.e(TAG, "Failed to initiate service discovery process for ${bluetoothGatt?.device?.address}.")
             }
        } else {
            Log.e(TAG, "Cannot discover services, BluetoothGatt is null (not connected?).")
        }
    }

    /**
     * Initiates a read operation for a specified characteristic.
     * This should be called after services are discovered and the desired characteristic is found.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     *
     * @param serviceUuid The UUID of the service containing the target characteristic.
     * @param characteristicUuid The UUID of the characteristic to read.
     */
    @SuppressLint("MissingPermission")
    fun readDistanceCharacteristic(serviceUuid: UUID, characteristicUuid: UUID) {
        // BLUETOOTH_CONNECT permission is needed for readCharacteristic on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted, cannot read characteristic.")
            return
        }

        if (bluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt not initialized. Cannot read characteristic for device.")
            return
        }
        val service = bluetoothGatt?.getService(serviceUuid)
        if (service == null) {
            Log.e(TAG, "Service UUID $serviceUuid not found on device ${bluetoothGatt?.device?.address}.")
            return
        }
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic UUID $characteristicUuid not found in service ${service.uuid} on device ${bluetoothGatt?.device?.address}.")
            return
        }
        // Check if characteristic is readable
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            Log.e(TAG, "Characteristic ${characteristic.uuid} on device ${bluetoothGatt?.device?.address} is not readable.")
            return
        }

        if (!bluetoothGatt!!.readCharacteristic(characteristic)) {
            Log.e(TAG, "Failed to initiate read for characteristic ${characteristic.uuid} on device ${bluetoothGatt?.device?.address}")
        } else {
            Log.i(TAG, "Reading characteristic: ${characteristic.uuid} on device ${bluetoothGatt?.device?.address}")
        }
    }

    /**
     * Enables notifications or indications for a specified characteristic.
     * This involves setting the characteristic notification locally and writing to the
     * Client Characteristic Configuration Descriptor (CCCD) on the remote device.
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     *
     * @param serviceUuid The UUID of the service containing the target characteristic.
     * @param characteristicUuid The UUID of the characteristic for which to enable notifications/indications.
     */
    @SuppressLint("MissingPermission")
    fun enableDistanceNotifications(serviceUuid: UUID, characteristicUuid: UUID) {
        // BLUETOOTH_CONNECT permission is needed for these operations on API 31+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted, cannot enable notifications.")
            return
        }

        if (bluetoothGatt == null) {
            Log.e(TAG, "BluetoothGatt not initialized. Cannot enable notifications for device.")
            return
        }
        val service = bluetoothGatt?.getService(serviceUuid)
        if (service == null) {
            Log.e(TAG, "Service UUID $serviceUuid not found for notifications on device ${bluetoothGatt?.device?.address}.")
            return
        }
        val characteristic = service.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            Log.e(TAG, "Characteristic UUID $characteristicUuid not found for notifications in service ${service.uuid} on device ${bluetoothGatt?.device?.address}.")
            return
        }

        // Check if characteristic supports notifications or indications
        val properties = characteristic.properties
        val supportsNotify = (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0
        val supportsIndicate = (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0

        if (!supportsNotify && !supportsIndicate) {
            Log.e(TAG, "Characteristic ${characteristic.uuid} does not support notifications or indications.")
            return
        }

        // Enable notifications locally
        if (!bluetoothGatt!!.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to enable notification locally for characteristic ${characteristic.uuid} on device ${bluetoothGatt?.device?.address}")
            return
        }

        // Write to the CCCD (Client Characteristic Configuration Descriptor)
        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Standard CCCD UUID
        val descriptor = characteristic.getDescriptor(cccdUuid)
        if (descriptor == null) {
            Log.e(TAG, "CCCD UUID $cccdUuid not found for characteristic ${characteristic.uuid} on device ${bluetoothGatt?.device?.address}. Notifications might still work if setCharacteristicNotification was successful and device doesn't require CCCD write.")
            // Some devices might not require explicit CCCD write if characteristic is already set to notify
            // However, it's best practice. If it's missing, we might still get notifications if setCharacteristicNotification worked.
            return
        }
        
        val enableValue = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else { // Must support indicate if not notify
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }

        val success: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+ requires specific value to be passed
            bluetoothGatt!!.writeDescriptor(descriptor, enableValue)
        } else {
            // For older APIs, set value then call writeDescriptor()
            descriptor.value = enableValue
            bluetoothGatt!!.writeDescriptor(descriptor)
        }

        if (success) {
            Log.i(TAG, "Successfully initiated write to CCCD for ${characteristic.uuid} on device ${bluetoothGatt?.device?.address} with value: ${enableValue.toHexString()}")
        } else {
            Log.e(TAG, "Failed to initiate write to CCCD for ${characteristic.uuid} on device ${bluetoothGatt?.device?.address}")
        }
    }
}

// Example of how the GattCallback might be structured by the client (e.g., in MainActivity)
// This is NOT part of BleManager.kt but shows how onServicesDiscovered would be used.
/*
private val gattCallback = object : BluetoothGattCallback() {
    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
        val deviceAddress = gatt?.device?.address
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "Successfully connected to $deviceAddress")
                bleManager.discoverServices() // Call this from BleManager instance
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "Successfully disconnected from $deviceAddress")
                // gatt?.close() // Consider closing GATT here or in BleManager.disconnect()
            }
        } else {
            Log.w(TAG, "Error $status encountered for $deviceAddress! Disconnecting...")
            // gatt?.close()
        }
    }

    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "Services discovered for device: ${gatt?.device?.address}")
            gatt?.services?.forEach { service ->
                Log.i(TAG, "Service UUID: ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    Log.i(TAG, "  Characteristic UUID: ${characteristic.uuid}, Properties: ${characteristic.properties}")
                }
            }
            // After logging, you would typically identify your specific service/characteristic
            // and then call bleManager.readDistanceCharacteristic() or bleManager.enableDistanceNotifications()
        } else {
            Log.w(TAG, "Service discovery failed with status: $status")
        }
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray, // This is for Android Tiramisu (API 33) and above
        status: Int
    ) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Characteristic ${characteristic.uuid} read value: ${value.toHexString()}")
            // Process distance data
        } else {
            Log.e(TAG, "Characteristic read failed for ${characteristic.uuid}, status: $status")
        }
    }
    
    // Override for older Android versions if minSdk < 33
    // override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
    //    // Handle pre-Tiramisu characteristic read
    // }


    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray // This is for Android Tiramisu (API 33) and above
    ) {
        Log.i(TAG, "Characteristic ${characteristic.uuid} changed value: ${value.toHexString()}")
        // Process distance data from notification
    }

    // Override for older Android versions if minSdk < 33
    // override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
    //    // Handle pre-Tiramisu characteristic change
    // }
    
    override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.i(TAG, "Descriptor ${descriptor?.uuid} written successfully.")
        } else {
            Log.e(TAG, "Descriptor ${descriptor?.uuid} write failed with status $status")
        }
    }
}

// Helper extension function for byte array to hex string
fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte -> "%02X".format(byte) }
*/
