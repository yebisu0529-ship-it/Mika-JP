package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleDock
import com.wangzan101.mika.capsule.CardDocking
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.DockSide
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.cap.phone.OperatingPace
import com.wangzan101.mika.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** ファンクションスイッチ。高速化スイッチは[SpeedFlags.describe]の形式で保存されており、評価ランナーはパラメータで上書き可能です。 */
class FeatureFlags(private val prefs: SharedPreferences) {
    /** まだ実行されていないデフォルト値のアップグレード ([SPEED_UPGRADES] を参照) を 1 回ずつ実行します。アップグレード後、ユーザーが手動でオフにすると、オフのままになります。 */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        val pending = SPEED_UPGRADES.filter { (key, _) -> !prefs.getBoolean(key, false) }
        if (pending.isEmpty()) return flags
        val upgraded = pending.fold(flags) { f, (_, upgrade) -> upgrade(f) }
        setSpeed(upgraded)
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        val edit = prefs.edit().putString(SPEED_FLAGS, flags.describe())
        SPEED_UPGRADES.forEach { (key, _) -> edit.putBoolean(key, true) }
        edit.commit()
    }

    /** 同時に実行できる並列タスクの最大数 (ユーザー設定、1 ～ 3)。実際の上限はデバイスによって異なります。「ParallelCapability」を参照してください。 */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** 各タスクの最大ステップ数。 null は制限がないことを意味します。デフォルトでは制限はなく、次のタスクから有効になります。 */
    fun taskStepLimit(): Int? = prefs.getInt(TASK_STEP_LIMIT, 0).takeIf { it in 1..MAX_TASK_STEP_LIMIT }

    fun setTaskStepLimit(limit: Int?) {
        prefs.edit().putInt(TASK_STEP_LIMIT, (limit ?: 0).coerceIn(0, MAX_TASK_STEP_LIMIT)).commit()
    }

    /** 作業中は画面が常にオンのままです。これはデフォルトでオンになっており、ユーザーがオフにすることができます。 */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    /** 作業中に操作したアプリをミュートします。これはデフォルトで有効になっており、ユーザーはオフにすることができます。 アプリ内の */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** の動作リズム: 高速、標準 (デフォルト)、人間に近い ([OperatingPace] を参照)。 */
    fun operatingPace(): OperatingPace = OperatingPace.parse(prefs.getString(OPERATING_PACE, null)) ?: OperatingPace.DEFAULT

    fun setOperatingPace(pace: OperatingPace) {
        prefs.edit().putString(OPERATING_PACE, pace.name).commit()
    }

    /** インターフェイスのカラー マッチング: システムに従い、常に明るい色、常に暗い色 ([ThemeMode] を参照)。インターフェースは、再構築することなく、[themeModeChanges]に従ってその場で変更できます。 */
    fun themeMode(): ThemeMode = ThemeMode.parse(prefs.getString(THEME_MODE, null))

    /** の外観の現在値。変更されるたびに再送信されます (設定ページでの変更とタスクでの変更は同じです)。 */
    fun themeModeChanges(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_MODE) trySend(themeMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).commit()
    }

    /**
     * を使用すると、Mika は独自の設定を変更できます (Core の SelfConfig を参照)。モデルとキー、およびデータの削除は許可されません。
     * はデフォルトではオフです。オンの場合のみ、タスク内に settings_list と settings_set という 2 つのツールがあります。
     */
    fun selfConfig(): Boolean = prefs.getBoolean(SELF_CONFIG, false)

    fun setSelfConfig(on: Boolean) {
        prefs.edit().putBoolean(SELF_CONFIG, on).commit()
    }

    /** サスペンション カプセル: オフ、タスクがある場合に表示 (デフォルト)、常に表示されます。 */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        prefs.edit().putString(CAPSULE_MODE, mode.name).commit()
    }

    /** カプセル スイッチの現在値。変更されるたびに再送信されます。 すべての設定をクリアすると、 */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // キーが null になります
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** カプセルはどこに止まりますか: 左または右、スイング可能な範囲の比率としての高さ。ドラッグしていない場合は、右上隅にあります。 */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * 展開されたカードがどこで止まるか: スイング可能範囲内の上部の割合 ([com.wangzan101.mika.capsule.CardDocking.release] を参照)。覚えていないかもしれませんが、最も高いカードが中央にあります。
     * 以前は、固定の高さに応じて記録されたカードの割合は、その上の割合とまったく同じであり、依然として読み取られていました。
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    fun quitRequested(): Boolean = prefs.getBoolean(QUIT_REQUESTED, false)

    /** プロセスを終了する前に保存を確認する。 */
    fun setQuitRequested(value: Boolean): Boolean {
        val previous = quitRequested()
        if (prefs.edit().putBoolean(QUIT_REQUESTED, value).commit()) return true
        prefs.edit().putBoolean(QUIT_REQUESTED, previous).apply()
        return false
    }

    companion object {
        const val PREFS = "feature_flags"
        /** 各タスクステップの上限として設定できる最大値。 0/null は制限がないことを意味します。 */
        const val MAX_TASK_STEP_LIMIT = 500
        private const val SPEED_FLAGS = "speed_flags"
        /**
         * 高速化スイッチのデフォルト値をアップグレードします。順番に 1 回ずつ実行します (実行したかどうかに注意してください): v2 では、階層的な画面読み取り、連続操作、オンデマンドでの画像表示が開きます。
         * v3 では、体験リプレイが開きます (エンディング判定の変更を学習した後でのみルートを思い出すことができます)。
         */
        private val SPEED_UPGRADES: List<Pair<String, (SpeedFlags) -> SpeedFlags>> = listOf(
            "speed_defaults_applied_v2" to { f -> f.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true) },
            "speed_defaults_applied_v3" to { f -> f.copy(replay = true) },
        )
        private const val PARALLEL_MAX = "parallel_max"
        private const val TASK_STEP_LIMIT = "task_step_limit"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val MUTE_TARGET = "mute_target"
        private const val OPERATING_PACE = "operating_pace"
        private const val SELF_CONFIG = "self_config"
        private const val THEME_MODE = "theme_mode"
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"
        private const val QUIT_REQUESTED = "quit_requested"

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
