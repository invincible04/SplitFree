package com.splitfree.domain.usecase.group

import com.splitfree.di.ApplicationScope
import com.splitfree.domain.model.group.Group
import com.splitfree.util.DebugLog as Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps joins and their outcomes across Activity recreation within the same process. Process death
 * loses this in-memory state; [JoinGroupUseCase] resumes persisted progress when the link is offered again.
 *
 * Only [State.Idle] accepts a join. A finished outcome remains in [state] until acknowledged,
 * preventing another request from replacing a result the UI has not consumed.
 */
@Singleton
class JoinGroupCoordinator
@Inject
constructor(
    private val joinGroup: JoinGroupUseCase,
    @ApplicationScope private val scope: CoroutineScope
) {
    sealed interface State {
        data object Idle : State

        /** A join is in flight for the given (bearer) link; never logged. */
        data class Joining(val link: String) : State

        data class Joined(val group: Group) : State

        data class Failed(val message: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Start joining via [link] unless a join is already running or an outcome is still unacknowledged. */
    fun join(link: String) {
        if (!_state.compareAndSet(State.Idle, State.Joining(link))) return
        scope.launch {
            val outcome = try {
                State.Joined(joinGroup(link))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Join failed: ${e.message}", e)
                State.Failed(e.message)
            }
            _state.value = outcome
        }
    }

    /** The UI has shown the finished outcome; return to [State.Idle]. A running join is left alone. */
    fun acknowledge() {
        _state.update { if (it is State.Joining) it else State.Idle }
    }

    private companion object {
        const val TAG = "JoinGroupCoordinator"
    }
}
