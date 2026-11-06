package com.wangzan101.mika.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wangzan101.mika.R
import com.wangzan101.mika.core.chatgpt.ChatGptAuth
import com.wangzan101.mika.core.oauth.OpenRouterAuth
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.voice.SystemSpeech
import com.wangzan101.mika.settings.ChatGptSignIns
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.wangzan101.mika.ui.components.MikaDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.ModelContextSettings
import com.wangzan101.mika.settings.ModelOption
import com.wangzan101.mika.settings.ModelOptions
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.CapabilityTag
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.Hairline
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.SelectChip
import com.wangzan101.mika.ui.components.TagTone
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/**
 * 構成モデル: 目的 (メイン モデル、ダイアグラム モデル、軽量モデル) とサービス プロバイダーの 2 つの部分。初めて使用を開始したときは、[onboarding] が true になります。
 * (最初にサービスプロバイダー、ページネーションポイントと一番下に「次へ」);設定 › モデル ([ModelsScreen]) もこのページにありますが、タイトルとガイドが異なり、下部ボタンはありません。
 * 使用法、サービス プロバイダーの詳細、およびすべてのサービス プロバイダー モデルの選択は、このページのサブページです。開始したら、「次へ」をクリックしてメインモデルを選択し、[onDone] をクリックします。
 */
@Composable
fun ModelSetupScreen(
    store: ProviderStore, onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit,
    /** 設定の「Speech to Text」カードをクリックして選択ページに移動します。起動プロセスをアップロードしないとカードは表示されません。 */
    onOpenSpeech: (() -> Unit)? = null,
    /** 設定からトークン使用量を開くための操作です。初回設定では渡しません。 */
    onOpenUsage: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val vm = remember {
        val signIns = (context.applicationContext as? MikaApp)?.chatGptSignIn ?: ChatGptSignIns(scope)
        ModelSetupViewModel(
            store, scope, browser = AndroidSignInBrowser(context.applicationContext), signIns = signIns,
            speech = (context.applicationContext as? MikaApp)?.speech,
            systemSpeech = {
                val silent = (context.applicationContext as? MikaApp)?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }
    // アカウント ログイン パネル: 追加フォームとサービス プロバイダーの詳細で開くことができます。ログイン中にログインを中断することなく閉じることができます。
    var signInSheet by rememberSaveable { mutableStateOf<SignInKind?>(null) }

    // はアカウントで正常にログインしました: パネルを閉じて、この会社の詳細を開きます (アカウントとモデルを確認し、メイン モデルを設定します)
    LaunchedEffect(ui.signedInProvider) {
        val id = ui.signedInProvider ?: return@LaunchedEffect
        signInSheet = null
        picking = null
        openProvider = id
        allModels = false
        vm.signedInShown()
    }
    // ChatGPT は正常にログインし、このページに残ります (パッケージ クォータが初めて使用されたとき)、パネルは閉じられます
    LaunchedEffect(ui.chatGptAccount?.signedIn, ui.chatGptSignIn) {
        if (signInSheet == SignInKind.ChatGpt && ui.chatGptAccount?.signedIn == true && ui.chatGptSignIn == ListState.Idle) signInSheet = null
    }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    val role = picking
    when {
        provider != null -> {
            LaunchedEffect(provider.id) { vm.ensureListed() }
            val accountKind = when {
                provider.kind == ProviderConfig.Kind.Xai -> SignInKind.Xai
                provider.signIn == ProviderStore.OPENROUTER_SIGN_IN -> SignInKind.OpenRouter
                else -> null
            }
            val actions = ProviderDetailActions(
                refresh = { vm.refresh(provider.id) }, changeKey = { vm.changeKey(provider.id, it) },
                remove = { vm.removeProvider(provider.id); openProvider = null; allModels = false },
                use = { r, model -> vm.pick(r, provider.id, model) },
                setVision = { model, sees -> vm.setModelVision(provider.id, model, sees) },
                showAll = { allModels = true },
                retest = { vm.retest(provider.id) },
                // ChatGPT 通常どおり、ブラウザに移動して直接ログインします。 xAI と OpenRouter の場合は、最初にヘルプ パネルを開きます
                signIn = { if (accountKind != null) signInSheet = accountKind else vm.signInWithChatGpt() },
                signOut = { if (accountKind != null) vm.signOutAccount(provider.id) else vm.signOutChatGpt(provider.id) },
                manageUsage = {
                    openInBrowser(context, when (accountKind) {
                        SignInKind.Xai -> ModelsCopy.ACCOUNT_MANAGE_URL
                        SignInKind.OpenRouter -> OpenRouterAuth.KEYS_PAGE
                        else -> ChatGptAuth.USAGE_URL
                    })
                },
                useAsMain = { vm.useAsMain(provider.id) },
            )
            if (allModels) {
                BackHandler { allModels = false }
                AllModelsContent(ui, provider, actions, onBack = { allModels = false })
            } else {
                BackHandler { openProvider = null }
                ProviderDetailContent(ui, provider, actions, onBack = { openProvider = null })
            }
        }
        role != null -> {
            BackHandler { picking = null }
            LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model ->
                        // は先ほど保存したものですが、どのモデルも合格しませんでした。最初に呼び出してみて、次に選択して、合格した場合は戻ります。
                        if (ui.probeBeforePick(role, pid)) vm.pickManual(role, pid, model) { picking = null }
                        else { vm.pick(role, pid, model); picking = null }
                    },
                    pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                    choose = { vm.choose(role, it); picking = null },
                    addProvider = { vm.openForm(); picking = null },
                ),
                onBack = { picking = null },
            )
        }
        else -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(
                ui, onboarding,
                ModelSetupActions(
                    selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setApiKey = vm::setApiKey,
                    setModel = vm::setModel, setManual = vm::setManual, setName = vm::setName,
                    testAndSave = vm::testAndSave, saveUntested = vm::saveWithoutTest,
                    openForm = vm::openForm, closeForm = vm::closeForm,
                    openProvider = { openProvider = it; allModels = false }, pickRole = { picking = it }, next = onDone, openSpeech = onOpenSpeech,
                    openUsage = onOpenUsage,
                    openSignIn = { signInSheet = it },
                    setReasoningEffort = vm::setReasoningEffort,
                    setModelContext = vm::setModelContext,
                ),
                onBack,
            )
        }
    }

    signInSheet?.let { kind ->
        SignInSheet(
            ui, kind,
            SignInSheetActions(
                start = { k -> k.account?.let(vm::signInWithAccount) ?: vm.signInWithChatGpt() },
                cancel = { k -> if (k.account != null) vm.cancelAccountSignIn() else vm.cancelChatGptSignIn() },
                useApiKey = { k ->
                    signInSheet = null
                    openProvider = null
                    picking = null
                    vm.openForm()
                    k.keyPreset?.let(vm::selectPreset)
                },
                dismiss = {
                    signInSheet = null
                    // 失敗の理由がわかりました。まだログインしている場合は、中断しないでください。
                    if (!ui.accountSignIn.running) vm.dismissAccountFailure()
                },
            ),
        )
    }
}

