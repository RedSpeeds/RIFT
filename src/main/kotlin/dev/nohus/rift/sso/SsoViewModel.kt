package dev.nohus.rift.sso

import dev.nohus.rift.ViewModel
import dev.nohus.rift.characters.repositories.LocalCharactersRepository
import dev.nohus.rift.sso.authentication.SsoAuthenticator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import java.net.BindException

private val logger = KotlinLogging.logger {}

@Factory
class SsoViewModel(
    @InjectedParam private val inputModel: SsoAuthority,
    private val ssoAuthenticator: SsoAuthenticator,
    private val localCharactersRepository: LocalCharactersRepository,
) : ViewModel() {

    data class UiState(
        val status: SsoStatus = SsoStatus.Waiting,
    )

    sealed interface SsoStatus {
        data object Waiting : SsoStatus
        data object Complete : SsoStatus
        data class Failed(val message: String?) : SsoStatus
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun onWindowOpened() {
        _state.update { it.copy(status = SsoStatus.Waiting) }
        viewModelScope.launch {
            try {
                ssoAuthenticator.authenticate(inputModel)
                _state.update { it.copy(status = SsoStatus.Complete) }
                localCharactersRepository.load()
            } catch (e: BindException) {
                _state.update { it.copy(status = SsoStatus.Failed("Could not listen for a response from EVE SSO. Likely some other application on your computer is interfering.")) }
            } catch (e: Exception) {
                logger.error(e) { "SSO flow failed" }
                _state.update { it.copy(status = SsoStatus.Failed(null)) }
            }
        }
    }

    fun onCloseRequest() {
        ssoAuthenticator.cancel()
        _state.update { it.copy(status = SsoStatus.Waiting) }
    }
}
