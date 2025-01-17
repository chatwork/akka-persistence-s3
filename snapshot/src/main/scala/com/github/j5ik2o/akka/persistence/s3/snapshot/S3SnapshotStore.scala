package com.github.j5ik2o.akka.persistence.s3.snapshot

import akka.actor.{ ActorSystem, DynamicAccess, ExtendedActorSystem }
import akka.persistence.snapshot.SnapshotStore
import akka.persistence.{ SelectedSnapshot, SnapshotMetadata, SnapshotSelectionCriteria }
import akka.serialization.{ Serialization, SerializationExtension }
import com.github.j5ik2o.akka.persistence.s3.base.config.S3ClientConfig
import com.github.j5ik2o.akka.persistence.s3.base.metrics.{ MetricsReporter, MetricsReporterProvider }
import com.github.j5ik2o.akka.persistence.s3.base.model.{ Context, PersistenceId, SequenceNumber }
import com.github.j5ik2o.akka.persistence.s3.base.resolver.PathPrefixResolver
import com.github.j5ik2o.akka.persistence.s3.base.trace.{ TraceReporter, TraceReporterProvider }
import com.github.j5ik2o.akka.persistence.s3.base.utils.{ HttpClientBuilderUtils, S3ClientBuilderUtils }
import com.github.j5ik2o.akka.persistence.s3.config.SnapshotPluginConfig
import com.github.j5ik2o.akka.persistence.s3.resolver.{ SnapshotBucketNameResolver, SnapshotMetadataKeyConverter }
import com.github.j5ik2o.akka.persistence.s3.serialization.ByteArraySnapshotSerializer
import com.typesafe.config.Config
import software.amazon.awssdk.core.async.{ AsyncRequestBody, AsyncResponseTransformer }
import software.amazon.awssdk.services.s3.model._

import java.util.UUID
import scala.collection.immutable
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }

class S3SnapshotStore(config: Config) extends SnapshotStore {
  implicit val system: ActorSystem = context.system

  private val pluginConfig: SnapshotPluginConfig  = SnapshotPluginConfig.fromConfig(config)
  private val bucketNameResolverClassName: String = pluginConfig.bucketNameResolverClassName
  private val keyConverterClassName: String       = pluginConfig.keyConverterClassName
  private val pathPrefixResolverClassName: String = pluginConfig.pathPrefixResolverClassName
  private val extensionName: String               = pluginConfig.extensionName
  private val maxLoadAttempts: Int                = pluginConfig.maxLoadAttempts
  private val s3ClientConfig: S3ClientConfig      = pluginConfig.clientConfig

  private val extendedSystem: ExtendedActorSystem = system.asInstanceOf[ExtendedActorSystem]
  private val dynamicAccess: DynamicAccess        = extendedSystem.dynamicAccess

  private val httpClientBuilder = HttpClientBuilderUtils.setup(s3ClientConfig)
  private val javaS3ClientBuilder =
    S3ClientBuilderUtils.setup(dynamicAccess, pluginConfig, httpClientBuilder.build())
  private val s3AsyncClient = javaS3ClientBuilder.build()

  protected val metricsReporter: Option[MetricsReporter] = {
    val metricsReporterProvider = MetricsReporterProvider.create(dynamicAccess, pluginConfig)
    metricsReporterProvider.create
  }

  protected val traceReporter: Option[TraceReporter] = {
    val traceReporterProvider = TraceReporterProvider.create(dynamicAccess, pluginConfig)
    traceReporterProvider.create
  }

  protected val bucketNameResolver: SnapshotBucketNameResolver = {
    dynamicAccess
      .createInstanceFor[SnapshotBucketNameResolver](
        bucketNameResolverClassName,
        immutable.Seq(classOf[Config] -> config)
      )
      .getOrElse(throw new ClassNotFoundException(bucketNameResolverClassName))
  }

  protected val keyConverter: SnapshotMetadataKeyConverter = {
    dynamicAccess
      .createInstanceFor[SnapshotMetadataKeyConverter](keyConverterClassName, immutable.Seq(classOf[Config] -> config))
      .getOrElse(throw new ClassNotFoundException(keyConverterClassName))
  }

  protected val pathPrefixResolver: PathPrefixResolver = {
    dynamicAccess
      .createInstanceFor[PathPrefixResolver](pathPrefixResolverClassName, immutable.Seq(classOf[Config] -> config))
      .getOrElse(throw new ClassNotFoundException(pathPrefixResolverClassName))
  }

