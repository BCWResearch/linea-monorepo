package net.consensys.zkevm.ethereum.finalization

import io.vertx.core.Vertx
import io.vertx.junit5.Timeout
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import net.consensys.FakeFixedClock
import net.consensys.linea.ethereum.gaspricing.FakeGasPriceCapProvider
import net.consensys.zkevm.coordinator.clients.prover.serialization.BlobCompressionProofJsonResponse
import net.consensys.zkevm.coordinator.clients.prover.serialization.ProofToFinalizeJsonResponse
import net.consensys.zkevm.coordinator.clients.smartcontract.LineaContractVersion
import net.consensys.zkevm.coordinator.clients.smartcontract.LineaRollupSmartContractClient
import net.consensys.zkevm.domain.Aggregation
import net.consensys.zkevm.domain.BlobRecord
import net.consensys.zkevm.domain.Constants.LINEA_BLOCK_INTERVAL
import net.consensys.zkevm.domain.createAggregation
import net.consensys.zkevm.domain.createBlobRecords
import net.consensys.zkevm.ethereum.Account
import net.consensys.zkevm.ethereum.ContractsManager
import net.consensys.zkevm.ethereum.MakeFileDelegatedContractsManager
import net.consensys.zkevm.ethereum.findFile
import net.consensys.zkevm.ethereum.submission.BlobSubmissionCoordinator
import net.consensys.zkevm.ethereum.submission.L1ShnarfBasedAlreadySubmittedBlobsFilter
import net.consensys.zkevm.persistence.AggregationsRepository
import net.consensys.zkevm.persistence.BlobsRepository
import net.consensys.zkevm.persistence.dao.aggregation.AggregationsRepositoryImpl
import net.consensys.zkevm.persistence.dao.aggregation.PostgresAggregationsDao
import net.consensys.zkevm.persistence.dao.blob.BlobsPostgresDao
import net.consensys.zkevm.persistence.dao.blob.BlobsRepositoryImpl
import net.consensys.zkevm.persistence.db.DbHelper
import net.consensys.zkevm.persistence.test.CleanDbTestSuiteParallel
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.waitAtMost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tech.pegasys.teku.infrastructure.async.SafeFuture
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@ExtendWith(VertxExtension::class)
class BlobAndAggregationFinalizationIntTest : CleanDbTestSuiteParallel() {
  override val databaseName = DbHelper.generateUniqueDbName("coordinator-tests-submission-int-test")
  private val fakeClock = FakeFixedClock()
  private lateinit var lineaRollupContractForAggregationSubmission: LineaRollupSmartContractClient
  private lateinit var contractDeploymentAccount: Account
  private lateinit var aggregationsRepository: AggregationsRepository
  private lateinit var blobsRepository: BlobsRepository
  private lateinit var blobSubmissionCoordinator: BlobSubmissionCoordinator
  private lateinit var aggregationFinalizationCoordinator: AggregationFinalizationCoordinator
  private val testDataDir = "coordinator/ethereum/blob-submitter/src/integrationTest/test-data"

  // 1-block-per-blob test data has 3 aggregations: 1..7, 8..14, 15..21.
  // We will upgrade the contract in the middle of 2nd aggregation: 12
  // shall submit blob 12, stop submission, upgrade the contract and resume with blob 13
  // val lastSubmittedBlobs = blobs.filter { it.startBlockNumber == 7UL }
  private lateinit var aggregations: List<Aggregation>
  private lateinit var blobs: List<BlobRecord>

  private fun setupTest(
    vertx: Vertx,
    smartContractVersion: LineaContractVersion,
    overridingTestDataDir: String? = null
  ) {
    val rollupDeploymentFuture = ContractsManager.get()
      .deployLineaRollup(numberOfOperators = 2, contractVersion = LineaContractVersion.V5)
    // load files from FS while smc deploy
    loadBlobsAndAggregations(smartContractVersion, overridingTestDataDir)
      .let { (blobs, aggregations) ->
        this.blobs = blobs
        this.aggregations = aggregations
      }
    // wait smc deployment finishes
    val rollupDeploymentResult = rollupDeploymentFuture.get()

    contractDeploymentAccount = rollupDeploymentResult.contractDeploymentAccount

    blobsRepository = BlobsRepositoryImpl(
      BlobsPostgresDao(
        config = BlobsPostgresDao.Config(maxBlobsToReturn = 100U),
        connection = sqlClient,
        clock = fakeClock
      )
    )
    aggregationsRepository = AggregationsRepositoryImpl(PostgresAggregationsDao(sqlClient, fakeClock))

    val lineaRollupContractForDataSubmissionV4 = rollupDeploymentResult.rollupOperatorClient

    @Suppress("DEPRECATION")
    val alreadySubmittedBlobFilter = L1ShnarfBasedAlreadySubmittedBlobsFilter(lineaRollupContractForDataSubmissionV4)

    blobSubmissionCoordinator = run {
      BlobSubmissionCoordinator.create(
        config = BlobSubmissionCoordinator.Config(
          pollingInterval = 6.seconds,
          proofSubmissionDelay = 0.seconds,
          maxBlobsToSubmitPerTick = 100u,
          targetBlobsToSubmitPerTx = 6u
        ),
        blobsRepository = blobsRepository,
        aggregationsRepository = aggregationsRepository,
        lineaSmartContractClient = lineaRollupContractForDataSubmissionV4,
        alreadySubmittedBlobsFilter = alreadySubmittedBlobFilter,
        gasPriceCapProvider = FakeGasPriceCapProvider(),
        vertx = vertx,
        clock = fakeClock
      )
    }

    aggregationFinalizationCoordinator = run {
      lineaRollupContractForAggregationSubmission = MakeFileDelegatedContractsManager
        .connectToLineaRollupContractV5(
          rollupDeploymentResult.contractAddress,
          rollupDeploymentResult.rollupOperators[1].txManager
        )

      val aggregationSubmitter = AggregationSubmitterImpl(
        lineaRollup = lineaRollupContractForAggregationSubmission,
        gasPriceCapProvider = FakeGasPriceCapProvider()
      )

      AggregationFinalizationCoordinator(
        config = AggregationFinalizationCoordinator.Config(
          pollingInterval = 6.seconds,
          proofSubmissionDelay = 0.seconds
        ),
        aggregationSubmitter = aggregationSubmitter,
        aggregationsRepository = aggregationsRepository,
        blobsRepository = blobsRepository,
        lineaRollup = lineaRollupContractForAggregationSubmission,
        alreadySubmittedBlobsFilter = alreadySubmittedBlobFilter,
        vertx = vertx,
        clock = fakeClock
      )
    }
  }

