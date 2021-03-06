// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.extractor

import java.io.File

import com.daml.bazeltools.BazelRunfiles._
import com.daml.extractor.services.{CustomMatchers, ExtractorFixtureAroundAll}
import com.daml.ledger.api.testing.utils.SuiteResourceManagementAroundAll
import com.daml.testing.postgresql.PostgresAroundAll
import io.circe.parser._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalaz.Scalaz._

class GenMapSpec
    extends AnyFlatSpec
    with Suite
    with PostgresAroundAll
    with SuiteResourceManagementAroundAll
    with ExtractorFixtureAroundAll
    with Inside
    with Matchers
    with CustomMatchers {

  override protected def darFile =
    new File(rlocation("daml-lf/encoder/test-1.11.dar"))

  override def scenario: Option[String] = Some("GenMapMod:createContracts")

  "Lists" should "be extracted" in {
    val contracts = getContracts
    contracts should have length 4
  }

  it should "contain the correct JSON data" in {
    val contractsJson = getContracts.map(_.create_arguments)

    val expected = List(
      """
        {
          "x" : [],
          "party" : "Bob"
        }
      """,
      """
        {
          "x" : [
                  [ { "fst" : 1, "snd" : "1.0" },                            { "tag" : "Left", "value" : 0 } ]
                ],
          "party" : "Bob"
        }
      """,
      """
        {
          "x" : [
                  [ { "fst" : -2, "snd" : "-2.2222222222" },                   { "tag" : "Right", "value" : "1.1111111111" } ],
                  [ { "fst" : 1, "snd" : "1.0" },                              { "tag" : "Left", "value" : 0 } ]
                ],
          "party" : "Bob"
        }
      """,
      """
        {
          "x" : [
                  [ { "fst" : -3, "snd" : "-3333333333333333333333333333.0" },  { "tag" : "Right", "value" : "-2.2222222222" } ],
                  [ { "fst" : -2, "snd" : "-2.2222222222" },                    { "tag" : "Right", "value" : "1.1111111111" } ],
                  [ { "fst" : 1, "snd" : "1.0" },                               { "tag" : "Left", "value" : 0 } ]
                ],
          "party" : "Bob"
        }
      """,
    ).traverse(parse)

    expected should be(Symbol("right")) // That should only fail if this JSON^^ is ill-formatted

    contractsJson should contain theSameElementsAs expected.toOption.get
  }
}
