// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package speedy
package perf

import com.daml.bazeltools.BazelRunfiles._
import com.daml.lf.archive.{Decode, UniversalArchiveReader}
import com.daml.lf.data.Ref.{Identifier, QualifiedName, Party}
import com.daml.lf.data.Time
import com.daml.lf.language.Ast.EVal
import com.daml.lf.speedy.SResult._
import com.daml.lf.transaction.Transaction.Value
import com.daml.lf.types.Ledger
import com.daml.lf.types.Ledger._
import com.daml.lf.value.Value.{ContractId, ContractInst}
import com.daml.lf.speedy.SExpr.{SEApp, SEValue}
import com.daml.lf.speedy.Speedy.Machine

import java.io.File
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._

class CollectAuthority {
  @Benchmark @BenchmarkMode(Array(Mode.AverageTime)) @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def bench(state: CollectAuthorityState): Unit = {
    state.run()
  }
}

@State(Scope.Benchmark)
class CollectAuthorityState {

  @Param(Array("//daml-lf/scenario-interpreter/CollectAuthority.dar"))
  private var dar: String = _
  @Param(Array("CollectAuthority:test"))
  private var scenario: String = _

  var machine: Machine = _
  var the_sexpr: SExpr = _

  @Setup(Level.Trial)
  def init(): Unit = {
    val darFile = new File(if (dar.startsWith("//")) rlocation(dar.substring(2)) else dar)
    val packages = UniversalArchiveReader().readFile(darFile).get
    val packagesMap = packages.all.map {
      case (pkgId, pkgArchive) => Decode.readArchivePayloadAndVersion(pkgId, pkgArchive)._1
    }.toMap
    val stacktracing = Compiler.FullStackTrace

    // NOTE(MH): We use a static seed to get reproducible runs.
    val seeding = crypto.Hash.secureRandom(crypto.Hash.hashPrivateKey("scenario-perf"))
    val compiledPackages = PureCompiledPackages(packagesMap, stacktracing).right.get
    val compiler = compiledPackages.compiler
    val expr = EVal(Identifier(packages.main._1, QualifiedName.assertFromString(scenario)))

    // This is the expression which we insert into the machine each time we run()
    the_sexpr = SEApp(compiler.unsafeCompile(expr), Array(SEValue.Token))

    machine = Machine.fromSExpr(
      sexpr = the_sexpr,
      compiledPackages = compiledPackages,
      submissionTime = Time.Timestamp.MinValue,
      seeding = InitialSeeding.TransactionSeed(seeding()),
      globalCids = Set.empty
    )

    // fill the caches!
    setup()
  }

  // Caches for Party creation & Ledger interaction performed during the setup run.
  // The maps are indexed by step number.
  private var cachedParty: Map[Int, Party] = Map()
  private var cachedCommit: Map[Int, SValue] = Map()
  private var cachedContract: Map[Int, ContractInst[Value[ContractId]]] = Map()

  // This is function that we benchmark
  def run(): Unit = {
    machine.setExpressionToEvaluate(the_sexpr)
    var step = 0
    var finalValue: SValue = null
    while (finalValue == null) {
      step += 1
      machine.run() match {
        case SResultScenarioGetParty(_, callback) => callback(cachedParty(step))
        case SResultScenarioCommit(_, _, _, callback) => callback(cachedCommit(step))
        case SResultNeedContract(_, _, _, _, callback) => callback(cachedContract(step))
        case SResultFinalValue(v) => finalValue = v
        case r => crash("bench run: unexpected result from speedy")
      }
    }
  }

  // This is the initial setup run (not benchmarked), where we cache the results of
  // interacting with the ledger, so they can be reused during the benchmark runs.

  def setup(): Unit = {
    var ledger: Ledger = Ledger.initialLedger(Time.Timestamp.Epoch)
    var step = 0
    var finalValue: SValue = null
    while (finalValue == null) {
      step += 1
      machine.run() match {
        case SResultScenarioGetParty(partyText, callback) =>
          Party.fromString(partyText) match {
            case Right(res) =>
              cachedParty = cachedParty + (step -> res)
              callback(res)
            case Left(msg) =>
              crash(s"Party.fromString failed: $msg")
          }
        case SResultScenarioCommit(value, tx, committers, callback) =>
          Ledger.commitTransaction(
            committers.head,
            ledger.currentTime,
            machine.commitLocation,
            tx,
            ledger
          ) match {
            case Left(fas) => crash(s"commitTransaction failed: $fas")
            case Right(result) =>
              ledger = result.newLedger
              cachedCommit = cachedCommit + (step -> value)
              callback(value)
          }
        case SResultNeedContract(acoid, _, committers, _, callback) =>
          val effectiveAt = ledger.currentTime
          ledger.lookupGlobalContract(ParticipantView(committers.head), effectiveAt, acoid) match {
            case LookupOk(_, result) =>
              cachedContract = cachedContract + (step -> result)
              callback(result)
            case x =>
              crash(s"lookupGlobalContract failed: $x")
          }
        case SResultFinalValue(v) =>
          finalValue = v
        case r =>
          crash("setup run: unexpected result from speedy")
      }
    }
  }

  def crash(reason: String) =
    throw new RuntimeException(s"CollectAuthority: $reason")
}
