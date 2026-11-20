//code for GPS service
//code for GPS service

package com.example.dundeemapper

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class ForegroundOnlyLocationService : Service() {

    private var configurationChange = false
    @Volatile
    private var serviceRunningInForeground = false
    private val localBinder = LocalBinder()

    private lateinit var notificationManager: NotificationManager
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var currentLocation: Location? = null

    // Reverse geocoding (location -> city name). Runs off the main thread because
    // Geocoder.getFromLocation() blocks on the network.
    private val geocoderExecutor = Executors.newSingleThreadExecutor()
    private val geocodingInFlight = AtomicBoolean(false)
    @Volatile
    private var currentCity: String? = null
    private var lastGeocodedLocation: Location? = null
    private var lastGeocodeAttemptMs = 0L

    /** Current tracking state. The activity can read this when it binds. */
    var status: TrackingStatus = TrackingStatus.STOPPED
        private set

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")

        notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        fusedLocationProviderClient =
            LocationServices.getFusedLocationProviderClient(this)

        // No setMaxUpdateDelayMillis(): that allows Google Play services to batch updates
        // for minutes, which made the first fix (and the notification) very slow to appear.
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            TimeUnit.SECONDS.toMillis(10)
        )
            .setMinUpdateIntervalMillis(TimeUnit.SECONDS.toMillis(5))
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return
                publishLocation(location)
            }

            // Fires when Play services can or can't currently determine a location
            // (e.g. no GPS signal, or location switched off in system settings).
            override fun onLocationAvailability(availability: LocationAvailability) {
                Log.d(TAG, "onLocationAvailability(): ${availability.isLocationAvailable}")
                if (status == TrackingStatus.STOPPED) return
                updateStatus(
                    when {
                        !isSystemLocationEnabled() -> TrackingStatus.LOCATION_OFF
                        availability.isLocationAvailable && currentLocation != null -> TrackingStatus.ACTIVE
                        else -> TrackingStatus.SEARCHING
                    }
                )
            }
        }
    }

    private fun isSystemLocationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    private fun updateStatus(newStatus: TrackingStatus) {
        if (status == newStatus) return
        Log.d(TAG, "status: $status -> $newStatus")
        status = newStatus

        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(
            Intent(ACTION_STATUS_BROADCAST).apply {
                putExtra(EXTRA_STATUS, newStatus.name)
            }
        )

        if (serviceRunningInForeground) {
            notificationManager.notify(NOTIFICATION_ID, generateNotification(currentLocation))
        }
    }

    /**
     * Looks up the city for [location]. Skipped if we already have a city for roughly this
     * spot, if a lookup is already running, or if we tried very recently (so a failing
     * lookup, e.g. offline, isn't retried on every location update).
     */
    private fun updateCity(location: Location) {
        val last = lastGeocodedLocation
        if (currentCity != null && last != null &&
            location.distanceTo(last) < CITY_REFRESH_DISTANCE_METERS
        ) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastGeocodeAttemptMs < GEOCODE_RETRY_INTERVAL_MS) return
        if (!Geocoder.isPresent()) return
        if (!geocodingInFlight.compareAndSet(false, true)) return
        lastGeocodeAttemptMs = now

        geocoderExecutor.execute {
            try {
                val geocoder = Geocoder(applicationContext, Locale.getDefault())
                @Suppress("DEPRECATION")
                val address = geocoder
                    .getFromLocation(location.latitude, location.longitude, 1)
                    ?.firstOrNull()

                val city = address?.locality
                    ?: address?.subAdminArea
                    ?: address?.adminArea

                if (city != null) {
                    currentCity = city
                    lastGeocodedLocation = location
                    Log.d(TAG, "Resolved city: $city")
                    if (serviceRunningInForeground) {
                        notificationManager.notify(
                            NOTIFICATION_ID,
                            generateNotification(currentLocation)
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Reverse geocoding failed", e)
            } finally {
                geocodingInFlight.set(false)
            }
        }
    }

    private fun publishLocation(location: Location) {
        currentLocation = location
        updateStatus(TrackingStatus.ACTIVE)
        updateCity(location)

        val broadcastIntent =
            Intent(ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST).apply {
                putExtra(EXTRA_LOCATION, location)
            }
        LocalBroadcastManager.getInstance(applicationContext)
            .sendBroadcast(broadcastIntent)

        if (serviceRunningInForeground) {
            notificationManager.notify(
                NOTIFICATION_ID,
                generateNotification(location)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        if (intent?.getBooleanExtra(
                EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION,
                false
            ) == true
        ) {
            unsubscribeToLocationUpdates()
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "onBind()")
        leaveForeground()
        configurationChange = false
        return localBinder
    }

    override fun onRebind(intent: Intent) {
        super.onRebind(intent)
        Log.d(TAG, "onRebind()")
        leaveForeground()
        configurationChange = false
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.d(TAG, "onUnbind()")

        if (!configurationChange && SharedPreferenceUtil.getLocationTrackingPref(this)) {
            enterForeground()
        }

        // Return true so onRebind() is called when the activity binds again.
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        geocoderExecutor.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationChange = true
    }

    fun subscribeToLocationUpdates() {
        Log.d(TAG, "subscribeToLocationUpdates()")
        SharedPreferenceUtil.saveLocationTrackingPref(this, true)

        // Keeps the service alive after the activity unbinds.
        startService(Intent(applicationContext, ForegroundOnlyLocationService::class.java))

        // We're now attempting to get a fix (unless location is switched off entirely).
        updateStatus(
            if (isSystemLocationEnabled()) TrackingStatus.SEARCHING
            else TrackingStatus.LOCATION_OFF
        )

        try {
            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )

            // Seed with the last known fix so we don't sit on "waiting for locations"
            // until the first live update arrives.
            fusedLocationProviderClient.lastLocation.addOnSuccessListener { last ->
                if (last != null && currentLocation == null) {
                    publishLocation(last)
                }
            }
        } catch (e: SecurityException) {
            SharedPreferenceUtil.saveLocationTrackingPref(this, false)
            updateStatus(TrackingStatus.PERMISSION_MISSING)
            Log.e(TAG, "Location permission is missing; cannot request updates.", e)
        }
    }

    fun unsubscribeToLocationUpdates() {
        Log.d(TAG, "unsubscribeToLocationUpdates()")

        try {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Location callback removed")
                        updateStatus(TrackingStatus.STOPPED)
                        leaveForeground()
                        stopSelf()
                    } else {
                        Log.e(TAG, "Could not remove location callback", task.exception)
                    }
                }

            SharedPreferenceUtil.saveLocationTrackingPref(this, false)
        } catch (e: SecurityException) {
            SharedPreferenceUtil.saveLocationTrackingPref(this, true)
            Log.e(TAG, "Location permission is missing; cannot remove updates.", e)
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Promotes the service to a foreground service. On Android 14+ this throws unless the
     * service declares foregroundServiceType="location" in the manifest AND the location
     * permission is currently granted, so guard against both rather than crash.
     */
    private fun enterForeground() {
        if (!hasFineLocationPermission()) {
            Log.w(TAG, "Location permission missing; not starting foreground service")
            SharedPreferenceUtil.saveLocationTrackingPref(this, false)
            return
        }

        try {
            val notification = generateNotification(currentLocation)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            serviceRunningInForeground = true
        } catch (e: Exception) {
            // SecurityException, MissingForegroundServiceTypeException,
            // ForegroundServiceStartNotAllowedException, etc.
            Log.e(TAG, "Could not start foreground service", e)
            serviceRunningInForeground = false
        }
    }

    private fun leaveForeground() {
        if (serviceRunningInForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        serviceRunningInForeground = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun generateNotification(location: Location?): Notification {
        // Only show coordinates while we actually have a live fix; otherwise say what's happening.
        val notificationText =
            if (status == TrackingStatus.ACTIVE && location != null) {
                listOfNotNull(currentCity, location.toText()).joinToString(" · ")
            } else {
                status.label
            }
        val titleText = getString(R.string.app_name)

        val launchActivityIntent = Intent(this, MainActivity::class.java)
        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ForegroundOnlyLocationService::class.java).apply {
            putExtra(EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION, true)
        }
        val servicePendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(notificationText)
                    .setBigContentTitle(titleText)
            )
            .setContentTitle(titleText)
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOngoing(true)
            // Without this, every update re-triggers the sound/vibration defaults.
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                R.drawable.ic_launch,
                getString(R.string.launch_activity),
                activityPendingIntent
            )
            .addAction(
                R.drawable.ic_cancel,
                getString(R.string.stop_location_updates_button_text),
                servicePendingIntent
            )
            .build()
    }

    inner class LocalBinder : Binder() {
        val service: ForegroundOnlyLocationService
            get() = this@ForegroundOnlyLocationService
    }

    companion object {
        private const val TAG = "ForegroundOnlyLocationService"
        private const val PACKAGE_NAME = "com.example.dundeemapper"

        const val ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST =
            "$PACKAGE_NAME.action.FOREGROUND_ONLY_LOCATION_BROADCAST"
        const val EXTRA_LOCATION = "$PACKAGE_NAME.extra.LOCATION"

        const val ACTION_STATUS_BROADCAST = "$PACKAGE_NAME.action.STATUS_BROADCAST"
        const val EXTRA_STATUS = "$PACKAGE_NAME.extra.STATUS"

        private const val EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION =
            "$PACKAGE_NAME.extra.CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION"
        private const val CITY_REFRESH_DISTANCE_METERS = 1000f
        private const val GEOCODE_RETRY_INTERVAL_MS = 30_000L
        private const val NOTIFICATION_ID = 12345678
        private const val NOTIFICATION_CHANNEL_ID = "while_in_use_channel_01"
    }
}


