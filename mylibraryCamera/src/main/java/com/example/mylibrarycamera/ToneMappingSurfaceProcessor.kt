/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.mylibrarycamera

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.example.mylibrarycamera.gles.EglCore
import com.example.mylibrarycamera.gles.FullFrameRect
import com.example.mylibrarycamera.gles.ShaderProvider
import com.example.mylibrarycamera.gles.Texture2dProgram
import com.example.mylibrarycamera.gles.WindowSurface
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

/**
 * A processor that applies tone mapping on camera output.
 *
 * <p>The thread safety is guaranteed by using the main thread.
 */
class ToneMappingSurfaceProcessor() : SurfaceProcessor, OnFrameAvailableListener {
    private val VIDEO_WIDTH = 1280 // dimensions for 720p video
    private val VIDEO_HEIGHT = 720
    private val DESIRED_PREVIEW_FPS = 15
    private val TAG = ToneMappingSurfaceProcessor::class.simpleName
    private val VERBOSE = true
    private val mTmpMatrix = FloatArray(16)
    private var mFrameNum = 0
    private var mFileSaveInProgress = false

    /**
     * Custom message handler for main UI thread.
     *
     *
     * Used to handle camera preview "frame available" notifications, and implement the
     * blinking "recording" text.  Receives callback messages from the encoder thread.
     */
    private class MyHandler(
        val processor: ToneMappingSurfaceProcessor,
        looper: android.os.Looper
    ) : Handler(looper),
        CircularEncoder.Callback {
//        private val mWeakActivity: WeakReference<ContinuousCaptureActivity>

        init {
//            mWeakActivity = WeakReference<ContinuousCaptureActivity>(activity)
        }

        // CircularEncoder.Callback, called on encoder thread
        override fun fileSaveComplete(status: Int) {
            sendMessage(obtainMessage(MSG_FILE_SAVE_COMPLETE, status, 0, null))
        }

        // CircularEncoder.Callback, called on encoder thread
        override fun bufferStatus(totalTimeMsec: Long) {
            sendMessage(
                obtainMessage(
                    MSG_BUFFER_STATUS,
                    (totalTimeMsec shr 32).toInt(),
                    totalTimeMsec.toInt()
                )
            )
        }

        override fun handleMessage(msg: Message) {
//            val activity: ContinuousCaptureActivity? = mWeakActivity.get()
//            if (activity == null) {
//                Log.d(ContinuousCaptureActivity.TAG, "Got message for dead activity")
//                return
//            }
            when (msg.what) {
                MSG_BLINK_TEXT -> {
//                    val tv = activity.findViewById<View>(R.id.recording_text) as TextView
//
//                    // Attempting to make it blink by using setEnabled() doesn't work --
//                    // it just changes the color.  We want to change the visibility.
//                    var visibility = tv.visibility
//                    visibility = if (visibility == View.VISIBLE) {
//                        View.INVISIBLE
//                    } else {
//                        View.VISIBLE
//                    }
//                    tv.visibility = visibility
//                    val delay = if (visibility == View.VISIBLE) 1000 else 200
//                    sendEmptyMessageDelayed(MSG_BLINK_TEXT, delay.toLong())
                }

                MSG_FRAME_AVAILABLE -> {
                    //activity.drawFrame()
                }

                MSG_FILE_SAVE_COMPLETE -> {
                    processor.fileSaveComplete(msg.arg1)
                }

                MSG_BUFFER_STATUS -> {
//                    val duration = msg.arg1.toLong() shl 32 or
//                            (msg.arg2.toLong() and 0xffffffffL)
//                    activity.updateBufferStatus(duration)
                }

                else -> throw java.lang.RuntimeException("Unknown message " + msg.what)
            }
        }

        companion object {
            const val MSG_BLINK_TEXT = 0
            const val MSG_FRAME_AVAILABLE = 1
            const val MSG_FILE_SAVE_COMPLETE = 2
            const val MSG_BUFFER_STATUS = 3
        }
    }
    companion object {
//        // A fragment shader that applies a yellow hue.
        private val TONE_MAPPING_SHADER_PROVIDER = object : ShaderProvider {
            override fun createFragmentShader(sampler: String, fragCoords: String): String {
//                return """
//                    #extension GL_OES_EGL_image_external : require
//                    precision mediump float;
//                    uniform samplerExternalOES $sampler;
//                    varying vec2 $fragCoords;
//                    void main() {
//                      vec4 sampleColor = texture2D($sampler, $fragCoords);
//                      gl_FragColor = vec4(
//                           sampleColor.r * 0.5 + sampleColor.g * 0.8 + sampleColor.b * 0.3,
//                           sampleColor.r * 0.4 + sampleColor.g * 0.7 + sampleColor.b * 0.2,
//                           sampleColor.r * 0.3 + sampleColor.g * 0.5 + sampleColor.b * 0.1,
//                           1.0);
//                     }
//                    """
                return """
                    #extension GL_OES_EGL_image_external : require
                    precision mediump float;
                    uniform samplerExternalOES $sampler;
                    varying vec2 $fragCoords;
                    void main() {
                      vec4 sampleColor = texture2D($sampler, $fragCoords);
                      gl_FragColor = sampleColor;
                     }
                    """
            }
        }

        private const val GL_THREAD_NAME = "ToneMappingSurfaceProcessor"
    }

