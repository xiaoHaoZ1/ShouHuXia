package io.github.xiaohaoz1.jiarenxia.guardian.core

/**
 * 状态机核心（纯 Kotlin，不碰 Android API）。
 * 一切状态流转走 handle(Event)，所有处理 @Synchronized，幂等可重入。
 *
 * 持久化通过 Store SPI（data/GuardStore 实现；单测用内存 Fake）。
 *
 * 真实报警序列（ALERTING）：先群发紧急短信，再按顺序依次拨打有效联系人，
 * 每通结束后查通话记录判定接通；STOP_ON_ANSWER 有人接通即停，
 * 一轮无人接通最多重拨 MAX_ROUNDS 轮，最后群发结果短信。
 * 序列进度持久化在 alertSeq，进程被杀后可续拨不重拨、不重复群发。
 *
 * 实机演练（Drill）是独立于守护开关的状态机：守护未开启时也能演练
 * （"演练通过"是开启的前提），守护开启后演练与真实监测并行，
 * 真实报警永远优先——真实守护离开 MONITORING 时演练立即中止。
 */
class GuardEngine(
    private val store: Store,
    private val clock: Clock,
    private val ports: Ports,
) {
    /** 引擎与持久层之间的存储接口。 */
    interface Store {
        fun loadConfig(): GuardConfig
        fun saveConfig(c: GuardConfig)
        fun loadState(): GuardState
        fun saveState(s: GuardState)
    }

    /** 拨号序列归属：真实报警（alertSeq）或演练（drill.calls）。 */
    private enum class SeqOwner { ALERT, DRILL }

    private var config: GuardConfig = store.loadConfig()
    private var state: GuardState = store.loadState()

    /** UI/测试读取快照用（只读拷贝）。 */
    @Synchronized
    fun snapshot(): GuardState = state

    @Synchronized
    fun snapshotConfig(): GuardConfig = config

    @Synchronized
    fun handle(e: Event) {
        when (e) {
            is Event.EnabledChanged -> onEnabledChanged(e.enabled)
            is Event.Activity -> onActivity(e.kind, e.at)
            // 演练部分先做：未开启时这些事件也要驱动演练
            Event.UserPresent -> {
                onDrillUserPresent()
                onActivity(ActivityKind.UNLOCK, null)
            }
            Event.ScreenOff -> {
                onDrillScreenOff()
                onScreenOff()
            }
            Event.SyncSession -> onSyncSession()
            Event.DeadlineAlarm -> onDeadline(force = false)
            Event.ForceDeadline -> onDeadline(force = true)
            Event.ConfirmEndAlarm -> onConfirmEnd()
            Event.CallRinging -> onCallRinging()
            Event.CallOffhook -> onCallOffhook()
            Event.CallIdle -> onCallIdle()
            Event.CallCheckAlarm -> onCallCheckAlarm()
            Event.CallFallbackAlarm -> onCallFallback()
            Event.NextRoundAlarm -> onNextRoundAlarm()
            is Event.SmsSent -> onSmsSent(e)
            Event.ImOk -> onActivity(ActivityKind.MANUAL_IM_OK, null)
            Event.AlertNow -> onAlertNow()
            Event.WatchdogAlarm -> onWatchdog()
            Event.DailySmsAlarm -> onDailySms()
            Event.SystemReset -> doSystemReset(abortDrill = true)
            Event.ClockShift -> onClockShift()
            Event.ConfigChanged -> onConfigChanged()
            is Event.DrillStart -> onDrillStart(e.waitMinutes)
            Event.DrillStop -> onDrillStop()
            Event.DrillAlarm -> onDrillAlarm()
            Event.DrillConfirmEndAlarm -> onDrillConfirmEnd()
            is Event.DrillScreenShown -> onDrillScreenShown(e.keyguardLocked)
            is Event.DrillContactAnswer -> onDrillContactAnswer(e)
        }
    }

    private fun now(): Long = clock.now()

    private fun saveState() = store.saveState(state)

    private fun saveConfig() = store.saveConfig(config)

    // ---------- 开关 ----------

    /** 有效演练记录（qualifiedPass）：通过 + 等待达标 + 版本/系统指纹/联系人都未变。 */
    private fun drillRecordValid(): Boolean = DrillRules.isRecordValid(
        state.qualifiedPass,
        ports.appVersionCode(),
        ports.buildFingerprint(),
        DrillRules.contactsKey(config.validContacts),
    )

    private fun onEnabledChanged(enabled: Boolean) {
        if (enabled) {
            if (config.validContacts.isEmpty()) {
                ports.log(TAG, "未设置紧急联系人，无法开启守护")
                return
            }
            if (!drillRecordValid()) {
                ports.log(TAG, "无有效演练记录，拒绝开启守护")
                return
            }
        }
        config = config.copy(enabled = enabled)
        saveConfig()
        if (enabled) {
            val now = now()
            // 开启瞬间手机若正在使用，先记为使用中会话（走使用中上限，不立刻倒计时）
            val inUse = ports.isDeviceInUse()
            state = state.copy(
                phase = Phase.MONITORING,
                lastActivityAt = now,
                lastActivityKind = ActivityKind.ENABLE,
                inUse = inUse,
                deadlineAt = TimeRules.computeDeadline(now, config, inUse),
                confirmEndAt = 0,
                selfDialing = false,
            )
            val next = TimeRules.nextDailySmsAt(now, config.dailySmsMinuteOfDay)
            state = state.copy(nextDailySmsAt = next)
            saveState()
            ports.scheduleDeadline(state.deadlineAt)
            ports.ensureServiceRunning()
            ports.scheduleDailySms(next)
            ports.scheduleWatchdog(WATCHDOG_INTERVAL_MS)
            ports.log(TAG, "守护已开启 deadline=${state.deadlineAt} nextSms=$next")
        } else {
            // 演练响铃进行中时不能误停演练的响铃（演练独立继续，见演练约定）
            if (state.drill.phase != DrillPhase.RINGING) {
                ports.stopRinging()
            }
            ports.cancelAll()
            // cancelAll 平台层已含通话闹钟；这里显式再取消一次确保 Fake/老实现也清干净
            ports.cancelCallAlarms()
            // 演练进行中：前台服务必须继续（收锁屏/解锁广播，也是被测对象）
            if (!state.drill.active) {
                // 停掉前台服务与常驻通知（旧版本 tag 状态通知一并清掉）
                ports.stopService()
            }
            // 关闭时正在拨号：序列不再续拨也不发短信，按已报警收尾
            val seq = if (state.phase == Phase.ALERTING)
                state.alertSeq.copy(step = SeqStep.DONE) else state.alertSeq
            val phase = if (state.phase == Phase.ALERTING) Phase.ALERTED else state.phase
            // 保留 phase/lastActivity 等字段便于 UI 显示，仅清掉未到期的闹钟锚点。
            state = state.copy(
                phase = phase,
                alertSeq = seq,
                deadlineAt = 0, confirmEndAt = 0, selfDialing = false, inUse = false
            )
            saveState()
            ports.log(TAG, "守护已关闭")
        }
    }

    // ---------- 活动 ----------

    private fun onActivity(kind: ActivityKind, at: Long?) {
        if (!config.enabled) {
            if (!state.drill.active) {
                ports.log(TAG, "忽略活动（未开启）kind=$kind")
            }
            return
        }
        // 时钟防呆：外部给的未来时间戳一律钳到 now，避免 deadline 被推到遥远未来
        val ts = minOf(at ?: now(), now())
        val prevPhase = state.phase
        val wasAlerting = prevPhase != Phase.MONITORING
        // 解锁开始使用会话，使用后熄屏结束会话；其他活动不改变会话标记
        val inUse = when (kind) {
            ActivityKind.UNLOCK -> true
            ActivityKind.LOCK_AFTER_USE -> false
            else -> state.inUse
        }
        state = state.copy(
            phase = Phase.MONITORING,
            lastActivityAt = ts,
            lastActivityKind = kind,
            inUse = inUse,
            lastLockAt =
                if (kind == ActivityKind.LOCK_AFTER_USE) ts else state.lastLockAt,
            deadlineAt = TimeRules.computeDeadline(ts, config, inUse),
            confirmEndAt = 0,
            selfDialing = false,
        )
        // 解除报警：清拨号闹钟；已发过紧急短信时群发"已解除"；序列与短信标记复位
        if (prevPhase == Phase.ALERTING || prevPhase == Phase.ALERTED) {
            ports.cancelCallAlarms()
            if (state.alertSmsFlags and GuardState.SMS_EMERGENCY_SENT != 0 &&
                state.alertSmsFlags and GuardState.SMS_RESOLVED_SENT == 0
            ) {
                sendGroup(SmsKind.RESOLVED, SmsText.resolved())
                state = state.copy(
                    alertSmsFlags = state.alertSmsFlags or GuardState.SMS_RESOLVED_SENT
                )
            }
            state = state.copy(alertSeq = CallSeq(), alertSmsFlags = 0)
        }
        saveState()
        if (wasAlerting) {
            ports.stopRinging()
            ports.cancelConfirmAndAlert()
        }
        ports.scheduleDeadline(state.deadlineAt)
        ports.log(TAG, "活动 kind=${kind.label} ts=$ts deadline=${state.deadlineAt}")
    }

    /** 熄屏：使用会话中 → 记 LOCK_AFTER_USE 重新计时；报警流程中 → 仅结束会话，不算平安。 */
    private fun onScreenOff() {
        if (!config.enabled) {
            if (!state.drill.active) {
                ports.log(TAG, "忽略熄屏（未开启）")
            }
            return
        }
        if (!state.inUse) return
        if (state.phase == Phase.MONITORING) {
            onActivity(ActivityKind.LOCK_AFTER_USE, null)
        } else {
            // 响铃/拨号流程里的熄屏不能当“人没事”，只把会话标记收回
            state = state.copy(inUse = false)
            saveState()
            ports.log(TAG, "报警流程中熄屏：仅结束使用会话，不算平安")
        }
    }

    /**
     * 会话对账：只在 MONITORING 下用 isDeviceInUse 实况校对 inUse。
     * CONFIRMING/ALERTING/ALERTED 一律不动——屏幕状态永远不能取消报警。
     */
    private fun syncSession() {
        if (!config.enabled || state.phase != Phase.MONITORING) return
        val live = ports.isDeviceInUse()
        if (live && !state.inUse) {
            // 漏掉的解锁广播（服务被杀等）：补记一次使用
            onActivity(ActivityKind.UNLOCK, null)
        } else if (!live && state.inUse) {
            // 会话结束但没收到熄屏广播：保守地从上次活动按锁屏后阈值重算
            state = state.copy(
                inUse = false,
                deadlineAt = TimeRules.computeDeadline(state.lastActivityAt, config, false),
            )
            saveState()
            ports.scheduleDeadline(state.deadlineAt)
            ports.log(TAG, "会话已结束（未收到熄屏广播），按上次活动重新计时")
        }
    }

    /**
     * 会话对账 + 闹钟自愈：进程被强停会带走全部闹钟，服务启动/守护页可见时补挂。
     * CONFIRMING 只补挂确认结束闹钟——过期窗口交给看门狗，绝不在此拨号。
     * ALERTING 续走拨号序列（不重复群发、不重拨已判定的那通）。
     * 演练的自愈与超时判定独立于守护开关，先做。
     */
    private fun onSyncSession() {
        onDrillSync()
        syncSession()
        if (!config.enabled) return
        val now = now()
        when (state.phase) {
            Phase.MONITORING -> {
                // 持久 deadline 若落进免打扰时段（改配置/跨时区后），先顺延再判断
                val dl = TimeRules.deferQuiet(state.deadlineAt, config)
                if (dl != state.deadlineAt) {
                    state = state.copy(deadlineAt = dl)
                    saveState()
                }
                if (state.deadlineAt in 1..now) {
                    onDeadline(force = false)
                } else if (state.deadlineAt > now) {
                    ports.scheduleDeadline(state.deadlineAt)
                }
            }
            Phase.CONFIRMING ->
                if (state.confirmEndAt > now) {
                    ports.scheduleConfirmEnd(state.confirmEndAt)
                }
            Phase.ALERTING -> resumeSeq(SeqOwner.ALERT)
            Phase.ALERTED -> Unit
        }
        // 平安短信与看门狗闹钟始终补挂
        val next = if (state.nextDailySmsAt > now) {
            state.nextDailySmsAt
        } else {
            TimeRules.nextDailySmsAt(now, config.dailySmsMinuteOfDay)
        }
        if (next != state.nextDailySmsAt) {
            state = state.copy(nextDailySmsAt = next)
            saveState()
        }
        ports.scheduleDailySms(next)
        ports.scheduleWatchdog(WATCHDOG_INTERVAL_MS)
    }

    // ---------- 到期 / 确认 ----------

    private fun onDeadline(force: Boolean) {
        if (!config.enabled || state.phase != Phase.MONITORING) return
        // UsageStats 兜底：服务被杀期间可能漏掉解锁事件
        val missed = ports.reconcileMissedUnlockSince(state.lastActivityAt)
        if (missed != null && missed > state.lastActivityAt) {
            ports.log(TAG, "UsageStats 找回解锁 ts=$missed")
            onActivity(ActivityKind.USAGE_STATS, missed)
            return
        }
        val now = now()
        if (!force) {
            // 持久 deadline 可能落在新设的免打扰时段内（改配置/跨时区后）：顺延，不报警
            val dl = TimeRules.deferQuiet(state.deadlineAt, config)
            if (dl != state.deadlineAt) {
                state = state.copy(deadlineAt = dl)
                saveState()
                ports.scheduleDeadline(dl)
                ports.log(TAG, "deadline 落在免打扰时段，顺延到 $dl")
                return
            }
            if (now < state.deadlineAt) {
                // 闹钟提前响了（系统对齐等），按持久 deadline 重挂
                ports.scheduleDeadline(state.deadlineAt)
                return
            }
        }
        enterConfirming(now)
    }

    private fun enterConfirming(now: Long) {
        // 真实报警永远优先：演练立即中止，且不动真实响铃/通知
        if (state.drill.active) abortDrillForRealAlert("真实报警进行中，演练中止")
        state = state.copy(
            phase = Phase.CONFIRMING,
            confirmEndAt = now + config.effectiveConfirmWindowMillis(),
        )
        saveState()
        ports.startConfirmRinging(state.confirmEndAt)
        ports.showConfirmNotification(state.confirmEndAt)
        ports.scheduleConfirmEnd(state.confirmEndAt)
        ports.log(TAG, "进入确认 phase=CONFIRMING confirmEnd=${state.confirmEndAt}")
    }

    private fun onConfirmEnd() {
        if (state.phase != Phase.CONFIRMING) return
        if (!config.enabled) {
            ports.log(TAG, "确认结束闹钟到时但已关闭守护，忽略")
            return
        }
        val now = now()
        if (now < state.confirmEndAt) {
            ports.scheduleConfirmEnd(state.confirmEndAt)
            return
        }
        dialOut("确认超时无人响应")
    }

    // ---------- 拨号序列（真实报警与演练共用） ----------

    /** 当前活动序列：真实报警优先，其次演练 CALLING；否则 null。 */
    private fun activeOwner(): SeqOwner? = when {
        config.enabled && state.phase == Phase.ALERTING -> SeqOwner.ALERT
        state.drill.phase == DrillPhase.CALLING -> SeqOwner.DRILL
        else -> null
    }

    private fun seqOf(owner: SeqOwner): CallSeq =
        if (owner == SeqOwner.ALERT) state.alertSeq else state.drill.calls

    private fun writeSeq(owner: SeqOwner, seq: CallSeq) {
        state = if (owner == SeqOwner.ALERT) {
            state.copy(alertSeq = seq)
        } else {
            state.copy(drill = state.drill.copy(calls = seq))
        }
    }

    /** 群发短信给全部有效联系人，返回"提交成功"位掩码；失败只记日志不阻断。 */
    private fun sendGroup(kind: SmsKind, body: String): Int {
        var mask = 0
        config.validContacts.forEachIndexed { i, c ->
            val ok = try {
                ports.sendSms(c.number, body, kind, i)
            } catch (t: Throwable) {
                ports.log(TAG, "短信提交异常 kind=$kind index=$i $t")
                false
            }
            if (ok) mask = mask or (1 shl i)
            ports.log(TAG, "短信群发 kind=$kind index=$i " +
                maskNumber(c.number) + " submitted=$ok")
        }
        return mask
    }

    /** 短信发出结果回传：演练的 DRILL_TEST 更新 smsOk/smsFail 掩码（任一失败即失败）。 */
    private fun onSmsSent(e: Event.SmsSent) {
        val number = config.validContacts.getOrNull(e.index)?.number ?: ""
        ports.log(TAG, "短信回执 kind=${e.kind} index=${e.index} " +
            maskNumber(number) + " ok=${e.ok}")
        val d = state.drill
        if (e.kind == SmsKind.DRILL_TEST &&
            (d.phase == DrillPhase.CALLING || d.phase == DrillPhase.ASK_ANSWER)
        ) {
            val bit = 1 shl e.index
            state = state.copy(
                drill = d.copy(
                    smsOkMask = if (e.ok) d.smsOkMask or bit else d.smsOkMask,
                    smsFailMask = if (e.ok) d.smsFailMask else d.smsFailMask or bit,
                )
            )
            saveState()
        }
    }

    /** CONFIRMING 结束 / AlertNow：进入真实报警序列。 */
    private fun dialOut(reason: String) {
        // 真实报警永远优先：演练立即中止
        if (state.drill.active) abortDrillForRealAlert("真实报警进行中，演练中止")
        state = state.copy(
            phase = Phase.ALERTING,
            alertSeq = CallSeq(
                step = SeqStep.START, startedAt = now(), round = 1, index = 0
            ),
            alertSmsFlags = 0,
            alertedAt = 0,
        )
        saveState()
        ports.stopRinging()
        ports.log(TAG, "进入拨号序列 reason=$reason")
        alertStep()
    }

    /** 真实序列 START：先群发紧急短信（立即置位保存，先于任何拨号），再拨当前位。 */
    private fun alertStep() {
        val seq = state.alertSeq
        if (seq.step != SeqStep.START) return
        if (state.alertSmsFlags and GuardState.SMS_EMERGENCY_SENT == 0) {
            val bat = ports.batteryStatus()
            sendGroup(
                SmsKind.EMERGENCY,
                SmsText.emergency(state.lastActivityAt, now(), bat.pct, bat.charging),
            )
            // 发完立即置位保存：进程在随后拨号中死掉也不会重复群发
            state = state.copy(
                alertSmsFlags = state.alertSmsFlags or GuardState.SMS_EMERGENCY_SENT
            )
            saveState()
        }
        dialCurrent(SeqOwner.ALERT)
    }

    /**
     * 拨当前位（真实/演练共用）：先持久化 DIALING 再拨号——进程在 placeCall
     * 中途死掉时，恢复逻辑把它当"已拨未判定"补查，绝不重拨。
     */
    private fun dialCurrent(owner: SeqOwner) {
        val contacts = config.validContacts
        val seq = seqOf(owner)
        val c = contacts.getOrNull(seq.index)
        if (c == null) {
            // 有效联系人为空（理论上不会）：按无人接听收尾
            ports.log(TAG, "无有效联系人可拨，按无人接听收尾 owner=$owner")
            if (owner == SeqOwner.ALERT) {
                finishAlert(SmsKind.NO_ANSWER, SmsText.noAnswer())
            } else {
                ports.cancelCallAlarms()
                writeSeq(owner, seq.copy(step = SeqStep.DONE))
                state = state.copy(
                    drill = state.drill.copy(phase = DrillPhase.ASK_ANSWER, askIndex = 0)
                )
                saveState()
            }
            return
        }
        val now = now()
        writeSeq(owner, seq.copy(
            step = SeqStep.DIALING,
            dialedAt = now,
            dialedNumber = c.number,
            offhookSeen = false,
            checks = 0,
        ))
        state = state.copy(selfDialing = true)
        saveState()
        ports.scheduleCallFallback(now + AlertRules.CALL_FALLBACK_MS)
        val ok = try {
            ports.placeCall(c.number)
        } catch (se: SecurityException) {
            ports.log(TAG, "拨号权限不足 ${se.message}")
            false
        } catch (t: Throwable) {
            ports.log(TAG, "拨号异常 $t")
            false
        }
        val s2 = seqOf(owner)
        writeSeq(owner, if (ok) s2.copy(placedMask = s2.placedMask or (1 shl s2.index)) else s2)
        state = state.copy(selfDialing = false)
        saveState()
        ports.log(TAG, "序列拨号 owner=$owner index=${s2.index} " +
            maskNumber(c.number) + " ok=$ok")
        if (!ok) {
            // 发起失败：本通直接判未接通，走下一位
            onCallVerdict(owner, answered = false)
        }
    }

    /** 通话结束回到 IDLE：见过 OFFHOOK 的拨号转查记录；否则忽略（陈旧广播，交兜底闹钟）。 */
    private fun onCallIdle() {
        val owner = activeOwner() ?: return
        val seq = seqOf(owner)
        if (seq.step != SeqStep.DIALING || !seq.offhookSeen) {
            ports.log(TAG, "忽略 IDLE（step=${seq.step} offhookSeen=${seq.offhookSeen}）")
            return
        }
        writeSeq(owner, seq.copy(step = SeqStep.CHECKING, checks = 0))
        saveState()
        ports.scheduleCallCheck(now() + AlertRules.CALL_LOG_DELAY_MS)
        ports.log(TAG, "通话结束转查记录 owner=$owner index=${seq.index}")
    }

    /** 兜底闹钟：DIALING 超时时若仍在通话则再等，否则转查记录。 */
    private fun onCallFallback() {
        val owner = activeOwner() ?: return
        val seq = seqOf(owner)
        if (seq.step != SeqStep.DIALING) return
        val now = now()
        if (now < seq.dialedAt + AlertRules.CALL_FALLBACK_MS) {
            // 闹钟提前到了，重挂到原定兜底时刻
            ports.scheduleCallFallback(seq.dialedAt + AlertRules.CALL_FALLBACK_MS)
            return
        }
        if (ports.isInCall()) {
            // 通话仍在进行（IDLE 广播丢了）：再等一分钟
            ports.scheduleCallFallback(now + AlertRules.CALL_FALLBACK_RECHECK_MS)
            return
        }
        writeSeq(owner, seq.copy(step = SeqStep.CHECKING, checks = 0))
        saveState()
        ports.log(TAG, "兜底超时转查记录 owner=$owner index=${seq.index}")
        runCallCheck(owner)
    }

    private fun onCallCheckAlarm() {
        val owner = activeOwner() ?: return
        if (seqOf(owner).step != SeqStep.CHECKING) return
        runCallCheck(owner)
    }

    /**
     * 查通话记录判定本通是否接通：ANSWERED→接通；NOT_ANSWERED/UNAVAILABLE→未接通；
     * NOT_FOUND 重试至上限后按未接通。
     */
    private fun runCallCheck(owner: SeqOwner) {
        val seq = seqOf(owner)
        val r = try {
            ports.queryOutgoingCall(
                seq.dialedNumber,
                seq.dialedAt - AlertRules.CALL_LOG_SINCE_TOLERANCE_MS,
            )
        } catch (t: Throwable) {
            ports.log(TAG, "查通话记录异常 $t")
            CallLogResult.UNAVAILABLE
        }
        ports.log(TAG, "通话记录 owner=$owner index=${seq.index} " +
            maskNumber(seq.dialedNumber) + " result=$r checks=${seq.checks}")
        when (r) {
            CallLogResult.ANSWERED -> onCallVerdict(owner, answered = true)
            CallLogResult.NOT_ANSWERED, CallLogResult.UNAVAILABLE ->
                onCallVerdict(owner, answered = false)
            CallLogResult.NOT_FOUND -> {
                val checks = seq.checks + 1
                if (checks < AlertRules.CALL_LOG_MAX_CHECKS) {
                    writeSeq(owner, seq.copy(checks = checks))
                    saveState()
                    ports.scheduleCallCheck(now() + AlertRules.CALL_LOG_RETRY_MS)
                } else {
                    onCallVerdict(owner, answered = false)
                }
            }
        }
    }

    /** 本通判定完成：清通话闹钟，记掩码，交给各自推进逻辑。 */
    private fun onCallVerdict(owner: SeqOwner, answered: Boolean) {
        ports.cancelCallAlarms()
        val seq = seqOf(owner)
        if (answered) {
            val bit = 1 shl seq.index
            writeSeq(owner, seq.copy(
                answeredMask = seq.answeredMask or bit,
                roundAnsweredMask = seq.roundAnsweredMask or bit,
            ))
            saveState()
        }
        ports.log(TAG, "本通判定 owner=$owner index=${seq.index} answered=$answered")
        if (owner == SeqOwner.ALERT) alertAdvance(answered) else drillAdvance()
    }

    /** 真实序列推进：接通策略 / 下一位 / 下一轮 / 收尾群发。 */
    private fun alertAdvance(answered: Boolean) {
        val seq = state.alertSeq
        val contacts = config.validContacts
        when {
            answered && config.answerPolicy == AnswerPolicy.STOP_ON_ANSWER ->
                finishAlert(
                    SmsKind.ANSWERED,
                    SmsText.answered(
                        contacts.getOrNull(seq.index)?.label() ?: ""
                    ),
                )
            seq.index + 1 < contacts.size -> {
                writeSeq(SeqOwner.ALERT, seq.copy(index = seq.index + 1))
                saveState()
                dialCurrent(SeqOwner.ALERT)
            }
            seq.roundAnsweredMask != 0 -> finishAlert(
                SmsKind.SUMMARY,
                SmsText.summary(
                    contacts.mapIndexedNotNull { i, c ->
                        if (seq.roundAnsweredMask and (1 shl i) != 0) c.label() else null
                    }
                ),
            )
            seq.round < AlertRules.MAX_ROUNDS -> {
                val nextAt = now() + AlertRules.ROUND_INTERVAL_MS
                writeSeq(SeqOwner.ALERT, seq.copy(
                    step = SeqStep.ROUND_WAIT, nextRoundAt = nextAt
                ))
                saveState()
                ports.scheduleNextRound(nextAt)
                ports.log(TAG, "本轮无人接通，等下一轮 nextRoundAt=$nextAt")
            }
            else -> finishAlert(SmsKind.NO_ANSWER, SmsText.noAnswer())
        }
    }

    /** 演练推进：不论策略每位只拨一次，拨完转逐位问答。 */
    private fun drillAdvance() {
        val d = state.drill
        val n = config.validContacts.size
        if (d.calls.index + 1 < n) {
            writeSeq(SeqOwner.DRILL, d.calls.copy(index = d.calls.index + 1))
            saveState()
            dialCurrent(SeqOwner.DRILL)
        } else {
            ports.cancelCallAlarms()
            state = state.copy(
                drill = d.copy(
                    phase = DrillPhase.ASK_ANSWER,
                    askIndex = 0,
                    calls = d.calls.copy(step = SeqStep.DONE),
                )
            )
            saveState()
            ports.log(TAG, "演练拨号完毕，进入逐位问答")
        }
    }

    /** 下一轮闹钟到点：从第 1 位重拨。 */
    private fun onNextRoundAlarm() {
        val owner = activeOwner()
        if (owner != SeqOwner.ALERT) return
        val seq = state.alertSeq
        if (seq.step != SeqStep.ROUND_WAIT) return
        val now = now()
        if (now < seq.nextRoundAt) {
            ports.scheduleNextRound(seq.nextRoundAt)
            return
        }
        writeSeq(SeqOwner.ALERT, seq.copy(
            round = seq.round + 1, index = 0, roundAnsweredMask = 0
        ))
        saveState()
        ports.log(TAG, "开始第 ${seq.round + 1} 轮拨号")
        dialCurrent(SeqOwner.ALERT)
    }

    /** 序列收尾：群发结果短信（只发一次），相位落 ALERTED，掩码保留供 UI 显示。 */
    private fun finishAlert(kind: SmsKind, body: String) {
        if (state.alertSmsFlags and GuardState.SMS_FINAL_SENT == 0) {
            sendGroup(kind, body)
            state = state.copy(
                alertSmsFlags = state.alertSmsFlags or GuardState.SMS_FINAL_SENT
            )
        }
        val now = now()
        state = state.copy(
            phase = Phase.ALERTED,
            alertedAt = now,
            selfDialing = false,
            alertSeq = state.alertSeq.copy(step = SeqStep.DONE),
        )
        saveState()
        ports.showAlertedNotification(now)
        ports.log(TAG, "拨号序列结束 kind=$kind alertedAt=$now")
    }

    /**
     * 进程恢复（SystemReset/Watchdog/SyncSession）：按持久化的 step 续上。
     * 判定过的那通 index 已前进、群发已置位——不重复拨号、不重复群发。
     */
    private fun resumeSeq(owner: SeqOwner) {
        val seq = seqOf(owner)
        val now = now()
        when (seq.step) {
            SeqStep.START -> if (owner == SeqOwner.ALERT) alertStep() else drillStep()
            SeqStep.DIALING -> {
                val fallbackAt = seq.dialedAt + AlertRules.CALL_FALLBACK_MS
                if (now < fallbackAt) {
                    // 拨号可能刚开始：只补挂兜底闹钟，不在此查记录（防误判）
                    ports.scheduleCallFallback(fallbackAt)
                } else if (ports.isInCall()) {
                    ports.scheduleCallFallback(now + AlertRules.CALL_FALLBACK_RECHECK_MS)
                } else {
                    writeSeq(owner, seq.copy(step = SeqStep.CHECKING, checks = 0))
                    saveState()
                    runCallCheck(owner)
                }
            }
            SeqStep.CHECKING -> runCallCheck(owner)
            SeqStep.ROUND_WAIT ->
                if (now < seq.nextRoundAt) {
                    ports.scheduleNextRound(seq.nextRoundAt)
                } else {
                    writeSeq(owner, seq.copy(
                        round = seq.round + 1, index = 0, roundAnsweredMask = 0
                    ))
                    saveState()
                    dialCurrent(owner)
                }
            SeqStep.IDLE, SeqStep.DONE -> Unit
        }
        ports.log(TAG, "序列恢复 owner=$owner step=${seqOf(owner).step}")
    }

    // ---------- 其他事件 ----------

    private fun onCallRinging() {
        state = state.copy(lastRingAt = now())
        saveState()
        ports.log(TAG, "来电响铃 lastRingAt=${state.lastRingAt}")
    }

    private fun onCallOffhook() {
        val now = now()
        // 1) 活动序列已拨出且未见新来电响铃：这是我们自己拨的电话摘机，不算活动
        val owner = activeOwner()
        if (owner != null) {
            val seq = seqOf(owner)
            if (seq.step == SeqStep.DIALING && now >= seq.dialedAt &&
                state.lastRingAt <= seq.dialedAt
            ) {
                writeSeq(owner, seq.copy(offhookSeen = true))
                saveState()
                ports.log(TAG, "自拨号摘机 owner=$owner index=${seq.index}")
                return
            }
        }
        // 2) placeCall 正在执行：双保险
        if (state.selfDialing) {
            ports.log(TAG, "忽略自拨号摘机")
            return
        }
        // 3) 序列期间（拨号/查记录/轮间等待）没有新来电响铃的摘机都视为自己的电话
        if (config.enabled && state.phase == Phase.ALERTING &&
            state.lastRingAt <= state.alertSeq.dialedAt
        ) {
            ports.log(TAG, "忽略报警序列期间的摘机")
            return
        }
        if (state.drill.phase == DrillPhase.CALLING &&
            state.lastRingAt <= state.drill.calls.dialedAt
        ) {
            ports.log(TAG, "忽略演练拨号期间的摘机")
            return
        }
        // 4) 最后一通拨号之后的保护窗内、没有新来电响铃的摘机仍算自己的电话
        if (state.phase == Phase.ALERTED && state.alertSeq.dialedAt > 0 &&
            now - state.alertSeq.dialedAt <= ALERT_CALL_GUARD_MS &&
            state.lastRingAt <= state.alertSeq.dialedAt
        ) {
            ports.log(TAG, "忽略报警电话自身的摘机")
            return
        }
        // 5) 报警期间有新来电并接听 = 有人在用手机，走解除
        onActivity(ActivityKind.CALL, null)
    }

    private fun onAlertNow() {
        if (!config.enabled) return
        when (state.phase) {
            Phase.CONFIRMING, Phase.MONITORING -> dialOut("手动立即拨打")
            else -> ports.log(TAG, "AlertNow 在 ${state.phase} 下忽略")
        }
    }

    private fun onWatchdog() {
        // 关闭状态下闹钟残留不应拉起服务
        if (!config.enabled) return
        ports.ensureServiceRunning()
        syncSession()
        val now = now()
        when (state.phase) {
            Phase.MONITORING -> {
                val dl = TimeRules.deferQuiet(state.deadlineAt, config)
                if (dl != state.deadlineAt) {
                    state = state.copy(deadlineAt = dl)
                    saveState()
                    ports.scheduleDeadline(dl)
                } else if (dl in 1..now) {
                    onDeadline(force = false)
                }
            }
            Phase.CONFIRMING ->
                if (state.confirmEndAt in 1..now) {
                    onConfirmEnd()
                } else if (state.confirmEndAt > 0) {
                    // 进程被杀会带走响铃：确认窗口内自愈重启响铃与全屏通知
                    ports.startConfirmRinging(state.confirmEndAt)
                    ports.showConfirmNotification(state.confirmEndAt)
                }
            Phase.ALERTING -> resumeSeq(SeqOwner.ALERT)
            Phase.ALERTED -> Unit
        }
        ports.scheduleWatchdog(WATCHDOG_INTERVAL_MS)
        ports.log(TAG, "看门狗自查 phase=${state.phase} now=$now")
    }

    private fun onDailySms() {
        if (!config.enabled) return
        if (config.dailySmsEnabled && state.phase == Phase.MONITORING) {
            val bat = ports.batteryStatus()
            sendGroup(
                SmsKind.DAILY,
                SmsText.daily(
                    state.lastActivityAt, now(), bat.pct, bat.charging, state.inUse
                ),
            )
            ports.log(TAG, "已群发每日平安短信")
        } else if (config.dailySmsEnabled) {
            // 报警流程中不发平安短信——不能在报警时告诉家人"一切正常"
            ports.log(TAG, "报警流程中不发平安短信 phase=${state.phase}")
        }
        val next = TimeRules.nextDailySmsAt(now(), config.dailySmsMinuteOfDay)
        state = state.copy(nextDailySmsAt = next)
        saveState()
        ports.scheduleDailySms(next)
    }

    /**
     * 系统时钟变化（自动对时/手动改时区）：墙钟与 elapsedRealtime 差值算出
     * 计划时刻偏移，偏移 >60 秒说明计时基准失效才中止演练（不算失败）。
     */
    private fun onClockShift() {
        val d = state.drill
        if (d.active && d.phase == DrillPhase.WAITING &&
            d.plannedAt > 0 && d.plannedAtElapsed > 0
        ) {
            val shifted = now() + (d.plannedAtElapsed - ports.elapsedRealtime())
            if (kotlin.math.abs(shifted - d.plannedAt) > 60_000L) {
                finishDrill(
                    DrillOutcome.ABORTED,
                    "系统时间变化较大，演练计时基准失效，请重新开始",
                )
            }
        }
        doSystemReset(abortDrill = false)
    }

    /** abortDrill=true：重启/包升级，计时基准必失效，演练一律中止。 */
    private fun doSystemReset(abortDrill: Boolean) {
        if (abortDrill && state.drill.active) {
            finishDrill(DrillOutcome.ABORTED, "演练期间手机重启或应用更新，请重新开始")
        }
        if (!config.enabled) return
        ports.ensureServiceRunning()
        var now = now()
        // 时钟回拨防护：lastActivity 不得在未来
        var clamped = false
        if (state.lastActivityAt > now) {
            state = state.copy(lastActivityAt = now)
            saveState()
            clamped = true
            ports.log(TAG, "时钟回拨，lastActivity 钳到 $now")
        }
        syncSession()
        when (state.phase) {
            Phase.MONITORING -> {
                var dl = state.deadlineAt
                if (dl <= 0 || now >= dl || clamped ||
                    TimeRules.deferQuiet(dl, config) != dl
                ) {
                    dl = TimeRules.computeDeadline(state.lastActivityAt, config, state.inUse)
                }
                if (now >= dl) {
                    // 已过：先响铃给缓冲，不直接拨号
                    state = state.copy(deadlineAt = dl)
                    saveState()
                    ports.log(TAG, "SystemReset: deadline 已过，进入确认流程")
                    onDeadline(force = true)
                } else {
                    state = state.copy(deadlineAt = dl)
                    saveState()
                    ports.scheduleDeadline(dl)
                }
            }
            Phase.CONFIRMING -> {
                if (state.confirmEndAt <= 0) {
                    state = state.copy(confirmEndAt = now + config.effectiveConfirmWindowMillis())
                    saveState()
                }
                ports.startConfirmRinging(state.confirmEndAt)
                ports.showConfirmNotification(state.confirmEndAt)
                ports.scheduleConfirmEnd(state.confirmEndAt)
            }
            Phase.ALERTING ->
                // 拨号途中进程死/重启：按持久化序列进度续上，绝不重拨已判定的那通
                resumeSeq(SeqOwner.ALERT)
            Phase.ALERTED ->
                ports.log(TAG, "SystemReset: 已报警状态，仅记录")
        }
        // 保证日报/看门狗闹钟都在
        now = now()
        val next = if (state.nextDailySmsAt > now) {
            state.nextDailySmsAt
        } else {
            TimeRules.nextDailySmsAt(now, config.dailySmsMinuteOfDay)
        }
        if (next != state.nextDailySmsAt) {
            state = state.copy(nextDailySmsAt = next)
            saveState()
        }
        ports.scheduleDailySms(next)
        ports.scheduleWatchdog(WATCHDOG_INTERVAL_MS)
        ports.log(TAG, "SystemReset 完成 phase=${state.phase} deadline=${state.deadlineAt}")
    }

    private fun onConfigChanged() {
        config = store.loadConfig()
        ports.log(TAG, "配置更新 $config")
        // 演练进行中改联系人：快照 key 对不上 → 中止重来（与守护开关无关，先判）
        val d = state.drill
        if (d.active && d.contactsKey != DrillRules.contactsKey(config.validContacts)) {
            finishDrill(DrillOutcome.ABORTED, "演练期间修改了紧急联系人，请重新开始")
        }
        if (!config.enabled) return
        val now = now()
        if (state.phase == Phase.MONITORING) {
            val dl = TimeRules.computeDeadline(state.lastActivityAt, config, state.inUse)
            if (dl != state.deadlineAt) {
                state = state.copy(deadlineAt = dl)
                saveState()
                ports.scheduleDeadline(dl)
            }
        }
        val next = TimeRules.nextDailySmsAt(now, config.dailySmsMinuteOfDay)
        if (next != state.nextDailySmsAt) {
            state = state.copy(nextDailySmsAt = next)
            saveState()
            ports.scheduleDailySms(next)
        }
        ports.scheduleWatchdog(WATCHDOG_INTERVAL_MS)
    }

    // ---------- 实机演练 ----------

    /**
     * 发起演练：复用真实链路（闹钟 → 响铃/全屏页 → 测试短信 → 依次拨号）。
     * 前置条件：有紧急联系人；守护开启时须处于 MONITORING。
     * 从锁屏（SCREEN_OFF）开始计时；开始时已锁屏则立即起算。
     */
    private fun onDrillStart(waitMinutes: Int) {
        if (state.drill.active) {
            ports.log(TAG, "已有演练进行中，忽略重复开始")
            return
        }
        if (waitMinutes <= 0) return
        if (config.validContacts.isEmpty()) {
            ports.log(TAG, "未设置紧急联系人，不能开始演练")
            return
        }
        if (config.enabled && state.phase != Phase.MONITORING) {
            ports.log(TAG, "真实守护不在监测中（${state.phase}），不能开始演练")
            return
        }
        val now = now()
        // 开始瞬间就处于锁屏/熄屏：收不到 SCREEN_OFF，直接按已锁屏起算
        val locked = !ports.isDeviceInUse()
        val planned = if (locked) now + waitMinutes * 60_000L else 0L
        state = state.copy(
            drill = Drill(
                phase = if (locked) DrillPhase.WAITING else DrillPhase.WAIT_LOCK,
                waitMinutes = waitMinutes,
                startedAt = now,
                lockedAt = if (locked) now else 0L,
                plannedAt = planned,
                plannedAtElapsed =
                    if (locked) ports.elapsedRealtime() + (planned - now) else 0L,
                contactsKey = DrillRules.contactsKey(config.validContacts),
            )
        )
        saveState()
        if (planned > 0) ports.scheduleDrillAlarm(planned)
        // 前台服务：收 SCREEN_OFF/USER_PRESENT 广播，也是被测对象本身
        ports.ensureServiceRunning()
        ports.log(TAG, "演练开始 wait=${waitMinutes}分 locked=$locked planned=$planned")
    }

    /** 等待中的熄屏：从这一刻开始锁屏计时。 */
    private fun onDrillScreenOff() {
        val d = state.drill
        if (d.phase != DrillPhase.WAIT_LOCK) return
        val now = now()
        val planned = now + d.waitMinutes * 60_000L
        state = state.copy(
            drill = d.copy(
                phase = DrillPhase.WAITING,
                lockedAt = now,
                plannedAt = planned,
                plannedAtElapsed = ports.elapsedRealtime() + (planned - now),
            )
        )
        saveState()
        ports.scheduleDrillAlarm(planned)
        ports.log(TAG, "演练锁屏计时开始 planned=$planned")
    }

    /** 等待中的解锁：到点前解锁=中止；到点后仍未触发=失败（后台被清理）。 */
    private fun onDrillUserPresent() {
        val d = state.drill
        if (d.phase != DrillPhase.WAITING) return
        if (now() >= d.plannedAt) {
            finishDrill(
                DrillOutcome.FAILED,
                "到点没有响铃：后台很可能被系统清理，请检查省电策略、自启动、多任务锁定",
            )
        } else {
            finishDrill(DrillOutcome.ABORTED, "等待中解锁了手机，请锁屏后放着别动再试")
        }
    }

    /** 演练到点闹钟：进入响铃确认。 */
    private fun onDrillAlarm() {
        val d = state.drill
        if (d.phase != DrillPhase.WAITING) return
        val now = now()
        if (now < d.plannedAt) {
            // 闹钟提前响了，按计划时刻重挂
            ports.scheduleDrillAlarm(d.plannedAt)
            return
        }
        // 前台服务活着时解锁会先走 onDrillUserPresent 结束演练；到点闹钟送达时
        // 仍是"等待中+使用中"，只可能是 USER_PRESENT 没收到（进程被杀）或闹钟被
        // 推迟到解锁之后——两者都是演练要抓的失败，不是用户中止。
        if (ports.isDeviceInUse()) {
            state = state.copy(drill = d.copy(firedAt = now))
            finishDrill(
                DrillOutcome.FAILED,
                "锁屏期间守护未能按时响铃（后台可能被清理或闹钟被推迟），" +
                    "请检查省电策略、自启动、多任务锁定",
            )
            return
        }
        // 真实报警优先
        if (config.enabled && state.phase != Phase.MONITORING) {
            abortDrillForRealAlert("真实报警进行中，演练中止")
            return
        }
        val confirmEnd = now + config.drillConfirmWindowMillis()
        state = state.copy(
            drill = d.copy(
                phase = DrillPhase.RINGING,
                firedAt = now,
                confirmEndAt = confirmEnd,
            )
        )
        saveState()
        ports.startDrillRinging(confirmEnd)
        ports.scheduleDrillConfirmEnd(confirmEnd)
        ports.log(TAG, "演练到点响铃 firedAt=$now delay=${now - d.plannedAt}ms")
    }

    /** 全屏页上报已显示：取首次上报参与判定。 */
    private fun onDrillScreenShown(keyguardLocked: Boolean) {
        val d = state.drill
        if (d.phase != DrillPhase.RINGING || d.screenShownAt > 0) return
        state = state.copy(
            drill = d.copy(screenShownAt = now(), screenShownLocked = keyguardLocked)
        )
        saveState()
        ports.log(TAG, "演练全屏页已显示 locked=$keyguardLocked")
    }

    /** 演练确认窗口结束：群发测试短信并依次拨号，之后逐位询问。 */
    private fun onDrillConfirmEnd() {
        val d = state.drill
        if (d.phase != DrillPhase.RINGING) return
        val now = now()
        if (now < d.confirmEndAt) {
            ports.scheduleDrillConfirmEnd(d.confirmEndAt)
            return
        }
        // 逾期超宽限（闹钟丢了/送达太晚）：此时拨号只会惊吓联系人，判失败不拨号
        if (now - d.confirmEndAt > DrillRules.CONFIRM_END_GRACE_MS) {
            finishDrill(
                DrillOutcome.FAILED,
                "确认窗口结束后没有自动拨号：后台被清理",
            )
            return
        }
        if (config.enabled && state.phase != Phase.MONITORING) {
            abortDrillForRealAlert("真实报警进行中，演练中止")
            return
        }
        state = state.copy(
            drill = d.copy(
                phase = DrillPhase.CALLING,
                calls = CallSeq(step = SeqStep.START, startedAt = now, round = 1, index = 0),
            )
        )
        saveState()
        ports.stopRinging()
        ports.log(TAG, "演练进入拨号序列")
        drillStep()
    }

    /** 演练序列 START：群发测试短信（只发一次），再拨当前位；绝不发其他文案。 */
    private fun drillStep() {
        val d = state.drill
        if (d.phase != DrillPhase.CALLING || d.calls.step != SeqStep.START) return
        if (!d.smsSentFlag) {
            val submitted = sendGroup(SmsKind.DRILL_TEST, SmsText.drillTest())
            state = state.copy(
                drill = state.drill.copy(smsSentFlag = true, smsSubmittedMask = submitted)
            )
            saveState()
        }
        dialCurrent(SeqOwner.DRILL)
    }

    /** 用户停止演练（演练页/通知上的"停止测试"）。 */
    private fun onDrillStop() {
        if (!state.drill.active) return
        finishDrill(DrillOutcome.ABORTED, "用户停止了测试")
    }

    /** 逐位问答：只认当前 askIndex 的回答（乱序/重复回答忽略），答完汇总判定。 */
    private fun onDrillContactAnswer(e: Event.DrillContactAnswer) {
        val d = state.drill
        if (d.phase != DrillPhase.ASK_ANSWER || e.index != d.askIndex) return
        val bit = 1 shl e.index
        state = state.copy(
            drill = d.copy(
                askIndex = d.askIndex + 1,
                smsConfirmedMask =
                    if (e.smsReceived) d.smsConfirmedMask or bit else d.smsConfirmedMask,
                callConfirmedMask =
                    if (e.callReceived) d.callConfirmedMask or bit else d.callConfirmedMask,
            )
        )
        saveState()
        val dd = state.drill
        val contacts = config.validContacts
        if (dd.askIndex < contacts.size) return
        // 全部答完：逐位汇总四项结果
        val delay = if (dd.firedAt > 0) dd.firedAt - dd.plannedAt else -1L
        val onTime = dd.firedAt > 0 && delay <= DrillRules.ON_TIME_TOLERANCE_MS
        val fullscreenOk = dd.screenShownAt > 0 && dd.screenShownLocked &&
            dd.screenShownAt - dd.firedAt <= DrillRules.FULLSCREEN_WINDOW_MS
        val results = contacts.mapIndexed { i, c ->
            val b = 1 shl i
            DrillContactResult(
                name = c.name,
                number = c.number,
                smsSent = dd.smsSubmittedMask and b != 0 &&
                    dd.smsOkMask and b != 0 && dd.smsFailMask and b == 0,
                callAnswered = dd.calls.answeredMask and b != 0,
                smsConfirmed = dd.smsConfirmedMask and b != 0,
                callConfirmed = dd.callConfirmedMask and b != 0,
            )
        }
        val passed = onTime && fullscreenOk && results.isNotEmpty() && results.all { it.ok }
        val reason = if (passed) {
            "演练通过"
        } else {
            buildList {
                if (dd.firedAt <= 0 || !onTime) {
                    add("闹钟被系统限制（晚于计划触发）")
                }
                if (!fullscreenOk) {
                    add("全屏页未在锁屏弹出：请检查全屏通知/后台弹出界面是否允许")
                }
                results.forEachIndexed { i, r ->
                    val label = contacts.getOrNull(i)?.label() ?: "联系人${i + 1}"
                    if (dd.calls.placedMask and (1 shl i) == 0) {
                        add("$label：拨号失败：拨号权限不足，或系统弹出了选卡/确认框" +
                            "（小米需把拨打电话设为“始终允许”并记住选择）")
                    } else if (!r.callAnswered) {
                        add("$label：电话未接通，请确认号码正确、对方手机可接听")
                    }
                    if (!r.smsSent) {
                        add("$label：测试短信未发出，请检查短信权限与 SIM 卡")
                    }
                    if (!r.smsConfirmed) add("$label：你确认对方没收到测试短信")
                    if (!r.callConfirmed) add("$label：你确认对方没接到电话")
                }
            }.joinToString("；")
        }
        finishDrill(
            if (passed) DrillOutcome.PASSED else DrillOutcome.FAILED,
            reason, onTime, fullscreenOk, results,
        )
    }

    /** SyncSession/看门狗顺带做演练自愈与"到点未触发"判定（独立于守护开关）。 */
    private fun onDrillSync() {
        val d = state.drill
        if (!d.active) return
        val now = now()
        when (d.phase) {
            DrillPhase.WAITING -> {
                if (d.plannedAt in 1..now) {
                    finishDrill(
                        DrillOutcome.FAILED,
                        "到点没有响铃：后台很可能被系统清理，请检查省电策略、自启动、多任务锁定",
                    )
                    return
                }
                // 进程重启后补挂闹钟（演练闹钟本身是 AlarmManager 落盘的，这里是保险）
                ports.scheduleDrillAlarm(d.plannedAt)
            }
            DrillPhase.RINGING -> {
                if (d.confirmEndAt > now) {
                    // 进程被杀会带走响铃：窗口内自愈重响并补挂确认结束闹钟
                    ports.startDrillRinging(d.confirmEndAt)
                    ports.scheduleDrillConfirmEnd(d.confirmEndAt)
                } else {
                    // 确认窗口已过：直接推进到拨号（与真实确认不同，演练恢复要收口）
                    onDrillConfirmEnd()
                }
            }
            DrillPhase.CALLING -> resumeSeq(SeqOwner.DRILL)
            else -> Unit
        }
        // 演练期间前台服务必须在跑
        ports.ensureServiceRunning()
    }

    /** 真实报警抢占：只清演练自身的闹钟/通知，绝不动真实响铃。 */
    private fun abortDrillForRealAlert(reason: String) {
        // 演练若正在拨号，通话闹钟一并取消（之后由真实序列重新挂，不冲突）
        if (state.drill.phase == DrillPhase.CALLING) ports.cancelCallAlarms()
        ports.cancelDrill()
        writeDrillResult(DrillOutcome.ABORTED, reason)
        state = state.copy(drill = Drill())
        saveState()
        ports.log(TAG, "演练中止：$reason")
    }

    /** 演练结束统一收口：清闹钟/通知/响铃，写记录，按守护开关决定服务去留。 */
    private fun finishDrill(
        outcome: DrillOutcome,
        reason: String,
        onTime: Boolean = false,
        fullscreenOk: Boolean = false,
        contactResults: List<DrillContactResult> = emptyList(),
    ) {
        val d = state.drill
        val wasRinging = d.phase == DrillPhase.RINGING
        if (d.phase == DrillPhase.CALLING) ports.cancelCallAlarms()
        ports.cancelDrill()
        if (wasRinging) ports.stopRinging()
        writeDrillResult(outcome, reason, onTime, fullscreenOk, contactResults)
        state = state.copy(drill = Drill())
        saveState()
        // 演练结束且守护未开启：前台服务可以停了
        if (!config.enabled) ports.stopService()
        ports.log(TAG, "演练结束 outcome=$outcome reason=$reason")
    }

    /**
     * 写演练结果：lastDrillResult 记录最近一次尝试（任何结局，仅展示）。
     * qualifiedPass（开启门槛凭据）只在 PASSED 且等待达标时更新；
     * 任何 FAILED 都清空（这台手机刚被证明靠不住）；ABORTED 不影响。
     */
    private fun writeDrillResult(
        outcome: DrillOutcome,
        reason: String,
        onTime: Boolean = false,
        fullscreenOk: Boolean = false,
        contactResults: List<DrillContactResult> = emptyList(),
    ) {
        val d = state.drill
        val r = DrillResult(
            outcome = outcome,
            reason = reason,
            waitMinutes = d.waitMinutes,
            plannedAt = d.plannedAt,
            firedAt = d.firedAt,
            delayMillis = if (d.firedAt > 0) d.firedAt - d.plannedAt else -1L,
            onTime = onTime,
            fullscreenOk = fullscreenOk,
            contactResults = contactResults,
            finishedAt = now(),
            versionCode = ports.appVersionCode(),
            fingerprint = ports.buildFingerprint(),
            contactsKey = d.contactsKey,
        )
        state = state.copy(
            lastDrillResult = r,
            qualifiedPass = when {
                outcome == DrillOutcome.PASSED &&
                    d.waitMinutes >= DrillRules.MIN_QUALIFY_MINUTES -> r
                outcome == DrillOutcome.FAILED -> null
                else -> state.qualifiedPass
            },
        )
        saveState()
    }

    companion object {
        const val TAG = "GuardEngine"
        const val WATCHDOG_INTERVAL_MS = 30 * 60_000L

        /** 报警电话拨出后的自摘机保护窗（区分被守护的人真实接打电话）。 */
        const val ALERT_CALL_GUARD_MS = 3 * 60_000L
    }
}
