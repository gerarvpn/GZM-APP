package com.gzmnet.app.fmt

import com.gzmnet.app.AppConfig
import com.gzmnet.app.dto.V2rayConfig
import com.gzmnet.app.dto.entities.ProfileItem
import com.gzmnet.app.enums.EConfigType
import com.gzmnet.app.util.JsonUtil

object CustomOutboundFmt : FmtBase() {
    fun parse(str: String): Pair<ProfileItem, String> {
        val config = ProfileItem.create(EConfigType.CUSTOM_OUTBOUND)

        val outbound = JsonUtil.fromJson(str, V2rayConfig.OutboundBean::class.java)

        config.remarks = outbound?.tag ?: System.currentTimeMillis().toString()
        config.server = outbound?.getServerAddress()
        config.serverPort = outbound?.getServerPort()?.toString()

        outbound?.tag = AppConfig.TAG_PROXY
        val rawOutbound = outbound?.let { JsonUtil.toJsonPretty(it) } ?: ""
        return Pair(config, rawOutbound)
    }
}