  @Test
  @Timeout(3, timeUnit = TimeUnit.MINUTES)
  fun `submission works with contract V5`(
    vertx: Vertx,
    testContext: VertxTestContext
  ) {
    testSubmission(vertx, testContext, LineaContractVersion.V5)
  }

  private fun testSubmission(
    vertx: Vertx,
    testContext: VertxTestContext,
    smartContractVersion: LineaContractVersion,
    overridingTestDataDir: String? = null
  ) {
    setupTest(vertx, smartContractVersion, overridingTestDataDir)

    SafeFuture.allOf(
      SafeFuture.collectAll(blobs.map { blobsRepository.saveNewBlob(it) }.stream()),
      SafeFuture.collectAll(aggregations.map { aggregationsRepository.saveNewAggregation(it) }.stream())
    ).get()

    val aggEndTime = aggregations.last().aggregationProof!!.finalTimestamp
    val blobsEndTime = blobs.last().endBlockTime
    val endTime = if (aggEndTime > blobsEndTime) aggEndTime else blobsEndTime

    fakeClock.setTimeTo(endTime.plus(10.seconds))

    blobSubmissionCoordinator.start()
    aggregationFinalizationCoordinator.start()
      .thenApply {
        waitAtMost(2.minutes.toJavaDuration())
          .pollInterval(1.seconds.toJavaDuration())
          .untilAsserted {
            val finalizedBlockNumber = lineaRollupContractForAggregationSubmission.finalizedL2BlockNumber().get()
            assertThat(finalizedBlockNumber).isEqualTo(aggregations.last().endBlockNumber)
          }
        testContext.completeNow()
      }.whenException(testContext::failNow)
  }

  private fun proverResponsesFromDir(dir: String): List<File> {
    return findFile(dir)
      .toFile()
      .listFiles()
      ?.filter { it.name.endsWith(".json") }
      ?: emptyList()
  }

  private fun <T> loadProverResponses(responsesDir: String, mapper: (String) -> T): List<T> {
    return proverResponsesFromDir(responsesDir)
      .map { mapper.invoke(it.readText()) }
  }

  private fun loadAggregations(aggregationsDir: String): List<Aggregation> {
    return loadProverResponses(aggregationsDir) {
      createAggregation(aggregationProof = ProofToFinalizeJsonResponse.fromJsonString(it).toDomainObject())
    }.sortedBy { it.startBlockNumber }
  }

  private fun loadBlobs(blobsDir: String, aggregations: List<Aggregation>): List<BlobRecord> {
    return loadProverResponses(blobsDir) {
      BlobCompressionProofJsonResponse.fromJsonString(it).toDomainObject()
    }
      .let { compressionProofs ->
        val firstAggregationBlockTime = aggregations.first().let { agg ->
          agg.aggregationProof!!.finalTimestamp
            .minus(LINEA_BLOCK_INTERVAL.times((agg.endBlockNumber - agg.startBlockNumber).toInt()))
        }
        createBlobRecords(
          compressionProofs = compressionProofs,
          firstBlockStartBlockTime = firstAggregationBlockTime
        )
      }
      .sortedBy { it.startBlockNumber }
  }

  private fun loadBlobsAndAggregations(
    smartContractVersion: LineaContractVersion,
    overridingTestDataDir: String? = null
  ): Pair<List<BlobRecord>, List<Aggregation>> {
    val testCaseDataDir = overridingTestDataDir
      ?: (
        testDataDir + if (smartContractVersion == LineaContractVersion.V5) {
          "/start-at-v5"
        } else {
          throw IllegalArgumentException("Unsupported contract version: $smartContractVersion")
        }
        )

    val aggregations = loadAggregations("$testCaseDataDir/prover-aggregation/responses")
    val blobs = loadBlobs("$testCaseDataDir/prover-compression/responses", aggregations)
    return blobs to aggregations
  }
}
