package moe.chenxy.oppopods

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.SurfaceTexture
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import moe.chenxy.oppopods.pods.PodImageSlot
import moe.chenxy.oppopods.pods.PodImageStore
import moe.chenxy.oppopods.ui.AppTheme
import moe.chenxy.oppopods.ui.components.rememberBatteryDisplay
import moe.chenxy.oppopods.utils.applyTransparentEdgeToEdge
import moe.chenxy.oppopods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction
import moe.chenxy.oppopods.utils.miuiStrongToast.data.PodParams
import moe.chenxy.oppopods.utils.miuiStrongToast.data.batteryStatusCompat
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ConnectionPopupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        applyTransparentEdgeToEdge()

        val prefs = getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE)
        val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
            1 -> ColorSchemeMode.Light
            2 -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }

        val initialBatteryParams = intent.batteryStatusCompat() ?: BatteryParams()
        val initialDeviceName = intent.getStringExtra("device_name").orEmpty()

        val connectVideoFile = PodImageStore.customFile(this, PodImageSlot.CONNECT_VIDEO)

        setContent {
            AppTheme(colorSchemeMode = colorSchemeMode) {
                ConnectionPopupContent(
                    initialDeviceName = initialDeviceName,
                    initialBatteryParams = initialBatteryParams,
                    videoFile = connectVideoFile,
                    onDismiss = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ConnectionPopupContent(
    initialDeviceName: String,
    initialBatteryParams: BatteryParams,
    videoFile: java.io.File? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val deviceName = remember { mutableStateOf(initialDeviceName) }
    val batteryParams = remember { mutableStateOf(initialBatteryParams) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_CONNECTED -> {
                        deviceName.value = intent.getStringExtra("device_name") ?: deviceName.value
                    }
                    OppoPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        intent.batteryStatusCompat()?.let {
                            batteryParams.value = it
                        }
                    }
                    OppoPodsAction.ACTION_PODS_DISCONNECTED -> {
                        onDismiss()
                    }
                }
            }
        }

        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_CONNECTED)
            addAction(OppoPodsAction.ACTION_PODS_BATTERY_CHANGED)
            addAction(OppoPodsAction.ACTION_PODS_DISCONNECTED)
        }, Context.RECEIVER_EXPORTED)

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    ConnectionPopupCard(
        deviceName = deviceName.value.ifEmpty { stringResource(R.string.app_name) },
        batteryParams = batteryParams.value,
        videoFile = videoFile,
        onDismiss = onDismiss
    )
}

