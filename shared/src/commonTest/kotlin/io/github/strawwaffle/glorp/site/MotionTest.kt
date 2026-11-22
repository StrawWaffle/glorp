package io.github.strawwaffle.glorp.site

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {
    // As the site writes them: a sprite run and a wobble on one emote.
    private val css = """
        .emote.BLICKY { background-image: url("b.png"); height: 32px; width: 1632px; }
        .emote.BLICKY { width: 32px; height: 32px; background-size: 1632px 32px; transform-origin: 0% 100%;
          animation: BLICKY-anim steps(51) 0.255s 4 0.15s, BLICKY-rotate ease-out 1.5s 1 0.15s; }
        .emote.BLICKY:hover { animation: BLICKY-hover-rotate ease-out 1.25s infinite 0.15s; }
        @keyframes BLICKY-anim { from { background-position: 0 0; } to { background-position: -1632px 0; } }
        @keyframes BLICKY-rotate { 0% { transform: rotate(0deg); } 30% { transform: rotate(-6deg); } 55% { transform: rotate(0deg); } }
        .emote.BERN { background-image: url("c.png"); height: 30px; width: 60px; animation: BERN-shake 1 1.55s; }
        @keyframes BERN-shake { 15%, 25% { transform: translate(-0.5px, .25px); } 50% { opacity: 0.5; filter: brightness(1.5); } }
    """.trimIndent()

    private val json = """[{"prefix":"BLICKY","image":[{"url":"b.png","width":1632,"height":32}]},
        {"prefix":"BERN","image":[{"url":"c.png","width":60,"height":30}]}]"""

    private fun near(a: Float, b: Float) = assertTrue(abs(a - b) < 0.05f, "$a != $b")

    @Test
    fun readsMovementsBesideTheSprite() {
        val blicky = Emotes.parse(json, css)["BLICKY"]!!
        assertEquals(51, blicky.sprite?.frames)
        val turn = blicky.motions.single()
        assertEquals(1500L, turn.durationMs)
        assertEquals(150L, turn.delayMs)
        assertEquals(0f, blicky.originX)
        assertEquals(1f, blicky.originY)
        // Before its delay it rests, at its 30% keyframe it's turned, after its end it rests.
        assertEquals(0f, turn.apply(100, Pose.Rest).rotate)
        near(turn.apply(150 + 450, Pose.Rest).rotate, -6f)
        assertEquals(0f, turn.apply(5_000, Pose.Rest).rotate)
        assertEquals(1650L, blicky.settlesAfterMs.coerceAtMost(1650L))
    }

    @Test
    fun fillsMissingEndsWithTheRestingPose() {
        val bern = Emotes.parse(json, css)["BERN"]!!
        val shake = bern.motions.single()
        assertEquals(1550L, shake.durationMs)
        // Between 0% (resting, implied) and 15%.
        val early = shake.apply((1550 * 0.075).toLong(), Pose.Rest)
        assertTrue(early.tx < 0f && early.tx > -0.5f)
        near(shake.apply(775, Pose.Rest).opacity, 0.5f)
        near(shake.apply(775, Pose.Rest).brightness, 1.5f)
        assertEquals(Pose.Rest, shake.apply(2_000, Pose.Rest))
    }

    @Test
    fun readsTransformsAndTimingWords() {
        val pose = readTransform("translate(2px, -1px) rotate(0.5turn) scale(1.5)")!!
        assertEquals(2f, pose.tx); assertEquals(-1f, pose.ty); assertEquals(180f, pose.rotate); assertEquals(1.5f, pose.sx); assertEquals(1.5f, pose.sy)
        val frames = mapOf("spin" to listOf(KeyFrame(1f, Pose(rotate = 360f), null, null)))
        val spin = motionsOf(listOf("spin 2s linear infinite alternate"), frames).single()
        assertEquals(Int.MAX_VALUE, spin.iterations)
        near(spin.apply(1_000, Pose.Rest).rotate, 180f)
        // The second run goes back.
        near(spin.apply(2_500, Pose.Rest).rotate, 270f)
        assertEquals(Easing.Linear, spin.easing)
    }

    @Test
    fun leavesMixedColourFiltersAlone() {
        val frames = motionKeyframes("@keyframes a { 50% { filter: grayscale(1) brightness(1.8); } } @keyframes b { 50% { filter: brightness(120%) drop-shadow(0 0 2px rgba(0,0,0,.5)); } }")
        assertTrue("a" !in frames)
        near(frames.getValue("b").single().brightness!!, 1.2f)
    }

    @Test
    fun stepsAndCurves() {
        assertEquals(0.5f, Easing.Steps(2, false).of(0.6f))
        assertEquals(1f, Easing.Steps(2, true).of(0.6f))
        near(Easing.EaseInOut.of(0.5f), 0.5f)
        assertTrue(Easing.EaseOut.of(0.25f) > 0.25f)
    }
}
