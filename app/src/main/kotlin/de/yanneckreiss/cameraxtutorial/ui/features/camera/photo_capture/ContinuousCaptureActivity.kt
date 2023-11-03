/*
 * Copyright 2014 Google Inc. All rights reserved.
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
package de.yanneckreiss.cameraxtutorial.ui.features.camera.photo_capture

import android.app.Activity
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.opengl.GLES20
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.camera.core.CameraX
import de.yanneckreiss.cameraxtutorial.R
import de.yanneckreiss.cameraxtutorial.ui.AspectFrameLayout
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.EglCore
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.FullFrameRect
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.Texture2dProgram
import de.yanneckreiss.cameraxtutorial.ui.features.camera.gles.WindowSurface
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference

/**
 * Demonstrates capturing video into a ring buffer.  When the "capture" button is clicked,
 * the buffered video is saved.
 *
 *
 * Capturing and storing raw frames would be slow and require lots of memory.  Instead, we
 * feed the frames into the video encoder and buffer the output.
 *
 *
 * Whenever we receive a new frame from the camera, our SurfaceTexture callback gets
 * notified.  That can happen on an arbitrary thread, so we use it to send a message
 * through our Handler.  That causes us to render the new frame to the display and to
 * our video encoder.
 */
class ContinuousCaptureActivity : Activity(), SurfaceHolder.Callback,
    SurfaceTexture.OnFrameAvailableListener {
    private var mEglCore: EglCore? = null
    private var mDisplaySurface: WindowSurface? = null
    private var mCameraTexture: SurfaceTexture? =
        null // receives the output from the camera preview
    private var mFullFrameBlit: FullFrameRect? = null
    private val mTmpMatrix = FloatArray(16)
    private var mTextureId = 0
    private var mFrameNum = 0
    private var mCamera: Camera? = null
    private var mCameraX: CameraX? = null
    private var mCameraPreviewThousandFps = 0
    private var mOutputFile: File? = null
    private var mCircEncoder: CircularEncoder? = null
    private var mEncoderSurface: WindowSurface? = null
    private var mFileSaveInProgress = false
    private var mHandler: MainHandler? = null
    private var mSecondsOfVideo = 0f

    /**
     * Custom message handler for main UI thread.
     *
     *
     * Used to handle camera preview "frame available" notifications, and implement the
     * blinking "recording" text.  Receives callback messages from the encoder thread.
     */
    private class MainHandler(activity: ContinuousCaptureActivity) : Handler(),
        CircularEncoder.Callback {
        private val mWeakActivity: WeakReference<ContinuousCaptureActivity>

        init {
            mWeakActivity = WeakReference(activity)
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
            val activity = mWeakActivity.get()
            if (activity == null) {
                Log.d(TAG, "Got message for dead activity")
                return
            }
            when (msg.what) {
                MSG_BLINK_TEXT -> {
                    val tv: TextView = activity.findViewById<View>(R.id.recording_text) as TextView

                    // Attempting to make it blink by using setEnabled() doesn't work --
                    // it just changes the color.  We want to change the visibility.
                    var visibility: Int = tv.getVisibility()
                    visibility = if (visibility == View.VISIBLE) {
                        View.INVISIBLE
                    } else {
                        View.VISIBLE
                    }
                    tv.setVisibility(visibility)
                    val delay = if (visibility == View.VISIBLE) 1000 else 200
                    sendEmptyMessageDelayed(MSG_BLINK_TEXT, delay.toLong())
                }

                MSG_FRAME_AVAILABLE -> {
                    activity.drawFrame()
                }

                MSG_FILE_SAVE_COMPLETE -> {
                    activity.fileSaveComplete(msg.arg1)
                }

                MSG_BUFFER_STATUS -> {
                    val duration = msg.arg1.toLong() shl 32 or
                            (msg.arg2.toLong() and 0xffffffffL)
                    activity.updateBufferStatus(duration)
                }

                else -> throw RuntimeException("Unknown message " + msg.what)
            }
        }

        companion object {
            const val MSG_BLINK_TEXT = 0
            const val MSG_FRAME_AVAILABLE = 1
            const val MSG_FILE_SAVE_COMPLETE = 2
            const val MSG_BUFFER_STATUS = 3
        }
    }

    protected override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_continuous_capture)
        val sv: SurfaceView = findViewById<View>(R.id.continuousCapture_surfaceView) as SurfaceView
        val sh: SurfaceHolder = sv.getHolder()
        sh.addCallback(this)
        mHandler = MainHandler(this)
        mHandler!!.sendEmptyMessageDelayed(MainHandler.MSG_BLINK_TEXT, 1500)
        mOutputFile = File(getFilesDir(), "continuous-capture.mp4")
        mSecondsOfVideo = 0.0f
        updateControls()
    }

    protected override fun onResume() {
        super.onResume()
        if (!PermissionHelper.hasCameraPermission(this)) {
            PermissionHelper.requestCameraPermission(this, false)
        } else {
            if (mCamera == null) {
                // Ideally, the frames from the camera are at the same resolution as the input to
                // the video encoder so we don't have to scale.
                openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS)
            }
            if (mEglCore != null) {
                startPreview()
            }
        }
    }

    protected override fun onPause() {
        super.onPause()
        releaseCamera()
        if (mCircEncoder != null) {
            mCircEncoder!!.shutdown()
            mCircEncoder = null
        }
        if (mCameraTexture != null) {
            mCameraTexture!!.release()
            mCameraTexture = null
        }
        if (mDisplaySurface != null) {
            mDisplaySurface!!.release()
            mDisplaySurface = null
        }
        if (mFullFrameBlit != null) {
            mFullFrameBlit!!.release(false)
            mFullFrameBlit = null
        }
        if (mEglCore != null) {
            mEglCore!!.release()
            mEglCore = null
        }
        Log.d(TAG, "onPause() done")
    }

    /**
     * Opens a camera, and attempts to establish preview mode at the specified width and height.
     *
     *
     * Sets mCameraPreviewFps to the expected frame rate (which might actually be variable).
     */
    private fun openCamera(desiredWidth: Int, desiredHeight: Int, desiredFps: Int) {
        if (mCamera != null) {
            throw RuntimeException("camera already initialized")
        }
        val info = Camera.CameraInfo()

        // Try to find a front-facing camera (e.g. for videoconferencing).
        val numCameras = Camera.getNumberOfCameras()
        for (i in 0 until numCameras) {
            Camera.getCameraInfo(i, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                mCamera = Camera.open(i)
                break
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default")
            mCamera = Camera.open() // opens first back-facing camera
        }
        if (mCamera == null) {
            throw RuntimeException("Unable to open camera")
        }
        val parms = mCamera!!.parameters
        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight)

        // Try to set the frame rate to a constant value.
        mCameraPreviewThousandFps = CameraUtils.chooseFixedPreviewFps(parms, desiredFps * 1000)

        // Give the camera a hint that we're recording video.  This can have a big
        // impact on frame rate.
        parms.setRecordingHint(true)
        mCamera!!.parameters = parms
        val cameraPreviewSize = parms.previewSize
        val previewFacts = cameraPreviewSize.width.toString() + "x" + cameraPreviewSize.height +
                " @" + mCameraPreviewThousandFps / 1000.0f + "fps"
        Log.i(TAG, "Camera config: $previewFacts")
        val layout: AspectFrameLayout =
            findViewById<View>(R.id.continuousCapture_afl) as AspectFrameLayout
        val display: Display =
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).getDefaultDisplay()
        if (display.rotation == Surface.ROTATION_0) {
            mCamera!!.setDisplayOrientation(90)
            layout.setAspectRatio(cameraPreviewSize.height.toDouble() / cameraPreviewSize.width)
        } else if (display.rotation == Surface.ROTATION_270) {
            layout.setAspectRatio(cameraPreviewSize.height.toDouble() / cameraPreviewSize.width)
            mCamera!!.setDisplayOrientation(180)
        } else {
            // Set the preview aspect ratio.
            layout.setAspectRatio(cameraPreviewSize.width.toDouble() / cameraPreviewSize.height)
        }
    }

    /**
     * Stops camera preview, and releases the camera to the system.
     */
    private fun releaseCamera() {
        if (mCamera != null) {
            mCamera!!.stopPreview()
            mCamera!!.release()
            mCamera = null
            Log.d(TAG, "releaseCamera -- done")
        }
    }

    /**
     * Updates the current state of the controls.
     */
    private fun updateControls() {
        val str: String = getString(R.string.secondsOfVideo, mSecondsOfVideo)
        val tv: TextView = findViewById<View>(R.id.capturedVideoDesc_text) as TextView
        tv.setText(str)
        val wantEnabled = mCircEncoder != null && !mFileSaveInProgress
        val button = findViewById<View>(R.id.capture_button) as Button
        if (button.isEnabled != wantEnabled) {
            Log.d(TAG, "setting enabled = $wantEnabled")
            button.isEnabled = wantEnabled
        }
    }

    /**
     * Handles onClick for "capture" button.
     */
    fun clickCapture(@Suppress("unused") unused: View?) {
        Log.d(TAG, "capture")
        if (mFileSaveInProgress) {
            Log.w(TAG, "HEY: file save is already in progress")
            return
        }

        // The button is disabled in onCreate(), and not enabled until the encoder and output
        // surface is ready, so it shouldn't be possible to get here with a null mCircEncoder.
        mFileSaveInProgress = true
        updateControls()
        val tv: TextView = findViewById<View>(R.id.recording_text) as TextView
        val str: String = getString(R.string.nowSaving)
        tv.setText(str)
        mCircEncoder!!.saveVideo(mOutputFile)
    }

    /**
     * The file save has completed.  We can resume recording.
     */
    private fun fileSaveComplete(status: Int) {
        Log.d(TAG, "fileSaveComplete $status")
        if (!mFileSaveInProgress) {
            throw RuntimeException("WEIRD: got fileSaveComplete when not in progress")
        }
        mFileSaveInProgress = false
        updateControls()
        val tv: TextView = findViewById<View>(R.id.recording_text) as TextView
        var str: String? = getString(R.string.nowRecording)
        tv.setText(str)
        if (status == 0) {
            str = getString(R.string.recordingSucceeded)
        } else {
            str = getString(R.string.recordingFailed, status)
        }
        val toast: Toast = Toast.makeText(this, str, Toast.LENGTH_SHORT)
        toast.show()
    }

    /**
     * Updates the buffer status UI.
     */
    private fun updateBufferStatus(durationUsec: Long) {
        mSecondsOfVideo = durationUsec / 1000000.0f
        updateControls()
    }

    // SurfaceHolder.Callback - set up surface for camera data acquisition(mCameraTexture),
    // and surface for preview(mDisplaySurface)
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceCreated holder=$holder")

        // Set up everything that requires an EGL context.
        //
        // We had to wait until we had a surface because you can't make an EGL context current
        // without one, and creating a temporary 1x1 pbuffer is a waste of time.
        //
        // The display surface that we use for the SurfaceView, and the encoder surface we
        // use for video, use the same EGL context.
        mEglCore = EglCore(null, EglCore.FLAG_RECORDABLE)
        mDisplaySurface = WindowSurface(mEglCore!!, holder.getSurface(), false)
        mDisplaySurface!!.makeCurrent()
        mFullFrameBlit = FullFrameRect(
            Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT)
        )
        mTextureId = mFullFrameBlit!!.createTextureObject()
        mCameraTexture = SurfaceTexture(mTextureId)
        mCameraTexture!!.setOnFrameAvailableListener(this)
        startPreview()
    }

    private fun startPreview() {
        if (mCamera != null) {
            Log.d(TAG, "starting camera preview")
            try {
                mCamera!!.setPreviewTexture(mCameraTexture)
            } catch (ioe: IOException) {
                throw RuntimeException(ioe)
            }
            mCamera!!.startPreview()
        }

        // TODO: adjust bit rate based on frame rate?
        // TODO: adjust video width/height based on what we're getting from the camera preview?
        //       (can we guarantee that camera preview size is compatible with AVC video encoder?)
        mCircEncoder = try {
            CircularEncoder(
                VIDEO_WIDTH, VIDEO_HEIGHT, 6000000,
                mCameraPreviewThousandFps / 1000, 10, mHandler!!
            )
        } catch (ioe: IOException) {
            throw RuntimeException(ioe)
        }
        mEncoderSurface = WindowSurface(mEglCore!!, mCircEncoder!!.inputSurface, true)
        updateControls()
    }

    // SurfaceHolder.Callback
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(
            TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height +
                    " holder=" + holder
        )
    }

    // SurfaceHolder.Callback
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "surfaceDestroyed holder=$holder")
    }

    // SurfaceTexture.OnFrameAvailableListener; runs on arbitrary thread
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        //Log.d(TAG, "frame available");
        mHandler!!.sendEmptyMessage(MainHandler.MSG_FRAME_AVAILABLE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this,
                "Camera permission is needed to run this application", Toast.LENGTH_LONG
            ).show()
            PermissionHelper.launchPermissionSettings(this)
            finish()
        } else {
            openCamera(VIDEO_WIDTH, VIDEO_HEIGHT, DESIRED_PREVIEW_FPS)
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
    private fun drawFrame() {
        //Log.d(TAG, "drawFrame");
        if (mEglCore == null) {
            Log.d(TAG, "Skipping drawFrame after shutdown")
            return
        }

        // Latch the next frame from the camera.
        mDisplaySurface?.makeCurrent()
        mCameraTexture?.updateTexImage()
        mCameraTexture?.getTransformMatrix(mTmpMatrix)

        // Fill the SurfaceView with it.
        val sv: SurfaceView = findViewById<View>(R.id.continuousCapture_surfaceView) as SurfaceView
        val viewWidth: Int = sv.getWidth()
        val viewHeight: Int = sv.getHeight()
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        mFullFrameBlit?.drawFrame(mTextureId, mTmpMatrix)
        drawExtra(mFrameNum, viewWidth, viewHeight)
        mDisplaySurface?.swapBuffers()




        // Send it to the video encoder.
        if (!mFileSaveInProgress) {
            mEncoderSurface?.makeCurrent()
            GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)
            mFullFrameBlit?.drawFrame(mTextureId, mTmpMatrix)
            drawExtra(mFrameNum, VIDEO_WIDTH, VIDEO_HEIGHT)
            mCircEncoder!!.frameAvailableSoon()
            mEncoderSurface?.setPresentationTime(mCameraTexture!!.timestamp)
            mEncoderSurface?.swapBuffers()
        }
        mFrameNum++
    }

    companion object {
        private val TAG = ContinuousCaptureActivity::class.java.simpleName
        private const val VIDEO_WIDTH = 1280 // dimensions for 720p video
        private const val VIDEO_HEIGHT = 720
        private const val DESIRED_PREVIEW_FPS = 15

        /**
         * Adds a bit of extra stuff to the display just to give it flavor.
         */
        private fun drawExtra(frameNum: Int, width: Int, height: Int) {
            // We "draw" with the scissor rect and clear calls.  Note this uses window coordinates.
            val `val` = frameNum % 3
            when (`val`) {
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
    }
}