/** メイン ページのすべてのアクション。 */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    /** 「モデル名を手動で入力」開閉。 */
    val setManual: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val testAndSave: () -> Unit = {},
    /** はテストされず、直接保存されません ([ModelSetupViewModel.saveWithoutTest] を参照)。 */
    val saveUntested: () -> Unit = {},
    val openForm: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val openProvider: (String) -> Unit = {},
    val pickRole: (ModelRole) -> Unit = {},
    /** 「音声テキスト変換」選択ページを開きます。 null の場合、このカードは表示されません (開始)。 */
    val openSpeech: (() -> Unit)? = null,
    /** トークン使用量を開きます。初回設定など null の場合は入口を表示しません。 */
    val openUsage: (() -> Unit)? = null,
    val next: () -> Unit = {},
    /** は、アカウントにログインするための説明パネルを開きます。 */
    val openSignIn: (SignInKind) -> Unit = {},
    val setReasoningEffort: (ModelRole, String?) -> Unit = { _, _ -> },
    val setModelContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
)

/**
 * ステータスのないメイン ページ。テストに便利です。変更はその場で反映されます（テストに合格したらサービスプロバイダーを保存し、目的を選択してから使用します）。
 * したがって、設定を入力するときのみ戻ることができます。開始するときは「次へ」を一番下に残し、キーボードが表示されたときに非表示にし、フォームにスペースを与えます。
 */
