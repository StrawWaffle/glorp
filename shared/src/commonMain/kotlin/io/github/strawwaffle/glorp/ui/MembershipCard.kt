package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.site.ProfileCard

// The site's card colours (web.css `.id-card__…`), so the card reads as the same card.
private val CardStripe = Color(0xFF0090FF)
private val CardLabel = Color(0xFF777B84)
private val CardSince = Color(0xFF70B8FF)
private val CardBio = Color(0xFFAFB3BA)
private val CardLine = Color(0xFF43484E)
private val CardInk = Color(0xFFEDEEF0)
private val CardPhotoBack = Color(0xFF212225)

/** The site's own card colour, whatever the theme: its text is the site's light ink. */
private val CardBack = Color(0xFF18191B)

private fun standingColor(standing: String?): Color = when (standing) {
    "decorated" -> Color(0xFFFFC53D)
    "noteworthy" -> Color(0xFFFAD173)
    "commendable" -> Color(0xFFF4DDA8)
    "flagged" -> Color(0xFFEAABAF)
    "probationary" -> Color(0xFFE77A7E)
    "condemned" -> Color(0xFFE5484D)
    else -> CardInk
}

/**
 * Someone's membership card, as on their profile page: the front (photo,
 * team, tier, name, when they joined, nationality, gender, age, standing,
 * biography) and, flipped, the back (signature, fine print, member number
 * and its barcode).
 */
@Composable
fun MembershipCard(card: ProfileCard, modifier: Modifier = Modifier) {
    var back by rememberSaveable(card.name) { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, CardLine, RoundedCornerShape(10.dp)).background(CardBack),
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(CardStripe))
        if (!back) Front(card) else Back(card)
        // As the site's flip button: the other side.
        Text(
            if (back) "Show front" else "Show back",
            color = CardSince,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().clickable { back = !back }.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Label(text: String) = Text(text, color = CardLabel, fontSize = 12.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Front(card: ProfileCard) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        card.tier?.let {
            Text(
                it, color = CardSince, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.border(1.dp, CardSince.copy(alpha = 0.4f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.photoUrl?.let { url ->
                    AsyncImage(
                        url, "Profile photo",
                        Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(CardPhotoBack).border(1.dp, CardLine, RoundedCornerShape(8.dp)),
                    )
                }
                card.team?.let {
                    Text(
                        it, color = Color(0xFF3DD68C), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.background(Color(0xFF113B29), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Label("Name")
                Text(card.name, color = CardInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                card.since?.let { Text(it, color = CardSince, fontSize = 13.sp) }
            }
        }
        if (card.fields.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(CardLine))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, value) in card.fields) {
                    Column(Modifier.width(130.dp)) {
                        Label(label)
                        Text(
                            value,
                            color = if (label == "Standing") standingColor(card.standing) else CardInk,
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        card.bio?.let {
            Column {
                Label("Biography")
                Text(it, color = CardBio, fontSize = 14.sp, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
private fun Back(card: ProfileCard) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("Signature")
        val sig = card.signature
        Box(Modifier.fillMaxWidth().height(90.dp)) {
            if (sig != null) {
                val path = remember(sig.path) { runCatching { PathParser().parsePathString(sig.path).toPath() }.getOrNull() }
                if (path != null) Canvas(Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = "Signature" }) {
                    // `preserveAspectRatio="xMinYMax meet"`: fitted, at the bottom left; a 2.5 px line at any size.
                    val s = minOf(size.width / sig.width, size.height / sig.height)
                    translate(0f, size.height - sig.height * s) {
                        scale(s, s, Offset.Zero) {
                            drawPath(path, CardInk, style = Stroke(width = 2.5.dp.toPx() / s, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            } else {
                Text("No signature", color = CardLabel, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomStart))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(CardLine))
        }
        card.finePrint?.let { Text(it, color = CardLabel, fontSize = 11.sp, lineHeight = 16.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (card.bars.isNotEmpty() && card.barcodeWidth > 0) {
                Canvas(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(4.dp)).background(CardInk).semantics {
                    contentDescription = "Barcode: ${card.memberNo.orEmpty()}"
                }) {
                    val unit = size.width / card.barcodeWidth
                    for ((x, w) in card.bars) drawRect(Color(0xFF111113), Offset(x * unit, 0f), Size(w * unit, size.height))
                }
            }
            card.memberNo?.let {
                Column {
                    Label("Member No.")
                    Text(it, color = CardInk, fontSize = 15.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
