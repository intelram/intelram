package com.intelram.netsteer.tunnel

/**
 * JNI binding for hev-socks5-tunnel (built from app/src/main/jni, pinned 2.18.0).
 * JNI_OnLoad registers exactly these four static methods on this class by name,
 * with -DPKGNAME=com/intelram/netsteer/tunnel -DCLSNAME=TProxy. Do not rename.
 */
object TProxy {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    /** Starts the tunnel thread reading/writing [tunFd]. False if already running or on error. */
    @JvmStatic external fun TProxyStartService(configPath: String, tunFd: Int): Boolean

    /** Stops the tunnel and joins its thread. */
    @JvmStatic external fun TProxyStopService(): Boolean

    @JvmStatic external fun TProxyIsRunning(): Boolean

    /** [txPackets, txBytes, rxPackets, rxBytes] as seen on the TUN interface. */
    @JvmStatic external fun TProxyGetStats(): LongArray
}
