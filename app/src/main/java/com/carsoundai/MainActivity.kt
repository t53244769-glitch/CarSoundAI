package com.carsoundai

import android.Manifest
import android.content.pm.PackageManager
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
    private lateinit var carEqGuide: TextView
    private lateinit var volumeStageLabel: TextView
    private lateinit var volumeProfileResult: TextView
    @Volatile private var measuring = false
    private var micThread: Thread? = null
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
        buildUi()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 16f, color: Int = Color.rgb(30, 37, 48)): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(dp(2), dp(6), dp(2), dp(6))
        }
    }

    private fun button(text: String, action: () -> Unit): Button =
        Button(this).apply { this.text = text; setOnClickListener { action() } }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(Color.rgb(246, 248, 251))
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
        val mark = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(255, 255, 255))
            }
        }
        listOf(12, 24, 34, 20, 10).forEachIndexed { index, height ->
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(3).toFloat()
                    setColor(if (index == 2) Color.rgb(37, 177, 220) else Color.rgb(35, 100, 168))
                }
            }
            mark.addView(bar, LinearLayout.LayoutParams(dp(4), dp(height)).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        hero.addView(mark, LinearLayout.LayoutParams(dp(64), dp(64)))
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        heroText.addView(TextView(this).apply {
            text = "CarSoundAI"
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
            text = "차량 사운드 측정과 튜닝"
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
        listOf("01  듣기", "02  측정", "03  조정").forEachIndexed { i, title ->
            val chip = TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) Color.WHITE else Color.rgb(35, 83, 125))
                setPadding(dp(8), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(if (i == 0) Color.rgb(25, 103, 168) else Color.rgb(226, 237, 247))
                }
            }
            quickSummary.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
        root.addView(quickSummary)
        root.addView(sectionHeader("5단계 사운드 가이드", "처음이라면 순서대로 따라 해 보세요."))
        status = label(steps[0], 17f)
        root.addView(status)
        root.addView(label(guidance[0]))
        root.addView(sectionHeader("빠른 프리셋", "원하는 소리 성향을 한 번에 선택"))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        presets.addView(button("저음 강조") { setEq(4, 0, 1) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        presets.addView(button("보컬") { setEq(-1, 3, 0) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        presets.addView(button("균형") { setEq(0, 0, 0) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(presets)

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
            status.text = "A 설정 저장됨"
        })
        root.addView(button("현재 설정을 B로 저장") {
            savedB = Triple(low, mid, high)
            showingA = false
            status.text = "B 설정 저장됨"
        })
        root.addView(button("A ↔ B 전환") { switchAB() })
        root.addView(label("A와 B를 각각 저장한 뒤 전환하며 비교하세요. 이 비교는 앱의 테스트 톤에 적용됩니다.", 14f))

        root.addView(sectionHeader("볼륨별 측정", "단계별 기록을 저장해 차이를 확인"))
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
        root.addView(button("현재 볼륨 단계 측정 시작") { startNoiseMeasurement() })
        volumeProfileResult = label("각 단계에서 같은 음악 구간을 측정하면 볼륨별 차이를 비교할 수 있습니다.", 14f)
        root.addView(volumeProfileResult)
        root.addView(button("볼륨별 측정 기록 초기화") { volumeProfiles.clear(); saveVolumeProfiles(); showVolumeProfile() })

        root.addView(sectionHeader("주파수 진단", "8개 대역 분석과 보정 시작값"))
        micResult = label("분석 전 · 안전하게 주차한 뒤 측정용 핑크 노이즈를 차량 스피커로 재생하세요. 측정 시간은 20초입니다.")
        root.addView(micResult)
        root.addView(button("선택한 볼륨 단계에서 20초 분석") { startNoiseMeasurement() })
        root.addView(label("핑크 노이즈 입력을 8개 대역으로 분석하고 로그 주파수 폭을 보정한 상대 레벨과 보정 시작값을 표시합니다. 현재 진단은 규칙 기반 프로토타입이며 학습된 AI 모델이나 보정 마이크 측정은 아닙니다.", 14f))

        root.addView(button("측정용 핑크 노이즈 재생 / 정지") { toggleTone() })
        root.addView(button("이전 단계") { step = (step - 1).coerceAtLeast(0); updateStep() })
        root.addView(button("다음 단계") { step = (step + 1).coerceAtMost(4); updateStep() })
        root.addView(button("현재 설정 초기화") { setEq(0, 0, 0) })
        root.addView(sectionHeader("사용 안내", "정확도와 안전을 위한 참고 사항"))
        root.addView(label("핑크 노이즈는 측정 자극음으로 사용합니다. 이 앱은 자체 테스트 소리와 이 앱에 연결된 이퀄라이저를 조절합니다. Android의 제한으로 다른 음악 앱이나 차량 블루투스에서 재생 중인 모든 음악을 직접 변경하지는 못합니다. 차량 오디오의 EQ는 차량/오디오 시스템에서 조절하세요.", 14f))
        root.addView(label("안전을 위해 주행 중에는 조작하지 마세요.", 14f, Color.rgb(170, 45, 35)))
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.rgb(231, 240, 249))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(211, 226, 240))
            }
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 18f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setTextColor(Color.rgb(19, 57, 93))
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(76, 103, 128))
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
        lowValue.text = dbLabel(low); midValue.text = dbLabel(mid); highValue.text = dbLabel(high)
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
        status.text = if (showingA) "A 설정 적용 중" else "B 설정 적용 중"
    }

    private fun updateStep() {
        status.text = steps[step]
        val texts = root.findViewsWithText(ArrayList<View>(), guidance[0], View.FIND_VIEWS_WITH_TEXT)
        val all = ArrayList<View>()
        root.findViewsWithText(all, guidance[0], View.FIND_VIEWS_WITH_TEXT)
        val guidanceView = root.getChildAt(4) as? TextView
        guidanceView?.text = guidance[step]
        if (step == 4) status.append(" · 완료")
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
        micResult.text = "20초 측정 중… 핑크 노이즈를 차량 스피커로 재생하고 측정이 끝날 때까지 기다려 주세요."
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
                val endAt = System.currentTimeMillis() + 20000
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
                    carEqGuide.text = "스펙트럼 분석 기반 EQ 시작값\n저음 / Bass: " + String.format(Locale.getDefault(), "%+d 단계", bassRec) + "\n중음 / Mid: 기준에서 시작\n고음 / Treble: " + String.format(Locale.getDefault(), "%+d 단계", trebleRec) + "\n차량 EQ에 적용 후 다시 측정해 비교하세요."
                }
            } catch (e: Exception) {
                handler.post { micResult.text = "마이크 측정 실패: " + (e.message ?: "권한 또는 기기 문제") }
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                measuring = false
            }
        }.also { it.start() }
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
