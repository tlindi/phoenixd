package fr.acinq.phoenixd.payments

import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenixd.payments.Parser
import kotlin.test.Test
import kotlin.test.assertEquals

class ParserTestsCommon {

    @Test
    fun `test address parsing`() {
        data class TestCase(val address: String, val user: String, val domain: String)

        val testCases = listOf(
            TestCase("foo@bar.com", "foo", "bar.com"),
            TestCase("₿foo@bar.com", "foo", "bar.com"),
            TestCase("₿₿foo@bar.com", "foo", "bar.com"),
        )

        testCases.forEach { testCase -> assertEquals(testCase.user to testCase.domain, Parser.parseEmailLikeAddress(testCase.address)) }
    }

    @Test
    fun `test bip21 parsing`() {
        data class TestCase(val uri: String, val offer: OfferTypes.Offer?)

        val offer =
            OfferTypes.Offer.decode("lno1qgsyxjtl6luzd9t3pr62xr7eemp6awnejusgf6gw45q75vcfqqqqqqqsespexwyy4tcadvgg89l9aljus6709kx235hhqrk6n8dey98uyuftzdqzrtkahuum7m56dxlnx8r6tffy54004l7kvs7pylmxx7xs4n54986qyqeeuhhunayntt50snmdkq4t7fzsgghpl69v9csgparek8kv7dlp5uqr8ymp5s4z9upmwr2s8xu020d45t5phqc8nljrq8gzsjmurzevawjz6j6rc95xwfvnhgfx6v4c3jha7jwynecrz3y092nn25ek4yl7xp9yu9ry9zqagt0ktn4wwvqg52v9ss9ls22sqyqqestzp2l6decpn87pq96udsvx")
                .get()

        val testCases = listOf(
            TestCase("bitcoin:?lno=$offer", offer),
            TestCase("bitcoin:?lno=$offer&foo=bar", offer),
            TestCase("bitcoin:?foo=bar&lno=$offer", offer),
            TestCase("bitcoin:?foo=bar&lno=$offer&bar=baz", offer),
            TestCase("not-an-uri", null),
            TestCase("notbitcoin:?lno=$offer", null),
            TestCase("bitcoin:?foo=bar&bar=baz", null),
            TestCase("bitcoin:tb1qla78tll0eua3l5f4nvfq3tx58u35yc3m44flfu?time=1618931109&exp=604800", null),
        )

        testCases.forEach { testCase -> assertEquals(testCase.offer, Parser.parseBip21Offer(testCase.uri)) }
    }
}