package com.nen.alarmsynccalendar.calendar

object MeetingUtils {
    private val urlRegex = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    
    private val knownDomains = listOf(
        "meet.google.com",
        "teams.microsoft.com",
        "teams.live.com",
        "zoom.us",
        "zoom.gov",
        "webex.com",
        "skype.com",
        "gotomeeting.com",
        "jitsi.org",
        "bluejeans.com",
        "lu.ma",
        "whereby.com",
        "discord.gg",
        "chime.aws",
        "meet.jit.si"
    )

    // Google Maps URLs are locations, never video-meeting links
    private val mapsUrlMarkers = listOf("google.com/maps", "maps.google.", "maps.app.goo.gl", "goo.gl/maps")

    fun isGoogleMapsUrl(text: String): Boolean {
        val lower = text.lowercase()
        return mapsUrlMarkers.any { lower.contains(it) }
    }

    fun extractMeetingLink(location: String?, description: String?): String? {
        // 1. If location is directly a URL, prioritize it — unless it's a Maps link
        if (location != null) {
            val trimmed = location.trim()
            if ((trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) && !isGoogleMapsUrl(trimmed)) {
                return trimmed
            }
        }

        // 2. Search for known video meeting urls in location first
        val locationLink = findKnownUrl(location)
        if (locationLink != null) return locationLink

        // 3. Any other URL in the location field is intentional (e.g. a lu.ma or
        //    short event link embedded in text) — treat the event as online and
        //    use that link, unless it's a Maps link (that's a location).
        if (location != null) {
            val anyUrl = urlRegex.find(location)?.value
            if (anyUrl != null && !isGoogleMapsUrl(anyUrl)) return anyUrl
        }

        // 4. Search in description text (known meeting domains only — descriptions
        //    are full of unrelated links, so no catch-all fallback here)
        return findKnownUrl(description)
    }

    // Location strings Outlook/Google use for online-only meetings — not real addresses.
    private val onlineLocationKeywords = listOf(
        "microsoft teams", "teams meeting", "google meet", "zoom meeting",
        "zoom call", "webex", "skype", "online", "virtual"
    )

    /**
     * Returns the location only if it looks like a physical address:
     * not a URL and not a meeting-platform placeholder like "Microsoft Teams Meeting".
     */
    fun extractPhysicalLocation(location: String?): String? {
        val trimmed = location?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (urlRegex.containsMatchIn(trimmed)) {
            // Google Maps links: mine coordinates or the place name locally — no API cost
            if (isGoogleMapsUrl(trimmed)) return extractFromMapsUrl(trimmed)
            return null
        }
        val lower = trimmed.lowercase()
        if (onlineLocationKeywords.any { lower.contains(it) }) return null
        if (knownDomains.any { lower.contains(it) }) return null
        return trimmed
    }

    // Words that indicate an internal space (meeting room, floor, etc.), not a street address
    private val roomKeywords = listOf(
        "room", "conf", "conference", "boardroom", "board room", "cabin", "cubicle",
        "huddle", "bay", "desk", "pod", "wing", "floor", "flr", "bldg", "building",
        "hall", "auditorium", "canteen", "cafeteria", "pantry", "lobby", "reception", "office"
    )

    // Street/locality suffixes that indicate a real address (incl. common Indian terms)
    private val streetKeywords = listOf(
        "street", "st.", "road", "rd", "avenue", "ave", "lane", "ln", "boulevard", "blvd",
        "drive", "cross", "main", "layout", "nagar", "sector", "phase", "stage", "block",
        "circle", "highway", "hwy", "marg", "colony", "enclave", "extension", "puram",
        "campus", "tech park", "industrial area", "city", "town"
    )

    /**
     * Heuristic: does this location string look like an internal space (conference
     * room, floor, cabin) rather than a geocodable address? Used to skip the billed
     * Distance Matrix call and show "travel check skipped" instead.
     *
     * Rules, in order:
     * 1. "lat,lng" coordinates → address.
     * 2. Strong address signals (comma, 5-6 digit postal code, or street suffix
     *    plus a number) → address, even if a room word also appears
     *    ("Meeting Room 2, 45 MG Road, Bengaluru").
     * 3. Room keyword → room.
     * 4. Short alphanumeric codes ("MR-3", "B2", "CR 12") → room.
     * 5. Anything else (venue names like "Taj West End") → treated as an address;
     *    the API decides, and NOT_FOUND results are blacklisted after one attempt.
     */
    fun isRoomLikeLocation(location: String): Boolean {
        val lower = location.lowercase().trim()
        if (Regex("""^-?\d{1,3}\.\d+\s*,\s*-?\d{1,3}\.\d+$""").matches(lower)) return false

        val hasComma = lower.contains(',')
        val hasPostalCode = Regex("""\b\d{5,6}\b""").containsMatchIn(lower)
        val hasStreetWord = streetKeywords.any { Regex("""\b${Regex.escape(it)}""").containsMatchIn(lower) }
        val hasDigit = lower.any { it.isDigit() }
        if (hasComma || hasPostalCode || (hasStreetWord && hasDigit)) return false

        if (roomKeywords.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lower) }) return true
        if (Regex("""^[a-z]{0,4}[-\s]?\d{1,4}[a-z]?$""").matches(lower)) return true

        return false
    }

    /**
     * Pulls a usable destination out of a Google Maps URL without any network call:
     * "@lat,lng" coordinates, a "?q=" query, or the "/maps/place/<name>" segment.
     * Short links (maps.app.goo.gl) carry none of these — returns null, so no
     * Distance Matrix call is wasted on them.
     */
    private fun extractFromMapsUrl(url: String): String? {
        Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""").find(url)?.let {
            return "${it.groupValues[1]},${it.groupValues[2]}"
        }
        Regex("""[?&]q(?:uery)?=([^&]+)""").find(url)?.let {
            val q = try { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (e: Exception) { it.groupValues[1] }
                .replace('+', ' ').trim()
            if (q.isNotBlank()) return q
        }
        Regex("""/maps/place/([^/@?]+)""").find(url)?.let {
            val name = try { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } catch (e: Exception) { it.groupValues[1] }
                .replace('+', ' ').trim()
            if (name.isNotBlank()) return name
        }
        return null
    }

    private fun findKnownUrl(text: String?): String? {
        if (text == null) return null
        val matches = urlRegex.findAll(text)
        for (match in matches) {
            val url = match.value
            if (knownDomains.any { url.contains(it, ignoreCase = true) }) {
                return url
            }
        }
        return null
    }
}
