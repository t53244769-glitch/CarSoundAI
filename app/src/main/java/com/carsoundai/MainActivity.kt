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

        root.addView(label("CarSoundAI", 30f, Color.rgb(20, 43, 75)))
        root.addView(label("초보자도 따라 하는 차량 사운드 튜닝", 16f))
        root.addView(label("5단계 사운드 가이드", 20f, Color.rgb(20, 43, 75)))
        status = label(steps[0], 17f)
        root.addView(status)
        root.addView(label(guidance[0]))
        root.addView(label("간편 프리셋", 18f))
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

        root.addView(label("차량 EQ에 직접 적용", 18f, Color.rgb(20, 43, 75)))
        carEqGuide = label("")
        root.addView(carEqGuide)
        root.addView(label("위 추천은 조정 방향과 강도 참고용입니다. 차량 EQ의 밴드 수와 범위가 다르므로 숫자를 그대로 복사하지 말고, 차량 EQ에서 가장 가까운 대역을 비슷한 방향으로 조금씩 조절하세요. 먼저 차량 EQ를 0으로 맞춘 뒤 적용하고, 주행 중에는 조작하지 마세요.", 14f))
        updateCarEqGuide()

        root.addView(label("A/B 설정 비교", 18f, Color.rgb(20, 43, 75)))
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

        root.addView(label("실내 소음 간이 측정", 18f, Color.rgb(20, 43, 75)))
        micResult = label("측정 전 · 차량을 안전하게 주차한 뒤 측정하세요.")
        root.addView(micResult)
        root.addView(button("주변 소음 5초 측정") { startNoiseMeasurement() })
        root.addView(label("마이크 측정은 주변 소리 크기만 상대적으로 보여줍니다. 보정된 dB SPL이나 주파수 응답 측정은 아닙니다.", 14f))

        root.addView(button("테스트 소리 재생 / 정지") { toggleTone() })
        root.addView(button("이전 단계") { step = (step - 1).coerceAtLeast(0); updateStep() })
        root.addView(button("다음 단계") { step = (step + 1).coerceAtMost(4); updateStep() })
        root.addView(button("현재 설정 초기화") { setEq(0, 0, 0) })
        root.addView(label("안내", 18f, Color.rgb(20, 43, 75)))
        root.addView(label("이 앱은 자체 테스트 톤과 이 앱에 연결된 이퀄라이저를 조절합니다. Android의 제한으로 다른 음악 앱이나 차량 블루투스에서 재생 중인 모든 음악을 직접 변경하지는 못합니다. 차량 오디오의 EQ는 차량/오디오 시스템에서 조절하세요.", 14f))
        root.addView(label("안전을 위해 주행 중에는 조작하지 마세요.", 14f, Color.rgb(170, 45, 35)))
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
        micResult.text = "측정 중… 주변에서 말하지 말고 5초 기다려 주세요."
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
                val endAt = System.currentTimeMillis() + 5000
                while (measuring && System.currentTimeMillis() < endAt) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        for (i in 0 until n) { val v = buffer[i].toDouble(); squares += v * v }
                        count += n
                    }
                }
                val rms = if (count > 0) sqrt(squares / count) else 0.0
                val dbfs = if (rms > 0) 20.0 * log10(rms / 32768.0) else -100.0
                handler.post {
                    micResult.text = "측정 완료 · 입력 레벨 " + String.format(Locale.getDefault(), "%.1f dBFS", dbfs) +
                        "\n상대 비교용 수치이며 보정된 소음계 값은 아닙니다."
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
                var phase = 0.0
                while (playing && !Thread.currentThread().isInterrupted) {
                    for (i in buffer.indices) {
                        buffer[i] = (sin(phase) * 5000).toInt().toShort()
                        phase += 2.0 * PI * 440.0 / sampleRate
                        if (phase >= 2.0 * PI) phase -= 2.0 * PI
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
