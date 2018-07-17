package com.example

import co.paralleluniverse.fibers.Suspendable
import it.oraclize.cordapi.OraclizeUtils
import it.oraclize.cordapi.entities.ProofType
import it.oraclize.cordapi.examples.contracts.CashIssueContract
import it.oraclize.cordapi.examples.states.CashOwningState
import it.oraclize.cordapi.flows.OraclizeQueryAwaitFlow
import it.oraclize.cordapi.flows.OraclizeSignFlow
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.loggerFor
import java.util.function.Predicate


@InitiatingFlow
@StartableByRPC
class ExampleFlow : FlowLogic<SignedTransaction>() {


    companion object {
        object ORACLIZE_ANSWER : ProgressTracker.Step("Oraclize answer")
        object VERIFYING_PROOF : ProgressTracker.Step("Verifying the proof")
        object TX_BUILDING : ProgressTracker.Step("Transaction building")
        object TX_VERIFYING : ProgressTracker.Step("Verifying")
        object TX_SIGNATURES : ProgressTracker.Step("Signatures")
        object TX_FINAL : ProgressTracker.Step("Finalizing")

        fun tracker() = ProgressTracker(ORACLIZE_ANSWER, VERIFYING_PROOF, TX_BUILDING, TX_VERIFYING, TX_SIGNATURES, TX_FINAL)

        val console = loggerFor<ExampleFlow>()
    }

    override val progressTracker: ProgressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryIdentities.first()
        val oracle = serviceHub.identityService.wellKnownPartyFromX500Name(OraclizeUtils.getNodeName()) as Party

        progressTracker.currentStep = ORACLIZE_ANSWER
        val issueState = CashOwningState(10, ourIdentity)
        val answer = subFlow(OraclizeQueryAwaitFlow("URL",
                "json(https://www.therocktrading.com/api/ticker/BTCEUR).result.0.last",
                ProofType.TLSNOTARY))

        val proofVerificationTool = OraclizeUtils.ProofVerificationTool()

        val verified = proofVerificationTool.verifyProof(answer.proof!!)

        console.info(if (verified) "Proof verified!" else "Proof not verified")

        val issueCmd = Command(CashIssueContract.Commands.Issue(), listOf(ourIdentity.owningKey))
        val answerCmd = Command(answer, oracle.owningKey)

        console.info(answer.value)

        progressTracker.currentStep = TX_BUILDING
        val tx = TransactionBuilder(notary).withItems(
                StateAndContract(issueState, CashIssueContract.TEST_CONTRACT_ID),
                issueCmd,
                answerCmd
        )

        progressTracker.currentStep = TX_VERIFYING
        tx.verify(serviceHub)

        val filtering = OraclizeUtils()::filtering
        val ftx = tx.toWireTransaction(serviceHub).buildFilteredTransaction(
                Predicate { filtering(oracle.owningKey, it) }
        )

        val oracleSignature = subFlow(OraclizeSignFlow(ftx))

        progressTracker.currentStep = TX_SIGNATURES
        val signed = serviceHub.signInitialTransaction(tx).withAdditionalSignature(oracleSignature)

        progressTracker.currentStep = TX_FINAL
        return subFlow(FinalityFlow(signed))

    }
}
