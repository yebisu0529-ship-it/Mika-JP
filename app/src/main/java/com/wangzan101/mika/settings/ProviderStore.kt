package com.wangzan101.mika.settings

import android.content.Context
import com.wangzan101.mika.core.agent.ContextLimits
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.chatgpt.ChatGptSession
import com.wangzan101.mika.core.chatgpt.ChatGptTokens
import com.wangzan101.mika.core.oauth.AccountSession
import com.wangzan101.mika.core.oauth.AccountTokens
import com.wangzan101.mika.core.oauth.OauthTokenResult
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.core.oauth.XaiAuth
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.ClientHeaders
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.ListFailure
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelCatalog
import com.wangzan101.mika.core.model.ModelEndpoint
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelListResult
import com.wangzan101.mika.core.model.ModelProtocol
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.ModelSession
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import com.wangzan101.mika.core.usage.UsageSource
import com.wangzan101.mika.core.usage.metered
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.wangzan101.mika.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * モデル アクセス: どのサービス プロバイダーに属するか ([providerId])、モデル名、および機能。インターフェイスのタイプ、アドレス、名前はサービス プロバイダーからコピーされます (モデルの構築時に使用されます)。
 * キーはサービス プロバイダーに保存されます。これはストレージ内のレイヤーです。ユーザーは「どのモデルをどの目的で使用するか」を選択するだけで、構成は選択したとおりに作成され、誰も使用しない場合は削除されます。
 */
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val baseUrl: String,
    val model: String,
    val capabilities: Capabilities,
    /** インターフェイスに表示される名前。 */
    val name: String = "",
    /** は [Provider] の ID に属します。空の場合、[ProviderStore.save] は、インターフェイスのタイプ、アドレス、名前に従って、対応するサービス プロバイダーに割り当てられます。 */
    val providerId: String = "",
) {
    /**
     * インターフェイス タイプ。 [OpenAiResponses] は、モデル リスト内のモデルの応答プロトコルを指定し、「OpenAI」を事前設定し、カスタム サービス プロバイダーを追加するときにそれを選択するときに使用されます。
     * [ChatGpt] は、ChatGPT アカウントを使用してログインし、パッケージ クォータを使用するものです。認証情報は API キーではなく、ログイン ([ChatGptSession] を参照) です。
     * [Xai] もアカウント ログイン (デバイス コード) であり、資格情報は API キーではなくログインであり、xAI 独自の応答インターフェイスを呼び出します。
     */
    enum class Kind {
        OpenAiCompat, Anthropic, Gemini, OpenAiResponses, ChatGpt, Xai;

        /**
         * このインターフェイス タイプは、リクエストがどのプロトコルを通じて送信されるべきかを示します。これを使用して、サポートされていないモデルを示すインターフェイスを除外します。[com.wangzan101.mika.ui.ModelSetupViewModel] を参照してください。
         * の試し通話。両方のアカウント (ChatGpt、
         * したがって、ここでは正しく書きたいだけであり、誰が審査されるかには影響しません。
         */
        val protocol: ModelProtocol
            get() = when (this) {
                OpenAiCompat -> ModelProtocol.ChatCompletions
                Anthropic -> ModelProtocol.Messages
                Gemini -> ModelProtocol.GenerateContent
                OpenAiResponses, ChatGpt, Xai -> ModelProtocol.Responses
            }

        companion object {
            /** モデルは、プロトコルに使用するインターフェイス タイプのアダプターを個別に指定します。 */
            fun of(protocol: ModelProtocol): Kind = when (protocol) {
                ModelProtocol.ChatCompletions -> OpenAiCompat
                ModelProtocol.Messages -> Anthropic
                ModelProtocol.GenerateContent -> Gemini
                ModelProtocol.Responses -> OpenAiResponses
            }

            /** カスタム サービス プロバイダーを追加する場合のオプションのインターフェイス タイプ (アドレスと API キーを入力)。応答は、README の順序に従って最後にリストされます。 */
            val withApiKey: List<Kind> = listOf(OpenAiCompat, Anthropic, Gemini, OpenAiResponses)
        }
    }
}

/**
 * 接続されたサービス プロバイダー: 名前、インターフェイス タイプ、アドレス。キーは暗号化され、個別に保存されます。
 * [models] は、最後にリストされたモデルです (「テストと保存」または更新で記録され、構成と同じファイルに保存されます)。
 * が null の場合は、まだリストされていないことを意味します。空のリストは、リストには登録されているが、サービスがリストを提供していない (またはリストが存在しない) ことを意味します。
 * [sessionHeader] は、会話セッション番号を必要とするリクエスト ヘッダー名 (デフォルト、[ProviderPreset.sessionHeader] を参照) です。そうでない場合は null です。
 * [verifiedModels] は、このトライアル コールで応答されたモデル名です ([ProviderStore.probe] を参照)。サービス プロバイダーはリストを提供せず、モデル名は手動で入力されます。
 * ここにない場合は検証されていません（古いバージョンは保存されておらず、当時リストを提供しなかったサービスプロバイダーはそれを呼び出そうとしませんでした）。
 * [typedModels] は、ユーザーが手動で入力し、トライアル コールを通過した後に追加されたモデル名です (リストにない、またはモデルがここにリストされません)。モデルを選択するときにリストを更新し、「手動入力」をマークします。
 * [savedUntested] は、ユーザーがテストをスキップして直接保存したものです ([ProviderStore.connect] を参照)。モデルはリストされておらず、呼び出しも試行されていません。
 * つまり、[models] は空のリストであり、[verifiedModels] も空ですが、「この会社はリストを提供していない」という議論は当てはまりません。
 * の後、モデルは 1 回リストに登録されるか、テストに 1 回合格した後にクリアされます ([ProviderStore.rememberModels]、[ProviderStore.markVerified] を参照)。
 */
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val models: List<ListedModel>? = null,
    val sessionHeader: String? = null,
    val verifiedModels: Set<String> = emptySet(),
    val typedModels: Set<String> = emptySet(),
    /**
     * キーの取得方法: アカウント認証のために交換されます (現在は [ProviderStore.OPENROUTER_SIGN_IN] のみ)。それはその会社のマークであり、ユーザーはnullを記入します。
     * マークされたサービスプロバイダーの詳細には、「キーの変更」ではなく、アカウントのログイン (ログアウトし、相手のページに移動してキーを削除) が表示されます。
     */
    val signIn: String? = null,
    val savedUntested: Boolean = false,
    /** はモデル名に従って保持され、用途を切り替えた後に取得できます。 */
    val contextSettings: Map<String, ModelContextSettings> = emptyMap(),
    /** ユーザーは、特定のモデルを写真で表示できるかどうか (モデル名で)、比率リストとプリセット初期値カウントの両方を表示できるかどうかを述べました。[ModelOptions.seesImages] を参照してください。 */
    val visionOverrides: Map<String, Boolean> = emptyMap(),
    /** 自己ホストのプリセットを本人が選択した意図。モデル系列や接続先の名前変更とは分けて保持する。 */
    val explicitSelfHosted: Boolean = false,
) {
    val key: String get() = ProviderConnection.keyOf(kind, baseUrl, name)

    /**
     * [model] はここのテストに合格していません。この会社には利用可能なリストがありません (提供されていないか、テストをスキップして保存するとリストに表示されません)。モデル名は手動で入力されており、リストにはありません。 内部は
     * [verifiedModels]。アカウントにログインしているモデル名は固定されており、カウントされません。サービスプロバイダーのページでは、使用中のそのようなモデルを「再テスト」します。
     */
    fun untested(model: String): Boolean =
        kind != ProviderConfig.Kind.ChatGpt && kind != ProviderConfig.Kind.Xai && models?.isEmpty() == true && model !in verifiedModels
}

