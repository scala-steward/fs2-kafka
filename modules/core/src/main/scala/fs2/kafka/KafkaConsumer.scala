/*
 * Copyright 2018-2021 OVO Energy Limited
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package fs2.kafka

import cats.{Foldable, Reducible, Applicative, Monoid}
import cats.data.{NonEmptyList, NonEmptySet, OptionT}
import cats.effect._
import cats.effect.std._
import cats.effect.implicits._
import cats.syntax.all._
import fs2.{Chunk, Stream}
import fs2.kafka.internal._
import fs2.kafka.internal.converters.collection._
import fs2.kafka.instances._
import fs2.kafka.internal.KafkaConsumerActor._
import fs2.kafka.internal.syntax._
import fs2.kafka.consumer._

import java.util
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.{Metric, MetricName, PartitionInfo, TopicPartition}

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/**
  * [[KafkaConsumer]] represents a consumer of Kafka records, with the
  * ability to `subscribe` to topics, start a single top-level stream,
  * and optionally control it via the provided [[fiber]] instance.<br>
  * <br>
  * The following top-level streams are provided.<br>
  * <br>
  * - [[stream]] provides a single stream of records, where the order
  *   of records is guaranteed per topic-partition.<br>
  * - [[partitionedStream]] provides a stream with elements as streams
  *   that continually request records for a single partition. Order
  *   is guaranteed per topic-partition, but all assigned partitions
  *   will have to be processed in parallel.<br>
  * - [[partitionsMapStream]] provides a stream where each element contains
  *   a current assignment. The current assignment is the `Map`, where keys
  *   is a `TopicPartition`, and values are streams with records for a
  *   particular `TopicPartition`.
  * <br>
  * For the streams, records are wrapped in [[CommittableConsumerRecord]]s
  * which provide [[CommittableOffset]]s with the ability to commit
  * record offsets to Kafka. For performance reasons, offsets are
  * usually committed in batches using [[CommittableOffsetBatch]].
  * Provided `Pipe`s, like [[commitBatchWithin]] are available for
  * batch committing offsets. If you are not committing offsets to
  * Kafka, you can simply discard the [[CommittableOffset]], and
  * only make use of the record.<br>
  * <br>
  * While it's technically possible to start more than one stream from a
  * single [[KafkaConsumer]], it is generally not recommended as there is
  * no guarantee which stream will receive which records, and there might
  * be an overlap, in terms of duplicate records, between the two streams.
  * If a first stream completes, possibly with error, there's no guarantee
  * the stream has processed all of the records it received, and a second
  * stream from the same [[KafkaConsumer]] might not be able to pick up where
  * the first one left off. Therefore, only create a single top-level stream
  * per [[KafkaConsumer]], and if you want to start a new stream if the first
  * one finishes, let the [[KafkaConsumer]] shutdown and create a new one.
  */
sealed abstract class KafkaConsumer[F[_], K, V]
    extends KafkaConsume[F, K, V]
    with KafkaAssignment[F]
    with KafkaOffsets[F]
    with KafkaSubscription[F]
    with KafkaTopics[F]
    with KafkaCommit[F]
    with KafkaMetrics[F]
    with KafkaConsumerLifecycle[F]

case class FakeFiber[F[_], A](join: F[A], cancel: F[Unit])

object FakeFiber {
  implicit def fiberApplicative[F[_]](implicit F: Concurrent[F]): Applicative[FakeFiber[F, *]] =
    new Applicative[FakeFiber[F, *]] {
      final override def pure[A](x: A): FakeFiber[F, A] =
        FakeFiber(F.pure(x), F.unit)
      final override def ap[A, B](ff: FakeFiber[F, A => B])(fa: FakeFiber[F, A]): FakeFiber[F, B] =
        map2(ff, fa)(_(_))
      final override def map2[A, B, Z](fa: FakeFiber[F, A], fb: FakeFiber[F, B])(
        f: (A, B) => Z
      ): FakeFiber[F, Z] = {
        val fa2 = F.guaranteeCase(fa.join) {
          case Outcome.Errored(_) => fb.cancel; case _ => F.unit
        }
        val fb2 = F.guaranteeCase(fb.join) {
          case Outcome.Errored(_) => fa.cancel; case _ => F.unit
        }
        FakeFiber(
          F.racePair(fa2, fb2).flatMap {
            case Left((a, fiberB))  => (a.embedNever, fiberB.joinWithNever).mapN(f)
            case Right((fiberA, b)) => (fiberA.joinWithNever, b.embedNever).mapN(f)
          },
          F.map2(fa.cancel, fb.cancel)((_, _) => ())
        )
      }
      final override def product[A, B](
        fa: FakeFiber[F, A],
        fb: FakeFiber[F, B]
      ): FakeFiber[F, (A, B)] =
        map2(fa, fb)((_, _))
      final override def map[A, B](fa: FakeFiber[F, A])(f: A => B): FakeFiber[F, B] =
        FakeFiber(F.map(fa.join)(f), fa.cancel)
      final override val unit: FakeFiber[F, Unit] =
        FakeFiber(F.unit, F.unit)
    }

