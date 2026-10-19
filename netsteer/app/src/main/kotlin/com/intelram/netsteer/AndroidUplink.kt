package com.intelram.netsteer

import android.net.Network
import com.intelram.netsteer.core.Uplink
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A real link. Every socket is bound to [network] before use, so traffic leaves
 * through that interface regardless of the system default network. This app
 * is excluded from its own VPN (addDisallowedApplication), so these sockets
 * never loop back into the tunnel.
 */
class AndroidUplink(val network: Network, override val id: String) : Uplink {
    override fun resolve(host: String): List<InetAddress> = network.getAllByName(host).toList()

    override fun connect(address: InetSocketAddress, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            network.bindSocket(s)
            s.connect(address, timeoutMs)
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    override fun datagramSocket(): DatagramSocket {
        val ds = DatagramSocket()
        try {
            network.bindSocket(ds)
            return ds
        } catch (e: Exception) {
            ds.close()
            throw e
        }
    }
}
