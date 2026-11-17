//code for GPS service

package com.example.dundeemapper

//imports
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.location.LocationRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit

//i miss commaas from java
class ForegroundOnlyLocationService : Service() {

    //set variables / establish
    private var configureationChange = false
    private var serviceRunningInForeground = false
    private val localBinder = LocalBinder()
    private lateinit var notificationManager: NotificationManager

    //review some variables
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    ///Location storage variable
    private var currentLocation: Location? = null

    override fun onCreate() {
        Log.d(TAG, "onCreate()")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        //Check client #robust
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        //create a location request
        locationRequest = LocationRequest.create().apply {
            i = TimeUnit.SECONDS.toMillis(60)
            fastedi = TimeUnit.SECONDS.toMillis(30)
            waitTime = TimeUnit.MINUTES.toMillis(2)

            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        //start locationcallback]
        locationCallBack = object : LocationCallBack() {
            override fun onLocationResult(location: LocationResult) {
                super.onLocationResult(locationResult)

                val intent = intent(ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
                intent.putExtra(EXTRA_LOCATION, currentLocation)
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

                //update notifications
                if (serviceRunningInForeground){
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        generateNotification(currentLocation)
                    )
                }
            }
        }

        //on start commands (self explanatory)
        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int{
            Log.d(TAG, "onStartCommand()")

            val cancelLocationTrackingFromNotification = intent.getBooleanExtra(EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION, false)

            if (cancelLocationTrackingFromNotification) {
                unsubscribeToLocationUpdates()
                stopself()
            }

            return START_NOT_STICKY
            }
        }
    }
}