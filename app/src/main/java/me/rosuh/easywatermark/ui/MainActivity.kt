package me.rosuh.easywatermark.ui

import android.animation.ObjectAnimator
import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_SEND
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.Address
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.forEach
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.FuncTitleModel
import me.rosuh.easywatermark.data.model.ImageInfo
import me.rosuh.easywatermark.data.model.ViewInfo
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.about.AboutActivity
import me.rosuh.easywatermark.ui.adapter.FuncPanelAdapter
import me.rosuh.easywatermark.ui.adapter.PhotoListPreviewAdapter
import me.rosuh.easywatermark.ui.dialog.*
import me.rosuh.easywatermark.ui.panel.*
import me.rosuh.easywatermark.ui.widget.CenterLayoutManager
import me.rosuh.easywatermark.ui.widget.LaunchView
import me.rosuh.easywatermark.utils.FileUtils
import me.rosuh.easywatermark.utils.VibrateHelper
import me.rosuh.easywatermark.utils.ktx.*
import me.rosuh.easywatermark.utils.onItemClick
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var pickIconPhotoPickerLauncher: ActivityResultLauncher<PickVisualMediaRequest>
    private lateinit var pickIconLegacyLauncher: ActivityResultLauncher<String>
    private lateinit var takePictureLauncher: ActivityResultLauncher<Uri>
    private val viewModel: MainViewModel by viewModels()

    private val currentBgColor: Int
        get() = ((launchView.parent as? View?)?.background as? ColorDrawable)?.color ?: colorSurface

    private lateinit var requestPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestCameraPermissionsLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var requestLocationPermissionOnStartLauncher: ActivityResultLauncher<String>

    private var pendingPermissionAction: (() -> Unit)? = null
    private var pendingPermissionDeniedAction: (() -> Unit)? = null
    private var pendingCameraImageUri: Uri? = null
    private var hasAutoLaunchedCamera = false
    private var freshLocation: Location? = null
    private var activeLocationListener: LocationListener? = null

    private val isSystemPhotoPickerAvailable by lazy {
        ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(this)
    }

    private val contentFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Text,
                getString(R.string.water_mark_mode_text),
                R.drawable.ic_func_text
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Icon,
                getString(R.string.water_mark_mode_image),
                R.drawable.ic_func_sticker
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Save,
                getString(R.string.action_save),
                R.drawable.ic_save
            )
        )
    }

    private val styleFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.TileMode,
                getString(R.string.title_tile_mode),
                R.drawable.ic_tile_mode
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.TextSize,
                getString(R.string.title_text_size),
                R.drawable.ic_func_size
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.TextStyle,
                getString(R.string.title_text_style),
                R.drawable.ic_func_typeface
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Color,
                getString(R.string.title_text_color),
                R.drawable.ic_func_color
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Alpha,
                getString(R.string.style_alpha),
                R.drawable.ic_func_opacity
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Degree,
                getString(R.string.title_text_rotate),
                R.drawable.ic_func_angle
            )
        )
    }

    private val layoutFunList: List<FuncTitleModel> by lazy {
        listOf(
            FuncTitleModel(
                FuncTitleModel.FuncType.Horizon,
                getString(R.string.title_horizon_layout),
                R.drawable.ic_func_layour_horizontal
            ),
            FuncTitleModel(
                FuncTitleModel.FuncType.Vertical,
                getString(R.string.title_vertical_layout),
                R.drawable.ic_func_layout_vertical
            )
        )
    }

    private val funcAdapter by lazy {
        FuncPanelAdapter(ArrayList(contentFunList)).apply {
            setHasStableIds(true)
        }
    }

    private val photoListPreviewAdapter by lazy { PhotoListPreviewAdapter(this) }

    private val vibrateHelper: VibrateHelper by lazy { VibrateHelper.get() }

    private lateinit var launchView: LaunchView

    private var bgTransformAnimator: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MyApp.recoveryMode) {
            setContentView(R.layout.activity_recovery)
            initRecoveryView()
            return
        }
        launchView = LaunchView(this).apply {
            setBackgroundColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    R.color.md_theme_dark_background
                )
            )
        }
        setContentView(launchView)
        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
            }
        }
        initView()
        initObserver()
        registerResultCallback()
        checkHadCrash()
        // Activity was recycled but dialog still showing in some case?
        SaveImageBSDialogFragment.safetyHide(this@MainActivity.supportFragmentManager)
        if (!hasAutoLaunchedCamera && intent?.action != ACTION_SEND) {
            hasAutoLaunchedCamera = true
            val sp = getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)
            val hasRequestedLocation = sp.getBoolean("key_location_requested", false)
            if (!hasRequestedLocation && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                requestLocationPermissionOnStartLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                launchCameraForImage()
            }
        }
    }

    private fun initRecoveryView() {
        val tvCrashInfo = findViewById<TextView>(R.id.tv_crash_info).apply {
            with(getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)) {
                val crashInfo = getString(MyApp.KEY_STACK_TRACE, "")
                text = crashInfo
            }
        }
        val btnCopy = findViewById<Button>(R.id.btn_copy).apply {
            setOnClickListener {
                try {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(tvCrashInfo.text, tvCrashInfo.text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, R.string.copy_success, Toast.LENGTH_SHORT)
                        .show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, R.string.copy_failed, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        val btnSendEmail = findViewById<Button>(R.id.btn_email).apply {
            setOnClickListener {
                viewModel.extraCrashInfo(this@MainActivity, tvCrashInfo.text.toString())
            }
        }
        val btnTelegram = findViewById<Button>(R.id.btn_telegram).apply {
            setOnClickListener {
                openLink("https://t.me/rosuh")
            }
        }
        val btnStore = findViewById<Button>(R.id.btn_store).apply {
            setOnClickListener {
                openLink(Uri.parse("market://details?id=me.rosuh.easywatermark")) {
                    Toast.makeText(this@MainActivity, R.string.store_not_found, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }

        findViewById<Button>(R.id.btn_close_recovery_mode).apply {
            setOnClickListener {
                (MyApp.instance as MyApp).launchSuccess()
                Toast.makeText(this@MainActivity, R.string.recovery_mode_closed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (MyApp.recoveryMode) {
            return
        }
    }

    private fun registerResultCallback() {
        takePictureLauncher =
            registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                handleCapturedImage(success)
            }
        pickIconPhotoPickerLauncher =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                val result = uri?.let(::listOf) ?: emptyList()
                handlePickedMedia(REQ_PICK_ICON, result)
            }
        pickIconLegacyLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                val result = uri?.let(::listOf) ?: emptyList()
                handlePickedMedia(REQ_PICK_ICON, result)
            }
        requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingPermissionAction?.invoke()
                pendingPermissionAction = null
                pendingPermissionDeniedAction = null
                return@registerForActivityResult
            }
            Toast.makeText(
                this,
                getString(R.string.request_permission_failed),
                Toast.LENGTH_SHORT
            ).show()
            pendingPermissionDeniedAction?.invoke()
            pendingPermissionAction = null
            pendingPermissionDeniedAction = null
        }
        requestCameraPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
                val hasCameraPermission = grants[Manifest.permission.CAMERA]
                    ?: hasPermission(Manifest.permission.CAMERA)
                if (hasCameraPermission) {
                    pendingPermissionAction?.invoke()
                    pendingPermissionAction = null
                    pendingPermissionDeniedAction = null
                    return@registerForActivityResult
                }
                Toast.makeText(
                    this,
                    getString(R.string.request_permission_failed),
                    Toast.LENGTH_SHORT
                ).show()
                pendingPermissionAction = null
                pendingPermissionDeniedAction = null
                if (launchView.mode == LaunchView.ViewMode.LaunchMode) {
                    finish()
                }
            }
        requestLocationPermissionOnStartLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
                getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean("key_location_requested", true)
                    .apply()
                launchCameraForImage()
            }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        this.intent = intent
    }

    override fun onStart() {
        super.onStart()
        // Accepting shared images from other apps
        if (intent?.action == ACTION_SEND && intent?.data != null) {
            dealWithImage(listOf(intent?.data!!))
        }
    }

    override fun onResume() {
        super.onResume()
        if (MyApp.recoveryMode) {
            return
        }
        lifecycleScope.launch {
            delay(1000)
            if (this@MainActivity.isFinishing) {
                return@launch
            }
            (MyApp.instance as? MyApp?)?.launchSuccess()
        }
    }

    override fun onDestroy() {
        bgTransformAnimator?.cancel()
        super.onDestroy()
    }

    private fun checkHadCrash() {
        with(getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)) {
            val isCrash = getBoolean(MyApp.KEY_IS_CRASH, false)
            if (!isCrash) {
                return@with
            }
            val crashInfo = getString(MyApp.KEY_STACK_TRACE, "")
            edit {
                putBoolean(MyApp.KEY_IS_CRASH, false)
                putString(MyApp.KEY_STACK_TRACE, "")
            }
            showCrashDialog(crashInfo)
        }
    }

    private fun showCrashDialog(crashInfo: String?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tips_tip_title)
            .setMessage(R.string.msg_crash)
            .setNegativeButton(
                R.string.tips_cancel_dialog
            ) { dialog, _ -> dialog?.dismiss() }
            .setPositiveButton(
                R.string.crash_mail
            ) { dialog, _ ->
                viewModel.extraCrashInfo(this, crashInfo)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun initObserver() {
        lifecycleScope.launch {
            viewModel.uiStateFlow.flowWithLifecycle(
                this@MainActivity.lifecycle,
                Lifecycle.State.STARTED
            ).collect {
                if (it == UiState.GoEditDialog) {
                    TextWatermarkBSDFragment.safetyShow(supportFragmentManager)
                }
            }
        }
        viewModel.waterMark.observe(this) {
            if (it == null) {
                return@observe
            }
            Log.i("initObserver", "$it")
            launchView.post {
                launchView.ivPhoto.config = it
            }
            if (it.markMode == WaterMarkRepository.MarkMode.Image && launchView.tabLayout.selectedTabPosition == 0) {
                hideDetailPanel()
            }
            viewModel.resetJobStatus()
        }
        viewModel.selectedImage.observe(this) {
            if (it == null || it.uri.toString().isBlank()) {
                return@observe
            }
            try {
                val isAnimating = launchView.toEditorMode()
                if (isAnimating) {
                    launchView.ivPhoto.updateUri(true, it)
                    selectTab(0)
                } else {
                    launchView.ivPhoto.updateUri(false, it)
                }
            } catch (se: SecurityException) {
                se.printStackTrace()
                // reset the uri because we don't have permission -_-
                viewModel.selectImage(Uri.EMPTY)
            }
        }
        viewModel.imageList.observe(this) {
            photoListPreviewAdapter.selectedPos = viewModel.nextSelectedPos
            photoListPreviewAdapter.submitList(it.first.toList()) {
                if (it.second.not()) {
                    return@submitList
                }
                launchView.rvPhotoList.apply {
                    post { smoothScrollToPosition(0) }
                }
            }
        }

        viewModel.saveResult.observe(this) {
            if (it.isFailure()) {
                when (it.code) {
                    MainViewModel.TYPE_ERROR_SAVE_OOM -> {
                        toast(getString(R.string.error_save_oom))
                        CompressImageDialogFragment.safetyShow(supportFragmentManager)
                        viewModel.resetJobStatus()
                    }
                    MainViewModel.TYPE_ERROR_FILE_NOT_FOUND -> toast(getString(R.string.error_file_not_found))
                    MainViewModel.TYPE_ERROR_NOT_IMG -> toast(getString(R.string.error_not_img))
                    else -> toast("${getString(R.string.tips_error)}: ${it.message}")
                }
                viewModel.resetJobStatus()
            } else {
                toast(it.message)
            }
        }

        viewModel.colorPalette.observe(this) { palette ->
            val bgColor = palette.bgColor(this)
            val titleTextColor = palette.titleTextColor(this)

            bgTransformAnimator = currentBgColor.toColor(bgColor) {
                val c = it.animatedValue as Int
                if (launchView.isEdit()) {
                    doApplyBgChanged(c)
                } else {
                    doApplyBgChanged()
                }
            }
            funcAdapter.textColor.toColor(titleTextColor) {
                val c = it.animatedValue as Int
                funcAdapter.applyTextColor(c)
                launchView.tabLayout.setTabTextColors(c, this.colorPrimary)
                launchView.toolbar.menu.forEach { menuItem ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        menuItem.iconTintList = ColorStateList.valueOf(c)
                    } else {
                        menuItem.icon?.setTint(c)
                    }
                }
            }
        }
    }

    private fun Context.toast(msg: String?) {
        if (msg.isNullOrBlank()) return
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        doApplyBgChanged()
        // prepare MotionLayout
        launchView.setListener {
            onModeChange { _, newMode ->
                when (newMode) {
                    LaunchView.ViewMode.Editor -> {
                        launchView.logoView.stop()
                    }
                    LaunchView.ViewMode.LaunchMode -> {
                        launchView.logoView.start()
                    }
                }
            }
        }
        // setting tool bar
        launchView.toolbar.apply {
            navigationIcon =
                ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_logo_tool_bar)
            title = null
            setSupportActionBar(this)
            supportActionBar?.title = null
        }
        // go about page
        launchView.ivGoAboutPage.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        // pick image button
        launchView.ivSelectedPhotoTips.setOnClickListener {
            performFileSearch(REQ_CODE_PICK_IMAGE)
        }
        // setting bg
        launchView.ivPhoto.apply {
            onBgReady { palette ->
                viewModel.updateColorPalette(palette)
            }
            onOffsetChanged {
                viewModel.updateOffset(it)
            }
            onScaleEnd {
                viewModel.updateTextSize(it)
            }
        }
        // functional panel in recyclerView
        launchView.rvPanel.apply {
            adapter = funcAdapter
            setHasFixedSize(true)
            layoutManager = CenterLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false)
            onItemClick { _, pos, v ->
                val snapView = snapHelper.findSnapView(launchView.rvPanel.layoutManager)
                if (snapView == v) {
                    val item = (this.adapter as FuncPanelAdapter).dataSet[pos]
                    handleFuncItem(item)
                    funcAdapter.selectedPos = pos
                } else {
                    smoothScrollToPosition(pos)
                }
            }

            onSnapViewPreview { snapView, _ ->
                vibrateHelper.doVibrate(snapView)
            }

            onSnapViewSelected { snapView, pos ->
                funcAdapter.selectedPos = pos
                handleFuncItem(funcAdapter.dataSet[pos])
                vibrateHelper.doVibrate(snapView)
            }

            post {
                canAutoSelected = false
                scrollToPosition(0)
                canAutoSelected = true
            }
        }
        // image list
        launchView.rvPhotoList.apply {
            enableBorder = true
            adapter = photoListPreviewAdapter
            setHasFixedSize(true)
            layoutManager =
                CenterLayoutManager(this@MainActivity, RecyclerView.HORIZONTAL, false).apply {
                    onStartSmoothScroll {
                        canTouch = false
                    }
                    onStopSmoothScroll {
                        canTouch = true
                    }
                }

            photoListPreviewAdapter.onRemove { imageInfo ->
                viewModel.removeImage(imageInfo, photoListPreviewAdapter.selectedPos)
            }

            onItemClick { _, pos, v ->
                val snapView = snapHelper.findSnapView(launchView.rvPanel.layoutManager)
                if (snapView != v) {
                    smoothScrollToPosition(pos)
                }
            }

            onSnapViewPreview { snapView, _ ->
                vibrateHelper.doVibrate(snapView)
            }

            onSnapViewSelected { snapView, pos ->
                photoListPreviewAdapter.selectedPos = pos
                val uri = photoListPreviewAdapter.getItem(pos)?.uri ?: return@onSnapViewSelected
                viewModel.selectImage(uri)
                vibrateHelper.doVibrate(snapView)
            }
        }

        launchView.tabLayout.apply {
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    if (tab == null) {
                        return
                    }
                    hideDetailPanel()
                    vibrateHelper.doVibrate(this@apply)
                    val adapter = (launchView.rvPanel.adapter as? FuncPanelAdapter)
                    when (tab.position) {
                        0 -> {
                            val curPos =
                                if (launchView.ivPhoto.config?.markMode == WaterMarkRepository.MarkMode.Image) 1 else 0
                            if (curPos == 0) {
                                launchView.rvPanel.smoothScrollToPosition(0)
                                adapter?.also {
                                    it.seNewData(contentFunList, 0)
                                    post { handleFuncItem(it.dataSet[0]) }
                                }
                            } else {
                                hideDetailPanel()
                                adapter?.seNewData(contentFunList, curPos)
                                manuallySelectedItem(curPos)
                            }
                        }
                        2 -> {
                            launchView.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(layoutFunList, 0)
                                post { handleFuncItem(it.dataSet[0]) }
                            }
                        }
                        else -> {
                            launchView.rvPanel.smoothScrollToPosition(0)
                            adapter?.also {
                                it.seNewData(styleFunList, 0)
                                post { handleFuncItem(it.dataSet[0]) }
                            }
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {}

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    if (tab?.position == 0) {
                        handleFuncItem(contentFunList[0])
                    }
                }
            })
        }
    }

    private fun hideDetailPanel() {
        commitWithAnimation {
            supportFragmentManager.fragments.forEach {
                remove(it)
            }
        }
    }

    private fun handleFuncItem(item: FuncTitleModel) {
        Log.i("handleFuncItem", "item = $item")
        when (item.type) {
            FuncTitleModel.FuncType.Text -> {
                TextContentDisplayFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Icon -> {
                performFileSearch(REQ_PICK_ICON)
            }
            FuncTitleModel.FuncType.Color -> {
                ColorFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Alpha -> {
                AlphaPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Degree -> {
                DegreePbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TextStyle -> {
                TextStyleFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Vertical -> {
                VerticalPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Horizon -> {
                HorizonPbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TextSize -> {
                TextSizePbFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.TileMode -> {
                TileModeFragment.replaceShow(this, launchView.fcFunctionDetail.id)
            }
            FuncTitleModel.FuncType.Save -> {
                SaveImageBSDialogFragment.safetyShow(supportFragmentManager)
            }
        }
    }

    private fun setStatusBarColor(color: Int, isInEditMode: Boolean) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val systemUiAppearance = if (isInEditMode && this.isNight()) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                systemUiAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            )
        } else {
            val systemUiVisibilityFlags = if (!isInEditMode && !this.isNight()) {
                window.decorView.systemUiVisibility or SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility and SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            window.decorView.systemUiVisibility = systemUiVisibilityFlags
        }
        window.statusBarColor = color
        window.findViewById<View>(android.R.id.content)?.foreground = null
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, AboutActivity::class.java))
            true
        }

        R.id.action_pick -> {
            performFileSearch(REQ_CODE_PICK_IMAGE)
            true
        }

        R.id.action_save -> {
            SaveImageBSDialogFragment.safetyShow(supportFragmentManager)
            true
        }
        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    /**
     * Opens camera for the base image, or an image picker for watermark icons.
     */
    private fun performFileSearch(requestCode: Int) {
        if (requestCode == REQ_CODE_PICK_IMAGE) {
            launchCameraForImage()
            return
        }

        val request = PickVisualMediaRequest.Builder()
            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
            .build()

        if (isSystemPhotoPickerAvailable) {
            launchView.logoView.stop()
            when (requestCode) {
                REQ_PICK_ICON -> pickIconPhotoPickerLauncher.launch(request)
            }
            return
        }

        val launchLegacyPicker = {
            if (requestCode == REQ_PICK_ICON) {
                launchView.logoView.stop()
                pickIconLegacyLauncher.launch("image/*")
            } else {
                openLegacyGallery()
            }
        }

        pendingPermissionAction = launchLegacyPicker
        checkReadingPermission(requestPermissionLauncher, grant = {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            action?.invoke()
        })
    }

    private fun launchCameraForImage() {
        if (hasPermission(Manifest.permission.CAMERA)) {
            launchCameraIntent()
            return
        }
        pendingPermissionAction = ::launchCameraIntent
        requestCameraPermissionsLauncher.launch(arrayOf(Manifest.permission.CAMERA))
    }

    private fun launchCameraIntent() {
        launchView.logoView.stop()
        val uri = runCatching {
            createCameraImageUri()
        }.getOrElse {
            launchView.logoView.start()
            Toast.makeText(
                this,
                "${getString(R.string.tips_error)}: ${it.message}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        pendingCameraImageUri = uri
        startBackgroundLocationRequest()
        runCatching {
            takePictureLauncher.launch(uri)
        }.getOrElse {
            stopBackgroundLocationRequest()
            pendingCameraImageUri = null
            launchView.logoView.start()
            Toast.makeText(
                this,
                "${getString(R.string.tips_error)}: ${it.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateWatermarkTextWithGps(onUpdated: () -> Unit) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fallbackLocation = runCatching {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        }.getOrNull()

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER).not()) {
            updateWatermarkText(fallbackLocation, onUpdated)
            return
        }

        var finished = false
        lateinit var listener: LocationListener
        val handler = Handler(Looper.getMainLooper())
        fun finish(location: Location?) {
            if (finished) {
                return
            }
            finished = true
            runCatching { locationManager.removeUpdates(listener) }
            updateWatermarkText(location ?: fallbackLocation, onUpdated)
        }

        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                finish(location)
            }

            override fun onProviderDisabled(provider: String) {
                finish(fallbackLocation)
            }
        }

        handler.postDelayed({ finish(fallbackLocation) }, GPS_LOCATION_TIMEOUT_MS)
        runCatching {
            locationManager.requestSingleUpdate(
                LocationManager.GPS_PROVIDER,
                listener,
                Looper.getMainLooper()
            )
        }.getOrElse {
            finish(fallbackLocation)
        }
    }

    private fun updateWatermarkText(location: Location?, onUpdated: () -> Unit) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                buildWatermarkText(location)
            }
            viewModel.updateText(text).invokeOnCompletion {
                runOnUiThread { onUpdated() }
            }
        }
    }

    private fun buildWatermarkText(location: Location?): String {
        val now = Date()
        val locale = Locale.getDefault()
        val date = SimpleDateFormat("yyyy-MM-dd", locale).format(now)
        val time = SimpleDateFormat("HH:mm:ss", locale).format(now)
        val week = SimpleDateFormat("EEEE", locale).format(now)
        val place = location?.let(::buildLocationText)
            ?: getString(R.string.watermark_location_unavailable)
        val model = listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        return listOf(date, time, week, place, model).joinToString("\n")
    }

    private fun buildLocationText(location: Location): String {
        return resolveAddress(location) ?: formatCoordinates(location)
    }

    @Suppress("DEPRECATION")
    private fun resolveAddress(location: Location): String? {
        val sp = getSharedPreferences(MyApp.SP_NAME, MODE_PRIVATE)
        val gaodeKey = sp.getString("key_gaode_api_key", "") ?: ""
        if (gaodeKey.isNotBlank()) {
            val amapAddress = queryAmapRegeo(location.latitude, location.longitude, gaodeKey)
            if (!amapAddress.isNullOrBlank()) {
                return amapAddress
            }
        }

        if (Geocoder.isPresent().not()) {
            return null
        }
        val geocoder = Geocoder(this, Locale.getDefault())
        
        // 1. Get raw WGS-84 address
        val wgsAddress = runCatching {
            geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
        }.getOrNull()

        // 2. If inside China, try converted GCJ-02 address
        val isChina = !isOutOfChina(location.latitude, location.longitude)
        val gcjAddress = if (isChina) {
            val (gcjLat, gcjLng) = wgs84ToGcj02(location.latitude, location.longitude)
            runCatching {
                geocoder.getFromLocation(gcjLat, gcjLng, 1)?.firstOrNull()
            }.getOrNull()
        } else {
            null
        }

        // 3. Choose the one with the higher precision score
        val selectedAddress = when {
            wgsAddress != null && gcjAddress != null -> {
                if (getAddressPrecisionScore(gcjAddress) > getAddressPrecisionScore(wgsAddress)) {
                    gcjAddress
                } else {
                    wgsAddress
                }
            }
            wgsAddress != null -> wgsAddress
            gcjAddress != null -> gcjAddress
            else -> null
        }

        return selectedAddress?.let { compileAddress(it) }
    }

    private fun queryAmapRegeo(lat: Double, lng: Double, key: String): String? {
        return runCatching {
            val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
            val urlStr = "https://restapi.amap.com/v3/geocode/regeo?key=$key&location=$gcjLng,$gcjLat&extensions=all"
            val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(jsonStr)
                if (json.optString("status") == "1") {
                    val regeocode = json.optJSONObject("regeocode")
                    val formattedAddress = regeocode?.optString("formatted_address")
                    if (!formattedAddress.isNullOrBlank()) {
                        return if (formattedAddress.startsWith("中国")) {
                            formattedAddress.substring(2)
                        } else {
                            formattedAddress
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun compileAddress(address: Address): String? {
        val sb = StringBuilder()
        if (!address.adminArea.isNullOrBlank()) {
            sb.append(address.adminArea)
        }
        if (!address.locality.isNullOrBlank() && address.locality != address.adminArea) {
            sb.append(address.locality)
        }
        if (!address.subLocality.isNullOrBlank()) {
            sb.append(address.subLocality)
        }
        if (!address.thoroughfare.isNullOrBlank()) {
            sb.append(address.thoroughfare)
        }
        if (!address.subThoroughfare.isNullOrBlank()) {
            sb.append(address.subThoroughfare)
        }
        if (!address.featureName.isNullOrBlank() && 
            address.featureName != address.thoroughfare && 
            address.featureName != address.subThoroughfare &&
            address.featureName != address.locality &&
            address.featureName != address.subLocality &&
            address.featureName != address.adminArea
        ) {
            sb.append(address.featureName)
        }
        val compiled = sb.toString()
        if (compiled.isNotBlank()) {
            return if (compiled.startsWith("中国")) {
                compiled.substring(2)
            } else {
                compiled
            }
        }
        val fallback = address.getAddressLine(0).orEmpty()
        return if (fallback.startsWith("中国")) {
            fallback.substring(2)
        } else {
            fallback.takeIf { it.isNotBlank() }
        }
    }

    private fun getAddressPrecisionScore(address: Address): Int {
        var score = 0
        if (!address.subThoroughfare.isNullOrBlank()) {
            score += 10
        }
        if (!address.featureName.isNullOrBlank() && address.featureName != address.thoroughfare) {
            score += 8
        }
        if (!address.premises.isNullOrBlank()) {
            score += 5
        }
        if (!address.thoroughfare.isNullOrBlank()) {
            score += 2
        }
        val line0 = address.getAddressLine(0).orEmpty()
        if (line0.any { it.isDigit() }) {
            score += 5
        }
        if (line0.contains("号") || line0.contains("栋") || line0.contains("楼") || line0.contains("室") || line0.contains("弄")) {
            score += 5
        }
        score += line0.length
        return score
    }

    private fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): Pair<Double, Double> {
        if (isOutOfChina(wgsLat, wgsLng)) return Pair(wgsLat, wgsLng)
        val pi = 3.1415926535897932384626
        val a = 6378245.0
        val ee = 0.00669342162296594323
        var dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0, pi)
        var dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0, pi)
        val radLat = wgsLat / 180.0 * pi
        var magic = Math.sin(radLat)
        magic = 1 - ee * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((a * (1 - ee)) / (magic * sqrtMagic) * pi)
        dLng = (dLng * 180.0) / (a / sqrtMagic * Math.cos(radLat) * pi)
        return Pair(wgsLat + dLat, wgsLng + dLng)
    }

    private fun isOutOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double, pi: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * pi) + 20.0 * Math.sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(y * pi) + 40.0 * Math.sin(y / 3.0 * pi)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(y / 12.0 * pi) + 320.0 * Math.sin(y * pi / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double, pi: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
        ret += (20.0 * Math.sin(6.0 * x * pi) + 20.0 * Math.sin(2.0 * x * pi)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(x * pi) + 40.0 * Math.sin(x / 3.0 * pi)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(x / 12.0 * pi) + 300.0 * Math.sin(x / 30.0 * pi)) * 2.0 / 3.0
        return ret
    }

    private fun formatCoordinates(location: Location): String {
        val locale = Locale.getDefault()
        val accuracy = if (location.hasAccuracy()) ", ±${location.accuracy.toInt()}m" else ""
        return String.format(locale, "%.6f, %.6f%s", location.latitude, location.longitude, accuracy)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createCameraImageUri(): Uri {
        val cameraDir = File(cacheDir, "compressor").apply {
            mkdirs()
        }
        val imageFile = File(cameraDir, "camera_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
    }

    private fun handleCapturedImage(success: Boolean) {
        launchView.logoView.start()
        val uri = pendingCameraImageUri
        pendingCameraImageUri = null
        stopBackgroundLocationRequest()
        if (success && uri != null) {
            updateCapturedImageWatermark {
                dealWithImage(listOf(uri))
            }
            return
        }
        freshLocation = null
        Toast.makeText(
            this,
            getString(R.string.tips_do_not_choose_image),
            Toast.LENGTH_SHORT
        ).show()
        if (launchView.mode == LaunchView.ViewMode.LaunchMode) {
            finish()
        }
    }

    private fun updateCapturedImageWatermark(onUpdated: () -> Unit) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasLocationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val location = if (hasLocationPermission) {
            freshLocation ?: runCatching {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
        } else {
            null
        }
        freshLocation = null
        updateWatermarkText(location, onUpdated)
    }

    @SuppressLint("MissingPermission")
    private fun startBackgroundLocationRequest() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        freshLocation = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (freshLocation == null || location.accuracy < (freshLocation?.accuracy ?: Float.MAX_VALUE)) {
                    freshLocation = location
                }
            }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        activeLocationListener = listener
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private fun stopBackgroundLocationRequest() {
        val listener = activeLocationListener
        if (listener != null) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            runCatching {
                locationManager.removeUpdates(listener)
            }
            activeLocationListener = null
        }
    }

    private fun openLegacyGallery() {
        GalleryFragment().apply {
            launchView.logoView.stop()
            doOnDismiss {
                launchView.logoView.start()
            }
            show(supportFragmentManager, "GalleryFragment")
        }
    }

    private fun dealWithImage(uri: List<Uri>) {
        if (FileUtils.isImage(this.contentResolver, uri.first())) {
            viewModel.updateImageList(uri)
        } else {
            Toast.makeText(
                this,
                getString(R.string.tips_choose_other_file_type),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handlePickedMedia(requestCode: Int, list: List<Uri>) {
        launchView.logoView.start()
        val finalList = list.filter {
            FileUtils.isImage(this.contentResolver, it)
        }
        if (finalList.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.tips_do_not_choose_image),
                Toast.LENGTH_SHORT
            ).show()
            if (requestCode == REQ_PICK_ICON && viewModel.waterMark.value?.markMode == WaterMarkRepository.MarkMode.Text) {
                manuallySelectedItem(0)
            }
            return
        }
        when (requestCode) {
            REQ_CODE_PICK_IMAGE -> {
                Log.i(MainActivity::class.simpleName, finalList.toTypedArray().contentToString())
                dealWithImage(finalList)
            }
            REQ_PICK_ICON -> {
                viewModel.updateIcon(finalList.first())
            }
        }
    }

    private fun manuallySelectedItem(pos: Int) {
        launchView.rvPanel.canAutoSelected = false
        funcAdapter.selectedPos = pos
        launchView.rvPanel.scrollToPosition(pos)
        launchView.rvPanel.canAutoSelected = true
    }

    override fun onBackPressed() {
        if (MyApp.recoveryMode) {
            super.onBackPressed()
            return
        }
        if (launchView.mode == LaunchView.ViewMode.LaunchMode) {
            super.onBackPressed()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_title_exist_confirm)
            .setMessage(R.string.dialog_content_exist_confirm)
            .setNegativeButton(
                R.string.tips_confirm_dialog
            ) { _, _ ->
                resetView()
            }
            .setPositiveButton(
                R.string.dialog_cancel_exist_confirm
            ) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun resetView() {
        viewModel.resetJobStatus()
        viewModel.clearData()
        launchView.ivPhoto.reset()
        bgTransformAnimator?.cancel()
        TextContentDisplayFragment.remove(this)
        finish()
    }

    private fun doApplyBgChanged(
        color: Int = ContextCompat.getColor(
            this,
            R.color.md_theme_dark_background
        )
    ) {
        (launchView.parent as? View?)?.setBackgroundColor(color)
        window?.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window?.navigationBarDividerColor = Color.TRANSPARENT
        }
        setStatusBarColor(color, true)
    }

    private fun selectTab(index: Int) {
        launchView.tabLayout.getTabAt(index).let {
            launchView.tabLayout.selectTab(it)
        }
    }

    fun getImageList(): List<ImageInfo> {
        return photoListPreviewAdapter.data
    }

    fun getImageViewInfo(): ViewInfo {
        return ViewInfo.from(launchView.ivPhoto)
    }

    fun requestPermission(block: () -> Unit) {
        pendingPermissionAction = block
        checkWritingPermission(requestPermissionLauncher, grant = {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            action?.invoke()
        })
    }

    companion object {
        private const val REQ_CODE_PICK_IMAGE: Int = 42
        const val REQ_CODE_REQ_WRITE_PERMISSION: Int = 43
        const val REQ_PICK_ICON: Int = 44
        private const val GPS_LOCATION_TIMEOUT_MS: Long = 8000L
    }
}
