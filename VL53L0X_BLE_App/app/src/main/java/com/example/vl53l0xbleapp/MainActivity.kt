package com.example.vl53l0xbleapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity 
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences 
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter 
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vl53l0xbleapp.databinding.ActivityMainBinding
import java.util.UUID 
import java.nio.ByteBuffer // For 4-byte float and int parsing
import java.nio.ByteOrder // For specifying Little Endian

private const val TAG = "MainActivity" // Logcat tag
private const val CONNECTION_TIMEOUT_MS = 20000L // 20 seconds; timeout for BLE connection attempts

/**
 * Helper extension function to convert a ByteArray to a Hex String for logging/display.
 * Example: [0x0A, 0x1F] -> "0A 1F"
 */
fun ByteArray.toHexString(): String = joinToString(separator = " ") { byte -> "%02X".format(byte) }

class MainActivity : AppCompatActivity() {

    // ViewBinding instance for accessing UI elements
    private lateinit var binding: ActivityMainBinding
    // Instance of BleManager for handling BLE operations
    private lateinit var bleManager: BleManager
    // Adapter for the RecyclerView displaying scanned BLE devices
    private lateinit var deviceListAdapter: DeviceListAdapter

    // List to hold discovered Bluetooth devices during a scan
    private var discoveredDevices = mutableListOf<BluetoothDevice>()
    // Flag to indicate if a BLE scan is currently active
    private var isScanning = false
    // Stores the device currently being connected to, to manage connection timeouts
    private var connectingDevice: BluetoothDevice? = null 

    // Handler to post UI updates from background BLE threads
    private val mainHandler = Handler(Looper.getMainLooper())
    // Runnable for implementing connection timeout logic
    private var connectionTimeoutRunnable: Runnable? = null
    
    // SharedPreferences instance for storing and retrieving user-defined BLE configurations
    private lateinit var sharedPreferences: SharedPreferences


    // ActivityResultLauncher for handling permission requests (AndroidX way)
    // This launcher is triggered when permissions need to be requested from the user.
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var allPermissionsGranted = true
            var shouldShowRationaleForAnyPermission = false 

