package com.example.aichat.writer

import android.util.Log
import com.example.aichat.AiModelConfig
import com.example.aichat.ApiUtils
import com.example.aichat.ChatApi
import com.example.aichat.ChatService
import com.example.aichat.ChapterPlanContext
import com.example.aichat.ModelConfig
import com.example.aichat.SessionOutlineItem
import com.example.aichat.chat.ChatTextHelpers
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Writer 模式的章节计划生成（带容错降级、JSON 修复、关键字 fallback）。
 * 从 ChatService 抽出（R4），通过持有 [ChatService] 引用调用其 internal 方法。
 */
class WriterChapterPlanService(private val service: ChatService) {

    companion object {
        private const val TAG = "WriterChapterPlanService"
    }

    fun generate(ctx: ChapterPlanContext, callback: ChatService.ChatCallback) {
        val targetTitle = ctx.targetTitle.trim()
        if (targetTitle.isEmpty()) {
            callback.onError("目标章节标题为空")
            return
        }

        val config: AiModelConfig.ResolvedConfig
        try {
            config = AiModelConfig(service.context).getConfigForNovelSharp()
        } catch (e: Exception) {
            callback.onError("配置解析失败")
            return
        }
        if (config == null || !config.isValid()) {
            callback.onError(service.context.getString(com.example.aichat.R.string.error_no_novel_model_selected))
            return
        }

        var providerId = ""
        val preset = ModelConfig(service.context).getNovelSharpPreset()
        if (preset != null && preset.contains(":")) {
            providerId = preset.substring(0, preset.indexOf(':'))
        }
        providerId = service.resolveProviderId(providerId, config.apiHost)

        var baseUrl = config.toRetrofitBaseUrl()
        if (!baseUrl.endsWith("/")) baseUrl += "/"

        val localOpenAiCompat = service.isLocalOpenAiCompatibleProvider(providerId)
        val timeoutSec = if (localOpenAiCompat) 60 else 45

        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        val api = retrofit.create(ChatApi::class.java)

        val requestMessages = ArrayList<ChatApi.ChatMessage>()
        requestMessages.add(ChatApi.ChatMessage("system",
            "你是小说章节规划助手。\n" +
                    "你将收到：(a) 一段按类型分组的大纲上下文（含章节序列、人物、世界、知情等），(b) 一个明确的【本次必须规划的章节】标题。\n" +
                    "你的唯一任务：为【该目标章节本身】（不是它的前一章，也不是它的下一章）输出一个结构化写作计划。\n\n" +
                    "仅输出一个 JSON 对象（绝对禁止 Markdown 代码块、解释、Thinking 文本）：\n" +
                    "{\"chapterGoal\":\"\",\"startState\":\"\",\"endState\":\"\",\"characterDrives\":[],\"knowledgeBoundary\":[],\"eventChain\":[],\"foreshadow\":[],\"payoff\":[],\"forbidden\":[],\"styleGuide\":\"\",\"targetLength\":\"\"}\n\n" +
                    "字段语义（务必严格匹配，前端会一一回填到对应输入框）：\n" +
                    "- chapterGoal: 字符串，本章核心目标（≤120字）。\n" +
                    "- startState: 字符串，本章开场时的人物/局面状态。\n" +
                    "- endState: 字符串，本章收尾时的状态，需与 startState 形成可见对比。\n" +
                    "- characterDrives: 对象数组 [{\"name\":\"\",\"goal\":\"\",\"misbelief\":\"\",\"emotion\":\"\"}]，每个出场关键角色一项；name 必填。\n" +
                    "- knowledgeBoundary: 字符串数组；每条一行短陈述，形如 \"X 知道/不知道/误以为 Y\"。\n" +
                    "- eventChain: 字符串数组（3-7 项），按时间顺序，每条形如 \"起因 → 行为 → 结果\"，不得照抄前文已发生事件。\n" +
                    "- foreshadow: 字符串数组，本章埋下的伏笔。\n" +
                    "- payoff: 字符串数组，本章兑现/回收的伏笔。\n" +
                    "- forbidden: 字符串数组，本章不应写的内容（剧透、违反人设的动作、跳跃式叙述等）。\n" +
                    "- styleGuide: 字符串，文风 / 节奏 / 视角提示。\n" +
                    "- targetLength: 字符串（即使是数字也用引号），例如 \"3000\"。\n\n" +
                    "强约束（违反任意一条都视为失败）：\n" +
                    "1) 输出必须以 { 开头、以 } 结尾，必须保留全部 11 个键；空值用 \"\" 或 [] 占位。\n" +
                    "2) 严禁把目标章节误解成「下一章 / 续写章节」——你输出的计划就是用户指定的那一章本身。\n" +
                    "3) 若是【覆盖】模式，请基于「目标章节当前大纲」做重写或细化；不是为后续章节做规划。\n" +
                    "4) 计划要呼应章节序列中【本次必须规划的章节】所在位置——之前章节是已发生事实，之后章节（如有）是未来约束。\n" +
                    "5) 不得违背【知情约束】：角色只能基于其已知信息行动；让某角色得知新信息需在 eventChain 中给出获取路径。\n" +
                    "6) 内容具体可执行；避免「角色继续推进剧情」之类的空话。"))
        requestMessages.add(ChatApi.ChatMessage("user", buildChapterPlanUserPrompt(ctx)))

        val request = ChatApi.ChatRequest()
        request.model = config.modelId
        request.messages = requestMessages
        request.stream = false
        request.n = 1
        request.maxTokens = 800
        request.temperature = 0.15
        request.topP = 0.6
        service.applyModelDefaultsToRequest(request, providerId, config.modelId)
        request.stop = null
        // Keep chapter-plan request minimal for broad compatibility and lower latency.
        request.thinking = null
        request.reasoning = null
        request.responseFormat = null
        request.providerOptions = null

        val auth = if (config.apiKey != null && config.apiKey.trim().isNotEmpty())
            "Bearer " + config.apiKey.trim() else null
        val chatUrl = ApiUtils.toBaseUrl(config.apiHost, config.apiPath)
        Log.d(TAG, "generateChapterPlanJson providerId=$providerId"
                + ", model=${config.modelId}"
                + ", localOpenAiCompat=$localOpenAiCompat"
                + ", thinking=${request.thinking}"
                + ", reasoning=${request.reasoning?.toString() ?: "null"}"
                + ", responseFormat=${request.responseFormat?.toString() ?: "null"}")
        callback.onPartial("正在请求章节计划模型…")
        requestChapterPlanWithFallback(api, chatUrl, auth, request, callback, true)
    }

