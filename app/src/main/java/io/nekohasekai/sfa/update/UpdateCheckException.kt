package io.nekohasekai.sfa.update

sealed class UpdateCheckException : Exception() {
    class TrackNotSupported : UpdateCheckException()

    /**
     * No releases repository is configured for this build, so there is nowhere to ask.
     *
     * Kept distinct from "no update available" on purpose: to a user who pressed the button
     * the two look the same, but only this one is a build-configuration gap rather than an
     * answer about versions. See `GitHubUpdateChecker.TARN_RELEASES_REPO`.
     */
    class NotConfigured : UpdateCheckException()
}
