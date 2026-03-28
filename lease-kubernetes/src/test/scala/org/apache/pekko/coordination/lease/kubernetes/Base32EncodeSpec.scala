/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.coordination.lease.kubernetes

import java.nio.charset.StandardCharsets.UTF_8

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * Tests for [[AbstractKubernetesLease.base32Encode]].
 *
 * Expected values are derived from RFC 4648 §10 test vectors, with the standard uppercase
 * alphabet replaced by the lowercase alphabet used here ([a-z2-7] instead of [A-Z2-7]) and
 * without the '=' padding characters.
 */
class Base32EncodeSpec extends AnyWordSpec with Matchers {

  private def encode(bytes: Array[Byte]): String =
    AbstractKubernetesLease.base32Encode(bytes)

  private def encode(bytes: Int*): String =
    encode(bytes.map(_.toByte).toArray)

  "base32Encode" should {

    // ---- edge cases -------------------------------------------------------

    "return empty string for an empty array" in {
      encode(Array.empty[Byte]) shouldEqual ""
    }

    "encode a single zero byte" in {
      // 0x00 = 00000000 → 5 bits: 00000='a', remaining 3 bits: 000<<2=00000='a'
      encode(0x00) shouldEqual "aa"
    }

    "encode a single all-ones byte" in {
      // 0xFF = 11111111 → 5 bits: 11111='7', remaining 3 bits: 111<<2=11100=28='4'
      encode(0xFF) shouldEqual "74"
    }

    "encode two zero bytes" in {
      // 16 bits of zeros: 3 full groups of 5 + 1 remaining bit
      // 00000 00000 00000 0[0000] → 'a','a','a','a'
      encode(0x00, 0x00) shouldEqual "aaaa"
    }

    "encode two all-ones bytes" in {
      // 0xFF 0xFF = 11111111 11111111 → 3×5 full + 1 remaining
      // 11111=31='7', 11111=31='7', 11111=31='7', 1[0000]=16='q'
      encode(0xFF, 0xFF) shouldEqual "777q"
    }

    // ---- RFC 4648 §10 test vectors (lowercase, no padding) ----------------

    "encode 'f' (single byte 0x66) matching RFC 4648 vector" in {
      // 0x66 = 01100110 → 01100=12='m', 110<<2=11000=24='y'
      encode("f".getBytes(UTF_8)) shouldEqual "my"
    }

    "encode 'fo' (two bytes) matching RFC 4648 vector" in {
      encode("fo".getBytes(UTF_8)) shouldEqual "mzxq"
    }

    "encode 'foo' (three bytes) matching RFC 4648 vector" in {
      encode("foo".getBytes(UTF_8)) shouldEqual "mzxw6"
    }

    "encode 'foob' (four bytes) matching RFC 4648 vector" in {
      encode("foob".getBytes(UTF_8)) shouldEqual "mzxw6yq"
    }

    "encode 'fooba' (five bytes — full 8-char group) matching RFC 4648 vector" in {
      encode("fooba".getBytes(UTF_8)) shouldEqual "mzxw6ytb"
    }

    "encode 'foobar' (six bytes) matching RFC 4648 vector" in {
      encode("foobar".getBytes(UTF_8)) shouldEqual "mzxw6ytboi"
    }

    // ---- output properties ------------------------------------------------

    "produce output containing only [a-z2-7] characters for arbitrary inputs" in {
      val inputs: Seq[Array[Byte]] = Seq(
        Array.empty,
        Array(0x00.toByte),
        Array(0xFF.toByte),
        Array.fill(1)(0xAB.toByte),
        Array.fill(3)(0xCD.toByte),
        (0 to 255).map(_.toByte).toArray)
      for (input <- inputs) {
        withClue(s"input length ${input.length}: ") {
          (encode(input) should fullyMatch).regex("[a-z2-7]*")
        }
      }
    }

    "never emit '=' padding characters" in {
      // Lengths 0–6 bytes cover all residue classes mod 5
      for (len <- 0 to 6) {
        val input = Array.fill(len)(0x42.toByte)
        withClue(s"length $len: ") {
          (encode(input) should not).include("=")
        }
      }
    }

    "produce correct output length (ceiling of 8n/5 characters for n input bytes)" in {
      // RFC 4648 unpadded length = ceil(n * 8 / 5)
      for (n <- 0 to 10) {
        val expectedLen = if (n == 0) 0 else math.ceil(n * 8.0 / 5).toInt
        val input = Array.fill(n)(0x00.toByte)
        withClue(s"n=$n: ") {
          encode(input).length shouldEqual expectedLen
        }
      }
    }

    "produce the same output for the same input (determinism)" in {
      val input = "hello world".getBytes(UTF_8)
      encode(input) shouldEqual encode(input)
    }

    "produce different output for different inputs" in {
      (encode("abc".getBytes(UTF_8)) should not).equal(encode("abd".getBytes(UTF_8)))
    }
  }
}
