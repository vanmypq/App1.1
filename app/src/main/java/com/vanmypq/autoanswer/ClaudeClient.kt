package com.vanmypq.autoanswer

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// Giữ tên ClaudeClient để không phải sửa file khác. Thực tế gọi Google Gemini API.
// Ưu tiên model Flash đầy đủ (suy luận tốt hơn); chỉ tạm chuyển sang Flash-Lite khi lỗi/hết hạn mức,
// và sau 5 phút tự thử lại model tốt nhất.
object ClaudeClient {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private var models: List<String>? = null
    private var idx = 0
    private var switchedAt = 0L
    private val modeOf = HashMap<String, Int>()
    // mode 0: thinkingBudget=1024 | 1: thinkingLevel=low | 2: không cấu hình suy nghĩ | 3: như 2 nhưng bỏ ảnh

    @Volatile var lastInfo: String = ""

    private const val BASE_RULES = "Bạn là giáo viên Toán/Khoa học THCS Việt Nam giải bài trắc nghiệm. Người dùng gửi " +
        "danh sách các phần tử trên màn hình điện thoại, mỗi dòng dạng [số] nội dung, và có thể kèm ẢNH CHỤP " +
        "màn hình. Dùng ảnh để đọc công thức, hình vẽ, bảng biểu mà danh sách chữ thiếu hoặc bị dính chữ " +
        "(ví dụ 'y2' nghĩa là y bình phương). Dòng '(công thức)' là công thức đã ghép lại, nằm ngay sau đáp án " +
        "đứng trước nó. Ký hiệu ☐ là ô tích chọn chưa chọn, ☑ là ô đã chọn, '(ô chọn) ...' là ô tích chọn kèm chữ " +
        "cùng hàng. Hỗ trợ MỌI dạng: đáp án có chữ A/B/C/D; chỉ có ô tròn/ô vuông không có chữ cái; một đáp án " +
        "đúng; nhiều đáp án đúng; dạng Đúng/Sai theo từng ý a,b,c,d (chọn nút Đúng hoặc Sai của MỖI ý); và nhiều " +
        "câu hỏi cùng lúc trên một màn hình (trả lời tất cả). Với câu PHỦ ĐỊNH (KHÔNG PHẢI, SAI, NGOẠI TRỪ) chọn " +
        "phương án KHÔNG thỏa điều kiện. Ưu tiên bấm ô tích chọn (☐) hoặc chữ cái A/B/C/D của đáp án; không chọn " +
        "lại ô đã ☑ trừ khi sai. Dòng cuối cùng phải là JSON duy nhất: {\"ids\": [N, N, ...]} gồm số [id] của MỌI " +
        "phần tử cần bấm, theo thứ tự từ trên xuống. Nếu màn hình không có câu trắc nghiệm hoặc không chắc chắn, " +
        "trả {\"ids\": []}. "
    private const val FAST_TAIL = "TRẢ LỜI NGAY, KHÔNG giải thích, chỉ in đúng một dòng JSON."
    private const val SLOW_TAIL = "Kiểm tra TỪNG phương án (chú ý bẫy hệ số bằng 0, số mũ, mẫu số chứa ẩn), " +
        "chỉ ghi phân tích ngắn gọn tối đa 6 dòng rồi mới in JSON."

