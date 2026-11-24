package io.github.strawwaffle.glorp.site

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ProfileTest {
    // The profile page's card, trimmed to its shape, with made-up values.
    private val page = """
        <main class="wrapper public-profile"> <div class="id-card id-card--private" data-profile-card-target="card"> <div class="id-card__inner">
        <div class="id-card__face id-card__face--front"> <div class="id-card__stripe"></div> <div class="id-card__header">
        <span class="badge badge--primary id-card__tier" > <span class="badge__text">Tier 2 Subscriber </span> </span> </div>
        <div class="id-card__body"> <div class="id-card__photo-column">
        <img class="id-card__photo id-card__view" src="https://cdn.example/img/profile-photo-yee.png" alt="Profile photo" />
        <div class="id-card__team id-card__view" > <span class="badge badge--success" > <span class="badge__text">Team YEE</span> </span> </div> </div>
        <div class="id-card__details"> <span class="id-card__label">Name</span> <div class="id-card__name id-card__view">Tester</div>
        <div class="id-card__since">Member since January 2, 2021</div> <div class="id-card__fields">
        <div class="id-card__field"> <span class="id-card__label">Nationality</span> <span class="id-card__value id-card__value--private id-card__view">Unspecified</span> </div>
        <div class="id-card__field"> <span class="id-card__label">Standing</span> <span class="id-card__value id-card__standing id-card__standing--normal">Normal</span> </div>
        </div> </div> </div> <div class="id-card__bio"> <span class="id-card__label">Biography</span> <p class="id-card__bio-text id-card__view">Likes &amp; dislikes.</p> </div> </div>
        <div class="id-card__face id-card__face--back"> <div class="id-card__signature id-card__view">
        <svg class="id-card__signature-image" viewBox="0 0 5907 1000" preserveAspectRatio="xMinYMax meet" role="img" aria-label="Signature" > <path d="M78 15L15 78L15 985Z" vector-effect="non-scaling-stroke" /> </svg> </div>
        <p class="id-card__fine-print"> By signing, Bearer agrees. </p> <div class="id-card__footer"> <div class="id-card__barcode">
        <svg class="id-card__barcode-bars" viewBox="0 0 154 1" preserveAspectRatio="none" role="img"><rect x="10" width="2" height="1" /><rect x="13" width="1" height="1" /></svg> </div>
        <div class="id-card__member-no"> <span class="id-card__label">Member No.</span> <span class="id-card__member-no-value">DGG-00000042</span> </div> </div> </div> </div> </div>
    """.trimIndent()

    @Test
    fun readsTheMembershipCard() {
        val card = assertNotNull(parseProfileCard(page, "https://www.example"))
        assertEquals("Tester", card.name)
        assertEquals("Tier 2 Subscriber", card.tier)
        assertEquals("Team YEE", card.team)
        assertEquals("https://cdn.example/img/profile-photo-yee.png", card.photoUrl)
        assertEquals("Member since January 2, 2021", card.since)
        assertEquals(listOf("Nationality" to "Unspecified", "Standing" to "Normal"), card.fields)
        assertEquals("normal", card.standing)
        assertEquals("Likes & dislikes.", card.bio)
        assertEquals(Signature(5907f, 1000f, "M78 15L15 78L15 985Z"), card.signature)
        assertEquals("By signing, Bearer agrees.", card.finePrint)
        assertEquals("DGG-00000042", card.memberNo)
        assertEquals(listOf(10 to 2, 13 to 1), card.bars)
        assertEquals(154, card.barcodeWidth)
    }
}
