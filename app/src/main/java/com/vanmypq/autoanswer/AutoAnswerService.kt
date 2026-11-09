package com.vanmypq.autoanswer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.abs

class AutoAnswerService : AccessibilityService() {
    data class Item(
        val text: String,
        val rect: Rect,
        val node: AccessibilityNodeInfo?,
        val ctrl: Boolean = false,
        val checked: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var button: TextView? = null
    private var auto = false
    private var busy = false
    private var lastHash = 0
    private var currentHash = 0
    private var layoutHash = 0
    private var retries = 0
    private var cooldownUntil = 0L
    private var status = ""
    private var warnedNoOcr = false
    private var lastSent = ""
    private val answerCache = HashMap<Int, List<Pair<String, Int>>>()
    private var tapping = false

    private val idleTicker = object : Runnable {
        override fun run() {
            if (!auto) refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!auto) return
            tick()
            handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val tv = TextView(this).apply {
            setPadding(36, 24, 36, 24)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 3
            maxWidth = 700
            setOnClickListener { toggle() }
            setOnLongClickListener {
                val cm = this@AutoAnswerService.getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ai-input", ClaudeClient.lastInfo + "\n\n" + lastSent))
                toast("Đã sao chép dữ liệu app đọc được (dán vào chat để xem)")
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 300 }
        wm.addView(tv, lp)
        button = tv
        refresh()
        handler.post(idleTicker)
    }

    private fun toggle() {
        auto = !auto
        lastHash = 0
        currentHash = 0
        layoutHash = 0
        retries = 0
        cooldownUntil = 0L
        tapping = false
        status = if (auto) "đang quét" else ""
        refresh()
        handler.removeCallbacks(loop)
        if (auto) handler.post(loop)
    }

    private fun refresh() {
        button?.apply {
            text = if (auto) "■ AI: $status"
                   else if (CaptureService.isRunning()) "▶ AI 📷" else "▶ AI ⚠ chưa bật chụp màn hình"
            setBackgroundColor(if (auto) Color.parseColor("#CC2E7D32") else Color.parseColor("#CC424242"))
        }
    }

    private fun setStatus(s: String) {
        handler.post { status = s; refresh() }
    }

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    private fun tick() {
        if (busy || tapping || SystemClock.uptimeMillis() < cooldownUntil) return
        val key = getSharedPreferences("cfg", MODE_PRIVATE).getString("key", "") ?: ""
        if (key.isBlank()) {
            toast("Chưa nhập API key"); auto = false; refresh(); return
        }
        val items = collect()
        if (items.sumOf { it.text.length } >= 40) {
            process(items, key)
        } else if (Build.VERSION.SDK_INT >= 30) {
            ocrThenProcess(key)
        } else if (!warnedNoOcr) {
            warnedNoOcr = true
            setStatus("không đọc được chữ trên màn hình này")
        }
    }

    // Gom toàn bộ chữ của một nút và các nút con (dùng cho công thức toán bị ẩn khỏi màn hình).
    private fun gather(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 12) return ""
        val sb = StringBuilder()
        val t = (n.text ?: n.contentDescription)?.toString()
        if (!t.isNullOrEmpty()) sb.append(t)
        for (i in 0 until n.childCount) n.getChild(i)?.let { sb.append(gather(it, depth + 1)) }
        return sb.toString()
    }

    private fun isChoiceNode(n: AccessibilityNodeInfo): Boolean {
        val cn = n.className?.toString() ?: ""
        return n.isCheckable || cn.contains("RadioButton") || cn.contains("CheckBox") || cn.contains("CheckedTextView")
    }

    // Nút tròn/ô vuông tự vẽ (ImageView bấm được, không có chữ) dùng làm ô tích chọn.
    private fun looksLikeIconButton(n: AccessibilityNodeInfo, r: Rect): Boolean {
        if (!n.isClickable || !n.isEnabled || n.childCount != 0) return false
        val d = resources.displayMetrics.density
        val w = r.width()
        val h = r.height()
        if (w < 18 * d || h < 18 * d || w > 90 * d || h > 90 * d) return false
        return abs(w - h) <= 0.35f * maxOf(w, h)
    }

    // Ô tích chọn không có chữ: gắn nhãn bằng chữ cùng hàng (ưu tiên bên phải) để AI biết ô nào ứng với đáp án nào.
    private fun labelControls(list: ArrayList<Item>): List<Item> {
        var k = 0
        for (i in list.indices) {
            val c = list[i]
            if (!c.ctrl || c.text.isNotEmpty()) continue
            var best: Item? = null
            var bestGap = Int.MAX_VALUE
            for (o in list) {
                if (o === c || o.ctrl || o.text.isEmpty()) continue
                val top = maxOf(o.rect.top, c.rect.top)
                val bot = minOf(o.rect.bottom, c.rect.bottom)
                if ((bot - top) < 0.5f * minOf(o.rect.height(), c.rect.height())) continue
                val gap = if (o.rect.left >= c.rect.right - 10) o.rect.left - c.rect.right
                          else 100000 + maxOf(0, c.rect.left - o.rect.right)
                if (gap < bestGap) { bestGap = gap; best = o }
            }
            val label = best?.text?.take(150)
            k++
            list[i] = c.copy(text = if (label != null) "(ô chọn) $label" else "(ô chọn trống $k)")
        }
        return list
    }

    private fun collect(): List<Item> {
        val out = ArrayList<Item>()
        val root = rootInActiveWindow ?: return out
        fun walk(n: AccessibilityNodeInfo, depth: Int, parentVisible: Boolean, parentRect: Rect) {
            if (out.size >= 200 || depth > 40) return
            if (n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()
                val choice = isChoiceNode(n)
                if (!r.isEmpty) {
                    if (!t.isNullOrEmpty()) {
                        out.add(Item(t.take(400), r, n, choice, choice && n.isChecked))
                    } else if (choice || looksLikeIconButton(n, r)) {
                        out.add(Item("", r, n, true, choice && n.isChecked))
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1, true, r) }
            } else if (parentVisible) {
                // Nút ẩn nằm trong nút đang hiện (ví dụ công thức KaTeX/MathML): lấy chữ, chạm vào vùng của nút cha.
                val t = gather(n, 0).trim()
                if (t.isNotEmpty() && !parentRect.isEmpty) {
                    out.add(Item("(công thức) " + t.take(300), Rect(parentRect), n))
                }
            }
        }
        walk(root, 0, false, Rect())
        return labelControls(out)
    }

    @TargetApi(30)
    private fun ocrThenProcess(key: String) {
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (bmp == null) { busy = false; return }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { res ->
                        val list = res.textBlocks.flatMap { it.lines }
                            .mapNotNull { l -> l.boundingBox?.let { Item(l.text, it, null) } }
                        busy = false
                        process(list, key)
                    }
                    .addOnFailureListener { busy = false }
            }
            override fun onFailure(errorCode: Int) { busy = false }
        })
    }

    private fun process(items: List<Item>, key: String) {
        if (items.isEmpty()) return
        val h = items.joinToString("|") { it.text }.hashCode()
        if (h != currentHash) { currentHash = h; retries = 0 }
        if (h == lastHash) return
        val lh = items.joinToString("|") { it.text + it.rect.flattenToString() }.hashCode()
        if (lh != layoutHash) {
            layoutHash = lh
            setStatus("chờ trang ổn định…")
            return
        }
        val cached = answerCache[h]
        if (cached != null) {
            val targets = ArrayList<Pair<Item, Int>>()
            for ((txt, occ) in cached) {
                val cand = items.filter { it.text == txt }
                if (cand.size > occ) targets.add(Pair(cand[occ], occ))
            }
            if (targets.isNotEmpty() && targets.size == cached.size) {
                lastHash = h
                status = "nhớ: ${targets.size} đáp án"
                refresh()
                chooseAll(targets, 0)
                return
            }
        }
        val hasImg = CaptureService.isRunning()
        val text = buildPrompt(items)
        if (!hasImg && text.contains("(công thức)")) {
            status = "⚠ CHƯA BẬT CHỤP MÀN HÌNH: mở app Auto Trắc Nghiệm > Cấp quyền chụp màn hình"
            refresh()
            cooldownUntil = SystemClock.uptimeMillis() + 3000
            return
        }
        lastHash = h
        busy = true
        val fast = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("fast", true)
        setStatus(if (fast) "AI nhanh…" else "AI đang suy nghĩ…")
        lastSent = text
        exec.execute {
            var got: List<Int> = emptyList()
            var gotErr: String? = null
            try {
                got = ClaudeClient.ask(key, text, fast)
            } catch (e: Exception) {
                gotErr = e.message ?: "lỗi mạng"
            }
            val ids: List<Int> = got
            val err: String? = gotErr
            handler.post {
                busy = false
                if (err != null) {
                    lastHash = 0
                    val wait = if (err.contains("429")) 30000L else 6000L
                    cooldownUntil = SystemClock.uptimeMillis() + wait
                    status = "lỗi: " + err.take(80)
                    refresh()
                    toast(err)
                    return@post
                }
                val valid = ids.filter { it >= 1 && it <= items.size }.distinct().sorted()
                if (valid.isNotEmpty()) {
                    retries = 0
                    val targets = valid.map { id ->
                        val t = items[id - 1].text
                        Pair(items[id - 1], items.subList(0, id - 1).count { x -> x.text == t })
                    }
                    status = "chọn ${valid.size}: " + items[valid[0] - 1].text.take(25)
                    refresh()
                    if (hasImg || !text.contains("(công thức)")) {
                        answerCache[h] = targets.map { p -> Pair(p.first.text, p.second) }
                    }
                    chooseAll(targets, 0)
                } else if (retries < 1) {
                    retries++
                    lastHash = 0
                    status = "thử lại ($retries)"
                    refresh()
                } else {
                    status = "không thấy câu hỏi"
                    refresh()
                }
            }
        }
    }

    // Ghép các mảnh công thức rời rạc (mỗi ký tự một nút) thành một dòng "(công thức)" cho AI dễ đọc.
    // Ô tích chọn được đánh dấu ☐ (chưa chọn) / ☑ (đã chọn). Số thứ tự gốc được giữ để AI trả đúng số cần chạm.
    private fun buildPrompt(items: List<Item>): String {
        val sb = StringBuilder()
        val run = ArrayList<Int>()
        fun flush() {
            if (run.isEmpty()) return
            val joined = run.joinToString("") { items[it].text }
            val formulaLike = joined.any { !it.isLetterOrDigit() || Character.isSurrogate(it) }
            if (run.size >= 3 && formulaLike) {
                sb.append("(công thức) ").append(joined).append('\n')
            } else {
                for (k in run) sb.append("[${k + 1}] ").append(items[k].text).append('\n')
            }
            run.clear()
        }
        for ((i, item) in items.withIndex()) {
            val t = item.text
            val isChoiceLetter = t.length == 1 && t[0] in 'A'..'D'
            val short = !item.ctrl && t.length <= 2 && !isChoiceLetter && !t.startsWith("(")
            if (short) {
                run.add(i)
            } else {
                flush()
                if (item.ctrl) sb.append("[${i + 1}] ").append(if (item.checked) "☑ " else "☐ ").append(t).append('\n')
                else sb.append("[${i + 1}] ").append(t).append('\n')
            }
        }
        flush()
        return sb.toString().trimEnd()
    }

    private fun layoutOf(list: List<Item>): Int =
        list.joinToString("|") { it.text + it.rect.flattenToString() }.hashCode()

    // Lấy lại tọa độ mới nhất của đáp án (trang có thể đã dịch chuyển trong lúc chờ AI).
    private fun resolve(item: Item, occurrence: Int): Item {
        val fresh = collect().filter { it.text == item.text }
        if (fresh.size > occurrence) return fresh[occurrence]
        val n = item.node
        if (n != null) {
            n.refresh()
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty) return item.copy(rect = r)
        }
        return item
    }

    // Chạm lần lượt tất cả đáp án (nhiều câu / nhiều đáp án đúng / Đúng-Sai). Bỏ qua ô đã được tích để khỏi bị bỏ tích.
    private fun chooseAll(targets: List<Pair<Item, Int>>, index: Int) {
        tapping = true
        if (index >= targets.size) {
            tapping = false
            cooldownUntil = SystemClock.uptimeMillis() + 700
            afterTap()
            return
        }
        val cur = resolve(targets[index].first, targets[index].second)
        val n = cur.node
        var already = false
        if (cur.ctrl && n != null) {
            n.refresh()
            already = n.isCheckable && n.isChecked
        }
        val next = { handler.postDelayed({ chooseAll(targets, index + 1) }, 60) }
        if (already) {
            next()
            return
        }
        tap(cur.rect) { ok ->
            if (!ok) clickNode(cur.node)
            next()
        }
    }

    // Sau khi chạm, coi màn hình hiện tại (có thể có lời giải/đáp án đúng) là "đã xem" để không hỏi lại.
    private fun afterTap() {
        handler.postDelayed({
            val items2 = collect()
            val h2 = items2.joinToString("|") { it.text }.hashCode()
            lastHash = h2
            currentHash = h2
            layoutHash = layoutOf(items2)
        }, 500)
    }

    private fun clickNode(n: AccessibilityNodeInfo?) {
        if (n == null) return
        n.refresh()
        val maxH = resources.displayMetrics.heightPixels * 0.5
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                val b = Rect(); cur.getBoundsInScreen(b)
                if (b.height() < maxH && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                break
            }
            cur = cur.parent
            depth++
        }
    }

    private fun tap(r: Rect, onDone: (Boolean) -> Unit) {
        val dm = resources.displayMetrics
        val x = r.exactCenterX().coerceIn(2f, dm.widthPixels - 2f)
        val y = r.exactCenterY().coerceIn(2f, dm.heightPixels - 2f)
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 40)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { onDone(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { onDone(false) }
        }, handler)
        if (!ok) onDone(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        handler.removeCallbacks(loop)
        handler.removeCallbacks(idleTicker)
        (getSystemService(WINDOW_SERVICE) as WindowManager).let { wm -> button?.let { wm.removeView(it) } }
        super.onDestroy()
    }
}
