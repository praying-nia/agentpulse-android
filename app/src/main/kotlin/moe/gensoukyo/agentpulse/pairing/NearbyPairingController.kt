package moe.gensoukyo.agentpulse.pairing

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.Build
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import moe.gensoukyo.agentpulse.protocol.PAIRING_BLE_CHARACTERISTIC_UUID
import moe.gensoukyo.agentpulse.protocol.PAIRING_BLE_SERVICE_UUID

class NearbyPairingController(
    private val activity: ComponentActivity,
    private val onPairingUri: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val manager = activity.getSystemService(CompanionDeviceManager::class.java)
    private val chooser = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        @Suppress("DEPRECATION")
        val extra = result.data?.getParcelableExtra<android.os.Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
        val device = when (extra) {
            is BluetoothDevice -> extra
            is ScanResult -> extra.device
            else -> null
        }
        if (device == null) onError("The selected BLE device was unavailable") else readBundle(device)
    }

    fun start() {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(PAIRING_BLE_SERVICE_UUID)))
                    .build(),
            )
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()
        manager.associate(
            request,
            object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = launch(intentSender)

                @Deprecated("Required for API 26-32")
                override fun onDeviceFound(chooserLauncher: IntentSender) = launch(chooserLauncher)

                override fun onFailure(error: CharSequence?) = onError(error?.toString() ?: "Nearby pairing failed")
            },
            null,
        )
    }

    private fun launch(sender: IntentSender) = chooser.launch(IntentSenderRequest.Builder(sender).build())

    @SuppressLint("MissingPermission", "Deprecated")
    @Suppress("DEPRECATION")
    private fun readBundle(device: BluetoothDevice) {
        val delivered = AtomicBoolean(false)
        var gatt: BluetoothGatt? = null
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(connection: BluetoothGatt, status: Int, newState: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connection.requestMtu(517)
                    connection.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && !delivered.get()) {
                    fail(connection, "BLE connection ended before the pairing bundle was read")
                }
            }

            override fun onServicesDiscovered(connection: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(connection, "AgentPulse BLE service discovery failed")
                    return
                }
                val characteristic = connection
                    .getService(UUID.fromString(PAIRING_BLE_SERVICE_UUID))
                    ?.getCharacteristic(UUID.fromString(PAIRING_BLE_CHARACTERISTIC_UUID))
                if (characteristic == null) {
                    fail(connection, "The selected device does not expose AgentPulse Pairing v1")
                    return
                }
                if (Build.VERSION.SDK_INT >= 33) connection.readCharacteristic(characteristic)
                else {
                    @Suppress("DEPRECATION")
                    connection.readCharacteristic(characteristic)
                }
            }

            @Deprecated("Required for API 26-32")
            override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                @Suppress("DEPRECATION")
                deliver(connection, characteristic.value, status)
            }

            override fun onCharacteristicRead(connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                deliver(connection, value, status)
            }

            private fun deliver(connection: BluetoothGatt, value: ByteArray, status: Int) {
                if (!delivered.compareAndSet(false, true)) return
                if (status == BluetoothGatt.GATT_SUCCESS) onPairingUri(value.decodeToString())
                else onError("Secure BLE read failed with status $status")
                connection.disconnect()
                connection.close()
            }

            private fun fail(connection: BluetoothGatt, message: String) {
                if (delivered.compareAndSet(false, true)) onError(message)
                connection.disconnect()
                connection.close()
            }
        }
        gatt = device.connectGatt(activity, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK)
        if (gatt == null) onError("Could not open the BLE GATT connection")
    }
}
