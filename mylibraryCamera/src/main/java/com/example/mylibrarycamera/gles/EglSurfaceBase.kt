/*
 * Copyright 2013 Google Inc. All rights reserved.
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
package com.example.mylibrarycamera.gles

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.GLES20
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Common base class for EGL surfaces.
 *
 *
 * There can be multiple surfaces associated with a single context.
 */
open class EglSurfaceBase protected constructor( // EglCore object we're associated with.  It may be associated with multiple surfaces.
    protected var mEglCore: EglCore
) {
    private var mEGLSurface = EGL14.EGL_NO_SURFACE
    private var mWidth = -1
    private var mHeight = -1

    /**
     * Creates a window surface.
     *
     *
     * @param surface May be a Surface or SurfaceTexture.
     */
    fun createWindowSurface(surface: Any) {
        check(mEGLSurface === EGL14.EGL_NO_SURFACE) { "surface already created" }
        mEGLSurface = mEglCore.createWindowSurface(surface)

        // Don't cache width/height here, because the size of the underlying surface can change
        // out from under us (see e.g. HardwareScalerActivity).
        //mWidth = mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH);
        //mHeight = mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT);
    }

    /**
     * Creates an off-screen surface.
     */
    fun createOffscreenSurface(width: Int, height: Int) {
        check(mEGLSurface === EGL14.EGL_NO_SURFACE) { "surface already created" }
        mEGLSurface = mEglCore.createOffscreenSurface(width, height)
        mWidth = width
        mHeight = height
    }

    val width: Int
        /**
         * Returns the surface's width, in pixels.
         *
         *
         * If this is called on a window surface, and the underlying surface is in the process
         * of changing size, we may not see the new size right away (e.g. in the "surfaceChanged"
         * callback).  The size should match after the next buffer swap.
         */
        get() = if (mWidth < 0) {
            mEglCore.querySurface(mEGLSurface, EGL14.EGL_WIDTH)
        } else {
            mWidth
        }
    val height: Int
        /**
         * Returns the surface's height, in pixels.
         */
        get() = if (mHeight < 0) {
            mEglCore.querySurface(mEGLSurface, EGL14.EGL_HEIGHT)
        } else {
            mHeight
        }

    /**
     * Release the EGL surface.
     */
    fun releaseEglSurface() {
        mEglCore.releaseSurface(mEGLSurface)
        mEGLSurface = EGL14.EGL_NO_SURFACE
        mHeight = -1
        mWidth = mHeight
    }

    /**
     * Makes our EGL context and surface current.
     */
    fun makeCurrent() {
        mEglCore.makeCurrent(mEGLSurface)
    }

    /**
     * Makes our EGL context and surface current for drawing, using the supplied surface
     * for reading.
     */
    fun makeCurrentReadFrom(readSurface: EglSurfaceBase) {
        mEglCore.makeCurrent(mEGLSurface, readSurface.mEGLSurface)
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     */
    fun swapBuffers(): Boolean {
        val result = mEglCore.swapBuffers(mEGLSurface)
        if (!result) {
            Log.d(TAG, "WARNING: swapBuffers() failed")
        }
        return result
    }

    /**
     * Sends the presentation time stamp to EGL.
     *
     * @param nsecs Timestamp, in nanoseconds.
     */
    fun setPresentationTime(nsecs: Long) {
        mEglCore.setPresentationTime(mEGLSurface, nsecs)
    }

    /**
     * Saves the EGL surface to a file.
     *
     *
     * Expects that this object's EGL surface is current.
     */
    @Throws(IOException::class)
    fun saveFrame(file: File) {
        if (!mEglCore.isCurrent(mEGLSurface)) {
            throw RuntimeException("Expected EGL context/surface is not current")
        }

        // glReadPixels fills in a "direct" ByteBuffer with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  While the Bitmap
        // constructor that takes an int[] wants little-endian ARGB (blue/red swapped), the
        // Bitmap "copy pixels" method wants the same format GL provides.
        //
        // Ideally we'd have some way to re-use the ByteBuffer, especially if we're calling
        // here often.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside down relative to what appears on screen if the
        // typical GL conventions are used.
        val filename = file.toString()
        val width = width
        val height = height
        val buf = ByteBuffer.allocateDirect(width * height * 4)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        GLES20.glReadPixels(
            0, 0, width, height,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GlUtil.checkGlError("glReadPixels")
        buf.rewind()
        var bos: BufferedOutputStream? = null
        try {
            bos = BufferedOutputStream(FileOutputStream(filename))
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
            bmp.recycle()
        } finally {
            bos?.close()
        }
        Log.d(TAG, "Saved " + width + "x" + height + " frame as '" + filename + "'")
    }

    companion object {
        protected val TAG: String? = GlUtil.TAG
    }
}