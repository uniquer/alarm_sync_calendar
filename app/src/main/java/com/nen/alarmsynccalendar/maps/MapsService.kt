package com.nen.alarmsynccalendar.maps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nen.alarmsynccalendar.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

data class PlaceSuggestion(val placeId: String, val description: String)
data class PlaceDetails(val name: String, val lat: Double, val lng: Double)
data class AutocompleteResult(val suggestions: List<PlaceSuggestion>, val error: String? = null)
data class PlaceDetailsResult(val details: PlaceDetails?, val error: String? = null)

sealed class TravelResult {
    data class Found(val distanceKm: Double, val durationMinutes: Int) : TravelResult()
    /** Destination geocoded but no driving route exists (e.g. overseas). Permanent. */
    object NoRoute : TravelResult()
    /** The address text could not be geocoded at all — improper address in the event. */
    object InvalidAddress : TravelResult()
    /** Network/API failure — unknown result, worth retrying on a later sync. */
    object Unavailable : TravelResult()
}

/**
 * Thin wrapper around the Google Maps web services:
 * Places Autocomplete (start-location search), Place Details (lat/lng lookup)
 * and Distance Matrix (driving distance + duration to event locations).
 */
object MapsService {
    private const val KEY = BuildConfig.MAPS_API_KEY

    /**
     * Last Maps API error, observed by the UI while the app is active and
     * surfaced as a ticker (snackbar). Set to null after being shown.
     * Place search errors are returned inline instead (quiet mode).
     */
    val lastError = MutableStateFlow<String?>(null)

    // App identity sent as headers so package+fingerprint-restricted API keys
    // work with the Maps web services (they can't verify the app otherwise).
    @Volatile private var appPackage: String? = null
    @Volatile private var appCertSha1: String? = null

    fun init(context: Context) {
        if (appPackage != null) return
        appPackage = context.packageName
        appCertSha1 = try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            signatures?.firstOrNull()?.let { sig ->
                MessageDigest.getInstance("SHA-1").digest(sig.toByteArray())
                    .joinToString("") { String.format("%02X", it) }
            }
        } catch (e: Exception) {
            Log.w("MapsService", "Could not compute signing cert digest: ${e.message}")
            null
        }
    }

    private fun reportError(code: String, description: String) {
        lastError.value = "Maps API error [$code]: $description"
    }

    private fun describeStatus(status: String): String = when (status) {
        "REQUEST_DENIED" -> "Request denied — check the API key restrictions and enabled APIs"
        "OVER_QUERY_LIMIT", "OVER_DAILY_LIMIT" -> "API quota exceeded"
        "INVALID_REQUEST" -> "Invalid request"
        "UNKNOWN_ERROR" -> "Google server error — will retry"
        "NOT_FOUND" -> "Address could not be found"
        else -> "Unexpected error"
    }

    suspend fun autocomplete(query: String): AutocompleteResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val (json, error) = getJson("https://maps.googleapis.com/maps/api/place/autocomplete/json?input=$encoded&key=$KEY", quiet = true)
        if (json == null) return@withContext AutocompleteResult(emptyList(), error)
        val predictions = json.optJSONArray("predictions") ?: return@withContext AutocompleteResult(emptyList())
        val list = mutableListOf<PlaceSuggestion>()
        for (i in 0 until predictions.length()) {
            val p = predictions.getJSONObject(i)
            val placeId = p.optString("place_id").takeIf { it.isNotBlank() } ?: continue
            list.add(PlaceSuggestion(placeId, p.optString("description", placeId)))
        }
        AutocompleteResult(list)
    }

    suspend fun placeDetails(placeId: String): PlaceDetailsResult = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(placeId, "UTF-8")
        val (json, error) = getJson("https://maps.googleapis.com/maps/api/place/details/json?place_id=$encoded&fields=name,formatted_address,geometry&key=$KEY", quiet = true)
        if (json == null) return@withContext PlaceDetailsResult(null, error)
        val result = json.optJSONObject("result") ?: return@withContext PlaceDetailsResult(null, "No details returned for this place")
        val loc = result.optJSONObject("geometry")?.optJSONObject("location")
            ?: return@withContext PlaceDetailsResult(null, "No coordinates returned for this place")
        val name = result.optString("formatted_address").takeIf { it.isNotBlank() }
            ?: result.optString("name", "Selected location")
        PlaceDetailsResult(PlaceDetails(name, loc.getDouble("lat"), loc.getDouble("lng")))
    }

    suspend fun travelInfo(originLat: Double, originLng: Double, destination: String): TravelResult = withContext(Dispatchers.IO) {
        val dest = URLEncoder.encode(destination, "UTF-8")
        val (json, _) = getJson("https://maps.googleapis.com/maps/api/distancematrix/json?origins=$originLat,$originLng&destinations=$dest&mode=driving&key=$KEY")
        if (json == null) return@withContext TravelResult.Unavailable
        val element = json.optJSONArray("rows")?.optJSONObject(0)
            ?.optJSONArray("elements")?.optJSONObject(0)
            ?: return@withContext TravelResult.Unavailable
        when (val status = element.optString("status")) {
            "OK" -> {
                val meters = element.getJSONObject("distance").getLong("value")
                val seconds = element.getJSONObject("duration").getLong("value")
                TravelResult.Found(meters / 1000.0, ((seconds + 59) / 60).toInt())
            }
            "ZERO_RESULTS" -> {
                Log.i("MapsService", "No driving route to '$destination'")
                TravelResult.NoRoute
            }
            "NOT_FOUND" -> {
                Log.w("MapsService", "Address not recognized: '$destination'")
                reportError(status, "Event address not recognized: \"$destination\"")
                TravelResult.InvalidAddress
            }
            else -> {
                Log.w("MapsService", "Distance Matrix element status: $status for '$destination'")
                reportError(status, describeStatus(status))
                TravelResult.Unavailable
            }
        }
    }

    /**
     * Returns (json, null) on success or (null, errorText) on failure.
     * quiet=true returns the error to the caller only (for inline display);
     * otherwise it is also published to [lastError] for the UI ticker.
     */
    private fun getJson(urlString: String, quiet: Boolean = false): Pair<JSONObject?, String?> {
        return try {
            val conn = URL(urlString).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            appPackage?.let { conn.setRequestProperty("X-Android-Package", it) }
            appCertSha1?.let { conn.setRequestProperty("X-Android-Cert", it) }
            if (conn.responseCode != 200) {
                val msg = conn.responseMessage ?: "Could not reach Google Maps"
                Log.w("MapsService", "HTTP ${conn.responseCode} from Maps API")
                if (!quiet) reportError("HTTP ${conn.responseCode}", msg)
                return null to "[HTTP ${conn.responseCode}] $msg"
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val status = json.optString("status")
            if (status != "OK" && status != "ZERO_RESULTS") {
                val apiMessage = json.optString("error_message").takeIf { it.isNotBlank() } ?: describeStatus(status)
                Log.w("MapsService", "Maps API status: $status $apiMessage")
                if (!quiet) reportError(status, apiMessage)
                return null to "[$status] $apiMessage"
            }
            json to null
        } catch (e: Exception) {
            Log.w("MapsService", "Maps API call failed: ${e.message}")
            val msg = e.message ?: "Network error reaching Google Maps"
            if (!quiet) reportError("NETWORK", msg)
            null to "[NETWORK] $msg"
        }
    }
}