@Composable
private fun ConnectionPopupCard(
    deviceName: String,
    batteryParams: BatteryParams,
    videoFile: java.io.File? = null,
    onDismiss: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    // 与 HyperOS 原生连接弹窗（fastconnect.MiuiFastConnectActivity）对齐
    val videoBackgroundColor = Color(0xFFFFFFFF)
    val containerColor = if (isDark) Color(0xFF242424) else videoBackgroundColor
    val textColor = if (isDark) Color(0xE5FFFFFF) else Color(0xFF000000)
    val secondaryTextColor = if (isDark) Color(0xCCFFFFFF) else Color(0x99000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(containerColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 46.67.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    text = deviceName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    style = TextStyle(
                        color = textColor,
                        fontSize = 20.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(18.3.dp))
                // 内置视频是白底 mp4，深色模式下改用官方透明底渲染图，避免整块白色面板；
                // 用户自定义视频无法重新着色，仍放在圆角浅色媒体面板中。
                if (isDark && videoFile?.exists() != true) {
                    Image(
                        painter = painterResource(R.drawable.img_connection_dark),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .aspectRatio(960f / 312f),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    ConnectionVideo(
                        modifier = if (isDark) {
                            Modifier
                                .fillMaxWidth(0.95f)
                                .clip(RoundedCornerShape(16.dp))
                                .aspectRatio(960f / 312f)
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(960f / 312f)
                        },
                        videoFile = videoFile
                    )
                }
                Spacer(modifier = Modifier.height(45.dp))

                BatteryColumns(
                    batteryParams = batteryParams,
                    textColor = secondaryTextColor
                )

                Spacer(modifier = Modifier.height(42.6.dp))
                DoneButton(
                    onClick = onDismiss,
                    containerColor = MiuixTheme.colorScheme.primary,
                    textColor = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ConnectionVideo(modifier: Modifier = Modifier, videoFile: java.io.File? = null) {
    Box(modifier = modifier.background(Color(0xFFFFFFFF))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                LoopingResourceVideoView(context, videoFile)
            }
        )
    }
}

@Composable
private fun BatteryColumns(
    batteryParams: BatteryParams,
    textColor: Color
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val leftColumnWidth = 116.dp
        val caseColumnWidth = 116.dp
        val leftCenter = maxWidth * 0.31f
        val caseCenter = maxWidth * 0.69f

        Column(
            modifier = Modifier
                .width(leftColumnWidth)
                .offset(x = leftCenter - leftColumnWidth / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BatteryChip(
                label = stringResource(R.string.connection_popup_left_ear),
                pod = batteryParams.left,
                textColor = textColor
            )
            BatteryChip(
                label = stringResource(R.string.connection_popup_right_ear),
                pod = batteryParams.right,
                textColor = textColor
            )
        }
        Box(
            modifier = Modifier
                .width(caseColumnWidth)
                .offset(x = caseCenter - caseColumnWidth / 2),
            contentAlignment = Alignment.TopCenter
        ) {
            BatteryChip(
                label = stringResource(R.string.connection_popup_case),
                pod = batteryParams.case,
                textColor = textColor
            )
        }
    }
}

@Composable
private fun BatteryChip(
    label: String,
    pod: PodParams?,
    textColor: Color
) {
    val display = rememberBatteryDisplay(pod)
    val isConnected = display.isConnected
    val level = display.level
    val levelText = when {
        isConnected -> "$level%"
        display.hasLastKnown -> "${display.lastKnownLevel}%"
        else -> "-"
    }
    // Unknown battery shows the full icon (like MiLink) instead of an empty one.
    val iconLevel = when {
        isConnected -> level
        display.hasLastKnown -> display.lastKnownLevel
        else -> 100
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            text = label,
            modifier = Modifier.width(38.dp),
            style = TextStyle(
                color = textColor,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.End
            ),
            maxLines = 1,
            softWrap = false
        )
        Spacer(modifier = Modifier.width(4.dp))
        Image(
            painter = painterResource(getBatteryIconRes(iconLevel, if (isConnected) pod?.isCharging == true else false)),
            contentDescription = "$label $levelText",
            modifier = Modifier.size(width = 32.dp, height = 14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        BasicText(
            text = levelText,
            modifier = Modifier.width(36.dp),
            style = TextStyle(
                color = textColor,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun DoneButton(
    onClick: () -> Unit,
    containerColor: Color = Color(0x0A000000),
    textColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = stringResource(R.string.done),
            style = TextStyle(
                color = textColor,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center
            )
        )
    }
}

private fun getBatteryIconRes(level: Int, isCharging: Boolean): Int {
    val index = when {
        level <= 10 -> 1
        level <= 20 -> 2
        level <= 30 -> 3
        level <= 40 -> 4
        level <= 50 -> 5
        level <= 60 -> 6
        level <= 70 -> 7
        level <= 80 -> 8
        level <= 90 -> 9
        else -> 10
    }
    return if (isCharging) {
        when (index) {
            1 -> R.drawable.charge_1
            2 -> R.drawable.charge_2
            3 -> R.drawable.charge_3
            4 -> R.drawable.charge_4
            5 -> R.drawable.charge_5
            6 -> R.drawable.charge_6
            7 -> R.drawable.charge_7
            8 -> R.drawable.charge_8
            9 -> R.drawable.charge_9
            else -> R.drawable.charge_10
        }
    } else {
        when (index) {
            1 -> R.drawable.common_1
            2 -> R.drawable.common_2
            3 -> R.drawable.common_3
            4 -> R.drawable.common_4
            5 -> R.drawable.common_5
            6 -> R.drawable.common_6
            7 -> R.drawable.common_7
            8 -> R.drawable.common_8
            9 -> R.drawable.common_9
            else -> R.drawable.common_10
        }
    }
}

private class LoopingResourceVideoView(
    context: Context,
    private val videoFile: java.io.File? = null
) : TextureView(context), TextureView.SurfaceTextureListener {

    private var mediaPlayer: MediaPlayer? = null
    private var mediaSurface: Surface? = null

    init {
        isOpaque = false
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        startVideo(surfaceTexture)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releaseVideo()
        return true
    }

    override fun onDetachedFromWindow() {
        releaseVideo()
        super.onDetachedFromWindow()
    }

    private fun startVideo(surfaceTexture: SurfaceTexture) {
        releaseVideo()
        val surface = Surface(surfaceTexture)
        mediaSurface = surface
        mediaPlayer = MediaPlayer().apply {
            val customVideo = videoFile?.takeIf { it.exists() }
            if (customVideo != null) {
                setDataSource(customVideo.path)
            } else {
                setDataSource(
                    context,
                    Uri.parse("android.resource://${context.packageName}/${R.raw.boot_connected_state}")
                )
            }
            setSurface(surface)
            isLooping = true
            setVolume(0f, 0f)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    private fun releaseVideo() {
        mediaPlayer?.let { player ->
            runCatching {
                player.stop()
                player.release()
            }
        }
        mediaPlayer = null
        mediaSurface?.release()
        mediaSurface = null
    }
}