            permissions.entries.forEach { entry ->
                if (!entry.value) { // If a permission was denied
                    allPermissionsGranted = false
                    Log.w(TAG, "Permission denied: ${entry.key}")
                    // Check if we should show a rationale for why the permission is needed.
                    // If shouldShowRequestPermissionRationale returns false, it could mean "Don't ask again" was selected by the user.
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, entry.key)) {
                        shouldShowRationaleForAnyPermission = true
                    }
                }
            }

            if (allPermissionsGranted) {
                Log.i(TAG, "All required BLE permissions granted by user.")
                tryStartScanAfterPermissions() // Proceed with BLE operations if permissions are now granted
            } else {
                Log.e(TAG, "One or more BLE permissions were denied by user.")
                if (!shouldShowRationaleForAnyPermission && permissions.any { !it.value && !ActivityCompat.shouldShowRequestPermissionRationale(this, it.key) } ) {
                    // If rationale should not be shown for at least one of the denied permissions, it implies "Don't ask again".
                    binding.connectionStatusTextView.text = "Status: Permissions permanently denied."
                    Toast.makeText(this, "Required permissions were permanently denied. Please enable them in App Settings to use BLE features.", Toast.LENGTH_LONG).show()
                    // Optionally: Intent to open app settings:
                    // Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)).also { startActivity(it) }
                } else {
                    // User denied, but did not select "Don't ask again" for all denied permissions, or some need rationale.
                    binding.connectionStatusTextView.text = "Status: Permissions denied."
                    Toast.makeText(this, "Bluetooth and Location permissions are essential for BLE functionality.", Toast.LENGTH_LONG).show()
                }
            }
        }
    
    // ActivityResultLauncher for handling the result of the Bluetooth enable intent
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.i(TAG, "Bluetooth successfully enabled by user.")
            // After Bluetooth is enabled, proceed to check permissions and then potentially start scan
            checkAndRequestPermissions() 
        } else {
            Log.w(TAG, "Bluetooth not enabled by user; user cancelled or error occurred.")
            binding.connectionStatusTextView.text = "Status: Bluetooth not enabled."
            Toast.makeText(this, "Bluetooth is required to scan for BLE devices.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Initialize BleManager
        bleManager = BleManager(applicationContext)

        // Setup UI components
        setupRecyclerView()
        setupScanButton()
        setupSettingsUI() // Initializes the settings UI, loads saved preferences, and sets up save button listener

        // Check for BLE support on the device
        if (!bleManager.isBleSupported()) {
            Toast.makeText(this, "Bluetooth Low Energy is not supported on this device.", Toast.LENGTH_LONG).show()
            binding.scanButton.isEnabled = false
            binding.connectionStatusTextView.text = "Status: BLE Not Supported"
            // Disable settings UI if BLE is not supported
            binding.settingServiceUuidEditText.isEnabled = false
            binding.settingCharacteristicUuidEditText.isEnabled = false
            binding.settingDeviceNameEditText.isEnabled = false
            binding.settingDataFormatSpinner.isEnabled = false
            binding.settingSaveButton.isEnabled = false
            return
        }
    }
    
    /**
     * Sets up the settings UI elements:
     * - Populates the data format Spinner with options from `arrays.xml`.
     * - Loads previously saved settings (or defaults) from SharedPreferences and populates the UI fields.
     * - Sets up the OnClickListener for the "Save Settings" button.
     */
    private fun setupSettingsUI() {
        // Populate the data format Spinner with options from arrays.xml
        ArrayAdapter.createFromResource(
            this,
            R.array.data_format_options, // Defined in res/values/arrays.xml
            android.R.layout.simple_spinner_item // Standard layout for the selected item
        ).also { adapter ->
            // Specify the layout to use when the list of choices appears
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            // Apply the adapter to the spinner
            binding.settingDataFormatSpinner.adapter = adapter
        }

        // Load any saved settings and update the UI fields accordingly
        loadSettingsAndPopulateUI()

        // Set up the listener for the "Save Settings" button
        binding.settingSaveButton.setOnClickListener {
            saveSettings()
        }
    }

    /**
     * Saves the current values from the settings UI fields (Service UUID, Characteristic UUID,
     * target device name, and selected data format index) to SharedPreferences.
     */
    private fun saveSettings() {
        val editor = sharedPreferences.edit()
        // Save each setting value, trimming whitespace from EditText fields
        editor.putString(KEY_SERVICE_UUID, binding.settingServiceUuidEditText.text.toString().trim())
        editor.putString(KEY_CHARACTERISTIC_UUID, binding.settingCharacteristicUuidEditText.text.toString().trim())
        editor.putString(KEY_DEVICE_NAME, binding.settingDeviceNameEditText.text.toString().trim())
        editor.putInt(KEY_DATA_FORMAT_INDEX, binding.settingDataFormatSpinner.selectedItemPosition)
        
        editor.apply() // Apply changes asynchronously; use commit() for synchronous save if needed
        
        Toast.makeText(this, "Settings saved successfully.", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "BLE configuration settings saved to SharedPreferences.")
        // Note: If UUIDs or device name filters are changed, subsequent BLE operations (like new scans or connections)
        // will use these updated values as they are read from SharedPreferences when needed.
    }

    /**
     * Loads BLE configuration settings from SharedPreferences and populates the corresponding UI fields.
     * Uses default values defined in the companion object if no settings were previously saved.
     */
    private fun loadSettingsAndPopulateUI() {
        binding.settingServiceUuidEditText.setText(sharedPreferences.getString(KEY_SERVICE_UUID, DEFAULT_SERVICE_UUID))
        binding.settingCharacteristicUuidEditText.setText(sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID))
        binding.settingDeviceNameEditText.setText(sharedPreferences.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME))
        binding.settingDataFormatSpinner.setSelection(sharedPreferences.getInt(KEY_DATA_FORMAT_INDEX, DEFAULT_DATA_FORMAT_INDEX))
        Log.i(TAG, "BLE configuration settings loaded from SharedPreferences and UI populated.")
    }


    override fun onResume() {
        super.onResume()
        // Check BLE support and if Bluetooth is enabled every time the activity resumes
        if (bleManager.isBleSupported()) {
            if (bluetoothAdapter?.isEnabled == false) { // Check if Bluetooth is off
                 binding.connectionStatusTextView.text = "Status: Bluetooth is disabled."
                 // Prompt user to enable Bluetooth
                 val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                 enableBluetoothLauncher.launch(enableBtIntent)
            } else { // Bluetooth is on
                // Proceed to check permissions
                checkAndRequestPermissions()
                // Update UI based on current connection status if returning to app
                 if (bleManager.bluetoothGatt == null && !isScanning) { // If not connected and not scanning
                    binding.connectionStatusTextView.text = "Status: Ready"
                 } else if (isScanning) { // If scanning
                    binding.connectionStatusTextView.text = "Status: Scanning..."
                 }
                 // If connected, the gattCallback would have already set the appropriate status text.
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Stop scanning if it's active when the activity is paused
        if (isScanning) {
            stopBleScan() 
        }
        // Consider disconnecting from the device here if desired, to save battery.
        // For this application, we might want to keep the connection alive if the user temporarily switches apps.
        // However, for simplicity in this example, one might call bleManager.disconnect() here.
        // mainHandler.removeCallbacksAndMessages(null) // Optionally remove all pending handler messages
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up resources: remove handler callbacks and disconnect from any BLE device.
        mainHandler.removeCallbacksAndMessages(null) 
        bleManager.disconnect() 
    }


    /**
     * Initializes the RecyclerView for displaying scanned BLE devices.
     * Sets up the DeviceListAdapter and a LinearLayoutManager.
     * Defines the click action for each item (attempt to connect to the device).
     */
    private fun setupRecyclerView() {
        deviceListAdapter = DeviceListAdapter(mutableListOf()) { device ->
            connectToDevice(device) // Set item click listener to connect to the selected device
        }
        binding.devicesRecyclerView.apply {
            adapter = deviceListAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    /**
     * Sets up the OnClickListener for the scan button.
     * Toggles between starting and stopping BLE scans.
     */
    private fun setupScanButton() {
        binding.scanButton.setOnClickListener {
            if (isScanning) {
                stopBleScan()
            } else {
                // Before starting a scan, ensure Bluetooth is enabled and permissions are granted.
                tryStartScanAfterPermissions()
            }
        }
    }
    
    /**
     * Centralized function to attempt starting a scan.
     * It first checks if Bluetooth is enabled, then if permissions are granted.
     * If either is not met, it triggers the appropriate user prompt or request.
     */
    private fun tryStartScanAfterPermissions() {
        if (bluetoothAdapter?.isEnabled == true) { // Check if Bluetooth is enabled
            if (checkAndRequestPermissions()) { // Check (and request if needed) permissions
                startBleScan() // If all checks pass, start the scan
            }
            // If checkAndRequestPermissions() returns false, it means permissions are being requested.
            // The actual scan will start from the requestPermissionsLauncher callback if granted.
        } else { // Bluetooth is not enabled
            binding.connectionStatusTextView.text = "Status: Enable Bluetooth to scan."
            Toast.makeText(this, "Please enable Bluetooth.", Toast.LENGTH_SHORT).show()
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableBtIntent) // Prompt user to enable Bluetooth
        }
    }


    /**
     * Checks for required BLE permissions (SCAN, CONNECT, and FINE_LOCATION based on SDK version).
     * If permissions are not granted, it launches the permission request flow.
     * @return True if all permissions are already granted, false if a request is launched.
     */
    private fun checkAndRequestPermissions(): Boolean {
        val requiredPermissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12 (API 31) and above
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else { // Android 11 (API 30) and below
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            // ACCESS_FINE_LOCATION is critical for scan results on API < 31
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION) 
        }
        // Ensure ACCESS_FINE_LOCATION is included for pre-API 31 if not already added
        // (though the above logic already covers it for older versions).
        // For API 31+, it's not strictly needed for BLE scan if BLUETOOTH_SCAN
        // is granted with `usesPermissionFlags="neverForLocation"`, but we don't use that flag here.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !requiredPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION)) {
             requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // Filter out permissions that are already granted
        val permissionsToRequest = requiredPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            Log.i(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            binding.connectionStatusTextView.text = "Status: Requesting permissions..."
            requestPermissionsLauncher.launch(permissionsToRequest) // Launch permission request
            return false // Permissions are not yet granted; request is in progress
        }
        Log.i(TAG, "All required BLE permissions are already granted.")
        return true // All necessary permissions are already granted
    }

    /**
     * Starts the BLE scan. Assumes permissions and Bluetooth enabled state are verified.
     * Clears previous scan results and updates UI to reflect scanning state.
     */
    @SuppressLint("MissingPermission") // Permissions are checked by checkAndRequestPermissions before this is called
    private fun startBleScan() {
        if (!bleManager.hasRequiredPermissions()) { // Defensive check
             Log.e(TAG, "Scan started without required permissions. This should not happen.")
             Toast.makeText(this, "Permissions error, cannot scan.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Load current settings to apply filters (device name) for the scan
        val targetDeviceNameFilter = sharedPreferences.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME)?.trim()
        
        if (!targetDeviceNameFilter.isNullOrEmpty()) {
            Log.i(TAG, "Scanning with target device name filter: '$targetDeviceNameFilter'")
        }

        discoveredDevices.clear() // Clear list of previously discovered devices
        deviceListAdapter.clearDevices() // Clear adapter data
        
        mainHandler.post { // Ensure UI updates are on main thread
            binding.scanButton.text = "Stop Scan" // Update button text
            binding.connectionStatusTextView.text = "Status: Scanning..."
            binding.distanceTextView.text = "Distance: -- mm" // Reset distance display
        }
        
        bleManager.startScan(bleScanCallback, targetDeviceNameFilter) // Pass the name filter to BleManager
        isScanning = true
        Log.i(TAG, "BLE scan started. Filter: '${targetDeviceNameFilter ?: "None"}'")
    }

    /**
     * Stops the ongoing BLE scan and updates the UI.
     */
    @SuppressLint("MissingPermission") // Permissions should be granted if scan was started
    private fun stopBleScan() {
        bleManager.stopScan(bleScanCallback)
        isScanning = false
        mainHandler.post {
            binding.scanButton.text = "Start Scan"
            if (binding.connectionStatusTextView.text == "Status: Scanning...") { // Avoid overwriting other statuses
                 binding.connectionStatusTextView.text = if (discoveredDevices.isEmpty()) "Status: No devices found" else "Status: Scan stopped"
            }
        }
        Log.i(TAG, "BLE scan stopped.")
    }

    /**
     * Callback for BLE scan results.
     * Handles individual scan results, batch results, and scan failures.
     */
    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT for device.name on API 31+
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            // BLUETOOTH_CONNECT permission is needed for device.name on API 31+
            // If permission is not granted, name will be null or empty.
            val deviceNameString = if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                device.name ?: "N/A"
            } else {
                "N/A (No Connect Perm)" // Indicate name cannot be fetched due to missing permission
            }
            
            // Device name filtering is now handled by BleManager's startScan method using ScanFilter
            // However, we can double-check or log here if needed.
            // val targetDeviceName = sharedPreferences.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME)?.trim()
            // if (!targetDeviceName.isNullOrEmpty() && 
            //     (deviceNameString == "N/A (No Connect Perm)" || !deviceNameString.equals(targetDeviceName, ignoreCase = true))) {
            //     return // Skip this device if name filter is active and doesn't match
            // }

            Log.i(TAG, "Scan result: ${device.address} - Name: $deviceNameString")
            // Add device to list and update RecyclerView if it's new
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                mainHandler.post { deviceListAdapter.addDevice(device) }
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            results?.forEach { result -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) }
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            val errorText = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Scan failed: App registration error"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Scan failed: Internal system error"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Scan failed: Feature not supported on this device"
                // These require API 26+
                // ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "Scan failed: Out of hardware resources" 
                // ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scan failed: Scanning too frequently" 
                else -> "Scan failed: Unknown error code ($errorCode)"
            }
            Log.e(TAG, "BLE Scan Failed: $errorText")
            mainHandler.post {
                binding.connectionStatusTextView.text = "Status: $errorText"
                binding.scanButton.text = "Start Scan"
                isScanning = false
            }
        }
    }
    
    /**
     * Initiates a connection timeout when attempting to connect to a BLE device.
     * If the connection is not established within [CONNECTION_TIMEOUT_MS], it triggers a disconnect.
     * @param device The device for which the connection timeout is being started.
     */
    private fun startConnectionTimeout(device: BluetoothDevice) {
        connectionTimeoutRunnable = Runnable {
            Log.w(TAG, "Connection to ${device.address} timed out after ${CONNECTION_TIMEOUT_MS}ms.")
            bleManager.disconnect() // This will trigger onConnectionStateChange with a disconnect state
            mainHandler.post {
                val deviceName = if (bleManager.hasRequiredPermissions()) device.name ?: device.address else device.address
                binding.connectionStatusTextView.text = "Status: Connection to $deviceName timed out"
            }
            connectingDevice = null // Clear the device we were attempting to connect to
        }
        mainHandler.postDelayed(connectionTimeoutRunnable!!, CONNECTION_TIMEOUT_MS)
    }

    /**
     * Cancels any pending connection timeout runnable.
     * This should be called when a connection attempt succeeds or fails before the timeout.
     */
    private fun cancelConnectionTimeout() {
        connectionTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionTimeoutRunnable = null
    }


    /**
     * Attempts to connect to the selected BLE device.
     * Stops scanning if active, updates UI, and initiates connection via [BleManager].
     * @param device The [BluetoothDevice] to connect to.
     */
    @SuppressLint("MissingPermission") // Permissions are checked by checkAndRequestPermissions before scan, and connect perm is re-checked here
    private fun connectToDevice(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && 
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
             Toast.makeText(this, "BLUETOOTH_CONNECT permission is required to connect to a device.", Toast.LENGTH_SHORT).show()
             // Optionally, re-trigger permission request or guide user.
            return
        }

        if (isScanning) {
            stopBleScan() // Stop scanning before attempting to connect
        }
        connectingDevice = device // Track the device we are currently trying to connect to
        val deviceName = if (bleManager.hasRequiredPermissions()) device.name ?: device.address else device.address
        mainHandler.post {
            binding.connectionStatusTextView.text = "Status: Connecting to $deviceName..."
            binding.distanceTextView.text = "Distance: -- mm" // Reset distance display
        }
        Log.i(TAG, "Attempting to connect to device: ${device.address}")
        startConnectionTimeout(device) // Start a timeout for the connection attempt
        bleManager.connect(device, gattCallback) // Initiate connection
    }

    /**
     * GATT callback for handling BLE events such as connection state changes,
     * service discovery, characteristic reads/changes, and descriptor writes.
     */
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT for device.name
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            val deviceName = if (bleManager.hasRequiredPermissions()) gatt.device.name ?: deviceAddress else deviceAddress

            // If this callback is for the device we were attempting to connect to, cancel the timeout
            if (deviceAddress == connectingDevice?.address) { 
                 cancelConnectionTimeout() 
                 // connectingDevice = null; // Clear only if connection failed or fully established.
                                         // If successful, it's cleared after service discovery or if discovery fails.
            }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Successfully connected to $deviceName ($deviceAddress)")
                    // connectedGatt reference is managed by BleManager
                    mainHandler.post {
                        binding.connectionStatusTextView.text = "Status: Connected to $deviceName"
                        binding.distanceTextView.text = "Distance: Discovering services..."
                    }
                    // Initiate service discovery
                    bleManager.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from $deviceName ($deviceAddress)")
                    connectingDevice = null // Clear connecting device on definitive disconnect
                    mainHandler.post {
                        binding.connectionStatusTextView.text = "Status: Disconnected from $deviceName"
                        binding.distanceTextView.text = "Distance: -- mm"
                    }
                    bleManager.disconnect() // Ensure BleManager cleans up its gatt object
                }
            } else { // An error occurred with the connection state change
                Log.w(TAG, "Connection state change error for $deviceAddress. Status: $status, NewState: $newState")
                connectingDevice = null // Clear connecting device on error
                 mainHandler.post {
                    binding.connectionStatusTextView.text = "Status: Connection Failed (Error: $status)"
                    binding.distanceTextView.text = "Distance: -- mm"
                }
                bleManager.disconnect() // Ensure cleanup
            }
        }

        @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT for gatt operations
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered successfully for device: $deviceAddress")
                var serviceFound = false
                var charFound = false
                
                // Retrieve Service and Characteristic UUIDs from SharedPreferences
                val serviceUuidString = sharedPreferences.getString(KEY_SERVICE_UUID, DEFAULT_SERVICE_UUID)
                val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
                val targetServiceUuid: UUID?
                val targetCharUuid: UUID?

                try {
                    targetServiceUuid = UUID.fromString(serviceUuidString)
                    targetCharUuid = UUID.fromString(charUuidString)
                    Log.i(TAG, "Using Service UUID: $targetServiceUuid, Characteristic UUID: $targetCharUuid from settings for service discovery.")
                } catch (e: IllegalArgumentException) {
                    Log.e(TAG, "Invalid UUID format in SharedPreferences: Service='$serviceUuidString', Char='$charUuidString'", e)
                    mainHandler.post {
                        binding.connectionStatusTextView.text = "Status: Invalid UUID in settings. Check configuration."
                        binding.distanceTextView.text = "Distance: -- mm"
                    }
                    bleManager.disconnect() // Disconnect due to invalid configuration
                    connectingDevice = null // Clear connecting device tracker
                    return
                }

                if (targetServiceUuid == null || targetCharUuid == null) { 
                    Log.e(TAG, "Parsed UUIDs resulted in null (should have been caught by try-catch), cannot proceed with service discovery.")
                    mainHandler.post { binding.connectionStatusTextView.text = "Status: Critical UUID error in settings." }
                    bleManager.disconnect()
                    connectingDevice = null
                    return
                }

                // Iterate through discovered services to find the configured one
                gatt.services?.forEach serviceLoop@{ service -> 
                    if (service.uuid == targetServiceUuid) {
                        serviceFound = true
                        Log.i(TAG, "Target Service found: ${service.uuid}")
                        // Iterate through characteristics of the found service
                        service.characteristics.forEach charLoop@{ characteristic ->
                            if (characteristic.uuid == targetCharUuid) {
                                charFound = true
                                Log.i(TAG, "Target Characteristic found: ${characteristic.uuid}. Attempting to enable notifications.")
                                // Request BleManager to enable notifications for this characteristic, passing the dynamic UUIDs
                                bleManager.enableDistanceNotifications(targetServiceUuid, targetCharUuid) 
                                mainHandler.post { binding.distanceTextView.text = "Distance: Enabling notifications..." }
                                connectingDevice = null // Successfully initiated post-connection steps
                                return@serviceLoop // Exit services loop once characteristic is found and processed
                            }
                        }
                    }
                }

                // Update UI based on whether the service and characteristic were found
                if (!serviceFound) {
                     Log.w(TAG, "Target Service $targetServiceUuid not found on device: $deviceAddress. Check configured Service UUID.")
                     mainHandler.post { binding.connectionStatusTextView.text = "Status: Target Service not found. Check UUID." }
                     bleManager.disconnect() // Disconnect if essential service/char not found
                     connectingDevice = null
                } else if (!charFound) {
                     Log.w(TAG, "Target Characteristic $targetCharUuid not found in service $targetServiceUuid on device: $deviceAddress. Check configured Characteristic UUID.")
                     mainHandler.post { binding.connectionStatusTextView.text = "Status: Target Characteristic not found. Check UUID." }
                     bleManager.disconnect()
                     connectingDevice = null
                }
            } else { // Service discovery failed
                Log.w(TAG, "Service discovery failed for $deviceAddress with status: $status")
                mainHandler.post {
                     binding.connectionStatusTextView.text = "Status: Service discovery failed (Error: $status)"
                     binding.distanceTextView.text = "Distance: -- mm"
                }
                bleManager.disconnect()
                connectingDevice = null
            }
        }

        // Callback for characteristic read operations (API 33+)
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
            var targetCharUuid: UUID? = null
            try { targetCharUuid = UUID.fromString(charUuidString) } catch (e: Exception) { Log.e(TAG, "Invalid Char UUID for read"); return }

            if (characteristic.uuid == targetCharUuid) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val distance = parseDistance(value)
                    Log.i(TAG, "Distance Characteristic Read (API 33+): $distance mm, Raw: ${value.toHexString()}")
                    mainHandler.post {
                        if (distance == -2) binding.distanceTextView.text = "Distance: Format=String"
                        else if (distance == -3) binding.distanceTextView.text = "Distance: Format=Hex"
                        else if (distance != -1) binding.distanceTextView.text = "Distance: $distance mm"
                        else binding.distanceTextView.text = "Distance: Invalid data"
                    }
                } else {
                    Log.e(TAG, "Characteristic read failed for ${characteristic.uuid}, status: $status")
                    mainHandler.post { binding.distanceTextView.text = "Distance: Read failed (S:$status)" }
                }
            }
        }
        
        // Deprecated callback for characteristic read operations (for API < 33)
        @Deprecated("Used for Android SDK <= 32")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
                var targetCharUuid: UUID? = null
                try { targetCharUuid = UUID.fromString(charUuidString) } catch (e: Exception) { Log.e(TAG, "Invalid Char UUID for legacy read"); return }

                if (characteristic.uuid == targetCharUuid) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val distance = parseDistance(characteristic.value)
                        Log.i(TAG, "Distance Characteristic Read (Legacy): $distance mm, Raw: ${characteristic.value?.toHexString() ?: "null"}")
                        mainHandler.post {
                             if (distance == -2) binding.distanceTextView.text = "Distance: Format=String"
                             else if (distance == -3) binding.distanceTextView.text = "Distance: Format=Hex"
                             else if (distance != -1) binding.distanceTextView.text = "Distance: $distance mm"
                             else binding.distanceTextView.text = "Distance: Invalid data"
                        }
                    } else {
                        Log.e(TAG, "Characteristic read failed (Legacy) for ${characteristic.uuid}, status: $status")
                        mainHandler.post { binding.distanceTextView.text = "Distance: Read failed (S:$status)" }
                    }
                }
            }
        }

        // Callback for characteristic change notifications (API 33+)
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
            var targetCharUuid: UUID? = null
            try { targetCharUuid = UUID.fromString(charUuidString) } catch (e: Exception) { Log.e(TAG, "Invalid Char UUID for changed"); return }

            if (characteristic.uuid == targetCharUuid) {
                val distance = parseDistance(value)
                Log.i(TAG, "Distance Characteristic Changed (API 33+): $distance mm, Raw: ${value.toHexString()}")
                mainHandler.post {
                     if (distance == -2) binding.distanceTextView.text = "Distance: Format=String (N)"
                     else if (distance == -3) binding.distanceTextView.text = "Distance: Format=Hex (N)"
                     else if (distance != -1) binding.distanceTextView.text = "Distance: $distance mm (N)"
                     else binding.distanceTextView.text = "Distance: Invalid data (N)"
                }
            }
        }

        // Deprecated callback for characteristic change notifications (for API < 33)
        @Deprecated("Used for Android SDK <= 32")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
             if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
                var targetCharUuid: UUID? = null
                try { targetCharUuid = UUID.fromString(charUuidString) } catch (e: Exception) { Log.e(TAG, "Invalid Char UUID for legacy changed"); return }

                if (characteristic.uuid == targetCharUuid) {
                    val distance = parseDistance(characteristic.value)
                    Log.i(TAG, "Distance Characteristic Changed (Legacy): $distance mm, Raw: ${characteristic.value?.toHexString() ?: "null"}")
                    mainHandler.post {
                        if (distance == -2) binding.distanceTextView.text = "Distance: Format=String (N)"
                        else if (distance == -3) binding.distanceTextView.text = "Distance: Format=Hex (N)"
                        else if (distance != -1) binding.distanceTextView.text = "Distance: $distance mm (N)"
                        else binding.distanceTextView.text = "Distance: Invalid data (N)"
                    }
                }
            }
        }
        
        // Callback for descriptor write operations (e.g., enabling notifications via CCCD)
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val charUuidString = sharedPreferences.getString(KEY_CHARACTERISTIC_UUID, DEFAULT_CHARACTERISTIC_UUID)
            var targetCharUuid: UUID? = null
            try { targetCharUuid = UUID.fromString(charUuidString) } catch (e: Exception) { Log.e(TAG, "Invalid Char UUID for descriptor write"); /* Fallthrough or return */ }

            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD descriptor written successfully for characteristic ${descriptor?.characteristic?.uuid}")
                 if(descriptor?.characteristic?.uuid == targetCharUuid) { // Check if it's for the correct characteristic
                     mainHandler.post { binding.distanceTextView.text = "Distance: Waiting for data..." }
                     // Optionally, can initiate a read here if the device doesn't send a notification immediately
                     // bleManager.readDistanceCharacteristic(UUID.fromString(sharedPreferences.getString(KEY_SERVICE_UUID, DEFAULT_SERVICE_UUID)!!), targetCharUuid!!)
                 }
            } else {
                Log.e(TAG, "CCCD descriptor write failed for ${descriptor?.characteristic?.uuid}, status: $status")
                mainHandler.post { binding.distanceTextView.text = "Distance: Notification setup failed (S:$status)" }
            }
        }
    }

    /**
     * Parses distance data from a ByteArray based on the format selected in SharedPreferences.
     * The function handles various integer and float formats, assuming little-endian byte order.
     * It also includes placeholders for string formats (UTF-8, Hex) which return error codes
     * as direct conversion to an Int distance is not applicable.
     *
     * @param data The ByteArray received from the BLE characteristic, expected to contain the distance value.
     * @return The parsed distance as an Int. 
     *         Returns -1 for general parsing errors or if data is null/too short.
     *         Returns -2 if the selected format is UTF-8 String.
     *         Returns -3 if the selected format is Hex String.
     */
    private fun parseDistance(data: ByteArray?): Int {
        if (data == null) {
            Log.w(TAG, "Received null data array for distance parsing.")
            return -1 // Error code for null data
        }
        
        // Retrieve the selected data format index from SharedPreferences.
        // DEFAULT_DATA_FORMAT_INDEX (0) corresponds to "2-byte unsigned LE integer".
        val formatIndex = sharedPreferences.getInt(KEY_DATA_FORMAT_INDEX, DEFAULT_DATA_FORMAT_INDEX)
        Log.d(TAG, "Parsing data with format index $formatIndex: ${data.toHexString()}")
        
        try {
            return when (formatIndex) {
                0 -> { // 2-byte unsigned LE integer
                    if (data.size < 2) throw IndexOutOfBoundsException("Data too short for 2-byte unsigned LE integer. Expected 2, got ${data.size}.")
                    // LSB first: (data[1] << 8) | data[0]
                    (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
                }
                1 -> { // 2-byte signed LE integer
                    if (data.size < 2) throw IndexOutOfBoundsException("Data too short for 2-byte signed LE integer. Expected 2, got ${data.size}.")
                    // LSB first
                    var value = (data[1].toInt() and 0xFF shl 8) or (data[0].toInt() and 0xFF)
                    // Sign extension if the MSB (of the 16 bits) is set
                    if (value and 0x8000 != 0) value = value or 0xFFFF0000.toInt() 
                    value
                }
                2 -> { // 4-byte unsigned LE integer - returned as Long then toInt(), may truncate if value > Int.MAX_VALUE
                    if (data.size < 4) throw IndexOutOfBoundsException("Data too short for 4-byte unsigned LE integer. Expected 4, got ${data.size}.")
                    // LSB first
                    val value = ((data[3].toLong() and 0xFF) shl 24) or
                           ((data[2].toLong() and 0xFF) shl 16) or
                           ((data[1].toLong() and 0xFF) shl 8)  or
                           (data[0].toLong() and 0xFF)
                    if (value > Int.MAX_VALUE.toLong()) {
                        Log.w(TAG, "4-byte unsigned value $value exceeds Int.MAX_VALUE, truncation will occur.")
                    }
                    value.toInt() 
                }
                3 -> { // 4-byte signed LE integer
                     if (data.size < 4) throw IndexOutOfBoundsException("Data too short for 4-byte signed LE integer. Expected 4, got ${data.size}.")
                    // LSB first, using ByteBuffer for simplicity and correctness with endianness
                    java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                }
                4 -> { // 4-byte LE float
                    if (data.size < 4) throw IndexOutOfBoundsException("Data too short for 4-byte LE float. Expected 4, got ${data.size}.")
                    // LSB first, using ByteBuffer
                    java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.toInt() // Converting float to Int for display
                }
                5 -> { // UTF-8 String
                    Log.i(TAG, "Data format selected: UTF-8 String. Raw: ${data.decodeToString()}")
                    // This format cannot be directly parsed into an Int for distance.
                    // Returning an error code; UI should handle this by showing "Invalid format" or the string itself.
                    return -2 // Special error code for "String format selected"
                }
                6 -> { // Hex String
                    Log.i(TAG, "Data format selected: Hex String. Raw: ${data.toHexString()}")
                     // Similar to UTF-8, this is not directly an Int.
                    return -3 // Special error code for "Hex format selected"
                }
                else -> {
                    Log.w(TAG, "Unknown data format index selected: $formatIndex. Cannot parse distance.")
                    return -1 // Error code for unknown format
                }
            }
        } catch (e: IndexOutOfBoundsException) {
            Log.e(TAG, "Error parsing distance data due to insufficient data length for selected format. Data: ${data.toHexString()}", e)
            return -1 // Error code for parsing failure
        } catch (e: Exception) { // Catch any other unexpected parsing related errors
            Log.e(TAG, "Generic error parsing distance data: ${data.toHexString()}", e)
            return -1 // General error code
        }
    }
    
    // Lazily initialize BluetoothAdapter to ensure Bluetooth service is available.
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1 // Request code for enabling Bluetooth
        
        // SharedPreferences Keys - used for storing and retrieving user-defined BLE configurations
        const val PREFS_NAME = "Vl53l0xAppPrefs" // Name of the SharedPreferences file
        const val KEY_SERVICE_UUID = "pref_service_uuid" // Key for storing the Service UUID string
        const val KEY_CHARACTERISTIC_UUID = "pref_characteristic_uuid" // Key for storing the Characteristic UUID string
        const val KEY_DEVICE_NAME = "pref_device_name" // Key for storing the target device name filter string
        const val KEY_DATA_FORMAT_INDEX = "pref_data_format_index" // Key for storing the selected index from the data format Spinner

        // Default Values - used if no preference is found, or to populate initial settings
        // These example UUIDs should ideally be for a common service/characteristic or clearly marked as placeholders.
        const val DEFAULT_SERVICE_UUID = "0000181A-0000-1000-8000-00805f9b34fb" // Example: Environmental Sensing Service
        const val DEFAULT_CHARACTERISTIC_UUID = "00002A67-0000-1000-8000-00805f9b34fb" // Example: Elevation Characteristic (used as a stand-in for distance)
        const val DEFAULT_DEVICE_NAME = "" // Default: No device name filter
        const val DEFAULT_DATA_FORMAT_INDEX = 0 // Default to the first item in data_format_options array ("2-byte unsigned LE integer")
    }
}
```
