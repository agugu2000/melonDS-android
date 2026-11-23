package me.magnum.melonds.ui.emulator.render

import android.opengl.GLES30
import me.magnum.melonds.common.opengl.Shader
import me.magnum.melonds.common.opengl.ShaderFactory
import me.magnum.melonds.common.opengl.VideoFilterShaderProvider
import me.magnum.melonds.domain.model.SCREEN_HEIGHT
import me.magnum.melonds.domain.model.SCREEN_WIDTH
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.consoleAspectRatio
import me.magnum.melonds.domain.model.render.PresentFrameWrapper
import me.magnum.melonds.ui.RdsRotation
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ExternalScreenRender(
    private var rotateLeft: Boolean,
    private var keepAspectRatio: Boolean,
) : EmulatorRenderer {

    private var shader: Shader? = null
    private var screensVbo = 0
    private var screensVao = 0

    private var videoFiltering: VideoFiltering = VideoFiltering.NONE

    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var areVerticesDirty = false
    private var areRenderSettingsDirty = false
    private var viewportLock = Any()

    // Last geometry pushed to the vertex buffer, kept so the LCD grid shader can be
    // told the exact on-screen footprint of this screen's pixels every frame.
    private var screenCoords = FloatArray(12)
    private var screenUvs = FloatArray(12)
    // Snapshot of the rotation that is baked into screenCoords, so the fragment
    // geometry never disagrees with the vertices for a frame.
    private var screenRotated = false

    override fun updateRendererConfiguration(newRendererConfiguration: RuntimeRendererConfiguration?) {
        synchronized(viewportLock) {
            videoFiltering = newRendererConfiguration?.videoFiltering ?: VideoFiltering.NONE
            areRenderSettingsDirty = true
        }
    }

    override fun setLeftRotationEnabled(enabled: Boolean) {
        synchronized(viewportLock) {
            rotateLeft = enabled
            areVerticesDirty = true
        }
    }

    override fun onSurfaceCreated() {
        shader = ShaderFactory.createShaderProgram(
            VideoFilterShaderProvider.getShaderSource(videoFiltering)
        )

        val buffers = IntArray(2)
        GLES30.glGenBuffers(1, buffers, 0)
        GLES30.glGenVertexArrays(1, buffers, 1)
        screensVbo = buffers[0]
        screensVao = buffers[1]
        areVerticesDirty = true
    }

    private fun updateScreenVertices() {
        val (actualWidth, actualHeight) = if (rotateLeft) {
            surfaceHeight to surfaceWidth
        } else {
            surfaceWidth to surfaceHeight
        }

        val coords = if (keepAspectRatio) {
            val surfaceAspectRatio = actualWidth.toFloat() / actualHeight
            if (surfaceAspectRatio > consoleAspectRatio) {
                val screenScale = actualHeight.toFloat() / SCREEN_HEIGHT
                val scaledWidth = SCREEN_WIDTH * screenScale
                val relativeWidth = scaledWidth * 2f / actualWidth
                val halfWidth = relativeWidth / 2f
                floatArrayOf(
                    -halfWidth, -1f,
                    -halfWidth, 1f,
                    halfWidth, 1f,
                    -halfWidth, -1f,
                    halfWidth, 1f,
                    halfWidth, -1f,
                )
            } else {
                val screenScale = actualWidth.toFloat() / SCREEN_WIDTH
                val scaledHeight = SCREEN_HEIGHT * screenScale
                val relativeHeight = scaledHeight * 2f / actualHeight
                val halfHeight = relativeHeight / 2f
                floatArrayOf(
                    -1f, -halfHeight,
                    -1f, halfHeight,
                    1f, halfHeight,
                    -1f, -halfHeight,
                    1f, halfHeight,
                    1f, -halfHeight,
                )
            }
        } else {
            floatArrayOf(
                -1f, -1f,
                -1f, 1f,
                1f, 1f,
                -1f, -1f,
                1f, 1f,
                1f, -1f
            )
        }

        if (rotateLeft) {
            RdsRotation.rotateLeft(coords)
        }

        val lineRelativeSize = 1f / (SCREEN_HEIGHT * 2 + 2).toFloat()
        val uvs = if (true /*screen == DsExternalScreen.TOP*/) {
            floatArrayOf(
                0f, 0.5f - lineRelativeSize,
                0f, 0f,
                1f, 0f,
                0f, 0.5f - lineRelativeSize,
                1f, 0f,
                1f, 0.5f - lineRelativeSize
            )
        } else {
            floatArrayOf(
                0f, 1f,
                0f, 0.5f + lineRelativeSize,
                1f, 0.5f + lineRelativeSize,
                0f, 1f,
                1f, 0.5f + lineRelativeSize,
                1f, 1f
            )
        }

        val vertexData = floatArrayOf(
            coords[0], coords[1],   uvs[0], uvs[1], 1f,
            coords[2], coords[3],   uvs[2], uvs[3], 1f,
            coords[4], coords[5],   uvs[4], uvs[5], 1f,
            coords[6], coords[7],   uvs[6], uvs[7], 1f,
            coords[8], coords[9],   uvs[8], uvs[9], 1f,
            coords[10], coords[11], uvs[10], uvs[11], 1f,
        )

        val vertexBufferSize = vertexData.size * Float.SIZE_BYTES
        val vertexBuffer = ByteBuffer.allocateDirect(vertexBufferSize)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertexData)
            .position(0)

        screenCoords = coords.copyOf()
        screenUvs = uvs.copyOf()
        screenRotated = rotateLeft

        GLES30.glBindVertexArray(screensVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screensVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertexBufferSize, vertexBuffer, GLES30.GL_STATIC_DRAW)
    }

    private fun updateShader() {
        // Delete previous shader
        shader?.delete()

        val shaderSource = VideoFilterShaderProvider.getShaderSource(videoFiltering)
        shader = ShaderFactory.createShaderProgram(shaderSource)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        areVerticesDirty = true
    }

    override fun drawFrame(presentFrameWrapper: PresentFrameWrapper) {
        synchronized(viewportLock) {
            if (areVerticesDirty) {
                updateScreenVertices()
                areVerticesDirty = false
            }

            if (areRenderSettingsDirty) {
                updateShader()
                areRenderSettingsDirty = false
            }
        }

        if (!presentFrameWrapper.isValidFrame) {
            return
        }

        val textureId = presentFrameWrapper.textureId

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        shader?.let {
            it.use()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glBindVertexArray(screensVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, screensVbo)
            GLES30.glVertexAttribPointer(it.attribPos, 2, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, 0)
            GLES30.glVertexAttribPointer(it.attribUv, 2, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, 2 * Float.SIZE_BYTES)
            GLES30.glVertexAttribPointer(it.attribAlpha, 1, GLES30.GL_FLOAT, false, 5 * Float.SIZE_BYTES, (2 + 2) * Float.SIZE_BYTES)
            GLES30.glUniform1i(it.uniformTex, 0)
            applyScreenGeometry(it)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        }
    }

    /**
     * Hands the exact texture bounds and on-screen footprint of this screen to the
     * LCD grid shader, so the grid is generated from this panel's own first texel
     * and stays aligned no matter how the surface is scaled or rotated.
     */
    private fun applyScreenGeometry(shader: Shader) {
        var minU = Float.MAX_VALUE
        var maxU = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE

        for (i in 0 until 6) {
            val u = screenUvs[i * 2]
            val v = screenUvs[i * 2 + 1]
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v

            val x = screenCoords[i * 2]
            val y = screenCoords[i * 2 + 1]
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        // A zero sized quad covers no pixels, but clamping keeps the uniforms sane
        val spanX = maxOf((maxX - minX) * 0.5f * surfaceWidth, 1f)
        val spanY = maxOf((maxY - minY) * 0.5f * surfaceHeight, 1f)

        val fragScaleX: Float
        val fragScaleY: Float
        if (screenRotated) {
            fragScaleX = SCREEN_WIDTH.toFloat() / spanY
            fragScaleY = SCREEN_HEIGHT.toFloat() / spanX
        } else {
            fragScaleX = SCREEN_WIDTH.toFloat() / spanX
            fragScaleY = SCREEN_HEIGHT.toFloat() / spanY
        }

        shader.setScreenGeometry(minU, minV, maxU - minU, maxV - minV, fragScaleX, fragScaleY)
    }

    fun setKeepAspectRatio(keep: Boolean) {
        synchronized(viewportLock) {
            keepAspectRatio = keep
            areVerticesDirty = true
        }
    }
}