  implicit def fiberMonoid[F[_]: Concurrent, M[_], A: Monoid]: Monoid[FakeFiber[F, A]] =
    Applicative.monoid[FakeFiber[F, *], A]
}

object KafkaConsumer {
  private def spawnRepeating[F[_]: Concurrent, A](fa: F[A]): Resource[F, FakeFiber[F, Unit]] =
    Resource.make {
      Deferred[F, Either[Throwable, Unit]].flatMap { deferred =>
        fa.foreverM[Unit]
          .guaranteeCase { outcome: Outcome[F, Throwable, Unit] =>
            outcome match {
              case Outcome.Errored(e) => deferred.complete(Left(e)).void
              case _                  => deferred.complete(Right(())).void
            }
          }
          .start
          .map(fiber => FakeFiber(deferred.get.rethrow, fiber.cancel.start.void))
      }
    }(_.cancel)

  private def startConsumerActor[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    polls: Queue[F, Request[F, K, V]],
    actor: KafkaConsumerActor[F, K, V]
  )(
    implicit F: Async[F]
  ): Resource[F, FakeFiber[F, Unit]] =
    spawnRepeating {
      OptionT(requests.tryTake)
        .getOrElseF(polls.take)
        .flatMap(actor.handle(_))
    }

  private def startPollScheduler[F[_], K, V](
    polls: Queue[F, Request[F, K, V]],
    pollInterval: FiniteDuration
  )(
    implicit F: Temporal[F]
  ): Resource[F, FakeFiber[F, Unit]] =
    spawnRepeating {
      polls.offer(Request.poll) >> F.sleep(pollInterval)
    }

