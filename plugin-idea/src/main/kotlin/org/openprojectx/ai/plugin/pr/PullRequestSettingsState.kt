package org.openprojectx.ai.plugin.pr

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(
    name = "OpenProjectXPullRequestSettings",
    storages = [Storage("openprojectx-pull-request.xml")]
)
class PullRequestSettingsState : PersistentStateComponent<PullRequestSettingsState.State> {

    data class State(
        var createAfterPush: Boolean = false,
        var targetBranch: String = "main"
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(project: com.intellij.openapi.project.Project): PullRequestSettingsState {
            return project.getService(PullRequestSettingsState::class.java)
        }
    }
}