// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

data class DiscoveredHost(val label: String, val address: String)

/** Finds SMB servers on the LAN via passive mDNS (`_smb._tcp`), with optional on-demand port-445 scan. */
class NetworkDiscovery(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _hosts = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val hosts: StateFlow<List<DiscoveredHost>> = _hosts
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var scanJob: Job? = null

    private fun add(found: DiscoveredHost) = _hosts.update { cur ->
        // Service names come from anyone on the LAN: bound their length and the list size.
        val h = found.copy(label = found.label.filter { it >= ' ' }.take(64).ifBlank { found.address })
        val existing = cur.indexOfFirst { it.address == h.address }
        when {
            existing < 0 && cur.size >= 256 -> cur
            existing < 0 -> (cur + h).sortedBy { it.label.lowercase() }
            // Prefer a real name over a bare IP label.
            cur[existing].label == cur[existing].address && h.label != h.address ->
                (cur.toMutableList().also { it[existing] = h }).sortedBy { it.label.lowercase() }
            else -> cur
        }
    }

    fun start() {
        if (listener == null) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
                override fun onServiceLost(info: NsdServiceInfo) {
                    _hosts.update { cur -> cur.filterNot { it.label == info.serviceName } }
                }
            }
            runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, l); listener = l }
        }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        scanJob?.cancel()
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        pending.addLast(info)
        resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        if (resolving) return
        val next = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = done()
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host
                if (host is Inet4Address) add(DiscoveredHost(serviceInfo.serviceName, host.hostAddress ?: return done()))
                done()
            }

            fun done() = synchronized(this@NetworkDiscovery) { resolving = false; resolveNext() }
        })
    }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch(Dispatchers.IO) {
            _scanning.value = true
            try {
                val prefix = localPrefix() ?: return@launch
                val gate = Semaphore(48)
                (1..254).map { i ->
                    async {
                        gate.withPermit {
                            val ip = "$prefix.$i"
                            if (open445(ip)) add(DiscoveredHost(ip, ip))
                        }
                    }
                }.awaitAll()
            } finally {
                _scanning.value = false
            }
        }
    }

    private fun localPrefix(): String? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val addr = props.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().firstOrNull() ?: return null
        return addr.hostAddress?.substringBeforeLast('.')
    }

    private suspend fun open445(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(ip, 445), 350); true }
        } catch (_: Exception) {
            false
        }
    }
}
