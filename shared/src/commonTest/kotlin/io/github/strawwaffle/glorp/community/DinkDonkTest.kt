package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Samples are trimmed from dinkdonk.mov's public pages (2026-11-22).
class DinkDonkTest {
    @Test
    fun readsHistory() {
        val body = """{"results":[
            {"id":4593,"title":"Boogie Nights","year":"1997","episodeTag":null,"imdbId":"tt0118749","watchedAt":"2026-11-21 11:16","watchedAtTs":1795277805,"conductor":"Nuddel","picker":"","notes":""},
            {"id":49,"title":"The Batman","year":"2022","episodeTag":null,"imdbId":"tt1877830","watchedAt":"2026-07-04 04:10","watchedAtTs":1783152600,"conductor":"DivideNZL","picker":null,"notes":null},
            {"id":4588,"title":"Some Kind of Heaven","year":"2021","episodeTag":null,"imdbId":"tt11358166","watchedAt":"2026-11-15 15:17","watchedAtTs":1794773861,"conductor":"Nuddel","picker":"Ozma","notes":"Rare documentary pick","extra":1},
            {"id":1,"title":"","year":"2020"}
        ]}"""
        val history = DinkDonkParse.history(body)
        assertEquals(3, history.size)
        assertEquals(Watched(4593, "Boogie Nights", "1997", null, "tt0118749", 1795277805, "Nuddel", null, null), history[0])
        assertNull(history[1].picker)
        assertEquals("Ozma", history[2].picker)
        assertEquals("Rare documentary pick", history[2].notes)
    }

    @Test
    fun readsTheScheduleInNewYorkTime() {
        val html = """
            <div
        class="schedule-banner"
        data-controller="schedule-banner"
        data-start="2026-11-21 21:00:00"
        data-end="2026-11-22 09:00:00"
        data-timezone="America/New_York"
    >
    <div class="tv-guide" data-tv-guide-target="guide">
                                    <div class="tv-guide__session tv-guide__session--active"
                     data-start="2026-11-21 21:00:00"
                     data-end="2026-11-22 09:00:00">
                    <div class="tv-guide__marker">
                        <span class="tv-guide__marker-time"></span>
                    </div>
                </div>
                                                <div class="tv-guide__session"
                     data-start="2026-11-22 13:00:00"
                     data-end="2026-11-22 17:00:00">
                    <div class="tv-guide__card"></div>
                </div>
                <div class="tv-guide__session" data-start="not a date">
        """
        val schedule = DinkDonkParse.schedule(html)
        // 21:00 EST is 02:00 UTC the next day.
        val start = 1795312800L
        assertEquals(MovieSession(start, start + 12 * 3600), schedule.banner)
        assertEquals(listOf(MovieSession(start, start + 12 * 3600, live = true), MovieSession(start + 16 * 3600, start + 20 * 3600)), schedule.sessions)

        assertEquals(MovieSession(start, start + 12 * 3600, live = false), DinkDonkParse.current(schedule, start - 60))
        assertEquals(true, DinkDonkParse.current(schedule, start + 60)?.live)
        assertEquals(start + 16 * 3600, DinkDonkParse.current(schedule, start + 13 * 3600)?.start)
        assertNull(DinkDonkParse.current(schedule, start + 21 * 3600))
    }

