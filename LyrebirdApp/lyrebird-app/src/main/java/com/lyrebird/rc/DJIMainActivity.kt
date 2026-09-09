package com.lyrebird.rc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private val permissionArray = arrayListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.KILL_BACKGROUND_PROCESSES,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    init {
        permissionArray.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                add(Manifest.permission.READ_MEDIA_IMAGES)
//                add(Manifest.permission.READ_MEDIA_VIDEO)
//                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

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

        initMSDKInfoView()
        observeSDKManager()
        observeSdkVersionAvailability()
        checkPermissionAndRequest()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkPermission()) {
            handleAfterPermissionPermitted()
        }
    }

    override fun onResume() {
        super.onResume()
        updateLyrebirdBuildInfo()
        configureImmersiveMode()
        if (checkPermission()) {
            handleAfterPermissionPermitted()
        }
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
        addStatRow(binding.layoutLyrebirdStats, "Drone name", droneName) { promptRenameDrone(preferences) }
        addStatRow(binding.layoutLyrebirdStats, "MAVLink vehicle ID", "V$systemId") {
            promptMavlinkSystemId(preferences)
        }
        addStatRow(binding.layoutLyrebirdStats, "Wi-Fi network", currentWifiSsid() ?: "Not connected")
        addStatRow(binding.layoutLyrebirdStats, "Phone IP", NetworkUtils.getDeviceIpAddress() ?: "Unavailable")

        binding.textViewLyrebirdGitInfo.text = buildString {
            append("Built ").append(formatBuildTimeForPhone())
            append('\n').append("Git ").append(BuildConfig.LYREBIRD_GIT_SHA)
                .append(" · ").append(BuildConfig.LYREBIRD_GIT_STATE)
            append('\n').append('v').append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(')')
        }
    }

    /** A stat/settings-style row matching FlightDeckActivity's cockpit rows: title left, value right. */
    private fun addStatRow(container: LinearLayout, title: String, detail: String, onClick: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(7), 0, dpToPx(7), 0)
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { handler -> setOnClickListener { handler() } }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(26)
            ).apply { bottomMargin = dpToPx(3) }
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@DJIMainActivity, R.color.lyrebird_text))
            textSize = 10.5f
            typeface = ResourcesCompat.getFont(this@DJIMainActivity, R.font.space_grotesk)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = detail.ifBlank { "Unavailable" }
            setTextColor(ContextCompat.getColor(this@DJIMainActivity, R.color.lyrebird_muted))
            textSize = 9.5f
            typeface = ResourcesCompat.getFont(this@DJIMainActivity, R.font.dm_sans)
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        container.addView(row)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    /** Best-effort synchronous serial read, mirroring FlightDeckActivity's sysid-key derivation. */
    private fun currentDroneSerial(): String? = try {
        FlightControllerKey.KeySerialNumber.create().get("").trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun currentWifiSsid(): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork ?: return null
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            (capabilities.transportInfo as? android.net.wifi.WifiInfo)?.ssid
        } else {
            val wifiManager = getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ssid
        }
        return raw?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }

    private fun promptRenameDrone(preferences: SharedPreferences) {
        val input = EditText(this).apply {
            setText(preferences.getString(LYREBIRD_PREF_DRONE_NAME, LYREBIRD_DEFAULT_DRONE_NAME))
            hint = "e.g., lb_01, alpha, scout"
        }
        AlertDialog.Builder(this)
            .setTitle("Drone name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    preferences.edit().putString(LYREBIRD_PREF_DRONE_NAME, name).apply()
                    updateLyrebirdBuildInfo()
                } else {
                    showToast("Enter a name to override the automatic name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMavlinkSystemId(preferences: SharedPreferences) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(
                preferences.getInt(
                    MavlinkEndpointConfig.PREF_SYSTEM_ID, MavlinkEndpointConfig.DEFAULT_SYSTEM_ID
                ).toString()
            )
            hint = "0 = automatic, or 1-99"
        }
        AlertDialog.Builder(this)
            .setTitle("MAVLink vehicle ID")
            .setMessage("0 derives the id automatically from the aircraft serial. 1-99 assigns it manually.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim().toIntOrNull()
                if (value != null && (value == MavlinkSystemId.AUTO || MavlinkSystemId.isManual(value))) {
                    preferences.edit().putInt(MavlinkEndpointConfig.PREF_SYSTEM_ID, value).apply()
                    updateLyrebirdBuildInfo()
                } else {
                    showToast("Enter 0 for automatic, or 1-99 for a manual id")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        if (!checkPermission()) {
            requestPermission()
        }
    }

    private fun checkPermission(): Boolean {
        for (i in permissionArray.indices) {
            if (!PermissionUtil.isPermissionGranted(this, permissionArray[i])) {
                return false
            }
        }
        return true
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result?.entries?.forEach {
            if (!it.value) {
                requestPermission()
                return@forEach
            }
        }
    }

    private fun requestPermission() {
        requestPermissionLauncher.launch(permissionArray.toArray(arrayOf()))
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