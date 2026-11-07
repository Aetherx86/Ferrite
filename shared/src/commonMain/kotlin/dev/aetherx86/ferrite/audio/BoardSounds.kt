package dev.aetherx86.ferrite.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Moments a platform makes a sound. */
enum class BoardSound { APPROACH, ROLLING_IN, ARRIVAL, DOORS_CLOSING, DEPARTURE }

/**
 * Every sound in the app, synthesized from nothing, so there are no recordings to license and the Android and
 * Apple builds sound the same.
 */
object SoundSynth {
    const val SAMPLE_RATE = 22_050

    fun render(sound: BoardSound): ShortArray = when (sound) {
        BoardSound.APPROACH -> approachChime()
        BoardSound.ROLLING_IN -> rollingIn()
        BoardSound.ARRIVAL -> arrival()
        BoardSound.DOORS_CLOSING -> doorsClosing()
        BoardSound.DEPARTURE -> departure()
    }

    /** A small, deterministic noise source, so a sound is identical on every run. */
    private class Noise(seed: Int) {
        private var state = seed
        fun next(): Float {
            state = state * 1664525 + 1013904223
            return (state shr 8 and 0xFFFF) / 32768f - 1f
        }
    }

    private fun bell(out: FloatArray, startSeconds: Float, frequency: Float, gain: Float, decay: Float = 2.6f) {
        val partials = floatArrayOf(1f, 2.0f, 2.76f, 4.07f)
        val weights = floatArrayOf(1f, 0.42f, 0.16f, 0.07f)
        val start = (startSeconds * SAMPLE_RATE).toInt()
        val length = min(out.size - start, (SAMPLE_RATE * 2.4f).toInt())
        for (n in 0 until length) {
            val t = n / SAMPLE_RATE.toFloat()
            var v = 0f
            for (p in partials.indices) {
                val f = frequency * partials[p]
                if (f > SAMPLE_RATE / 2) continue
                v += weights[p] * sin(2f * PI.toFloat() * f * t) * exp(-t * decay * (1f + p * 0.75f))
            }
            val attack = min(1f, t * 400f)
            out[start + n] += v * gain * attack
        }
    }

    private fun approachChime(): ShortArray {
        val out = FloatArray((SAMPLE_RATE * 2.6f).toInt())
        bell(out, 0f, 659.25f, 0.34f)
        bell(out, 0.26f, 830.61f, 0.30f)
        bell(out, 0.52f, 987.77f, 0.30f)
        bell(out, 0.86f, 1318.5f, 0.16f, 3.4f)
        return finish(out)
    }

    /** Wheels on rail joints, getting louder and slower as the train brakes. */
    private fun rollingIn(): ShortArray {
        val seconds = 7.5f
        val out = FloatArray((SAMPLE_RATE * seconds).toInt())
        val noise = Noise(11)
        var low = 0f
        var mid = 0f
        var nextClack = 0f
        for (n in out.indices) {
            val t = n / SAMPLE_RATE.toFloat()
            val progress = t / seconds
            val swell = progress.pow(1.6f)
            val white = noise.next()
            low += (white - low) * 0.012f
            mid += (white - mid) * 0.08f
            out[n] += (low * 2.4f + (mid - low) * 0.4f) * (0.08f + 0.62f * swell)
            // Traction motor pitch falls as the train slows down.
            val pitch = 340f - 210f * progress
            out[n] += 0.05f * swell * sin(2f * PI.toFloat() * pitch * t) * (1f - progress * 0.4f)
            if (t >= nextClack) {
                val gap = 0.16f + 0.26f * progress
                nextClack = t + gap
                addClack(out, n, 0.16f + 0.42f * swell, noise)
            }
            if (progress > 0.72f) {
                val squeal = ((progress - 0.72f) / 0.28f).pow(2f)
                out[n] += 0.035f * squeal * sin(2f * PI.toFloat() * (1680f + 90f * sin(t * 9f)) * t)
            }
        }
        fade(out, inSeconds = 0.6f, outSeconds = 0.5f)
        return finish(out)
    }

    private fun addClack(out: FloatArray, at: Int, gain: Float, noise: Noise) {
        val length = (SAMPLE_RATE * 0.05f).toInt()
        for (i in 0 until min(length, out.size - at)) {
            val t = i / SAMPLE_RATE.toFloat()
            val tone = sin(2f * PI.toFloat() * 118f * t) + 0.5f * noise.next()
            out[at + i] += gain * tone * exp(-t * 62f)
        }
    }

    /** A bright two-note call, then the doors letting out their air. */
    private fun arrival(): ShortArray {
        val out = FloatArray((SAMPLE_RATE * 4f).toInt())
        bell(out, 0f, 1046.5f, 0.32f, 2.2f)
        bell(out, 0.36f, 783.99f, 0.36f, 2.0f)
        airRelease(out, 1.35f, 0.9f, 0.13f)
        return finish(out)
    }

