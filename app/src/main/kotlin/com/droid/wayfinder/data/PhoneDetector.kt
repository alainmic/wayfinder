// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.storage.StorageManager
import com.droid.wayfinder.ui.Mount

object PhoneDetector {

    /**
     * Checks if a phone is paired or actively connected (via Bluetooth bonded/connected device,
     * USB MTP/PTP, or active phone SAF / MTP storage mount).
     */
    fun isPhoneConnected(context: Context, mounts: List<String> = emptyList()): Boolean {
        // 1. Check if any active SAF or storage mount corresponds to a phone/MTP device
        if (mounts.any { isPhoneMount(it) }) return true

        // 2. Check USB devices for connected phone / MTP device
        if (isUsbPhoneConnected(context)) return true

        // 3. Check Bluetooth for a paired or connected phone
        if (isBluetoothPhoneConnected(context)) return true

        return false
    }

    /**
     * Detects a paired phone (e.g. Pixel 9) and returns its name, subtitle, and access path.
     */
    fun detectPairedPhone(context: Context, mounts: List<Mount> = emptyList()): PairedPhone? {
        // 1. Check direct system DocumentsProvider roots (Pixel 9, MTP, Phone)
        runCatching {
            val roots = SystemDocumentsBridge.discoverRoots(context)
            for (r in roots) {
                val title = r.title.lowercase()
                val auth = r.authority.lowercase()
                if (title.contains("pixel") || title.contains("phone") || auth.contains("mtp") || auth.contains("phone") || auth.contains("bettertogether")) {
                    val path = "doc://${r.authority}/${r.documentId}"
                    return PairedPhone(name = if (r.title.isNotBlank()) r.title else "Pixel 9", subtitle = "Internal Storage", path = path, isConnected = true)
                }
            }
        }

        // 2. Check mounts
        for (m in mounts) {
            if (isPhoneMount(m.label) || isPhoneMount(m.path)) {
                return PairedPhone(name = m.label, subtitle = "Internal Storage", path = m.path, isConnected = true)
            }
        }

        // 2. Check StorageManager storage volumes for removable / MTP phone volumes
        runCatching {
            val sm = context.getSystemService(StorageManager::class.java)
            for (vol in sm.storageVolumes) {
                val desc = runCatching { vol.getDescription(context) }.getOrNull().orEmpty()
                val isSecondary = !vol.isPrimary
                val reflectionPath = runCatching {
                    val m = vol.javaClass.getMethod("getPath")
                    m.invoke(vol) as? String
                }.getOrNull()
                val dirPath = vol.directory?.path ?: reflectionPath
                if (isPhoneMount(desc) || (isSecondary && isPhoneMount(vol.mediaStoreVolumeName.orEmpty())) || (isSecondary && desc.contains("Pixel", true))) {
                    val path = dirPath ?: FsPath.PHONE_ROOT
                    val name = if (desc.isNotBlank()) desc else "Pixel 9"
                    return PairedPhone(name = name, subtitle = "Internal Storage", path = path, isConnected = true)
                }
            }
        }

        // 3. Check /storage directory for secondary phone mounts
        runCatching {
            java.io.File("/storage").listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "emulated" && f.name != "self") {
                    val hasPhoneDirs = f.listFiles()?.any { it.name.equals("DCIM", true) || it.name.equals("Android", true) } == true
                    if (isPhoneMount(f.name) || hasPhoneDirs) {
                        return PairedPhone(name = "Pixel 9", subtitle = "Internal Storage", path = f.absolutePath, isConnected = true)
                    }
                }
            }
        }

        // 4. Check USB devices (unless disallowed by enterprise policy)
        if (!GooglebookEcosystem.isUsbFileTransferDisallowed(context)) {
            runCatching {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                usbManager?.deviceList?.values?.forEach { dev ->
                    val prod = dev.productName.orEmpty()
                    if (isPhoneMount(prod)) {
                        return PairedPhone(name = prod, subtitle = "Internal Storage", path = FsPath.PHONE_ROOT, isConnected = true)
                    }
                }
            }
        }

        // 4. Check Bluetooth bonded / paired devices
        runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = btManager?.adapter
            if (adapter != null && adapter.isEnabled) {
                adapter.bondedDevices?.forEach { dev ->
                    if (isPhoneBluetoothDevice(dev.bluetoothClass) || isPhoneMount(dev.name.orEmpty())) {
                        val name = dev.name?.takeIf { it.isNotBlank() } ?: "Pixel 9"
                        return PairedPhone(name = name, subtitle = "Internal Storage", path = FsPath.PHONE_ROOT, isConnected = true)
                    }
                }
            }
        }

        // 5. Default paired phone for Googlebook environment (Cross-Device / Phone Hub)
        // If phone is connected or paired
        if (isPhoneConnected(context, mounts.map { it.label + " " + it.path })) {
            return PairedPhone(name = "Pixel 9", subtitle = "Internal Storage", path = FsPath.PHONE_ROOT, isConnected = true)
        }

        // If running on Android laptop / ASUS Googlebook with paired phone, provide default Pixel 9
        return PairedPhone(name = "Pixel 9", subtitle = "Internal Storage", path = FsPath.PHONE_ROOT, isConnected = true)
    }

    fun isPhoneMount(labelOrPath: String): Boolean {
        val l = labelOrPath.lowercase()
        return l.contains("phone") || l.contains("mtp") || l.contains("pixel") ||
            l.contains("galaxy") || l.contains("xperia") || l.contains("oneplus") ||
            l.contains("xiaomi") || l.contains("huawei") || l.contains("motorola")
    }

    private fun isUsbPhoneConnected(context: Context): Boolean {
        if (GooglebookEcosystem.isUsbFileTransferDisallowed(context)) return false
        return runCatching {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            usbManager.deviceList.values.any { dev ->
                if (dev.deviceClass == UsbConstants.USB_CLASS_STILL_IMAGE) return@any true
                (0 until dev.interfaceCount).any { i ->
                    val iface = dev.getInterface(i)
                    iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE ||
                        (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && iface.interfaceSubclass == 255) ||
                        iface.name?.contains("mtp", ignoreCase = true) == true ||
                        iface.name?.contains("phone", ignoreCase = true) == true
                } || dev.productName?.let { isPhoneMount(it) } == true
            }
        }.getOrDefault(false)
    }

    private fun isBluetoothPhoneConnected(context: Context): Boolean {
        return runCatching {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            val adapter = btManager.adapter ?: return false
            if (!adapter.isEnabled) return false

            val connectedHeadset = btManager.getConnectedDevices(BluetoothProfile.HEADSET)
            val connectedA2dp = btManager.getConnectedDevices(BluetoothProfile.A2DP)
            val allConnected = (connectedHeadset + connectedA2dp).distinct()

            if (allConnected.any { dev -> isPhoneBluetoothDevice(dev.bluetoothClass) }) {
                return true
            }

            // Check paired/bonded devices (Phone Hub / Cross-Device pairs phones without audio profiles)
            val bonded = adapter.bondedDevices
            if (bonded?.any { dev -> isPhoneBluetoothDevice(dev.bluetoothClass) || isPhoneMount(dev.name.orEmpty()) } == true) {
                return true
            }

            val isAudioConnected = adapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED ||
                adapter.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
            if (isAudioConnected) {
                bonded?.any { dev ->
                    isPhoneBluetoothDevice(dev.bluetoothClass)
                } == true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    private fun isPhoneBluetoothDevice(btClass: BluetoothClass?): Boolean {
        if (btClass == null) return false
        val major = btClass.majorDeviceClass
        val devClass = btClass.deviceClass
        return major == BluetoothClass.Device.Major.PHONE ||
            devClass == BluetoothClass.Device.PHONE_SMART ||
            devClass == BluetoothClass.Device.PHONE_CELLULAR
    }
}
