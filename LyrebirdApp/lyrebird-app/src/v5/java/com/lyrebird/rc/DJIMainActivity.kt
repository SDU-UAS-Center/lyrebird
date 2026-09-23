package com.lyrebird.rc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import com.lyrebird.rc.databinding.ActivityMainBinding
import com.lyrebird.rc.mavlink.MavlinkEndpointConfig
import com.lyrebird.rc.mavlink.MavlinkSystemId
import com.lyrebird.rc.models.BaseMainActivityVm
import com.lyrebird.rc.models.MSDKInfoVm
import com.lyrebird.rc.models.MSDKManagerVM
import com.lyrebird.rc.models.VersionInfoVm
import com.lyrebird.rc.models.globalViewModels
import com.lyrebird.rc.server.DeviceSessionLeaseRegistry
import com.lyrebird.rc.settings.LyrebirdOnboarding
import com.lyrebird.rc.util.Helper
import com.lyrebird.rc.util.NetworkUtils
import com.lyrebird.rc.util.ToastUtils
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.v5.et.create
import dji.v5.et.get
import dji.v5.utils.common.LogUtils
import dji.v5.utils.common.PermissionUtil
import dji.v5.utils.common.StringUtils
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.addTo
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/2/10
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
abstract class DJIMainActivity : AppCompatActivity() {

    val tag: String = LogUtils.getTag(this)

    /** The permissions modal is offered once per process: a denial is an answer, not a nag. */
    private var permissionsOffered = false

    /** The open permissions modal, so a permission result refreshes its rows in place. */
    private var permissionsDialog: AlertDialog? = null

    private val baseMainActivityVm: BaseMainActivityVm by viewModels()
    private val msdkInfoVm: MSDKInfoVm by viewModels()
    private val msdkManagerVM: MSDKManagerVM by globalViewModels()
    private val versionInfoVm: VersionInfoVm by viewModels()
    private lateinit var binding: ActivityMainBinding
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val disposable = CompositeDisposable()

    abstract fun prepareUxActivity()

    abstract fun prepareTestingToolsActivity()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateLyrebirdBuildInfo()

