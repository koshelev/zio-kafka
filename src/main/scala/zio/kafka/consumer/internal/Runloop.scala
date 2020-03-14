package zio.kafka.consumer.internal

import java.util

import org.apache.kafka.clients.consumer._
import org.apache.kafka.common.TopicPartition
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.duration._
import zio.kafka.consumer.Consumer.OffsetRetrieval
import zio.kafka.consumer.diagnostics.{ DiagnosticEvent, Diagnostics }
import zio.kafka.consumer.CommittableRecord
import zio.kafka.consumer.internal.ConsumerAccess.ByteArrayKafkaConsumer
import zio.kafka.consumer.internal.Runloop.{ ByteArrayConsumerRecord, Command }
import zio.stream._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

private[consumer] final case class Runloop(fiber: Fiber[Throwable, Unit], deps: Runloop.Deps)
private[consumer] object Runloop {
  type ByteArrayCommittableRecord = CommittableRecord[Array[Byte], Array[Byte]]
  type ByteArrayConsumerRecord    = ConsumerRecord[Array[Byte], Array[Byte]]

  sealed abstract class Command
  object Command {
    case class Request(tp: TopicPartition, cont: Promise[Option[Throwable], Chunk[ByteArrayCommittableRecord]])
        extends Command
    case class Poll()                                                                     extends Command
    case class Commit(offsets: Map[TopicPartition, Long], cont: Promise[Throwable, Unit]) extends Command
  }

  case class Deps(
    consumer: ConsumerAccess,
    pollFrequency: Duration,
    pollTimeout: Duration,
    requestQueue: Queue[Command.Request],
    commitQueue: Queue[Command.Commit],
    partitions: Queue[Take[Throwable, (TopicPartition, ZStreamChunk[Any, Throwable, ByteArrayCommittableRecord])]],
    rebalancingRef: Ref[Boolean],
    rebalanceListener: RebalanceListener,
    diagnostics: Diagnostics,
    shutdownRef: Ref[Boolean],
    offsetRetrieval: OffsetRetrieval
  ) {
    def commit(cmd: Command.Commit)   = commitQueue.offer(cmd).unit
    def commits                       = ZStream.fromQueue(commitQueue)
    def request(cmd: Command.Request) = requestQueue.offer(cmd).unit
    def requests                      = ZStream.fromQueue(requestQueue)

    val isRebalancing = rebalancingRef.get

    def polls = ZStream(Command.Poll()).repeat(Schedule.spaced(pollFrequency))

    def newPartitionStream(tp: TopicPartition) = {
      val stream = ZStreamChunk {
        ZStream {
          ZManaged.succeed {
            for {
              p      <- Promise.make[Option[Throwable], Chunk[ByteArrayCommittableRecord]]
              _      <- request(Command.Request(tp, p))
              _      <- diagnostics.emitIfEnabled(DiagnosticEvent.Request(tp))
              result <- p.await
            } yield result
          }
        }
      }

      partitions.offer(Take.Value(tp -> stream)).unit
    }

    val isShutdown = shutdownRef.get

    def gracefulShutdown: UIO[Unit] =
      for {
        shutdown <- shutdownRef.modify((_, true))
        _        <- partitions.offer(Take.End).when(!shutdown)
      } yield ()
  }

  object Deps {
    def make(
      consumer: ConsumerAccess,
      pollFrequency: Duration,
      pollTimeout: Duration,
      diagnostics: Diagnostics,
      offsetRetrieval: OffsetRetrieval
    ) =
      for {
        rebalancingRef <- Ref.make(false).toManaged_
        requestQueue   <- Queue.unbounded[Command.Request].toManaged(_.shutdown)
        commitQueue    <- Queue.unbounded[Command.Commit].toManaged(_.shutdown)
        partitions <- Queue
                       .unbounded[
                         Take[Throwable, (TopicPartition, ZStreamChunk[Any, Throwable, ByteArrayCommittableRecord])]
                       ]
                       .map { queue =>
                         queue.mapM {
                           case Take.End => queue.shutdown.as(Take.End)
                           case x        => ZIO.succeed(x)
                         }
                       }
                       .toManaged(_.shutdown)
        rebalanceListener = {
          val trackRebalancing = RebalanceListener(
            onAssigned = _ => rebalancingRef.set(false),
            onRevoked = _ => rebalancingRef.set(true)
          )

          val emitDiagnostics = RebalanceListener(
            assigned => diagnostics.emitIfEnabled(DiagnosticEvent.Rebalance.Assigned(assigned)),
            revoked => diagnostics.emitIfEnabled(DiagnosticEvent.Rebalance.Revoked(revoked))
          )

          val pausePartitionsOnRevoke =
            RebalanceListener.onRevoked(revoked => Task(consumer.consumer.pause(revoked.asJavaCollection)))

          trackRebalancing ++ emitDiagnostics ++ pausePartitionsOnRevoke
        }
        shutdownRef <- Ref.make(false).toManaged_
      } yield Deps(
        consumer,
        pollFrequency,
        pollTimeout,
        requestQueue,
        commitQueue,
        partitions,
        rebalancingRef,
        rebalanceListener,
        diagnostics,
        shutdownRef,
        offsetRetrieval
      )
  }

  def apply(deps: Deps): ZManaged[Clock with Blocking, Throwable, Runloop] = {

    def commit(offsets: Map[TopicPartition, Long]): ZIO[Any, Throwable, Unit] =
      for {
        p <- Promise.make[Throwable, Unit]
        _ <- deps.commit(Command.Commit(offsets, p))
        _ <- deps.diagnostics.emitIfEnabled(DiagnosticEvent.Commit.Started(offsets))
        _ <- p.await
      } yield ()

    // Returns the highest offset to commit per partition
    def aggregateOffsets(cmds: List[Command.Commit]): Map[TopicPartition, OffsetAndMetadata] = {
      val offsets = mutable.Map[TopicPartition, OffsetAndMetadata]()

      cmds.foreach { commit =>
        commit.offsets.foreach {
          case (tp, offset) =>
            val existing = offsets.get(tp).fold(-1L)(_.offset())

            if (existing < offset)
              offsets += tp -> new OffsetAndMetadata(offset + 1)
        }
      }

      offsets.toMap
    }

    def doCommit(cmds: List[Command.Commit]): ZIO[Blocking, Nothing, Unit] = {
      val offsets   = aggregateOffsets(cmds)
      val cont      = (e: Exit[Throwable, Unit]) => ZIO.foreach_(cmds)(_.cont.done(e))
      val onSuccess = cont(Exit.succeed(())) <* deps.diagnostics.emitIfEnabled(DiagnosticEvent.Commit.Success(offsets))
      val onFailure = (err: Throwable) =>
        cont(Exit.fail(err)) <* deps.diagnostics.emitIfEnabled(DiagnosticEvent.Commit.Failure(offsets, err))

      ZIO
        .runtime[Any]
        .map(makeOffsetCommitCallback(onSuccess, onFailure))
        .flatMap { callback =>
          deps.consumer.withConsumerM { c =>
            // We don't wait for the completion of the commit here, because it
            // will only complete once we poll again.
            ZIO(c.commitAsync(offsets.asJava, callback))
          }
        }
        .catchAll(onFailure)
    }

    def makeOffsetCommitCallback(onSuccess: Task[Unit], onFailure: Exception => Task[Unit])(
      runtime: Runtime[Any]
    ): OffsetCommitCallback = new OffsetCommitCallback {
      override def onComplete(offsets: util.Map[TopicPartition, OffsetAndMetadata], exception: Exception): Unit =
        runtime.unsafeRun(if (exception eq null) onSuccess else onFailure(exception))
    }

    // Pause partitions for which there is no demand and resume those for which there is now demand
    def resumeAndPausePartitions(
      c: ByteArrayKafkaConsumer,
      assignment: Set[TopicPartition],
      requestedPartitions: Set[TopicPartition]
    ) = {
      val toResume = assignment intersect requestedPartitions
      val toPause  = assignment -- requestedPartitions

      c.resume(toResume.asJava)
      c.pause(toPause.asJava)
    }

    /**
     * Does all needed to end revoked partitions:
     * 1. Fail the Requests's continuation promises
     * 2. Remove from the list of pending requets
     * 3. Remove from buffered records
     * @return New pending requests and new buffered records
     */
    def endRevoked(
      reqs: List[Command.Request],
      bufferedRecords: Map[TopicPartition, Chunk[ByteArrayConsumerRecord]],
      revoked: TopicPartition => Boolean
    ): UIO[
      (List[Command.Request], Map[TopicPartition, Chunk[ByteArrayConsumerRecord]])
    ] = {
      var acc = List[Command.Request]()
      val buf = mutable.Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]()
      buf ++= bufferedRecords

      var revokeAction: UIO[_] = UIO.unit

      val reqsIt = reqs.iterator
      while (reqsIt.hasNext) {
        val req = reqsIt.next
        if (revoked(req.tp)) {
          revokeAction = revokeAction *> req.cont.fail(None)
          buf -= req.tp
        } else acc ::= req
      }

      revokeAction.as((acc.reverse, buf.toMap))
    }

    def fulfillRequests(
      pendingRequests: List[Command.Request],
      bufferedRecords: Map[TopicPartition, Chunk[ByteArrayConsumerRecord]],
      records: ConsumerRecords[Array[Byte], Array[Byte]]
    ): UIO[
      (List[Command.Request], Map[TopicPartition, Chunk[ByteArrayConsumerRecord]])
    ] = {
      var acc = List[Command.Request]()
      val buf = mutable.Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]()
      buf ++= bufferedRecords

      var fulfillAction: UIO[_] = UIO.unit

      val reqsIt = pendingRequests.iterator
      while (reqsIt.hasNext) {
        val req           = reqsIt.next
        val bufferedChunk = buf.getOrElse(req.tp, Chunk.empty)
        val reqRecs       = records.records(req.tp)

        if ((bufferedChunk.length + reqRecs.size) == 0) {
          acc ::= req
        } else {
          val concatenatedChunk = bufferedChunk ++
            Chunk.fromArray(
              reqRecs.toArray(Array.ofDim[ByteArrayConsumerRecord](reqRecs.size))
            )

          fulfillAction = fulfillAction *> req.cont.succeed(
            concatenatedChunk.map(CommittableRecord(_, commit(_)))
          )
          buf -= req.tp
        }
      }

      fulfillAction.as((acc, buf.toMap))
    }

    def bufferUnrequestedPartitions(
      records: ConsumerRecords[Array[Byte], Array[Byte]],
      unrequestedTps: Iterable[TopicPartition]
    ): Map[TopicPartition, Chunk[ByteArrayConsumerRecord]] = {
      val builder = Map.newBuilder[TopicPartition, Chunk[ByteArrayConsumerRecord]]
      builder.sizeHint(unrequestedTps.size)

      val tpsIt = unrequestedTps.iterator
      while (tpsIt.hasNext) {
        val tp   = tpsIt.next
        val recs = records.records(tp)

        if (recs.size > 0)
          builder += (tp -> Chunk.fromArray(
            recs.toArray(Array.ofDim[ByteArrayConsumerRecord](recs.size))
          ))
      }

      builder.result()
    }

    def doSeekForNewPartitions(c: ByteArrayKafkaConsumer, tps: Set[TopicPartition]): Task[Unit] =
      deps.offsetRetrieval match {
        case OffsetRetrieval.Manual(getOffsets) =>
          getOffsets(tps)
            .flatMap(offsets => ZIO.foreach(offsets) { case (tp, offset) => ZIO(c.seek(tp, offset)) })
            .when(tps.nonEmpty)

        case OffsetRetrieval.Auto(_) =>
          ZIO.unit
      }

    def doPoll(c: ByteArrayKafkaConsumer, requestedPartitions: Set[TopicPartition]) =
      try {
        val pollTimeout =
          if (requestedPartitions.nonEmpty) deps.pollTimeout.asJava
          else 0.millis.asJava

        c.poll(pollTimeout)
      } catch {
        // The consumer will throw an IllegalStateException if no call to subscribe
        // has been made yet, so we just ignore that. We have to poll even if c.subscription()
        // is empty because pattern subscriptions start out as empty.
        case _: IllegalStateException => null
      }

    def pauseAllPartitions(c: ByteArrayKafkaConsumer) = ZIO.effectTotal {
      val currentAssigned = c.assignment().asScala.toSet
      c.pause(currentAssigned.asJava)
    }

    def handlePoll(state: State): RIO[Blocking, State] =
      for {
        pollResult <- deps.consumer.withConsumerM { c =>
                       Task.effectSuspend {

                         val prevAssigned        = c.assignment().asScala.toSet
                         val requestedPartitions = state.pendingRequests.map(_.tp).toSet

                         resumeAndPausePartitions(c, prevAssigned, requestedPartitions)

                         val records = doPoll(c, requestedPartitions)

                         // Check shutdown again after polling (which takes up to the poll timeout)
                         deps.isShutdown.flatMap { shutdown =>
                           if (shutdown) {
                             pauseAllPartitions(c) *> ZIO.succeed(
                               (Set(), (state.pendingRequests, Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]()))
                             )
                           } else if (records eq null) {
                             ZIO.succeed(
                               (Set(), (state.pendingRequests, Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]()))
                             )
                           } else {
                             val tpsInResponse   = records.partitions.asScala.toSet
                             val currentAssigned = c.assignment().asScala.toSet
                             val newlyAssigned   = currentAssigned -- prevAssigned
                             val revoked         = prevAssigned -- currentAssigned
                             val unrequestedRecords =
                               bufferUnrequestedPartitions(records, tpsInResponse -- requestedPartitions)

                             doSeekForNewPartitions(c, newlyAssigned) *> endRevoked(
                               state.pendingRequests,
                               state.addBufferedRecords(unrequestedRecords).bufferedRecords,
                               revoked(_)
                             ).flatMap {
                               case (pendingRequests, bufferedRecords) =>
                                 for {
                                   output                    <- fulfillRequests(pendingRequests, bufferedRecords, records)
                                   (notFulfilled, fulfilled) = output
                                   _ <- deps.diagnostics.emitIfEnabled(
                                         DiagnosticEvent.Poll(
                                           requestedPartitions,
                                           fulfilled.keySet,
                                           notFulfilled.map(_.tp).toSet
                                         )
                                       )
                                 } yield output
                             }.map((newlyAssigned, _))
                           }
                         }
                       }
                     }
        (newlyAssigned, (unfulfilledRequests, bufferedRecords)) = pollResult
        _                                                       <- ZIO.foreach_(newlyAssigned)(tp => deps.newPartitionStream(tp))
        stillRebalancing                                        <- deps.isRebalancing
        newPendingCommits <- if (!stillRebalancing && state.pendingCommits.nonEmpty)
                              doCommit(state.pendingCommits).as(Nil)
                            else ZIO.succeed(state.pendingCommits)
      } yield State(unfulfilledRequests, newPendingCommits, bufferedRecords)

    def handleRequest(state: State, req: Command.Request): URIO[Blocking, State] =
      deps.consumer
        .withConsumer(_.assignment.asScala)
        .flatMap { assignment =>
          for {
            rebalancing <- deps.isRebalancing
            newState <- if (!rebalancing && !assignment.contains(req.tp))
                         req.cont.fail(None).as(state)
                       else UIO.succeed(state.addRequest(req))
          } yield newState
        }
        .orElse(UIO.succeed(state.addRequest(req)))

    def handleCommit(state: State, cmd: Command.Commit): URIO[Blocking, State] =
      for {
        rebalancing <- deps.isRebalancing
        newState <- if (rebalancing)
                     UIO.succeed(state.addCommit(cmd))
                   else doCommit(List(cmd)).as(state)
      } yield newState

    def handleShutdown(state: State, cmd: Command): RIO[Blocking, State] = cmd match {
      case Command.Poll() =>
        state.pendingRequests match {
          case h :: t =>
            handleShutdown(state, h).flatMap(s => handleShutdown(s.copy(pendingRequests = t), cmd))
          case Nil => handlePoll(state)
        }
      case Command.Request(tp, cont) =>
        state.bufferedRecords.get(tp) match {
          case Some(recs) =>
            cont
              .succeed(recs.map(CommittableRecord(_, commit(_))))
              .as(state.removeBufferedRecordsFor(tp))
          case None => cont.fail(None).as(state)
        }
      case cmd @ Command.Commit(_, _) => handleCommit(state, cmd)
    }

    ZStream
      .mergeAll(3, 32)(
        deps.polls,
        deps.requests,
        deps.commits
      )
      .foldM(State.initial) { (state, cmd) =>
        deps.isShutdown.flatMap { shutdown =>
          if (shutdown) handleShutdown(state, cmd)
          else
            cmd match {
              case Command.Poll()              => handlePoll(state)
              case req @ Command.Request(_, _) => handleRequest(state, req)
              case cmd @ Command.Commit(_, _)  => handleCommit(state, cmd)
            }
        }
      }
      .onError(cause => deps.partitions.offer(Take.Fail(cause)))
      .unit
      .toManaged_
      .fork
      .map(Runloop(_, deps))
  }
}

private[internal] final case class State(
  pendingRequests: List[Command.Request],
  pendingCommits: List[Command.Commit],
  bufferedRecords: Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]
) {
  def addCommit(c: Command.Commit)   = copy(pendingCommits = c :: pendingCommits)
  def addRequest(c: Command.Request) = copy(pendingRequests = c :: pendingRequests)
  def addBufferedRecords(recs: Map[TopicPartition, Chunk[ByteArrayConsumerRecord]]) =
    copy(
      bufferedRecords = recs.foldLeft(bufferedRecords) {
        case (acc, (tp, recs)) =>
          acc.get(tp) match {
            case Some(existingRecs) => acc + (tp -> (existingRecs ++ recs))
            case None               => acc + (tp -> recs)
          }
      }
    )

  def removeBufferedRecordsFor(tp: TopicPartition) =
    copy(bufferedRecords = bufferedRecords - tp)
}

object State {
  def initial: State = State(Nil, Nil, Map())
}
