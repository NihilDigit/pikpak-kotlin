package io.github.nihildigit.pikpak

import io.github.nihildigit.pikpak.internal.buildUrl
import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One monthly transfer allowance. Both fields are bytes; PikPak sends them as
 * JSON numbers here, unlike the strings of [QuotaInfo].
 */
@Serializable
data class TransferAllowance(
    @SerialName("total_assets") val limitBytes: Long = 0L,
    @SerialName("assets") val usedBytes: Long = 0L,
) {
    val remainingBytes: Long get() = (limitBytes - usedBytes).coerceAtLeast(0L)
}

/**
 * The account's own allowances, as the web client's Transfer Quota dialog
 * shows them.
 *
 * What draws on which, measured 2026-09-24 against a premium account:
 * - [offline] (Cloud Download) is charged the full size of an offline task.
 * - [upload] is charged 15 % of the size of every [instantCreate], whether or
 *   not the account already holds that content (4.6 GB drew 0.69 GB, 3.8 GB
 *   drew 0.57 GB).
 * - Neither touches [download] (Downstream).
 *
 * The SDK's own requests count here, not under the connected-apps share: that
 * share stayed at zero throughout.
 */
@Serializable
data class TransferAllowances(
    val offline: TransferAllowance = TransferAllowance(),
    val download: TransferAllowance = TransferAllowance(),
    val upload: TransferAllowance = TransferAllowance(),
    /** Per-day downstream cap for non-premium accounts; zero on premium. */
    @SerialName("download_daily") val downloadDaily: TransferAllowance = TransferAllowance(),
    @SerialName("vip_status") val vipStatus: String = "",
    /** Premium expiry, ISO-8601 with offset, empty for a free account. */
    @SerialName("expire_time") val expireTime: String = "",
)

/**
 * Response of [getTransferQuota]. [connectedApps] is the separate share (a
 * quarter of the account's) that third-party apps authorised through PikPak's
 * app platform draw on.
 */
@Serializable
data class TransferQuota(
    @SerialName("base") val account: TransferAllowances = TransferAllowances(),
    @SerialName("apps_summary") val connectedApps: TransferAllowances = TransferAllowances(),
)

/**
 * Monthly transfer allowances (`GET /vip/v1/quantity/list?type=transfer`).
 * This is not the storage quota of [getQuota]: the two are counted separately,
 * and an instant copy that leaves `about` untouched still draws on [TransferAllowances.upload].
 * The response carries no reset time; the web client shows the first of the
 * month, 00:00 SGT.
 */
suspend fun PikPakClient.getTransferQuota(): TransferQuota {
    val response = http.request(
        method = HttpMethod.Get,
        url = buildUrl(PikPakConstants.DRIVE_BASE, "/vip/v1/quantity/list", mapOf("type" to "transfer", "limit" to "200")),
        captchaAction = "GET:/vip/v1/quantity/list",
    )
    return json.decodeFromJsonElement(TransferQuota.serializer(), response)
}