        // 有一些手机从系统桌面进入的时候可能会重启main类型的activity
        // 需要校验这种情况，业界标准做法，基本所有app都需要这个
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && Intent.ACTION_MAIN == intent.action) {

                finish()
                return

        }

        configureImmersiveMode()

        if (DeviceSessionLeaseRegistry.blockedByAnotherSession) {
            // This process did not initialise the DJI SDK (see DJIApplication): another Lyrebird
            // app owns the device session. Every view below reaches into the SDK, and DJI
            // answers with "SDKManager is not initialized" rather than a default — so none of it
            // is built, and the operator is told who owns the device instead of watching the app
            // crash on its own launch screen.
            showSessionBlockedMessage()
            return
        }

        binding.permissionsButton.setOnClickListener { showPermissionsDialog() }
        updatePermissionsButton()
        initMSDKInfoView()
        observeSDKManager()
        observeSdkVersionAvailability()
        checkPermissionAndRequest()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (DeviceSessionLeaseRegistry.blockedByAnotherSession) return
        updatePermissionsButton()
    }

    override fun onResume() {
        super.onResume()
        updateLyrebirdBuildInfo()
        configureImmersiveMode()
        if (DeviceSessionLeaseRegistry.blockedByAnotherSession) return
        updatePermissionsButton()
        if (startupFeaturesOff() > 0) offerPermissionsOnce()
        // Coming back from a Settings grant ("All files access") changes a row's state; rebuild
        // the modal so it shows the new one instead of a stale chip.
        if (permissionsDialog != null) showPermissionsDialog()
    }

    private fun configureImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    private fun handleAfterPermissionPermitted() {
        prepareTestingToolsActivity()
    }

    @SuppressLint("SetTextI18n")
    private fun initMSDKInfoView() {
        msdkInfoVm.msdkInfo.observe(this) {
            binding.textViewVersion.text = StringUtils.getResStr(R.string.sdk_version, it.SDKVersion + " " + it.buildVer)
            binding.textViewProductName.text = StringUtils.getResStr(R.string.product_name, it.productType.name)
            binding.textViewPackageProductCategory.text = StringUtils.getResStr(R.string.package_product_category, it.packageProductCategory)
            binding.textViewIsDebug.text = StringUtils.getResStr(R.string.is_sdk_debug, it.isDebug)
            binding.textCoreInfo.text = it.coreInfo.toString()
        }

        binding.viewBaseInfo.setOnClickListener {
            baseMainActivityVm.doPairing {
                showToast(it)
            }
        }
    }

    protected fun updateLyrebirdBuildInfo() {
        val preferences = getSharedPreferences(LYREBIRD_PREFS_NAME, MODE_PRIVATE)
        val droneName = preferences.getString(LYREBIRD_PREF_DRONE_NAME, LYREBIRD_DEFAULT_DRONE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: LYREBIRD_DEFAULT_DRONE_NAME
        val configuredSystemId = preferences.getInt(
            MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID
        )
        val systemId = MavlinkSystemId.resolve(configuredSystemId, currentDroneSerial() ?: "UNKNOWN")

        binding.layoutLyrebirdStats.removeAllViews()
        addStatGridRow(
            binding.layoutLyrebirdStats,
            "Drone name", droneName, { promptDroneIdentity(preferences) },
            "Vehicle sysid", "V$systemId", { promptDroneIdentity(preferences) }
        )
        addStatGridRow(
            binding.layoutLyrebirdStats,
            "Wi-Fi network", currentWifiSsid() ?: "Not connected", null,
            "Phone IP", NetworkUtils.getDeviceIpAddress() ?: "Unavailable", null
        )
        addSectionHeader(binding.layoutLyrebirdStats, "GIT INFO")

        updateDroneSerialInfo()
        binding.textViewLyrebirdGitInfo.text = buildGitInfoText()
    }

    private fun buildGitInfoText(): CharSequence {
        val text = SpannableStringBuilder()
        fun boldLabel(label: String) {
            val start = text.length
            text.append(label)
            text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        boldLabel("Built ")
        text.append(formatBuildTimeForPhone())
        text.append('\n')
        boldLabel("Git ")
        text.append(BuildConfig.LYREBIRD_GIT_SHA).append(" · ").append(BuildConfig.LYREBIRD_GIT_STATE)
        text.append('\n')
        boldLabel("Version ")
        text.append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE.toString()).append(')')
        return text
    }

    /** Two stat cells side by side, forming one row of a 2x2 grid of drone/network facts. */
    private fun addStatGridRow(
        container: LinearLayout,
        leftTitle: String,
        leftDetail: String,
        leftClick: (() -> Unit)?,
        rightTitle: String,
        rightDetail: String,
        rightClick: (() -> Unit)?
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dpToPx(5) }
        }
        row.addView(
            buildStatCell(leftTitle, leftDetail, leftClick).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dpToPx(4) }
            }
        )
        row.addView(
            buildStatCell(rightTitle, rightDetail, rightClick).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dpToPx(4) }
            }
        )
        container.addView(row)
    }

    private fun buildStatCell(title: String, detail: String, onClick: (() -> Unit)?): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { handler -> setOnClickListener { handler() } }
            addView(TextView(this@DJIMainActivity).apply {
                text = title.uppercase(Locale.getDefault())
                setTextColor(ContextCompat.getColor(this@DJIMainActivity, R.color.lyrebird_orange))
                textSize = 10f
                setTypeface(ResourcesCompat.getFont(this@DJIMainActivity, R.font.space_grotesk), Typeface.BOLD)
                letterSpacing = 0.05f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(this@DJIMainActivity).apply {
                text = detail.ifBlank { "Unavailable" }
                setTextColor(ContextCompat.getColor(this@DJIMainActivity, R.color.lyrebird_text))
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@DJIMainActivity, R.font.dm_sans)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    /** A single-row labeled divider, e.g. "GIT INFO", matching FlightDeckActivity's cockpit section headers. */
    private fun addSectionHeader(container: LinearLayout, label: String) {
        container.addView(TextView(this).apply {
            text = label
            setTextColor(ContextCompat.getColor(this@DJIMainActivity, R.color.lyrebird_orange))
            textSize = 11f
            setTypeface(ResourcesCompat.getFont(this@DJIMainActivity, R.font.space_grotesk), Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(22)
            ).apply { topMargin = dpToPx(4) }
        })
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Best-effort synchronous serial read, mirroring FlightDeckActivity's sysid-key derivation. */
    private fun currentDroneSerial(): String? = try {
        FlightControllerKey.KeySerialNumber.create().get("").trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    /** Show the aircraft DJI serial in its own row, under the model; hidden until it's readable. */
    private fun updateDroneSerialInfo() {
        val serial = currentDroneSerial()
        binding.textViewSerialNumber.visibility = if (serial != null) View.VISIBLE else View.GONE
        if (serial != null) {
            binding.textViewSerialNumber.text = StringUtils.getResStr(R.string.serial_number, serial)
        }
    }

    /**
     * Best-effort connected Wi-Fi SSID read.
     *
     * For apps targeting API 33+, the SSID from NetworkCapabilities.transportInfo is redacted to
     * "<unknown ssid>" (or null) even when NEARBY_WIFI_DEVICES is granted, and activeNetwork can
     * point at cellular while the phone is also joined to a WiFi network. WifiManager.connectionInfo
     * is the reliable source of the SSID of the currently connected network; fall back to scanning
     * all networks for a WiFi transport otherwise.
     */
    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String? {
        try {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            sanitizeSsid(wifiManager?.connectionInfo?.ssid)?.let { return it }
        } catch (_: SecurityException) {
            // Not granted -- fall through to the transportInfo scan below.
        }

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val ssid = (capabilities.transportInfo as? android.net.wifi.WifiInfo)?.ssid
            sanitizeSsid(ssid)?.let { return it }
        }
        return null
    }

    private fun sanitizeSsid(raw: String?): String? =
        raw?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }

    private fun promptDroneIdentity(preferences: SharedPreferences) {
        val currentName = preferences.getString(LYREBIRD_PREF_DRONE_NAME, LYREBIRD_DEFAULT_DRONE_NAME)
            ?: LYREBIRD_DEFAULT_DRONE_NAME
        val currentSysId = preferences.getInt(
            MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID
        )
        LyrebirdIdentityDialog.show(
            this,
            "Drone identity",
            getString(R.string.lyrebird_identity_subtitle),
            currentName,
            currentSysId,
            onSave = { name, sysId ->
                if (name != null) {
                    preferences.edit().putString(LYREBIRD_PREF_DRONE_NAME, name).apply()
                }
                preferences.edit().putInt(MavlinkEndpointConfig.PREF_SYSTEM_ID, sysId).apply()
                updateLyrebirdBuildInfo()
            }
        )
    }

    private fun observeSdkVersionAvailability() {
        versionInfoVm.listenLatestVersionInfo()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (latest, isNewer) ->
                binding.textViewNewVersionBadge.visibility = if (isNewer) View.VISIBLE else View.GONE
                binding.textViewNewVersionBadge.setOnClickListener {
                    Helper.startBrowser(this, StringUtils.getResStr(R.string.release_node_url))
                }
            }, {})
            .addTo(disposable)
        versionInfoVm.refreshLatestVersionInfo()
    }

    private fun formatBuildTimeForPhone(): String = runCatching {
        val sourceFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val phoneFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
        phoneFormat.format(checkNotNull(sourceFormat.parse(BuildConfig.LYREBIRD_BUILD_TIME)))
    }.getOrDefault(BuildConfig.LYREBIRD_BUILD_TIME)

    private fun observeSDKManager() {
        msdkManagerVM.lvRegisterState.observe(this) { resultPair ->
            val statusText: String?
            if (resultPair.first) {
                ToastUtils.showToast("Register Success")
                statusText = StringUtils.getResStr(this, R.string.registered)
                msdkInfoVm.initListener()
                updateDroneSerialInfo()
                handler.postDelayed({
                    prepareUxActivity()
                }, 5000)
            } else {
                showToast("Register Failure: ${resultPair.second}")
                statusText = StringUtils.getResStr(this, R.string.unregistered)
            }
            binding.textViewRegistered.text = StringUtils.getResStr(R.string.registration_status, statusText)
        }

        msdkManagerVM.lvProductConnectionState.observe(this) { resultPair ->
            showToast("Product: ${resultPair.second} ,ConnectionState:  ${resultPair.first}")
        }

        msdkManagerVM.lvProductChanges.observe(this) { productId ->
            showToast("Product: $productId Changed")
        }

        msdkManagerVM.lvInitProcess.observe(this) { processPair ->
            showToast("Init Process event: ${processPair.first.name}")
        }

        msdkManagerVM.lvDBDownloadProgress.observe(this) { resultPair ->
            showToast("Database Download Progress current: ${resultPair.first}, total: ${resultPair.second}")
        }
    }

    private fun showToast(content: String) {
        ToastUtils.showToast(content)

    }


    fun <T> enableFlightDeck(cl: Class<T>) {
        enableShowCaseButton(binding.flightDeckButton, cl)
    }

    /**
     * Opens the default layout if its showcase button is currently accessible (enabled).
     * Returns true when the default layout was opened.
     */
    fun openFlightDeckIfAccessible(): Boolean {
        if (!binding.flightDeckButton.isEnabled) {
            return false
        }
        binding.flightDeckButton.performClick()
        return true
    }

    fun <T> enableWidgetList(cl: Class<T>) {
        enableShowCaseButton(binding.widgetListButton, cl)
    }

    fun <T> enableTestingTools(cl: Class<T>) {
        enableShowCaseButton(binding.testingToolButton, cl)
    }

    private fun <T> enableShowCaseButton(view: View, cl: Class<T>) {
        view.isEnabled = true
        view.setOnClickListener {
            Intent(this, cl).also {
                startActivity(it)
            }
        }
    }

    private fun checkPermissionAndRequest() {
        // Nothing is gated on a permission: each one switches off only its own feature (no
        // location: the telemetry carries no RC position; no files: media download; no
        // microphone: the megaphone cannot record). The modal is offered because those features
        // are worth having, not because the app is blocked without them.
        handleAfterPermissionPermitted()
        if (startupFeaturesOff() > 0) offerPermissionsOnce()
    }

    /** One row of the permissions modal: what it is, why the app asks, which grants back it. */
    private class PermissionRow(
        val titleRes: Int,
        val whyRes: Int,
        val permissions: List<String>,
        /** False for the microphone: the megaphone asks for it on record, so it never nags. */
        val offerAtStartup: Boolean = true,
        /**
         * True for "All files access" on Android 11+: Android grants it in Settings, and its
         * state is read from the environment rather than from a runtime permission.
         */
        val settingsManaged: Boolean = false,
    )

    /** Whether this row is completely satisfied, including the Settings-managed grant. */
    private fun isRowGranted(row: PermissionRow): Boolean =
        if (row.settingsManaged) {
            Environment.isExternalStorageManager()
        } else {
            row.permissions.all { PermissionUtil.isPermissionGranted(this, it) }
        }

    /** How many startup features are switched off; the Permissions button shows this count. */
    private fun startupFeaturesOff(): Int =
        permissionRows().count { it.offerAtStartup && !isRowGranted(it) }

    /**
     * Every permission the app uses, and only those.
     *
     * The microphone is granted here too, but never offered at startup: its one user is the
     * megaphone's real-time page, which asks for it on the record button (MegaphoneVM records
     * through DJI's AudioRecordHandler). The old list asked for KILL_BACKGROUND_PROCESSES as
     * well, which the manifest does not declare — Android answers an undeclared request with an
     * immediate silent denial, so the permission check could never be satisfied, the Testing
     * Tools wiring that waited on it never ran, and the request loop spun on every resume.
     *
     * The 33+ Wi-Fi entry is what makes the SSID readable (WifiInfo/NetworkCapabilities redact
     * it without this permission even with fine location granted).
     */
    private fun permissionRows(): List<PermissionRow> =
        buildList {
            add(
                PermissionRow(
                    R.string.permission_name_location,
                    R.string.permission_why_location,
                    listOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                ),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(
                    PermissionRow(
                        R.string.permission_name_nearby_wifi,
                        R.string.permission_why_nearby_wifi,
                        listOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                    ),
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // On Android 11+ saving downloaded media needs "All files access", which Android
                // grants in Settings rather than with a dialog — the row opens that screen.
                add(
                    PermissionRow(
                        R.string.permission_name_files,
                        R.string.permission_why_files,
                        permissions = emptyList(),
                        settingsManaged = true,
                    ),
                )
            } else {
                add(
                    PermissionRow(
                        R.string.permission_name_files,
                        R.string.permission_why_files,
                        listOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ),
                    ),
                )
            }
            add(
                PermissionRow(
                    R.string.permission_name_microphone,
                    R.string.permission_why_microphone,
                    listOf(Manifest.permission.RECORD_AUDIO),
                    offerAtStartup = false,
                ),
            )
        }

    /**
     * The permissions modal, offered once per process when something the app uses is off.
     *
     * A denial is an answer: re-asking on every resume is what makes an app feel like it nags,
     * and the app works without any of these — the telemetry simply carries no RC position. The
     * home screen's Permissions button opens the same modal at any time.
     */
    private fun offerPermissionsOnce() {
        if (permissionsOffered) return
        permissionsOffered = true
        showPermissionsDialog()
    }

    private fun showPermissionsDialog() {
        permissionsDialog?.dismiss()
        val content = layoutInflater.inflate(R.layout.dialog_permissions, null)
        val rowsContainer = content.findViewById<LinearLayout>(R.id.permissions_rows)
        val rowStates = mutableListOf<Pair<PermissionRow, View>>()
        for (row in permissionRows()) {
            val rowView = layoutInflater.inflate(R.layout.item_permission_row, rowsContainer, false)
            rowView.findViewById<TextView>(R.id.permission_title).setText(row.titleRes)
            rowView.findViewById<TextView>(R.id.permission_why).setText(row.whyRes)
            rowView.setOnClickListener { onPermissionRowClicked(row) }
            rowsContainer.addView(rowView)
            rowStates += row to rowView
        }
        val dialog =
            AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton(R.string.permission_close, null)
                .setPositiveButton(R.string.permission_grant_missing) { _, _ ->
                    requestMissingPermissions()
                }
                .create()
        dialog.setOnDismissListener { permissionsDialog = null }
        dialog.show()
        permissionsDialog = dialog
        updatePermissionRows(rowStates)
    }

    /** One row's state: its label and the colours the chip and the dot are tinted with. */
    private class PermissionState(
        val textRes: Int,
        val foregroundRes: Int,
        val backgroundRes: Int,
    )

    /** At-a-glance state per row: granted, waiting to be asked, or only fixable in settings. */
    private fun updatePermissionRows(rowStates: List<Pair<PermissionRow, View>>) {
        for ((row, view) in rowStates) {
            val missing =
                row.permissions.filterNot { PermissionUtil.isPermissionGranted(this, it) }
            val state =
                when {
                    isRowGranted(row) ->
                        PermissionState(
                            R.string.permission_state_granted,
                            R.color.lyrebird_permission_granted,
                            R.color.lyrebird_permission_granted_bg,
                        )

                    // "All files access" is granted in Settings; the row says so by staying
                    // actionable (blue) instead of claiming Android will not ask again.
                    row.settingsManaged ->
                        PermissionState(
                            R.string.permission_state_missing,
                            R.color.lyrebird_permission_actionable,
                            R.color.lyrebird_permission_actionable_bg,
                        )

                    missing.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) } ->
                        PermissionState(
                            R.string.permission_state_denied,
                            R.color.lyrebird_permission_manual,
                            R.color.lyrebird_permission_manual_bg,
                        )

                    else ->
                        PermissionState(
                            R.string.permission_state_missing,
                            R.color.lyrebird_permission_actionable,
                            R.color.lyrebird_permission_actionable_bg,
                        )
                }
            val foreground = ContextCompat.getColor(this, state.foregroundRes)
            view.findViewById<TextView>(R.id.permission_state).apply {
                setText(state.textRes)
                setTextColor(foreground)
                backgroundTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this@DJIMainActivity, state.backgroundRes))
            }
            view.findViewById<View>(R.id.permission_dot).backgroundTintList =
                ColorStateList.valueOf(foreground)
        }
    }

    /**
     * A row tap grants that row. Settings-managed grants open their settings screen; a runtime
     * grant Android has stopped asking for can only be undone in the app's settings page — a
     * permanent "don't ask again" is not a state the app can undo by asking harder.
     */
    private fun onPermissionRowClicked(row: PermissionRow) {
        if (isRowGranted(row)) return
        when {
            row.settingsManaged -> openAllFilesAccessSettings()
            row.permissions.none { ActivityCompat.shouldShowRequestPermissionRationale(this, it) } ->
                openAppSettings()

            else -> requestPermissionLauncher.launch(row.permissions.toTypedArray())
        }
    }

    /** Grant everything the app can ask for; the Settings-managed grant opens its own screen. */
    private fun requestMissingPermissions() {
        val runtimeMissing =
            permissionRows()
                .filterNot { it.settingsManaged }
                .flatMap { it.permissions }
                .filterNot { PermissionUtil.isPermissionGranted(this, it) }
        if (runtimeMissing.isNotEmpty()) {
            requestPermissionLauncher.launch(runtimeMissing.toTypedArray())
        } else if (permissionRows().any { it.settingsManaged && !isRowGranted(it) }) {
            openAllFilesAccessSettings()
        }
    }

    /** The button tells the truth before it is tapped: how many features are still off. */
    private fun updatePermissionsButton() {
        val off = startupFeaturesOff()
        binding.permissionsButton.text =
            if (off == 0) {
                getString(R.string.permissions_button)
            } else {
                getString(R.string.permissions_button_missing, off)
            }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // Never re-launch from the callback: after two denials Android answers with no
            // dialog at all, so the old "request again on any denial" loop spun without ever
            // showing anything. Refresh the button and the modal's rows instead.
            updatePermissionsButton()
            val wasShowing = permissionsDialog != null
            permissionsDialog?.dismiss()
            if (wasShowing) showPermissionsDialog()
        }

    /**
     * True while a Lyrebird modal is over this screen. The home screen's drone notice may not
     * announce an automatic Flight Deck open that a dialog would hide, and must not open the
     * deck under a dialog the operator is still reading.
     */
    protected fun isLyrebirdModalShowing(): Boolean =
        permissionsDialog?.isShowing == true || LyrebirdOnboarding.dialogShowing

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    /**
     * "All files access" for this app. Android 11 shows it as its own settings page with a
     * per-app switch; the generic all-apps page is the fallback for devices that do not answer
     * the per-app intent.
     */
    private fun openAllFilesAccessSettings() {
        val perApp =
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", packageName, null),
            )
        runCatching { startActivity(perApp) }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /**
     * The leaf screen for a process that does not own the device session: the one screen that
     * can still speak without an SDK, saying who owns it and what to do about it.
     */
    private fun showSessionBlockedMessage() {
        // The SDK info card is fed by listeners that only run once the SDK exists; left alone it
        // shows the layout's raw "%1$s" placeholders, which is exactly the kind of half-built
        // state this screen exists to avoid.
        binding.textViewVersion.text = getString(R.string.session_blocked_status)
        binding.textViewRegistered.text = ""
        binding.textViewPackageProductCategory.text = ""
        binding.textViewProductName.text = ""
        binding.textViewIsDebug.text = ""
        binding.textCoreInfo.text = ""
        AlertDialog.Builder(this)
            .setTitle(R.string.session_blocked_title)
            .setMessage(R.string.session_blocked_message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        disposable.dispose()
    }
}

private const val LYREBIRD_PREFS_NAME = "LyrebirdPrefs"
private const val LYREBIRD_PREF_DRONE_NAME = "drone_name"
private const val LYREBIRD_DEFAULT_DRONE_NAME = "drone_1"