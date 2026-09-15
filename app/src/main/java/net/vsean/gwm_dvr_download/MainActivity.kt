package net.vsean.gwm_dvr_download

import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MaterialR

class MainActivity : AppCompatActivity() {

    private enum class StepState { PENDING, ACTIVE, DONE, ERROR }

    private class StepView(val badge: TextView, val label: TextView)

    private lateinit var btnScan: MaterialButton
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSsid: TextView
    private lateinit var tvFileName: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvLogToggle: TextView
    private lateinit var progress: LinearProgressIndicator
    private val steps = ArrayList<StepView>()
    private var currentStep = -1

    private lateinit var wifi: WifiConnector
    private var job: Job? = null
    @Volatile
    private var client: DashcamClient? = null

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let { handleQrText(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // targetSdk 35+ 强制 edge-to-edge，给状态栏/导航栏留出空间
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        btnScan = findViewById(R.id.btnScan)
        tvStatusTitle = findViewById(R.id.tvStatusTitle)
        tvStatus = findViewById(R.id.tvStatus)
        tvSsid = findViewById(R.id.tvSsid)
        tvFileName = findViewById(R.id.tvFileName)
        tvProgress = findViewById(R.id.tvProgress)
        tvLog = findViewById(R.id.tvLog)
        tvLogToggle = findViewById(R.id.tvLogToggle)
        progress = findViewById(R.id.progress)

        findViewById<TextView>(R.id.tvVersion).apply {
            text = "版本 ${packageManager.getPackageInfo(packageName, 0).versionName}"
            // 连续点击 5 次（每次间隔不超过 1 秒）才显示
            var taps = 0
            var lastTap = 0L
            setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                taps = if (now - lastTap <= 1000) taps + 1 else 1
                lastTap = now
                if (taps >= 5) {
                    taps = 0
                    showWechatQr()
                }
            }
        }

        buildSteps(findViewById(R.id.steps))
        wifi = WifiConnector(applicationContext)

        btnScan.setOnClickListener {
            if (job?.isActive == true) stop()
            else scanLauncher.launch(Intent(this, ScanActivity::class.java))
        }
        findViewById<View>(R.id.logHeader).setOnClickListener {
            val show = tvLog.visibility != View.VISIBLE
            tvLog.visibility = if (show) View.VISIBLE else View.GONE
            tvLogToggle.text = if (show) "收起" else "展开"
        }
    }

    override fun onDestroy() {
        client?.close()
        wifi.release()
        super.onDestroy()
    }

    private fun handleQrText(text: String) {
        if (job?.isActive == true) return
        val cfg = try {
            QrParser.parse(text)
        } catch (e: Exception) {
            toast("二维码无法识别：${e.message}")
            return
        }
        start(cfg)
    }

    private fun start(cfg: HotspotConfig) {
        tvLog.text = ""
        tvFileName.visibility = View.GONE
        tvProgress.visibility = View.GONE
        resetSteps()
        setStep(0, StepState.DONE)
        steps[1].label.text = "连接热点「${cfg.ssid}」"

        job = lifecycleScope.launch {
            setRunning(true)
            try {
                setStep(1, StepState.ACTIVE)
                showStatus("正在连接热点", "请在系统弹窗中点击「连接」")
                tvSsid.text = cfg.ssid
                tvSsid.visibility = View.VISIBLE
                showIndeterminate()
                log("连接热点 ${cfg.ssid}")
                val network = wifi.connect(cfg.ssid, cfg.password)
                log("热点已连接")

                setStep(1, StepState.DONE)
                setStep(2, StepState.ACTIVE)
                showStatus("等待车机确认", "正在连接行车记录仪 …")

                val c = DashcamClient(network.socketFactory, cfg.host, cfg.port, DeviceName.value, events, MediaStoreSink(applicationContext))
                client = c
                val result = withContext(Dispatchers.IO) { c.run() }

                progress.visibility = View.GONE
                tvSsid.visibility = View.GONE
                tvFileName.visibility = View.GONE
                tvProgress.visibility = View.GONE
                when {
                    result.total == 0 -> {
                        setStep(currentStep, StepState.ERROR)
                        showStatus("没有文件", "车机没有发送任何文件，请先在车机上选择要导出的视频")
                    }
                    result.succeeded == result.total -> {
                        setStep(3, StepState.DONE)
                        showStatus("下载完成", "${result.total} 个文件已保存到相册（Movies/CarVideo）")
                    }
                    else -> {
                        setStep(3, StepState.ERROR)
                        showStatus("部分完成", "成功 ${result.succeeded} 个，失败 ${result.total - result.succeeded} 个，详见日志")
                    }
                }
            } catch (e: CancellationException) {
                setStep(currentStep, StepState.PENDING)
                showStatus("已取消", "可以重新扫码开始下载")
                throw e
            } catch (e: Exception) {
                setStep(currentStep, StepState.ERROR)
                showStatus("出错了", e.message ?: e.javaClass.simpleName)
                log("${e.javaClass.simpleName}: ${e.message}")
            } finally {
                client = null
                wifi.release()
                progress.visibility = View.GONE
                tvSsid.visibility = View.GONE
                setRunning(false)
            }
        }
    }

    private fun stop() {
        client?.close()
        job?.cancel()
    }