    private fun loadModels(apiKey: String): List<String> {
        models?.let { return it }
        val c = URL("$BASE/models?pageSize=200").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("x-goog-api-key", apiKey)
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${txt.take(150)}")
        val arr = JSONObject(txt).optJSONArray("models") ?: JSONArray()
        val skip = listOf("image", "tts", "live", "audio", "embedding", "robotics", "computer", "exp")
        val flash = ArrayList<String>()
        val lite = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val n = m.optString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
            if (!methods.contains("generateContent") || !n.startsWith("gemini-") ||
                !n.contains("flash") || skip.any { n.contains(it) }
            ) continue
            if (n.contains("lite")) lite.add(n) else flash.add(n)
        }
        val list = ArrayList<String>()
        list.add(if (flash.contains("gemini-flash-latest")) "gemini-flash-latest"
                 else flash.sortedDescending().firstOrNull() ?: "gemini-flash-latest")
        list.add(if (lite.contains("gemini-flash-lite-latest")) "gemini-flash-lite-latest"
                 else lite.sortedDescending().firstOrNull() ?: "gemini-flash-lite-latest")
        list.addAll(flash.sortedDescending())
        list.addAll(lite.sortedDescending())
        val result = list.distinct()
        models = result
        return result
    }

    private fun buildBody(screenText: String, mode: Int, img: String?, fast: Boolean): JSONObject {
        val parts = JSONArray()
        if (img != null && mode < 3) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", img)))
        }
        parts.put(JSONObject().put("text", screenText))
        val gen = JSONObject().put("maxOutputTokens", if (fast) 1024 else 4096)
        if (mode == 0) gen.put("thinkingConfig", JSONObject().put("thinkingBudget", if (fast) 0 else 1024))
        if (mode == 1) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
        if (fast && mode < 3) {
            // Ép model chỉ trả {"ids":[...]} (vài token) -> nhanh nhất.
            gen.put("responseMimeType", "application/json")
            gen.put("responseSchema", JSONObject()
                .put("type", "OBJECT")
                .put("properties", JSONObject().put("ids", JSONObject()
                    .put("type", "ARRAY")
                    .put("items", JSONObject().put("type", "INTEGER"))))
                .put("required", JSONArray().put("ids")))
        }
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", BASE_RULES + (if (fast) FAST_TAIL else SLOW_TAIL)))))
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", parts)))
            .put("generationConfig", gen)
    }

    private fun post(apiKey: String, m: String, body: JSONObject, fast: Boolean): Pair<Int, String> {
        try {
            val c = URL("$BASE/models/$m:generateContent").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = if (fast) 10000 else 30000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            return Pair(code, resp)
        } catch (e: SocketTimeoutException) {
            return Pair(408, "quá thời gian chờ")
        }
    }

    private fun replyText(resp: String): String {
        val cands = JSONObject(resp).optJSONArray("candidates") ?: return ""
        if (cands.length() == 0) return ""
        val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }

    private fun parseIds(text: String): List<Int> {
        val arr = Regex("\"ids\"\\s*:\\s*\\[([^\\]]*)\\]").findAll(text).lastOrNull()
        if (arr != null) {
            return Regex("\\d+").findAll(arr.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
        }
        val one = Regex("\"id\"\\s*:\\s*(\\d+)").findAll(text).lastOrNull()
        return if (one != null) listOfNotNull(one.groupValues[1].toIntOrNull()) else emptyList()
    }

    // Không gọi danh sách model trước (bớt 1 vòng mạng); chỉ dò model khi mọi model mặc định đều lỗi.
    private val DEFAULT_MODELS = listOf("gemini-flash-latest", "gemini-flash-lite-latest")

    // Mở sẵn kết nối HTTPS khi bật nút AI để câu đầu tiên không mất thời gian bắt tay TLS.
    fun warmUp(apiKey: String) {
        try {
            val c = URL("$BASE/models/gemini-flash-lite-latest").openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.setRequestProperty("x-goog-api-key", apiKey)
            c.responseCode
            (try { c.inputStream } catch (e: Exception) { c.errorStream })?.use { it.readBytes() }
        } catch (e: Exception) {
        }
    }

    fun ask(apiKey: String, screenText: String, fast: Boolean): List<Int> {
        // Chế độ nhanh chỉ gửi ảnh khi có công thức; chế độ chính xác luôn gửi ảnh.
        val needImg = !fast || screenText.contains("(công thức)")
        val img: String? = if (needImg) (try { CaptureService.grab(if (fast) 1000 else 0) } catch (e: Exception) { null }) else null
        val base = models ?: DEFAULT_MODELS
        // Nhanh: ưu tiên bản Lite (nhanh gấp ~2 lần); Chính xác: ưu tiên bản Flash đầy đủ.
        val list = if (fast) base.sortedBy { if (it.contains("lite")) 0 else 1 } else base
        if (idx != 0 && System.currentTimeMillis() - switchedAt > 300000L) idx = 0
        var lastErr = "không có model"
        var tries = 0
        while (tries < list.size) {
            val m = list[idx % list.size]
            var mode = modeOf[m] ?: 0
            var r = post(apiKey, m, buildBody(screenText, mode, img, fast), fast)
            while (r.first == 400 && mode < 3 && !r.second.contains("API key", ignoreCase = true)) {
                mode++
                r = post(apiKey, m, buildBody(screenText, mode, img, fast), fast)
            }
            if (r.first in 200..299) {
                modeOf[m] = mode
                val text = replyText(r.second)
                lastInfo = "Model: $m | Nhanh: $fast | Ảnh: " + (if (img != null && mode < 3) "CÓ" else "KHÔNG") +
                    " | Chế độ suy nghĩ: $mode\nAI trả lời:\n$text"
                return parseIds(text)
            }
            lastErr = "HTTP ${r.first} ($m): ${r.second.take(120)}"
            if (r.first == 429 || r.first == 404 || r.first == 408 || r.first == 500 || r.first == 503) {
                idx++
                switchedAt = System.currentTimeMillis()
                tries++
                continue
            }
            throw RuntimeException(lastErr)
        }
        models = null
        try { loadModels(apiKey) } catch (e: Exception) { }
        throw RuntimeException(lastErr)
    }
}
