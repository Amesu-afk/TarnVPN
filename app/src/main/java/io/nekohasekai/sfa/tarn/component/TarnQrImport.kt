package io.nekohasekai.sfa.tarn.component

import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult

/** Only a plain QR payload belongs in Tarn's link/subscription importer. */
internal fun QRScanResult.tarnImportText(): String? = (this as? QRScanResult.RawText)?.value?.trim()?.takeIf(String::isNotEmpty)
