package uk.co.mrsheep.halive.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animation state enum for the visualizer.
 */
enum class VisualizerState {
    /** Slow pulse (3s), minimal movement, desaturated colors, 50% opacity */
    DORMANT,

    /** Medium pulse (2s), steady particle orbit, full color, 100% opacity */
    ACTIVE,

    /** Fast pulse (1.2s), faster particles, color shift toward accent, 100% opacity */
    EXECUTING
}

/**
 * AudioVisualizerView is a custom View that displays an animated audio visualization.
 *
 * The visualizer features:
 * - A rotating radial gradient background
 * - 2-3 concentric pulsing glow halos
 * - 24 particles orbiting in a ring with varying speeds and sizes
 * - A subtle central orb that provides a gentle anchor point
 *
 * Supports three animation states (DORMANT, ACTIVE, EXECUTING) that control:
 * - Pulse speed (3s → 2s → 1.2s cycle)
 * - Particle movement speed
 * - Color saturation and opacity
 * - Overall visual intensity
 *
 * Optimized for 60fps with pre-allocated Paint objects and efficient animation
 * using postInvalidateOnAnimation() and SystemClock.elapsedRealtime().
 */
class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ===================== State =====================

    private var currentState = VisualizerState.DORMANT

    /**
     * Sets the animation state of the visualizer.
     *
     * @param state The new [VisualizerState] to transition to.
     */
    fun setState(state: VisualizerState) {
        currentState = state
        invalidate()
    }

    // ===================== Particle Data Model =====================

    /**
     * Data class representing a particle in the orbital ring.
     *
     * @property angle Current rotation angle in radians
     * @property distance Normalized distance from center (0.0 - 1.0)
     * @property speed Angular velocity multiplier for this particle
     * @property size Radius of the particle in pixels
     * @property alpha Opacity of the particle (0.0 - 1.0)
     */
    data class Particle(
        var angle: Float = 0f,
        var distance: Float = 0f,
        var speed: Float = 0f,
        var size: Float = 0f,
        var alpha: Float = 0.5f
    )

    // ===================== Pre-allocated Paint Objects =====================

    private lateinit var paintPrimary: Paint
    private lateinit var paintPrimaryLight: Paint
    private lateinit var paintPrimaryDark: Paint
    private lateinit var paintAccent: Paint
    private lateinit var paintParticle: Paint
    private lateinit var paintOrbBackground: Paint
    private lateinit var paintOrbForeground: Paint
    private lateinit var paintGradientBg: Paint
    private lateinit var paintHalo1: Paint
    private lateinit var paintHalo2: Paint
    private lateinit var paintHalo3: Paint
    private lateinit var paintOrbHighlight: Paint

    private var colorPrimary: Int = Color.BLUE
    private var colorPrimaryLight: Int = Color.CYAN
    private var colorPrimaryDark: Int = Color.DKGRAY
    private var colorAccent: Int = Color.YELLOW

    // ===================== Particles =====================

    private val particles = MutableList(24) { index ->
        Particle(
            angle = (index / 24f) * 2f * PI.toFloat(),
            distance = 0.4f,
            speed = 0.5f + (index % 3) * 0.15f,
            size = 4f + (index % 4) * 1.5f,
            alpha = 0.4f + (index % 4) * 0.075f
        )
    }

    // ===================== Animation Management =====================

    private var startTimeMs: Long = 0
    private var isAnimating = false

    private val baseSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        300f,
        resources.displayMetrics
    )

    // ===================== Initialization =====================

    init {
        initializePaints()
        resolveThemeColors()
    }

    /**
     * Initializes all Paint objects used in rendering.
     * This is done once in init to avoid per-frame allocations.
     */
    private fun initializePaints() {
        paintPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorPrimary
        }

        paintPrimaryLight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorPrimaryLight
        }

        paintPrimaryDark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorPrimaryDark
        }

        paintAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorAccent
        }

        paintParticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            alpha = 128
        }

        paintOrbBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorPrimaryLight
        }

        paintOrbForeground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = colorPrimary
        }

        paintGradientBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        paintHalo1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        paintHalo2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        paintHalo3 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        paintOrbHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
    }

    /**
     * Resolves colors from the theme and creates lighter/darker variants.
     * Falls back to default colors if theme colors are not available.
     */
    private fun resolveThemeColors() {
        val ta = context.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.colorPrimary,
                android.R.attr.colorAccent
            )
        )

        colorPrimary = ta.getColor(0, Color.BLUE)
        colorAccent = ta.getColor(1, Color.YELLOW)
        ta.recycle()

        // Create lighter and darker variants using ColorUtils
        colorPrimaryLight = ColorUtils.blendARGB(colorPrimary, Color.WHITE, 0.4f)
        colorPrimaryDark = ColorUtils.blendARGB(colorPrimary, Color.BLACK, 0.3f)

        // Update paints with resolved colors
        paintPrimary.color = colorPrimary
        paintPrimaryLight.color = colorPrimaryLight
        paintPrimaryDark.color = colorPrimaryDark
        paintAccent.color = colorAccent
        paintOrbBackground.color = colorPrimaryLight
        paintOrbForeground.color = colorPrimary
    }

    // ===================== Lifecycle Management =====================

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTimeMs = SystemClock.elapsedRealtime()
        isAnimating = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAnimating = false
    }

    // ===================== Measurement =====================

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredSize = baseSize.toInt() + paddingLeft + paddingRight
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(desiredSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    // ===================== Drawing =====================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!isAnimating) return

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) - 20f

        val elapsedMs = SystemClock.elapsedRealtime() - startTimeMs
        val elapsedSeconds = elapsedMs / 1000f

        // Get state parameters based on current animation state
        val stateParams = getStateParameters(currentState)

        // Draw visual elements in order
        drawRotatingBackground(canvas, centerX, centerY, radius, elapsedSeconds, stateParams.opacity)
        drawGlowHalos(
            canvas, centerX, centerY, radius, elapsedSeconds,
            stateParams.pulseFrequency, stateParams.opacity
        )
        drawParticles(
            canvas, centerX, centerY, radius * 0.65f,
            elapsedSeconds, stateParams.particleSpeed, stateParams.opacity, stateParams.colorBlend
        )
        drawCentralOrb(
            canvas, centerX, centerY, elapsedSeconds,
            stateParams.pulseFrequency, stateParams.opacity, stateParams.colorBlend
        )

        if (isAnimating) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Gets animation parameters based on the current state.
     *
     * @param state The current [VisualizerState]
     * @return A [StateParameters] object containing pulse frequency, particle speed, opacity, and color blend
     */
    private fun getStateParameters(state: VisualizerState): StateParameters {
        return when (state) {
            VisualizerState.DORMANT -> StateParameters(
                pulseFrequency = 3f,
                particleSpeed = 0.1f,
                opacity = 0.5f,
                colorBlend = 0f
            )
            VisualizerState.ACTIVE -> StateParameters(
                pulseFrequency = 2f,
                particleSpeed = 1f,
                opacity = 1f,
                colorBlend = 0f
            )
            VisualizerState.EXECUTING -> StateParameters(
                pulseFrequency = 1.2f,
                particleSpeed = 1.5f,
                opacity = 1f,
                colorBlend = 0.3f
            )
        }
    }

    /**
     * Draws the rotating radial gradient background.
     */
    private fun drawRotatingBackground(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        radius: Float,
        elapsedSeconds: Float,
        opacity: Float
    ) {
        val rotation = (elapsedSeconds * 10f) % 360f

        val shader = RadialGradient(
            centerX, centerY, radius,
            intArrayOf(
                applyOpacity(colorPrimaryDark, opacity),
                applyOpacity(colorPrimary, opacity),
                applyOpacity(colorPrimaryLight, opacity)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        paintGradientBg.shader = shader
        canvas.drawCircle(centerX, centerY, radius, paintGradientBg)
        canvas.restore()
    }

    /**
     * Draws 2-3 concentric pulsing glow halos around the center.
     */
    private fun drawGlowHalos(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        maxRadius: Float,
        elapsedSeconds: Float,
        pulseFrequency: Float,
        opacity: Float
    ) {
        val pulseValue = ((sin(elapsedSeconds * 2f * PI.toFloat() * pulseFrequency) + 1f) / 2f).toFloat()
        val haloPaints = arrayOf(paintHalo1, paintHalo2, paintHalo3)

        for (i in haloPaints.indices) {
            val haloRadius = maxRadius * (0.4f + (i * 0.2f)) * (0.8f + pulseValue * 0.4f)
            val haloAlpha = (opacity * (0.3f - i * 0.08f)).coerceIn(0f, 1f)

            haloPaints[i].color = applyOpacity(colorPrimary, haloAlpha)
            canvas.drawCircle(centerX, centerY, haloRadius, haloPaints[i])
        }
    }

    /**
     * Draws 24 orbiting particles in a ring around the center.
     * Each particle has its own speed, size, and opacity variation.
     */
    private fun drawParticles(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        orbitRadius: Float,
        elapsedSeconds: Float,
        speedMultiplier: Float,
        opacity: Float,
        colorBlend: Float
    ) {
        particles.forEach { particle ->
            // Update particle angle based on speed
            particle.angle += particle.speed * speedMultiplier * 0.02f

            // Calculate particle position on orbit
            val x = centerX + cos(particle.angle.toDouble()).toFloat() * orbitRadius
            val y = centerY + sin(particle.angle.toDouble()).toFloat() * orbitRadius

            // Determine particle color based on blend (for EXECUTING state)
            val particleColor = if (colorBlend > 0f) {
                ColorUtils.blendARGB(
                    applyOpacity(Color.WHITE, particle.alpha),
                    applyOpacity(colorAccent, particle.alpha),
                    colorBlend
                )
            } else {
                applyOpacity(Color.WHITE, particle.alpha)
            }

            paintParticle.color = particleColor
            canvas.drawCircle(x, y, particle.size * opacity, paintParticle)
        }
    }

    /**
     * Draws the subtle central orb with gentle pulse and soft background halo.
     * Designed to be a non-distracting anchor point while particles and halos provide visual interest.
     */
    private fun drawCentralOrb(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        elapsedSeconds: Float,
        pulseFrequency: Float,
        opacity: Float,
        colorBlend: Float
    ) {
        val pulseValue = ((sin(elapsedSeconds * 2f * PI.toFloat() * pulseFrequency) + 1f) / 2f).toFloat()
        // Subtle pulse: 12-15px range (3px swing, gentle breathing)
        val orbRadius = 12f + (pulseValue * 3f)

        // Draw background halo with reduced opacity for subtlety
        paintOrbBackground.alpha = (opacity * 80).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, orbRadius * 1.3f, paintOrbBackground)

        // Draw foreground orb with optional color blend
        val orbColor = if (colorBlend > 0f) {
            ColorUtils.blendARGB(colorPrimary, colorAccent, colorBlend)
        } else {
            colorPrimary
        }
        paintOrbForeground.color = applyOpacity(orbColor, opacity * 0.7f)
        canvas.drawCircle(centerX, centerY, orbRadius, paintOrbForeground)

        // Highlight removed for subtlety - particles and halos are the stars now
    }

    // ===================== Utility Functions =====================

    /**
     * Applies opacity to a color while preserving RGB values.
     *
     * @param color The color to modify
     * @param opacity The opacity multiplier (0.0 - 1.0)
     * @return A new color with adjusted alpha channel
     */
    private fun applyOpacity(color: Int, opacity: Float): Int {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        val alpha = (Color.alpha(color) * clampedOpacity).toInt()
        return Color.argb(
            alpha,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    // ===================== Data Classes for State Management =====================

    /**
     * Parameters that define the animation behavior for a given state.
     *
     * @property pulseFrequency How many pulses per second (Hz)
     * @property particleSpeed Multiplier for particle orbital velocity
     * @property opacity Overall opacity of all visual elements (0.0 - 1.0)
     * @property colorBlend Amount to blend toward accent color (0.0 - 1.0), used in EXECUTING state
     */
    private data class StateParameters(
        val pulseFrequency: Float,
        val particleSpeed: Float,
        val opacity: Float,
        val colorBlend: Float
    )
}
