package com.example.vl53l0xbleapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.vl53l0xbleapp.databinding.ListItemDeviceBinding


class DeviceListAdapter(
    private var devices: MutableList<BluetoothDevice>,
    private val onItemClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceListAdapter.DeviceViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ListItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    @SuppressLint("MissingPermission") // Permissions for name/address are checked before initiating scan/connection
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.bind(device)
        holder.itemView.setOnClickListener { onItemClick(device) }
    }

    override fun getItemCount(): Int = devices.size

    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice) {
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clearDevices() {
        devices.clear()
        notifyDataSetChanged()
    }

    @SuppressLint("MissingPermission")
    class DeviceViewHolder(private val binding: ListItemDeviceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(device: BluetoothDevice) {
            binding.deviceNameTextView.text = device.name ?: "Unknown Device"
            binding.deviceAddressTextView.text = device.address
        }
    }
}
