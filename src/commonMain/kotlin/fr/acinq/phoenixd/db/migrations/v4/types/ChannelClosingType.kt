/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.phoenixd.db.migrations.v4.types

import fr.acinq.lightning.db.ChannelCloseOutgoingPayment.ChannelClosingType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json


enum class ClosingInfoTypeVersion {
    // basic type, containing only a [ChannelClosingType] field
    CLOSING_INFO_V0,
}

sealed class ClosingInfoData {

    @Serializable
    @SerialName("fr.acinq.lightning.bin.db.payments.ClosingInfoData.V0")
    data class V0(val closingType: ChannelClosingType)

    companion object {
        fun deserialize(typeVersion: ClosingInfoTypeVersion, blob: ByteArray): ChannelClosingType =
            when (typeVersion) {
                ClosingInfoTypeVersion.CLOSING_INFO_V0 -> Json.decodeFromString<V0>(blob.decodeToString()).closingType
            }
    }
}