  private def resolvePathPrefix(
      persistenceId: PersistenceId
  ): Option[String] = {
    pluginConfig.pathPrefix.orElse(pathPrefixResolver.resolve(persistenceId))
  }

  private def resolveBucketName(persistenceId: PersistenceId) = {
    pluginConfig.bucketName
      .map(_.stripPrefix("/"))
      .getOrElse(bucketNameResolver.resolve(persistenceId))
  }

  private def convertToKey(snapshotMetadata: SnapshotMetadata) = {
    keyConverter.convertTo(snapshotMetadata, extensionName)
  }

  private def convertToSnapshotMetadata(s: S3Object) = {
    keyConverter.convertFrom(s.key(), extensionName)
  }

  private val serialization: Serialization = SerializationExtension(system)

  private val serializer = new ByteArraySnapshotSerializer(serialization, metricsReporter, traceReporter)

  override def loadAsync(
      persistenceId: String,
      criteria: SnapshotSelectionCriteria
  ): Future[Option[SelectedSnapshot]] = {
    implicit val ec: ExecutionContext = system.dispatcher

    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeSnapshotStoreLoadAsync(context))
    def future = snapshotMetadatas(persistenceId, criteria)
      .map(_.sorted.takeRight(maxLoadAttempts))
      .flatMap(load)

    val traced = traceReporter.fold(future)(_.traceSnapshotStoreLoadAsync(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterSnapshotStoreLoadAsync(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorSnapshotStoreLoadAsync(newContext, ex))
    }
    traced
  }

