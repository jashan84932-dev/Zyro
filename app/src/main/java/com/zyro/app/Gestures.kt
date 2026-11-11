package com.zyro.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.util.Calendar
import java.util.Locale


/** Gestures: face-down silence, pocket detection and a double shake for the torch. */
object Gestures {
    @Volatile var faceDown = false
    @Volatile var inPocket = false

    var onFlipUp: (() -> Unit)? = null
    var onFaceDown: (() -> Unit)? = null
    /** A quick hand wave over the phone (close, then away again within a moment). */
    var onWave: (() -> Unit)? = null
    private var nearSince = 0L

    private var sm: SensorManager? = null
    private var accL: SensorEventListener? = null
    private var proxL: SensorEventListener? = null
    private var lightL: SensorEventListener? = null
    private var started = false

    private var downSince = 0L
    private var lastSpike = 0L
    private var torchOn = false
    private var near = false
    private var lux = 100f

    fun pocketActive(): Boolean = Prefs.pocketMode && inPocket
    fun quiet(): Boolean = Prefs.faceDownQuiet && faceDown

    fun start(ctx: Context, hands: Hands) {
        if (started) return
        val m = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc != null) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val x = e.values[0]
                    val y = e.values[1]
                    val z = e.values[2]
                    val now = System.currentTimeMillis()

                    // face down: the screen looks at the table
                    if (z < -8f && Math.abs(x) < 3f && Math.abs(y) < 3f) {
                        if (downSince == 0L) downSince = now
                        if (!faceDown && now - downSince > 1500) {
                            faceDown = true
                            onFaceDown?.invoke()
                        }
                    } else {
                        downSince = 0L
                        if (faceDown && z > -6f) {
                            faceDown = false
                            onFlipUp?.invoke()
                        }
                    }

                    // double shake = torch on / off
                    if (Prefs.shakeTorch && !faceDown) {
                        val mag = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                        if (Math.abs(mag - 9.81f) > 15f) {
                            val gap = now - lastSpike
                            if (gap in 250..900) {
                                lastSpike = 0L
                                torchOn = !torchOn
                                hands.torch(torchOn)
                            } else if (gap > 900 || lastSpike == 0L) {
                                lastSpike = now
                            }
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            accL = l
            m.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME)
        }
        val prox = m.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (prox != null) {
            val limit = Math.min(prox.maximumRange, 5f)
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    val wasNear = near
                    near = e.values[0] < limit
                    inPocket = near && lux < 8f
                    val now = System.currentTimeMillis()
                    if (near && !wasNear) nearSince = now
                    if (!near && wasNear && nearSince != 0L && now - nearSince < 700) {
                        nearSince = 0L
                        onWave?.invoke()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            proxL = l
            m.registerListener(l, prox, SensorManager.SENSOR_DELAY_NORMAL)
        }
        val light = m.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (light != null) {
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    lux = e.values[0]
                    inPocket = near && lux < 8f
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
            lightL = l
            m.registerListener(l, light, SensorManager.SENSOR_DELAY_NORMAL)
        }
        started = true
    }

    fun stop() {
        try {
            accL?.let { sm?.unregisterListener(it) }
            proxL?.let { sm?.unregisterListener(it) }
            lightL?.let { sm?.unregisterListener(it) }
        } catch (e: Exception) {
            // ignore
        }
        accL = null
        proxL = null
        lightL = null
        started = false
        faceDown = false
        inPocket = false
    }
}
