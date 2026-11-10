package com.nemap.android.core

/**
 * Catalogue fermé des codes d'erreur (cf. 01-SPEC-DEEPSEEK-WEB.md §8).
 * Le code est stable et fait partie du contrat public de l'API.
 */
class NemApiException(
    val code: String,
    override val message: String
) : Exception(message) {

    companion object {
        const val PROVIDER_NOT_CONFIGURED = "provider_not_configured"
        const val PROVIDER_BUSY = "provider_busy"
        const val PROVIDER_NOT_READY = "provider_not_ready"
        const val SELECTOR_MISSING = "selector_missing"
        const val SELECTOR_AMBIGUOUS = "selector_ambiguous"
        const val LOGIN_REQUIRED = "login_required"
        const val USER_INTERVENTION_REQUIRED = "user_intervention_required"
        const val NETWORK_ERROR = "network_error"
        const val STREAM_INTERRUPTED = "stream_interrupted"
        const val TIMEOUT = "timeout"
        const val CANCELLED = "cancelled"
        const val UPSTREAM_ERROR = "upstream_error"

        fun notReady(msg: String = "La page du provider n'est pas prête. Ouvre l'onglet et vérifie la connexion.") =
            NemApiException(PROVIDER_NOT_READY, msg)

        fun login() = NemApiException(
            LOGIN_REQUIRED,
            "La session web a expiré. Reconnecte-toi dans l'onglet du provider."
        )

        fun busy() = NemApiException(
            PROVIDER_BUSY,
            "Une génération est déjà en cours sur ce provider."
        )

        fun timeout(what: String) = NemApiException(TIMEOUT, what)

        fun cancelled() = NemApiException(CANCELLED, "La génération a été annulée.")
    }
}

object ErrorTypes {
    /** type OpenAI associé au code interne */
    fun typeFor(code: String): String = when (code) {
        NemApiException.PROVIDER_BUSY,
        NemApiException.PROVIDER_NOT_CONFIGURED,
        NemApiException.PROVIDER_NOT_READY,
        NemApiException.SELECTOR_MISSING,
        NemApiException.SELECTOR_AMBIGUOUS,
        NemApiException.LOGIN_REQUIRED,
        NemApiException.USER_INTERVENTION_REQUIRED -> "invalid_request_error"
        NemApiException.CANCELLED -> "cancelled"
        NemApiException.TIMEOUT -> "timeout"
        NemApiException.NETWORK_ERROR,
        NemApiException.STREAM_INTERRUPTED,
        NemApiException.UPSTREAM_ERROR -> "api_error"
        else -> "api_error"
    }
}
