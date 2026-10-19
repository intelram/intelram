package com.intelram.netsteer.core

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * The subset of SOCKS5 (RFC 1928 / RFC 1929) spoken by hev-socks5-tunnel:
 * username/password auth, CONNECT, and hev's FWD_UDP ("UDP in TCP") command.
 */
object Socks5 {
    const val VERSION = 5
    const val AUTH_NONE = 0x00
    const val AUTH_USERPASS = 0x02
    const val AUTH_NO_ACCEPTABLE = 0xFF
    const val CMD_CONNECT = 0x01
    const val CMD_UDP_ASSOCIATE = 0x03
    /** hev-socks5 extension: UDP datagrams framed inside this TCP stream. */
    const val CMD_FWD_UDP = 0x05
    const val ATYP_IPV4 = 0x01
    const val ATYP_DOMAIN = 0x03
    const val ATYP_IPV6 = 0x04

    const val REP_SUCCEEDED = 0x00
    const val REP_GENERAL_FAILURE = 0x01
    const val REP_NOT_ALLOWED = 0x02
    const val REP_NETWORK_UNREACHABLE = 0x03
    const val REP_HOST_UNREACHABLE = 0x04
    const val REP_CONNECTION_REFUSED = 0x05
    const val REP_COMMAND_NOT_SUPPORTED = 0x07
    const val REP_ATYP_NOT_SUPPORTED = 0x08

    class ProtocolException(msg: String) : IOException(msg)

    /** Destination: either a literal address or a host name to resolve on the chosen link. */
    sealed interface Dest {
        val port: Int
        data class Ip(val address: InetAddress, override val port: Int) : Dest
        data class Name(val host: String, override val port: Int) : Dest
    }

    data class Request(val cmd: Int, val dest: Dest)

    /**
     * Server side of method negotiation + RFC 1929 auth.
     * Returns true when the client authenticated with [user]/[pass].
     */
    fun serverHandshake(input: DataInputStream, out: OutputStream, user: String, pass: String): Boolean {
        if (input.readUnsignedByte() != VERSION) throw ProtocolException("bad version")
        val n = input.readUnsignedByte()
        val methods = ByteArray(n).also { input.readFully(it) }
        if (methods.none { it.toInt() and 0xFF == AUTH_USERPASS }) {
            out.write(byteArrayOf(VERSION.toByte(), AUTH_NO_ACCEPTABLE.toByte())); out.flush()
            return false
        }
        out.write(byteArrayOf(VERSION.toByte(), AUTH_USERPASS.toByte())); out.flush()

        if (input.readUnsignedByte() != 0x01) throw ProtocolException("bad auth version")
        val u = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        val p = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        // Constant-time compare: the port is reachable by every app on the device.
        val ok = MessageDigest.isEqual(u, user.toByteArray()) and MessageDigest.isEqual(p, pass.toByteArray())
        out.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01)); out.flush()
        return ok
    }

    fun readRequest(input: DataInputStream): Request {
        if (input.readUnsignedByte() != VERSION) throw ProtocolException("bad request version")
        val cmd = input.readUnsignedByte()
        input.readUnsignedByte() // RSV
        return Request(cmd, readAddress(input))
    }

    /** Reads ATYP + address + port (shared by requests and UDP frames). */
    fun readAddress(input: DataInputStream): Dest = when (val atyp = input.readUnsignedByte()) {
        ATYP_IPV4 -> Dest.Ip(InetAddress.getByAddress(ByteArray(4).also { input.readFully(it) }), input.readUnsignedShort())
        ATYP_IPV6 -> Dest.Ip(InetAddress.getByAddress(ByteArray(16).also { input.readFully(it) }), input.readUnsignedShort())
        ATYP_DOMAIN -> {
            val name = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
            Dest.Name(String(name, Charsets.US_ASCII), input.readUnsignedShort())
        }
        else -> throw ProtocolException("unsupported atyp $atyp")
    }

    /** Reply with BND.ADDR 0.0.0.0:0 (hev only accepts IPv4/IPv6 here, and ignores the value). */
    fun writeReply(out: OutputStream, rep: Int) {
        out.write(byteArrayOf(VERSION.toByte(), rep.toByte(), 0, ATYP_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    fun encodeAddress(address: InetAddress, port: Int): ByteArray {
        val raw = address.address
        val atyp = when (address) {
            is Inet4Address -> ATYP_IPV4
            is Inet6Address -> ATYP_IPV6
            else -> throw ProtocolException("unknown address family")
        }
        return byteArrayOf(atyp.toByte()) + raw + byteArrayOf((port shr 8).toByte(), port.toByte())
    }

    /**
     * hev UDP-in-TCP frame, identical in both directions:
     * `[datlen:u16][hdrlen:u8][ATYP][addr][port:u16][payload]`, where
     * hdrlen = 3 + len(ATYP..port).
     */
    data class UdpFrame(val dest: Dest, val payload: ByteArray) {
        override fun equals(other: Any?) =
            other is UdpFrame && dest == other.dest && payload.contentEquals(other.payload)
        override fun hashCode() = 31 * dest.hashCode() + payload.contentHashCode()
    }

    /** @return null on clean EOF before a new frame starts. */
    fun readUdpFrame(input: DataInputStream): UdpFrame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val datlen = (b0 shl 8) or input.readUnsignedByte()
        val hdrlen = input.readUnsignedByte()
        val dest = readAddress(input)
        val addrLen = when (dest) {
            is Dest.Ip -> 1 + dest.address.address.size + 2
            is Dest.Name -> 1 + 1 + dest.host.length + 2
        }
        if (hdrlen != 3 + addrLen) throw ProtocolException("bad udp hdrlen $hdrlen")
        val payload = ByteArray(datlen)
        try {
            input.readFully(payload)
        } catch (e: EOFException) {
            throw ProtocolException("truncated udp frame")
        }
        return UdpFrame(dest, payload)
    }

    fun writeUdpFrame(out: OutputStream, from: InetAddress, port: Int, data: ByteArray, off: Int, len: Int) {
        require(len <= 0xFFFF) { "datagram too large" }
        val addr = encodeAddress(from, port)
        val header = ByteArray(3 + addr.size)
        header[0] = (len shr 8).toByte()
        header[1] = len.toByte()
        header[2] = (3 + addr.size).toByte()
        addr.copyInto(header, 3)
        // Single write per frame: the stream is shared by one writer thread only.
        out.write(header + data.copyOfRange(off, off + len))
        out.flush()
    }
}