  override def saveAsync(snapshotMetadata: SnapshotMetadata, snapshot: Any): Future[Unit] = {
    implicit val ec: ExecutionContext = system.dispatcher
    val pid                           = PersistenceId(snapshotMetadata.persistenceId)
    val context                       = Context.newContext(UUID.randomUUID(), pid)
    val newContext                    = metricsReporter.fold(context)(_.beforeSnapshotStoreSaveAsync(context))

    def future = for {
      serialized <- serializer.serialize(snapshotMetadata, snapshot)
      putObjectRequest = PutObjectRequest
        .builder()
        .contentLength(serialized.length.toLong)
        .bucket(resolveBucketName(pid))
        .key(convertToKey(snapshotMetadata))
        .build()
      result <- s3AsyncClient
        .putObject(putObjectRequest, AsyncRequestBody.fromBytes(serialized.snapshot))
        .toScala
        .flatMap { response =>
          val sdkHttpResponse = response.sdkHttpResponse
          if (response.sdkHttpResponse().isSuccessful)
            Future.successful(())
          else
            Future.failed(
              new S3SnapshotException(
                s"Failed to PutObjectRequest: statusCode = ${sdkHttpResponse.statusCode()}"
              )
            )
        }
    } yield result

    val traced = traceReporter.fold(future)(_.traceSnapshotStoreSaveAsync(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterSnapshotStoreSaveAsync(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorSnapshotStoreSaveAsync(newContext, ex))
    }

    traced
  }

  override def deleteAsync(snapshotMetadata: SnapshotMetadata): Future[Unit] = {
    implicit val ec: ExecutionContext = system.dispatcher

    if (snapshotMetadata.timestamp == 0L)
      deleteAsync(
        snapshotMetadata.persistenceId,
        SnapshotSelectionCriteria(
          snapshotMetadata.sequenceNr,
          Long.MaxValue,
          snapshotMetadata.sequenceNr,
          Long.MinValue
        )
      )
    else {
      val pid        = PersistenceId(snapshotMetadata.persistenceId)
      val context    = Context.newContext(UUID.randomUUID(), pid)
      val newContext = metricsReporter.fold(context)(_.beforeSnapshotStoreDeleteAsync(context))
      val request = DeleteObjectRequest
        .builder()
        .bucket(resolveBucketName(pid))
        .key(convertToKey(snapshotMetadata))
        .build()

      def future = s3AsyncClient.deleteObject(request).toScala.flatMap { response =>
        val sdkHttpResponse = response.sdkHttpResponse
        if (response.sdkHttpResponse().isSuccessful)
          Future.successful(())
        else
          Future.failed(
            new S3SnapshotException(
              s"Failed to DeleteObjectRequest: statusCode = ${sdkHttpResponse.statusCode()}"
            )
          )
      }

      val traced = traceReporter.fold(future)(_.traceSnapshotStoreDeleteAsync(context)(future))

      traced.onComplete {
        case Success(_) =>
          metricsReporter.foreach(_.afterSnapshotStoreDeleteAsync(newContext))
        case Failure(ex) =>
          metricsReporter.foreach(_.errorSnapshotStoreDeleteAsync(newContext, ex))
      }
      traced
    }
  }

  override def deleteAsync(
      persistenceId: String,
      criteria: SnapshotSelectionCriteria
  ): Future[Unit] = {
    implicit val ec: ExecutionContext = system.dispatcher

    val pid        = PersistenceId(persistenceId)
    val context    = Context.newContext(UUID.randomUUID(), pid)
    val newContext = metricsReporter.fold(context)(_.beforeSnapshotStoreDeleteWithCriteriaAsync(context))
    val metadatas  = snapshotMetadatas(persistenceId, criteria)
    def future     = metadatas.flatMap(list => Future.sequence(list.map(deleteAsync))).map(_ => ())

    val traced = traceReporter.fold(future)(_.traceSnapshotStoreDeleteWithCriteriaAsync(context)(future))

    traced.onComplete {
      case Success(_) =>
        metricsReporter.foreach(_.afterSnapshotStoreDeleteWithCriteriaAsync(newContext))
      case Failure(ex) =>
        metricsReporter.foreach(_.errorSnapshotStoreDeleteWithCriteriaAsync(newContext, ex))
    }
    traced
  }

  private def load(
      metadata: immutable.Seq[SnapshotMetadata]
  )(implicit ec: ExecutionContext): Future[Option[SelectedSnapshot]] =
    metadata.lastOption match {
      case None => Future.successful(None)
      case Some(snapshotMetadata) =>
        val request = GetObjectRequest
          .builder()
          .bucket(resolveBucketName(PersistenceId(snapshotMetadata.persistenceId)))
          .key(convertToKey(snapshotMetadata))
          .build()
        s3AsyncClient
          .getObject(request, AsyncResponseTransformer.toBytes[GetObjectResponse])
          .toScala
          .flatMap { responseBytes =>
            if (responseBytes.response().sdkHttpResponse().isSuccessful) {
              serializer
                .deserialize(
                  SnapshotRow(
                    PersistenceId(snapshotMetadata.persistenceId),
                    SequenceNumber(snapshotMetadata.sequenceNr),
                    snapshotMetadata.timestamp,
                    responseBytes.asByteArray()
                  )
                ).map { case (snapshotMetadata, snapshotData) =>
                  Some(SelectedSnapshot(snapshotMetadata, snapshotData))
                }
            } else Future.successful(None)
          }.recoverWith { case NonFatal(e) =>
            log.error(e, s"Error loading snapshot [${snapshotMetadata}]")
            load(metadata.init) // try older snapshot
          }
    }

  private def snapshotMetadatas(
      persistenceId: String,
      criteria: SnapshotSelectionCriteria
  )(implicit ec: ExecutionContext): Future[List[SnapshotMetadata]] = {
    val pid = PersistenceId(persistenceId)
    var builder = ListObjectsRequest
      .builder()
      .bucket(resolveBucketName(pid))
      .delimiter("/")
    builder = resolvePathPrefix(pid).fold(builder)(builder.prefix)
    val request = builder.build()
    s3AsyncClient
      .listObjects(request)
      .toScala
      .flatMap { response =>
        val sdkHttpResponse = response.sdkHttpResponse
        if (sdkHttpResponse.isSuccessful)
          Future.successful(
            response
              .contents()
              .asScala
              .toList
              .map(convertToSnapshotMetadata)
              .filter { snapshotMetadata =>
                snapshotMetadata.sequenceNr >= criteria.minSequenceNr &&
                snapshotMetadata.sequenceNr <= criteria.maxSequenceNr &&
                snapshotMetadata.timestamp >= criteria.minTimestamp &&
                snapshotMetadata.timestamp <= criteria.maxTimestamp
              }
          )
        else
          Future.failed(
            new S3SnapshotException(
              s"Failed to ListObjectsRequest: statusCode = ${sdkHttpResponse.statusCode()}"
            )
          )
      }

  }

}
