package com.watt.testgpstrackerbackground

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
/**
 * Created by khm on 2021-08-09.
 */

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("AlarmReceiver","----- onReceive in AlarmReceiver ")
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val undeadServiceIntent = Intent(context, GpsTrackerService::class.java)
            context?.startForegroundService(undeadServiceIntent)
        }else{
            val undeadServiceIntent = Intent(context, GpsTrackerService::class.java)
            context?.startService(undeadServiceIntent)
        }


    }
}