    /**
     * 构建 user prompt：把目标章节、章节序列、人物/世界/知情/资料、最近对话、用户提示
     * 按结构化段落输出，让模型既知道要规划哪一章、又能锚定到大纲位置。
     */
    private fun buildChapterPlanUserPrompt(ctx: ChapterPlanContext): String {
        val targetTitle = ctx.targetTitle.trim()
        val mode = if (ctx.isExisting) "覆盖已有计划" else "新建续写章节"
        val sb = StringBuilder()
        // 把目标章节定为头条信息 + 醒目箭头 + 重复声明，避免模型把它误解为「下一章」。
        sb.append("============================\n")
        sb.append("【本次必须规划的章节】").append(targetTitle).append("\n")
        sb.append("【模式】").append(mode).append("\n")
        sb.append("⚠️ 你的所有输出仅围绕上面这一章；不是它的前一章，不是它的下一章。\n")
        sb.append("============================\n")

        if (ctx.isExisting && ctx.existingContent.trim().isNotEmpty()) {
            sb.append("\n【目标章节当前大纲（你需要重写/细化的内容）】\n")
                .append(truncate(ctx.existingContent.trim(), 800))
                .append("\n")
        }

        if (ctx.allChapters.isNotEmpty()) {
            sb.append("\n【章节序列上下文 — 仅供定位，不是规划目标】\n")
            var matchedTarget = false
            val perChapterCap = 240
            for ((idx, item) in ctx.allChapters.withIndex()) {
                val itemTitle = item.title?.trim().orEmpty()
                val itemContent = item.content?.trim().orEmpty()
                val isTarget = ctx.isExisting && itemTitle == targetTitle && !matchedTarget
                if (isTarget) matchedTarget = true
                sb.append(idx + 1).append(". ")
                if (isTarget) sb.append("◆◆ 这就是目标章节 ◆◆ ")
                sb.append(if (itemTitle.isEmpty()) "(无标题)" else itemTitle)
                if (itemContent.isNotEmpty()) {
                    sb.append("：").append(truncate(itemContent.replace("\n", " "), perChapterCap))
                }
                sb.append("\n")
            }
            if (!ctx.isExisting) {
                sb.append(ctx.allChapters.size + 1).append(". ◆◆ 这就是目标章节（新建续写） ◆◆ ").append(targetTitle).append("\n")
            }
        } else if (!ctx.isExisting) {
            sb.append("\n【章节序列上下文】（暂无章节，本章为开篇）\n")
        }

        appendOutlineSection(sb, "人物资料", ctx.characters, perItemCap = 320)
        appendOutlineSection(sb, "世界背景", ctx.worlds, perItemCap = 320, hideTitle = true)
        appendOutlineSection(sb, "知情约束", ctx.knowledgeConstraints, perItemCap = 240)
        appendOutlineSection(sb, "其他资料", ctx.materials, perItemCap = 240, hideTitle = true)

        val dlg = ctx.recentDialogue.trim()
        if (dlg.isNotEmpty()) {
            sb.append("\n【最近对话节选（按时间顺序）】\n").append(truncate(dlg, 1500)).append("\n")
        }

        val hint = ctx.userHint.trim()
        if (hint.isNotEmpty()) {
            sb.append("\n【本章用户补充指示】\n").append(truncate(hint, 600)).append("\n")
        }

        val target = ctx.targetLength.trim()
        if (target.isNotEmpty()) {
            sb.append("\n【期望篇幅】").append(target).append("（请将此值写入 targetLength 字段）\n")
        }

        val oprompt = ctx.outlinePrompt.trim()
        if (oprompt.isNotEmpty()) {
            sb.append("\n【文风与风格指导】\n").append(truncate(oprompt, 600)).append("\n")
        }

        // 末尾再强调一次目标章节，模型在长 prompt 中往往关注首尾。
        sb.append("\n============================\n")
        sb.append("提醒：现在请输出【").append(targetTitle).append("】这一章的写作计划 JSON。\n")
        sb.append("============================\n")

        // Soft cap to keep request reasonable; keep head + tail to preserve target+latest context.
        return softCap(sb.toString(), 7800)
    }