  private def createKafkaConsumer[F[_], K, V](
    requests: Queue[F, Request[F, K, V]],
    settings: ConsumerSettings[F, K, V],
    actor: FakeFiber[F, Unit],
    polls: FakeFiber[F, Unit],
    streamIdRef: Ref[F, StreamId],
    id: Int,
    withConsumer: WithConsumer[F],
    stopConsumingDeferred: Deferred[F, Unit]
  )(implicit F: Async[F]): KafkaConsumer[F, K, V] =
    new KafkaConsumer[F, K, V] {

      private val fiber: FakeFiber[F, Unit] = {
        val actorFiber =
          FakeFiber[F, Unit](actor.join.guaranteeCase {
            (_: Outcome[F, Throwable, Unit]) match {
              case Outcome.Succeeded(_) => polls.cancel
              case _                    => F.unit
            }
          }, actor.cancel)

        val pollsFiber =
          FakeFiber[F, Unit](polls.join.guaranteeCase {
            (_: Outcome[F, Throwable, Unit]) match {
              case Outcome.Succeeded(_) => actor.cancel
              case _                    => F.unit
            }
          }, polls.cancel)

        actorFiber combine pollsFiber
      }

      override def partitionsMapStream
        : Stream[F, Map[TopicPartition, Stream[F, CommittableConsumerRecord[F, K, V]]]] = {
        val chunkQueue: F[Queue[F, Option[Chunk[CommittableConsumerRecord[F, K, V]]]]] =
          Queue.bounded(settings.maxPrefetchBatches - 1)

        type PartitionRequest =
          (Chunk[CommittableConsumerRecord[F, K, V]], FetchCompletedReason)

        type PartitionsMap = Map[TopicPartition, Stream[F, CommittableConsumerRecord[F, K, V]]]
        type PartitionsMapQueue = Queue[F, Option[PartitionsMap]]

        def createPartitionStream(
          streamId: StreamId,
          partitionStreamId: PartitionStreamId,
          partition: TopicPartition
        ): F[Stream[F, CommittableConsumerRecord[F, K, V]]] = {

          for {
            chunks <- chunkQueue
            dequeueDone <- Deferred[F, Unit]
            shutdown = F
              .race(
                F.race(
                  awaitTermination.attempt,
                  dequeueDone.get
                ),
                stopConsumingDeferred.get
              )
              .void
            stopReqs <- Deferred[F, Unit]
          } yield Stream.eval {
            def fetchPartition(deferred: Deferred[F, PartitionRequest]): F[Unit] = {
              val request = Request.Fetch(
                partition,
                streamId,
                partitionStreamId,
                deferred.complete(_: PartitionRequest).void
              )
              val fetch = requests.offer(request) >> deferred.get
              F.race(shutdown, fetch).flatMap {
                case Left(()) =>
                  stopReqs.complete(()).void

                case Right((chunk, reason)) =>
                  val enqueueChunk = chunks.offer(Some(chunk)).whenA(chunk.nonEmpty)

                  val completeRevoked: F[Unit] =
                    stopReqs.complete(()).void.whenA(reason.topicPartitionRevoked)

                  enqueueChunk >> completeRevoked
              }
            }

            Stream
              .repeatEval {
                stopReqs.tryGet.flatMap {
                  case None =>
                    Deferred[F, PartitionRequest] >>= fetchPartition

                  case Some(()) =>
                    // Prevent issuing additional requests after partition is
                    // revoked or shutdown happens, in case the stream isn't
                    // interrupted fast enough
                    F.unit
                }
              }
              .interruptWhen(F.race(shutdown, stopReqs.get).void.attempt)
              .compile
              .drain
              .guarantee(F.race(dequeueDone.get, chunks.offer(None)).void)
              .start
              .as {
                Stream
                  .repeatEval(chunks.take)
                  .unNoneTerminate
                  .flatMap(Stream.chunk)
                  .covary[F]
                  .onFinalize(dequeueDone.complete(()).void)
              }
          }.flatten
        }

        def enqueueAssignment(
          streamId: StreamId,
          partitionStreamIdRef: Ref[F, PartitionStreamId],
          assigned: SortedSet[TopicPartition],
          partitionsMapQueue: PartitionsMapQueue
        ): F[Unit] = {
          val assignment: F[PartitionsMap] = if (assigned.isEmpty) {
            F.pure(Map.empty)
          } else {
            val indexedAssigned = assigned.toVector.zipWithIndex
            partitionStreamIdRef
              .modify { id =>
                val result = indexedAssigned.map {
                  case (partition, idx) =>
                    (partition, idx + id)
                }
                val (_, lastId) = result.last
                (lastId + 1, result)
              }
              .flatMap { (partitions: Vector[(TopicPartition, PartitionStreamId)]) =>
                partitions
                  .traverse {
                    case (partition, partitionStreamId) =>
                      createPartitionStream(streamId, partitionStreamId, partition).map { stream =>
                        partition -> stream
                      }
                  }
                  .map(_.toMap)
              }
          }

          assignment.flatMap { assignment =>
            stopConsumingDeferred.tryGet.flatMap {
              case None =>
                partitionsMapQueue.offer(Some(assignment))
              case Some(()) =>
                F.unit
            }
          }
        }

        def onRebalance(
          streamId: StreamId,
          partitionStreamIdRef: Ref[F, PartitionStreamId],
          partitionsMapQueue: PartitionsMapQueue
        ): OnRebalance[F, K, V] = OnRebalance(
          onAssigned = assigned =>
            enqueueAssignment(streamId, partitionStreamIdRef, assigned, partitionsMapQueue),
          onRevoked = _ => F.unit
        )

        def requestAssignment(
          streamId: StreamId,
          partitionStreamIdRef: Ref[F, PartitionStreamId],
          partitionsMapQueue: PartitionsMapQueue
        ): F[SortedSet[TopicPartition]] = {
          Deferred[F, Either[Throwable, SortedSet[TopicPartition]]].flatMap { deferred =>
            val request =
              Request.Assignment[F, K, V](
                deferred.complete(_).void,
                Some(onRebalance(streamId, partitionStreamIdRef, partitionsMapQueue))
              )
            val assignment = requests.offer(request) >> deferred.get.rethrow
            F.race(awaitTermination.attempt, assignment).map {
              case Left(_)         => SortedSet.empty[TopicPartition]
              case Right(assigned) => assigned
            }
          }
        }

        def initialEnqueue(
          streamId: StreamId,
          partitionsMapQueue: PartitionsMapQueue,
          partitionStreamIdRef: Ref[F, PartitionStreamId]
        ): F[Unit] =
          requestAssignment(streamId, partitionStreamIdRef, partitionsMapQueue).flatMap {
            assigned =>
              enqueueAssignment(streamId, partitionStreamIdRef, assigned, partitionsMapQueue)
          }

        Stream.eval(stopConsumingDeferred.tryGet).flatMap {
          case None =>
            for {
              partitionsMapQueue <- Stream.eval(Queue.unbounded[F, Option[PartitionsMap]])
              streamId <- Stream.eval(streamIdRef.modify(n => (n + 1, n)))
              partitionStreamIdRef <- Stream.eval(Ref.of[F, PartitionStreamId](0))
              _ <- Stream.eval(initialEnqueue(streamId, partitionsMapQueue, partitionStreamIdRef))
              out <- Stream
                .repeatEval(partitionsMapQueue.take)
                .unNoneTerminate
                .interruptWhen(awaitTermination.attempt)
                .concurrently(
                  Stream.eval(stopConsumingDeferred.get >> partitionsMapQueue.offer(None))
                )
            } yield out

          case Some(()) =>
            Stream.empty.covaryAll[F, PartitionsMap]
        }
      }

      override def partitionedStream: Stream[F, Stream[F, CommittableConsumerRecord[F, K, V]]] = {
        partitionsMapStream.flatMap { partitionsMap =>
          Stream.emits(partitionsMap.toVector.map {
            case (_, partitionStream) =>
              partitionStream
          })
        }
      }

      override def stream: Stream[F, CommittableConsumerRecord[F, K, V]] =
        partitionedStream.parJoinUnbounded

      override def commitAsync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] = {
        request { callback =>
          Request.ManualCommitAsync(
            callback = callback,
            offsets = offsets
          )
        }
      }

