package de.yanneckreiss.cameraxtutorial.ui.features.camera.photo_capture

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.widget.TextView
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import de.yanneckreiss.cameraxtutorial.R
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.EglCore
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.FullFrameRect
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.Texture2dProgram
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.WindowSurface
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.Executor

class TestSurfaceProcessor: SurfaceProcessor, SurfaceTexture.OnFrameAvailableListener {
    private final val TAG = TestSurfaceProcessor::class.java.simpleName
    var executor = Executor{}
    private val handler = Handler(Looper.getMainLooper())
    private var mEglCore: EglCore? = null
    private var mDisplaySurface: WindowSurface? = null
    private var surfaceTexture: SurfaceTexture? =
        null // receives the output from the camera preview
    private var mFullFrameBlit: FullFrameRect? = null
    private val mTmpMatrix = FloatArray(16)
    private var mTextureId = 0

    override fun onInputSurface(request: SurfaceRequest) {
        Log.d(TAG, "onInputSurface ")

        // Set up everything that requires an EGL context.
        //
        // We had to wait until we had a surface because you can't make an EGL context current
        // without one, and creating a temporary 1x1 pbuffer is a waste of time.
        //
        // The display surface that we use for the SurfaceView, and the encoder surface we
        // use for video, use the same EGL context.
        mEglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
        mFullFrameBlit = FullFrameRect(
            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
        )
        mTextureId = mFullFrameBlit!!.createTextureObject()
        surfaceTexture = SurfaceTexture(mTextureId)

        // Create Surface based on the request.
        //val surfaceTexture = SurfaceTexture(textureName)
        surfaceTexture!!.setDefaultBufferSize(request.resolution.width, request.resolution.height)
        val surface = Surface(surfaceTexture);

        // Provide the Surface to CameraX, and cleanup when it's no longer used.
        request.provideSurface(surface, executor) {
            surfaceTexture!!.setOnFrameAvailableListener(null)
            surfaceTexture!!.release()
            surface.release()
        }

        // Listen to the incoming frames.
        surfaceTexture!!.setOnFrameAvailableListener(
            this, // Process the incoming frames and draw to the output Surface from #onOutputSurface
            handler);
    }


//    override fun surfaceCreated(holder: SurfaceHolder) {
//        Log.d(ContinuousCaptureActivity.TAG, "surfaceCreated holder=$holder")
//
//        // Set up everything that requires an EGL context.
//        //
//        // We had to wait until we had a surface because you can't make an EGL context current
//        // without one, and creating a temporary 1x1 pbuffer is a waste of time.
//        //
//        // The display surface that we use for the SurfaceView, and the encoder surface we
//        // use for video, use the same EGL context.
//        mEglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
//        mDisplaySurface = WindowSurface(mEglCore, holder.getSurface(), false)
//        mDisplaySurface!!.makeCurrent()
//        mFullFrameBlit = FullFrameRect(
//            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
//        )
//        mTextureId = mFullFrameBlit!!.createTextureObject()
//        surfaceTexture = SurfaceTexture(mTextureId)
//        surfaceTexture!!.setOnFrameAvailableListener(this)
//        startPreview()
//    }


    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        TODO("Not yet implemented")
    }

    override fun onFrameAvailable(p0: SurfaceTexture?) {
        TODO("Not yet implemented")
    }
}