    private fun appendOutlineSection(
        sb: StringBuilder,
        label: String,
        items: List<SessionOutlineItem>,
        perItemCap: Int,
        hideTitle: Boolean = false,
    ) {
        if (items.isEmpty()) return
        sb.append("\n【").append(label).append("】\n")
        for (item in items) {
            val title = item.title?.trim().orEmpty()
            val content = item.content?.trim().orEmpty()
            if (title.isEmpty() && content.isEmpty()) continue
            sb.append("- ")
            if (!hideTitle && title.isNotEmpty()) sb.append(title).append("：")
            if (content.isNotEmpty()) sb.append(truncate(content.replace("\n", " "), perItemCap))
            sb.append("\n")
        }
    }

    private fun truncate(s: String, max: Int): String {
        if (s.length <= max) return s
        return s.substring(0, max) + "…"
    }

    private fun softCap(s: String, max: Int): String {
        if (s.length <= max) return s
        val head = s.substring(0, max - 200)
        val tail = s.substring(s.length - 200)
        return head + "\n…(中段省略)…\n" + tail
    }

    private fun requestChapterPlanWithFallback(
        api: ChatApi,
        chatUrl: String,
        auth: String?,
        request: ChatApi.ChatRequest,
        callback: ChatService.ChatCallback,
        allowFallback: Boolean
    ) {
        api.chatWithUrl(chatUrl, auth, "application/json", request)
            .enqueue(object : retrofit2.Callback<ChatApi.ChatResponse> {
                override fun onResponse(
                    call: retrofit2.Call<ChatApi.ChatResponse>,
                    response: retrofit2.Response<ChatApi.ChatResponse>
                ) {
                    val body707 = response.body()
                    val choices707 = body707?.choices
                    if (!response.isSuccessful || body707 == null || choices707 == null
                        || choices707.isEmpty() || choices707[0] == null
                        || choices707[0].message == null) {
                        var detail = ""
                        try {
                            if (response.errorBody() != null) {
                                detail = response.errorBody()!!.string()
                            }
                        } catch (ignored: Exception) {}
                        if (allowFallback && shouldRetryWithoutAdvancedParams(detail)) {
                            callback.onPartial("参数兼容中，正在重试…")
                            Log.w(TAG, "chapter plan retry without advanced params, detail=$detail")
                            requestChapterPlanWithFallback(
                                api,
                                chatUrl,
                                auth,
                                buildChapterPlanFallbackRequest(request),
                                callback,
                                false
                            )
                            return
                        }
                        callback.onError("章节计划生成失败: " + response.code()
                                + if (detail.isEmpty()) "" else ("\n" + detail))
                        return
                    }
                    callback.onPartial("模型已返回，正在解析计划…")
                    val raw = ChatTextHelpers.extractAssistantContent(body707)
                    Log.d(TAG, "chapter plan raw length=${raw?.length ?: 0}"
                            + ", preview=${ChatTextHelpers.previewForLog(raw, 180)}")
                    val obj = WriterJsonHelpers.parseFirstJsonObject(raw)
                    if (obj == null) {
                        val preview = raw?.trim() ?: ""
                        var head = preview
                        var tail = ""
                        if (head.length > 120) {
                            head = head.substring(0, 120) + "..."
                            val start = Math.max(0, preview.length - 120)
                            tail = "...\n末尾片段: " + preview.substring(start)
                        }
                        callback.onError("章节计划解析失败" +
                                if (preview.isEmpty()) "" else ("\n返回长度: " + preview.length
                                        + "\n开头片段: " + head + tail))
                        return
                    }
                    callback.onPartial("章节计划已生成")
                    val normalized = WriterJsonHelpers.normalizeChapterPlanJson(obj)
                    Log.d(TAG, "chapter plan normalized nonEmptyFields=${WriterJsonHelpers.countNonEmptyPlanFields(normalized)}"
                            + ", payload=${ChatTextHelpers.previewForLog(normalized.toString(), 220)}")
                    callback.onSuccess(normalized.toString())
                }

                override fun onFailure(call: retrofit2.Call<ChatApi.ChatResponse>, t: Throwable) {
                    val reason = t.message ?: "章节计划生成失败"
                    callback.onError("章节计划生成失败(${request.model}): $reason")
                }
            })
    }

    private fun buildChapterPlanFallbackRequest(source: ChatApi.ChatRequest?): ChatApi.ChatRequest {
        val request = ChatApi.ChatRequest()
        request.model = source?.model
        request.messages = source?.messages
        request.stream = false
        request.n = null
        request.maxTokens = source?.maxTokens
        request.temperature = null
        request.topP = null
        request.stop = null
        request.thinking = null
        request.reasoning = null
        request.responseFormat = null
        request.providerOptions = null
        return request
    }

    private fun shouldRetryWithoutAdvancedParams(detail: String?): Boolean {
        if (detail == null || detail.trim().isEmpty()) return false
        val lower = detail.lowercase(java.util.Locale.ROOT)
        if (lower.contains("invalid_request_error")) return true
        if (lower.contains("unknown parameter")) return true
        if (lower.contains("invalid parameter")) return true
        if (lower.contains("unsupported parameter")) return true
        if (lower.contains("response_format")) return true
        if (lower.contains("reasoning")) return true
        if (lower.contains("thinking")) return true
        if (lower.contains("temperature")) return true
        return lower.contains("top_p")
    }
}