    private val glThread: HandlerThread = HandlerThread(GL_THREAD_NAME)
    private var glHandler: Handler
    private var glExecutor: Executor

    // Members below are only accessed on GL thread.
    private val glRenderer: OpenGlRenderer = OpenGlRenderer()
    private val outputSurfaces: MutableMap<SurfaceOutput, Surface> = mutableMapOf()
    private val textureTransform: FloatArray = FloatArray(16)
    private val surfaceTransform: FloatArray = FloatArray(16)
    private var isReleased = false
    private var mFullFrameBlit: FullFrameRect? = null
    private var mTextureId = 0

    private var surfaceRequested = false
    private var outputSurfaceProvided = false
    private lateinit var mEglCore: EglCore
    private lateinit var mCircEncoder: CircularEncoder
    private lateinit var mEncoderSurface: WindowSurface
    private lateinit var mDisplaySurface: WindowSurface
    init {
        glThread.start()
        glHandler = MyHandler(this, glThread.looper )
        //glExecutor = Executor {  }
        glExecutor = MyHandlerScheduledExecutorService(glHandler)
        glExecutor.execute {
            //glRenderer.init(SDR, TONE_MAPPING_SHADER_PROVIDER)

            // Set up everything that requires an EGL context.
            //
            // We had to wait until we had a surface because you can't make an EGL context current
            // without one, and creating a temporary 1x1 pbuffer is a waste of time.
            //
            // The display surface that we use for the SurfaceView, and the encoder surface we
            // use for video, use the same EGL context.

            // TODO: adjust bit rate based on frame rate?
            // TODO: adjust video width/height based on what we're getting from the camera preview?
            //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
            try {
                mCircEncoder = CircularEncoder(
                    VIDEO_WIDTH, //TODO make parameter of ToneMappingSurfaceProcessor
                    VIDEO_HEIGHT, //TODO make parameter of ToneMappingSurfaceProcessor
                    6000000, //TODO make parameter of ToneMappingSurfaceProcessor
                    30, //TODO make parameter of ToneMappingSurfaceProcessor
                    10, //TODO make parameter of ToneMappingSurfaceProcessor
                    glHandler as MyHandler
                )
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            }
            mEglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
            mEncoderSurface = WindowSurface(
                mEglCore,
                mCircEncoder.inputSurface,
                true
            ) // buffer producer after camera to save
            mEncoderSurface.makeCurrent()

            mFullFrameBlit = FullFrameRect(
                Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
            )
            mTextureId = mFullFrameBlit!!.createTextureObject()
        }
    }

    override fun onInputSurface(surfaceRequest: SurfaceRequest) {
        checkGlThread()
        if (isReleased) {
            surfaceRequest.willNotProvideSurface()
            return
        }
        surfaceRequested = true
        mFullFrameBlit = FullFrameRect(
            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
        )
        mTextureId = mFullFrameBlit!!.createTextureObject()
        val surfaceTexture = SurfaceTexture(mTextureId)
        surfaceTexture.setDefaultBufferSize(
            surfaceRequest.resolution.width, surfaceRequest.resolution.height
        )
        val surface = Surface(surfaceTexture)
        surfaceRequest.provideSurface(surface, glExecutor) {
            surfaceTexture.setOnFrameAvailableListener(null)
            surfaceTexture.release()
            surface.release()
        }
        surfaceTexture.setOnFrameAvailableListener(this, glHandler)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        checkGlThread()
        outputSurfaceProvided = true
        if (isReleased) {
            surfaceOutput.close()
            return
        }
        val surface = surfaceOutput.getSurface(glExecutor) {
            surfaceOutput.close()
            outputSurfaces.remove(surfaceOutput)//?.let { removedSurface ->
                //glRenderer.unregisterOutputSurface(removedSurface)
            //}
        }
        //glRenderer.registerOutputSurface(surface)
        outputSurfaces[surfaceOutput] = surface
        mDisplaySurface = WindowSurface(
            mEglCore,
            surface,
            false
        ) // buffer producer after camera to display

    }

    @VisibleForTesting
    fun isSurfaceRequestedAndProvided(): Boolean {
        return surfaceRequested && outputSurfaceProvided
    }

    fun release() {
        glExecutor.execute {
            releaseInternal()
        }
    }

