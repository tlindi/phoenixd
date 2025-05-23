package fr.acinq.phoenixd

import fr.acinq.bitcoin.*
import fr.acinq.bitcoin.crypto.Digest
import fr.acinq.bitcoin.crypto.hmac
import fr.acinq.bitcoin.utils.Either
import fr.acinq.bitcoin.utils.Try
import fr.acinq.bitcoin.utils.toEither
import fr.acinq.lightning.Lightning.randomBytes32
import fr.acinq.lightning.NodeParams
import fr.acinq.lightning.PaymentEvents
import fr.acinq.lightning.blockchain.fee.FeeratePerByte
import fr.acinq.lightning.blockchain.fee.FeeratePerKw
import fr.acinq.lightning.channel.ChannelCloseResponse
import fr.acinq.lightning.channel.ChannelCommand
import fr.acinq.lightning.channel.ChannelFundingResponse
import fr.acinq.lightning.channel.states.*
import fr.acinq.lightning.crypto.LocalKeyManager
import fr.acinq.lightning.db.*
import fr.acinq.lightning.io.Peer
import fr.acinq.lightning.io.WrappedChannelCommand
import fr.acinq.lightning.logging.LoggerFactory
import fr.acinq.lightning.logging.info
import fr.acinq.lightning.logging.warning
import fr.acinq.lightning.payment.Bolt11Invoice
import fr.acinq.lightning.utils.*
import fr.acinq.lightning.wire.OfferTypes
import fr.acinq.phoenixd.api.WebsocketProtocolAuthenticationProvider
import fr.acinq.phoenixd.csv.WalletPaymentCsvWriter
import fr.acinq.phoenixd.db.SqlitePaymentsDb
import fr.acinq.phoenixd.json.ApiType
import fr.acinq.phoenixd.json.ApiType.*
import fr.acinq.phoenixd.payments.AddressResolver
import fr.acinq.phoenixd.payments.Parser
import fr.acinq.phoenixd.payments.PayDnsAddress
import fr.acinq.phoenixd.payments.lnurl.LnurlHandler
import fr.acinq.phoenixd.payments.lnurl.helpers.LnurlParser
import fr.acinq.phoenixd.payments.lnurl.models.Lnurl
import fr.acinq.phoenixd.payments.lnurl.models.LnurlAuth
import fr.acinq.phoenixd.payments.lnurl.models.LnurlPay
import fr.acinq.phoenixd.payments.lnurl.models.LnurlWithdraw
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.encodeToByteString
import kotlinx.io.files.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class Api(
    private val nodeParams: NodeParams,
    private val peer: Peer,
    private val eventsFlow: SharedFlow<ApiEvent>,
    private val fullAccessPassword: String,
    private val limitedAccessPassword: String,
    private val webhookUrls: List<Url>,
    private val webhookSecret: String,
    private val loggerFactory: LoggerFactory,
) {

    @OptIn(ExperimentalStdlibApi::class)
    fun Application.module() {

        val log = loggerFactory.newLogger(this::class)

        val payDnsAddress = PayDnsAddress()
        val lnurlHandler = LnurlHandler(loggerFactory, nodeParams.keyManager as LocalKeyManager)
        val addressResolver = AddressResolver(payDnsAddress, lnurlHandler)

        val fullAccessUser = UserIdPrincipal("full-access")
        val limitedAccessUser = UserIdPrincipal("limited-access")

        val json = Json {
            prettyPrint = true
            isLenient = true
            explicitNulls = false
            serializersModule = fr.acinq.lightning.json.JsonSerializers.json.serializersModule
        }

        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
            timeoutMillis = 10_000
            pingPeriodMillis = 10_000
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respondText(text = cause.message ?: "", status = defaultExceptionStatusCode(cause) ?: HttpStatusCode.InternalServerError)
            }
            status(HttpStatusCode.Unauthorized) { call, status ->
                call.respondText(text = "Invalid authentication (use basic auth with the http password set in phoenix.conf)", status = status)
            }
            status(HttpStatusCode.MethodNotAllowed) { call, status ->
                call.respondText(text = "Invalid http method (use the correct GET/POST)", status = status)
            }
            status(HttpStatusCode.NotFound) { call, status ->
                call.respondText(text = "Unknown endpoint (check api doc)", status = status)
            }
            status(HttpStatusCode.NoContent) { call, _ ->
                // Will be returned when a payment lookup returns no result for a given id.
                // We convert those to 404 for backward compatibility, and because it is more correct for a REST interface.
                call.respondText(text = "Not found", status = HttpStatusCode.NotFound)
            }
        }
        install(Authentication) {
            basic {
                validate { credentials ->
                    when (credentials.password) {
                        fullAccessPassword -> fullAccessUser
                        limitedAccessPassword -> limitedAccessUser
                        else -> null
                    }
                }
            }
            basic("full-access") {
                validate { credentials ->
                    when (credentials.password) {
                        fullAccessPassword -> fullAccessUser
                        else -> null
                    }
                }
            }
            register(WebsocketProtocolAuthenticationProvider("websocket-protocol") { protocols ->
                when {
                    protocols.any { it.value == fullAccessPassword } -> fullAccessUser
                    protocols.any { it.value == limitedAccessPassword } -> limitedAccessUser
                    else -> null
                }
            })
        }

        routing {
            authenticate {
                get("getinfo") {
                    val info = NodeInfo(
                        nodeId = nodeParams.nodeId,
                        channels = peer.channels.values.map { ApiType.Channel.from(it) },
                        chain = nodeParams.chain.name.lowercase(),
                        blockHeight = peer.currentTipFlow.value,
                        version = BuildVersions.phoenixdVersion
                    )
                    call.respond(info)
                }
                get("getbalance") {
                    val balance = peer.channels.values
                        .filterIsInstance<ChannelStateWithCommitments>()
                        .filterNot { it is Closing || it is Closed }
                        .map { it.commitments.active.first().availableBalanceForSend(it.commitments.params, it.commitments.changes) }
                        .sum().truncateToSatoshi()
                    call.respond(Balance(balance, peer.feeCreditFlow.value.truncateToSatoshi()))
                }
                get("estimateliquidityfees") {
                    val amount = call.parameters.getLong("amountSat").sat
                    val feerate = peer.onChainFeeratesFlow.filterNotNull().first().fundingFeerate
                    val fundingRates = peer.remoteFundingRates.filterNotNull().first()
                    when (val fundingRate = fundingRates.findRate(amount)) {
                        null -> badRequest("no available funding rates for amount=$amount")
                        else -> {
                            val liquidityFees = fundingRate.fees(feerate, amount, amount, isChannelCreation = peer.channels.isEmpty())
                            call.respond(LiquidityFees(liquidityFees))
                        }
                    }
                }
                get("listchannels") {
                    call.respond(peer.channels.values.toList())
                }
                post("createinvoice") {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters.getOptionalLong("amountSat")?.sat
                    val maxDescriptionSize = 128
                    val description = formParameters["description"]
                        ?.also { if (it.length > maxDescriptionSize) badRequest("Request parameter description is too long (max $maxDescriptionSize characters)") }
                    val descriptionHash = formParameters.getOptionalByteVector32("descriptionHash")
                    val eitherDesc = when {
                        description != null && descriptionHash == null -> Either.Left(description)
                        description == null && descriptionHash != null -> Either.Right(descriptionHash)
                        else -> badRequest("Must provide either a description or descriptionHash")
                    }
                    val expiry = formParameters.getOptionalLong("expirySeconds")?.seconds
                    val invoice = peer.createInvoice(randomBytes32(), amount?.toMilliSatoshi(), eitherDesc, expiry)
                    val externalId = formParameters["externalId"]
                    val webhookUrl = formParameters.getOptionalUrl("webhookUrl")
                    if (externalId != null || webhookUrl != null) {
                        paymentDb.metadataQueries.insert(paymentHash = invoice.paymentHash, externalId = externalId, webhookUrl = webhookUrl)
                    }
                    call.respond(GeneratedInvoice(invoice.amount?.truncateToSatoshi(), invoice.paymentHash, serialized = invoice.write()))
                }
                post("createoffer") {
                    val formParameters = call.receiveParameters()
                    val amount = formParameters.getOptionalLong("amountSat")?.sat
                    val maxDescriptionSize = 128
                    val description = formParameters["description"]
                        ?.also { if (it.length > maxDescriptionSize) badRequest("Request parameter description is too long (max $maxDescriptionSize characters)") }
                    if (amount != null && description == null) {
                        badRequest("Must provide a description if an amount is specified")
                    }
                    call.respond(nodeParams.randomOffer(peer.walletParams.trampolineNode.id, amount?.toMilliSatoshi(), description).first.encode())
                }
                get("getoffer") {
                    call.respond(nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).first.encode())
                }
                get("getlnaddress") {
                    if (peer.channels.isEmpty()) {
                        call.respond("must have one channel")
                    } else {
                        val address = peer.requestAddress("en")
                        call.respond("₿$address")
                    }
                }
                get("payments/incoming") {
                    val payments: List<ApiType> = paymentDb.listIncomingPayments(
                        from = call.parameters.getOptionalLong("from") ?: 0L,
                        to = call.parameters.getOptionalLong("to") ?: currentTimestampMillis(),
                        limit = call.parameters.getOptionalLong("limit") ?: 20,
                        offset = call.parameters.getOptionalLong("offset") ?: 0,
                        listAll = call.parameters["all"]?.toBoolean() ?: false, // by default, only list incoming payments that have been received
                        externalId = call.parameters["externalId"] // may filter incoming payments by an external id
                    ).mapNotNull { (payment, externalId) ->
                        when (payment) {
                            is LightningIncomingPayment -> ApiType.IncomingPayment(payment, externalId)
                            is @Suppress("DEPRECATION") LegacyPayToOpenIncomingPayment -> ApiType.IncomingPayment(payment, externalId)
                            else -> null
                        }
                    }
                    call.respond(payments)
                }
                get("payments/incoming/{paymentHash}") {
                    val paymentHash = call.parameters.getByteVector32("paymentHash")
                    val metadata = paymentDb.metadataQueries.get(paymentHash)
                    val payment: ApiType? = when (val payment = paymentDb.getIncomingPayment(paymentHash)) {
                        is LightningIncomingPayment -> ApiType.IncomingPayment(payment, metadata?.externalId)
                        is @Suppress("DEPRECATION") LegacyPayToOpenIncomingPayment -> ApiType.IncomingPayment(payment, metadata?.externalId)
                        else -> null
                    }
                    payment
                        ?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NoContent)
                }
                get("payments/outgoing") {
                    val payments: List<ApiType> = paymentDb.listOutgoingPayments(
                        from = call.parameters.getOptionalLong("from") ?: 0L,
                        to = call.parameters.getOptionalLong("to") ?: currentTimestampMillis(),
                        limit = call.parameters.getOptionalLong("limit") ?: 20,
                        offset = call.parameters.getOptionalLong("offset") ?: 0,
                        listAll = call.parameters["all"]?.toBoolean() ?: false // by default, only list outgoing payments that have been successfully sent, or are pending
                    ).map {
                        when (it) {
                            is LightningOutgoingPayment -> ApiType.OutgoingPayment(it)
                            is OnChainOutgoingPayment -> ApiType.OutgoingPayment(it)
                        }
                    }
                    call.respond(payments)
                }
                get("payments/outgoing/{uuid}") {
                    val uuid = call.parameters.getUUID("uuid")
                    val payment: ApiType? = paymentDb.getLightningOutgoingPayment(uuid)?.let { ApiType.OutgoingPayment(it) }
                    payment
                        ?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NoContent)
                }
                get("payments/outgoingbyhash/{paymentHash}") {
                    val paymentHash = call.parameters.getByteVector32("paymentHash")
                    val payment: ApiType? = paymentDb.listLightningOutgoingPayments(paymentHash)
                        .maxByOrNull {
                            when (it.status) {
                                is LightningOutgoingPayment.Status.Succeeded -> 3
                                is LightningOutgoingPayment.Status.Pending -> 2
                                is LightningOutgoingPayment.Status.Failed -> 1
                            }
                        }
                        ?.let { ApiType.OutgoingPayment(it) }
                    payment
                        ?.let { call.respond(it) }
                        ?: call.respond(HttpStatusCode.NoContent)
                }
                authenticate("full-access", strategy = AuthenticationStrategy.Required) {
                    post("payinvoice") {
                        val formParameters = call.receiveParameters()
                        val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                        val invoice = formParameters.getInvoice("invoice")
                        val amount = (overrideAmount ?: invoice.amount) ?: missing("amountSat")
                        when (val event = peer.payInvoice(amount, invoice)) {
                            is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                            is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                            is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                        }
                    }
                    post("payoffer") {
                        val formParameters = call.receiveParameters()
                        val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                        val offer = formParameters.getOffer("offer")
                        val amount = (overrideAmount ?: offer.amount) ?: missing("amountSat")
                        val note = formParameters["message"]
                        when (val event = peer.payOffer(amount, offer, payerKey = nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).second, payerNote = note, fetchInvoiceTimeout = 30.seconds)) {
                            is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                            is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                            is fr.acinq.lightning.io.OfferNotPaid -> call.respond(PaymentFailed(event))
                        }
                    }
                    post("paylnaddress") {
                        val formParameters = call.receiveParameters()
                        val amount = formParameters.getLong("amountSat").sat.toMilliSatoshi()
                        val (username, domain) = formParameters.getEmailLikeAddress("address")
                        val note = formParameters["message"]
                        when (val res = addressResolver.resolveAddress(username, domain, amount, note)) {
                            is Try.Success -> when (val either = res.result) {
                                is Either.Left -> {
                                    // LNURL
                                    val lnurlInvoice = either.value
                                    when (val event = peer.payInvoice(amount, lnurlInvoice.invoice)) {
                                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                        is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                                    }
                                }
                                is Either.Right -> {
                                    // OFFER
                                    val offer = either.value
                                    when (val event = peer.payOffer(amount, offer, payerKey = nodeParams.defaultOffer(peer.walletParams.trampolineNode.id).second, payerNote = note, fetchInvoiceTimeout = 30.seconds)) {
                                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                        is fr.acinq.lightning.io.OfferNotPaid -> call.respond(PaymentFailed(event))
                                    }
                                }
                            }
                            is Try.Failure -> error("cannot resolve address: ${res.error.message}")
                        }
                    }
                }
                post("decodeinvoice") {
                    val formParameters = call.receiveParameters()
                    val invoice = formParameters.getInvoice("invoice")
                    call.respond(invoice)
                }
                post("decodeoffer") {
                    val formParameters = call.receiveParameters()
                    val offer = formParameters.getOffer("offer")
                    call.respond(offer)
                }
                authenticate("full-access", strategy = AuthenticationStrategy.Required) {
                    post("lnurlpay") {
                        val formParameters = call.receiveParameters()
                        val overrideAmount = formParameters["amountSat"]?.let { it.toLongOrNull() ?: invalidType("amountSat", "integer") }?.sat?.toMilliSatoshi()
                        val comment = formParameters["message"]
                        val request = formParameters.getLnurl("lnurl")
                        // early abort to avoid executing an invalid url
                        when (request) {
                            is LnurlAuth -> badRequest("this is an authentication lnurl")
                            is Lnurl.Request -> if (request.tag == Lnurl.Tag.Withdraw) badRequest("this is a withdraw lnurl")
                            else -> Unit
                        }
                        try {
                            val lnurl = lnurlHandler.executeLnurl(request.initialUrl)
                            when (lnurl) {
                                is LnurlWithdraw -> badRequest("this is a withdraw lnurl")
                                is LnurlPay.PaymentParameters -> {
                                    val amount = (overrideAmount ?: lnurl.minSendable)
                                    val invoice = lnurlHandler.getLnurlPayInvoice(lnurl, amount, comment)
                                    when (val event = peer.payInvoice(amount, invoice.invoice)) {
                                        is fr.acinq.lightning.io.PaymentSent -> call.respond(PaymentSent(event))
                                        is fr.acinq.lightning.io.PaymentNotSent -> call.respond(PaymentFailed(event))
                                        is fr.acinq.lightning.io.OfferNotPaid -> error("unreachable code")
                                    }
                                }
                                else -> badRequest("invalid [${lnurl::class}] lnurl=${lnurl.initialUrl}")
                            }
                        } catch (e: Exception) {
                            badRequest(e.message ?: e::class.toString())
                        }
                    }
                }
                post("lnurlwithdraw") {
                    val formParameters = call.receiveParameters()
                    val request = formParameters.getLnurl("lnurl")
                    // early abort to avoid executing an invalid url
                    when (request) {
                        is LnurlAuth -> badRequest("this is an authentication lnurl")
                        is Lnurl.Request -> if (request.tag == Lnurl.Tag.Pay) badRequest("this is a payment lnurl")
                        else -> Unit
                    }
                    try {
                        val lnurl = lnurlHandler.executeLnurl(request.initialUrl)
                        when (lnurl) {
                            is LnurlPay -> badRequest("this is a payment lnurl")
                            is LnurlWithdraw -> {
                                val invoice = peer.createInvoice(randomBytes32(), lnurl.maxWithdrawable, Either.Left(lnurl.defaultDescription))
                                lnurlHandler.sendWithdrawInvoice(lnurl, invoice)
                                call.respond(LnurlWithdrawResponse(lnurl, invoice))
                            }
                            else -> badRequest("invalid [${lnurl::class}] lnurl=${lnurl.initialUrl}")
                        }
                    } catch (e: Exception) {
                        badRequest(e.message ?: e::class.toString())
                    }
                }
                authenticate("full-access", strategy = AuthenticationStrategy.Required) {
                    post("lnurlauth") {
                        val formParameters = call.receiveParameters()
                        val request = formParameters.getLnurl("lnurl")
                        if (request !is LnurlAuth) badRequest("this is a payment or withdraw lnurl")
                        try {
                            lnurlHandler.signAndSendAuthRequest(request)
                            call.respond("authentication success")
                        } catch (e: Exception) {
                            badRequest("could not authenticate: ${e.message ?: e::class.toString()}")
                        }
                    }
                    post("sendtoaddress") {
                        val res = kotlin.runCatching {
                            val formParameters = call.receiveParameters()
                            val amount = formParameters.getLong("amountSat").sat
                            val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                            val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                            peer.spliceOut(amount, scriptPubKey, feerate)
                        }.toEither()
                        when (res) {
                            is Either.Right -> when (val r = res.value) {
                                is ChannelFundingResponse.Success -> call.respondText(r.fundingTxId.toString())
                                is ChannelFundingResponse.Failure -> call.respondText(r.toString())
                                else -> call.respondText("no channel available")
                            }
                            is Either.Left -> call.respondText(res.value.message.toString())
                        }
                    }
                    post("bumpfee") {
                        val formParameters = call.receiveParameters()
                        val targetFeerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                        val channel = peer.channels.values.filterIsInstance<Normal>().firstOrNull()
                        val cpfpFeerate = channel?.let { peer.estimateFeeForSpliceCpfp(it.channelId, targetFeerate)?.first }
                        if (cpfpFeerate == null) {
                            call.respondText("no channel available")
                            return@post
                        }
                        if (cpfpFeerate == targetFeerate) {
                            call.respondText("either the tx is already confirmed or has a higher feerate")
                            return@post
                        }
                        when (val r = peer.spliceCpfp(channel.channelId, cpfpFeerate)) {
                            is ChannelFundingResponse.Success -> call.respondText(r.fundingTxId.toString())
                            is ChannelFundingResponse.Failure -> call.respondText(r.toString())
                            else -> call.respondText("no channel available")
                        }
                    }
                    post("closechannel") {
                        val formParameters = call.receiveParameters()
                        val channelId = formParameters.getByteVector32("channelId")
                        val scriptPubKey = formParameters.getAddressAndConvertToScript("address")
                        val feerate = FeeratePerKw(FeeratePerByte(formParameters.getLong("feerateSatByte").sat))
                        when (val r = peer.mutualClose(channelId, scriptPubKey, feerate)) {
                            is ChannelCloseResponse.Success -> call.respondText(r.closingTxId.toString())
                            is ChannelCloseResponse.Failure -> call.respondText(r.toString())
                            else -> call.respondText("no channel available")
                        }
                    }
                }
                post("export") {
                    val from = call.parameters.getOptionalLong("from") ?: 0L
                    val to = call.parameters.getOptionalLong("to") ?: currentTimestampMillis()
                    val csvPath = Path(datadir, "exports", "export-${currentTimestampSeconds()}.csv")
                    log.info { "exporting payments to $csvPath..." }
                    val csvWriter = WalletPaymentCsvWriter(csvPath)
                    paymentDb.processSuccessfulPayments(from, to) { payment ->
                        csvWriter.add(payment)
                    }
                    csvWriter.close()
                    log.info { "csv export completed" }
                    call.respond("payment history has been exported to $csvPath")
                }
            }
            route("/websocket") {
                authenticate(configurations = arrayOf(null, "websocket-protocol"), strategy = AuthenticationStrategy.FirstSuccessful) {
                    webSocket {
                        kotlin.runCatching {
                            eventsFlow.collect { sendSerialized(it) }
                        }.onFailure {
                            log.warning { "websocket failure: ${it.message}" }
                        }
                    }
                }
            }
        }

        val client = HttpClient {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(json = Json {
                    prettyPrint = true
                    isLenient = true
                })
            }
        }
        client.sendPipeline.intercept(HttpSendPipeline.State) {
            when (val body = context.body) {
                is TextContent -> {
                    val bodyBytes = body.text.encodeToByteArray()
                    val secretBytes = webhookSecret.encodeToByteArray()
                    val sig = bodyBytes.hmacSha256(secretBytes)
                    context.headers.append("X-Phoenix-Signature", sig.toHexString())
                }
            }
        }
        suspend fun notifyWebhook(url: Url, event: ApiEvent) {
            kotlin.runCatching {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(event)
                }
            }.onFailure {
                log.warning { "webhook failure: ${it.message}" }
            }
        }
        // general webhook urls
        webhookUrls.forEach { url ->
            launch {
                eventsFlow.collect { event -> notifyWebhook(url, event) }
            }
        }
        // per-event webhook url
        launch {
            eventsFlow
                .filterIsInstance<PaymentReceived>()
                .collect { event ->
                    event.webhookUrl?.let { url -> notifyWebhook(url, event) }
                }
        }
    }

    private val paymentDb: SqlitePaymentsDb by lazy { peer.db.payments as SqlitePaymentsDb }

    private fun missing(argName: String): Nothing = throw MissingRequestParameterException(argName)

    private fun invalidType(argName: String, typeName: String): Nothing = throw ParameterConversionException(argName, typeName)

    private fun badRequest(message: String): Nothing = throw BadRequestException(message)

    private fun Parameters.getString(argName: String): String = (this[argName] ?: missing(argName))

    private fun Parameters.getByteVector32(argName: String): ByteVector32 = getString(argName).let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull() ?: invalidType(argName, "hex32") }

    private fun Parameters.getOptionalByteVector32(argName: String): ByteVector32? = this[argName]?.let { hex -> kotlin.runCatching { ByteVector32.fromValidHex(hex) }.getOrNull() ?: invalidType(argName, "hex32") }

    private fun Parameters.getUUID(argName: String): UUID = getString(argName).let { uuid -> kotlin.runCatching { UUID.fromString(uuid) }.getOrNull() ?: invalidType(argName, "uuid") }

    private fun Parameters.getAddressAndConvertToScript(argName: String): ByteVector = Script.write(Bitcoin.addressToPublicKeyScript(nodeParams.chainHash, getString(argName)).right ?: badRequest("Invalid address")).toByteVector()

    private fun Parameters.getInvoice(argName: String): Bolt11Invoice = getString(argName).let { invoice -> Bolt11Invoice.read(invoice).getOrElse { invalidType(argName, "bolt11invoice") } }

    private fun Parameters.getOffer(argName: String): OfferTypes.Offer = getString(argName).let { invoice -> OfferTypes.Offer.decode(invoice).getOrElse { invalidType(argName, "offer") } }

    private fun Parameters.getLong(argName: String): Long = ((this[argName] ?: missing(argName)).toLongOrNull()) ?: invalidType(argName, "integer")

    private fun Parameters.getOptionalLong(argName: String): Long? = this[argName]?.let { it.toLongOrNull() ?: invalidType(argName, "integer") }

    private fun Parameters.getEmailLikeAddress(argName: String): Pair<String, String> = this[argName]?.let { Parser.parseEmailLikeAddress(it) } ?: invalidType(argName, "username@domain")

    private fun Parameters.getLnurl(argName: String): Lnurl = this[argName]?.let { LnurlParser.extractLnurl(it) } ?: missing(argName)

    private fun Parameters.getLnurlAuth(argName: String): LnurlAuth = this[argName]?.let { LnurlParser.extractLnurl(it) as LnurlAuth } ?: missing(argName)

    private fun Parameters.getOptionalUrl(argName: String): Url? = this[argName]?.let { runCatching { Url(it) }.getOrNull() ?: invalidType(argName, "url") }

}
