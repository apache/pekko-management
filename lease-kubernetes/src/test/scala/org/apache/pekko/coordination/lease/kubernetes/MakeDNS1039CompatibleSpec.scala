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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MakeDNS1039CompatibleSpec extends AnyWordSpec with Matchers {

  "makeDNS1039Compatible" should {

    "leave a simple lowercase name unchanged" in {
      AbstractKubernetesLease.makeDNS1039Compatible("my-lease") shouldEqual "my-lease"
    }

    "convert underscores and dots to hyphens" in {
      AbstractKubernetesLease.makeDNS1039Compatible("my.lease_name") shouldEqual "my-lease-name"
    }

    "strip leading and trailing hyphens after normalization" in {
      AbstractKubernetesLease.makeDNS1039Compatible("-my-lease-") shouldEqual "my-lease"
    }

    "remove characters that are not allowed in DNS 1039 labels" in {
      AbstractKubernetesLease.makeDNS1039Compatible("my@lease!name") shouldEqual "myleasename"
    }

    "convert uppercase to lowercase" in {
      AbstractKubernetesLease.makeDNS1039Compatible("MyLease") shouldEqual "mylease"
    }

    "truncate to 63 characters by default (no hash when hashLength is 0)" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 0)
      (result should have).length(63)
      result shouldEqual "a" * 63
    }

    "truncate to a custom maxLength (no hash when hashLength is 0)" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 40, 0)
      (result should have).length(40)
      result shouldEqual "a" * 40
    }

    "trim trailing hyphens after truncation (no hash)" in {
      val name = "a" * 30 + "-" + "b" * 30
      val result = AbstractKubernetesLease.makeDNS1039Compatible(name, 31, 0)
      (result should not).endWith("-")
    }

    "not truncate when name fits within maxLength" in {
      val name63 = "a" * 63
      AbstractKubernetesLease.makeDNS1039Compatible(name63, 63, 8) shouldEqual name63
      (AbstractKubernetesLease.makeDNS1039Compatible(name63 + "extra", 63, 8) should not).equal(name63 + "extra")
    }

    "append hash suffix when truncation is needed and hashLength > 0" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 8)
      (result should have).length(63)
      (result.takeRight(8) should fullyMatch).regex("[a-z2-7]{8}")
      result should include("-")
    }

    "produce a deterministic hash suffix for the same input" in {
      val name = "my-very-long-lease-name-that-exceeds-the-maximum-allowed-kubernetes-length"
      val r1 = AbstractKubernetesLease.makeDNS1039Compatible(name, 63, 8)
      val r2 = AbstractKubernetesLease.makeDNS1039Compatible(name, 63, 8)
      r1 shouldEqual r2
    }

    "produce different hash suffixes for different original names that truncate to the same prefix" in {
      // Both names normalize to 'a' * N, but originate from different strings
      val name1 = "a" * 100
      val name2 = "A" * 100 // normalizes to same 'a'*100 but is a different original
      val r1 = AbstractKubernetesLease.makeDNS1039Compatible(name1, 63, 8)
      val r2 = AbstractKubernetesLease.makeDNS1039Compatible(name2, 63, 8)
      // The prefix is identical but hash suffixes differ because originals differ
      (r1 should not).equal(r2)
    }

    "produce different results for long names that differ only in the last character" in {
      // Both names are 70 chars and share the first 69 chars; they truncate to the same prefix
      // without a hash but must produce different DNS1039 results with one
      val base = "a" * 69
      val name1 = base + "b"
      val name2 = base + "c"
      val r1 = AbstractKubernetesLease.makeDNS1039Compatible(name1, 63, 8)
      val r2 = AbstractKubernetesLease.makeDNS1039Compatible(name2, 63, 8)
      (r1 should not).equal(r2)
      // Both must be valid length
      (r1 should have).length(63)
      (r2 should have).length(63)
    }

    "produce different results for long names that differ only in the last two characters" in {
      val base = "a" * 68
      val name1 = base + "bc"
      val name2 = base + "de"
      val r1 = AbstractKubernetesLease.makeDNS1039Compatible(name1, 63, 8)
      val r2 = AbstractKubernetesLease.makeDNS1039Compatible(name2, 63, 8)
      (r1 should not).equal(r2)
      (r1 should have).length(63)
      (r2 should have).length(63)
    }

    "produce only valid DNS 1039 characters when hash suffix is added" in {
      val longName = "My-Very-Long-Lease.Name_With_Special-Characters-That-Exceeds-63-Chars-Limit"
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 8)
      (result should fullyMatch).regex("[a-z][a-z0-9-]*[a-z0-9]")
      (result should have).length(63)
    }

    "respect a custom hashLength" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 12)
      (result should have).length(63)
      // last 12 chars are the hash suffix
      (result.takeRight(12) should fullyMatch).regex("[a-z2-7]{12}")
    }

    "not add hash when hashLength is 0 even if truncation occurs" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 0)
      result shouldEqual "a" * 63
    }

    "return full hash when hashLength equals maxLength" in {
      val longName = "a" * 100
      // SHA-256 → 32 bytes → 52 base32 chars; take(maxLength=63) returns the full 52-char hash
      // but that leaves room for a short prefix and hyphen
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 63)
      (result should have).length(63)
      assert(result.startsWith("aaaaaaaaaa-"))
    }

    "return full (capped at maxLength) when hashLength exceeds maxLength" in {
      val longName = "a" * 100
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 63, 100)
      (result should have).length(63)
      assert(result.startsWith("aaaaaaaaaa-"))
    }

    "return different names when hashLength >= maxLength and original names differ" in {
      val name1 = "a" * 100
      val name2 = ("a" * 99) + "b" // normalizes to same 'a'*100 but is a different original
      val r1 = AbstractKubernetesLease.makeDNS1039Compatible(name1, 63, 100)
      val r2 = AbstractKubernetesLease.makeDNS1039Compatible(name2, 63, 100)
      (r1 should not).equal(r2)
      (r1 should have).length(63)
      (r2 should have).length(63)
    }

    "return a valid DNS 1039 name when hashLength equals maxLength for a small maxLength" in {
      // maxLength=10, hashLength=10: take(10) from 52 base32 chars → exactly 10 chars
      val longName = "My-Very-Long-Lease.Name_With_Special-Characters"
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 10, 10)
      (result should have).length(10)
      (result should fullyMatch).regex("[a-z2-7]{10}")
    }

    "return a valid DNS 1039 name when hashLength 1 less than maxLength for a small maxLength" in {
      // maxLength=10, hashLength=9: take(9) from 52 base32 chars → exactly 9 chars
      val longName = "My-Very-Long-Lease.Name_With_Special-Characters"
      val result = AbstractKubernetesLease.makeDNS1039Compatible(longName, 10, 9)
      (result should have).length(9)
      (result should fullyMatch).regex("[a-z2-7]{9}")
    }

    "hash suffix contains only lowercase letters and digits (no uppercase, no '=' padding)" in {
      // Use many different inputs to exercise the full base32 output, including partial-group chars
      val inputs = Seq(
        "a" * 100,
        "my-very-long-lease-name-that-needs-to-be-truncated-for-kubernetes",
        "UPPER_CASE.DOTTED_NAME-that-is-indeed-too-long-for-kubernetes-label-limit",
        "x" * 70)
      for (name <- inputs) {
        val result = AbstractKubernetesLease.makeDNS1039Compatible(name, 63, 8)
        val suffix = result.takeRight(8)
        withClue(s"suffix '$suffix' of '$result' (from '$name') must match [a-z2-7]{8}: ") {
          (suffix should fullyMatch).regex("[a-z2-7]{8}")
        }
        withClue(s"result '$result' must not contain '=': ") {
          (result should not).include("=")
        }
      }
    }
  }
}
