package com.twitter.finagle.zipkin.thrift

import com.twitter.conversions.time._
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.thrift.{ThriftClientFramedCodec, thrift}
import com.twitter.finagle.tracing._
import com.twitter.finagle.util.DefaultTimer
import com.twitter.finagle.{Service, SimpleFilter, tracing}
import com.twitter.util.{Await, Base64StringEncoder, Future}
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import org.apache.thrift.protocol.TBinaryProtocol
import org.apache.thrift.transport.TIOStreamTransport
import scala.collection.mutable.{ArrayBuffer, HashMap, SynchronizedMap}

object RawZipkinTracer {
  // to make sure we only create one instance of the tracer per host and port
  private[this] val map =
    new HashMap[String, RawZipkinTracer] with SynchronizedMap[String, RawZipkinTracer]

  /**
   * @param scribeHost Host to send trace data to
   * @param scribePort Port to send trace data to
   * @param statsReceiver Where to log information about tracing success/failures
   */
  def apply(scribeHost: String = "localhost",
            scribePort: Int = 1463,
            statsReceiver: StatsReceiver = NullStatsReceiver
  ): Tracer = synchronized {
    val tracer = map.getOrElseUpdate(scribeHost + ":" + scribePort, {
      new RawZipkinTracer(
        scribeHost,
        scribePort,
        statsReceiver.scope("zipkin")
      )
    })
    tracer
  }

  // Try to flush the tracers when we shut
  // down. We give it 100ms.
  Runtime.getRuntime().addShutdownHook(new Thread {
    override def run() {
      val tracers = RawZipkinTracer.synchronized(map.values.toSeq)
      val joined = Future.join(tracers map(_.flush()))
      try {
        Await.result(joined, 100.milliseconds)
      } catch {
        case _: TimeoutException =>
          System.err.println("Failed to flush all traces before quitting")
      }
    }
  })
}

/**
 * Receives the Finagle generated traces and sends them off to Zipkin via scribe.
 * @param scribeHost The scribe host used to send traces to scribe
 * @param scribePort The scribe port used to send traces to scribe
 * @param statsReceiver We generate stats to keep track of traces sent, failures and so on
 */
