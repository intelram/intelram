package com.intelram.netsteer.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress

class Socks5Test {
    private fun din(vararg b: Int) = DataInputStream(ByteArrayInputStream(ByteArray(b.size) { b[it].toByte() }))
    private fun din(b: ByteArray) = DataInputStream(ByteArrayInputStream(b))

    private fun authBytes(user: String, pass: String) =
        byteArrayOf(5, 1, 2, 1, user.length.toByte()) + user.toByteArray() + byteArrayOf(pass.length.toByte()) + pass.toByteArray()

    @Test fun handshakeAcceptsCorrectCredentials() {
        val out = ByteArrayOutputStream()
        assertTrue(Socks5.serverHandshake(din(authBytes("u", "secret")), out, "u", "secret"))
        assertArrayEquals(byteArrayOf(5, 2, 1, 0), out.toByteArray())
    }

    @Test fun handshakeRejectsWrongPassword() {
        val out = ByteArrayOutputStream()
        assertFalse(Socks5.serverHandshake(din(authBytes("u", "nope")), out, "u", "secret"))
        assertArrayEquals(byteArrayOf(5, 2, 1, 1), out.toByteArray())
    }

    @Test fun handshakeRejectsClientWithoutUserPassMethod() {
        val out = ByteArrayOutputStream()
        assertFalse(Socks5.serverHandshake(din(5, 1, 0), out, "u", "p"))
        assertArrayEquals(byteArrayOf(5, 0xFF.toByte()), out.toByteArray())
    }

    @Test fun parsesAllAddressTypes() {
        assertEquals(
            Socks5.Request(1, Socks5.Dest.Ip(InetAddress.getByName("1.2.3.4"), 443)),
            Socks5.readRequest(din(5, 1, 0, 1, 1, 2, 3, 4, 0x01, 0xBB)),
        )
        assertEquals(
            Socks5.Request(1, Socks5.Dest.Name("ab.c", 80)),
            Socks5.readRequest(din(5, 1, 0, 3, 4, 'a'.code, 'b'.code, '.'.code, 'c'.code, 0, 80)),
        )
        val v6 = IntArray(16) { if (it == 15) 1 else 0 }
        val req = Socks5.readRequest(din(5, 5, 0, 4, *v6, 0, 53))
        assertEquals(Socks5.CMD_FWD_UDP, req.cmd)
        assertEquals(Socks5.Dest.Ip(InetAddress.getByName("::1"), 53), req.dest)
    }

    @Test(expected = Socks5.ProtocolException::class)
    fun rejectsUnknownAddressType() {
        Socks5.readRequest(din(5, 1, 0, 9, 0, 0))
    }

    @Test fun replyIsTenBytesIpv4() {
        val out = ByteArrayOutputStream()
        Socks5.writeReply(out, Socks5.REP_HOST_UNREACHABLE)
        assertArrayEquals(byteArrayOf(5, 4, 0, 1, 0, 0, 0, 0, 0, 0), out.toByteArray())
    }

    @Test fun udpFrameRoundTripMatchesHevLayout() {
        val out = ByteArrayOutputStream()
        val payload = "hello".toByteArray()
        Socks5.writeUdpFrame(out, InetAddress.getByName("8.8.4.4"), 53, payload, 0, payload.size)
        val bytes = out.toByteArray()
        // datlen=5, hdrlen=3+7=10, atyp=1, 8.8.4.4, port 53
        assertArrayEquals(byteArrayOf(0, 5, 10, 1, 8, 8, 4, 4, 0, 53), bytes.copyOfRange(0, 10))
        val f = Socks5.readUdpFrame(din(bytes))!!
        assertEquals(Socks5.UdpFrame(Socks5.Dest.Ip(InetAddress.getByName("8.8.4.4"), 53), payload), f)
    }

    @Test fun udpFrameWithDomainDestination() {
        // As emitted by hev when mapped DNS is enabled: atyp=3, len, name, port
        val name = "dns.google".toByteArray()
        val hdr = byteArrayOf(0, 2, (3 + 1 + 1 + name.size + 2).toByte(), 3, name.size.toByte()) + name + byteArrayOf(0x01, 0xBB.toByte())
        val f = Socks5.readUdpFrame(din(hdr + byteArrayOf(9, 9)))!!
        assertEquals(Socks5.Dest.Name("dns.google", 443), f.dest)
        assertArrayEquals(byteArrayOf(9, 9), f.payload)
    }

    @Test fun udpFrameEofAndCorruption() {
        assertNull(Socks5.readUdpFrame(din()))
        try {
            Socks5.readUdpFrame(din(0, 5, 99, 1, 1, 1, 1, 1, 0, 1, 1, 2, 3, 4, 5))
            throw AssertionError("expected bad hdrlen")
        } catch (_: Socks5.ProtocolException) {
        }
        try {
            Socks5.readUdpFrame(din(0, 5, 10, 1, 1, 1, 1, 1, 0, 1, 1, 2))
            throw AssertionError("expected truncated")
        } catch (_: Socks5.ProtocolException) {
        }
    }
}