/** 未指定のウィンドウはモデルの値、圧縮開始点は自動計算、読み取りタイムアウトは共通の120秒を使う。 */
data class ModelContextSettings(val windowTokens: Int? = null, val triggerTokens: Int? = null, val readTimeoutSeconds: Int? = null) {
    fun limits(modelWindow: Int): ContextLimits = ContextLimits.of(windowTokens ?: modelWindow, triggerTokens)

    fun validFor(modelWindow: Int): Boolean = (windowTokens == null || windowTokens > 0) &&
        (triggerTokens == null || (triggerTokens > 0 && triggerTokens == limits(modelWindow).trigger)) &&
        (readTimeoutSeconds == null || readTimeoutSeconds in 1..MAX_READ_TIMEOUT_SECONDS)

    companion object {
        // OkHttp は読み取りタイムアウトを Int のミリ秒で保持する。
        const val MAX_READ_TIMEOUT_SECONDS = Int.MAX_VALUE / 1000
    }
}

/** 目的に応じて何を選ぶか：特定のモデルを使用するか、メインモデルを使用するか、または使用しない（写真を見なければ使用できません）。 */
sealed interface RoleChoice {
    /** はモデル [configId] で構成されます。 */
    data class Model(val configId: String) : RoleChoice
    data object SameAsMain : RoleChoice
    data object Off : RoleChoice
}

/**
 * 3 つの用途のそれぞれに何を選択するかはユーザーによって明確に指定され、機能に基づくデフォルトの割り当てはありません。
 * [main] は、メイン モデルの構成 ID です。 null の場合は、選択されていないことを意味します。画像を表示する場合、デフォルトでは使用されません。軽量の場合、メイン モデルがデフォルトで使用されます。
 */
data class RoleChoices(
    val main: String? = null,
    val vision: RoleChoice = RoleChoice.Off,
    val light: RoleChoice = RoleChoice.SameAsMain,
    /** は用途に応じて保存されます。同じモデルでも目的に応じて異なるギアを選択できます。 「メインモデル」はそのギアとともにフォローされます。 */
    val reasoningEfforts: Map<ModelRole, String> = emptyMap(),
) {
    /** この目的のための選択。メインモデルが選択されていない場合は null になります。 */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** この目的で実際に使用される構成 ID: 「メイン モデルを使用」はメイン モデルを取得します。使用されていない場合、またはメイン モデルが選択されていない場合は null になります。 */
    fun resolve(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> main
        RoleChoice.Off, null -> null
    }

    /** 两个用途是否解析到同一条配置;任一用途没配时不算相同。 */
    fun sameModel(first: ModelRole, second: ModelRole): Boolean {
        val a = resolve(first) ?: return false
        return a == resolve(second)
    }

    /** 有用途在用的配置 id;其余的配置没人用,会被删掉。 */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet()

    /** 目的を変更します。この目的では選択は受け入れられません (メイン モデルは特定のモデルのみにすることができ、軽量モデルは使用できません)。null が返されます。 */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = (when (role) {
        ModelRole.Main -> (choice as? RoleChoice.Model)?.let { copy(main = it.configId) }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    })?.let { next ->
        if (get(role) == choice) next else next.copy(reasoningEfforts = reasoningEfforts - role)
    }

    fun reasoningEffort(role: ModelRole): String? = when (get(role)) {
        is RoleChoice.Model -> reasoningEfforts[role]
        RoleChoice.SameAsMain -> main?.let { reasoningEfforts[ModelRole.Main] }
        else -> null
    }

    /** [valid] 以外を指す選択は既定へ戻す: メインモデルは未選択になり、画像を見る用途は使わず、軽量用途はメインモデルを使う。 */
    fun keepOnly(valid: Set<String>): RoleChoices {
        fun RoleChoice.gone() = this is RoleChoice.Model && configId !in valid
        return RoleChoices(
            main = main?.takeIf { it in valid },
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
            reasoningEfforts = reasoningEfforts.filterKeys { role -> (get(role) as? RoleChoice.Model)?.configId in valid },
        )
    }
}

/**
 * モデルクライアントを作るときに追加で渡すもの: [headers] はすべてのリクエストに付ける（クライアント識別、セッション番号。[ClientHeaders] 参照）;
 * [maxOutputTokens] は返信の長さを制限する（接続テスト用）、null ならアダプタの既定; [reasoningEffort] はこの用途で選んだ推論の強さ;
 * [thinkingTypes] はモデル一覧が明記した、そのモデルが受け付ける思考方式（[ListedModel.thinkingTypes] 参照）、記載がなければ null。
 * [readTimeoutSeconds] はモデル別の読み取りタイムアウト。未指定なら共通の120秒を使う。
 */
data class ClientSetup(
    val headers: Map<String, String> = emptyMap(),
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val thinkingTypes: List<String>? = null,
    val readTimeoutSeconds: Int? = null,
)

/** 「テストして保存」テスト呼び出し ([ProviderStore.probe] を参照) の結果。 */
sealed interface ProbeResult {
    /** モデルは試用版ツールを呼び出します。インターフェイスが開いている場合は、ツールも呼び出されます。 */
    data object ToolCalled : ProbeResult
    /** インターフェイスは機能しますが、モデルは応答するだけでツールを呼び出しません。Mika はツールに依存して動作するため、このモデルはメイン モデルとして使用できません。 */
    data object NoToolCall : ProbeResult
    /** リクエストが失敗しました。 [error] によりサーバーからエラーが発生しました。 */
    data class Failed(val error: ModelError) : ProbeResult
}

/**
 * サービスプロバイダー、モデル構成へのアクセス、および使用方法の選択。構成は JSON ファイルとして保存され、API キーは [KeyCipher] によって暗号化されてから、サービス プロバイダーごとに 1 つのコピーとして別のファイルに保存されます。
 *
 * ユーザーには、どのサービス プロバイダーに接続するか、および各目的にどのモデルを使用するか ([RoleChoices]) という 2 つのレイヤーしかありません。モデル構成 ([ProviderConfig]) は内部の 1 つのレイヤーです。
 * はモデルの選択時にその場で作成され、使用されなくなった場合は削除されます ([prune] を参照。キーはサービス プロバイダーに従って保存され、構成を削除してもキーは削除されません)。
 * は能力マークに関係なく、目的が何であれ選択します。特定のモデルを選択する場合は、対応するマークを true としてマークします ([choose] を参照)。
 *
 * 古いバージョンのファイル: モデル設定とキーのみが設定 ID に従って保存されます。読み込む場合、[ProviderConnection.keyOf] に従ってサービス プロバイダーとして分類されます。
 * キーはグループ内のいずれかから取得され、構成 ID に従って保存されている元のキーはクリアされます ([migrate] を参照)。明示的に指定された用途のみが記録されます。
 * が読み取られると、その時点で実際に有効な割り当てが 1 回書き込まれます ([migrateRoles] を参照)。移行されたデータは再び移動されず、繰り返し実行しても副作用はありません。
 *
 * で使用されるモデルは、[modelFor] からのみエクスポートされます。エクスポートする前に、測定する必要があります ([metered] を参照)。各通話は [usage] に記録されます。
 * 誰が呼び出しても、何度呼び出しても、個別のアカウントを保持する必要はありません。
 */