private[thrift] class RawZipkinTracer(
  scribeHost: String,
  scribePort: Int,
  statsReceiver: StatsReceiver
) extends Tracer
{
  private[this] val protocolFactory = new TBinaryProtocol.Factory()
  private[this] val TraceCategory = "zipkin" // scribe category

  // this sends off spans after the deadline is hit, no matter if it ended naturally or not.
  private[this] val spanMap: DeadlineSpanMap =
    new DeadlineSpanMap(this, 120.seconds, statsReceiver, DefaultTimer.twitter)

  protected[thrift] val client = {
    val transport = ClientBuilder()
      .hosts(new InetSocketAddress(scribeHost, scribePort))
      .codec(ThriftClientFramedCodec())
      .hostConnectionLimit(5)
      .daemon(true)
      .build()

    new scribe.FinagledClient(
      new TracelessFilter andThen transport,
      new TBinaryProtocol.Factory())
  }

  protected[thrift] def flush() = spanMap.flush()

  /**
   * Always sample the request.
   */
  def sampleTrace(traceId: TraceId): Option[Boolean] = Some(true)

  /**
   * Serialize the spans, base64 encode and shove it all in a list.
   */
  private def createLogEntries(span: Span): ArrayBuffer[LogEntry] = {
    var msgs = new ArrayBuffer[LogEntry]()

    try {
      val s = span.toThrift
      val baos = new ByteArrayOutputStream
      s.write(protocolFactory.getProtocol(new TIOStreamTransport(baos)))
      val serializedBase64Span = Base64StringEncoder.encode(baos.toByteArray)
      msgs = msgs :+ new LogEntry(category = TraceCategory, message = serializedBase64Span)
    } catch {
      case e => statsReceiver.scope("create_log_entries").scope("error").
        counter("%s".format(e.toString)).incr()
    }

    msgs
  }

  /**
   * Log the span data via Scribe.
   */
  def logSpan(span: Span): Future[Unit] =
    client.log(createLogEntries(span)) onSuccess {
      case ResultCode.Ok => statsReceiver.scope("log_span").counter("ok").incr()
      case ResultCode.TryLater => statsReceiver.scope("log_span").counter("try_later").incr()
      case _ => () /* ignore */
    } onFailure {
      case e => statsReceiver.scope("log_span").scope("error").counter("%s".format(e.toString)).incr()
    } map(_ => ())

  /**
   * Mutate the Span with whatever new info we have.
   * If we see an "end" annotation we remove the span and send it off.
   */
  protected def mutate(traceId: TraceId)(f: Span => Span) {
    val span = spanMap.update(traceId)(f)

    // if either two "end annotations" exists we send off the span
    if (span.annotations.exists { a =>
      a.value.equals(thrift.Constants.CLIENT_RECV) ||
      a.value.equals(thrift.Constants.SERVER_SEND) ||
      a.value.equals(TimeoutFilter.TimeoutAnnotation)
    }) {
      spanMap.remove(traceId)
      logSpan(span)
    }
  }

  def record(record: Record) {
    record.annotation match {
      case tracing.Annotation.ClientSend()   =>
        annotate(record, thrift.Constants.CLIENT_SEND)
      case tracing.Annotation.ClientRecv()   =>
        annotate(record, thrift.Constants.CLIENT_RECV)
      case tracing.Annotation.ServerSend()   =>
        annotate(record, thrift.Constants.SERVER_SEND)
      case tracing.Annotation.ServerRecv()   =>
        annotate(record, thrift.Constants.SERVER_RECV)
      case tracing.Annotation.Message(value) =>
        annotate(record, value)
      case tracing.Annotation.Rpcname(service: String, rpc: String) =>
        mutate(record.traceId) { span =>
          span.copy(_name = Some(rpc), _serviceName = Some(service))
        }
      case tracing.Annotation.BinaryAnnotation(key: String, value: Boolean) =>
        binaryAnnotation(record, key, ByteBuffer.wrap(Array[Byte](if (value) 1 else 0)), thrift.AnnotationType.BOOL)
      case tracing.Annotation.BinaryAnnotation(key: String, value: Array[Byte]) =>
        binaryAnnotation(record, key, ByteBuffer.wrap(value), thrift.AnnotationType.BYTES)
      case tracing.Annotation.BinaryAnnotation(key: String, value: ByteBuffer) =>
        binaryAnnotation(record, key, value, thrift.AnnotationType.BYTES)
      case tracing.Annotation.BinaryAnnotation(key: String, value: Short) =>
        binaryAnnotation(record, key, ByteBuffer.allocate(2).putShort(0, value), thrift.AnnotationType.I16)
      case tracing.Annotation.BinaryAnnotation(key: String, value: Int) =>
        binaryAnnotation(record, key, ByteBuffer.allocate(4).putInt(0, value), thrift.AnnotationType.I32)
      case tracing.Annotation.BinaryAnnotation(key: String, value: Long) =>
        binaryAnnotation(record, key, ByteBuffer.allocate(8).putLong(0, value), thrift.AnnotationType.I64)
      case tracing.Annotation.BinaryAnnotation(key: String, value: Double) =>
        binaryAnnotation(record, key, ByteBuffer.allocate(8).putDouble(0, value), thrift.AnnotationType.DOUBLE)
      case tracing.Annotation.BinaryAnnotation(key: String, value: String) =>
        binaryAnnotation(record, key, ByteBuffer.wrap(value.getBytes), thrift.AnnotationType.STRING)
      case tracing.Annotation.BinaryAnnotation(key: String, value) => // Throw error?
      case tracing.Annotation.ClientAddr(ia: InetSocketAddress) =>
        setEndpoint(record, ia)
      case tracing.Annotation.ServerAddr(ia: InetSocketAddress) =>
        setEndpoint(record, ia)
    }
  }

  /**
   * Sets the endpoint in the span for any future annotations. Also
   * sets the endpoint in any previous annotations.
   */
  protected def setEndpoint(record: Record, ia: InetSocketAddress) {
    mutate(record.traceId) { span =>
      val endpoint = Endpoint.fromSocketAddress(ia).boundEndpoint
      span.copy(_endpoint = Some(endpoint),
        annotations = span.annotations map { a => ZipkinAnnotation(a.timestamp, a.value, endpoint, a.duration)})
    }
  }

  protected def binaryAnnotation(
    record: Record,
    key: String,
    value: ByteBuffer,
    annotationType: thrift.AnnotationType
  ) = {
    mutate(record.traceId) { span =>
      span.copy(bAnnotations = span.bAnnotations ++ Seq(
        BinaryAnnotation(key, value, annotationType, span.endpoint)))
    }
  }

  /**
   * Add this record as a time based annotation.
   */
  protected def annotate(record: Record, value: String) = {
    mutate(record.traceId) { span =>
      span.copy(annotations =
        ZipkinAnnotation(record.timestamp, value, span.endpoint, record.duration) +: span.annotations)
    }
  }
}

/**
 * Makes sure we don't trace the Scribe logging.
 */
private[thrift] class TracelessFilter[Req, Rep]()
  extends SimpleFilter[Req, Rep]
{
  def apply(request: Req, service: Service[Req, Rep]) = {
    Trace.unwind {
      Trace.clear()
      service(request)
    }
  }
}
