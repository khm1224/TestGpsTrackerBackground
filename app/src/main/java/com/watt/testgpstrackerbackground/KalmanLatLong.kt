package com.watt.testgpstrackerbackground

/**
 * Created by khm on 2022-01-05.
 */

class KalmanLatLong(private val Q_metres_per_second: Float) {
    private val MinAccuracy = 1f
    private var TimeStamp_milliseconds: Long = 0
    private var lat = 0.0
    private var lng = 0.0
    // P matrix.  Negative means object uninitialised.  NB: units irrelevant, as long as same units used throughout
    private var variance: Float = -1.0f

    fun get_TimeStamp(): Long {
        return TimeStamp_milliseconds
    }

    fun get_lat(): Double {
        return lat
    }

    fun get_lng(): Double {
        return lng
    }

    fun get_accuracy(): Float {
        return Math.sqrt(variance.toDouble()).toFloat()
    }

    fun setState(lat: Double, lng: Double, accuracy: Float, TimeStamp_milliseconds: Long) {
        this.lat = lat
        this.lng = lng
        variance = accuracy * accuracy
        this.TimeStamp_milliseconds = TimeStamp_milliseconds
    }

    /// <summary>
    /// Kalman filter processing for lattitude and longitude
    /// </summary>
    /// <param name="lat_measurement_degrees">new measurement of lattidude</param>
    /// <param name="lng_measurement">new measurement of longitude</param>
    /// <param name="accuracy">measurement of 1 standard deviation error in metres</param>
    /// <param name="TimeStamp_milliseconds">time of measurement</param>
    /// <returns>new state</returns>
    fun process(
        lat_measurement: Double,
        lng_measurement: Double,
        accuracy_: Float,
        TimeStamp_milliseconds: Long
    ) {
        var accuracy = accuracy_
        if (accuracy < MinAccuracy) accuracy = MinAccuracy
        if (variance < 0) {
            // if variance < 0, object is uninitialised, so initialise with current values
            this.TimeStamp_milliseconds = TimeStamp_milliseconds
            lat = lat_measurement
            lng = lng_measurement
            variance = accuracy * accuracy
        } else {
            // else apply Kalman filter methodology
            val TimeInc_milliseconds = TimeStamp_milliseconds - this.TimeStamp_milliseconds
            if (TimeInc_milliseconds > 0) {
                // time has moved on, so the uncertainty in the current position increases
                variance += TimeInc_milliseconds * Q_metres_per_second * Q_metres_per_second / 1000
                this.TimeStamp_milliseconds = TimeStamp_milliseconds
                // TO DO: USE VELOCITY INFORMATION HERE TO GET A BETTER ESTIMATE OF CURRENT POSITION
            }

            // Kalman gain matrix K = Covarariance * Inverse(Covariance + MeasurementVariance)
            // NB: because K is dimensionless, it doesn't matter that variance has different units to lat and lng
            val K = variance / (variance + accuracy * accuracy)
            // apply K
            lat += K * (lat_measurement - lat)
            lng += K * (lng_measurement - lng)
            // new Covarariance  matrix is (IdentityMatrix - K) * Covarariance
            variance = (1 - K) * variance
        }
    }


}