    private fun airRelease(out: FloatArray, startSeconds: Float, lengthSeconds: Float, gain: Float) {
        val noise = Noise(5)
        var high = 0f
        val start = (startSeconds * SAMPLE_RATE).toInt()
        val length = min(out.size - start, (lengthSeconds * SAMPLE_RATE).toInt())
        for (i in 0 until length) {
            val white = noise.next()
            high += (white - high) * 0.45f
            val progress = i / length.toFloat()
            val envelope = min(1f, progress * 18f) * (1f - progress).pow(1.4f)
            out[start + i] += (white - high) * gain * envelope
        }
    }

    /** The warning tones of closing doors, then the thud of them meeting. */
    private fun doorsClosing(): ShortArray {
        val out = FloatArray((SAMPLE_RATE * 3.4f).toInt())
        for (beep in 0 until 4) {
            val start = (beep * 0.44f * SAMPLE_RATE).toInt()
            val length = (0.2f * SAMPLE_RATE).toInt()
            for (i in 0 until length) {
                val t = i / SAMPLE_RATE.toFloat()
                val envelope = min(1f, t * 200f) * min(1f, (0.2f - t) * 200f)
                val tone = 0.75f * sin(2f * PI.toFloat() * 932.33f * t) + 0.25f * sin(2f * PI.toFloat() * 1864.66f * t)
                out[start + i] += tone * 0.27f * envelope
            }
        }
        airRelease(out, 1.75f, 0.7f, 0.1f)
        val thud = (2.4f * SAMPLE_RATE).toInt()
        for (i in 0 until (0.14f * SAMPLE_RATE).toInt()) {
            val t = i / SAMPLE_RATE.toFloat()
            out[thud + i] += 0.5f * sin(2f * PI.toFloat() * 86f * t) * exp(-t * 26f)
        }
        return finish(out)
    }

    /** Motors winding up in steps, then rumble fading into the tunnel. */
    private fun departure(): ShortArray {
        val seconds = 7f
        val out = FloatArray((SAMPLE_RATE * seconds).toInt())
        val noise = Noise(23)
        var low = 0f
        var phase = 0f
        var nextClack = 0.8f
        for (n in out.indices) {
            val t = n / SAMPLE_RATE.toFloat()
            val progress = t / seconds
            val level = min(1f, t * 1.6f) * (1f - progress).pow(1.3f)
            val white = noise.next()
            low += (white - low) * 0.014f
            out[n] += low * 2.3f * level * 0.7f
            val step = (t * 1.5f).toInt()
            val pitch = 150f + step * 92f + 60f * (t * 1.5f - step)
            phase += 2f * PI.toFloat() * pitch / SAMPLE_RATE
            out[n] += 0.07f * level * (sin(phase) + 0.4f * sin(phase * 2.01f))
            if (t >= nextClack) {
                nextClack = t + max(0.11f, 0.34f - progress * 0.3f)
                addClack(out, n, 0.3f * level, noise)
            }
        }
        fade(out, inSeconds = 0.25f, outSeconds = 0.8f)
        return finish(out)
    }

    private fun fade(out: FloatArray, inSeconds: Float, outSeconds: Float) {
        val fadeIn = (inSeconds * SAMPLE_RATE).toInt()
        val fadeOut = (outSeconds * SAMPLE_RATE).toInt()
        for (i in 0 until min(fadeIn, out.size)) out[i] *= i / fadeIn.toFloat()
        for (i in 0 until min(fadeOut, out.size)) out[out.size - 1 - i] *= i / fadeOut.toFloat()
    }

    private fun finish(out: FloatArray): ShortArray {
        var peak = 0.001f
        for (v in out) peak = max(peak, kotlin.math.abs(v))
        val gain = if (peak > 0.92f) 0.92f / peak else 1f
        return ShortArray(out.size) { (out[it] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** The sound as a WAV file, for a player that wants one. */
    fun toWav(samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        fun text(at: Int, value: String) = value.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun int(at: Int, value: Int) {
            for (i in 0 until 4) out[at + i] = (value shr (8 * i)).toByte()
        }
        fun short(at: Int, value: Int) {
            for (i in 0 until 2) out[at + i] = (value shr (8 * i)).toByte()
        }
        text(0, "RIFF")
        int(4, 36 + dataBytes)
        text(8, "WAVE")
        text(12, "fmt ")
        int(16, 16)
        short(20, 1)
        short(22, 1)
        int(24, SAMPLE_RATE)
        int(28, SAMPLE_RATE * 2)
        short(32, 2)
        short(34, 16)
        text(36, "data")
        int(40, dataBytes)
        for (i in samples.indices) short(44 + i * 2, samples[i].toInt())
        return out
    }
}
