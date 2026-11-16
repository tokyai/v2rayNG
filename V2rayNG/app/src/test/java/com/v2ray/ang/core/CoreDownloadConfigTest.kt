package com.v2ray.ang.core

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.V2rayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreDownloadConfigTest {

    @Test
    fun shouldReplaceListenersAndRouteEveryDownloadThroughTheTestedProxy() {
        val config = sampleConfig()

        CoreConfigManager.configureDownloadTest(config, 12001)

        val inbound = config.inbounds.single()
        assertEquals("http", inbound.protocol)
        assertEquals(AppConfig.LOOPBACK, inbound.listen)
        assertEquals(12001, inbound.port)
        assertEquals(AppConfig.TAG_PROXY, config.routing.rules.single().outboundTag)
        assertEquals("tcp,udp", config.routing.rules.single().network)
        assertEquals("AsIs", config.routing.domainStrategy)
        assertNull(config.dns)
        assertNull(config.stats)
        assertNull(config.fakedns)
        assertNull(config.policy)
        assertTrue(config.outbounds.all { it.mux == null })
        assertEquals("none", config.log.loglevel)
    }

    @Test
    fun shouldKeepThePolicyGroupBalancerInsteadOfTestingOnlyItsFirstMember() {
        val config = sampleConfig()
        config.outbounds.first().tag = "proxy-member-1"
        val balancer = V2rayConfig.RoutingBean.BalancerBean(AppConfig.TAG_BALANCER, listOf("proxy-member-"))
        config.routing.balancers = listOf(balancer)
        val observatory = mapOf("subjectSelector" to listOf("proxy-member-"))
        config.observatory = observatory

        CoreConfigManager.configureDownloadTest(config, 12002)

        assertEquals(AppConfig.TAG_BALANCER, config.routing.rules.single().balancerTag)
        assertNull(config.routing.rules.single().outboundTag)
        assertEquals(listOf(balancer), config.routing.balancers)
        assertEquals(observatory, config.observatory)
    }

    @Test
    fun shouldRejectMissingProxyAndInvalidPortInsteadOfFallingBackToDirect() {
        val config = sampleConfig()
        config.outbounds.removeAt(0)

        assertThrows(IllegalArgumentException::class.java) { CoreConfigManager.configureDownloadTest(config, 12003) }
        assertThrows(IllegalArgumentException::class.java) { CoreConfigManager.configureDownloadTest(sampleConfig(), 0) }
    }

    private fun sampleConfig(): V2rayConfig {
        return V2rayConfig(
            log = V2rayConfig.LogBean(loglevel = "debug"),
            inbounds = arrayListOf(V2rayConfig.InboundBean("tun", 10808, "tun")),
            outbounds = arrayListOf(
                V2rayConfig.OutboundBean(tag = AppConfig.TAG_PROXY, protocol = "socks"),
                V2rayConfig.OutboundBean(tag = AppConfig.TAG_DIRECT, protocol = "freedom"),
            ),
            routing = V2rayConfig.RoutingBean(
                domainStrategy = "IPIfNonMatch",
                rules = arrayListOf(V2rayConfig.RoutingBean.RulesBean(outboundTag = AppConfig.TAG_DIRECT)),
            ),
            stats = emptyMap<String, String>(),
            fakedns = emptyMap<String, String>(),
        )
    }
}
