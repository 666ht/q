/*
 * Copyright (c) 2018 m2049r
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.anonero.model

import kotlinx.serialization.Serializable
import java.util.regex.Pattern

@Serializable
class Subaddress(
    private val accountIndex: Int,
    val addressIndex: Int,
    val address: String,
    var label: String
) : Comparable<Subaddress> {
    var amount: Long = 0

    override fun compareTo(other: Subaddress): Int { // newer is <
        val compareAccountIndex = other.accountIndex - accountIndex
        return if (compareAccountIndex == 0) other.addressIndex - addressIndex else compareAccountIndex
    }

    val squashedAddress: String
        get() = address.substring(0, 8) + "…" + address.substring(address.length - 8)

    val displayLabel: String
        get() {
            if (addressIndex == 0) return "主地址 0"

            val baseLabel = if (
                label.isEmpty() ||
                DEFAULT_LABEL_FORMATTER.matcher(label).matches() ||
                label.matches(Regex("""(?i).*\b(primary\s+address|subaddress|address)\b.*"""))
            ) {
                "子地址"
            } else {
                label.replace(Regex("""\s*[#＃]?\s*\d+$"""), "")
            }
            return "$baseLabel $addressIndex"
        }

    companion object {
        val DEFAULT_LABEL_FORMATTER: Pattern =
            Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]{2}:[0-9]{2}:[0-9]{2}$")
    }

    fun withLabel(newLabel: String): Subaddress {
        return Subaddress(accountIndex, addressIndex, address, newLabel).also {
            it.amount = amount
        }
    }

    val totalAmount: Long
        get() = amount


}