class ProviderStore(
    dir: File,
    private val cipher: KeyCipher,
    private val usage: UsageSink = UsageSink { _, _ -> },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** モデルサービスに送る User-Agent。アプリは `Mika/<バージョン>` を送信する。 */
    private val userAgent: String = "Mika",
    /** 列モデル: 各リクエストによって伝送されるインターフェイス タイプ、アドレス、キー、ヘッダー。 */
    private val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** 「ChatGPT でログイン」認証サービス (トークン交換、更新、ログアウト)。 */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /** xAI サブスクリプション ログイン (デバイス コード、更新)。 */
    val xaiAuth: XaiAuth = defaultXaiAuth,
    /** OpenRouter アカウントの認証 (キーの変更)。 */
    val openRouterAuth: OpenRouterAuth = defaultOpenRouterAuth,
    /**
     * 呼び出し可能なモデルを作成します: 構成、キー、サービス プロバイダー リストでこのモデルに個別に指定されたインターフェイス (null でない場合は、構成されたインターフェイス タイプとアドレスに従って)、要求ヘッダーなど。
     * が null の場合、内蔵アダプターが使用されます (テストでは偽のアダプターに置き換えられます)。
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    private val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    /** ChatGPT サービス プロバイダーごとに 1 つのコピー: 同時にトークンを交換するのは 1 つだけです。 */
    private val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    /** サブスクリプション アカウントごとに 1 部。 ChatGPT と同様に、同時にトークンを交換できるのは 1 つだけです。 */
    private val accountTokens = mutableMapOf<String, AccountTokens>()
    private val configFile = File(dir, CONFIG_FILE)
    private val keyFile = File(dir, KEY_FILE)
    private val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** 接続されたサービス プロバイダー (接続順)。 */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val _roles = MutableStateFlow(RoleChoices())
    /** は 3 つの目的ごとに何を選択しますか。指摘された構成はすべて [configs] にあります。 */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    /** サービスプロバイダー ID → 暗号化されたキー。 */
    private var keys: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    /**
     * は、同じ ID を持つモデル構成を追加または置き換えます (新しい構成は [choose] に渡される必要があります。そうしないと、次回読み取られたときに誰も使用しなければ削除されます)。
     * 構成は、[ProviderConfig.providerId] で示されるサービス プロバイダーにリンクされます (インターフェイスの種類、アドレス、名前はサービス プロバイダーの影響を受けます)。
     * が指定されていないか、存在しません。インターフェイスのタイプ、アドレス、名前に基づいて、対応するサービス プロバイダーを検索するだけです。見つからない場合は、新しいものを作成します。
     * [apiKey] はそのサービス プロバイダーのキーです。null は変更がないことを意味し、空の文字列はクリアを意味します。
     */
    @Synchronized
    fun save(cfg: ProviderConfig, apiKey: String?) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }

    /** このモデルは、所属するサービスプロバイダーのキーを構成します。 */
    fun apiKey(configId: String): String? =
        _configs.value.firstOrNull { it.id == configId }?.let { providerKey(it.providerId) }

    /** サービスプロバイダーのキー。ロックを解除できない場合 (たとえば、キーストアがシステムによってクリアされた場合)、キーがないものとして扱われます。 */
    fun providerKey(providerId: String): String? {
        val enc = synchronized(this) { keys[providerId] } ?: return null
        return runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull()
    }

    /** このサービス プロバイダーはキーを保存したかどうか (ローカル サービスの場合は空白のままにすることができます)。 */
    fun hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

    /**
     * 「テストと保存」はサービス プロバイダーに接続します。プロバイダー (名前、インターフェイス タイプ、アドレス、キー) とリストされたモデルを保存します。
     * 同じインターフェイス タイプ、アドレス、名前を持つサービス プロバイダーがすでに存在します。繰り返し追加せずに、それを更新するだけです (キーを変更し、リストを変更します)。
     * [apiKey] が null の場合、既存のキーは変更されません。 [verified] は、この試用通話中に応答されたモデル名であり、[Provider.verifiedModels] に記録されます。
     * [typed] はユーザーによって手動で入力され、[Provider.typedModels] として記録されます。
     * [signIn] はキーの起源です ([Provider.signIn] を参照)。ユーザーが入力したキーは null であり、認可されたキーはその会社のマークです。
     * 2 つのソースは異なるサービス プロバイダーからのものです (アドレスと名前はマージされていません)。承認はユーザーが入力したキーを置き換えず、ログアウト時に承認されたキーのみが削除されます。
     * [untested] は、テストをスキップしたユーザーによって直接保存されました ([Provider.savedUntested] を参照)。モデルは 1 つもリストされておらず、それを呼び出す試みも行われませんでした。
     * にはすでにこれがあり、モデルとしてリストされているか、古いものとして検証されていますが、このパラメータのために未テストとして再マークされることはありません。
     */
    @Synchronized
    fun connect(
        name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
        sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
        verified: Set<String> = emptySet(),
        signIn: String? = null,
        typed: Set<String> = emptySet(),
        untested: Boolean = false,
        explicitSelfHosted: Boolean = false,
    ): Provider {
        val found = findOrAddProvider(name, kind, baseUrl, signIn)
        // テストをスキップするときに保存するリストがありません。以前にリストされたリストを保持してください。既存のリストを隠すために空のテーブルを使用しないでください。
        val listed = if (untested && models.isEmpty()) found.models ?: models else models
        val p = found.copy(
            models = listed, sessionHeader = sessionHeader,
            verifiedModels = found.verifiedModels + verified, typedModels = found.typedModels + typed,
            // は今回はテストされていません。この会社はモデルを検証しておらず、リストも作成していないため、「テストされていません」です。それ以外の場合は、テスト済みとみなされなければなりません。
            savedUntested = untested && verified.isEmpty() && found.verifiedModels.isEmpty() && listed.isEmpty(),
            explicitSelfHosted = found.explicitSelfHosted || explicitSelfHosted,
        )
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        repairUnavailableReasoningEfforts()
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
        return p
    }

    /** サービスプロバイダーがリストした最後のモデルに注目してください。リストに記載されている場合は、「テストされていない」が当てはまらないことを意味します (テストをスキップして保存した企業がここでマークをクリアします)。 */
    @Synchronized
    fun rememberModels(providerId: String, models: List<ListedModel>) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(models = models, savedUntested = false) else it
        }
        repairUnavailableReasoningEfforts()
        writeConfig()
    }

    /**
     * は [model] をメモし、このトライアル コールから応答を受け取りました ([Provider.verifiedModels] を参照)。
     * がテストに合格した場合、「テストされていない」という意味ではなくなります。テストをスキップして保存した会社はここでマークをクリアします - このモデルは元々
     * [Provider.verifiedModels] もクリアする必要があり、直接返すことはできません。
     */
    @Synchronized
    fun markVerified(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels && !p.savedUntested) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model, savedUntested = false) else it
        }
        writeConfig()
    }

    /** ユーザーが手動で入力した [model] を書き留めます ([Provider.typedModels] を参照)。 */
    @Synchronized
    fun markTyped(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.typedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(typedModels = it.typedModels + model) else it }
        writeConfig()
    }

    /** は、サービス プロバイダーのキーを置き換えます (これより下のすべてのモデルがこれを使用します)。 */
    @Synchronized
    fun updateProviderKey(providerId: String, apiKey: String) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }

    /**
     * サービスプロバイダーを削除します。モデル構成とその下のキーが一緒に削除されます。その目的を使用してデフォルトに戻します (メイン モデルは選択解除され、写真の表示には使用されなくなり、メイン モデルは軽く使用されます)。
     * インターフェイスでは、削除する前にどの使用を再選択する必要があるかについて説明しています。削除されたのは ChatGPT で、登録された client_id も忘れられました (ローカル ID は残りました)。
     * 次回ログインして再度登録すると、アカウントを変更できます。
     */
    @Synchronized
    fun removeProvider(providerId: String) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (providerId in keys) setKey(providerId, "")
        writeConfig()
    }

    /**
     * サービス プロバイダー [providerId] の下の [model] を [role] として使用します。このモデルはまだ設定されていないため、[caps] に従って作成し、それを [choose] に渡します。
     * は、サービスプロバイダーが存在しない場合に false を返します。
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val id = _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.id
            ?: newId().also { save(ProviderConfig(it, p.kind, p.baseUrl, model, caps, p.name, p.id), null) }
        return choose(role, RoleChoice.Model(id))
    }

    /**
     * 目的を設定します。能力マークに関係なく、ユーザーが選択したものを何でも設定します。特定のモデルをメイン モデルとして選択する場合、ツール呼び出しフラグを true としてマークします (ツール定義は実行時にそれに応じて送信されます)。
     * は保存されたピクチャーマークを変更しません。 [configFor] の現在の選択を押して押し出します。変更した場合は元に戻してください。使用されなくなった構成を削除します。
     * 構成が存在しない場合、またはこの目的でこの選択が受け入れられない場合は false を返します ([RoleChoices.with] を参照)。
     */
    @Synchronized
    fun choose(role: ModelRole, choice: RoleChoice): Boolean {
        if (choice is RoleChoice.Model && _configs.value.none { it.id == choice.configId }) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else if (role == ModelRole.Main) c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                else c
            }
        }
        _roles.value = next
        prune()
        writeConfig()
        return true
    }

    /** はこの目的のみを変更します。サポートされていないプロトコル、主要なモデルに従う使用、またはリストで明らかにサポートされていないギアは受け入れられません。 */
    @Synchronized
    fun setReasoningEffort(role: ModelRole, effort: String?): Boolean {
        if (_roles.value[role] !is RoleChoice.Model) return false
        val cfg = configFor(role) ?: return false
        val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
        val options = ReasoningOptions.of(cfg, listed)
        if (effort != null && (options == null || effort !in options.levels)) return false
        _roles.value = _roles.value.copy(reasoningEfforts =
            if (effort == null) _roles.value.reasoningEfforts - role else _roles.value.reasoningEfforts + (role to effort))
        writeConfig()
        return true
    }

    @Synchronized
    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val cfg = _configs.value.firstOrNull { it.id == configId } ?: return false
        val p = provider(cfg.providerId) ?: return false
        if (!settings.validFor(cfg.capabilities.contextTokens)) return false
        val saved = if (settings == ModelContextSettings()) p.contextSettings - cfg.model
            else p.contextSettings + (cfg.model to settings)
        val updated = _providers.value.map { if (it.id == p.id) p.copy(contextSettings = saved) else it }
        try {
            writeAtomically(configFile, configJson(updated))
        } catch (_: Exception) {
            return false
        }
        _providers.value = updated
        return true
    }

    /** [model] が写真を表示できるかどうかについてユーザーが言ったことを書き留めます ([Provider.visionOverrides] を参照)。サービスプロバイダーが存在しない場合は false を返します。 */
    @Synchronized
    fun setModelVision(providerId: String, model: String, sees: Boolean): Boolean {
        val p = provider(providerId) ?: return false
        _providers.value = _providers.value.map { if (it.id == p.id) p.copy(visionOverrides = p.visionOverrides + (model to sees)) else it }
        writeConfig()
        return true
    }

    /** 表示モデルの「表示位置が確認されました」(開発者向けオプションで変更);メインモデルを表示すると、メインモデルの構成が変更されます。モデルを表示していないときは動きません。 */
    @Synchronized
    fun setVisionGroundingVerified(verified: Boolean) {
        val id = _roles.value.resolve(ModelRole.Vision) ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id == id) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }

    /**
     * この目的に使用される実際の構成。選択されていないか使用されていない場合は null。保存されたリスト データが書き込まれているかどうかに関係なく、クリックすると画像マークが表示され、押し出されます。
     * は、画像を表示する場合は常に true (ユーザーが画像を表示するために選択します)。他の用途では、[ModelOptions.seesImages] (このモデルに関してユーザーが発言した内容) が適用されます。
     * そこまでは言っていませんが、この構成で写真を閲覧する場合（同じモデルを選択する、または「メインモデルを使用する」）でも閲覧できます。スクリーンショットは直接添付されるため、画像を再度調整する必要はありません。
     */
    fun configFor(role: ModelRole): ProviderConfig? {
        val choices = _roles.value
        val cfg = choices.resolve(role)?.let { id -> _configs.value.firstOrNull { it.id == id } } ?: return null
        val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
            provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = choices.resolve(ModelRole.Vision) == cfg.id)
        return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
    }

    /** メイン モデルは、そのモデルがテストに合格しなかった場合にそのモデルを提供します ([Provider.untested] を参照)。モデルが原因でタスクが失敗した場合は、このプロンプトを使用して「再テスト」します。 */
    fun untestedMainProvider(): Provider? {
        val cfg = configFor(ModelRole.Main) ?: return null
        return provider(cfg.providerId)?.takeIf { it.untested(cfg.model) }
    }

    /**
     * この目的に使用されるモデル、測定値を設定します。選択されていないか使用されていない場合は、null になります。使用状況は[role]という名前で記録されます：画像閲覧と光通話は「メインモデルを使用」
     * は画像として記録され、軽量、サービスプロバイダーとモデルがメインモデル用に構成されています。これは、アプリで呼び出し可能なモデルを構築するための唯一の入り口です。
     * [session] は、このコールが属する会話です (同じ会話で構築されたモデルはすべて同じものに与えられます)。サービスプロバイダーは会話ヘッダーを宣言し、それをすべてのリクエストに含めます。
     */
    fun modelFor(role: ModelRole, session: ModelSession = ModelSession.fresh()): ChatModel? = configFor(role)?.let { cfg ->
        val p = provider(cfg.providerId)
        val source = UsageSource(cfg.providerId, p?.name ?: cfg.name, cfg.model, UsagePurpose.of(role))
        val listed = listedOf(cfg)
        val settings = p?.contextSettings?.get(cfg.model)
        val configured = if (settings == null) cfg else cfg.copy(capabilities = cfg.capabilities.copy(
            contextTokens = settings.windowTokens ?: cfg.capabilities.contextTokens,
            compactionTriggerTokens = settings.triggerTokens,
        ))
        modelFactory(configured, apiKey(cfg.id), listed?.endpoint, ClientSetup(
            headers(p?.sessionHeader, session), reasoningEffort = _roles.value.reasoningEffort(role),
            thinkingTypes = listed?.thinkingTypes, readTimeoutSeconds = settings?.readTimeoutSeconds,
        )).metered(source, usage)
    }

    /**
     * 「テストして保存」トライアル コール: サービス プロバイダーはまだ保存しておらず、実際のタスクの同じパス ([modelFactory]、同じリクエスト ヘッダー) に従って [model] を構築しました。
     * はパラメータなしのツール ([PROBE_TOOL]) を取得して呼び出します。応答は非常に短い時間に制限されます (プロトコルが許可する場合は、[buildModel] を参照)。
     * はツール呼び出しを [ProbeResult.ToolCalled] として返し、メッセージのみ [ProbeResult.NoToolCall] として返しました。リクエストは失敗し、サーバーエラーがそのまま返されました。
     * 未測定: これらのトークンはどの目的にも属しません。どの会話にも属さない新しいセッション番号を使用してください。タイムアウトは呼び出し元によって管理されます。
     */
    suspend fun probe(
        kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    ): ProbeResult {
        val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true))
        val m = modelFactory(cfg, apiKey, endpoint, ClientSetup(headers(sessionHeader, ModelSession.fresh()), PROBE_MAX_TOKENS))
        return try {
            val reply = m.complete(PROBE_SYSTEM, listOf(Message(Role.User, listOf(Part.Text(PROBE_TEXT)), Author.Mika)), listOf(PROBE_TOOL))
            if (reply.parts.any { it is Part.ToolCall }) ProbeResult.ToolCalled else ProbeResult.NoToolCall
        } catch (e: ModelError) {
            ProbeResult.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failed(ModelError.BadResponse(e.message.orEmpty(), e))
        }
    }

    private fun headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

    /** どの会話にも属さないリクエスト (音声転写など) は、この会社にヘッダー (クライアント ID) をもたらし、セッション ヘッダーで新しいセッション番号を宣言します。 */
    fun clientHeaders(p: Provider): Map<String, String> = headers(p.sessionHeader, ModelSession.fresh())

    /** このモデルは、サービス プロバイダーによって最後にリストされたものです (個別に指定されたインターフェイス、記述された思考方法など)。モデルを構築するたびにそれを確認し、再リストされた場合は新しいモデルを使用してください。 */
    private fun listedOf(cfg: ProviderConfig): ListedModel? =
        provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }

    /** このサービスプロバイダーで利用可能なモデルをリストします ([ModelCatalog] を参照)。どのセッションにも属さない場合は、新しいセッション番号を使用してセッション ヘッダーを宣言します。 */
    suspend fun listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult =
        modelLister(kind, baseUrl, apiKey, headers(sessionHeader, ModelSession.fresh()))

    /** 接続されているサービス プロバイダーのモデルを再リストします。保存されたキーを使用します。サブスクリプションのログインにはアクセス トークンを使用します (期限切れが近づいている場合は、新しいものに置き換えます)。 */
    suspend fun listModels(p: Provider): ModelListResult {
        if (p.kind != ProviderConfig.Kind.ChatGpt && p.kind != ProviderConfig.Kind.Xai) {
            return listModels(p.kind, p.baseUrl, providerKey(p.id), p.sessionHeader)
        }
        val token = try {
            if (p.kind == ProviderConfig.Kind.ChatGpt) tokensFor(p.id).get(fresh = false) else accountTokensFor(p.id).get(fresh = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ModelError.SignInExpired) {
            return ModelListResult.Failed(ListFailure.Auth)
        } catch (e: ModelError) {
            return ModelListResult.Failed(if (e is ModelError.Network || e is ModelError.Proxy) ListFailure.of(e) else ListFailure.Server)
        }
        val base = provider(p.id)?.baseUrl ?: p.baseUrl
        return listModels(p.kind, base, token, p.sessionHeader)
    }

    // ---------- ChatGPT ログイン ----------

    /** ChatGPT サービス プロバイダーのログイン (API キーと同じ場所に存在し、暗号化されます)。利用できない場合、または復号化できない場合は、null になります。 */
    fun chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

    /** ChatGPT サービス プロバイダー (1 つだけ) に接続しました。 */
    fun chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

    /** ログイン成功: ログインとリストされたモデルを保存します。既存の ChatGPT サービス プロバイダーがある場合は、それを更新します。 */
    @Synchronized
    fun connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider =
        connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models)

    /**
     * ログアウト: まずサーバー上のリフレッシュ トークンをログアウトし (確認できない場合でもクリアします)、次にローカル トークンをクリアします。登録 (client_id) とアカウント番号を保存し、再ログインするときに使用します。
     * は、サーバーがログアウトを確認したかどうかを返します。サービスプロバイダーとそのモデルおよび使用法は移動されません。
     */
    suspend fun signOutChatGpt(providerId: String): Boolean {
        val s = chatGptSession(providerId) ?: return true
        val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
        saveChatGptSession(providerId, s.signedOut())
        return revoked
    }

    /**
     * ChatGPT 上のこのデバイスのローカル ID と登録された client_id。識別子は初めて使用されるときに生成され、その後は変更されません。登録後に client_id を書き留めます。
     * は、ログアウト後も保持します (再ログイン時に使用され、重複登録は行われません)。ChatGPT サービス プロバイダーを削除すると忘れられます。どちらもキーではなく、クリア テキストで保存されます。
     */
    @Synchronized
    fun chatGptRegistration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    /** 登録された client_id を書き留めます (トークンを変更する前にメモしてください。変更が失敗した場合、次回は再登録する必要はありません)。 */
    @Synchronized
    fun rememberChatGptClient(clientId: String) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }

    @Synchronized
    private fun saveChatGptSession(providerId: String, session: ChatGptSession) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }

    private fun tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
        chatGptTokens.getOrPut(providerId) {
            ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
        }
    }

    // ---------- xAI アカウント ログイン ----------

    /** サブスクリプション アカウントのログイン (API キーと同じ場所に存在し、暗号化されます)。利用できない場合、またはロックを解除できない場合は、null になります。 */
    fun accountSession(providerId: String): AccountSession? = AccountSession.fromJson(providerKey(providerId))

    fun xaiProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.Xai }

    /**
     * ログイン成功: ログインとリストされたモデルを保存します。同じアカウントでログインした場合、サービスプロバイダーは 1 つだけとなり、再ログイン時に更新されます。
     */
    @Synchronized
    fun connectAccount(session: AccountSession, models: List<ListedModel>): Provider {
        val (name, kind, base) = when (session.provider) {
            XaiAuth.PROVIDER -> Triple(XAI_NAME, ProviderConfig.Kind.Xai, XaiAuth.API_BASE)
            else -> throw IllegalArgumentException("unknown account provider")
        }
        val existing = _providers.value.firstOrNull { it.kind == kind }
        if (existing == null) return connect(name, kind, base, session.toJson(), models)
        val p = existing.copy(name = name, baseUrl = base, models = models)
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        _configs.value = _configs.value.map { if (it.providerId == p.id) it.copy(baseUrl = base, name = name) else it }
        setKey(p.id, session.toJson())
        writeConfig()
        return p
    }

    /** ログアウト: この電話のトークンのみをクリアしてください。 xAIの設定でご自身でxAIアカウントの認証を解除する必要があります。 */
    @Synchronized
    fun signOutAccount(providerId: String) {
        val s = accountSession(providerId) ?: return
        saveAccount(providerId, s.signedOut())
    }

    @Synchronized
    private fun saveAccount(providerId: String, session: AccountSession) {
        if (provider(providerId) == null) return
        setKey(providerId, session.toJson())
        val base = session.apiBase
        if (base.isNotBlank() && provider(providerId)?.baseUrl != base) {
            _providers.value = _providers.value.map { if (it.id == providerId) it.copy(baseUrl = base) else it }
            _configs.value = _configs.value.map { if (it.providerId == providerId) it.copy(baseUrl = base) else it }
            writeConfig()
        }
    }

    // ---------- OpenRouter アカウントでログイン ---------- 1 つの

    /** には、OpenRouter アカウントを使用して接続することが許可されています。そうでない場合は null です。 */
    fun openRouterAccount(): Provider? = _providers.value.firstOrNull { it.signIn == OPENROUTER_SIGN_IN }

    /**
     * 認証がキーに変更されました: OpenRouter を押すと、デフォルトで共通の OpenAI 互換サービス プロバイダーが設定され (キーは通常どおり暗号化されます)、認証済みとしてマークされます。
     * 承認された方が更新します。ユーザーが自分でKeyを入力するOpenRouterは動かず、両社が共存します。
     */
    @Synchronized
    fun connectOpenRouter(apiKey: String, models: List<ListedModel>): Provider =
        connect(OPENROUTER_NAME, ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, apiKey, models, signIn = OPENROUTER_SIGN_IN)

    /** OpenRouter を終了してログインします。この電話機のキーを削除します。サービスプロバイダー、モデル、目的を保持します。 OpenRouter のキーを使用するには、ユーザーが [キー] ページでキーを削除する必要があります。 */
    @Synchronized
    fun signOutOpenRouter(providerId: String) {
        if (provider(providerId)?.signIn == OPENROUTER_SIGN_IN && providerId in keys) setKey(providerId, "")
    }

    /**
     * この期間中に保存されたログインが変更されていない場合、トークンを更新して書き戻します。トークンの変更にはネットワークを待つ必要があり、ユーザーがログアウトまたは再ログインした可能性があります。
     * 古い更新結果は上書きできません (そうしないと、ログアウト後に自動的に再ログインされるか、新しいログインが古いトークンで上書きされます)。
     */
    @Synchronized
    private fun saveRefreshed(providerId: String, before: AccountSession, after: AccountSession) {
        val now = accountSession(providerId) ?: return
        if (now.refreshToken != before.refreshToken || now.accessToken != before.accessToken) return
        saveAccount(providerId, after)
    }

    private fun accountTokensFor(providerId: String): AccountTokens = synchronized(accountTokens) {
        accountTokens.getOrPut(providerId) {
            AccountTokens(
                account = XAI_ACCOUNT,
                refresh = { session ->
                    when (session.provider) {
                        XaiAuth.PROVIDER -> xaiAuth.refresh(session)
                        else -> OauthTokenResult.Rejected("unknown", terminal = true)
                    }
                },
                load = { accountSession(providerId) },
                save = { before, after -> saveRefreshed(providerId, before, after) },
            )
        }
    }

    /** モデルがインターフェースを個別に指定している場合は、そのプロトコルに従ってアダプターを選択し、そのアドレスを使用します。それ以外の場合は、設定されたインターフェイスのタイプとアドレスを使用します。キーはすべてサービス プロバイダーから提供されます。 */
    /**
     * は同じ 4 つのプロトコルを要求します。返信の上限 ([ClientSetup.maxOutputTokens]) は、メッセージ (必須) とgenerateContent のみに適用されます。
     * チャット完了は表示されません (一部のモデルは `max_tokens` のみを認識し、一部のモデルは `max_completion_tokens` のみを認識します。エラーが表示された場合、リクエスト全体が拒否されます。
     * は接続テスト中に誤検知を返します)、応答は返されません (`max_output_tokens` は ChatGPT パッケージでは受け入れられません)。
     * [ClientSetup.readTimeoutSeconds] の指定時は接続プールとプロキシを共有するクライアントを派生させる。
     * 未指定なら共通の120秒を使い、他のモデルの設定には影響しない。
     */
    private fun buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
        val (kind, adapterBase) = adapterFor(cfg, endpoint)
        // サブスクリプションのログインでは、サービス プロバイダーの現在のアドレスが使用されます (ログイン時に変更されている可能性があります)。設定は追加時に保存されます。
        val baseUrl = if (cfg.kind == ProviderConfig.Kind.Xai) {
            provider(cfg.providerId)?.baseUrl?.takeIf { it.isNotBlank() } ?: adapterBase
        } else adapterBase
        val defaultHttp = http
        val http = setup.readTimeoutSeconds
            ?.takeIf { it in 1..ModelContextSettings.MAX_READ_TIMEOUT_SECONDS }
            ?.let { seconds -> defaultHttp.newBuilder().readTimeout(seconds.toLong(), TimeUnit.SECONDS).build() }
            ?: defaultHttp
        return when (kind) {
            ProviderConfig.Kind.OpenAiCompat ->
                OpenAiCompatModel(baseUrl, apiKey, cfg.model, cfg.capabilities, http, endpoint?.reasoningField, setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.Anthropic -> AnthropicModel(
                baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http,
                maxTokens = setup.maxOutputTokens,
                headers = setup.headers, reasoningEffort = setup.reasoningEffort, thinkingTypes = setup.thinkingTypes,
            )
            ProviderConfig.Kind.Gemini ->
                GeminiModel(baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http, setup.headers, setup.maxOutputTokens,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.OpenAiResponses ->
                ResponsesModel(baseUrl, BearerToken.fixed(apiKey), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.ChatGpt ->
                ResponsesModel(baseUrl, tokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, chatGptPlan = true, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.Xai ->
                ResponsesModel(baseUrl, accountTokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort, accountLogin = XAI_ACCOUNT)
        }
    }

    private fun findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String, signIn: String? = null, explicitSelfHosted: Boolean = false): Provider {
        val key = ProviderConnection.keyOf(kind, baseUrl, name)
        _providers.value.firstOrNull { it.key == key && it.signIn == signIn }?.let { return it }
        val p = Provider(newId(), name.trim(), kind, baseUrl.trim(), signIn = signIn, explicitSelfHosted = explicitSelfHosted)
        _providers.value = _providers.value + p
        return p
    }

    private fun setKey(providerId: String, apiKey: String) {
        keys = if (apiKey.isEmpty()) keys - providerId else keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
        writeAtomically(keyFile, keysJson())
    }

    /** 目的に使用されていないモデル構成を削除します。キーはサービス プロバイダーに存在し、影響を受けません。 */
    private fun prune() {
        val used = _roles.value.referenced
        _configs.value = _configs.value.filter { it.id in used }
    }

    private fun load() {
        if (configFile.isFile) runCatching {
            val root = json.parseToJsonElement(configFile.readText()).jsonObject
            _providers.value = root["services"]?.jsonArray?.mapNotNull { runCatching { providerOf(it.jsonObject) }.getOrNull() }.orEmpty()
            _configs.value = root["providers"]?.jsonArray?.mapNotNull { runCatching { configOf(it.jsonObject) }.getOrNull() }.orEmpty()
            val uses = root["uses"]?.jsonObject
            _roles.value = if (uses != null) choicesOf(uses) else migrateRoles(root["roles"]?.jsonObject)
        }
        if (keyFile.isFile) runCatching {
            keys = json.parseToJsonElement(keyFile.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        }
        migrate()
        // 存在しない構成を指す選択はデフォルトに戻り、誰も使用していない構成は削除されます。ファイル内の内容と異なる場合にのみ書き戻されます (新しい移行の目的を含む)。
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        repairUnavailableReasoningEfforts()
        prune()
        if (configFile.isFile) {
            val text = configJson()
            if (runCatching { configFile.readText() }.getOrNull() != text) writeAtomically(configFile, text)
        }
    }

    /**
     * `roles` の古いバージョンは、明示的に指定された構成 ID のみを記憶し、指定されていない構成 ID はデフォルトのルールに従って割り当てられます。メイン モデルが最初の構成を取得し、メイン モデルが画像を表示できる場合は、その構成も担当します。
     * 軽量フォローメインモデル。ここでは、その時点で実際に有効となる割り当ては [RoleChoices] と記述されており、暗黙のルールはなくなります。
     */
    private fun migrateRoles(old: JsonObject?): RoleChoices {
        val ids = _configs.value.map { it.id }.toSet()
        fun explicit(role: ModelRole) = old?.get(role.name)?.jsonPrimitive?.content?.takeIf { it in ids }
        val main = explicit(ModelRole.Main) ?: _configs.value.firstOrNull()?.id
        val mainSees = _configs.value.firstOrNull { it.id == main }?.capabilities?.vision == true
        return RoleChoices(
            main = main,
            vision = explicit(ModelRole.Vision)?.let(RoleChoice::Model) ?: if (mainSees) RoleChoice.SameAsMain else RoleChoice.Off,
            light = explicit(ModelRole.Light)?.let(RoleChoice::Model) ?: RoleChoice.SameAsMain,
        )
    }

    /**
     * サービス プロバイダーの下で未所有のモデル構成 (古いバージョン ファイル) をグループ化します。[ProviderConnection.keyOf] ごとにグループ化し、グループごとに 1 つのサービス プロバイダーを指定します。
     * Key: 構成 ID に従って保存されているグループ内のいずれかを取得します (暗号文はそのまま移行されます)。まず新しいキー ファイルと古いキー ファイルを書き込み、次に設定を書き込み、最後にどのサービス プロバイダーにも属さないキーをクリアします。
     * が途中で切断された場合、所有されていない構成は次回の読み取り時にまだ存在しており、再実行されます。すべてが完了したら、残りのキーをクリーンアップするだけです。
     */
    private fun migrate() {
        val known = _providers.value.map { it.id }.toSet()
        val orphans = _configs.value.filter { it.providerId !in known }
        if (orphans.isNotEmpty()) {
            val attachedTo = mutableMapOf<String, Provider>()
            orphans.groupBy { ProviderConnection.keyOf(it.kind, it.baseUrl, ProviderConnection.displayName(it)) }.values.forEach { group ->
                val first = group.first()
                val name = ProviderConnection.displayName(first)
                val p = findOrAddProvider(name, first.kind, first.baseUrl,
                    explicitSelfHosted = ProviderPresets.legacySelfHostedConnection(name, first.kind, first.baseUrl))
                group.forEach { attachedTo[it.id] = p }
                if (p.id !in keys) group.firstNotNullOfOrNull { keys[it.id] }?.let { keys = keys + (p.id to it) }
            }
            writeAtomically(keyFile, keysJson())
            _configs.value = _configs.value.map { c ->
                attachedTo[c.id]?.let { p -> c.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name) } ?: c
            }
            writeConfig()
        }
        val ids = _providers.value.map { it.id }.toSet()
        if (keys.keys.any { it !in ids }) {
            keys = keys.filterKeys { it in ids }
            writeAtomically(keyFile, keysJson())
        }
    }

    /**
     * 模型目录或客户端认识的档位变化后,旧选择不能继续原样发给服务端:能确定当前选项时,失效项退回默认;
     * 没有档位元数据、协议本就不提供这项设置的旧配置不动,避免把仍可由服务端处理的值误删。
     */
    private fun repairUnavailableReasoningEfforts() {
        val repaired = _roles.value.reasoningEfforts.mapNotNull { (role, effort) ->
            val cfg = configFor(role) ?: return@mapNotNull null
            val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
            val options = ReasoningOptions.of(cfg, listed)
            if (options == null || effort in options.levels) role to effort else null
        }.toMap()
        _roles.value = _roles.value.copy(reasoningEfforts = repaired)
    }

    private fun providerOf(o: JsonObject) = Provider(
        id = o.str("id"),
        name = o.str("name"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        // この項目は古いバージョンでは保存されていません。アドレスに一致するデフォルトのアドレスに従って入力してください（ファイルを読み取った後に書き戻すときに注意してください）
        sessionHeader = o["sessionHeader"]?.jsonPrimitive?.content ?: ProviderPresets.forBaseUrl(o.str("baseUrl"))?.sessionHeader,
        signIn = o["signIn"]?.jsonPrimitive?.content,
        verifiedModels = o["verifiedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        contextSettings = (o["contextSettings"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val settings = ModelContextSettings(entry.positiveInt("windowTokens"), entry.positiveInt("triggerTokens"),
                entry.positiveInt("readTimeoutSeconds"))
            if (settings == ModelContextSettings()) null else model to settings
        }.toMap(),
        visionOverrides = (o["visionOverrides"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            (raw as? JsonPrimitive)?.booleanOrNull?.let { model to it }
        }.toMap(),
        typedModels = o["typedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        // 旧バージョンにはそのような項目は保存されていません。当時はどの企業も「テストと保存」を経ており、テスト済みとして計算されていました。
        savedUntested = o["untested"]?.jsonPrimitive?.booleanOrNull ?: false,
        explicitSelfHosted = if ("explicitSelfHosted" in o) (o["explicitSelfHosted"] as? JsonPrimitive)?.booleanOrNull ?: false
            else ProviderPresets.legacySelfHostedConnection(o.str("name"), ProviderConfig.Kind.valueOf(o.str("kind")), o.str("baseUrl")),
        models = o["models"]?.jsonArray?.map { e ->
            val m = e.jsonObject
            ListedModel(
                id = m.str("id"),
                displayName = m["displayName"]?.jsonPrimitive?.content.orEmpty(),
                toolCalls = m["toolCalls"]?.jsonPrimitive?.booleanOrNull,
                vision = m["vision"]?.jsonPrimitive?.booleanOrNull,
                contextTokens = m["contextTokens"]?.jsonPrimitive?.intOrNull,
                reasoning = m["reasoning"]?.jsonPrimitive?.booleanOrNull,
                reasoningEfforts = m["reasoningEfforts"]?.jsonArray?.map { it.jsonPrimitive.content },
                thinkingTypes = m["thinkingTypes"]?.jsonArray?.map { it.jsonPrimitive.content },
                chat = m["chat"]?.jsonPrimitive?.booleanOrNull,
                endpoint = (m["api"] as? JsonObject)?.let { a ->
                    ModelProtocol.of(a["protocol"]?.jsonPrimitive?.content)?.let { protocol ->
                        ModelEndpoint(protocol, a.str("baseUrl"), a["reasoningField"]?.jsonPrimitive?.content)
                    }
                },
                unsupportedApis = m["unsupportedApis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                protocols = m["protocols"]?.jsonArray?.mapNotNull { ModelProtocol.of(it.jsonPrimitive.content) }?.takeIf { it.isNotEmpty() },
            )
        },
    )

    private fun configOf(o: JsonObject) = ProviderConfig(
        id = o.str("id"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        model = o.str("model"),
        capabilities = Capabilities(
            toolCalls = o["toolCalls"]!!.jsonPrimitive.boolean,
            vision = o["vision"]!!.jsonPrimitive.boolean,
            contextTokens = o["contextTokens"]!!.jsonPrimitive.int,
            reasoning = o["reasoning"]!!.jsonPrimitive.boolean,
            groundingVerified = o["groundingVerified"]?.jsonPrimitive?.boolean ?: false,
            promptCaching = PromptCaching.of(o["promptCaching"]?.jsonPrimitive?.content),
        ),
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        providerId = o["providerId"]?.jsonPrimitive?.content.orEmpty(),
    )

    /** `uses` の中の1用途: `{"use":"model","config":id}`、`{"use":"main"}` または `{"use":"none"}`; 認識できないものは既定として扱う。 */
    private fun choicesOf(o: JsonObject): RoleChoices {
        fun choice(role: ModelRole): RoleChoice? {
            val c = runCatching { o[role.name]?.jsonObject }.getOrNull() ?: return null
            return when (c["use"]?.jsonPrimitive?.content) {
                USE_MODEL -> c["config"]?.jsonPrimitive?.content?.let(RoleChoice::Model)
                USE_MAIN -> RoleChoice.SameAsMain
                USE_NONE -> RoleChoice.Off
                else -> null
            }
        }
        var r = RoleChoices()
        ModelRole.entries.forEach { role -> choice(role)?.let { c -> r.with(role, c)?.let { r = it } } }
        return r.copy(reasoningEfforts = ModelRole.entries.mapNotNull { role ->
            val effort = runCatching { o[role.name]?.jsonObject?.get("reasoningEffort")?.jsonPrimitive?.content }.getOrNull()
            if (r[role] is RoleChoice.Model && !effort.isNullOrBlank()) role to effort else null
        }.toMap())
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.positiveInt(k: String) = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private fun writeConfig() = writeAtomically(configFile, configJson())

    private fun configJson(providers: List<Provider> = _providers.value): String {
        val root = buildJsonObject {
            putJsonArray("services") {
                providers.forEach { p ->
                    addJsonObject {
                        put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("baseUrl", p.baseUrl)
                        put("explicitSelfHosted", p.explicitSelfHosted)
                        p.sessionHeader?.let { put("sessionHeader", it) }
                        p.signIn?.let { put("signIn", it) }
                        if (p.savedUntested) put("untested", true)
                        if (p.verifiedModels.isNotEmpty()) putJsonArray("verifiedModels") { p.verifiedModels.forEach { add(it) } }
                        if (p.contextSettings.isNotEmpty()) putJsonObject("contextSettings") {
                            for ((model, settings) in p.contextSettings) putJsonObject(model) {
                                settings.windowTokens?.let { put("windowTokens", it) }
                                settings.triggerTokens?.let { put("triggerTokens", it) }
                                settings.readTimeoutSeconds?.let { put("readTimeoutSeconds", it) }
                            }
                        }
                        if (p.visionOverrides.isNotEmpty()) putJsonObject("visionOverrides") {
                            for ((model, sees) in p.visionOverrides) put(model, sees)
                        }
                        if (p.typedModels.isNotEmpty()) putJsonArray("typedModels") { p.typedModels.forEach { add(it) } }
                        p.models?.let { models ->
                            putJsonArray("models") {
                                models.forEach { m ->
                                    addJsonObject {
                                        put("id", m.id)
                                        if (m.displayName.isNotEmpty()) put("displayName", m.displayName)
                                        m.toolCalls?.let { put("toolCalls", it) }
                                        m.vision?.let { put("vision", it) }
                                        m.contextTokens?.let { put("contextTokens", it) }
                                        m.reasoning?.let { put("reasoning", it) }
                                        m.reasoningEfforts?.let { levels -> putJsonArray("reasoningEfforts") { levels.forEach { add(it) } } }
                                        m.thinkingTypes?.let { types -> putJsonArray("thinkingTypes") { types.forEach { add(it) } } }
                                        m.chat?.let { put("chat", it) }
                                        m.endpoint?.let { e ->
                                            putJsonObject("api") {
                                                put("protocol", e.protocol.wire); put("baseUrl", e.baseUrl)
                                                e.reasoningField?.let { put("reasoningField", it) }
                                            }
                                        }
                                        if (m.unsupportedApis.isNotEmpty()) putJsonArray("unsupportedApis") { m.unsupportedApis.forEach { add(it) } }
                                        m.protocols?.takeIf { it.isNotEmpty() }?.let { ps -> putJsonArray("protocols") { ps.forEach { add(it.wire) } } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("providers") {
                _configs.value.forEach { c ->
                    addJsonObject {
                        put("id", c.id); put("providerId", c.providerId); put("name", c.name); put("kind", c.kind.name)
                        put("baseUrl", c.baseUrl); put("model", c.model)
                        put("toolCalls", c.capabilities.toolCalls); put("vision", c.capabilities.vision)
                        put("contextTokens", c.capabilities.contextTokens); put("reasoning", c.capabilities.reasoning)
                        put("groundingVerified", c.capabilities.groundingVerified)
                        put("promptCaching", c.capabilities.promptCaching.wire)
                    }
                }
            }
            putJsonObject("uses") {
                ModelRole.entries.forEach { role ->
                    when (val c = _roles.value[role]) {
                        is RoleChoice.Model -> putJsonObject(role.name) {
                            put("use", USE_MODEL); put("config", c.configId)
                            _roles.value.reasoningEfforts[role]?.let { put("reasoningEffort", it) }
                        }
                        RoleChoice.SameAsMain -> putJsonObject(role.name) { put("use", USE_MAIN) }
                        RoleChoice.Off -> putJsonObject(role.name) { put("use", USE_NONE) }
                        null -> Unit
                    }
                }
            }
        }
        return root.toString()
    }

    private fun keysJson() = buildJsonObject { keys.forEach { (id, v) -> put(id, v) } }.toString()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "cannot write ${f.name}" }
        }
    }

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String) = Base64.getDecoder().decode(s)

    /** はプロセス中のアプリケーションを示します。 */
    interface Owner {
        val providers: ProviderStore
    }

    companion object {
        private const val CONFIG_FILE = "providers.json"
        private const val KEY_FILE = "provider-keys.json"
        private const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** `uses` の各用途の `use`: 具体的なモデル（別途 `config` に記録）、メインモデルを使う、使わない。旧版のファイルには `roles` だけがある。 */
        private const val USE_MODEL = "model"
        private const val USE_MAIN = "main"
        private const val USE_NONE = "none"

        // プロバイダーへのクライアントはすべて NetProxy 経由で作る: 設定 › ネットワークプロキシを通り、本機と LAN アドレス（ローカルモデル）は直接接続する
        private val http by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)) }

        /** 会話モデルと共有するコネクションプール; 同じプロバイダーへの他のリクエスト（音声の文字起こしなど）もこれを使う。 */
        val modelHttp: OkHttpClient get() = http

        /** 列モデル。アカウント ログインとトークン交換に使用されます。読み取りタイムアウトが短くなり、ネットワーク プロキシも使用されます。 */
        private val shortHttp by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)) }

        private val catalog by lazy { ModelCatalog(shortHttp) }

        private val defaultChatGptAuth by lazy { ChatGptAuth(shortHttp) }
        private val defaultOpenRouterAuth by lazy { OpenRouterAuth(shortHttp) }
        private val defaultXaiAuth by lazy { XaiAuth(shortHttp) }

        /** ChatGPT サービス プロバイダーの名前 (ブランド名、インターフェイス言語によって変わりません)。 */
        const val CHATGPT_NAME = "ChatGPT"
        /** xAI アカウントでログインしたサービス プロバイダーの名前 (ブランド名、インターフェイス言語によって変わりません)。キーを入力した「xAI」とは別の会社です。 */
        const val XAI_NAME = "xAI / Grok"
        /** は、OpenRouter アカウントによって承認されたサービス プロバイダーです。名前はデフォルトと同じで、[Provider.signIn] にはこれがマークされています。 */
        const val OPENROUTER_NAME = "OpenRouter"
        const val OPENROUTER_SIGN_IN = "openrouter"
        /** エラーの説明にあるこのログインの名前は何ですか。 */
        private const val XAI_ACCOUNT = "xAI"

        /**
         * トライアルコール: 一時的に設定された ID、システムプロンプト、センテンス、トライアルツール、応答の上限。
         * の上限は、最初に考慮する必要があるモデルの考え方と合わせて、1 回のツール呼び出しに十分な量を残します (メッセージ、generateContent、思考も上限に含まれます)。
         */
        private const val PROBE_ID = "probe"
        private const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        private const val PROBE_TEXT = "What time is it? Use the get_time tool."
        private val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        private const val PROBE_MAX_TOKENS = 1024

        /**
         * 列モデルのインターフェイス: OpenAI 互換性、応答、ChatGPT、および xAI はすべて `GET {base}/models` (ベアラー。サブスクリプション ログインの [apiKey] はアクセス トークン)。
         * [headers] はすべてのリクエストで伝送されます ([ClientHeaders] を参照)。
         */
        suspend fun listAvailable(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult =
            when (kind) {
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    catalog.openAiCompat(baseUrl, apiKey, headers)
                ProviderConfig.Kind.Anthropic -> catalog.anthropic(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.Gemini -> catalog.gemini(baseUrl, apiKey.orEmpty(), headers)
            }

        /**
         * このモデルを呼び出すために使用されるアダプターとそれに与えられるベース URL。クリックするとモデル ([buildModel]) が構築され、思考装置 ([ReasoningOptions]) が与えられます。
         * サブスクリプション ログイン (xAI) は常にこのインターフェイスを使用し、サブスクリプションを別のインターフェイスに転送しないように、モデル リストに記述された他のプロトコルには従いません。
         */
        internal fun adapterFor(cfg: ProviderConfig, endpoint: ModelEndpoint?): Pair<ProviderConfig.Kind, String> =
            if (endpoint == null || cfg.kind == ProviderConfig.Kind.Xai) cfg.kind to cfg.baseUrl
            else ProviderConfig.Kind.of(endpoint.protocol) to endpoint.adapterBaseUrl

        /** 製品のコピー (アプリ構築): ファイル/設定で構成され、キーは Android キーストアで暗号化され、使用状況は [usage] に記録されます。 */
        fun create(context: Context, usage: UsageSink, userAgent: String): ProviderStore =
            ProviderStore(File(context.applicationContext.filesDir, "settings"), KeystoreKeyCipher(), usage, userAgent = userAgent)

        /** アプリ内で共有される共有 ([Owner] によって与えられます)。 */
        fun get(context: Context): ProviderStore = (context.applicationContext as? Owner)?.providers
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement ProviderStore.Owner to give the model settings")
    }
}
