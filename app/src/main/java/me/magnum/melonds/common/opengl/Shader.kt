package me.magnum.melonds.common.opengl

import android.opengl.GLES30
import me.magnum.melonds.domain.model.SCREEN_HEIGHT
import me.magnum.melonds.domain.model.SCREEN_WIDTH

private const val INVALID_ATTRIBUTE = -1

class Shader(
    private val vertexShaderId: Int,
    private val fragmentShaderId: Int,
    private val programId: Int,
    val textureFiltering: Int,
) {
    val attribUv: Int
    val attribPos: Int
    val attribAlpha: Int
    val uniformTex: Int

    // Locations of the optional per-quad uniforms used by the LCD grid shader.
    // -1 when the shader in use does not declare them, which makes the matching
    // glUniform* calls a silent no-op, so every other filter is unaffected.
    val uniformSrcSize: Int
    val uniformUvMin: Int
    val uniformUvSpan: Int
    val uniformFragScale: Int

    init {
        GLES30.glUseProgram(programId)
        attribUv = GLES30.glGetAttribLocation(programId, "vUV")
        attribPos = GLES30.glGetAttribLocation(programId, "vPos")
        attribAlpha = GLES30.glGetAttribLocation(programId, "vAlpha")
        uniformTex = GLES30.glGetUniformLocation(programId, "tex")
        uniformSrcSize = GLES30.glGetUniformLocation(programId, "uSrcSize")
        uniformUvMin = GLES30.glGetUniformLocation(programId, "uUVMin")
        uniformUvSpan = GLES30.glGetUniformLocation(programId, "uUVSpan")
        uniformFragScale = GLES30.glGetUniformLocation(programId, "uFragScale")
        GLES30.glUseProgram(0)
    }

    /**
     * Feeds the exact on-screen geometry of the quad that is about to be drawn to
     * the LCD grid shader.
     *
     * [uvMinX] / [uvMinY] and [uvSpanX] / [uvSpanY] describe where the screen lives
     * inside the frame texture; [fragScaleX] / [fragScaleY] are how many source
     * pixels one framebuffer pixel covers. Passing those values instead of deriving
     * them inside the shader is what keeps the grid phase exact for every screen, so
     * the two DS panels can never beat against each other.
     *
     * Takes plain floats rather than arrays because this runs once per screen per
     * frame and should not allocate.
     *
     * The source size is fixed at one DS panel (256x192); every caller draws a
     * single panel quad, so there is nothing to pass in for it.
     */
    fun setScreenGeometry(
        uvMinX: Float,
        uvMinY: Float,
        uvSpanX: Float,
        uvSpanY: Float,
        fragScaleX: Float,
        fragScaleY: Float,
    ) {
        GLES30.glUniform2f(uniformSrcSize, SCREEN_WIDTH.toFloat(), SCREEN_HEIGHT.toFloat())
        GLES30.glUniform2f(uniformUvMin, uvMinX, uvMinY)
        GLES30.glUniform2f(uniformUvSpan, uvSpanX, uvSpanY)
        GLES30.glUniform2f(uniformFragScale, fragScaleX, fragScaleY)
    }

    fun use() {
        GLES30.glUseProgram(programId)
        GLES30.glEnableVertexAttribArray(attribUv)
        GLES30.glEnableVertexAttribArray(attribPos)
        if (attribAlpha != INVALID_ATTRIBUTE) {
            GLES30.glEnableVertexAttribArray(attribAlpha)
        }
    }

    fun delete() {
        GLES30.glDeleteShader(vertexShaderId)
        GLES30.glDeleteShader(fragmentShaderId)
        GLES30.glDeleteProgram(programId)
    }
}
