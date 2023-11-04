package de.cameraxtutorial.ui.features.camera.photo_capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraEffect.PREVIEW
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Card
import androidx.compose.material.ExtendedFloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mylibrarycamera.ToneMappingSurfaceEffect
import com.example.mylibrarycamera.ToneMappingSurfaceProcessor
import de.cameraxtutorial.core.utils.rotateBitmap
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor


@Composable
fun CameraScreen(
    viewModel: CameraViewModel = koinViewModel()
) {
    val cameraState: CameraState by viewModel.state.collectAsStateWithLifecycle()

    CameraContent(
        onPhotoCaptured = viewModel::storePhotoInGallery,
        lastCapturedPhoto = cameraState.capturedImage
    )
}

@OptIn(ExperimentalCamera2Interop::class) @Composable
private fun CameraContent(
    onPhotoCaptured: (Bitmap) -> Unit,
    lastCapturedPhoto: Bitmap? = null
) {
    // Set up the surface processor.
    var surfaceProcessor: ToneMappingSurfaceProcessor = remember { ToneMappingSurfaceProcessor() }
    val context: Context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current
    val cameraController: LifecycleCameraController = remember { LifecycleCameraController(context) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(text = "Take photo") },
                onClick = {
                    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    val current = LocalDateTime.now().format(formatter)
                   // captureVideo(context, cameraController, onPhotoCaptured)
                    surfaceProcessor.clickCapture(File(context.filesDir, "continuous-capture($current).mp4")) },
                icon = { Icon(imageVector = Icons.Default.Camera, contentDescription = "Camera capture icon") }
            )
        }
    ) { paddingValues: PaddingValues ->

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                factory = { context ->
                    PreviewView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                        setBackgroundColor(Color.BLACK)
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER

                    }.also { previewView ->
                        // Create a ResolutionSelector object.
                        val resolutionSelector = ResolutionSelector.Builder()
                            //.setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY.)
                            .setResolutionStrategy(ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_NONE))
                            .build()
                        val preview = androidx.camera.core.Preview.Builder()
                            .setTargetFrameRate(Range(30,30))
                            .setResolutionSelector(resolutionSelector)
                            .build()
                        var surfaceEffectTarget = 0
                        surfaceEffectTarget = PREVIEW
                        val effect = mutableSetOf<CameraEffect>()
                        effect.add(ToneMappingSurfaceEffect(surfaceEffectTarget, surfaceProcessor))
                        cameraController.setEffects(effect)
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        previewView.controller = cameraController
                        cameraController.bindToLifecycle(lifecycleOwner)
                    }
                }
            )

//            if (lastCapturedPhoto != null) {
//                LastPhotoPreview(
//                    modifier = Modifier.align(alignment = BottomStart),
//                    lastCapturedPhoto = lastCapturedPhoto
//                )
//            }
        }
    }
}

private fun captureVideo(
    context: Context,
    cameraController: LifecycleCameraController,
    onPhotoCaptured: (Bitmap) -> Unit
) {
    val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    cameraController.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
            val correctedBitmap: Bitmap = image
                .toBitmap()
                .rotateBitmap(image.imageInfo.rotationDegrees)

            onPhotoCaptured(correctedBitmap)
            image.close()
        }

        override fun onError(exception: ImageCaptureException) {
            Log.e("CameraContent", "Error capturing image", exception)
        }
    })
}

@Composable
private fun LastPhotoPreview(
    modifier: Modifier = Modifier,
    lastCapturedPhoto: Bitmap
) {

    val capturedPhoto: ImageBitmap = remember(lastCapturedPhoto.hashCode()) { lastCapturedPhoto.asImageBitmap() }

    Card(
        modifier = modifier
            .size(128.dp)
            .padding(16.dp),
        elevation = 8.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Image(
            bitmap = capturedPhoto,
            contentDescription = "Last captured photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
    }
}

@Preview
@Composable
private fun Preview_CameraContent() {
    CameraContent(
        onPhotoCaptured = {}
    )
}
