package com.harmony.mobile

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The call's UI state, shared between the voice session, the foreground service
 * and whatever screen is showing the call.
 *
 * The session writes roster and connection changes here; a screen reads the
 * current value and subscribes for updates. Mute, deafen and leave are commands a
 * screen issues here, so no screen has to hold the session itself -- the session
 * lives in [CallService], which can outlast any activity.
 */
object VoiceCall {
    data class Member(
        val id: String,
        val name: String,
        val muted: Boolean,
        val speaking: Boolean = false,
        val sharing: Boolean = false,
        val avatarUrl: String? = null,
    )

    data class State(
        val active: Boolean = false,
        val connecting: Boolean = false,
        val connected: Boolean = false,
        /**
         * The voice channel the call is in, or null when there is no call. The
         * shell's host bridge reports this to the instance's own client, whose
         * voice row keys on it (see [VoiceHostEmitter]).
         */
        val channelId: String? = null,
        val channelName: String? = null,
        val muted: Boolean = false,
        val deafened: Boolean = false,
        val localSharing: Boolean = false,
        val speaker: Boolean = true,
        val members: List<Member> = emptyList(),
    )

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State()
        private set

    /** The live session, set by the service while a call runs. */
    @Volatile
    var session: NativeVoiceSession? = null

    /** This member's own id, so the call screen can mark them. */
    @Volatile
    var selfId: String? = null

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        main.post { listener(state) }
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun update(change: (State) -> State) {
        val next = change(state)
        state = next
        main.post { for (listener in listeners) listener(next) }
    }

    fun reset() {
        session = null
        selfId = null
        update { State() }
    }

    fun toggleMute() {
        val next = !state.muted
        session?.setMuted(next)
        update { it.copy(muted = next) }
    }

    fun toggleDeafen() {
        val next = !state.deafened
        session?.setDeafened(next)
        update { it.copy(deafened = next) }
    }

    /** Switches between the speaker and the earpiece. */
    fun toggleSpeaker() {
        val next = !state.speaker
        session?.setSpeaker(next)
        update { it.copy(speaker = next) }
    }
}
