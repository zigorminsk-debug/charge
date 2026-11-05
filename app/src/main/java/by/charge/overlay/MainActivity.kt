package by.charge.overlay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import by.charge.overlay.nav.NavigatorLauncher
import by.charge.overlay.overlay.OverlayService
import by.charge.overlay.util.Permissions
import by.charge.overlay.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var btnOverlay: Button
    private lateinit var btnLocation: Button
    private lateinit var btnNotif: Button
    private lateinit var btnStart: Button

    private val locationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        radiusLabel = findViewById(R.id.radius_label)
        btnOverlay = findViewById(R.id.btn_overlay)
        btnLocation = findViewById(R.id.btn_location)
        btnNotif = findViewById(R.id.btn_notif)
        btnStart = findViewById(R.id.btn_start)

        btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnLocation.setOnClickListener {
            locationLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
        btnNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(NOTIFICATION_PERMISSION)
        }

        val seek = findViewById<SeekBar>(R.id.radius)
        seek.max = Prefs.MAX_RADIUS_KM - Prefs.MIN_RADIUS_KM
        seek.progress = Prefs.radiusKm(this) - Prefs.MIN_RADIUS_KM
        updateRadiusLabel(Prefs.radiusKm(this))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val km = progress + Prefs.MIN_RADIUS_KM
                updateRadiusLabel(km)
                if (fromUser) Prefs.setRadiusKm(this@MainActivity, km)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        btnStart.setOnClickListener {
            if (Permissions.hasLocation(this) && Permissions.canDrawOverlay(this)) {
                OverlayService.start(this)
                Toast.makeText(this, R.string.btn_start, Toast.LENGTH_SHORT).show()
            } else {
                refresh()
            }
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            OverlayService.stop(this)
        }
        findViewById<Button>(R.id.btn_navi).setOnClickListener {
            if (!NavigatorLauncher.openApp(this)) {
                Toast.makeText(this, R.string.navigator_missing, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun updateRadiusLabel(km: Int) {
        radiusLabel.text = getString(R.string.radius_label, km)
    }

    private fun refresh() {
        val location = Permissions.hasLocation(this)
        val overlay = Permissions.canDrawOverlay(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, NOTIFICATION_PERMISSION) == PackageManager.PERMISSION_GRANTED

        statusText.text = listOf(
            "${mark(overlay)} ${getString(R.string.perm_overlay)}",
            "${mark(location)} ${getString(R.string.perm_location)}",
            "${mark(notif)} ${getString(R.string.perm_notif)}",
        ).joinToString("\n")

        btnOverlay.isEnabled = !overlay
        btnLocation.isEnabled = !location
        btnNotif.isEnabled = !notif
        btnStart.isEnabled = location && overlay
    }

    private fun mark(ok: Boolean): String = if (ok) "✔" else "✘"

    companion object {
        private const val NOTIFICATION_PERMISSION = "android.permission.POST_NOTIFICATIONS"
    }
}
