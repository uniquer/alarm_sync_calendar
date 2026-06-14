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
        "bluejeans.com"
    )

    fun extractMeetingLink(location: String?, description: String?): String? {
        // 1. If location is directly a URL, prioritize it
        if (location != null) {
            val trimmed = location.trim()
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                return trimmed
            }
        }

        // 2. Search for known video meeting urls in location first
        val locationLink = findKnownUrl(location)
        if (locationLink != null) return locationLink

        // 3. Search in description text
        return findKnownUrl(description)
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