    private fun setRunning(running: Boolean) {
        if (running) {
            btnScan.text = "停止"
            btnScan.setIconResource(R.drawable.ic_stop)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            btnScan.text = "扫码连接并下载"
            btnScan.setIconResource(R.drawable.ic_qr_scan)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val events = object : DashcamEvents {
        private var fileStartTime = 0L
        private var currentIndex = -1

        override fun onStatus(text: String) = runOnUiThread { tvStatus.text = text }

        override fun onLog(text: String) = runOnUiThread { log(text) }

        override fun onFileList(files: List<RemoteFile>) = runOnUiThread {
            val total = files.sumOf { it.size }
            log("车机发送 ${files.size} 个文件，共 ${DashcamClient.formatSize(total)}")
            files.forEach { log("  · ${it.displayName}  ${DashcamClient.formatSize(it.size)}") }
            if (files.isNotEmpty()) {
                setStep(2, StepState.DONE)
                setStep(3, StepState.ACTIVE)
                showStatus("正在下载", "共 ${files.size} 个文件 · ${DashcamClient.formatSize(total)}")
                tvSsid.visibility = View.GONE
            }
            currentIndex = -1
        }

        override fun onProgress(index: Int, count: Int, file: RemoteFile, received: Long, total: Long) {
            if (index != currentIndex) {
                currentIndex = index
                fileStartTime = System.currentTimeMillis()
            }
            val elapsed = (System.currentTimeMillis() - fileStartTime).coerceAtLeast(1)
            val speed = received * 1000 / elapsed
            runOnUiThread {
                showDeterminate(if (total > 0) (received * 1000 / total).toInt() else 1000)
                tvFileName.visibility = View.VISIBLE
                tvProgress.visibility = View.VISIBLE
                tvFileName.text = if (count > 1) "(${index + 1}/$count) ${file.displayName}" else file.displayName
                tvProgress.text = "${DashcamClient.formatSize(received)} / ${DashcamClient.formatSize(total)} · " +
                    "${DashcamClient.formatSize(speed)}/s"
            }
        }
    }

    // ---------- 状态卡片 ----------

    private fun showStatus(title: String, detail: String) {
        tvStatusTitle.text = title
        tvStatus.text = detail
    }

    private fun showIndeterminate() {
        if (progress.visibility == View.VISIBLE && progress.isIndeterminate) return
        progress.visibility = View.INVISIBLE
        progress.isIndeterminate = true
        progress.visibility = View.VISIBLE
    }

    private fun showDeterminate(value: Int) {
        if (progress.visibility != View.VISIBLE || progress.isIndeterminate) {
            progress.visibility = View.INVISIBLE
            progress.isIndeterminate = false
            progress.visibility = View.VISIBLE
        }
        progress.setProgressCompat(value, true)
    }

    // ---------- 步骤列表 ----------

    private fun buildSteps(container: LinearLayout) {
        val labels = listOf("扫描车机二维码", "连接车机热点", "在车机上确认发送", "下载到相册")
        val density = resources.displayMetrics.density
        labels.forEach { text ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }
            val badge = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams((28 * density).toInt(), (28 * density).toInt())
            }
            val label = TextView(this).apply {
                this.text = text
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (14 * density).toInt()
                }
            }
            row.addView(badge)
            row.addView(label)
            container.addView(row)
            steps.add(StepView(badge, label))
        }
        resetSteps()
    }

    private fun resetSteps() {
        steps[1].label.text = "连接车机热点"
        steps.indices.forEach { setStep(it, StepState.PENDING) }
        currentStep = -1
    }

    private fun setStep(index: Int, state: StepState) {
        if (index !in steps.indices) return
        val step = steps[index]
        val primary = color(MaterialR.attr.colorPrimary)
        val onPrimary = color(MaterialR.attr.colorOnPrimary)
        val error = color(MaterialR.attr.colorError)
        val onError = color(MaterialR.attr.colorOnError)
        val outline = color(MaterialR.attr.colorOutline)
        val onSurface = color(MaterialR.attr.colorOnSurface)
        val muted = color(MaterialR.attr.colorOnSurfaceVariant)
        val density = resources.displayMetrics.density

        val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        when (state) {
            StepState.PENDING -> {
                bg.setColor(0)
                bg.setStroke((1.5f * density).toInt(), outline)
                step.badge.text = (index + 1).toString()
                step.badge.setTextColor(muted)
                step.label.setTextColor(muted)
                step.label.typeface = Typeface.DEFAULT
            }
            StepState.ACTIVE -> {
                bg.setColor(primary)
                step.badge.text = (index + 1).toString()
                step.badge.setTextColor(onPrimary)
                step.label.setTextColor(onSurface)
                step.label.typeface = Typeface.DEFAULT_BOLD
                currentStep = index
            }
            StepState.DONE -> {
                bg.setColor(primary)
                step.badge.text = "✓"
                step.badge.setTextColor(onPrimary)
                step.label.setTextColor(onSurface)
                step.label.typeface = Typeface.DEFAULT
            }
            StepState.ERROR -> {
                bg.setColor(error)
                step.badge.text = "!"
                step.badge.setTextColor(onError)
                step.label.setTextColor(error)
                step.label.typeface = Typeface.DEFAULT_BOLD
            }
        }
        step.badge.background = bg
    }

    private fun color(attr: Int): Int = MaterialColors.getColor(tvStatus, attr)

    private fun log(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        tvLog.append("[$time] $text\n")
    }

    private fun showWechatQr() {
        val density = resources.displayMetrics.density
        val image = ImageView(this).apply {
            setImageResource(R.drawable.wechat_qr)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "作者微信二维码"
            // 名片是白底，裁成圆角在深色模式下更协调
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 16 * density)
                }
            }
        }
        val container = FrameLayout(this).apply {
            val pad = (24 * density).toInt()
            setPadding(pad, pad, pad, 0)
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setView(container)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}
