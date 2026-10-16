package com.zhiyu.app.data.selfie

import android.content.Context
import android.util.AtomicFile
import com.zhiyu.app.data.*
import com.zhiyu.app.data.ocexpansion.OcFramingStore
import com.zhiyu.app.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Three durable opportunities per current OC/day/trajectory/crop. No credentials are stored here. */
class LocalSelfieBank(context: Context) {
    private val app = context.applicationContext
    private val assets = LocalMediaStore(app)
    private val visual = LocalVisualRepository(app)
    private val characters = OcCreatorStore(app)
    private val framing = OcFramingStore(app)
    private data class Lane(val generation: Int, val index: Int)
    private data class State(val scope: String, val lanes: List<Lane>, val usedSha: List<String> = emptyList())
    private data class Snapshot(val scope: String, val original: OcAssetRef, val referenceSha: String)

    fun targets(chat: String, profile: VisualProfile, scene: LocalDailyScene): List<LocalScenePrefetchTarget> =
        withSnapshot(chat, profile, scene) { snapshot ->
            if (snapshot == null) emptyList() else opportunities(scene, state(chat, snapshot.scope, scene))
        }

    fun current(chat: String, profile: VisualProfile, target: LocalScenePrefetchTarget): Boolean =
        target.selfie != null && targets(chat, profile, target.scene).any {
            it.selfie == target.selfie && it.index == target.index && it.slot == target.slot
        }

    fun acceptsPixels(chat: String, profile: VisualProfile, target: LocalScenePrefetchTarget, sha: String): Boolean =
        withSnapshot(chat, profile, target.scene) { snapshot ->
            ChatPhotoRules.digest(sha)
            snapshot != null && sha !in setOf(snapshot.original.sha256, snapshot.referenceSha) &&
                sha !in state(chat, snapshot.scope, target.scene).usedSha && current(chat, profile, target)
        }

    fun eligible(chat: String, item: LocalMediaDetails, profile: VisualProfile, scene: LocalDailyScene): Boolean =
        withSnapshot(chat, profile, scene) { snapshot ->
            snapshot != null && eligible(chat, item, profile, scene, snapshot, state(chat, snapshot.scope, scene)) != null
        }

    /** Advance before appending: a failed message save may consume a photo, but cannot resend it. */
    fun consume(chat: String, item: LocalMediaDetails, profile: VisualProfile, scene: LocalDailyScene): Boolean =
        withSnapshot(chat, profile, scene) { snapshot ->
            if (snapshot == null) return@withSnapshot false
            val before = state(chat, snapshot.scope, scene)
            val lane = eligible(chat, item, profile, scene, snapshot, before) ?: return@withSnapshot false
            require(before.usedSha.size < 128) { "本段轨迹的自拍已达到上限，请等待下一段轨迹" }
            val old = before.lanes[lane]
            require(old.generation < 1_000_000) { "自拍库存已达到本日上限" }
            val current = scene.slots.indexOf(scene.current).also { require(it >= 0) }
            val lanes = before.lanes.mapIndexed { index, value ->
                if (index == lane) Lane(value.generation + 1, current) else value
            }
            write(chat, before.copy(lanes = lanes, usedSha = before.usedSha + item.attachment.sha256))
            true
        }

    private fun eligible(chat: String, item: LocalMediaDetails, profile: VisualProfile, scene: LocalDailyScene,
        snapshot: Snapshot, state: State): Int? {
        val asset = item.attachment
        val tags = item.tags.toSet()
        if (item.purpose != "library" || asset.kind != "image" || asset.dayId != scene.dayId ||
            asset.visualVersion != profile.visualVersion || asset.sha256 in setOf(snapshot.original.sha256, snapshot.referenceSha) ||
            asset.sha256 in state.usedSha ||
            !tags.containsAll(listOf("selfie_stock", "contains_person", "remote_generated", "composite_reviewed", "decoded_final")) ||
            "local_placeholder" in tags || "daily_stock" in tags ||
            item.tags.singleOrNull { it.startsWith("remote_chat_") } != "remote_chat_${chat.take(24)}" ||
            item.tags.singleOrNull { it.startsWith("trajectory_") } != "trajectory_${LocalDailyRules.revision(scene).take(24)}" ||
            !OcFramingStockRules.permits(framing.active(chat, profile), tags, true)) return null
        return opportunities(scene, state).singleOrNull { target ->
            tags.containsAll(LocalSelfieRules.tags(checkNotNull(target.selfie))) && tags.containsAll(target.slot.tags) &&
                item.tags.singleOrNull { it.startsWith("slot_") } == "slot_${target.slot.time.replace(":", "")}"
        }?.selfie?.lane
    }

    private fun opportunities(scene: LocalDailyScene, state: State) = if (state.usedSha.size >= 128) emptyList() else state.lanes.mapIndexed { lane, value ->
        require(value.index in scene.slots.indices) { "自拍轨迹位置无效" }
        LocalScenePrefetchTarget(scene, value.index, scene.slots[value.index], false,
            LocalSelfieTicket(state.scope, lane, value.generation))
    }

