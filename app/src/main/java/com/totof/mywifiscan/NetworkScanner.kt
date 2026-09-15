package com.totof.mywifiscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class Device(
    val ip: String,
    val mac: String = "Inconnu (Restreint)",
    val name: String = "Inconnu",
    val model: String? = null,
    val type: String = "Ping",
    val os: String = "Inconnu"
)

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val capabilities: String
)

class NetworkScanner(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val multicastLock: WifiManager.MulticastLock by lazy {
        wifiManager.createMulticastLock("my_wifi_scan_lock")
    }

    fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun scanNetwork(): List<Device> = withContext(Dispatchers.IO) {
        if (!isWifiConnected()) return@withContext emptyList()

        val foundDevices = ConcurrentHashMap<String, Device>()

        coroutineScope {
            async { discoverUpnp(foundDevices) }
            async { discoverMdns(foundDevices) }
            val pingJob = async { performPingSweep(foundDevices) }

            delay(3000)
            pingJob.await()
        }

        // Tentative de deviner l'OS pour chaque appareil trouvé
        val devicesWithOs = foundDevices.values.map { device ->
            async { guessDeviceOS(device) }
        }.awaitAll()

        devicesWithOs.sortedBy { it.ip }
    }

    suspend fun scanWifiNetworks(): List<WifiNetwork> = withContext(Dispatchers.IO) {
        try {
            wifiManager.startScan()
            delay(1000)
            wifiManager.scanResults.map {
                WifiNetwork(
                    ssid = it.SSID ?: "Inconnu",
                    bssid = it.BSSID ?: "Inconnu",
                    rssi = it.level,
                    frequency = it.frequency,
                    capabilities = it.capabilities ?: ""
                )
            }.sortedByDescending { it.rssi }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun getLatestRssi(bssid: String): Int {
        try {
            // 1. Si c'est le réseau auquel on est actuellement connecté, 
            // on obtient une mise à jour instantanée et très fréquente.
            val connectionInfo = wifiManager.connectionInfo
            if (connectionInfo != null && connectionInfo.bssid == bssid) {
                return connectionInfo.rssi
            }
            
            // 2. Sinon, on demande un nouveau scan. 
            // Note: Android limite à 4 scans toutes les 2 minutes pour les apps au premier plan.
            wifiManager.startScan()
            
            // 3. On retourne la dernière valeur connue pour ce BSSID
            val results = wifiManager.scanResults
            return results.find { it.BSSID == bssid }?.level ?: -100
        } catch (e: SecurityException) {
            return -100
        } catch (e: Exception) {
            return -100
        }
    }

    private suspend fun performPingSweep(foundDevices: ConcurrentHashMap<String, Device>) = coroutineScope {
        val ipAddress = wifiManager.dhcpInfo.ipAddress
        if (ipAddress == 0) return@coroutineScope

        val prefix = String.format(
            Locale.US,
            "%d.%d.%d.",
            ipAddress and 0xFF,
            (ipAddress shr 8) and 0xFF,
            (ipAddress shr 16) and 0xFF
        )

        (1..254).map { i ->
            async {
                val testIp = prefix + i
                try {
                    val address = InetAddress.getByName(testIp)
                    if (address.isReachable(300)) {
                        val hostName = address.hostName
                        val existing = foundDevices[testIp]
                        val updatedName = if (existing?.name == null || existing.name == "Inconnu") {
                            if (hostName != testIp) hostName else "Appareil Actif"
                        } else {
                            existing.name
                        }
                        foundDevices[testIp] = (existing ?: Device(ip = testIp)).copy(
                            name = updatedName
                        )
                    }
                } catch (e: Exception) { }
            }
        }.awaitAll()
    }

    private fun discoverUpnp(foundDevices: ConcurrentHashMap<String, Device>) {
        try {
            multicastLock.acquire()
            val socket = DatagramSocket()
            socket.soTimeout = 2000
            val group = InetAddress.getByName("239.255.255.250")
            val port = 1900

            val query = "M-SEARCH * HTTP/1.1\r\n" +
                    "HOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\n" +
                    "ST: ssdp:all\r\n" +
                    "\r\n"

            val packet = DatagramPacket(query.toByteArray(), query.length, group, port)
            socket.send(packet)

            val buffer = ByteArray(2048)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 2500) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length)
                    val ip = receivePacket.address.hostAddress ?: continue

                    var model: String? = null
                    if (response.contains("ST:", ignoreCase = true)) {
                        model = response.lines().find { it.startsWith("ST:", ignoreCase = true) }
                            ?.substringAfter(":")?.trim()
                    }

                    val existing = foundDevices[ip]
                    foundDevices[ip] = (existing ?: Device(ip = ip)).copy(
                        type = "UPnP",
                        model = model ?: existing?.model
                    )
                } catch (e: Exception) { break }
            }
            socket.close()
        } catch (e: Exception) {} finally {
            if (multicastLock.isHeld) multicastLock.release()
        }
    }

    private fun discoverMdns(foundDevices: ConcurrentHashMap<String, Device>) {
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val ip = serviceInfo.host.hostAddress ?: return
                        val name = serviceInfo.serviceName
                        val existing = foundDevices[ip]
                        val osGuess = when {
                            name.contains("Android", ignoreCase = true) -> "Android"
                            name.contains("iPhone", ignoreCase = true) || name.contains("iPad", ignoreCase = true) -> "iOS"
                            name.contains("MacBook", ignoreCase = true) || name.contains("Mac", ignoreCase = true) -> "macOS"
                            else -> existing?.os ?: "Inconnu"
                        }
                        foundDevices[ip] = (existing ?: Device(ip = ip)).copy(
                            name = name,
                            type = "mDNS",
                            os = osGuess
                        )
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                try { nsdManager.stopServiceDiscovery(this) } catch(e: Exception) {}
            }
            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {
                try { nsdManager.stopServiceDiscovery(this) } catch(e: Exception) {}
            }
        }

        try {
            nsdManager.discoverServices("_http._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            nsdManager.discoverServices("_googlecast._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            nsdManager.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {}
    }

    private fun isPortOpen(ip: String, port: Int, timeout: Int = 200): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeout)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun guessDeviceOS(device: Device): Device = withContext(Dispatchers.IO) {
        // 1. Analyse via le nom ou le modèle déjà trouvé
        var guessedOS = device.os
        val combinedInfo = "${device.name} ${device.model}".lowercase()

        if (guessedOS == "Inconnu") {
            guessedOS = when {
                combinedInfo.contains("windows") -> "Windows"
                combinedInfo.contains("android") -> "Android"
                combinedInfo.contains("linux") -> "Linux"
                combinedInfo.contains("iphone") || combinedInfo.contains("ipad") || combinedInfo.contains("apple") -> "iOS"
                combinedInfo.contains("macbook") || combinedInfo.contains("macos") -> "macOS"
                else -> "Inconnu"
            }
        }

        // 2. Si toujours inconnu, on vérifie quelques ports caractéristiques
        if (guessedOS == "Inconnu") {
            guessedOS = when {
                isPortOpen(device.ip, 445) || isPortOpen(device.ip, 139) -> "Windows (Probable)"
                isPortOpen(device.ip, 22) -> "Linux/macOS (SSH)"
                isPortOpen(device.ip, 62078) -> "iOS (iPhone/iPad)"
                isPortOpen(device.ip, 8008) || isPortOpen(device.ip, 8009) -> "Android/Chromecast"
                else -> "Inconnu"
            }
        }

        device.copy(os = guessedOS)
    }

    suspend fun measureLatency(ip: String): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val address = InetAddress.getByName(ip)
            if (address.isReachable(1000)) {
                System.currentTimeMillis() - start
            } else {
                -1L
            }
        } catch (e: Exception) {
            -1L
        }
    }
}