      override def commitSync(offsets: Map[TopicPartition, OffsetAndMetadata]): F[Unit] = {
        request { callback =>
          Request.ManualCommitSync(
            callback = callback,
            offsets = offsets
          )
        }
      }

      private[this] def request[A](
        request: (Either[Throwable, A] => F[Unit]) => Request[F, K, V]
      ): F[A] =
        Deferred[F, Either[Throwable, A]].flatMap { deferred =>
          requests.offer(request(deferred.complete(_).void)) >>
            F.race(awaitTermination.as(ConsumerShutdownException()), deferred.get.rethrow)
        }.rethrow

      override def assignment: F[SortedSet[TopicPartition]] =
        assignment(Option.empty)

      private def assignment(
        onRebalance: Option[OnRebalance[F, K, V]]
      ): F[SortedSet[TopicPartition]] =
        request { callback =>
          Request.Assignment(
            callback = callback,
            onRebalance = onRebalance
          )
        }

      override def assignmentStream: Stream[F, SortedSet[TopicPartition]] = {
        // NOTE: `initialAssignmentDone` is needed here to guard against the
        // race condition when a rebalance triggers after the listeners are
        // registered but before `assignmentRef` can be updated with initial
        // assignments.
        def onRebalanceWith(
          updateQueue: Queue[F, SortedSet[TopicPartition]],
          assignmentRef: Ref[F, SortedSet[TopicPartition]],
          initialAssignmentDone: F[Unit]
        ): OnRebalance[F, K, V] =
          OnRebalance(
            onAssigned = assigned =>
              initialAssignmentDone >>
                assignmentRef
                  .updateAndGet(_ ++ assigned)
                  .flatMap(updateQueue.offer),
            onRevoked = revoked =>
              initialAssignmentDone >>
                assignmentRef
                  .updateAndGet(_ -- revoked)
                  .flatMap(updateQueue.offer)
          )

        Stream.eval {
          (
            Queue.unbounded[F, SortedSet[TopicPartition]],
            Ref[F].of(SortedSet.empty[TopicPartition]),
            Deferred[F, Unit]
          ).tupled.flatMap[Stream[F, SortedSet[TopicPartition]]] {
            case (updateQueue, assignmentRef, initialAssignmentDeferred) =>
              val onRebalance =
                onRebalanceWith(
                  updateQueue = updateQueue,
                  assignmentRef = assignmentRef,
                  initialAssignmentDone = initialAssignmentDeferred.get
                )

              assignment(Some(onRebalance))
                .flatMap { initialAssignment =>
                  assignmentRef.set(initialAssignment) >>
                    updateQueue.offer(initialAssignment) >>
                    initialAssignmentDeferred.complete(())
                }
                .as(Stream.repeatEval(updateQueue.take).changes)
          }
        }.flatten
      }

