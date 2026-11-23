package io.github.strawwaffle.glorp.site

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rules from the site's emotes.css that Glorp once read wrongly. */
class CssParityTest {
    private val css = """
        .emote.buddy-nutcheck { background-image: url("n.png"); width: 48px; height: 32px; background-size: 480px 32px;
          animation: nut 1s steps(10) 1; }
        @keyframes nut { from { background-position: 0 0; } to { background-position: -480px 0; } }
        .emote.BLADE { background-image: url("b.png"); width: 72px; height: 32px; transform-origin: -36px 32px; }
        .emote.WIDE { background-image: url("w.png"); width: 64px; height: 32px; background-size: 64px; }
        .emote.GIGACHAD { background-image: url("g.png"); width: 29px; height: 32px; }
        @keyframes GIGACHAD-anim { 0% { background-position: -28px 10px; } 100% { background-position: 0px; } }
        .emote.GIGACHAD { background-repeat: no-repeat; animation: GIGACHAD-anim 1.5s ease-out 1; }
    """.trimIndent()

    private val json = """[{"prefix":"buddy-nutcheck","image":[{"url":"https://cdn.example/n.png","width":528,"height":32}]},""" +
        """{"prefix":"BLADE","image":[{"url":"https://cdn.example/b.png","width":72,"height":32}]},""" +
        """{"prefix":"WIDE","image":[{"url":"https://cdn.example/w.png","width":128,"height":32}]},""" +
        """{"prefix":"GIGACHAD","image":[{"url":"https://cdn.example/g.png","width":29,"height":32}]}]"""

    private val emotes = Emotes.parse(json, css)

    private fun near(actual: Float, expected: Float) = assertTrue(abs(actual - expected) < 0.01f, "$actual, expected $expected")

    @Test
    fun readsRulesForNamesWithAHyphen() {
        val nut = assertNotNull(emotes["buddy-nutcheck"])
        assertEquals(48, nut.width)
        assertEquals(10, assertNotNull(nut.sprite).frames)
    }

    @Test
    fun readsAKeyframesPivotAndClip() {
        val frames = motionKeyframes("""
            @keyframes stab { 0%, 100% { transform: rotate(0deg); transform-origin: left bottom; } 10% { transform: rotate(45deg); } }
            @keyframes path { 0% { clip-path: polygon(-5% 95%, 20% 70%, 30% 80%, 5% 105%); } 50% { clip-path: polygon(-5% 95%, 95% -5%, 105% 5%, 5% 105%); } }
        """.trimIndent())
        val stab = motionsOf(listOf("stab 1s 1 linear"), frames).single().copy(restOrigin = 0.5f to 0.5f)
        // As an emote's motion, the written origin becomes fractions of its box.
        val placed = stab.copy(frames = stab.frames.map { f -> f.originText?.let { f.copy(origin = readOrigin(it, 30.0, 30.0)) } ?: f })
        val pose = placed.apply(100, Pose.Rest)
        near(pose.ox, 0f); near(pose.oy, 1f); near(pose.rotate, 45f)
        val path = motionsOf(listOf("path 1s 1 linear"), frames).single()
        val halfway = assertNotNull(path.apply(250, Pose.Rest).clip)
        near(halfway[2], 0.575f); near(halfway[3], 0.325f)
        // Past the last keyframe that sets it, toward none: the polygon holds until halfway, then goes.
        assertNotNull(path.apply(700, Pose.Rest).clip)
        assertEquals(null, path.apply(800, Pose.Rest).clip)
        assertEquals(true, Pose() == Pose.Rest)
    }

    @Test
    fun slidesAPictureInsideItsBox() {
        // GIGACHAD arrives from the left and below, and rests where its own rules put it.
        val chad = assertNotNull(emotes["GIGACHAD"])
        val motion = chad.motions.single()
        val start = motion.apply(0, chad.rest)
        near(start.bx, -28f); near(start.by, 10f)
        val end = motion.apply(1_500, chad.rest)
        near(end.bx, 0f); near(end.by, 0f)
        assertEquals(false, chad.isWholePicture)
    }

    @Test
    fun readsAPivotGivenInPixels() {
        val blade = assertNotNull(emotes["BLADE"])
        near(blade.originX, -0.5f)
        near(blade.originY, 1f)
        assertEquals(0f to 0.75f, readOrigin("0 75%"))
    }

    @Test
    fun oneBackgroundSizeKeepsThePicturesShape() {
        // `background-size: 64px` is 64 wide and `auto` high: half of a 128×32 picture.
        assertEquals(16, assertNotNull(emotes["WIDE"]).sheetHeight)
    }

    @Test
    fun movesInTheDirectionsAnEarlierScaleOrTurnLeaves() {
        // Mirrored, a step left is a step right on screen.
        val mirrored = assertNotNull(readTransform("scale(-1, 1) translate(-2px, 3px)"))
        near(mirrored.tx, 2f); near(mirrored.ty, 3f)
        // Written translate-first, it moves as written.
        val plain = assertNotNull(readTransform("translate(102px) scale(-1, 1)"))
        near(plain.tx, 102f)
    }

    @Test
    fun worksOutASimpleCalc() {
        near(assertNotNull(readTransform("scale(calc(28/36))")).sx, 28f / 36f)
    }
}
