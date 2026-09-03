package com.lumora

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lumora.data.AccountStore
import com.lumora.data.DeviceIdentity
import com.lumora.data.DeviceValidator
import com.lumora.model.AccountConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LockedActivity : AppCompatActivity() {

    private lateinit var loading: ProgressBar
    private lateinit var infoContainer: LinearLayout
    private lateinit var statusLabel: TextView
    private lateinit var messageView: TextView
    private lateinit var macValue: TextView
    private lateinit var keyValue: TextView
    private lateinit var retryButton: Button
    private lateinit var resetButton: Button
    private lateinit var versionView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_locked)

        loading = findViewById(R.id.lockedLoading)
        infoContainer = findViewById(R.id.lockedInfoContainer)
        statusLabel = findViewById(R.id.lockedStatusLabel)
        messageView = findViewById(R.id.lockedMessage)
        macValue = findViewById(R.id.lockedMacValue)
        keyValue = findViewById(R.id.lockedKeyValue)
        retryButton = findViewById(R.id.lockedRetryButton)
        resetButton = findViewById(R.id.lockedResetButton)
        versionView = findViewById(R.id.lockedVersion)

        versionView.text = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName} (build ${info.versionCode})"
        } catch (e: Exception) { "" }

        retryButton.setOnClickListener { runValidation() }

        if (BuildConfig.DEBUG) {
            resetButton.visibility = Button.VISIBLE
            resetButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.sett_reset_device))
                    .setMessage("Se borrará el registro del dispositivo y se reiniciará.")
                    .setPositiveButton("Restablecer") { _, _ ->
                        getSharedPreferences("device_identity", MODE_PRIVATE).edit().clear().apply()
                        recreate()
                    }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }

        runValidation()
    }

    private fun runValidation() {
        loading.visibility = ProgressBar.VISIBLE
        infoContainer.visibility = LinearLayout.GONE
        retryButton.visibility = Button.GONE

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DeviceValidator(this@LockedActivity).validate()
            }

            loading.visibility = ProgressBar.GONE

            if (result == null) {
                showNetworkError()
                return@launch
            }

            if (result.valid && !result.providerUrl.isNullOrBlank()) {
                val prefs = getSharedPreferences("iptv_prefs", MODE_PRIVATE)
                val existing = AccountStore.load(prefs).firstOrNull()
                val id = existing?.id ?: AccountStore.newId()
                val providerType = result.providerType ?: "m3u"
                AccountStore.upsert(prefs, AccountConfig(
                    id = id,
                    type = providerType,
                    name = "Proveedor",
                    url = result.providerUrl,
                    username = if (providerType == "xtream") result.username else null,
                    password = if (providerType == "xtream") result.password else null
                ))
                AccountStore.setActiveAccount(prefs, id)
                startActivity(Intent(this@LockedActivity, MainActivity::class.java))
                finish()
                return@launch
            }

            showLocked(result.valid, result.expired, result.message)
        }
    }

    private fun showLocked(valid: Boolean, expired: Boolean, message: String?) {
        macValue.text = DeviceIdentity.getDeviceId(this) ?: "---"
        keyValue.text = DeviceIdentity.getKey(this) ?: "---"

        statusLabel.text = if (valid) {
            getString(R.string.locked_pending)
        } else if (expired) {
            getString(R.string.locked_expired)
        } else {
            getString(R.string.locked_not_active)
        }

        messageView.text = message ?: getString(R.string.locked_no_info)

        infoContainer.visibility = LinearLayout.VISIBLE
    }

    private fun showNetworkError() {
        macValue.text = DeviceIdentity.getDeviceId(this) ?: "---"
        keyValue.text = DeviceIdentity.getKey(this) ?: "---"
        statusLabel.text = getString(R.string.locked_no_connection)
        messageView.text = getString(R.string.locked_retry_message)
        retryButton.visibility = Button.VISIBLE
        infoContainer.visibility = LinearLayout.VISIBLE
    }
}