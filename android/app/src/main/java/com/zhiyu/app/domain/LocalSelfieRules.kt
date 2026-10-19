package com.zhiyu.app.domain

data class LocalSelfieTicket(val scope: String, val lane: Int, val generation: Int)

/** Explicit selfie opportunities use a real persisted daily scene, never a synthetic scene. */
object LocalSelfieRules {
    const val SIZE = 3
    private val takingOrPostRecipient = Regex("^(?:请|麻烦|能|可以|能不能|能否)?(?:你)?(?:给我)?(?:发|发来|拍)(?:一|两|几)?(?:张|个)" +
        "(?:你(?:的|自己的|今天的|现在的|最新的|最近的))?(?:自拍|近照|照片|照)(?:给我(?:看|看看)?)?(?:吧|呀|嘛|吗|呢|~|～)*$")
    /** Only a narrow whole-message request may bypass generation; unknown or mixed intent keeps its answer. */
    fun standaloneRequest(text: String): Boolean {
        val value = text.lowercase().replace(Regex("\\s+"), "").trimEnd('。', '！', '？', '!', '?', '~', '～')
        if (!requested(text)) return false
        return Regex("^(?:请|麻烦)?(?:给我(?:发|看|看看)?|发给我|发我|来|发|发来|看看?你(?:的)?)" +
            "(?:一|两|几)?(?:张|个)?(?:你(?:的|今天的|现在的|最新的|最近的))?(?:自拍|近照|照片)(?:吧|呀|嘛|吗|呢)*$")
            .matches(value) || Regex("^(?:send|show|give)me(?:a|one|your)?selfie$").matches(value) || takingOrPostRecipient.matches(value)
    }
    fun requested(text: String): Boolean {
        val clauses = text.take(10_000).lowercase().split(Regex("[，。！？!?；;\\r\\n]"))
            .map { it.replace(Regex("\\s+"), "") }
        val lastCancellation = clauses.indexOfLast { Regex("^(?:现在|这次|那)?(?:不用了|不要了|算了|先别发了|先不发了)$").matches(it) }
        return clauses.drop(lastCancellation + 1).any(::requestedClause)
    }

    private fun requestedClause(value: String): Boolean {
        if (Regex("(?:不要|别|不用|不想|不需要).{0,16}(?:自拍|照片|近照|selfie)").containsMatchIn(value)) return false
        if (Regex("(?:教我|教教我|怎么自拍|如何自拍|怎样自拍|自拍技巧|自拍教程|帮我看|帮我看看)").containsMatchIn(value)) return false
        if (Regex("(?:给你(?:发|看)|发给你|发你|我的|这是我|(?<!给)我(?:想|要|准备|打算|刚|已经|正在)?拍)[^，。！？!?]{0,12}(?:自拍|照片|近照)")
                .containsMatchIn(value)) return false
        if (Regex("^(?:他|她|别人|朋友|你)(?:曾经|以前|之前)?说(?:过|了)?|[“”\"「」]").containsMatchIn(value)) return false
        // Recipient, companion ownership, or a direct imperative is required; a generic 想/看/给 is insufficient.
        val recipient = "(?:给我(?:发|看|看看)?|发给我|发我)(?:一|两|几)?(?:张|个)?(?:你(?:的|自己的|今天的|现在的|最新的|最近的))?(?:自拍|近照|照片)"
        val companion = "(?:(?:我)?(?:想|想要)?(?:看|看看)|(?:请)?发(?:一张|张)?)(?:你(?:的|今天的|现在的|最新的|最近的))(?:自拍|近照|照片)"
        val imperative = "^(?:请|能|可以|能不能|能否)?(?:给我)?(?:来|发|发来)(?:一|两|几)?(?:张|个)?(?:你的)?(?:自拍|近照|照片)(?:吧|呀|嘛|吗|呢|~|～)*$"
        val standalone = "^(?:你的(?:自拍|照片|近照)|自拍)(?:呢|吧|呀|嘛|吗|~|～)*$"
        val english = "(?:send|show|give)me(?:a|one|your)?selfie|(?:iwant|canihave)(?:a|your)?selfie"
        return takingOrPostRecipient.matches(value) || listOf(recipient, companion, imperative, standalone, english)
            .any { Regex(it).containsMatchIn(value) }
    }
    fun tags(ticket: LocalSelfieTicket): List<String> = check(ticket).let {
        listOf("selfie_scope_${it.scope.take(24)}", "selfie_${it.lane}_${it.generation}")
    }
    fun check(ticket: LocalSelfieTicket): LocalSelfieTicket {
        require(Regex("[a-f0-9]{64}").matches(ticket.scope) && ticket.lane in 0 until SIZE &&
            ticket.generation in 0..1_000_000) { "自拍库存位置无效" }
        return ticket
    }
}

data class LocalPhotoDeliveryRequest(val selfie: Boolean, val explicit: Boolean, val standalone: Boolean,
    val subject: String? = null)

