package pl.touk.esp.engine.process

import java.lang.Iterable
import java.util.concurrent.TimeUnit

import cats.data.Validated.{Invalid, Valid}
import cats.data._
import cats.std.list._
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.api.common.functions.RichFlatMapFunction
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.collector.selector.OutputSelector
import org.apache.flink.streaming.api.datastream.DataStreamSink
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.scala.{StreamExecutionEnvironment, _}
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.util.serialization.SerializationSchema
import org.apache.flink.util.Collector
import pl.touk.esp.engine.Interpreter.ContextImpl
import pl.touk.esp.engine.api._
import pl.touk.esp.engine.api.process.{InputWithExectutionContext, SinkFactory, Source, SourceFactory}
import pl.touk.esp.engine.compile.{ProcessCompilationError, ProcessCompiler}
import pl.touk.esp.engine.graph.EspProcess
import pl.touk.esp.engine.process.FlinkProcessRegistrar._
import pl.touk.esp.engine.process.util.{SpelHack, SynchronousExecutionContext}
import pl.touk.esp.engine.split.ProcessSplitter
import pl.touk.esp.engine.splittedgraph.part._
import pl.touk.esp.engine.splittedgraph.splittednode.{NextNode, PartRef, SplittedNode}
import pl.touk.esp.engine.splittedgraph.{SplittedProcess, splittednode}
import pl.touk.esp.engine.{Interpreter, InterpreterConfig}

import scala.collection.JavaConverters._
import scala.concurrent.{Future, Await}
import scala.concurrent.duration.Duration
import scala.language.implicitConversions

class FlinkProcessRegistrar(interpreterConfig: () => InterpreterConfig,
                            sourceFactories: Map[String, SourceFactory[_]],
                            sinkFactories: Map[String, SinkFactory],
                            espExceptionHandlerProvider: () => EspExceptionHandler,
                            processTimeout: Duration,
                            compiler: => ProcessCompiler = ProcessCompiler.default) {

  implicit def millisToTime(duration: Long): Time = Time.of(duration, TimeUnit.MILLISECONDS)

  def register(env: StreamExecutionEnvironment, process: EspProcess): Unit = {
    SpelHack.registerHackedSerializers(env)
    val splittedProcess = ProcessSplitter.split(process)
    validateOrFail(compiler.validate(splittedProcess))
    register(env, splittedProcess)
  }

  private def register(env: StreamExecutionEnvironment, process: SplittedProcess): Unit = {
    registerSourcePart(process.source)

    def registerSourcePart(part: SourcePart): Unit = {
      val source = createSource(part)
      val timeExtractionFunction = source.timeExtractionFunction

      timeExtractionFunction.foreach(_ => env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime))

      val newStart = env
        .addSource[Any](source.toFlinkSource)(source.typeInformation)
        //chyba nie ascending????
      val withAssigned = timeExtractionFunction.map(newStart.assignAscendingTimestamps).getOrElse(newStart)
        .flatMap(new InitialInterpretationFunction(compiler, part.source, interpreterConfig, espExceptionHandlerProvider, process.metaData, Interpreter.InputParamName, processTimeout))
        .split(SplitFunction)
      registerParts(withAssigned, part.nextParts)
    }

    def registerSubsequentPart[T](start: DataStream[T],
                                  processPart: SubsequentPart): Unit =
      processPart match {
        case part: AggregateExpressionPart =>
          val newStart = start.asInstanceOf[DataStream[InterpretationResult]]
            .keyBy(new AggregateKeyByFunction(compiler, part.aggregate, interpreterConfig, espExceptionHandlerProvider, processTimeout))
            .timeWindow(part.durationInMillis, part.slideInMillis)
            .fold(List[Any]())((a, b) => b.finalContext[Any](part.aggregatedVar) :: a)
            .map(_.asJava)
          registerSubsequentPart(newStart, part.nextPart)
        case part: AfterAggregationPart =>
          val typedStart = start.asInstanceOf[DataStream[Any]] // List[Any]
          part.next match {
            case NextNode(node) =>
              val newStart = typedStart
                .flatMap(new InitialInterpretationFunction(compiler, node, interpreterConfig, espExceptionHandlerProvider, process.metaData, part.aggregatedVar, processTimeout))
                .split(SplitFunction)
              registerParts(newStart, part.nextParts)
            case PartRef(id) =>
              assert(part.nextParts.size == 1, "Aggregate ended up with part ref should have one next part")
              assert(part.nextParts.head.id == id, "Aggregate ended up with part ref should have one next part with the same id as in ref")
              registerSubsequentPart(typedStart, part.nextParts.head)
          }
        case part: SinkPart =>
          start.asInstanceOf[DataStream[InterpretationResult]]
            .flatMap(new SinkInterpretationFunction(compiler, part.sink, interpreterConfig, espExceptionHandlerProvider, processTimeout))
            .map { (interpretationResult: InterpretationResult) =>
              InputWithExectutionContext(interpretationResult.output, SynchronousExecutionContext.ctx)
            }
            .addSink(createSink(part))
      }

    def registerParts(start: SplitStream[InterpretationResult],
                      nextParts: Seq[SubsequentPart]) = {
      nextParts.foreach { part =>
        registerSubsequentPart(start.select(part.id), part)
      }
      // TODO: register default sink
    }

    def createSource(part: SourcePart): Source[Any] = {
      val sourceType = part.ref.typ
      val sourceFactory = sourceFactories.getOrElse(sourceType, throw new scala.IllegalArgumentException(s"Missing source factory of type: $sourceType"))
      sourceFactory
        .create(process.metaData, part.ref.parameters.map(p => p.name -> p.value).toMap)
        .asInstanceOf[Source[Any]]
    }

    def createSink(part: SinkPart): SinkFunction[InputWithExectutionContext] = {
      val sinkType = part.ref.typ
      val sinkFactory = sinkFactories.getOrElse(sinkType, throw new IllegalArgumentException(s"Missing sink factory of type: $sinkType"))
      sinkFactory
        .create(process.metaData, part.ref.parameters.map(p => p.name -> p.value).toMap)
        .toFlinkSink
    }

  }

}