      override def seek(partition: TopicPartition, offset: Long): F[Unit] =
        withConsumer.blocking { _.seek(partition, offset) }

      override def seekToBeginning: F[Unit] =
        seekToBeginning(List.empty[TopicPartition])

      override def seekToBeginning[G[_]](partitions: G[TopicPartition])(
        implicit G: Foldable[G]
      ): F[Unit] =
        withConsumer.blocking { _.seekToBeginning(partitions.asJava) }

      override def seekToEnd: F[Unit] =
        seekToEnd(List.empty[TopicPartition])

      override def seekToEnd[G[_]](
        partitions: G[TopicPartition]
      )(implicit G: Foldable[G]): F[Unit] =
        withConsumer.blocking { _.seekToEnd(partitions.asJava) }

      override def partitionsFor(
        topic: String
      ): F[List[PartitionInfo]] =
        withConsumer.blocking { _.partitionsFor(topic).asScala.toList }

      override def partitionsFor(
        topic: String,
        timeout: FiniteDuration
      ): F[List[PartitionInfo]] =
        withConsumer.blocking { _.partitionsFor(topic, timeout.asJava).asScala.toList }

      override def position(partition: TopicPartition): F[Long] =
        withConsumer.blocking { _.position(partition) }

      override def position(partition: TopicPartition, timeout: FiniteDuration): F[Long] =
        withConsumer.blocking { _.position(partition, timeout.asJava) }

      override def subscribeTo(firstTopic: String, remainingTopics: String*): F[Unit] =
        subscribe(NonEmptyList.of(firstTopic, remainingTopics: _*))

      override def subscribe[G[_]](topics: G[String])(implicit G: Reducible[G]): F[Unit] =
        request { callback =>
          Request.SubscribeTopics(
            topics = topics.toNonEmptyList,
            callback = callback
          )
        }

      override def subscribe(regex: Regex): F[Unit] =
        request { callback =>
          Request.SubscribePattern(
            pattern = regex.pattern,
            callback = callback
          )
        }

      override def unsubscribe: F[Unit] =
        request { callback =>
          Request.Unsubscribe(
            callback = callback
          )
        }

      override def stopConsuming: F[Unit] =
        stopConsumingDeferred.complete(()).attempt.void

      override def assign(partitions: NonEmptySet[TopicPartition]): F[Unit] =
        request { callback =>
          Request.Assign(
            topicPartitions = partitions,
            callback = callback
          )
        }

      override def assign(topic: String, partitions: NonEmptySet[Int]): F[Unit] =
        assign(partitions.map(new TopicPartition(topic, _)))

      override def assign(topic: String): F[Unit] = {
        for {
          partitions <- partitionsFor(topic)
            .map { partitionInfo =>
              NonEmptySet.fromSet {
                SortedSet(partitionInfo.map(_.partition): _*)
              }
            }
          _ <- partitions.fold(F.unit)(assign(topic, _))
        } yield ()
      }

