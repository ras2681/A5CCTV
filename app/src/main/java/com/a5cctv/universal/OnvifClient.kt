package com.a5cctv.universal

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import javax.xml.namespace.NamespaceContext
import org.w3c.dom.Document

private const val NS_SOAP = "http://www.w3.org/2003/05/soap-envelope"
private const val NS_TDS = "http://www.onvif.org/ver10/device/wsdl"
private const val NS_TT = "http://www.onvif.org/ver10/schema"
private const val NS_TR2 = "http://www.onvif.org/ver20/media/wsdl"

class OnvifClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {
    data class Profile(val token: String, val name: String)
    data class Result(val streamUri: String, val profiles: List<Profile>, val deviceService: String, val mediaService: String)

    fun connect(): Result {
        val device = "http://$host:$port/onvif/device_service"
        val capabilities = soap(device, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities", capabilitiesBody())
        val media = firstText(capabilities, "//tds:Media/tt:XAddr")
            ?: firstText(capabilities, "//*[local-name()='Media']/*[local-name()='XAddr']")
            ?: throw IllegalStateException("ONVIF Media service не найден. Устройство может не поддерживать Media Profile S.")

        val profilesXml = soap(media, "http://www.onvif.org/ver10/media/wsdl/GetProfiles", emptyBody())
        val profiles = parseProfiles(profilesXml)
        if (profiles.isEmpty()) throw IllegalStateException("ONVIF не вернул ни одного Media Profile.")

        val chosen = profiles.first()
        val streamBody = """
            <trt:GetStreamUri>
              <trt:StreamSetup>
                <tt:Stream>RTP-Unicast</tt:Stream>
                <tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport>
              </trt:StreamSetup>
              <trt:ProfileToken>${xml(chosen.token)}</trt:ProfileToken>
            </trt:GetStreamUri>
        """.trimIndent()
        val streamXml = soap(media, "http://www.onvif.org/ver10/media/wsdl/GetStreamUri", streamBody)
        val uri = firstText(streamXml, "//*[local-name()='Uri']")
            ?: throw IllegalStateException("ONVIF не вернул RTSP URI для профиля ${chosen.name}.")
        return Result(uri, profiles, device, media)
    }

    private fun parseProfiles(xml: String): List<Profile> {
        val doc = parse(xml)
        val nodes = doc.getElementsByTagNameNS("*", "Profiles")
        val out = ArrayList<Profile>()
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            val token = n.attributes?.getNamedItem("token")?.nodeValue ?: continue
            val nameNode = n.childNodes.let { children ->
                (0 until children.length).map { children.item(it) }
                    .firstOrNull { it.localName == "Name" || it.nodeName.endsWith(":Name") }
            }
            out.add(Profile(token, nameNode?.textContent ?: token))
        }
        return out
    }

    private fun capabilitiesBody() = """
        <tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>
    """.trimIndent()

    private fun emptyBody() = ""

    private fun soap(endpoint: String, action: String, body: String): String {
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val nonceBytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val nonceB64 = Base64.getEncoder().encodeToString(nonceBytes)
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(nonceBytes + created.toByteArray(StandardCharsets.UTF_8) + password.toByteArray(StandardCharsets.UTF_8))
        val passwordDigest = Base64.getEncoder().encodeToString(digest)
        val request = """
            <s:Envelope xmlns:s="$NS_SOAP" xmlns:tds="$NS_TDS" xmlns:tt="$NS_TT" xmlns:trt="http://www.onvif.org/ver10/media/wsdl">
              <s:Header>
                <Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
                  <UsernameToken>
                    <Username>${xml(username)}</Username>
                    <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$passwordDigest</Password>
                    <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
                    <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
                  </UsernameToken>
                </Security>
              </s:Header>
              <s:Body>$body</s:Body>
            </s:Envelope>
        """.trimIndent()
        val conn = URL(endpoint).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
        conn.setRequestProperty("SOAPAction", action)
        conn.outputStream.use { it.write(request.toByteArray(StandardCharsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.readBytes()?.toString(StandardCharsets.UTF_8) ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            val fault = firstText(response, "//*[local-name()='Reason']//*[local-name()='Text']")
                ?: firstText(response, "//*[local-name()='Fault']//*[local-name()='Text']")
            throw IllegalStateException("ONVIF HTTP $code${if (!fault.isNullOrBlank()) ": $fault" else ""}")
        }
        return response
    }

    private fun parse(xml: String): Document {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun firstText(xml: String, expression: String): String? {
        if (xml.isBlank()) return null
        val xp = XPathFactory.newInstance().newXPath().apply {
            namespaceContext = object : NamespaceContext {
                override fun getNamespaceURI(prefix: String): String = when (prefix) {
                    "s" -> NS_SOAP
                    "tds" -> NS_TDS
                    "tt" -> NS_TT
                    "trt" -> "http://www.onvif.org/ver10/media/wsdl"
                    else -> javax.xml.XMLConstants.NULL_NS_URI
                }
                override fun getPrefix(namespaceURI: String?) = null
                override fun getPrefixes(namespaceURI: String?) = emptyList<String>().iterator()
            }
        }
        return runCatching { xp.evaluate(expression, parse(xml), XPathConstants.STRING) as String }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}

object OnvifDiscovery {
    private const val PROBE = """
        <?xml version=\"1.0\" encoding=\"UTF-8\"?>
        <e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">
          <e:Header>
            <w:MessageID>urn:uuid:__UUID__</w:MessageID>
            <w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>
            <w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>
          </e:Header>
          <e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body>
        </e:Envelope>
    """

    fun discover(timeoutMs: Int = 2500): List<String> {
        val message = PROBE.replace("__UUID__", UUID.randomUUID().toString()).toByteArray(StandardCharsets.UTF_8)
        val address = InetAddress.getByName("239.255.255.250")
        val packet = DatagramPacket(message, message.size, address, 3702)
        val socket = DatagramSocket().apply { soTimeout = 500 }
        socket.broadcast = true
        socket.send(packet)
        val start = System.currentTimeMillis()
        val out = linkedSetOf<String>()
        try {
            while (System.currentTimeMillis() - start < timeoutMs) {
                val buf = ByteArray(8192)
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                val text = String(p.data, p.offset, p.length, StandardCharsets.UTF_8)
                Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(text).forEach { out.add(it.value.trimEnd('/', '"', '\'')) }
            }
        } finally { socket.close() }
        return out.toList()
    }
}
