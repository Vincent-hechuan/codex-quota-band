package com.codex.quota.android.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.codex.quota.android.protocol.PairingDiscovery
import com.codex.quota.android.ui.CodexTokens
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

private enum class PairingMode {
  Choice,
  Scan,
  Manual,
}

@Composable
internal fun CodexPairingFlow(
  onClose: () -> Unit,
  onPairingLink: (String) -> Unit,
  onManualPairing: (PairingDiscovery, String) -> Unit,
) {
  var mode by remember { mutableStateOf(PairingMode.Choice) }
  PairingBackground {
    when (mode) {
      PairingMode.Choice ->
        PairingChoice(
          onClose = onClose,
          onScan = { mode = PairingMode.Scan },
          onManual = { mode = PairingMode.Manual },
        )
      PairingMode.Scan ->
        PairingScanner(
          onBack = { mode = PairingMode.Choice },
          onPairingLink = onPairingLink,
        )
      PairingMode.Manual ->
        ManualPairing(
          onBack = { mode = PairingMode.Choice },
          onPair = onManualPairing,
        )
    }
  }
}

@Composable
private fun PairingChoice(onClose: () -> Unit, onScan: () -> Unit, onManual: () -> Unit) {
  PairingPage(
    title = "连接电脑",
    subtitle = "手机和电脑需要连接同一个局域网",
    onBack = onClose,
  ) {
    Text(
      "先在电脑打开 Codex额度，右键托盘图标并选择「连接手机…」",
      modifier = Modifier.padding(horizontal = 4.dp),
      fontSize = CodexTokens.Type.Supporting,
      lineHeight = 17.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PairingSectionTitle("连接方式")
    PairingGlassCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(horizontal = 13.dp)) {
        PairingActionRow(
          title = "扫描二维码",
          supporting = "使用 Codex额度内置相机扫描",
          icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null) },
          onClick = onScan,
        )
        PairingDivider()
        PairingActionRow(
          title = "输入配对码",
          supporting = "无法扫码时输入 Windows 显示的 6 位数字",
          icon = { Icon(Icons.Outlined.Dialpad, contentDescription = null) },
          onClick = onManual,
        )
      }
    }
    Text(
      "二维码和配对码仅限本次连接，5 分钟内有效",
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
      fontSize = CodexTokens.Type.Caption,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PairingScanner(onBack: () -> Unit, onPairingLink: (String) -> Unit) {
  val context = LocalContext.current
  var granted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) {
    if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
  }

  PairingPage(
    title = "扫描二维码",
    subtitle = "对准 Windows 上的配对二维码",
    onBack = onBack,
  ) {
    if (granted) {
      PairingGlassCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Box(modifier = Modifier.fillMaxSize()) {
          CameraPreview(onPairingLink)
          Box(
            modifier =
              Modifier
                .align(Alignment.Center)
                .size(238.dp)
                .border(2.dp, Color.White, RoundedCornerShape(CodexTokens.Radius.Card)),
          )
          Surface(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            shape = CircleShape,
            color = Color(0xB8000000),
          ) {
            Text(
              "二维码会在识别后自动连接",
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
              fontSize = CodexTokens.Type.Supporting,
              color = Color.White,
            )
          }
        }
      }
    } else {
      PairingGlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(20.dp),
          horizontalAlignment = Alignment.Start,
        ) {
          Text(
            "需要相机权限",
            fontSize = CodexTokens.Type.Body,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            "相机画面只在手机本地用于识别二维码，不会保存或上传。",
            modifier = Modifier.padding(top = 5.dp),
            fontSize = CodexTokens.Type.Caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          PairingPrimaryButton(
            label = "允许使用相机",
            enabled = true,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
          ) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
          }
        }
      }
    }
    Text(
      "扫码不可用时，可以返回并输入 6 位配对码",
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
      textAlign = TextAlign.Center,
      fontSize = CodexTokens.Type.Caption,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
@SuppressLint("UnsafeOptInUsageError")
private fun CameraPreview(onPairingLink: (String) -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val gate = remember { PairingScanGate() }
  val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
  val scannerResult =
    remember {
      runCatching {
        BarcodeScanning.getClient(
          BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
      }
    }
  val scanner = scannerResult.getOrNull()
  if (scanner == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "扫码功能暂不可用\n请返回并输入配对码",
        modifier = Modifier.padding(24.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }
  val previewView =
    remember {
      PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
      }
    }

  DisposableEffect(lifecycleOwner) {
    var disposed = false
    val providerFuture = ProcessCameraProvider.getInstance(context)
    val listener =
      Runnable {
        if (disposed) return@Runnable
        runCatching {
          val provider = providerFuture.get()
          val preview =
            Preview.Builder().build().also {
              it.surfaceProvider = previewView.surfaceProvider
            }
          val analysis =
            ImageAnalysis.Builder()
              .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
              .build()
              .also { useCase ->
                useCase.setAnalyzer(cameraExecutor) { imageProxy ->
                  val mediaImage = imageProxy.image
                  if (mediaImage == null) {
                    imageProxy.close()
                  } else {
                    val image =
                      InputImage.fromMediaImage(
                        mediaImage,
                        imageProxy.imageInfo.rotationDegrees,
                      )
                    scanner
                      .process(image)
                      .addOnSuccessListener { barcodes ->
                        barcodes
                          .asSequence()
                          .mapNotNull { gate.accept(it.rawValue) }
                          .firstOrNull()
                          ?.let(onPairingLink)
                      }
                      .addOnCompleteListener { imageProxy.close() }
                  }
                }
              }
          provider.unbindAll()
          provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            analysis,
          )
        }
      }
    providerFuture.addListener(listener, ContextCompat.getMainExecutor(context))
    onDispose {
      disposed = true
      if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
      scanner.close()
      cameraExecutor.shutdown()
    }
  }
  AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ManualPairing(
  onBack: () -> Unit,
  onPair: (PairingDiscovery, String) -> Unit,
) {
  val context = LocalContext.current
  var discoveries by remember { mutableStateOf(emptyList<PairingDiscovery>()) }
  var selectedFingerprint by remember { mutableStateOf<String?>(null) }
  var pairingCode by remember { mutableStateOf("") }
  var expired by remember { mutableStateOf(false) }
  DisposableEffect(Unit) {
    val receiver =
      PairingDiscoveryReceiver { updated ->
        (context as Activity).runOnUiThread {
          discoveries = updated
          if (selectedFingerprint !in updated.map(PairingDiscovery::computerFingerprintHex)) {
            selectedFingerprint = updated.firstOrNull()?.computerFingerprintHex
          }
        }
      }
    receiver.start()
    onDispose { receiver.close() }
  }
  val selected = discoveries.firstOrNull { it.computerFingerprintHex == selectedFingerprint }
  val canPair = pairingCode.length == 6 && selected != null

  PairingPage(
    title = "输入配对码",
    subtitle = "手机会自动查找同一局域网中的电脑",
    onBack = onBack,
    modifier = Modifier.imePadding(),
  ) {
    PairingSectionTitle("配对码")
    PairingGlassCard(modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(
          "输入 Windows 配对窗口中的 6 位数字",
          fontSize = CodexTokens.Type.Caption,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BasicTextField(
          value = pairingCode,
          onValueChange = {
            pairingCode = it.filter(Char::isDigit).take(6)
            expired = false
          },
          modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
          textStyle =
            TextStyle(
              color = Color.Transparent,
              fontSize = 1.sp,
            ),
          singleLine = true,
          cursorBrush = SolidColor(Color.Transparent),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          decorationBox = { inner ->
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(6) { index ->
                  val digit = pairingCode.getOrNull(index)?.toString().orEmpty()
                  val active = index == pairingCode.length && pairingCode.length < 6
                  Column(
                    modifier = Modifier.width(32.dp).height(42.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                  ) {
                    Text(
                      digit,
                      modifier = Modifier.height(32.dp),
                      fontSize = 24.sp,
                      lineHeight = 30.sp,
                      fontWeight = FontWeight.SemiBold,
                      color = MaterialTheme.colorScheme.primary,
                      textAlign = TextAlign.Center,
                    )
                    Box(
                      modifier =
                        Modifier
                          .fillMaxWidth()
                          .height(2.dp)
                          .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .36f),
                            CircleShape,
                          ),
                    )
                  }
                }
              }
              Box(modifier = Modifier.size(1.dp).alpha(0f)) { inner() }
            }
          },
        )
      }
    }

    PairingSectionTitle("确认电脑", modifier = Modifier.padding(top = 3.dp))
    PairingGlassCard(modifier = Modifier.fillMaxWidth()) {
      if (discoveries.isEmpty()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Box(modifier = Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
          Text(
            "正在查找局域网中的电脑…",
            modifier = Modifier.padding(start = 10.dp),
            fontSize = CodexTokens.Type.Body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
          discoveries.forEachIndexed { index, discovery ->
            val selectedItem = discovery.computerFingerprintHex == selectedFingerprint
            Surface(
              modifier =
                Modifier
                  .fillMaxWidth()
                  .clickable { selectedFingerprint = discovery.computerFingerprintHex },
              shape = RoundedCornerShape(CodexTokens.Radius.Button),
              color =
                if (selectedItem) {
                  MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                } else {
                  Color.Transparent
                },
              border =
                if (selectedItem) {
                  BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .32f))
                } else {
                  null
                },
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Outlined.Computer,
                  contentDescription = null,
                  modifier = Modifier.size(22.dp),
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 11.dp)) {
                  Text(
                    "Windows 电脑",
                    fontSize = CodexTokens.Type.Body,
                    fontWeight = FontWeight.SemiBold,
                  )
                  Text(
                    "安全校验码",
                    modifier = Modifier.padding(top = 2.dp),
                    fontSize = CodexTokens.Type.Caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Text(
                  discovery.securityCode,
                  fontSize = CodexTokens.Type.Body,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
            if (index < discoveries.lastIndex) PairingDivider()
          }
        }
      }
    }
    Text(
      if (expired) {
        "配对信息已过期，请在 Windows 刷新配对码"
      } else {
        "请确认手机与 Windows 显示的安全校验码一致"
      },
      modifier = Modifier.padding(horizontal = 4.dp),
      fontSize = CodexTokens.Type.Caption,
      color =
        if (expired) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PairingPrimaryButton(
      label = "校验码一致，连接",
      enabled = canPair,
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
      if (selected != null && selected.expiresAtMs >= System.currentTimeMillis()) {
        onPair(selected, pairingCode)
      } else {
        expired = true
      }
    }
  }
}

@Composable
private fun PairingBackground(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  Box(
    modifier =
      Modifier
        .fillMaxSize()
        .background(
          if (dark) {
            Brush.linearGradient(
              listOf(
                CodexTokens.Color.BackgroundDark,
                CodexTokens.Color.BackgroundMiddleDark,
                Color(0xFF131A1E),
              ),
            )
          } else {
            Brush.linearGradient(
              listOf(Color(0xFFF4F8FA), Color(0xFFEEF2EE), Color(0xFFF7F0E9)),
            )
          },
        ),
  ) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .background(
            Brush.radialGradient(
              colors =
                if (dark) listOf(Color(0x304B7280), Color.Transparent)
                else listOf(Color(0x55D8EBF8), Color.Transparent),
              center = Offset.Zero,
              radius = 860f,
            ),
          ),
    )
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
      content()
    }
  }
}