/** A real recent offer can authorize delivery of existing pixels, never a new paid attempt. */
object LocalPhotoDeliveryRules {
    private val person = Regex("自拍|近照|你(?:的|自己的|今天的|现在的|最近的|最新的)照片")
    private val photos = Regex("照片|图片|自拍|近照|风景照|生活照|猫.{0,3}照")
    private val cancellation = Regex("(?:不要|不用|别|先不).{0,16}(?:发|看|照片|图片|自拍)|^(?:算了|不用了|不要了)$")
    private val protectedText = Regex("[“”\"「」]|(?:教我|教教我|教程|技巧|帮我看)|" +
        "(?:怎么|如何).{0,8}(?:自拍|拍照|拍照片)|(?:自拍|拍照|照片).{0,8}(?:怎么拍|如何拍)|" +
        "^(?:他|她|别人|朋友|你)(?:曾经|以前|之前)?说")
    private val acceptance = Regex("^(?:都)?看看?(?:吧|呀|嘛|吗|呢|哦)?$|" +
        "^(?:好|好哦|好呀|好啊|好哒|嗯|嗯嗯|可以|行|来吧|发吧){1,4}$|" +
        "^(?:还)?(?:没|没有)(?:挑|选|准备|弄)好(?:吗|嘛|么|呢)?$|" +
        "^(?:诶|咦|哎|欸)?(?:我)?(?:还)?(?:没|没有)(?:看到|看见)(?:啊|呀|哦|呢)?$|" +
        "^(?:只要你发的就行)?(?:给我)?发(?:一|几)?张(?:给我)?看看?(?:吧|呀|嘛|吗|呢|哦)*$|" +
        "^(?:只要你发的就行|都行|随便哪张|哪张都行|你挑吧)$")

    fun resolve(text: String, dialogue: List<Message>, now: java.time.Instant): LocalPhotoDeliveryRequest? {
        if (text.isBlank() || cancellation.containsMatchIn(compact(text))) return null
        // Looking at an existing picture is a question, not permission to send or generate another.
        if (Regex("这张|那张|这幅|那幅|图里|图中|照片里|照片中|图片里|图片中").containsMatchIn(text) &&
            Regex("写|什么|谁|几只|几个人|问题|怎么样|怎么回事|分析|识别|评价").containsMatchIn(text)) return null
        val direct = LocalSelfieRules.requested(text)
        if (direct && person.containsMatchIn(text)) return LocalPhotoDeliveryRequest(true, true, LocalSelfieRules.standaloneRequest(text))
        if (!direct && protectedText.containsMatchIn(text)) return null
        val life = lifeRequest(text)
        if (life) return LocalPhotoDeliveryRequest(false, true, standaloneLife(text), subject(text))
        val context = context(text, dialogue, now)
        if (context != null && (direct || clauses(text).any { acceptance.matches(it) }))
            return context.copy(explicit = direct, standalone = if (direct) LocalSelfieRules.standaloneRequest(text) else acceptance.matches(compact(text)))
        return if (direct) LocalPhotoDeliveryRequest(true, true, LocalSelfieRules.standaloneRequest(text)) else null
    }

    private fun context(text: String, dialogue: List<Message>, now: java.time.Instant): LocalPhotoDeliveryRequest? {
        val history = if (dialogue.lastOrNull()?.let { it.role == "user" && it.content == text } == true) dialogue.dropLast(1) else dialogue
        for (message in history.takeLast(10).asReversed()) {
            val age = runCatching { java.time.Duration.between(java.time.Instant.parse(message.createdAt), now).seconds }.getOrNull()
                ?: return null
            if (age !in -30L..900L || message.role !in setOf("user", "assistant") || message.content.isBlank()) return null
            if (message.role == "assistant" && message.mediaAttachments.any { it.kind == "image" }) return null
            val value = compact(message.content)
            if (cancellation.containsMatchIn(value) || protectedText.containsMatchIn(message.content)) return null
            if (message.role == "assistant" && offered(value))
                return LocalPhotoDeliveryRequest(person.containsMatchIn(value), false, true, subject(value))
            if (message.role == "user") {
                if (lifeRequest(message.content)) return LocalPhotoDeliveryRequest(false, false, true, subject(value))
                if (LocalSelfieRules.requested(message.content)) return LocalPhotoDeliveryRequest(true, false, true)
                if (clauses(message.content).all { acceptance.matches(it) }) continue
                return null // An unrelated user turn ends this offer rather than reviving it later.
            }
            if (!Regex("照片|图片|自拍|这张|翻翻|翻来翻去|挑张|挑一张|选张|发你|给你看|^还没").containsMatchIn(value)) return null
        }
        return null
    }

    private fun offered(value: String): Boolean =
        !Regex("(?:没有|不能|无法).{0,12}(?:照片|图片|自拍)|(?:照片|图片|自拍).{0,12}(?:还没|没有|不能|无法)").containsMatchIn(value) &&
            (photos.containsMatchIn(value) && Regex("(?:给你|发你|看看|要看|想看|看哪|看吗)").containsMatchIn(value) ||
                Regex("^(?:我)?还有几张(?:你)?(?:要|想)?看哪(?:样|张|个).*$").matches(value))

    private fun lifeRequest(text: String): Boolean = subject(text) != null && photos.containsMatchIn(text) &&
        !protectedText.containsMatchIn(text) && !cancellation.containsMatchIn(compact(text)) &&
        Regex("^(?:请|麻烦|能不能|可以)?(?:我)?(?:想看|想看看|看看|看|给我|发给我|发我|发|来)").containsMatchIn(compact(text))

    private fun standaloneLife(text: String): Boolean = clauses(text).size == 1 &&
        !Regex("顺便|另外|还有|以及|怎么办|为什么|怎样|今天怎么样").containsMatchIn(text)

    private fun subject(text: String): String? = when {
        Regex("猫|猫咪").containsMatchIn(text) -> "cat"
        Regex("风景|公园|天空|晚霞|夕阳").containsMatchIn(text) -> "landscape"
        Regex("食物|美食|饭菜|甜点|早餐|午餐|晚餐").containsMatchIn(text) -> "food"
        Regex("生活照").containsMatchIn(text) -> "life"
        else -> null
    }
    private fun clauses(text: String) = text.take(10_000).split(Regex("[，。！？!?；;\r\n]"))
        .map(::compact).filter(String::isNotEmpty)
    private fun compact(text: String) = text.take(10_000).lowercase().replace(Regex("[\\s，。！？!?；;~～]"), "")
}
