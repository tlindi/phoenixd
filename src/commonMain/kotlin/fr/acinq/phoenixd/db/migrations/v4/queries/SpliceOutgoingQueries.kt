/*
 * Copyright 2023 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.phoenixd.db.migrations.v4.queries

import fr.acinq.bitcoin.TxId
import fr.acinq.lightning.db.SpliceOutgoingPayment
import fr.acinq.lightning.utils.UUID
import fr.acinq.lightning.utils.sat
import fr.acinq.lightning.utils.toByteVector32

object SpliceOutgoingQueries {

    fun mapSpliceOutgoingPayment(
        id: String,
        recipient_amount_sat: Long,
        address: String,
        mining_fees_sat: Long,
        tx_id: ByteArray,
        channel_id: ByteArray,
        created_at: Long,
        confirmed_at: Long?,
        locked_at: Long?
    ): SpliceOutgoingPayment {
        return SpliceOutgoingPayment(
            id = UUID.fromString(id),
            recipientAmount = recipient_amount_sat.sat,
            address = address,
            miningFee = mining_fees_sat.sat,
            channelId = channel_id.toByteVector32(),
            txId = TxId(tx_id),
            liquidityPurchase = null,
            createdAt = created_at,
            confirmedAt = confirmed_at,
            lockedAt = locked_at
        )
    }
}