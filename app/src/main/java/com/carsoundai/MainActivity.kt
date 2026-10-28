package com.carsoundai

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.log10
import kotlin.math.sqrt

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var lowValue: TextView
    private lateinit var midValue: TextView
    private lateinit var highValue: TextView
    private lateinit var micResult: TextView
    private lateinit var spectrumView: LinearLayout
    private lateinit var carEqGuide: TextView
    private lateinit var volumeStageLabel: TextView
    private lateinit var volumeProfileResult: TextView
    private lateinit var guideText: TextView
    @Volatile private var measuring = false
    private var micThread: Thread? = null
    private var measurementUiTicker: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var low = 0
    private var mid = 0
    private var high = 0
    private var savedA: Triple<Int, Int, Int>? = null
    private var savedB: Triple<Int, Int, Int>? = null
    private var showingA = false
    private var step = 0
    private var audioTrack: AudioTrack? = null
    private var equalizer: Equalizer? = null
    private var playing = false
    private var toneThread: Thread? = null
    private val sampleRate = 44100

    private var selectedVolumeStage = 5
    private var measurementDurationSeconds = 20
    private data class VolumeProfile(val bassDb: Double, val midDb: Double, val trebleDb: Double, val bandDb: List<Double>, val bassRec: Int, val trebleRec: Int, val inputDbfs: Double)
    private val volumeProfiles = mutableMapOf<Int, VolumeProfile>()

    private val steps = listOf(
        "1/5 · 기준 소리", "2/5 · 저음", "3/5 · 보컬 선명도",
        "4/5 · 악기 분리감", "5/5 · 전체 균형"
    )
    private val guidance = listOf(
        "차량을 안전하게 주차하고 평소 듣는 음량으로 시작하세요. 먼저 기준 소리를 들어보세요.",
        "킥과 베이스가 약하면 저음을 +2 정도 올려보세요. 웅웅거리면 -2 정도 낮추세요.",
        "목소리가 묻히면 중음을 +1~+2 올려보세요. 소리가 코맹맹이처럼 들리면 조금 낮추세요.",
        "악기가 뭉치면 고음을 +1 정도 올려보세요. 치찰음이 거슬리면 고음을 낮추세요.",
        "한 번에 크게 바꾸지 말고 한 항목씩 조절한 뒤 기준과 비교해 가장 편안한 설정을 고르세요."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadVolumeProfiles()
        loadEqSettings()
        buildUi()
        setEq(low, mid, high)
        updateABLabels()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f, color: Int = Color.rgb(224, 235, 247)): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
    }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.rgb(0, 119, 255), Color.rgb(0, 190, 238))
            ).apply { cornerRadius = dp(16).toFloat() }
            stateListAnimator = null
            setOnClickListener { action() }
        }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(5, 15, 29))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Hero header: app identity and visual sound-wave motif.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 35, 67), Color.rgb(27, 91, 145))
            ).apply { cornerRadius = dp(24).toFloat() }
        }
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
        }
        heroText.addView(TextView(this).apply {
            text = "Selectru"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        heroText.addView(TextView(this).apply {
            text = "LISTEN  ·  MEASURE  ·  TUNE"
            textSize = 11f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(190, 225, 249))
            setPadding(0, dp(3), 0, dp(4))
        })
        heroText.addView(TextView(this).apply {
            text = "선택은 자유롭게, 진실은 정확하게."
            textSize = 14f
            setTextColor(Color.WHITE)
        })
        hero.addView(heroText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(hero, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(18)
        })

        val quickSummary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf("01  듣기", "02  측정", "03  선택").forEachIndexed { i, title ->
            val chip = TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) Color.WHITE else Color.rgb(170, 216, 245))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (i == 0) Color.rgb(25, 103, 168) else Color.rgb(13, 42, 69))
                }
            }
            quickSummary.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        root.addView(quickSummary)
        root.addView(sectionHeader("Selectru 사운드 가이드", "선택은 자유롭게, 진실은 정확하게."))
        status = label(steps[0], 17f)
        root.addView(status)
        guideText = label(guidance[0])
        root.addView(guideText)
        val presetPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val presetHeader = sectionHeader("공간·장르 이퀄라이저 ▾", "눌러서 프리셋 펼치기 / 접기")
        presetHeader.setOnClickListener {
            val opening = presetPanel.visibility != View.VISIBLE
            presetPanel.visibility = if (opening) View.VISIBLE else View.GONE
            (presetHeader.getChildAt(0) as TextView).text = if (opening) "공간·장르 이퀄라이저 ▴" else "공간·장르 이퀄라이저 ▾"
            if (opening) scroll.post { scroll.smoothScrollTo(0, presetHeader.top) }
        }
        root.addView(presetHeader)
        fun presetRow(vararg items: Pair<String, Triple<Int, Int, Int>>) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            items.forEach { (title, values) ->
                row.addView(button(title) { setEq(values.first, values.second, values.third); status.text = "프리셋 적용: $title" },
                    LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(2); rightMargin = dp(2) })
            }
            presetPanel.addView(row)
        }
        presetRow("균형" to Triple(0,0,0), "저음 강조" to Triple(4,0,1), "보컬" to Triple(-1,3,0))
        presetRow("콘서트홀" to Triple(2,0,2), "라이브" to Triple(2,2,2), "클럽" to Triple(5,1,3))
        presetRow("차량" to Triple(2,1,1), "스튜디오" to Triple(0,1,0), "시네마" to Triple(3,0,2))
        presetRow("어쿠스틱" to Triple(-1,2,2), "팟캐스트" to Triple(-2,4,1), "야간 저음" to Triple(2,1,-2))
        presetPanel.addView(label("프리셋은 저음·중음·고음 3밴드의 시작값입니다. 콘서트홀·라이브 같은 이름은 실제 잔향 효과를 생성하지 않으며, 공간 음향이나 리버브가 아닌 톤 밸런스만 바꿉니다.", 13f))
        root.addView(presetPanel)

        root.addView(sectionHeader("AI 자동 이퀄라이징", "측정값을 분석해 저음·중음·고음을 한 번에 조정"))
        root.addView(label("현재 버전은 저장된 8대역 측정값과 규칙 기반 분석으로 보수적인 EQ 시작값을 자동 계산합니다. 학습형 AI 모델이나 차량 DSP 직접 제어는 아니며, 적용 후 반드시 청취 비교하세요.", 13f))
        root.addView(button("✨ 측정값 기반 AI 자동 EQ 적용") { autoAiEqualize(true) })
        root.addView(button("측정 없이 기본 자동 EQ 적용") { autoAiEqualize(false) })

        root.addView(label("저음 (Bass)", 17f))
        lowValue = label("0 dB")
        root.addView(lowValue)
        root.addView(makeSlider { v -> low = v; lowValue.text = dbLabel(v); applyEq(); updateCarEqGuide() })
        root.addView(label("중음 · 보컬 (Mid)", 17f))
        midValue = label("0 dB")
        root.addView(midValue)
        root.addView(makeSlider { v -> mid = v; midValue.text = dbLabel(v); applyEq(); updateCarEqGuide() })
        root.addView(label("고음 · 선명도 (Treble)", 17f))
        highValue = label("0 dB")
        root.addView(highValue)
        root.addView(makeSlider { v -> high = v; highValue.text = dbLabel(v); applyEq(); updateCarEqGuide() })

        root.addView(sectionHeader("차량 EQ 가이드", "앱의 추천값을 차량 오디오에서 직접 조절"))
        carEqGuide = label("")
        root.addView(carEqGuide)
        root.addView(label("위 추천은 조정 방향과 강도 참고용입니다. 차량 EQ의 밴드 수와 범위가 다르므로 숫자를 그대로 복사하지 말고, 차량 EQ에서 가장 가까운 대역을 비슷한 방향으로 조금씩 조절하세요. 먼저 차량 EQ를 0으로 맞춘 뒤 적용하고, 주행 중에는 조작하지 마세요.", 14f))
        updateCarEqGuide()

        root.addView(sectionHeader("A/B 비교", "두 설정을 저장하고 귀로 비교하세요"))
        root.addView(button("현재 설정을 A로 저장") {
            savedA = Triple(low, mid, high)
            showingA = true
            saveABSettings()
            updateABLabels()
            status.text = "A 설정 저장됨"
        })
        root.addView(button("현재 설정을 B로 저장") {
            savedB = Triple(low, mid, high)
            showingA = false
            saveABSettings()
            updateABLabels()
            status.text = "B 설정 저장됨"
        })
        root.addView(button("A ↔ B 전환") { switchAB() }.apply { tag = "ab_switch" })
        root.addView(button("저장된 A/B 설정 지우기") { savedA = null; savedB = null; saveABSettings(); updateABLabels(); status.text = "A/B 저장 설정을 초기화했습니다." })
        root.addView(label("A와 B를 각각 저장한 뒤 전환하며 비교하세요. 이 비교는 앱의 테스트 톤에 적용됩니다.", 14f))

        root.addView(sectionHeader("볼륨별 측정", "단계별 기록을 저장해 차이를 확인"))
        root.addView(label("분석 시간", 15f))
        val durationPicker = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("20초 빠른 측정", "5분 수집 테스트", "20분 장시간 수집", "60분 장시간 수집"))
            setSelection(0)
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    measurementDurationSeconds = listOf(20, 300, 1200, 3600).getOrElse(position) { 20 }
                }
            }
        }
        root.addView(durationPicker)
        root.addView(label("장시간 측정은 휴대전화 마이크의 상대 스펙트럼을 여러 프레임 평균하는 테스트입니다. 보정된 음압 측정이나 전문적인 전달함수 측정을 대체하지 않습니다. 테스트 중 차량은 주차 상태로 두고, 마이크 위치와 재생 음량을 유지하세요.", 13f))
        root.addView(label("차량 오디오 볼륨을 직접 맞춘 뒤 측정용 핑크 노이즈를 재생하고 측정하세요. 앱이 차량 볼륨을 직접 제어하지는 않으며, 단계별 스펙트럼을 따로 저장해 비교합니다.", 14f))
        volumeStageLabel = label("측정할 차량 볼륨 단계: 5 / 10", 17f)
        root.addView(volumeStageLabel)
        val volumeStageSlider = SeekBar(this).apply {
            max = 9
            progress = 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedVolumeStage = progress + 1
                    volumeStageLabel.text = "측정할 차량 볼륨 단계: $selectedVolumeStage / 10" + if (volumeProfiles.containsKey(selectedVolumeStage)) " · 측정 저장됨" else " · 미측정"
                    showVolumeProfile()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(volumeStageSlider)
        root.addView(button("현재 볼륨 단계 측정 시작 / 취소") { if (measuring) { measuring = false; micResult.text = "측정을 취소했습니다." } else startNoiseMeasurement() })
        volumeProfileResult = label("각 단계에서 같은 음악 구간을 측정하면 볼륨별 차이를 비교할 수 있습니다.", 14f)
        root.addView(volumeProfileResult)
        root.addView(button("볼륨별 측정 기록 초기화") { volumeProfiles.clear(); saveVolumeProfiles(); showVolumeProfile() })

        root.addView(sectionHeader("주파수 진단", "8개 대역을 그래프로 확인하고 보정 시작값을 참고하세요"))
        spectrumView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(8, 24, 42))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(34, 88, 133))
            }
        }
        spectrumView.addView(label("측정 후 8개 주파수 대역이 여기에 표시됩니다.", 13f, Color.rgb(157, 190, 218)))
        root.addView(spectrumView, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        micResult = label("분석 전 · 안전하게 주차한 뒤 측정용 핑크 노이즈를 차량 스피커로 재생하세요. 측정 시간은 20초입니다.")
        root.addView(micResult)
        root.addView(button("선택한 볼륨 단계에서 선택 시간 분석 / 취소") { if (measuring) { measuring = false; micResult.text = "측정을 취소했습니다." } else startNoiseMeasurement() })
        root.addView(label("핑크 노이즈 입력을 8개 대역으로 분석하고 로그 주파수 폭을 보정한 상대 레벨과 보정 시작값을 표시합니다. 현재 진단은 규칙 기반 프로토타입이며 학습된 AI 모델이나 보정 마이크 측정은 아닙니다.", 14f))

        root.addView(button("측정용 핑크 노이즈 재생 / 정지") { toggleTone() })
        root.addView(button("이전 단계") { step = (step - 1).coerceAtLeast(0); updateStep() })
        root.addView(button("다음 단계") { step = (step + 1).coerceAtMost(4); updateStep() })
        root.addView(button("현재 설정 초기화") { setEq(0, 0, 0) })
        val manualPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val manualHeader = sectionHeader("내장 사용설명서 ▾", "눌러서 설명을 펼치고 아래로 스크롤")
        manualHeader.setOnClickListener {
            val opening = manualPanel.visibility != View.VISIBLE
            manualPanel.visibility = if (opening) View.VISIBLE else View.GONE
            (manualHeader.getChildAt(0) as TextView).text = if (opening) "내장 사용설명서 ▴" else "내장 사용설명서 ▾"
            if (opening) scroll.post { scroll.smoothScrollTo(0, manualHeader.top) }
        }
        root.addView(manualHeader)
        manualPanel.addView(sectionHeader("1. 처음 시작하기", "안전한 측정 순서"))
        manualPanel.addView(label("① 차량을 안전하게 주차하고 주차 브레이크를 적용합니다. ② 창문을 닫고 HVAC와 음장 효과를 가능한 한 끕니다. ③ 휴대전화 마이크를 가리지 않고 고정합니다. ④ 차량 볼륨을 일정하게 맞춥니다. ⑤ 앱의 핑크 노이즈를 재생하고 20초 분석을 시작합니다. 측정 중에는 소리 크기와 휴대전화 위치를 바꾸지 마세요.", 14f))
        manualPanel.addView(sectionHeader("2. 이퀄라이저 프리셋", "공간·장르별 시작값"))
        manualPanel.addView(label("균형은 기준점, 저음 강조·클럽은 저역을 더 강조, 보컬·팟캐스트는 말소리 대역을 강조, 어쿠스틱은 중고역을 살리는 방향입니다. 차량·라이브·콘서트홀·시네마 프리셋도 저음/중음/고음 3밴드만 바꿉니다. 실제 콘서트홀 잔향, 서라운드, 리버브를 만들어 내지는 않습니다. 취향과 스피커에 따라 조금씩 조절하세요.", 14f))
        manualPanel.addView(sectionHeader("3. 측정 결과 읽기", "8대역 스펙트럼과 dBFS"))
        manualPanel.addView(label("8대역 막대는 휴대전화 마이크에 들어온 소리의 상대적인 대역 레벨입니다. dBFS는 디지털 입력 레벨로, 0 dBFS에 가까울수록 클리핑 위험이 커집니다. 입력 레벨이 너무 낮거나 클리핑 경고가 있으면 볼륨을 안전한 범위에서 조정하고 다시 측정하세요. 이 값은 보정된 SPL이나 차량 스피커의 절대 주파수 응답이 아닙니다.", 14f))
        manualPanel.addView(sectionHeader("4. A/B 비교와 볼륨별 기록", "변경 전후를 비교"))
        manualPanel.addView(label("현재 EQ를 A로 저장하고 다른 설정을 B로 저장한 뒤 A↔B 전환으로 비교합니다. 저장 설정과 볼륨 단계별 측정 기록은 앱을 다시 열어도 유지됩니다. 같은 음악 구간·같은 측정 위치·같은 차량 볼륨을 사용해야 비교가 더 의미 있습니다.", 14f))
        manualPanel.addView(sectionHeader("5. 한계와 안전", "측정 결과는 참고값"))
        manualPanel.addView(label("이 버전은 규칙 기반 분석 프로토타입이며 학습된 AI나 보정 마이크 기반 장비가 아닙니다. 휴대전화 마이크의 자동 게인, 실내 반사음, 주변 소음, 음악 자체의 주파수 구성에 영향을 받습니다. 다른 앱이나 차량 블루투스의 음악 EQ를 직접 바꾸지 못하므로 차량 헤드유닛에서 추천 방향을 적용하세요. 주행 중 조작하거나 큰 테스트 음을 오래 재생하지 마세요.", 14f))
        manualPanel.addView(label("안전을 위해 주행 중에는 조작하지 마세요.", 14f, Color.rgb(255, 130, 130)))
        root.addView(manualPanel)

        root.addView(sectionHeader("공개 데이터·프로필 공유", "공개 연구자료를 참고하고 측정 프로필을 파일로 공유"))
        root.addView(label("공개 데이터는 용도와 라이선스를 확인해 사용합니다. AutoEq는 주로 헤드폰 주파수 응답 데이터이므로 차량 EQ에 그대로 적용하지 않습니다. 차량 실내 음향 연구자료는 모델 학습·검증 참고용이며, 데이터 파일 자체가 자동 보정 AI는 아닙니다.", 13f))
        root.addView(button("AutoEq 공개 EQ 데이터 정보 열기") { openExternal("https://github.com/jaakkopasanen/AutoEq") })
        root.addView(button("차량 음향 ANIR 연구 데이터 정보 열기") { openExternal("https://dss-kiel.de/index.php/media-center/data-bases/anir-corpus/") })
        root.addView(button("공개 실내 음향 데이터 목록 열기") { openExternal("https://github.com/moisestohias/IR-datasets") })
        root.addView(button("내 EQ·측정값 JSON 내보내기 / 공유") { exportProfileFile() })
        root.addView(button("공유된 EQ·측정값 JSON 가져오기") { importProfileFile() })
        root.addView(label("차량/순정 오디오/튜닝 목적을 붙인 공유 프로필을 만들어 다른 사용자가 검색·비교할 수 있습니다.", 13f))
        val vehicleNameInput = android.widget.EditText(this).apply { hint = "차량명 (예: 아이오닉 5)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val audioSystemInput = android.widget.EditText(this).apply { hint = "오디오 시스템 (예: 순정 오디오)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val speakerSetupInput = android.widget.EditText(this).apply { hint = "스피커 구성 (예: 순정 6스피커 / 프론트 2웨이)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val speakerBrandInput = android.widget.EditText(this).apply { hint = "스피커 제조사 (선택)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val speakerModelInput = android.widget.EditText(this).apply { hint = "스피커 모델명 (선택)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val speakerLocationInput = android.widget.EditText(this).apply { hint = "장착 위치 (예: 프론트 도어 / 대시보드 트위터)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val dspInput = android.widget.EditText(this).apply { hint = "앰프·DSP / 크로스오버 설정 (선택)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        val profileNameInput = android.widget.EditText(this).apply { hint = "프로필 이름 (예: 순정 중저음 균형형)"; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        root.addView(vehicleNameInput); root.addView(audioSystemInput)
        root.addView(speakerSetupInput); root.addView(speakerBrandInput); root.addView(speakerModelInput)
        root.addView(speakerLocationInput); root.addView(dspInput); root.addView(profileNameInput)
        val profileStyle = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("중저음 강조", "고음·선명도", "보컬 중심", "균형형", "저음 절제·야간", "장르 혼합"))
        }
        root.addView(profileStyle)
        val profileNotesInput = android.widget.EditText(this).apply { hint = "설명·측정 조건 (선택)"; minLines = 2; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY) }
        root.addView(profileNotesInput)
        root.addView(button("MYBOX에 수집 데이터·차량 프로필 저장") {
            exportProfileFile(vehicleNameInput.text.toString().ifBlank { "현대 아이오닉 5" }, audioSystemInput.text.toString().ifBlank { "순정 오디오" },
                profileNameInput.text.toString().ifBlank { "IONIQ5 기준 측정 프로필" }, profileStyle.selectedItem?.toString() ?: "균형형",
                profileNotesInput.text.toString(), speakerSetupInput.text.toString(), speakerBrandInput.text.toString(),
                speakerModelInput.text.toString(), speakerLocationInput.text.toString(), dspInput.text.toString())
        })
            exportProfileFile(vehicleNameInput.text.toString(), audioSystemInput.text.toString(),
                profileNameInput.text.toString(), profileStyle.selectedItem?.toString() ?: "균형형",
                profileNotesInput.text.toString(), speakerSetupInput.text.toString(),
                speakerBrandInput.text.toString(), speakerModelInput.text.toString(),
                speakerLocationInput.text.toString(), dspInput.text.toString())
        })
        root.addView(label("MYBOX 저장을 누른 뒤 파일 저장 위치 선택 화면에서 MYBOX를 선택하고 수집데이터/IONIQ5 폴더를 지정하세요. 폴더가 없으면 MYBOX에서 먼저 만들어야 할 수 있습니다. 이 방식은 Android 파일 선택기를 통한 저장이며 자동 백그라운드 동기화나 MYBOX 계정 직접 연동은 아닙니다.\\n공유 파일에는 차량명·오디오 종류·스피커 구성·제조사/모델·장착 위치·DSP 정보·튜닝 성향·설명과 EQ/A-B/측정 기록이 담깁니다. 이 앱 버전은 파일 내보내기·가져오기까지 제공하며 온라인 공개 카탈로그·검색 서버는 아직 없습니다. 차량별 세팅은 트림·스피커·좌석·측정 위치에 따라 달라질 수 있어 ‘공식 최적값’이 아닌 사용자 공유값으로 표시해야 합니다.", 13f))
    }

    private fun openExternal(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { status.text = "링크를 열 수 없습니다. 인터넷 연결을 확인하세요." }
    }

    private fun exportProfileFile(vehicle: String = "", audioSystem: String = "", profileName: String = "", style: String = "균형형", notes: String = "", speakerSetup: String = "", speakerBrand: String = "", speakerModel: String = "", speakerLocation: String = "", dsp: String = "") {
        val json = JSONObject()
        json.put("format", "selectru-profile")
        json.put("version", 1)
        json.put("createdAt", System.currentTimeMillis())
        json.put("vehicle", vehicle.trim())
        json.put("audioSystem", audioSystem.trim())
        json.put("profileName", profileName.trim())
        json.put("soundStyle", style)
        json.put("notes", notes.trim())
        json.put("speakerSetup", speakerSetup.trim())
        json.put("speakerBrand", speakerBrand.trim())
        json.put("speakerModel", speakerModel.trim())
        json.put("speakerLocation", speakerLocation.trim())
        json.put("dsp", dsp.trim())
        json.put("eq", JSONObject().put("bass", low).put("mid", mid).put("treble", high))
        json.put("ab", JSONObject().apply {
            savedA?.let { put("a", JSONArray(listOf(it.first, it.second, it.third))) }
            savedB?.let { put("b", JSONArray(listOf(it.first, it.second, it.third))) }
        })
        val measurements = JSONArray()
        volumeProfiles.toSortedMap().forEach { (stage, p) ->
            measurements.put(JSONObject().put("stage", stage).put("bassDb", p.bassDb)
                .put("midDb", p.midDb).put("trebleDb", p.trebleDb)
                .put("bands", JSONArray(p.bandDb)).put("bassRec", p.bassRec)
                .put("trebleRec", p.trebleRec).put("inputDbfs", p.inputDbfs))
        }
        json.put("measurements", measurements)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, (profileName.trim().ifBlank { "selectru-profile" }.replace(Regex("[^A-Za-z0-9가-힣_-]"), "_") + ".json"))
        }
        pendingExportJson = json.toString(2)
        startActivityForResult(intent, 72)
    }

    private var pendingExportJson: String? = null

    private fun importProfileFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, 73)
    }

    @Deprecated("Uses document picker for Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        try {
            if (requestCode == 72) {
                val payload = pendingExportJson ?: return
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) }
                pendingExportJson = null
                status.text = "Selectru 프로필 JSON을 내보냈습니다. 파일을 원하는 사람과 공유할 수 있습니다."
            } else if (requestCode == 73) {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("파일을 읽을 수 없습니다.")
                val json = JSONObject(raw)
                require(json.optString("format") == "selectru-profile") { "Selectru 프로필 파일이 아닙니다." }
                val eq = json.optJSONObject("eq")
                if (eq != null) setEq(eq.optInt("bass", 0), eq.optInt("mid", 0), eq.optInt("treble", 0))
                val ab = json.optJSONObject("ab")
                fun triple(key: String): Triple<Int, Int, Int>? {
                    val arr = ab?.optJSONArray(key) ?: return null
                    if (arr.length() != 3) return null
                    return Triple(arr.getInt(0).coerceIn(-12,12), arr.getInt(1).coerceIn(-12,12), arr.getInt(2).coerceIn(-12,12))
                }
                if (ab != null) {
                    savedA = triple("a"); savedB = triple("b"); saveABSettings(); updateABLabels()
                }
                val measurements = json.optJSONArray("measurements") ?: JSONArray()
                var imported = 0
                for (i in 0 until measurements.length()) {
                    val p = measurements.optJSONObject(i) ?: continue
                    val stage = p.optInt("stage", 0)
                    val bandsJson = p.optJSONArray("bands") ?: continue
                    if (stage !in 1..10 || bandsJson.length() != 8) continue
                    val bands = (0 until bandsJson.length()).map { bandsJson.getDouble(it) }
                    volumeProfiles[stage] = VolumeProfile(
                        p.optDouble("bassDb"), p.optDouble("midDb"), p.optDouble("trebleDb"),
                        bands, p.optInt("bassRec").coerceIn(-6,6), p.optInt("trebleRec").coerceIn(-6,6),
                        p.optDouble("inputDbfs")
                    )
                    imported++
                }
                saveVolumeProfiles(); showVolumeProfile()
                status.text = "프로필 가져오기 완료 · 측정 기록 ${imported}개. 출처와 측정 환경을 확인한 뒤 사용하세요."
            }
        } catch (e: Exception) {
            pendingExportJson = null
            status.text = "파일 처리 실패: ${e.message ?: "형식 또는 권한을 확인하세요"}"
        }
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.rgb(10, 29, 51))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(34, 88, 133))
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(Color.rgb(245, 249, 255))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(157, 190, 218))
                setPadding(0, dp(3), 0, 0)
            })
            val params = LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(18)
            params.bottomMargin = dp(7)
            layoutParams = params
        }
    }

    private fun makeSlider(onChanged: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            max = 24
            progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    onChanged(progress - 12)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun dbLabel(value: Int): String = String.format(Locale.getDefault(), "%+d 단계", value)

    private fun setEq(l: Int, m: Int, h: Int) {
        low = l.coerceIn(-12, 12); mid = m.coerceIn(-12, 12); high = h.coerceIn(-12, 12)
        if (::lowValue.isInitialized) lowValue.text = dbLabel(low)
        if (::midValue.isInitialized) midValue.text = dbLabel(mid)
        if (::highValue.isInitialized) highValue.text = dbLabel(high)
        getSharedPreferences("selectru_settings", MODE_PRIVATE).edit()
            .putInt("bass", low).putInt("mid", mid).putInt("treble", high).apply()
        val sliders = mutableListOf<SeekBar>()
        collectSliders(root, sliders)
        if (sliders.size >= 3) {
            sliders[0].progress = low + 12
            sliders[1].progress = mid + 12
            sliders[2].progress = high + 12
        }
        applyEq()
        updateCarEqGuide()
    }

    private fun autoAiEqualize(useMeasurement: Boolean) {
        val profile = if (useMeasurement) volumeProfiles[selectedVolumeStage] else null
        var bass = profile?.bassRec ?: 0
        var mid = 0
        var treble = profile?.trebleRec ?: 0
        var note = "측정값이 없어 중립을 기준으로 한 기본 자동 보정을 적용했습니다."
        val bands = profile?.bandDb
        if (bands != null && bands.size >= 8) {
            val avg = bands.average()
            val lowAvg = bands.take(3).average()
            val midAvg = bands.subList(3, 6).average()
            val highAvg = bands.takeLast(2).average()
            bass = (bass + when { lowAvg < avg - 3.0 -> 2; lowAvg > avg + 3.0 -> -2; else -> 0 }).coerceIn(-6, 6)
            mid = (when { midAvg < avg - 2.5 -> 2; midAvg > avg + 2.5 -> -2; else -> 0 }).coerceIn(-4, 4)
            treble = (treble + when { highAvg < avg - 3.0 -> 2; highAvg > avg + 3.0 -> -2; else -> 0 }).coerceIn(-6, 6)
            note = "저·중·고 대역의 상대 레벨을 분석해 자동 계산했습니다. 실내 반사와 휴대폰 마이크 특성으로 오차가 있을 수 있습니다."
        }
        setEq(bass, mid, treble)
        status.text = "자동 EQ 적용 · 저음 $bass / 중음 $mid / 고음 $treble"
        micResult.text = "자동 EQ 결과\\n저음 $bass · 중음 $mid · 고음 $treble\\n$note\\n청취 후 슬라이더로 미세 조정하세요."
    }

    private fun updateCarEqGuide() {
        if (!::carEqGuide.isInitialized) return
        fun direction(value: Int): String = when {
            value > 0 -> "+$value 단계 올리기"
            value < 0 -> "$value 단계 낮추기"
            else -> "기준(0)"
        }
        carEqGuide.text = "저음 / Bass: ${direction(low)}\n중음·보컬 / Mid: ${direction(mid)}\n고음·선명도 / Treble: ${direction(high)}"
    }

    private fun collectSliders(view: View, output: MutableList<SeekBar>) {
        if (view is SeekBar) output.add(view)
        if (view is LinearLayout) for (i in 0 until view.childCount) collectSliders(view.getChildAt(i), output)
        if (view is ScrollView) collectSliders(view.getChildAt(0), output)
    }

    private fun switchAB() {
        val a = savedA
        val b = savedB
        if (a == null || b == null) {
            status.text = "먼저 A와 B 설정을 각각 저장하세요."
            return
        }
        val target = if (showingA) b else a
        setEq(target.first, target.second, target.third)
        showingA = !showingA
        saveABSettings()
        status.text = if (showingA) "A 설정 적용 중" else "B 설정 적용 중"
    }

    private fun updateStep() {
        status.text = steps[step]
        if (::guideText.isInitialized) guideText.text = guidance[step]
        if (step == 4) status.append(" · 완료")
    }

    private fun loadEqSettings() {
        val prefs = getSharedPreferences("selectru_settings", MODE_PRIVATE)
        low = prefs.getInt("bass", 0).coerceIn(-12, 12)
        mid = prefs.getInt("mid", 0).coerceIn(-12, 12)
        high = prefs.getInt("treble", 0).coerceIn(-12, 12)
        val a = listOf("aBass", "aMid", "aTreble").map { prefs.getInt(it, Int.MIN_VALUE) }
        val b = listOf("bBass", "bMid", "bTreble").map { prefs.getInt(it, Int.MIN_VALUE) }
        if (a.none { it == Int.MIN_VALUE }) savedA = Triple(a[0], a[1], a[2])
        if (b.none { it == Int.MIN_VALUE }) savedB = Triple(b[0], b[1], b[2])
        showingA = prefs.getBoolean("showingA", false)
    }

    private fun saveABSettings() {
        val editor = getSharedPreferences("selectru_settings", MODE_PRIVATE).edit().putBoolean("showingA", showingA)
        if (savedA == null) editor.remove("aBass").remove("aMid").remove("aTreble")
        else savedA!!.let { editor.putInt("aBass", it.first).putInt("aMid", it.second).putInt("aTreble", it.third) }
        if (savedB == null) editor.remove("bBass").remove("bMid").remove("bTreble")
        else savedB!!.let { editor.putInt("bBass", it.first).putInt("bMid", it.second).putInt("bTreble", it.third) }
        editor.apply()
    }

    private fun updateABLabels() {
        if (!::root.isInitialized) return
        val a = savedA?.let { "A: " + it.first + "/" + it.second + "/" + it.third } ?: "A: 미저장"
        val b = savedB?.let { "B: " + it.first + "/" + it.second + "/" + it.third } ?: "B: 미저장"
        val existing = root.findViewWithTag<View>("ab_summary")
        if (existing != null) root.removeView(existing)
        val info = TextView(this).apply {
            tag = "ab_summary"
            text = "$a     $b"
            textSize = 12f
            setTextColor(Color.rgb(157, 190, 218))
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        val buttonIndex = root.indexOfChild(root.findViewWithTag<View>("ab_switch"))
        root.addView(info, if (buttonIndex >= 0) buttonIndex + 1 else root.childCount)
    }

    private fun startNoiseMeasurement() {
        if (measuring) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
            return
        }
        val rate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            micResult.text = "이 기기에서 마이크 측정을 시작할 수 없습니다."
            return
        }
        measuring = true
        val measurementStage = selectedVolumeStage
        micResult.text = "${measurementDurationSeconds}초 측정 중… 핑크 노이즈를 차량 스피커로 재생하고 측정이 끝날 때까지 기다려 주세요."
        val sessionDurationForUi = measurementDurationSeconds
        val measurementStartedAt = System.currentTimeMillis()
        val ticker = object : Runnable {
            override fun run() {
                if (!measuring || !::micResult.isInitialized) return
                val remaining = (sessionDurationForUi - ((System.currentTimeMillis() - measurementStartedAt) / 1000).toInt()).coerceAtLeast(0)
                micResult.text = "측정 중 · 남은 시간 ${remaining}초\\n차량은 안전하게 주차하고 측정음을 일정하게 유지하세요."
                if (remaining > 0) handler.postDelayed(this, 500)
            }
        }
        measurementUiTicker = ticker
        handler.post(ticker)
        micThread = Thread {
            var recorder: AudioRecord? = null
            try {
                recorder = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, 4096))
                if (recorder.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("마이크 초기화 실패")
                recorder.startRecording()
                val buffer = ShortArray(1024)
                var squares = 0.0
                var count = 0L
                var clippedSamples = 0L
                var bassEnergy = 0.0
                var midEnergy = 0.0
                var trebleEnergy = 0.0
                val bandEnergy = DoubleArray(8)
                var spectrumFrames = 0
                val sessionDuration = measurementDurationSeconds
                val endAt = System.currentTimeMillis() + sessionDuration * 1000L
                while (measuring && System.currentTimeMillis() < endAt) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        for (i in 0 until n) { val sample = buffer[i].toInt(); val v = sample.toDouble(); squares += v * v; if (kotlin.math.abs(sample) >= 32700) clippedSamples++ }
                        count += n
                        if (n == buffer.size) {
                            val bands = analyzeSpectrum(buffer, n, rate)
                            bassEnergy += bands[0]; midEnergy += bands[1]; trebleEnergy += bands[2]
                            for (band in 0 until 8) bandEnergy[band] += bands[band + 3]
                            spectrumFrames++
                        }
                    }
                }
                val rms = if (count > 0) sqrt(squares / count) else 0.0
                val dbfs = if (rms > 0) 20.0 * log10(rms / 32768.0) else -100.0
                val bassDb = 10.0 * log10(maxOf(1e-12, bassEnergy / maxOf(1, spectrumFrames)))
                val midDb = 10.0 * log10(maxOf(1e-12, midEnergy / maxOf(1, spectrumFrames)))
                val trebleDb = 10.0 * log10(maxOf(1e-12, trebleEnergy / maxOf(1, spectrumFrames)))
                val bassRec = ((midDb - bassDb) * 0.25).toInt().coerceIn(-4, 4)
                val trebleRec = ((midDb - trebleDb) * 0.25).toInt().coerceIn(-4, 4)
                val bandDb = bandEnergy.map { energy -> 10.0 * log10(maxOf(1e-12, energy / maxOf(1, spectrumFrames))) }
                val bandNames = listOf("20–60 Hz", "60–125 Hz", "125–250 Hz", "250–500 Hz", "500 Hz–1 kHz", "1–2 kHz", "2–4 kHz", "4–8 kHz")
                val maxBand = bandDb.indices.maxByOrNull { bandDb[it] } ?: 0
                val minBand = bandDb.indices.minByOrNull { bandDb[it] } ?: 0
                val bandSpread = bandDb[maxBand] - bandDb[minBand]
                val ruleDiagnosis = if (bandSpread < 3.0) {
                    "8개 대역 편차가 비교적 작습니다."
                } else {
                    "상대적으로 높은 대역: ${bandNames[maxBand]}, 낮은 대역: ${bandNames[minBand]} (편차 ${String.format(Locale.getDefault(), "%.1f", bandSpread)} dB). 큰 EQ 변화보다 작은 조정 후 재측정을 권장합니다."
                }
                val profile = VolumeProfile(bassDb, midDb, trebleDb, bandDb, bassRec, trebleRec, dbfs)
                val clipRatio = if (count > 0) clippedSamples.toDouble() / count else 0.0
                val qualityWarning = when {
                    rms < 80.0 -> "입력 신호가 너무 작습니다. 측정 소리와 입력 음량을 확인한 뒤 다시 측정하세요."
                    clipRatio > 0.005 -> "입력 왜곡 가능성이 있습니다. 음량을 낮추고 다시 측정하세요."
                    else -> "측정 레벨은 확인됐습니다. 스피커 경로, 실내 반사음, 마이크 위치에 영향을 받는 참고값입니다."
                }
                handler.post {
                    volumeProfiles[measurementStage] = profile
                    saveVolumeProfiles()
                    volumeStageLabel.text = "측정할 차량 볼륨 단계: $selectedVolumeStage / 10" + if (volumeProfiles.containsKey(selectedVolumeStage)) " · 측정 저장됨" else " · 미측정"
                    showVolumeProfile()
                    micResult.text = String.format(Locale.getDefault(), "볼륨 단계 %d/10 분석 완료 · 입력 %.1f dBFS\n저음 %.1f · 중음 %.1f · 고음 %.1f dB(상대값)\n8대역(20–60 / 60–125 / 125–250 / 250–500 / 500–1k / 1–2k / 2–4k / 4–8k Hz):\n%s\n규칙 기반 진단: %s\n초기 분석 추천: 저음 %+d, 중음 0, 고음 %+d\n%s", measurementStage, dbfs, bassDb, midDb, trebleDb, bandDb.mapIndexed { i, v -> String.format(Locale.getDefault(), "%s %.1f dB", listOf("20–60", "60–125", "125–250", "250–500", "500–1k", "1–2k", "2–4k", "4–8k")[i], v) }.joinToString("\n"), ruleDiagnosis, bassRec, trebleRec, qualityWarning)
                    renderSpectrum(bandDb)
                    carEqGuide.text = "스펙트럼 분석 기반 EQ 시작값\n저음 / Bass: " + String.format(Locale.getDefault(), "%+d 단계", bassRec) + "\n중음 / Mid: 기준에서 시작\n고음 / Treble: " + String.format(Locale.getDefault(), "%+d 단계", trebleRec) + "\n차량 EQ에 적용 후 다시 측정해 비교하세요."
                }
            } catch (e: Exception) {
                handler.post { micResult.text = "마이크 측정 실패: " + (e.message ?: "권한 또는 기기 문제") }
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                handler.post {
                    measurementUiTicker?.let { handler.removeCallbacks(it) }
                    measurementUiTicker = null
                    measuring = false
                }
            }
        }.also { it.start() }
    }

    private fun renderSpectrum(values: List<Double>) {
        if (!::spectrumView.isInitialized) return
        spectrumView.removeAllViews()
        spectrumView.addView(label("상대 대역 레벨 · 측정값 기준", 13f, Color.rgb(157, 190, 218)))
        val names = listOf("20–60", "60–125", "125–250", "250–500", "500–1k", "1–2k", "2–4k", "4–8k")
        val low = values.minOrNull() ?: 0.0
        val high = values.maxOrNull() ?: 1.0
        val range = (high - low).coerceAtLeast(0.1)
        values.forEachIndexed { index, value ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(this).apply {
                text = names[index] + " Hz"
                textSize = 11f
                setTextColor(Color.rgb(190, 211, 231))
            }, LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT))
            val track = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(Color.rgb(17, 43, 67))
                    cornerRadius = dp(5).toFloat()
                }
                clipToOutline = true
            }
            val ratio = ((value - low) / range).coerceIn(0.0, 1.0).toFloat()
            val visible = (0.12f + ratio * 0.88f).coerceIn(0.12f, 1f)
            track.addView(View(this).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.rgb(0, 119, 255), Color.rgb(0, 190, 238))
                ).apply { cornerRadius = dp(5).toFloat() }
            }, LinearLayout.LayoutParams(0, dp(12), visible))
            track.addView(View(this), LinearLayout.LayoutParams(0, dp(12), 1f - visible))
            row.addView(track, LinearLayout.LayoutParams(0, dp(12), 1f))
            row.addView(TextView(this).apply {
                text = String.format(Locale.getDefault(), "%.1f", value)
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(dp(44), LinearLayout.LayoutParams.WRAP_CONTENT))
            spectrumView.addView(row)
        }
    }

    private fun saveVolumeProfiles() {
        val prefs = getSharedPreferences("car_sound_measurements", MODE_PRIVATE)
        val editor = prefs.edit().clear()
        for ((stage, p) in volumeProfiles) {
            val values = mutableListOf(p.bassDb, p.midDb, p.trebleDb)
            values.addAll(p.bandDb)
            values.add(p.bassRec.toDouble())
            values.add(p.trebleRec.toDouble())
            values.add(p.inputDbfs)
            editor.putString("stage_$stage", values.joinToString(","))
        }
        editor.apply()
    }

    private fun loadVolumeProfiles() {
        val prefs = getSharedPreferences("car_sound_measurements", MODE_PRIVATE)
        for (stage in 1..10) {
            val raw = prefs.getString("stage_$stage", null) ?: continue
            try {
                val v = raw.split(",").map { it.toDouble() }
                if (v.size == 14) {
                    volumeProfiles[stage] = VolumeProfile(
                        v[0], v[1], v[2], v.subList(3, 11),
                        v[11].toInt(), v[12].toInt(), v[13]
                    )
                }
            } catch (_: Exception) {
                // Ignore a corrupted saved profile and allow a fresh measurement.
            }
        }
    }

    private fun showVolumeProfile() {
        if (!::volumeProfileResult.isInitialized) return
        val p = volumeProfiles[selectedVolumeStage]
        val saved = volumeProfiles.toSortedMap().entries.joinToString("\n") { (stage, v) ->
            String.format(Locale.getDefault(), "%d/10 단계 · 입력 %.1f dBFS · 추천 저음 %+d / 고음 %+d", stage, v.inputDbfs, v.bassRec, v.trebleRec)
        }
        volumeProfileResult.text = (if (p == null) "단계 $selectedVolumeStage/10: 아직 측정하지 않았습니다. 차량 볼륨을 이 단계로 직접 맞추고 같은 음악 구간을 측정하세요." else String.format(Locale.getDefault(), "단계 %d/10 저장됨\n스펙트럼 저음 %.1f · 중음 %.1f · 고음 %.1f dB\n8대역: %s\n시작 추천: 저음 %+d / 중음 0 / 고음 %+d", selectedVolumeStage, p.bassDb, p.midDb, p.trebleDb, p.bandDb.mapIndexed { i, v -> String.format(Locale.getDefault(), "%s %.1f", listOf("20–60", "60–125", "125–250", "250–500", "500–1k", "1–2k", "2–4k", "4–8k")[i], v) }.joinToString(" · "), p.bassRec, p.trebleRec)) + if (saved.isNotEmpty()) "\n\n저장된 단계\n$saved" else ""
    }

    private fun analyzeSpectrum(samples: ShortArray, count: Int, sampleRate: Int): DoubleArray {
        val n = 1024
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in 0 until minOf(n, count)) {
            val window = 0.5 - 0.5 * kotlin.math.cos(2.0 * PI * i / (n - 1))
            re[i] = samples[i].toDouble() * window / 32768.0
        }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val t = re[i]; re[i] = re[j]; re[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val c = kotlin.math.cos(angle)
            val si = kotlin.math.sin(angle)
            var base = 0
            while (base < n) {
                var wr = 1.0
                var wi = 0.0
                for (k in 0 until len / 2) {
                    val a = base + k
                    val b = a + len / 2
                    val tr = wr * re[b] - wi * im[b]
                    val ti = wr * im[b] + wi * re[b]
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val nw = wr * c - wi * si
                    wi = wr * si + wi * c; wr = nw
                }
                base += len
            }
            len = len shl 1
        }
        val edges = doubleArrayOf(20.0, 60.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        val sums = DoubleArray(8)
        val bins = IntArray(8)
        var bass = 0.0; var bassBins = 0
        var mid = 0.0; var midBins = 0
        var treble = 0.0; var trebleBins = 0
        for (bin in 1 until n / 2) {
            val hz = bin.toDouble() * sampleRate / n
            val power = re[bin] * re[bin] + im[bin] * im[bin]
            for (band in 0 until 8) {
                if (hz >= edges[band] && hz < edges[band + 1]) {
                    sums[band] += power
                    bins[band]++
                    break
                }
            }
            when {
                hz < 20.0 -> Unit
                hz < 250.0 -> { bass += power; bassBins++ }
                hz < 4000.0 -> { mid += power; midBins++ }
                hz < 8000.0 -> { treble += power; trebleBins++ }
            }
        }
        val result = DoubleArray(11)
        result[0] = bass / kotlin.math.ln(250.0 / 20.0)
        result[1] = mid / kotlin.math.ln(4000.0 / 250.0)
        result[2] = treble / kotlin.math.ln(8000.0 / 4000.0)
        for (band in 0 until 8) result[band + 3] = sums[band] / kotlin.math.ln(edges[band + 1] / edges[band])
        return result
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startNoiseMeasurement()
            else micResult.text = "마이크 권한이 없어 측정하지 않았습니다."
        }
    }

    private fun toggleTone() {
        if (playing) { stopTone(); return }
        try {
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer, sampleRate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            equalizer = Equalizer(0, audioTrack!!.audioSessionId).apply { enabled = true }
            applyEq()
            playing = true
            audioTrack!!.play()
            toneThread = Thread {
                val buffer = ShortArray(1024)
                var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0
                val random = java.util.Random()
                while (playing && !Thread.currentThread().isInterrupted) {
                    for (i in buffer.indices) {
                        val white = random.nextDouble() * 2.0 - 1.0
                        b0 = 0.99886 * b0 + white * 0.0555179
                        b1 = 0.99332 * b1 + white * 0.0750759
                        b2 = 0.96900 * b2 + white * 0.1538520
                        b3 = 0.86650 * b3 + white * 0.3104856
                        b4 = 0.55000 * b4 + white * 0.5329522
                        b5 = -0.7616 * b5 - white * 0.0168980
                        val pink = b0 + b1 + b2 + b3 + b4 + b5 + white * 0.5362
                        b5 = white * 0.115926
                        buffer[i] = (pink * 700.0).toInt().coerceIn(-12000, 12000).toShort()
                    }
                    audioTrack?.write(buffer, 0, buffer.size)
                }
            }.also { it.start() }
        } catch (e: Exception) {
            status.text = "테스트 소리를 시작하지 못했어요: ${e.message ?: "오디오 효과를 사용할 수 없음"}"
            stopTone()
        }
    }

    private fun applyEq() {
        val eq = equalizer ?: return
        try {
            if (eq.numberOfBands < 1) return
            val min = eq.bandLevelRange[0].toInt()
            val max = eq.bandLevelRange[1].toInt()
            for (i in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(i.toShort()) / 1000
                val stage = when {
                    hz < 250 -> low
                    hz < 3000 -> mid
                    else -> high
                }
                val level = (stage * 100).coerceIn(min, max).toShort()
                eq.setBandLevel(i.toShort(), level)
            }
        } catch (_: Exception) {}
    }

    private fun stopTone() {
        playing = false
        try { toneThread?.join(200) } catch (_: InterruptedException) {}
        toneThread = null
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null
    }

    override fun onDestroy() {
        measuring = false
        stopTone()
        super.onDestroy()
    }
}