@Composable
private fun PairingPage(
  title: String,
  subtitle: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .statusBarsPadding()
        .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(15.dp),
  ) {
    PairingHeader(title, subtitle, onBack)
    content()
  }
}

@Composable
private fun PairingHeader(title: String, subtitle: String, onBack: () -> Unit) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Surface(
      onClick = onBack,
      modifier = Modifier.size(40.dp),
      shape = CircleShape,
      color = PairingGlassSurface(alpha = .54f),
      border = BorderStroke(1.dp, PairingGlassBorder()),
      shadowElevation = 1.dp,
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          Icons.AutoMirrored.Outlined.ArrowBack,
          contentDescription = "返回",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 1.dp)) {
      Text(
        title,
        fontSize = CodexTokens.Type.PageTitle,
        lineHeight = 29.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1.0).sp,
      )
      Text(
        subtitle,
        modifier = Modifier.padding(top = 3.dp),
        fontSize = CodexTokens.Type.Supporting,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun PairingSectionTitle(title: String, modifier: Modifier = Modifier) {
  Text(
    title,
    modifier = modifier.padding(start = 4.dp, bottom = 7.dp),
    fontSize = CodexTokens.Type.SectionTitle,
    fontWeight = FontWeight.SemiBold,
  )
}

@Composable
private fun PairingActionRow(
  title: String,
  supporting: String,
  icon: @Composable () -> Unit,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      modifier = Modifier.size(38.dp),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primary.copy(alpha = .10f),
      contentColor = MaterialTheme.colorScheme.primary,
    ) {
      Box(modifier = Modifier.padding(9.dp), contentAlignment = Alignment.Center) { icon() }
    }
    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
      Text(title, fontSize = CodexTokens.Type.Body, fontWeight = FontWeight.SemiBold)
      Text(
        supporting,
        modifier = Modifier.padding(top = 3.dp),
        fontSize = CodexTokens.Type.Caption,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Icon(
      Icons.Outlined.ChevronRight,
      contentDescription = null,
      modifier = Modifier.size(18.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun PairingPrimaryButton(
  label: String,
  enabled: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Surface(
    modifier = modifier.height(48.dp).clickable(enabled = enabled, onClick = onClick),
    shape = RoundedCornerShape(CodexTokens.Radius.Button),
    color =
      if (enabled) MaterialTheme.colorScheme.primary
      else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .18f),
    contentColor =
      if (enabled) MaterialTheme.colorScheme.onPrimary
      else MaterialTheme.colorScheme.onSurfaceVariant,
    shadowElevation = if (enabled) 1.dp else 0.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(label, fontSize = CodexTokens.Type.Body, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun PairingGlassCard(
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val shape = RoundedCornerShape(CodexTokens.Radius.Card)
  Box(
    modifier =
      modifier
        .shadow(1.dp, shape)
        .clip(shape)
        .background(PairingGlassSurface())
        .border(1.dp, PairingGlassBorder(), shape),
    content = { content() },
  )
}

@Composable
private fun PairingDivider() =
  HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .15f))

@Composable
private fun PairingGlassSurface(alpha: Float = .56f): Color =
  if (isSystemInDarkTheme()) {
    when {
      alpha >= .7f -> CodexTokens.Color.SurfaceStrongDark
      alpha >= .55f -> CodexTokens.Color.SurfaceElevatedDark
      else -> CodexTokens.Color.SurfaceDark
    }
  } else {
    if (alpha >= .7f) Color(0xFFF9FBFB) else Color(0xFFF6F9F9)
  }

@Composable
private fun PairingGlassBorder(): Color =
  if (isSystemInDarkTheme()) CodexTokens.Color.GlassBorderDark
  else CodexTokens.Color.GlassBorderLight
