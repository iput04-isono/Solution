package com.crossvision.f.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS (Network Service Discovery) を使用して、WiFi内のサーバーを自動発見するクラス。
 */
class NsdHelper(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_crossvision._tcp."
    private var isDiscoveryStarted = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // 発見したサーバー情報を返すコールバック
    var onServerFound: ((String, Int) -> Unit)? = null

    fun startDiscovery() {
        if (isDiscoveryStarted) return
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NsdHelper", "Discovery started")
                isDiscoveryStarted = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d("NsdHelper", "Service found: ${serviceInfo.serviceName}")
                // 探しているサービスタイプか確認
                if (serviceInfo.serviceType.contains("crossvision")) {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("NsdHelper", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                            val host = resolvedServiceInfo.host.hostAddress
                            val port = resolvedServiceInfo.port
                            Log.i("NsdHelper", "Resolved address: $host:$port")
                            onServerFound?.invoke(host ?: "", port)
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d("NsdHelper", "Service lost")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d("NsdHelper", "Discovery stopped")
                isDiscoveryStarted = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdHelper", "Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NsdHelper", "Stop discovery failed: $errorCode")
                stopDiscovery()
            }
        }

        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        if (isDiscoveryStarted && discoveryListener != null) {
            nsdManager.stopServiceDiscovery(discoveryListener)
            discoveryListener = null
        }
    }
}