    @Test
    fun readsARatingsPage() {
        val html = """
<a class="rating-card" href="/rate/ksPRS/results">
    <div class="rating-card__poster">
        <img src="https://image.tmdb.org/t/p/w92/wnE24UPCPQsQnbBOu4zVE2qaDNm.jpg"/>
    </div>
    <div>
        <h3 class="rating-card__title">Boogie Nights</h3>
            </div>
            <div class="rating-card__details">
            <span data-controller="format-number" data-format-number-value-value="3.9473684210526" data-format-number-min-decimals-value="0" data-format-number-max-decimals-value="2">3.95</span> / 5 <span class="rating-card__details__ratings">(<span data-controller="format-number" data-format-number-value-value="76" data-format-number-min-decimals-value="0" data-format-number-max-decimals-value="0">76</span>)</span>
        </div>
    </a>
<a class="rating-card" href="/rate/abc12/results">
    <div class="rating-card__poster"></div>
    <div>
        <h3 class="rating-card__title">Guy Ritchie&#039;s The Covenant &amp; more</h3>
            </div>
    </a>
    <div class="ratings-list__next-page">
        <div class="rating-card">
                    <form hx-get="/api/page/ratings" hx-trigger="intersect">
                    <input type="hidden" name="page" type="number" value="2" />
                </form>
            <div class="rating-card__poster"></div>
    <h3 class="rating-card__title">&nbsp;</h3>
    <div class="rating-card__details">&nbsp;</div>
</div>
        """
        val page = DinkDonkParse.ratings(html)
        assertEquals(2, page.next)
        assertEquals(
            listOf(
                RatingCard("ksPRS", "Boogie Nights", "https://image.tmdb.org/t/p/w92/wnE24UPCPQsQnbBOu4zVE2qaDNm.jpg", 3.9473684210526, 76),
                RatingCard("abc12", "Guy Ritchie's The Covenant & more", null, null, null),
            ),
            page.items,
        )
        assertEquals(ListPage(emptyList<RatingCard>(), null), DinkDonkParse.ratings("""<div class="rating-card"><h3 class="rating-card__title">&nbsp;</h3></div>"""))
    }

    @Test
    fun readsAPollsPage() {
        val html = """
<a class="poll-card" href="/poll/j3EJF/results" data-poll="{}">
    <div class="poll-card__details">
        <h3 class="poll-card__title">Unseen only Run-Off</h3>
        <span>Ranked Choice (Borda) Poll by Pizza</span>
    </div>

    <div class="poll-card__winners">
        <div class="poll-card__winner">
                            Winner
                    <img src="https://image.tmdb.org/t/p/w92/a3vQS7JKqlOb3MdVJHuTCP9s7Mg.jpg">

<span>Deep Impact (1998)</span>
                    </div>
                    <div class="poll-card__winner">
                Runner-up
                    <img src="https://image.tmdb.org/t/p/w92/kVG8zFFYrpyYLoHChuEeOGAd6Ru.jpg">
<span>Guy Ritchie&#039;s The Covenant (2023)</span>
            </div>
            </div>
</a>
<a class="poll-card" href="/poll/vcfIS/results" data-poll="{}">
    <div class="poll-card__details">
        <h3 class="poll-card__title">Continuation Poll</h3>
        <span>Single Choice Poll by Pizza</span>
    </div>
    <div class="poll-card__winners">
        <div class="poll-card__winner">
                            Winner
                <span>Continue Conducting</span>
                    </div>
            </div>
</a>
    <div class="polls-list__next-page">
        <div class="poll-card">
                    <form hx-get="/api/page/polls" hx-trigger="intersect">
                    <input type="hidden" name="page" type="number" value="2" />
                </form>
                <div class="poll-card__details">
        <h3 class="poll-card__title">&nbsp;</h3>
        <span>&nbsp;</span>
    </div>
</div>
        """
        val page = DinkDonkParse.polls(html)
        assertEquals(2, page.next)
        assertEquals(
            listOf(
                PollCard("j3EJF", "Unseen only Run-Off", "Ranked Choice (Borda) Poll by Pizza", "Deep Impact (1998)", "https://image.tmdb.org/t/p/w92/a3vQS7JKqlOb3MdVJHuTCP9s7Mg.jpg"),
                PollCard("vcfIS", "Continuation Poll", "Single Choice Poll by Pizza", "Continue Conducting", null),
            ),
            page.items,
        )
    }

