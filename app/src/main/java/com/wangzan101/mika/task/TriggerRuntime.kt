package com.wangzan101.mika.task

import com.wangzan101.mika.i18n.tr
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.trigger.TriggerDefinition
import com.wangzan101.mika.core.trigger.TriggerEngine
import com.wangzan101.mika.core.trigger.ConditionOutcome
import com.wangzan101.mika.core.trigger.TriggerSnapshot
import com.wangzan101.mika.core.trigger.TriggerWindowState
import com.wangzan101.mika.core.trigger.rescheduledTo
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Android 目覚まし時計用薄型ケース。時間と重複排除は永続的な TriggerStore によって決定され、目覚まし時計のブロードキャストを繰り返すことができます。 */
object TriggerRuntime {
    private var recovered = false
    /** 目覚まし時計を保存して設定します。システムが目覚まし時計を時間通りにすることを許可していない場合 (時間通りにならないか、起きられない可能性があります)、ユーザーは一度目覚まし時計をオンにするよう案内され、その結果でプロンプトがあるかどうかがわかります。 */
    @Synchronized fun schedule(context: Context, definition: TriggerDefinition): Scheduled {
        val store = store(context)
        store.upsert(definition)
        engine(context, store).rearm(System.currentTimeMillis())
        val granted = canScheduleExactly(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 通知が実際に送信された場合のみ、リマインダーとみなされます。送信されない場合は、次回もう一度試してください。
        val notified = ExactAlarmGuide.shouldPrompt(granted, prefs.getBoolean(PROMPTED, false)) && promptExactAlarm(context)
        if (notified) prefs.edit().putBoolean(PROMPTED, true).apply()
        return Scheduled(granted, notified)
    }

    /** システム (Android 12 以降) の「目覚まし時計とリマインダー」認証ページ。 */
    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 初めて許可なくスケジュールされたタスクを作成したとき: 通知を送信し、クリックして承認ページを開きます (タスクはバックグラウンドで実行され、ページに直接ジャンプしません)。返品通知を送信しました。 */
    private fun promptExactAlarm(context: Context): Boolean {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (!notifications.areNotificationsEnabled()) return false
        TaskService.ensureChannels(context)
        val open = PendingIntent.getActivity(context, PROMPTED.hashCode(), exactAlarmSettings(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return runCatching { notifications.notify(PROMPTED.hashCode(), Notification.Builder(context, TaskService.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(ExactAlarmGuide.TITLE)
            .setContentText(ExactAlarmGuide.BODY).setStyle(Notification.BigTextStyle().bigText(ExactAlarmGuide.BODY))
            .setContentIntent(open).setAutoCancel(true).build()) }
            .onFailure { Log.w("MikaTrigger", "exact alarm prompt failed", it) }.isSuccess
    }

    @Synchronized fun rearm(context: Context) { engine(context, store(context)).rearm(System.currentTimeMillis()) }

    /** デバイスのタイム ゾーンが変更されると、古いデバイスのタイム ゾーンに従ったローカル タイム プランが新しいタイム ゾーンに再計算されます。他のタイムゾーンは明示的に固定されます。ベースラインがない場合 (初めて使用する場合)、現在のタイムゾーンをメモするだけです。 */
    @Synchronized fun syncTimeZone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = ZoneId.systemDefault().id
        val previous = prefs.getString(KEY_DEVICE_ZONE, null)
        prefs.edit().putString(KEY_DEVICE_ZONE, current).apply()
        if (previous == null || previous == current) return
        engine(context, store(context)).rezone(System.currentTimeMillis(), previous, ZoneId.of(current))
    }

    @Synchronized fun fire(context: Context) {
        if (!com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) engine(context, store(context)).fire(System.currentTimeMillis())
    }

    @Synchronized fun suspendAlarms(context: Context) = arm(context, null)

    @Synchronized fun delivered(context: Context, key: String) {
        val store = store(context)
        store.mark(key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun actionFailed(context: Context, key: String, reason: String) {
        val store = store(context)
        if (store.windows().none { it.key == key }) return
        store.mark(key, TriggerWindowState.Failed, reason.take(160))
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun interrupted(context: Context, key: String, reason: String) {
        val store = store(context)
        store.mark(key, TriggerWindowState.Unknown, reason)
        store.windows().firstOrNull { it.key == key }?.let { window ->
            store.get(window.triggerId)?.let { trigger ->
                if (!hasSavedResult(context, key)) runCatching { reportUncertain(context, trigger, key) }
                    .onFailure { Log.w("MikaTrigger", "could not report interrupted task", it) }
            }
        }
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun checked(context: Context, key: String, goal: String, threadId: String, outcome: ConditionOutcome) {
        val store = store(context)
        val window = store.windows().firstOrNull { it.key == key && it.state == TriggerWindowState.Dispatched } ?: return
        if (outcome is ConditionOutcome.Error) {
            store.mark(window.key, TriggerWindowState.Failed, outcome.reason)
            return
        }
        outcome as ConditionOutcome.Checked
        val trigger = store.get(window.triggerId) ?: return
        val wasMet = trigger.lastConditionMet == true
        store.upsert(trigger.copy(lastConditionMet = outcome.met))
        if (!outcome.met || wasMet) {
            store.mark(window.key, TriggerWindowState.Skipped, if (wasMet) "condition_unchanged" else "condition_not_met")
            return
        }
        store.mark(window.key, TriggerWindowState.ActionDispatched)
        try { TaskService.startTrigger(context, goal, threadId, key, null) }
        catch (e: Exception) { reportStartFailure(context, store, trigger, window.key, e, resetCondition = true) }
    }

    @Synchronized fun list(context: Context): List<TriggerDefinition> = store(context).all()

    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized fun snapshot(context: Context): TriggerSnapshot = store(context).let {
        TriggerSnapshot(it.all(), it.windows())
    }

    /** 存在しない計画や実行済みの単発計画の再開は false を返します。[TriggerEngine.setEnabled] を参照してください。 */
    @Synchronized fun setEnabled(context: Context, id: String, enabled: Boolean): Boolean =
        store(context).let { engine(context, it).setEnabled(id, enabled, System.currentTimeMillis()) }

    @Synchronized fun remove(context: Context, id: String) {
        val store = store(context)
        store.remove(id)
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun reschedule(context: Context, id: String, nextAtMs: Long) {
        val store = store(context)
        store.get(id)?.let { trigger ->
            store.upsert(trigger.rescheduledTo(nextAtMs))
        }
        engine(context, store).rearm(System.currentTimeMillis())
    }

    @Synchronized fun runNow(context: Context, id: String) {
        val store = store(context)
        val trigger = store.get(id) ?: error(tr(R.string.trigger_deleted))
        val window = store.claimNow(id, System.currentTimeMillis()) ?: error(tr(R.string.trigger_busy))
        try {
            if (trigger.reminderText != null) deliverReminder(context, store, trigger, window.key)
            else TaskService.startTrigger(context, trigger.goal, trigger.threadId, window.key, trigger.conditionGoal)
            store.mark(window.key, TriggerWindowState.Dispatched)
        } catch (e: Exception) {
            reportStartFailure(context, store, trigger, window.key, e)
            throw e
        } finally {
            engine(context, store).rearm(System.currentTimeMillis())
        }
    }

    private fun store(context: Context): FileTriggerStore {
        val store = FileTriggerStore(File(context.filesDir, "triggers-v3.json"))
        if (!recovered) {
            store.windows().filter { it.state in setOf(TriggerWindowState.Claimed,
                TriggerWindowState.Dispatched, TriggerWindowState.ActionDispatched) }.forEach {
                val trigger = store.get(it.triggerId)
                if (trigger?.reminderText != null) {
                    runCatching { deliverReminder(context, store, trigger, it.key) }
                        .onFailure { error -> store.mark(it.key, TriggerWindowState.Unknown, error::class.simpleName) }
                } else {
                    store.mark(it.key, TriggerWindowState.Unknown, "process restarted before result was confirmed")
                    if (trigger != null && !hasSavedResult(context, it.key))
                        runCatching { reportUncertain(context, trigger, it.key) }
                            .onFailure { error -> Log.w("MikaTrigger", "could not report recovered task", error) }
                }
            }
            recovered = true
        }
        return store
    }

    private fun engine(context: Context, store: FileTriggerStore) = TriggerEngine(store,
        { at -> arm(context, at) }, isIdle = { !((context.applicationContext as MikaApp).taskState.busy.value) }) { trigger, window ->
            if (trigger.reminderText != null) deliverReminder(context, store, trigger, window.key)
            else try { TaskService.startTrigger(context, trigger.goal, trigger.threadId, window.key, trigger.conditionGoal) }
                catch (e: Exception) { reportStartFailure(context, store, trigger, window.key, e); throw e }
        }

    private fun deliverReminder(context: Context, store: FileTriggerStore, trigger: TriggerDefinition, key: String) {
        val text = trigger.reminderText ?: return
        val conversations = ConversationStore.shared(context)
        val runId = "reminder:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, text, "succeeded", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_reminder), text)
        store.mark(key, TriggerWindowState.Delivered)
        store.retireDeliveredOneShots()
    }

    private fun reportStartFailure(context: Context, store: FileTriggerStore, trigger: TriggerDefinition,
        key: String, error: Exception, resetCondition: Boolean = false) {
        val message = tr(R.string.trigger_not_started_body)
        Log.w("MikaTrigger", "scheduled task could not start", error)
        store.mark(key, TriggerWindowState.Failed, tr(R.string.trigger_start_failed))
        if (resetCondition) store.get(trigger.id)?.let { store.upsert(it.copy(lastConditionMet = false)) }
        val conversations = ConversationStore.shared(context)
        val runId = "schedule-failed:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, message, "failed", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_not_started), message)
    }

    private fun hasSavedResult(context: Context, key: String): Boolean =
        FileRunStateStore(File(context.filesDir, "runs-v3.json")).all().any {
            it.triggerWindowKey == key && it.reply != null
        }

    private fun reportUncertain(context: Context, trigger: TriggerDefinition, key: String) {
        val message = tr(R.string.trigger_interrupted_body)
        val conversations = ConversationStore.shared(context)
        val runId = "schedule-uncertain:$key"
        if (conversations.turns(trigger.threadId).none { it.runId == runId }) {
            conversations.append(trigger.threadId, Turn(trigger.goal, message, "unverified", runId = runId))
        }
        notifyLocal(context, trigger.threadId, key, tr(R.string.trigger_unconfirmed), message)
    }

    private fun notifyLocal(context: Context, threadId: String, key: String, title: String, text: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        TaskService.ensureChannels(context)
        val open = PendingIntent.getActivity(context, key.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, threadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (notifications.areNotificationsEnabled()) {
            runCatching { notifications.notify(key.hashCode(), Notification.Builder(context, TaskService.CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(title)
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()) }
                .onFailure { Log.w("MikaTrigger", "reminder notification failed", it) }
        }
    }

    private fun arm(context: Context, atMs: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, TriggerAlarmReceiver::class.java).setAction(ACTION)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pending)
        if (atMs != null && !com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) {
            if (canScheduleExactly(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pending)
        }
    }

    private const val ACTION = "com.wangzan101.mika.task.TRIGGER_ALARM"
    private const val PREFS = "triggers"
    private const val PROMPTED = "exact_alarm_prompted"
    private const val KEY_DEVICE_ZONE = "device_timezone"
}

/** 時間厳守目覚まし時計許可ガイダンス: アラームがオンになっていない場合に 1 回だけ通知され、その後はスケジュールされたタスク ページに常に書き込まれます。 */
object ExactAlarmGuide {
    val TITLE: String get() = tr(R.string.trigger_alarm_title)
    val BODY: String get() = tr(R.string.trigger_alarm_body)

    fun shouldPrompt(granted: Boolean, prompted: Boolean): Boolean = !granted && !prompted
}

class TriggerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.wangzan101.mika.quit.MikaQuit.blocksWork(context)) return
        if (intent.action != "com.wangzan101.mika.task.TRIGGER_ALARM") return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { TriggerRuntime.fire(context.applicationContext) }
            catch (e: Exception) { Log.e("MikaTrigger", "alarm dispatch failed", e) }
            finally { pending.finish() }
        }
    }
}
