package com.valentina.riesgoscocina.iot

import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

// Conexión TLS al broker por IPv4 manteniendo la verificación del certificado con el nombre real del servidor
object TlsIpv4 {

    // Cambia el nombre del broker por su dirección IPv4 (el broker rechaza MQTT por IPv6)
    fun urlIpv4(url: String): String {
        val uri = URI(url)
        val ipv4 = InetAddress.getAllByName(uri.host).firstOrNull { it is Inet4Address } ?: return url
        return "${uri.scheme}://${ipv4.hostAddress}:${uri.port}"
    }

    fun nombre(url: String): String = URI(url).host

    fun verificador(nombre: String) = HostnameVerifier { _, sesion ->
        HttpsURLConnection.getDefaultHostnameVerifier().verify(nombre, sesion)
    }

    class Fabrica(private val nombre: String) : SSLSocketFactory() {
        private val base = SSLSocketFactory.getDefault() as SSLSocketFactory

        override fun getDefaultCipherSuites(): Array<String> = base.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = base.supportedCipherSuites

        // Usa el nombre real para SNI y comprueba que el certificado corresponda a ese nombre
        override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
            val ssl = base.createSocket(s, nombre, port, autoClose) as SSLSocket
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(nombre, ssl.session)) {
                ssl.close()
                throw SSLPeerUnverifiedException("El certificado no corresponde a $nombre")
            }
            return ssl
        }

        override fun createSocket(): Socket = base.createSocket()
        override fun createSocket(host: String, port: Int): Socket = base.createSocket(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            base.createSocket(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = base.createSocket(host, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            base.createSocket(address, port, localAddress, localPort)
    }
}
