package com.carsoundai

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

class MainActivity : Activity() {
    private val sr = 44100
    private val hz = doubleArrayOf(31.0,62.0,125.0,250.0,500.0,1000.0,2000.0,4000.0,8000.0,16000.0)
    private val names = arrayOf("31 Hz","62 Hz","125 Hz","250 Hz","500 Hz","1 kHz","2 kHz","4 kHz","8 kHz","16 kHz")
    private val gains = DoubleArray(10)
    private val measured = doubleArrayOf(-7.4860,2.2840,21.0430,21.1674,13.1094,5.8765,-2.6106,-0.0682)
    private val prefs by lazy { getSharedPreferences("carsoundstudio", MODE_PRIVATE) }
    private lateinit var curve: CurveView
    private lateinit var values: Array<TextView>
    private lateinit var status: TextView
    private lateinit var limiterSwitch: Switch
    private lateinit var masterValue: TextView
    private var master = 0.75
    private var limiter = true
    @Volatile private var playing = false
    private var track: AudioTrack? = null
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        for (i in gains.indices) gains[i] = prefs.getFloat("eq_" + i, 0f).toDouble()
        master = prefs.getFloat("master", 0.75f).toDouble().coerceIn(0.05, 1.0)
        limiter = prefs.getBoolean("limiter", true)
        buildUi()
        refresh()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun txt(s: String, size: Float, color: Int = Color.rgb(226,237,248), bold: Boolean = false) =
        TextView(this).apply { text=s; textSize=size; setTextColor(color); if (bold) typeface=Typeface.create("sans-serif-medium",Typeface.BOLD); setPadding(0,dp(3),0,dp(4)) }
    private fun panel() = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; setPadding(dp(15),dp(13),dp(15),dp(13))
        background=GradientDrawable().apply { setColor(Color.rgb(16,28,45)); cornerRadius=dp(18).toFloat(); setStroke(dp(1),Color.rgb(39,64,88)) }
    }
    private fun button(s: String, action: () -> Unit) = Button(this).apply {
        text=s; isAllCaps=false; textSize=13f; setTextColor(Color.WHITE)
        background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.rgb(0,125,255),Color.rgb(0,190,205))).apply { cornerRadius=dp(13).toFloat() }
        setOnClickListener { action() }
    }
    private fun addPanel(root: LinearLayout, p: LinearLayout, bottom: Int=12) {
        root.addView(p,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(bottom) })
    }
    private fun buildUi() {
        window.statusBarColor=Color.rgb(6,13,24); window.navigationBarColor=Color.rgb(6,13,24)
        val scroll=ScrollView(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(15),dp(15),dp(15),dp(24)); background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(5,12,24),Color.rgb(10,30,48),Color.rgb(5,14,26))) }
        scroll.addView(root); setContentView(scroll)
        val hero=panel()
        hero.background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(12,49,83),Color.rgb(13,105,130))).apply { cornerRadius=dp(22).toFloat() }
        hero.addView(txt("SELECTRU  /  SOUND LAB",12f,Color.rgb(170,235,255),true))
        hero.addView(txt("10-BAND EQUALIZER",25f,Color.WHITE,true))
        hero.addView(txt("IONIQ 5 · 순정 오디오 측정 프로필 기반 DSP",13f,Color.rgb(225,247,255)))
        addPanel(root,hero)
        val play=panel()
        play.addView(txt("실시간 DSP 엔진",17f,Color.WHITE,true))
        status=txt("대기 중 · 앱 내부 테스트 오디오에 EQ 적용",12f,Color.rgb(140,211,232))
        play.addView(status)
        val buttons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        buttons.addView(button("▶ 테스트 노이즈") { if(playing) stopAudio() else startAudio() },LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(5) })
        buttons.addView(button("측정값 보정 적용") { applyMeasured(); refresh(); save(); status.text="아이오닉 5 측정 프로필 보정 적용됨" },LinearLayout.LayoutParams(0,dp(48),1f).apply { leftMargin=dp(5) })
        play.addView(buttons); addPanel(root,play)
        val graph=panel()
        graph.addView(txt("주파수 응답 / EQ CURVE",16f,Color.WHITE,true))
        graph.addView(txt("슬라이더 변경 즉시 곡선과 테스트 신호에 반영",12f,Color.rgb(158,181,204)))
        curve=CurveView(); graph.addView(curve,LinearLayout.LayoutParams(-1,dp(170))); addPanel(root,graph)
        val eq=panel()
        val head=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
        head.addView(txt("10밴드 이퀄라이저",17f,Color.WHITE,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(button("FLAT") { for(i in gains.indices) gains[i]=0.0; refresh(); save() })
        eq.addView(head); eq.addView(txt("대역별 게인 · -12 dB ~ +12 dB",12f,Color.rgb(158,181,204)))
        values=Array(10) { txt("",12f,Color.rgb(80,220,230),true) }
        for(i in gains.indices) {
            val row=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(3),0,dp(3)) }
            val labels=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
            labels.addView(txt(names[i],13f,Color.rgb(196,220,241),true),LinearLayout.LayoutParams(0,-2,1f))
            labels.addView(values[i]); row.addView(labels)
            val seek=SeekBar(this).apply {
                max=240; progress=((gains[i]+12.0)*10).toInt().coerceIn(0,240)
                setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) {
                        gains[i]=p/10.0-12.0
                        values[i].text=String.format(Locale.US,"%+.1f dB",gains[i])
                        curve.invalidate()
                        if(fromUser) save()
                    }
                    override fun onStartTrackingTouch(b: SeekBar?) {}
                    override fun onStopTrackingTouch(b: SeekBar?) {}
                })
            }
            row.addView(seek); eq.addView(row)
        }
        addPanel(root,eq)
        val dyn=panel()
        dyn.addView(txt("출력 보호 / DYNAMICS",16f,Color.WHITE,true))
        limiterSwitch=Switch(this).apply { text="피크 리미터 활성화"; setTextColor(Color.rgb(215,229,244)); isChecked=limiter; setOnCheckedChangeListener { _,v -> limiter=v; save() } }
        dyn.addView(limiterSwitch)
        masterValue=txt("",13f,Color.rgb(80,220,230),true); dyn.addView(masterValue)
        dyn.addView(SeekBar(this).apply {
            max=100; progress=(master*100).toInt()
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, p: Int, fromUser: Boolean) { master=(p/100.0).coerceIn(0.05,1.0); masterValue.text="출력 레벨 " + (master*100).toInt() + "%"; if(fromUser) save() }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        })
        dyn.addView(txt("리미터는 디지털 클리핑을 줄이는 보호 기능입니다.",12f,Color.rgb(158,181,204))); addPanel(root,dyn)
        val profile=panel()
        profile.addView(txt("차량 측정 프로필",16f,Color.WHITE,true))
        profile.addView(txt("현대 아이오닉 5 · 순정 오디오 · 중저음 강조",13f,Color.rgb(80,220,230),true))
        profile.addView(txt("8개 측정 대역 (dB): " + measured.joinToString("  ") { String.format(Locale.US,"%.1f",it) },12f,Color.rgb(196,220,241)))
        profile.addView(txt("입력 레벨 -29.96 dBFS · 측정 단계 5",12f,Color.rgb(158,181,204)))
        profile.addView(button("중저음 프리셋") {
            val p=doubleArrayOf(2.5,2.0,1.5,0.5,0.0,0.0,0.5,1.0,1.0,0.5)
            for(i in gains.indices) gains[i]=p[i]
            refresh(); save(); status.text="중저음 프리셋 적용됨"
        })
        profile.addView(txt("측정값은 상대 응답으로 취급해 평균 대비 편차를 완만하게 보정합니다. 마이크 보정 정보가 없으므로 절대 음압 보정값으로 간주하지 않습니다.",12f,Color.rgb(158,181,204)))
        addPanel(root,profile)
        root.addView(txt("중요: Android 일반 앱은 다른 앱의 소리를 임의로 가로채 EQ를 적용할 수 없습니다. 본 DSP는 앱 내부 테스트 오디오에 적용됩니다.",11f,Color.rgb(123,151,176)))
    }
    private fun refresh() {
        if(::values.isInitialized) for(i in gains.indices) values[i].text=String.format(Locale.US,"%+.1f dB",gains[i])
        if(::curve.isInitialized) curve.invalidate()
        if(::masterValue.isInitialized) masterValue.text="출력 레벨 " + (master*100).toInt() + "%"
    }
    private fun save() {
        val e=prefs.edit().putFloat("master",master.toFloat()).putBoolean("limiter",limiter)
        for(i in gains.indices) e.putFloat("eq_"+i,gains[i].toFloat())
        e.apply()
    }
    private fun applyMeasured() {
        val mean=measured.average()
        val corr=measured.map { ((mean-it)*0.25).coerceIn(-4.0,4.0) }
        for(i in gains.indices) {
            val x=i*(corr.size-1).toDouble()/(gains.size-1)
            val lo=x.toInt().coerceIn(0,corr.size-1); val hi=(lo+1).coerceAtMost(corr.size-1); val t=x-lo
            gains[i]=(corr[lo]*(1.0-t)+corr[hi]*t).coerceIn(-4.0,4.0)
        }
    }
    private data class Biquad(val b0:Double,val b1:Double,val b2:Double,val a1:Double,val a2:Double)
    private fun peaking(fc:Double,g:Double):Biquad {
        val a=10.0.pow(g/40.0); val w=2.0*PI*fc/sr; val c=cos(w); val alpha=sin(w)/2.0
        val a0=1.0+alpha/a
        return Biquad((1.0+alpha*a)/a0,(-2.0*c)/a0,(1.0-alpha*a)/a0,(-2.0*c)/a0,(1.0-alpha/a)/a0)
    }
    private fun startAudio() {
        if(playing) return
        try {
            val min=AudioTrack.getMinBufferSize(sr,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
            track=AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min,8192)).setTransferMode(AudioTrack.MODE_STREAM).build()
            playing=true; track?.play(); status.text="재생 중 · 10밴드 biquad DSP 실시간 적용"
            worker=Thread {
                val random=Random(); val buf=ShortArray(512); val z1=DoubleArray(10); val z2=DoubleArray(10)
                var state=0.0; var limiterGain=1.0
                while(playing && !Thread.currentThread().isInterrupted) {
                    val filters=Array(10) { i -> peaking(hz[i].coerceAtMost(sr*0.45),gains[i]) }
                    for(n in buf.indices) {
                        val white=random.nextDouble()*2.0-1.0
                        state=0.985*state+0.015*white
                        var sample=(white*0.45+state*0.55)*0.72
                        for(i in filters.indices) {
                            val f=filters[i]; val y=f.b0*sample+z1[i]
                            z1[i]=f.b1*sample-f.a1*y+z2[i]; z2[i]=f.b2*sample-f.a2*y; sample=y
                        }
                        sample*=master
                        if(limiter) {
                            val peak=kotlin.math.abs(sample); val target=if(peak>0.82) 0.82/peak else 1.0
                            limiterGain=if(target<limiterGain) target else minOf(1.0,limiterGain+0.003)
                            sample*=limiterGain
                        }
                        buf[n]=(sample*32767.0).toInt().coerceIn(-32767,32767).toShort()
                    }
                    val t=track ?: break
                    if(t.write(buf,0,buf.size,AudioTrack.WRITE_BLOCKING)<0) break
                }
            }.apply { name="Selectru-DSP"; start() }
        } catch(e:Exception) {
            status.text="오디오 시작 실패: " + (e.message ?: "기기 오류")
            stopAudio()
        }
    }
    private fun stopAudio() {
        playing=false
        try { worker?.join(300) } catch(_:InterruptedException) {}
        worker=null
        try { track?.pause(); track?.flush(); track?.stop(); track?.release() } catch(_:Exception) {}
        track=null
        if(::status.isInitialized) status.text="정지 · 설정 자동 저장됨"
    }
    override fun onPause() { if(playing) stopAudio(); super.onPause() }
    override fun onDestroy() { stopAudio(); super.onDestroy() }

    private inner class CurveView:View(this@MainActivity) {
        private val grid=Paint(3).apply { color=Color.rgb(42,65,87); strokeWidth=dp(1).toFloat() }
        private val line=Paint(3).apply { color=Color.rgb(0,211,225); strokeWidth=dp(3).toFloat(); style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        private val fill=Paint(3).apply { color=Color.argb(35,0,211,225); style=Paint.Style.FILL }
        private val labels=Paint(3).apply { color=Color.rgb(145,172,196); textSize=dp(10).toFloat() }
        override fun onDraw(c:Canvas) {
            super.onDraw(c)
            val l=dp(30).toFloat(); val r=width-dp(8).toFloat(); val t=dp(10).toFloat(); val b=height-dp(24).toFloat()
            for(j in 0..4) {
                val y=t+(b-t)*j/4f; c.drawLine(l,y,r,y,grid)
                if(j==0) c.drawText("+12",0f,y+dp(4),labels)
                if(j==2) c.drawText("0",dp(12).toFloat(),y+dp(4),labels)
                if(j==4) c.drawText("-12",0f,y+dp(4),labels)
            }
            val p=Path()
            for(i in gains.indices) {
                val x=l+(r-l)*i/(gains.size-1); val y=b-((gains[i]+12.0)/24.0).toFloat()*(b-t)
                if(i==0) p.moveTo(x,y) else p.lineTo(x,y)
            }
            val area=Path(p).apply { lineTo(r,b); lineTo(l,b); close() }
            c.drawPath(area,fill); c.drawPath(p,line)
            for(i in gains.indices) {
                val x=l+(r-l)*i/(gains.size-1); val y=b-((gains[i]+12.0)/24.0).toFloat()*(b-t)
                c.drawCircle(x,y,dp(3).toFloat(),line)
                c.drawText(arrayOf("31","62","125","250","500","1k","2k","4k","8k","16k")[i],x-dp(7),height-dp(5).toFloat(),labels)
            }
        }
    }
}
