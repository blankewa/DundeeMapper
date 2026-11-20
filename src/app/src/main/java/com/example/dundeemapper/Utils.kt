package com.example.dundeemapper

import android.content.Context
import android.location.Location
import androidx.core.content.edit

/** What the location service is currently doing. */
enum class TrackingStatus(val label: String) {
    STOPPED("Tracking stopped"),
    SEARCHING("Searching for location…"),
    ACTIVE("Location found"),
    LOCATION_OFF("Location is turned off on this device"),
    PERMISSION_MISSING("Location permission missing")
}
//dundee craggications
private const val DUNDEE_LATITUDE = 56.4620
private const val DUNDEE_LONGITUDE = -2.9707
private const val DUNDEE_RADIUS_METERS = 8000f

const val NOT_IN_DUNDEE_TEXT = "you aint in scumdee mate"

fun Location.isInDundee(): Boolean {
    val results = FloatArray(1)
    Location.distanceBetween(
        latitude, longitude,
        DUNDEE_LATITUDE, DUNDEE_LONGITUDE,
        results
    )
    return results[0] <= DUNDEE_RADIUS_METERS
}

fun Location?.toText(): String {
    return if (this != null) {
        "($latitude, $longitude)"
    } else {
        "location unknown"
    }
}

internal object SharedPreferenceUtil {
    const val KEY_FOREGROUND_ENABLED = "tracking_foreground_location"

    fun getLocationTrackingPref(context: Context): Boolean {
        return context.getSharedPreferences(
            context.getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        ).getBoolean(KEY_FOREGROUND_ENABLED, false)
    }

    fun saveLocationTrackingPref(
        context: Context,
        requestingLocationUpdates: Boolean
    ) {
        context.getSharedPreferences(
            context.getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        ).edit {
            putBoolean(KEY_FOREGROUND_ENABLED, requestingLocationUpdates)
        }
    }
}
