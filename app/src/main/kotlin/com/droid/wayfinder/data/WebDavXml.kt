// Copyright 2026 The Wayfinder Authors
// SPDX-License-Identifier: Apache-2.0

package com.droid.wayfinder.data

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** One `<d:response>` of a PROPFIND multistatus. [path] is the decoded server path of `href` (no trailing slash). */
data class DavResource(
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modified: Long = 0L,
    val displayName: String? = null,
) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
}

/** Pure WebDAV multistatus parsing (RFC 4918) on top of XmlPullParser. DTDs are rejected (no XXE). */
object WebDavXml {
    private const val DAV = "DAV:"

    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>"""

    fun newParser(): XmlPullParser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()

    /** Decodes an href (absolute URL or path) into a plain server path without trailing slash, e.g. `/dav/My File.txt`. */
    fun hrefToPath(href: String): String {
        var h = href.trim()
        val scheme = h.indexOf("://")
        if (scheme > 0) h = h.substring(scheme + 3).let { r -> r.indexOf('/').let { if (it < 0) "/" else r.substring(it) } }
        h = h.substringBefore('?').substringBefore('#')
        val decoded = try { URLDecoder.decode(h.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { h }
        return "/" + decoded.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }

    fun parseHttpDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        for (pattern in listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val f = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                return f.parse(s.trim())?.time ?: continue
            } catch (_: Exception) {}
        }
        return 0L
    }

    /** Parses a multistatus document; [parser] defaults to the platform XmlPullParser. Malformed XML throws [IOException]. */
    fun parseMultistatus(xml: String, parser: XmlPullParser = newParser()): List<DavResource> {
        if (xml.length > MAX_RESPONSE_BYTES) throw IOException("The server's response is too large")
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("Refusing WebDAV response with a DTD")
        }
        return try {
            parse(xml, parser)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // XmlPullParserException and parser-specific runtime errors on malformed input.
            throw IOException("Malformed WebDAV response: ${e.message}", e)
        }
    }

    /** Upper bound for a PROPFIND response body and for the number of resources accepted from it. */
    const val MAX_RESPONSE_BYTES = 32L shl 20
    const val MAX_RESOURCES = 200_000

    private val okStatus = Regex("\\s2\\d\\d(\\s|$)")

    private fun parse(xml: String, parser: XmlPullParser): List<DavResource> {
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        runCatching { parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        parser.setInput(StringReader(xml))
        val out = ArrayList<DavResource>()
        var href: String? = null
        var isDir = false
        var size = 0L
        var modified = 0L
        var display: String? = null
        // Values of the current propstat; committed only if its status is 2xx (or missing).
        var pDir = false
        var pSize: Long? = null
        var pMod: Long? = null
        var pName: String? = null
        var pOk = true
        var inPropstat = false
        var inResourceType = false
        val text = StringBuilder()
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val dav = (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) &&
                (parser.namespace == DAV || parser.namespace.isNullOrEmpty())
            when (ev) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    if (dav) when (parser.name) {
                        "response" -> { href = null; isDir = false; size = 0L; modified = 0L; display = null }
                        "propstat" -> { inPropstat = true; pDir = false; pSize = null; pMod = null; pName = null; pOk = true }
                        "resourcetype" -> inResourceType = true
                        "collection" -> if (inResourceType) pDir = true
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> text.append(parser.text ?: "")
                XmlPullParser.END_TAG -> {
                    val t = text.toString().trim()
                    if (dav) when (parser.name) {
                        "href" -> if (!inPropstat && href == null) href = t
                        "status" -> if (inPropstat) pOk = okStatus.containsMatchIn(" $t")
                        "getcontentlength" -> pSize = t.toLongOrNull()?.coerceAtLeast(0L)
                        "getlastmodified" -> pMod = parseHttpDate(t)
                        "displayname" -> pName = t.ifEmpty { null }
                        "resourcetype" -> inResourceType = false
                        "propstat" -> {
                            inPropstat = false
                            if (pOk) {
                                if (pDir) isDir = true
                                pSize?.let { size = it }
                                pMod?.let { modified = it }
                                pName?.let { display = it }
                            }
                        }
                        "response" -> href?.let { h ->
                            if (out.size >= MAX_RESOURCES) throw IOException("The server returned too many items")
                            // Some servers only mark collections by a trailing slash.
                            val dir = isDir || h.endsWith("/")
                            out += DavResource(hrefToPath(h), dir, if (dir) 0L else size, modified, display)
                        }
                    }
                    text.setLength(0)
                }
            }
            ev = parser.next()
        }
        return out
    }
}
