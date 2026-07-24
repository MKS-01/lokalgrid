package dev.lokalgrid.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.lokalgrid.app.LiveState
import dev.lokalgrid.app.ui.BigValue
import dev.lokalgrid.app.ui.InfoRow
import dev.lokalgrid.app.ui.LgButton
import dev.lokalgrid.app.ui.Pill
import dev.lokalgrid.app.ui.PillKind
import dev.lokalgrid.app.ui.SectionLabel
import dev.lokalgrid.app.ui.theme.Lg
import dev.lokalgrid.protocol.TrackRecord
import kotlin.math.abs

@Composable
fun LiveScreen(state: LiveState) {
    val r = state.latest
    Column(
        Modifier
            .fillMaxSize()
            .background(Lg.Paper)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        if (r == null) {
            SectionLabel("fix")
            InfoRow("status", state.status)
            InfoRow("waiting", "no fix yet")
            return@Column
        }

        SectionLabel("fix")
        InfoRow("fix") {
            Pill(if (r.fix3d) "3D · ${r.sv} sv" else "2D · ${r.sv} sv", if (r.fix3d) PillKind.OK else PillKind.WARN)
        }
        BigValue("%.5f %s\n%.5f %s".format(abs(r.latDeg), if (r.latDeg >= 0) "N" else "S", abs(r.lonDeg), if (r.lonDeg >= 0) "E" else "W"))
        InfoRow("accuracy", "± %.0f m (hdop %.1f)".format(dev.lokalgrid.app.ui.accuracyMeters(r.hd), r.hd / 10.0))
        InfoRow("altitude", if (r.baro == TrackRecord.BARO_ABSENT) "${r.alt} m · no baro" else "${r.alt} m · baro ${r.baro}")
        if (!r.timeValid) InfoRow("time") { Pill("invalid — repair on client", PillKind.WARN) }

        SectionLabel("people · dummy · phase 02")
        InfoRow("you") { Pill("here", PillKind.OK) }
        for (p in dev.lokalgrid.app.ui.dummyPeers(r)) {
            InfoRow(p.name, value = dev.lokalgrid.app.ui.relative(r, p))
        }

        SectionLabel("node")
        InfoRow("battery", "${r.bat}%")
        InfoRow("charging", if (r.charging) "yes" else "no")
        InfoRow("last fix", "#${r.seqLo} · ${state.fixCount} received" + if (state.dropped > 0) " · ${state.dropped} dropped" else "")

        LgButton("Send position to all", primary = true)
    }
}
