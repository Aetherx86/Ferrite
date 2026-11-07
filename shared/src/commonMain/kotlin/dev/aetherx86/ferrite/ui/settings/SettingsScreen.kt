package dev.aetherx86.ferrite.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aetherx86.ferrite.BuildInfo
import dev.aetherx86.ferrite.LocalServices
import dev.aetherx86.ferrite.platform.LocalHost
import dev.aetherx86.ferrite.ui.common.GhostButton
import dev.aetherx86.ferrite.ui.common.Icon
import dev.aetherx86.ferrite.ui.common.IconButton
import dev.aetherx86.ferrite.ui.common.Label
import dev.aetherx86.ferrite.ui.common.LocalFonts
import dev.aetherx86.ferrite.ui.common.Palette
import dev.aetherx86.ferrite.ui.common.Panel
import dev.aetherx86.ferrite.ui.common.SectionTitle
import dev.aetherx86.ferrite.ui.common.textStyle

@Composable
fun SettingsScreen(onBack: () -> Unit, onLicenses: () -> Unit) {
    val services = LocalServices.current
    val settings = services.settings
    val host = LocalHost.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icon.BACK, "뒤로", onBack)
                Label("설정", size = 22.sp, weight = FontWeight.Black)
            }
            if (host.isWebPreview) {
                Spacer(Modifier.height(8.dp))
                Panel {
                    Label("웹 체험판입니다", size = 15.sp, weight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Label("브라우저 보안 규칙 때문에 공공 실시간 정보에 직접 연결할 수 없어 전광판, 공항, 버스는 시연 데이터로 움직입니다. 노선도, 길찾기, 걷기는 실제 데이터입니다. 실시간은 Android 앱에서 쓸 수 있습니다.", size = 13.sp, color = Palette.TextSoft, maxLines = 6)
                }
            }
            SectionTitle("실시간 정보 키")
            Panel {
                Label("키는 이 기기에만 저장되고 해당 서비스에만 보냅니다. 키가 없어도 노선도, 길찾기, 걷기와 배차 간격 기준 추정은 그대로 됩니다.", size = 13.sp, color = Palette.TextSoft, maxLines = 4)
                Spacer(Modifier.height(12.dp))
                KeyField("수도권 전철 실시간 도착", "서울 열린데이터광장의 인증키", settings.seoulKey, settings::updateSeoulKey)
                Spacer(Modifier.height(4.dp))
                GhostButton("키 받는 곳 열기", { host.openUrl("https://data.seoul.go.kr/dataList/OA-12764/F/1/datasetView.do") }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                KeyField("공항 운항, 버스 도착", "공공데이터포털의 일반 인증키", settings.dataKey, settings::updateDataKey)
                Spacer(Modifier.height(4.dp))
                GhostButton("키 받는 곳 열기", { host.openUrl("https://www.data.go.kr/data/15095093/openapi.do") }, Modifier.fillMaxWidth())
            }
            SectionTitle("화면과 소리")
            Panel {
                ToggleRow("전광판 소리", "접근, 도착, 문 닫힘을 소리로 알립니다", settings.soundOn, settings::updateSound)
                Spacer(Modifier.height(10.dp))
                ToggleRow("시연 모드", "실제가 아닌 예시 데이터로 모든 화면을 보여 줍니다", settings.demoMode, settings::updateDemo)
            }
            SectionTitle("정보")
            Panel(onClick = onLicenses) {
                Label("데이터 출처와 오픈소스 라이선스", size = 14.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Label("Ferrite ${BuildInfo.VERSION} · Aetherx86", size = 12.sp, color = Palette.TextDim)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun KeyField(title: String, hint: String, value: String, onChange: (String) -> Unit) {
    val family = LocalFonts.current.mono
    var shown by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Label(title, size = 14.sp, weight = FontWeight.Bold)
    Spacer(Modifier.height(5.dp))
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Palette.InkRaised).border(1.dp, Palette.Hairline, shape).padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(42.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Label(hint, size = 13.sp, color = Palette.TextFaint)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = textStyle(family, 13.sp, androidx.compose.ui.text.font.FontWeight.Medium, Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Label(if (shown) "숨기기" else "보기", size = 12.sp, weight = FontWeight.Bold, color = Palette.TextDim, modifier = Modifier.clickable { shown = !shown }.padding(6.dp))
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Label(title, size = 14.sp, weight = FontWeight.Bold)
            Label(detail, size = 12.sp, color = Palette.TextDim, maxLines = 2)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(46.dp, 26.dp).background(if (on) Palette.Accent else Palette.PanelHigh, RoundedCornerShape(50))) {
            Box(Modifier.offset(x = if (on) 23.dp else 3.dp, y = 3.dp).size(20.dp).background(Palette.Text, CircleShape))
        }
    }
}

/** Sources and licenses. These names appear here because their terms require it. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val items = listOf(
        "철도 노선과 역 위치, 걷는 길" to "© OpenStreetMap 기여자. Open Database License(ODbL)에 따라 이용합니다.",
        "행정구역 경계" to "통계청 통계지리정보서비스. 공공누리 제1유형에 따라 이용합니다.",
        "혼잡도 통계, 환승 거리" to "서울교통공사, 공공데이터포털. 이용허락범위 제한 없음.",
        "수도권 전철 실시간 도착" to "서울특별시 서울 열린데이터광장.",
        "공항 운항 정보" to "인천국제공항공사, 공공데이터포털.",
        "버스 도착 정보" to "서울특별시, 국토교통부 국가대중교통정보센터, 공공데이터포털.",
        "거리 지도" to "\u00A9 OpenFreeMap, \u00A9 OpenMapTiles, \u00A9 OpenStreetMap 기여자.",
        "걷기, 운전 경로와 장소 검색" to "OpenStreetMap 데이터 기반 공개 서비스.",
        "해외 대중교통" to "Transitous, 각 도시 교통기관이 공개한 시간표.",
        "글꼴" to "Ferrite Sans와 Ferrite Mono는 SIL Open Font License 1.1로 배포된 Pretendard(© 2021 Kil Hyung-jin)와 JetBrains Mono(© 2020 The JetBrains Mono Project Authors)를 줄여 만든 글꼴입니다. 라이선스 조건에 따라 이름을 바꿨습니다.",
        "소프트웨어 구성 요소" to "Kotlin, Compose Multiplatform, kotlinx.coroutines, kotlinx.serialization, AndroidX. Apache License 2.0.",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 620.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icon.BACK, "뒤로", onBack)
                Label("데이터 출처와 라이선스", size = 20.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            items.forEach { (title, body) ->
                Label(title, size = 14.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Label(body, size = 13.sp, color = Palette.TextSoft, maxLines = 8)
                Spacer(Modifier.height(14.dp))
            }
            Label("혼잡도 표시는 평균 통계를 바탕으로 한 추정입니다. 실시간 위치와 도착 시각은 운영기관 정보를 그대로 전달하며 실제와 다를 수 있습니다.", size = 12.sp, color = Palette.TextDim, maxLines = 5)
            Spacer(Modifier.height(30.dp))
        }
    }
}