    private fun releaseInternal() {
        checkGlThread()
        if (!isReleased) {
            // Once release is called, we can stop sending frame to output surfaces.
            for (surfaceOutput in outputSurfaces.keys) {
                surfaceOutput.close()
            }
            outputSurfaces.clear()
            //glRenderer.release()
            glThread.quitSafely()
            isReleased = true
        }
    }

    private fun checkGlThread() {
        if(GL_THREAD_NAME != Thread.currentThread().name)
            throw IllegalStateException("checkGlThread IllegalStateException")
    }

    fun getGlExecutor(): Executor {
        return glExecutor
    }

    var previusOutputSurfacesEntries = 0;
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        checkGlThread()
        if (isReleased) {
            return
        }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureTransform)
        if (VERBOSE && (previusOutputSurfacesEntries != outputSurfaces.entries.size))
        {
            Log.d(TAG, "onFrameAvailable: outputSurfaces.entries.size = " + outputSurfaces.entries.size)
            previusOutputSurfacesEntries = outputSurfaces.entries.size;
        }
        for (entry in outputSurfaces.entries.iterator()) {
            val surface = entry.value
            val surfaceOutput = entry.key

            surfaceOutput.updateTransformMatrix(surfaceTransform, textureTransform)
            //glRenderer.render(surfaceTexture.timestamp, surfaceTransform, surface)
            drawFrame(surfaceTexture.timestamp, surfaceTransform)
        }
    }

    /**
     * Draws a frame onto the SurfaceView and the encoder surface.
     *
     *
     * This will be called whenever we get a new preview frame from the camera.  This runs
     * on the UI thread, which ordinarily isn't a great idea -- you really want heavy work
     * to be on a different thread -- but we're really just throwing a few things at the GPU.
     * The upside is that we don't have to worry about managing state changes between threads.
     *
     *
     * If there was a pending frame available notification when we shut down, we might get
     * here after onPause().
     */
    private fun drawFrame(
        timestamp: Long,
        textureTransform: FloatArray,
    ) {
        //Log.d(TAG, "drawFrame");
        if (mEglCore == null) {
            Log.d(TAG, "mEglCore == null")
            return
        }

        // Fill the SurfaceView with it.
        mDisplaySurface.makeCurrent()
        // Latch the next frame from the camera.
        GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)
        mFullFrameBlit!!.drawFrame(mTextureId, textureTransform)
        drawExtra(mFrameNum, VIDEO_WIDTH, VIDEO_HEIGHT)

        mDisplaySurface.swapBuffers()

        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface.makeCurrent()
            GLES20.glViewport(
                0,
                0,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            )
            mFullFrameBlit!!.drawFrame(mTextureId, textureTransform)
            drawExtra(
                mFrameNum,
                VIDEO_WIDTH,
                VIDEO_HEIGHT
            )
            mCircEncoder.frameAvailableSoon()
            mEncoderSurface.setPresentationTime(timestamp)
            mEncoderSurface.swapBuffers()
        }
        mFrameNum++
    }

    /**
     * Adds a bit of extra stuff to the display just to give it flavor.
     */
    private fun drawExtra(frameNum: Int, width: Int, height: Int) {
        // We "draw" with the scissor rect and clear calls.  Note this uses window coordinates.
        when (frameNum % 3) {
            0 -> GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f)
            1 -> GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
            2 -> GLES20.glClearColor(0.0f, 0.0f, 1.0f, 1.0f)
        }
        val xpos = (width * (frameNum % 100 / 100.0f)).toInt()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(xpos, 0, width / 32, height / 32)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }


    /**
     * Handles onClick for "capture" button.
     */
    fun clickCapture( mOutputFile: File) {
        Log.d(TAG, "capture")
        if (mFileSaveInProgress) {
            Log.w(TAG, "HEY: file save is already in progress")
            return
        }
        // The button is disabled in onCreate(), and not enabled until the encoder and output
        // surface is ready, so it shouldn't be possible to get here with a null mCircEncoder.
        mFileSaveInProgress = true
        mCircEncoder.saveVideo(mOutputFile)
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private fun fileSaveComplete(status: Int) {
        Log.d(TAG, "fileSaveComplete $status")
        if (!mFileSaveInProgress) {
            throw java.lang.RuntimeException("WEIRD: got fileSaveComplete when not in progress")
        }
        mFileSaveInProgress = false

//        val tv = findViewById<View>(R.id.recording_text) as TextView
//        var str: String? = getString(R.string.nowRecording)
//        tv.text = str
//        if (status == 0) {
//            str = getString(R.string.recordingSucceeded)
//        } else {
//            str = getString(R.string.recordingFailed, status)
//        }
//        val toast: Toast = Toast.makeText(this, str, Toast.LENGTH_SHORT)
//        toast.show()
    }
}
