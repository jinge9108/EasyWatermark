package me.rosuh.easywatermark.utils

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LocationHelper(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): String {
        return suspendCoroutine { continuation ->
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    val address = getAddress(location.latitude, location.longitude)
                    continuation.resume(address)
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {
                    locationManager.removeUpdates(this)
                    continuation.resume("Unknown Location")
                }
            }

            try {
                val provider = if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    LocationManager.NETWORK_PROVIDER
                } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    LocationManager.GPS_PROVIDER
                } else {
                    null
                }

                if (provider != null) {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener)
                } else {
                    continuation.resume("Location Disabled")
                }
            } catch (e: Exception) {
                continuation.resume("Location Error")
            }
        }
    }

    private fun getAddress(latitude: Double, longitude: Double): String {
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val city = address.locality ?: ""
                val adminArea = address.adminArea ?: ""
                val featureName = address.featureName ?: ""
                "$adminArea $city $featureName".trim()
            } else {
                "Unknown Address"
            }
        } catch (e: Exception) {
            "Address Error"
        }
    }
}
