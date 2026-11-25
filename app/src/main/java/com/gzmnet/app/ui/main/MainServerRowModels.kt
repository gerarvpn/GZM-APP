package com.gzmnet.app.ui.main

import com.gzmnet.app.dto.entities.ProfileItem
import com.gzmnet.app.dto.entities.ServersCache
import com.gzmnet.app.extension.isComplexType
import com.gzmnet.app.extension.moveItem
import com.gzmnet.app.extension.nullIfBlank
import com.gzmnet.app.handler.AngConfigManager

internal data class ServerRowUiModel(
    val guid: String,
    val profile: ProfileItem,
    val remarks: String,
    val statistics: String,
    val typeDescription: String,
    val testDelayMillis: Long,
    val subscriptionBadge: String,
)

internal data class ServerGroupUiState(
    val servers: List<ServersCache> = emptyList(),
    val rows: List<ServerRowUiModel> = emptyList(),
    val isFiltered: Boolean = false,
) {
    fun canReorder(groupId: String, searchQuery: String): Boolean =
        groupId.isNotEmpty() && searchQuery.isEmpty() && !isFiltered

    fun moveServer(fromGuid: String, toGuid: String): ServerGroupUiState? {
        if (isFiltered) return null
        val from = servers.indexOfFirst { it.guid == fromGuid }
        val to = servers.indexOfFirst { it.guid == toGuid }
        if (from == to || from < 0 || to < 0) return null
        return ServerGroupUiState(
            servers = servers.toMutableList().apply { moveItem(from, to) },
            rows = rows.toMutableList().apply { moveItem(from, to) }
        )
    }
}

internal fun buildServerRowUiModel(
    server: ServersCache,
    subscriptionRemarks: String,
): ServerRowUiModel {
    val profile = server.profile
    return ServerRowUiModel(
        guid = server.guid,
        profile = profile,
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank()
            ?: AngConfigManager.generateDescription(profile),
        typeDescription = serverProtocolDescription(profile),
        testDelayMillis = server.testDelayMillis,
        subscriptionBadge = subscriptionRemarks.firstOrNull()?.toString().orEmpty(),
    )
}

private fun serverProtocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    profile.network?.let { network ->
        if (network.isNotBlank() && !network.equals("tcp", ignoreCase = true)) {
            parts.add(network)
        }
    }
    profile.security?.let { security ->
        if (security.isNotBlank()) {
            parts.add(
                if (profile.insecure == true && security.equals("tls", ignoreCase = true)) {
                    "$security insecure"
                } else {
                    security
                }
            )
        }
    }
    return parts.joinToString(" / ")
}
