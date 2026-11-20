package com.zyro.app

import android.content.Context
import android.net.ConnectivityManager

/** Is the internet there? When it is not, Zyro still does the local jobs (calls, alarms, apps, torch...) and says so plainly. */
object Net {
    const val OFFLINE_MSG = "अभी नेट नहीं है, इसलिए AI वाले जवाब नहीं दे पाऊँगा। कॉल, अलार्म, टाइमर, ऐप खोलना, टॉर्च और वॉल्यूम जैसे काम अभी भी कर सकता हूँ।"

    fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        val ni = cm.activeNetworkInfo
        @Suppress("DEPRECATION")
        ni != null && ni.isConnected
    } catch (e: Exception) { true }
}