    @Test
    fun readsARatingResult() {
        val html = """
<main class="wrapper">
              <div class="section-header">
        <div class="section-header__content">
            <h2 class="section-header__heading">Rating Poll</h2>
            <div class="section-header__footer-group">
                <span class="section-header__footer">Created by Nuddel</span>
                <span class="section-header__footer">Ended on <time data-controller="unix-timestamp" data-unix-timestamp-unix-value="1795288065"></time></span>
            </div>
        </div>
    </div>
    <div id="rating">
        <div class="option-group">
<div class="option">
    <div class="option__top">
            <img src="https://image.tmdb.org/t/p/w92/wnE24UPCPQsQnbBOu4zVE2qaDNm.jpg">
<div class="option__details">
    <div class="option__title-row">
                    <a class="link" href="https://imdb.com/title/tt0118749" target="_blank">Boogie Nights (1997)</a>
                                        <span class="badge badge--neutral" data-tippy-content="Under 17 requires accompanying parent.">R</span>
            </div>
            <div class="option__overview-wrapper" data-controller="overview-expand" >
            <p class="option__overview" data-overview-expand-target="text">In 1977, an idealistic porn producer and his promising protege try to catch up with the end of an era.</p>
        </div>
    <div class="option__details-extra">
                    <div class="option__details-extra--group">
                <span><span data-controller="format-number" data-format-number-value-value="76.33">76.33</span>% (<span data-controller="format-number" data-format-number-value-value="3790">3,790</span> reviews)</span>
            </div>
            </div>
    </div>
            </div>
    </div>
        </div>
        <div class="rating-stars">
            <span>Average <span data-controller="format-number" data-format-number-value-value="3.9473684210526" data-format-number-min-decimals-value="0" data-format-number-max-decimals-value="2">3.95</span> / 5</span>
            <input class="rating-stars__input" type="range" min="0" max="5" step=".01" value="3.95" style="--value:3.95" disabled="true">
            <span><span data-controller="format-number" data-format-number-value-value="76" data-format-number-min-decimals-value="0" data-format-number-max-decimals-value="0">76</span> total votes (and <span data-controller="format-number" data-format-number-value-value="6" data-format-number-min-decimals-value="0" data-format-number-max-decimals-value="0">6</span> didn't watch)</span>
        </div>
    </div>
    <div class="charts">
        <div class="chart" data-ratings="[5,4,5,5,4.5,3,0.5,5,4,4.5]" data-controller="rating-histogram-chart"><canvas data-rating-histogram-chart-target="canvas"></canvas></div>
    </div>
</main>
        """
        val result = assertNotNull(DinkDonkParse.ratingResult(html))
        assertEquals("Boogie Nights (1997)", result.title)
        assertEquals("https://imdb.com/title/tt0118749", result.link)
        assertEquals("https://image.tmdb.org/t/p/w92/wnE24UPCPQsQnbBOu4zVE2qaDNm.jpg", result.poster)
        assertTrue(result.overview!!.startsWith("In 1977, an idealistic"))
        assertEquals("Nuddel", result.createdBy)
        assertEquals(1795288065, result.endedAt)
        assertEquals(3.9473684210526, result.average)
        assertEquals(76, result.votes)
        assertEquals(6, result.didntWatch)
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 0, 2, 2, 4), result.histogram)
    }

    private val rankedPoll = """
<main class="wrapper">
              <div class="section-header">
        <div class="section-header__content">
            <h2 class="section-header__heading">Unseen only Run-Off</h2>
            <div class="section-header__footer-group">
                <span class="section-header__footer">Ranked Choice (Borda) Poll created by Pizza</span>
                <span class="section-header__footer">Ended on <time data-controller="unix-timestamp" data-unix-timestamp-unix-value="1795335177"></time></span>
            </div>
        </div>
    </div>
    <div data-controller="poll-result" data-poll-result-type-value="ranked_choice" data-poll-result-users-value="53">
        <div class="options" data-poll-result-target="options">
                <div class="option-group">
<div class="option">
    <div class="option__top">
            <img src="https://image.tmdb.org/t/p/w92/a3vQS7JKqlOb3MdVJHuTCP9s7Mg.jpg">
<div class="option__details">
    <div class="option__title-row">
                    <a class="link" href="https://imdb.com/title/tt0120647" target="_blank">Deep Impact (1998)</a>
            </div>
    <div class="option__details-extra">
                    <div class="option__details-extra--group">
                <span><span data-controller="format-number" data-format-number-value-value="62.61">62.61</span>% (<span data-controller="format-number" data-format-number-value-value="3558">3,558</span> reviews)</span>
            </div>
            </div>
    </div>
                    <div class="option__end">
                    <span><span data-controller="format-number" data-format-number-value-value="55">55</span> votes (<span data-controller="format-number" data-format-number-value-value="34.591194968553">34.59</span>%)</span>
            </div>
            </div>
            <div class="option__bottom">
    <div class="progressbar"><div class="progressbar__inner" style="width: 34.591194968553%"></div></div>
        </div>
    </div>
                    </div>
                <div class="option-group">
<div class="option">
    <div class="option__top">
            <img src="https://image.tmdb.org/t/p/w92/kVG8zFFYrpyYLoHChuEeOGAd6Ru.jpg">
<div class="option__details">
    <div class="option__title-row">
                    <a class="link" href="https://imdb.com/title/tt4873118" target="_blank">Guy Ritchie&#039;s The Covenant (2023)</a>
            </div>
    </div>
                    <div class="option__end">
                    <span><span data-controller="format-number" data-format-number-value-value="53">53</span> votes (<span data-controller="format-number" data-format-number-value-value="33.333333333333">33.33</span>%)</span>
            </div>
            </div>
            <div class="option__bottom"></div>
    </div>
                    </div>
        </div>
    <div class="section-footer">
        <div class="section-footer__group">
                            <span class="section-header__footer">Total of <span data-controller="format-number" data-format-number-value-value="159">159</span> points and <span data-controller="format-number" data-format-number-value-value="53">53</span> votes received.</span>
        </div>
    </div>
    </div>
</main>
    """

    @Test
    fun readsARankedPollResult() {
        val result = assertNotNull(DinkDonkParse.pollResult(rankedPoll))
        assertEquals("Unseen only Run-Off", result.title)
        assertEquals("Ranked Choice (Borda) Poll created by Pizza", result.byline)
        assertEquals(1795335177, result.endedAt)
        assertEquals("ranked_choice", result.type)
        assertTrue(result.countsPoints)
        assertEquals(
            listOf(
                PollOption("Deep Impact (1998)", "https://imdb.com/title/tt0120647", "https://image.tmdb.org/t/p/w92/a3vQS7JKqlOb3MdVJHuTCP9s7Mg.jpg", 55.0, 34.591194968553, sub = false),
                PollOption("Guy Ritchie's The Covenant (2023)", "https://imdb.com/title/tt4873118", "https://image.tmdb.org/t/p/w92/kVG8zFFYrpyYLoHChuEeOGAd6Ru.jpg", 53.0, 33.333333333333, sub = false),
            ),
            result.options,
        )
        assertEquals("Total of 159 points and 53 votes received.", result.footer)
    }

    @Test
    fun readsSubOptionsAndLeavesRunoffRoundsToTheSite() {
        val single = """
<main>
    <h2 class="section-header__heading">Continuation Poll</h2>
    <div data-controller="poll-result" data-poll-result-type-value="single_choice" data-poll-result-users-value="29">
        <div class="options" data-poll-result-target="options">
                <div class="option-group">
<div class="option">
    <div class="option__top">
        <div class="option__details">
    <span>Continue Conducting</span>
</div>
                    <div class="option__end">
                    <span><span data-controller="format-number" data-format-number-value-value="28">28</span> votes (<span data-controller="format-number" data-format-number-value-value="96.551724137931">96.55</span>%)</span>
            </div>
            </div>
            <div class="option__bottom"></div>
    </div>
<div class="option option-group__sub">
    <div class="option__top">
        <div class="option__details">
    <span>With New Conductor</span>
</div>
                    <div class="option__end">
                    <span><span data-controller="format-number" data-format-number-value-value="7">7</span> votes (<span data-controller="format-number" data-format-number-value-value="25">25.00</span>%)</span>
            </div>
            </div>
    </div>
        </div>
    </div>
</main>
        """
        val result = assertNotNull(DinkDonkParse.pollResult(single))
        assertEquals(
            listOf(
                PollOption("Continue Conducting", null, null, 28.0, 96.551724137931, sub = false),
                PollOption("With New Conductor", null, null, 7.0, 25.0, sub = true),
            ),
            result.options,
        )
        val runoff = assertNotNull(DinkDonkParse.pollResult(rankedPoll.replace("ranked_choice", "instant_runoff")))
        assertEquals(emptyList(), runoff.options)
        assertNull(DinkDonkParse.pollResult("<html><body>Just a moment...</body></html>"))
    }

    @Test
    fun picksPosterSizes() {
        assertEquals("https://image.tmdb.org/t/p/w154/x.jpg", tmdbPoster("https://image.tmdb.org/t/p/w92/x.jpg", "w154"))
        assertEquals("https://example.com/x.jpg", tmdbPoster("https://example.com/x.jpg", "w154"))
        assertNull(tmdbPoster(null, "w154"))
    }
}