    /** Lock order matches remote publication: media/profile, OC, persisted daily scene. */
    private fun <T> withSnapshot(chat: String, profile: VisualProfile, scene: LocalDailyScene, action: (Snapshot?) -> T): T =
        visual.withCurrentProfile(chat) { current ->
            characters.withCurrentSnapshot(chat) { saved ->
                MediaRules.path(chat)
                if (current.copy(generation = profile.generation) != profile || current.mode == "none" ||
                    current.visualVersion <= 0 || current.referenceId == null || scene.visualVersion != current.visualVersion)
                    return@withCurrentSnapshot action(null)
                val instant = sceneInstant(scene)
                LocalDailyRepository(app).use { daily ->
                    daily.withCurrentScene(chat, current.visualVersion, instant) { actual ->
                        if (LocalDailyRules.revision(actual) != LocalDailyRules.revision(scene)) action(null) else {
                            val identity = saved?.takeIf { it.identity.confirmed && it.identity.reference?.id == current.referenceId &&
                                it.identity.reference?.visualVersion == current.visualVersion }
                            action(snapshot(chat, current, scene, identity))
                        }
                    }
                }
            }
        }

    private fun snapshot(chat: String, profile: VisualProfile, scene: LocalDailyScene, identity: OcIdentitySnapshot?): Snapshot? {
        val reference = assets.details(checkNotNull(profile.referenceId)).attachment
        val policy = framing.active(chat, profile)
        val source = when {
            policy != null -> OcFramingRules.reference(policy)
            identity != null -> identity.identity.photoSource
            reference.sourceLabel in setOf("OC 人物预览", "OC 手动修整预览", "你制作的 OC") -> null
            else -> OcAssetRef(reference.id, reference.sha256, reference.visualVersion)
        } ?: return null
        assets.download(source.id, source.sha256)
        val fields = listOf("selfie-bank-v1", chat, LocalDailyRules.revision(scene),
            profile.visualVersion.toString(), profile.referenceId.orEmpty(), reference.sha256,
            source.id, source.sha256, identity?.packageSha.orEmpty(), policy?.let(OcFramingRules::hash).orEmpty())
        val scope = LocalMemoryCodec.sha(LocalMemoryCodec.bytes(buildString { fields.forEach { append(it.length).append(':').append(it) } }))
        return Snapshot(scope, source, reference.sha256)
    }

    private fun state(chat: String, scope: String, scene: LocalDailyScene): State {
        val before = read(chat)
        if (before?.scope == scope) return before
        val index = scene.slots.indexOf(scene.current).also { require(it >= 0) { "自拍日轨迹当前项无效" } }
        val created = State(scope, List(LocalSelfieRules.SIZE) { Lane(0, index) })
        write(chat, created)
        return created
    }
    private fun read(chat: String): State? {
        val file = path(chat)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val data = ByteArray(16_385)
            var count = 0
            while (count < data.size) {
                val read = input.read(data, count, data.size - count)
                if (read < 0) break
                require(read > 0) { "自拍库存读取失败" }
                count += read
            }
            require(count in 1..16_384) { "自拍库存记录损坏，已停止新生成" }
            data.copyOf(count)
        }
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val payload = envelope.getString("payload")
        require(LocalMemoryCodec.sha(LocalMemoryCodec.bytes(payload)) == envelope.getString("sha")) { "自拍库存完整性校验失败" }
        val value = JSONObject(payload)
        require(integer(value, "schema") == 1 && value.getString("chat") == chat) { "自拍库存归属无效" }
        val scope = value.getString("scope")
        val entries = value.getJSONArray("lanes")
        require(entries.length() == LocalSelfieRules.SIZE) { "自拍库存数量无效" }
        val lanes = (0 until entries.length()).map { lane ->
            val item = entries.getJSONObject(lane)
            val generation = integer(item, "generation"); val index = integer(item, "index")
            LocalSelfieRules.check(LocalSelfieTicket(scope, lane, generation))
            require(index in 0..23) { "自拍库存轨迹无效" }
            Lane(generation, index)
        }
        val used = value.getJSONArray("used_sha")
        require(used.length() in 0..128) { "自拍消费记录无效" }
        val usedSha = (0 until used.length()).map { used.getString(it).also(ChatPhotoRules::digest) }
        require(usedSha.distinct().size == usedSha.size) { "自拍消费记录重复" }
        return State(scope, lanes, usedSha)
    }
    private fun write(chat: String, state: State) {
        val value = JSONObject().put("schema", 1).put("chat", chat).put("scope", state.scope)
            .put("lanes", JSONArray(state.lanes.map { JSONObject().put("generation", it.generation).put("index", it.index) }))
            .put("used_sha", JSONArray(state.usedSha))
        val payload = value.toString()
        val envelope = JSONObject().put("payload", payload).put("sha", LocalMemoryCodec.sha(LocalMemoryCodec.bytes(payload)))
        LocalMediaStore.write(path(chat), envelope.toString().toByteArray(Charsets.UTF_8))
    }
    private fun integer(value: JSONObject, name: String): Int =
        (value.get(name) as? Int)?.also { require(it >= 0) { "自拍库存数值无效" } }
            ?: throw IllegalArgumentException("自拍库存数值无效")
    private fun path(chat: String): File {
        MediaRules.path(chat)
        return File(File(app.filesDir, "selfie_bank_v1"), "$chat.json")
    }
    private fun sceneInstant(scene: LocalDailyScene) = LocalDate.parse(scene.dayId).atTime(java.time.LocalTime.parse(scene.current.time))
        .atZone(ZoneId.of(LocalDailyRules.TIMEZONE)).toInstant()
}