@Composable
fun ModelSetupContent(ui: ModelSetupUi, onboarding: Boolean, actions: ModelSetupActions, onBack: () -> Unit) {
    val c = MikaTheme.colors
    // フォームを折りたたんだ後 (テストに合格した後に保存するかキャンセルした後)、ページ上に入力する場所がないため、キーボードを簡単に折りたたむことができます
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick: (ModelRole) -> Unit = { role -> if (ui.providers.isEmpty()) actions.openForm() else actions.pickRole(role) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (onboarding) {
                    Text(
                        ModelSetupCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                        fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                    )
                }
                // の使用を開始するときは、まずサービス プロバイダーに接続し、次にモデルを選択します。まずは設定で目的を確認してください
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    RolesSection(ui, onPick, actions.setReasoningEffort, null)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    RolesSection(ui, onPick, actions.setReasoningEffort, actions.openSpeech, actions.setModelContext)
                    ProvidersSection(ui, actions)
                    actions.openUsage?.let { openUsage ->
                        GroupCard {
                            ListRow(MikaIcons.Chart, UsageCopy.TITLE, first = true, onClick = openUsage) { RowChevron() }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            // キーボードの実際の高さを見てください。 isImeVisible は、システムが最初に報告する前にデフォルトで true に設定されます。これにより、誤ってボタンが非表示になります。
            if (onboarding && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(bottom = 14.dp))
                    MikaButton(ModelSetupCopy.NEXT, actions.next, Modifier.fillMaxWidth().testTag("setup-next"), enabled = ui.canNext)
                }
            }
        }
    }
}