      override def beginningOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.beginningOffsets(partitions.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def beginningOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.beginningOffsets(partitions.asJava, timeout.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def endOffsets(
        partitions: Set[TopicPartition]
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.endOffsets(partitions.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def endOffsets(
        partitions: Set[TopicPartition],
        timeout: FiniteDuration
      ): F[Map[TopicPartition, Long]] =
        withConsumer.blocking {
          _.endOffsets(partitions.asJava, timeout.asJava)
            .asInstanceOf[util.Map[TopicPartition, Long]]
            .toMap
        }

      override def metrics: F[Map[MetricName, Metric]] =
        withConsumer.blocking { _.metrics().asScala.toMap }

      override def toString: String =
        "KafkaConsumer$" + id

      override def terminate: F[Unit] = fiber.cancel

      override def awaitTermination: F[Unit] = fiber.join
    }

  /**
    * Creates a new [[KafkaConsumer]] in the `Resource` context,
    * using the specified [[ConsumerSettings]]. Note that there
    * is another version where `F[_]` is specified explicitly and
    * the key and value type can be inferred, which allows you
    * to use the following syntax.
    *
    * {{{
    * KafkaConsumer.resource[F].using(settings)
    * }}}
    */
  def resource[F[_], K, V](
    settings: ConsumerSettings[F, K, V]
  )(
    implicit F: Async[F]
  ): Resource[F, KafkaConsumer[F, K, V]] =
    for {
      keyDeserializer <- Resource.eval(settings.keyDeserializer)
      valueDeserializer <- Resource.eval(settings.valueDeserializer)
      id <- Resource.eval(F.delay(new Object().hashCode))
      jitter <- Resource.eval(Jitter.default[F])
      logging <- Resource.eval(Logging.default[F](id))
      requests <- Resource.eval(Queue.unbounded[F, Request[F, K, V]])
      polls <- Resource.eval(Queue.bounded[F, Request[F, K, V]](1))
      ref <- Resource.eval(Ref.of[F, State[F, K, V]](State.empty))
      streamId <- Resource.eval(Ref.of[F, StreamId](0))
      dispatcher <- Dispatcher[F]
      stopConsumingDeferred <- Resource.eval(Deferred[F, Unit])
      withConsumer <- WithConsumer(settings)
      actor = {
        implicit val jitter0: Jitter[F] = jitter
        implicit val logging0: Logging[F] = logging
        implicit val dispatcher0: Dispatcher[F] = dispatcher

        new KafkaConsumerActor(
          settings = settings,
          keyDeserializer = keyDeserializer,
          valueDeserializer = valueDeserializer,
          ref = ref,
          requests = requests,
          withConsumer = withConsumer
        )
      }
      actor <- startConsumerActor(requests, polls, actor)
      polls <- startPollScheduler(polls, settings.pollInterval)
    } yield createKafkaConsumer(
      requests,
      settings,
      actor,
      polls,
      streamId,
      id,
      withConsumer,
      stopConsumingDeferred
    )

  /**
    * Alternative version of `resource` where the `F[_]` is
    * specified explicitly, and where the key and value type can
    * be inferred from the [[ConsumerSettings]]. This allows you
    * to use the following syntax.
    *
    * {{{
    * KafkaConsumer.resource[F].using(settings)
    * }}}
    */
  def resource[F[_]](implicit F: Async[F]): ConsumerResource[F] = new ConsumerResource(F)

  /**
    * Creates a new [[KafkaConsumer]] in the `Stream` context,
    * using the specified [[ConsumerSettings]]. Note that there
    * is another version where `F[_]` is specified explicitly and
    * the key and value type can be inferred, which allows you
    * to use the following syntax.
    *
    * {{{
    * KafkaConsumer.stream[F].using(settings)
    * }}}
    */
  def stream[F[_], K, V](settings: ConsumerSettings[F, K, V])(
    implicit F: Async[F]
  ): Stream[F, KafkaConsumer[F, K, V]] =
    Stream.resource(resource(settings))

  /**
    * Alternative version of `stream` where the `F[_]` is
    * specified explicitly, and where the key and value type can
    * be inferred from the [[ConsumerSettings]]. This allows you
    * to use the following syntax.
    *
    * {{{
    * KafkaConsumer.stream[F].using(settings)
    * }}}
    */
  def stream[F[_]](implicit F: Async[F]): ConsumerStream[F] =
    new ConsumerStream[F](F)
}