object FlinkProcessRegistrar {

  private final val DefaultSinkId = "$"

  private def validateOrFail[T](validated: ValidatedNel[ProcessCompilationError, T]): T = validated match {
    case Valid(r) => r
    case Invalid(err) => throw new scala.IllegalArgumentException(err.unwrap.mkString("Compilation errors: ", ", ", ""))
  }

  class InitialInterpretationFunction(compiler: => ProcessCompiler,
                                      node: SplittedNode,
                                      configProvider: () => InterpreterConfig,
                                      espExceptionHandlerProvider: () => EspExceptionHandler,
                                      metaData: MetaData,
                                      inputParamName: String,
                                      processTimeout: Duration) extends RichFlatMapFunction[Any, InterpretationResult] {

    lazy val config = configProvider()
    private lazy val interpreter = new Interpreter(config)
    private lazy val compiledNode = validateOrFail(compiler.compile(node))
    private lazy val espExceptionHandler = espExceptionHandlerProvider()
    lazy implicit val ec = SynchronousExecutionContext.ctx

    override def open(parameters: Configuration): Unit = {
      super.open(parameters)
      config.open
      espExceptionHandler.open()
    }

    override def flatMap(input: Any, collector: Collector[InterpretationResult]): Unit = {
      val result = espExceptionHandler.recover {
        val resultFuture = interpreter.interpret(compiledNode, metaData, input, inputParamName)
        Await.result(resultFuture, processTimeout)
      }(ContextImpl(metaData).withVariable(inputParamName, input))
      result.foreach(collector.collect)
    }

    override def close(): Unit = {
      super.close()
      config.close()
      espExceptionHandler.close()
    }

  }

  class SinkInterpretationFunction(compiler: => ProcessCompiler,
                                   sink: splittednode.Sink,
                                   configProvider: () => InterpreterConfig,
                                   espExceptionHandlerProvider: () => EspExceptionHandler,
                                   processTimeout: Duration) extends RichFlatMapFunction[InterpretationResult, InterpretationResult] {

    lazy val config = configProvider()
    private lazy val interpreter = new Interpreter(config)
    private lazy val compiledNode = validateOrFail(compiler.compile(sink))
    private lazy val espExceptionHandler = espExceptionHandlerProvider()
    lazy implicit val ec = SynchronousExecutionContext.ctx

    override def open(parameters: Configuration): Unit = {
      super.open(parameters)
      config.open
      espExceptionHandler.open()
    }

    override def flatMap(input: InterpretationResult, collector: Collector[InterpretationResult]): Unit = {
      val result = espExceptionHandler.recover {
        val result = interpreter.interpret(compiledNode, input.finalContext)
        Await.result(result, processTimeout)
      }(input.finalContext)
      result.foreach(collector.collect)
    }

    override def close(): Unit = {
      super.close()
      config.open
      espExceptionHandler.close()
    }
  }

  object SplitFunction extends OutputSelector[InterpretationResult] {
    override def select(interpretationResult: InterpretationResult): Iterable[String] = {
      interpretationResult.reference match {
        case NextPartReference(id) => List(id).asJava
        case DefaultSinkReference => List(DefaultSinkId).asJava // TODO: default sink won't be registered
        case EndReference => throw new IllegalStateException("Non-sink interpretation shouldn't ended up by end reference")
      }
    }
  }

  class AggregateKeyByFunction(compiler: => ProcessCompiler,
                               node: splittednode.Aggregate,
                               configProvider: () => InterpreterConfig,
                               espExceptionHandlerProvider: () => EspExceptionHandler,
                               processTimeout: Duration) extends (InterpretationResult => Option[String]) with Serializable {

    private lazy val config = configProvider()
    private lazy val interpreter = new Interpreter(config)
    private lazy val espExceptionHandler = espExceptionHandlerProvider()
    private lazy val compiledNode = validateOrFail(compiler.compile(node))

    override def apply(result: InterpretationResult) = {
      implicit val ec = SynchronousExecutionContext.ctx
      val resultFuture = interpreter.interpret(compiledNode, result.finalContext).map(_.output.toString)
      espExceptionHandler.recover {
        Await.result(resultFuture, processTimeout)
      }(result.finalContext)
    }

  }

}