/** サービス プロバイダー: 会社ごとに 1 行 (どの目的で機能しているかを示します)、「サービス プロバイダーの追加」を追加します。サービスプロバイダーがない場合、または「追加」をクリックすると、ここにフォームが表示されます。 */
@Composable
private fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupLabel(ModelsCopy.PROVIDERS)
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (ui.signedOut(p)) ModelsCopy.CHATGPT_SIGNED_OUT else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                // 使用中だがテストに合格していないモデルが存在するか、テストがスキップされて保存され、すでに使用されています。この行は「テストに合格しなかったこと」を思い出させます。
                val warning = ModelsCopy.UNVERIFIED.takeIf {
                    ui.unverified(p).isNotEmpty() || (ui.untested(p) && ui.rolesOf(p).isNotEmpty())
                }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MikaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // 保存したモデルはどれもテストに合格しませんでした。メイン モデルを直接選択してください (選択するときに最初に呼び出してみてください)
    if (ui.pickToProbe != null && ui.roles.main == null) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
        )
    }
    if (ui.showForm) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** サービスプロバイダーの追加: まず、「アカウントでログイン」グループを入力し (キーを入力する必要はありません)、次にキーを入力します: デフォルトのカプセル → (カスタマイズ時) インターフェースのタイプとアドレス → API キー → 「テストして保存」。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MikaTheme.colors
    val f = ui.form
    var showKey by remember { mutableStateOf(false) }
    var confirmUntested by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(ModelsCopy.ADD_PROVIDER, Modifier.weight(1f))
        if (ui.providers.isNotEmpty()) {
            Text(
                ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.closeForm).padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
    AccountSignInGroup(ui, actions.openSignIn, actions.openProvider)
    GroupLabel(ModelsCopy.KEY_GROUP)
    var showOtherRegions by rememberSaveable { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (ProviderPresets.recommended + if (showOtherRegions) ProviderPresets.otherRegions else emptyList()).forEach { p ->
            // 「カスタム」はインターフェース言語に従って表示されます。サービスプロバイダーの名前は変更されません
            SelectChip(if (p == ProviderPresets.custom) stringResource(R.string.preset_custom) else p.displayLabel, f.preset == p.label, { actions.selectPreset(p.label) }, Modifier.testTag("preset-${p.label}"))
        }
        SelectChip(stringResource(R.string.models_other_regions), showOtherRegions, { showOtherRegions = !showOtherRegions }, Modifier.testTag("preset-other-regions"))
    }
    if (showOtherRegions) {
        Text(stringResource(R.string.models_china_optional_note), fontSize = 13.sp, color = c.ink2, modifier = Modifier.padding(bottom = 12.dp))
    }
    GroupCard {
        if (f.isCustom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ModelSetupCopy.KIND, Modifier.padding(end = 12.dp), style = MikaType.Body, color = c.ink)
                // 4 つのインターフェイスが 1 行に収まらない場合は、次の行に折りたたんでください。
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderConfig.Kind.withApiKey.forEach { k -> SelectChip(ModelsCopy.kindLabel(k), f.kind == k, { actions.setKind(k) }, small = true) }
                }
            }
        }
        if (f.urlEditable) {
            InputRow(
                ModelSetupCopy.BASE_URL, f.baseUrl, actions.setBaseUrl, "https://…", first = !f.isCustom,
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-base-url"),
            )
        }
        if (f.isCustom) {
            // 空白のままでも構いません。空白のままにする場合は、プロンプトに名前 (アドレスのホスト名) を直接入力します。
            InputRow(
                ModelSetupCopy.NAME, f.name, actions.setName,
                if (f.urlProblem == null) f.defaultName else ModelSetupCopy.NAME_HINT,
                modifier = Modifier.testTag("setup-name"),
            )
        }
        InputRow(
            ModelSetupCopy.API_KEY, f.apiKey, actions.setApiKey,
            if (f.keyRequired) ModelSetupCopy.KEY_HINT else ModelSetupCopy.KEY_OPTIONAL, first = !f.urlEditable,
            keyboard = KeyboardType.Password, secret = !showKey, modifier = Modifier.testTag("setup-api-key"),
        ) {
            Text(if (showKey) stringResource(R.string.hide) else stringResource(R.string.show), Modifier.clickable { showKey = !showKey }, fontSize = 13.sp, color = c.accent)
        }
        if (f.showModel) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    // ご希望のモデルがリストにない場合、または会社がリストを提供していない場合は、いつでもご自身で入力していただけます（モデルがリストにない場合、その列はすでに存在しています）。
    if (!f.noList) {
        Text(
            if (f.manual) ModelSetupCopy.MANUAL_OFF else ModelSetupCopy.MANUAL_ON,
            Modifier.padding(start = 16.dp, top = 8.dp).clickable { actions.setManual(!f.manual) }.padding(vertical = 4.dp).testTag("setup-manual-model"),
            fontSize = 13.sp, color = c.accent,
        )
    }
    if (f.manual && !f.noList) FooterNote(ModelSetupCopy.MANUAL_NOTE, Modifier.testTag("setup-manual-note"))
    f.urlProblem?.takeIf { f.urlEditable && f.baseUrl.isNotBlank() }?.let { FooterNote(it, color = c.bad) }
    f.suggestedPreset?.let { preset -> PresetSuggestion(f, preset.displayLabel) { actions.selectPreset(preset.label) } }
    if (f.noList) FooterNote(if (f.isCustom) ModelSetupCopy.NO_LIST_CUSTOM else ModelSetupCopy.NO_LIST_PRESET, Modifier.testTag("setup-no-list"))
    if (f.isSelfHosted) FooterNote(ModelSetupCopy.SELF_HOSTED_NOTE, Modifier.testTag("setup-self-hosted"))
    f.unencryptedWarning?.let { FooterNote(it, Modifier.testTag("setup-url-unencrypted"), color = c.warn) }
    MikaButton(
        if (f.test == ListState.Running) ModelSetupCopy.TESTING else ModelSetupCopy.TEST_AND_SAVE,
        actions.testAndSave, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("setup-test"),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = f.canTest,
    )
    (f.test as? ListState.Failed)?.let { failed ->
        Text(
            ModelSetupCopy.failed(failed.message), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("setup-test-failed"),
            fontSize = 13.sp, lineHeight = 18.sp, color = c.bad,
        )
    }
    // 試した後、最初に保存しておきたいこと: ボタンにして、小さな単語の行で隠さないでください。これは説明ではなく、実際のアクションです。
    MikaButton(
        ModelSetupCopy.SAVE_UNTESTED, { confirmUntested = true },
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("setup-save-untested"),
        kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = f.canSaveUntested,
    )
    if (confirmUntested) MikaDialog(
        onDismissRequest = { confirmUntested = false },
        title = { Text(ModelSetupCopy.SAVE_UNTESTED_TITLE) },
        text = { Text(ModelSetupCopy.SAVE_UNTESTED_BODY) },
        confirmButton = {
            TextButton({
                confirmUntested = false
                actions.saveUntested()
            }) { Text(ModelSetupCopy.SAVE_UNTESTED_CONFIRM) }
        },
        dismissButton = {
            TextButton({ confirmUntested = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
    FooterNote(ModelSetupCopy.TEST_NOTE, Modifier.padding(top = 6.dp))
}

/** 特定の会社の公式アドレスをカスタマイズします。その会社のデフォルト (ブロックせずに) を使用することをお勧めします。インターフェースのタイプが一致しない場合は、それも通知します。クリックして変更し、キーを保持します。 */
@Composable
private fun PresetSuggestion(f: AddForm, label: String, onUse: () -> Unit) {
    val c = MikaTheme.colors
    FooterNote(ModelSetupCopy.presetSuggestion(f), Modifier.padding(top = 2.dp).testTag("setup-preset-hint"), color = c.warn)
    Text(
        ModelSetupCopy.usePreset(label),
        Modifier.padding(start = 16.dp, bottom = 12.dp).clickable(onClick = onUse).testTag("setup-use-preset").padding(vertical = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
    )
}

/** ChatGPT で初めてログインに成功した後、一度説明してください。Mika での使用法が ChatGPT パッケージに使用されます。 */
@Composable
private fun ChatGptWelcome(onDismiss: () -> Unit) {
    MikaDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.CHATGPT_WELCOME_TITLE) },
        text = { Text(ModelsCopy.CHATGPT_WELCOME_BODY) },
        confirmButton = { TextButton(onDismiss) { Text(ModelsCopy.CHATGPT_GOT_IT) } },
    )
}

/**
 * ログインに必要なシステム機能: 認証ページはシステム ブラウザで開きます。コールバック ページ (トークンの変更後に表示されます) は、成功または失敗の理由を書き込み、Mika にリンクを戻します (インテント リンク、クリックするとブラウザで Mega が開きます)。
 * ログイン後（成功または失敗）、ミカをフロントデスクに戻します。 Android はバックグラウンドで開くインターフェイスを制限しており、ユーザーがコールバック ページのリンクをクリックしたとき、または最近のタスクから戻ったときにバックグラウンドで開くインターフェイスを元に戻すことはできません。
 */
private class AndroidSignInBrowser(private val context: Context) : SignInBrowser {
    override fun open(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.chatgpt_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    override fun returnToApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun page(failure: String?): String {
        val text = failure ?: tr(R.string.chatgpt_page_done)
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return SignInPage.html(tr(R.string.chatgpt_page_title), text, tr(R.string.chatgpt_page_back), back)
    }
}

/** コールバック後のブラウザのページ: 一文でアプリに戻るボタンを追加します。テキストはエスケープされて挿入されます。 */
internal object SignInPage {
    fun html(title: String, text: String, back: String, backUrl: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>${esc(title)}</title><style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#1d1d1f;background:#fff}""" +
            """p{font-size:17px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 28px;border-radius:999px;background:#1d1d1f;""" +
            """color:#fff;text-decoration:none;font-size:16px}</style></head><body><p>${esc(text)}</p><a href="${esc(backUrl)}">${esc(back)}</a></body></html>"""
    }
}

/** 用途：メインモデル、映像閲覧モデル、軽量モデル 各1枚。クリックすると選択ページが開きます。 */
@Composable
private fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit, onReasoning: (ModelRole, String?) -> Unit,
    onSpeech: (() -> Unit)?, onContext: ((String, ModelContextSettings) -> Boolean)? = null,
) {
    GroupLabel(ModelsCopy.ROLES)
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
            if (ui.roles[role] == RoleChoice.SameAsMain) {
                ReasoningControl(ui, role) { onReasoning(role, it) }
            } else {
                val reasoning = hasReasoningAction(ui, role)
                val context = onContext != null && hasModelContextControl(ui, role)
                if (reasoning || context) {
                    Column(Modifier.padding(start = 70.dp, end = 16.dp)) {
                        Hairline(start = 0.dp)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (reasoning) ReasoningControl(ui, role, Modifier.weight(1f)) { onReasoning(role, it) }
                            if (reasoning && context) Box(Modifier.width(0.5.dp).height(14.dp).background(MikaTheme.colors.hair))
                            if (onContext != null && context) ModelContextControl(ui, role, onContext, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 4 番目の使用法: 音声からテキストへの変換 (対話モデルではありません。クリックすると別の選択ページが開きます)
        if (onSpeech != null) {
            RoleCard(ModelsCopy.SPEECH_ICON, ModelsCopy.SPEECH_LABEL, ModelsCopy.speechLine(ui), tag = "role-speech", onClick = onSpeech)
        }
    }
}

private fun hasModelContextControl(ui: ModelSetupUi, role: ModelRole): Boolean =
    ui.roles[role] is RoleChoice.Model && ui.configFor(role) != null

@Composable
private fun ModelContextControl(ui: ModelSetupUi, role: ModelRole,
    onSave: (String, ModelContextSettings) -> Boolean, modifier: Modifier = Modifier,
) {
    if (ui.roles[role] !is RoleChoice.Model) return
    val cfg = ui.configFor(role) ?: return
    val saved = ui.providerOf(cfg)?.contextSettings?.get(cfg.model) ?: ModelContextSettings()
    var open by rememberSaveable(cfg.id) { mutableStateOf(false) }
    ModelOptionButton(stringResource(R.string.model_context_title), "context-${role.name.lowercase()}", modifier) { open = true }
    if (!open) return
    var window by rememberSaveable(cfg.id) { mutableStateOf(saved.windowTokens?.toString().orEmpty()) }
    var trigger by rememberSaveable(cfg.id) { mutableStateOf(saved.triggerTokens?.toString().orEmpty()) }
    var readTimeout by rememberSaveable(cfg.id) { mutableStateOf(saved.readTimeoutSeconds?.toString().orEmpty()) }
    var saveFailed by remember { mutableStateOf(false) }
    fun positiveInt(text: String) = text.trim().takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    val settings = ModelContextSettings(positiveInt(window), positiveInt(trigger), positiveInt(readTimeout))
    val modelWindow = cfg.capabilities.contextTokens
    val limits = settings.limits(modelWindow)
    val badWindow = window.isNotBlank() && settings.windowTokens == null
    val badTrigger = trigger.isNotBlank() && (settings.triggerTokens == null || (!badWindow && settings.triggerTokens != limits.trigger))
    val badReadTimeout = readTimeout.isNotBlank() &&
        (settings.readTimeoutSeconds == null || settings.readTimeoutSeconds > ModelContextSettings.MAX_READ_TIMEOUT_SECONDS)
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    MikaDialog(
        onDismissRequest = { open = false },
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.model_context_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(cfg.model)
                Text(stringResource(R.string.model_context_note))
                OutlinedTextField(window, { window = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-window"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_window)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, modelWindow)) },
                    supportingText = if (badWindow) ({ Text(stringResource(R.string.model_context_integer)) }) else null,
                    isError = badWindow)
                OutlinedTextField(trigger, { trigger = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-trigger"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_trigger)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, limits.trigger)) },
                    supportingText = {
                        if (!badWindow && limits.usable > 0) Text(stringResource(R.string.model_context_range,
                            limits.minTrigger.coerceAtLeast(1), limits.usable))
                        else if (badTrigger) Text(stringResource(R.string.model_context_integer))
                    }, isError = badTrigger)
                OutlinedTextField(readTimeout, { readTimeout = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-read-timeout"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_read_timeout)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, 120)) },
                    supportingText = if (badReadTimeout) ({ Text(stringResource(R.string.model_context_timeout_range, ModelContextSettings.MAX_READ_TIMEOUT_SECONDS)) }) else null,
                    isError = badReadTimeout)
                if (!badWindow && (trigger.isBlank() || settings.triggerTokens != null)) Text(stringResource(R.string.model_context_effective, limits.window, limits.trigger),
                    modifier = Modifier.testTag("context-effective"))
                TextButton(onClick = { window = ""; trigger = ""; readTimeout = ""; saveFailed = false }) { Text(stringResource(R.string.reset)) }
                if (saveFailed) Text(stringResource(R.string.model_context_save_failed), color = MikaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (onSave(cfg.id, settings)) open = false else saveFailed = true
        }, enabled = !badWindow && !badTrigger && !badReadTimeout, modifier = Modifier.testTag("context-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** モデルの能力ラベル。 [accent] が false の場合、常に中間色になります (リスト行のアクセント カラーは「使用中」カプセル用に予約されています)。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(o: ModelOption, forVision: Boolean = false, accent: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSetupCopy.tags(o, forVision).forEach { (text, strong) -> CapabilityTag(text, if (strong && accent) TagTone.Accent else TagTone.Neutral) }
    }
}

internal val ModelIdStyle = MikaType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

/** はモデル ページを構成し (開始時)、サービス プロバイダー フォームのテキストを追加します (純粋な機能、テストが簡単)。目的は[ModelsCopy]に記載されています。 */
object ModelSetupCopy {
    val TITLE: String get() = tr(R.string.model_setup_title)
    val LEAD: String get() = tr(R.string.model_setup_lead)
    val ONBOARDING_NOTE: String get() = tr(R.string.model_setup_onboarding_note)
    val CANCEL: String get() = tr(R.string.cancel)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.model_base_url)
    val NAME: String get() = tr(R.string.model_provider_name)
    val NAME_HINT: String get() = tr(R.string.model_provider_name_hint)
    const val API_KEY = "API Key"
    val KEY_HINT: String get() = tr(R.string.model_key_hint)
    val KEY_OPTIONAL: String get() = tr(R.string.model_key_optional)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val TEST_NOTE: String get() = tr(R.string.model_test_note)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val NO_LIST_CUSTOM: String get() = tr(R.string.model_no_list_custom)
    val NO_LIST_PRESET: String get() = tr(R.string.model_no_list_preset)
    val MANUAL_ON: String get() = tr(R.string.model_manual_on)
    val MANUAL_OFF: String get() = tr(R.string.model_manual_off)
    val MANUAL_NOTE: String get() = tr(R.string.model_manual_note)
    val SAVE_UNTESTED: String get() = tr(R.string.model_save_untested)
    val SAVE_UNTESTED_TITLE: String get() = tr(R.string.model_save_untested_title)
    val SAVE_UNTESTED_BODY: String get() = tr(R.string.model_save_untested_body)
    val SAVE_UNTESTED_CONFIRM: String get() = tr(R.string.model_save_untested_confirm)

    /** インターフェイスの種類に応じて、モデル名を手動で入力する場合の例（モデル名は各社異なります）。 */
    fun modelExample(kind: ProviderConfig.Kind): String = tr(
        when (kind) {
            ProviderConfig.Kind.Gemini -> R.string.model_example_gemini
            ProviderConfig.Kind.Anthropic -> R.string.model_example_anthropic
            ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt -> R.string.model_example_openai
            ProviderConfig.Kind.Xai -> R.string.model_example_grok
            ProviderConfig.Kind.OpenAiCompat -> R.string.model_example_compat
        },
    )

    /** カスタムアドレスが特定の会社の正式なアドレスである場合に推奨されます。インターフェースの種類がその会社のものと一致しない場合は補足を追加してください。 */
    fun presetSuggestion(f: AddForm): String {
        val preset = f.suggestedPreset ?: return ""
        val match = tr(R.string.model_preset_match, preset.displayLabel)
        return if (!f.presetKindMismatch) match
        else match + tr(R.string.sentence_sep) + tr(R.string.model_preset_kind_mismatch, preset.displayLabel, ModelsCopy.kindLabel(preset.kind), ModelsCopy.kindLabel(f.kind))
    }

    fun usePreset(label: String) = tr(R.string.model_use_preset, label)
    val NEXT: String get() = tr(R.string.next)

    fun failed(reason: String) = tr(R.string.model_failed, reason)

    /** モデルの機能ラベル：（テキスト、アクセントカラーの有無）。インターフェイスは提供されず、ユーザーは何も言わず、デフォルトの推論に基づいて「未確認」とマークされました。参照のみを目的としており、選択を妨げるものではありません。 */
    fun tags(o: ModelOption, forVision: Boolean = false): List<Pair<String, Boolean>> = buildList {
        val caps = o.capabilities
        val visionKnown = o.visionKnown || o.visionByUser != null
        if (o.typed) add(tr(R.string.cap_typed) to false)
        if (forVision) {
            add((if (visionKnown) tr(R.string.cap_vision) else tr(R.string.cap_vision_unconfirmed)) to false)
        } else {
            when {
                !caps.toolCalls -> add(tr(R.string.cap_no_tools) to false)
                o.toolCallsKnown -> add(tr(R.string.cap_tools) to true)
                else -> add(tr(R.string.cap_tools_unconfirmed) to false)
            }
            if (caps.vision && visionKnown) add(tr(R.string.cap_vision) to false)
        }
        if (caps.reasoning) add(tr(R.string.cap_reasoning) to false)
        if (o.contextKnown) add(ModelOptions.contextLabel(caps.contextTokens) to false)
    }
}
