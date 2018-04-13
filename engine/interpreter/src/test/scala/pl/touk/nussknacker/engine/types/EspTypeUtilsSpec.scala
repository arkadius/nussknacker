package pl.touk.nussknacker.engine.types

import java.util.regex.Pattern

import org.scalatest.{FlatSpec, FunSuite, Matchers}
import org.scalatest.prop.TableDrivenPropertyChecks._
import pl.touk.nussknacker.engine.api.{Documentation, ParamName}
import pl.touk.nussknacker.engine.api.process.{ClassExtractionSettings, ClassMemberPatternPredicate}
import pl.touk.nussknacker.engine.api.typed.ClazzRef
import pl.touk.nussknacker.engine.definition.TypeInfos.{MethodInfo, Parameter}

import scala.annotation.meta.{field, getter}
import scala.concurrent.Future
import scala.reflect.ClassTag

class EspTypeUtilsSpec extends FunSuite with Matchers {

  val signatures = Table(("signature", "value", "matches"),
    (java.lang.Boolean.TYPE, classOf[java.lang.Boolean], true),
    (java.lang.Long.TYPE, classOf[java.lang.Long], true),
    (java.lang.Integer.TYPE, classOf[java.lang.Integer], true),
    (classOf[java.lang.Long], classOf[java.lang.Integer], true),
    (classOf[java.lang.Long], java.lang.Integer.TYPE, true),
    (java.lang.Long.TYPE, java.lang.Integer.TYPE, true),
    (java.lang.Long.TYPE, classOf[java.lang.Integer], true),
    (java.lang.Long.TYPE, classOf[java.lang.Integer], true),

    (java.lang.Character.TYPE, classOf[java.lang.Character], true),
    (classOf[java.lang.Number], classOf[java.lang.Integer], true),
    (java.lang.Integer.TYPE, classOf[java.lang.Long], false)
  )

  test("should check if signature is possible") {

    forAll(signatures) { (signature, value, matches) =>
      EspTypeUtils.signatureElementMatches(signature, value) shouldBe matches
    }
  }

  case class SampleClass(foo: Int, bar: String) extends SampleAbstractClass with SampleInterface

  class Returning {
    def futureOfList: Future[java.util.List[SampleClass]] = ???
  }

  test("should extract generic return type parameters") {

    val method = classOf[Returning].getMethod("futureOfList")

    val extractedType = EspTypeUtils.getGenericType(method.getGenericReturnType).get

    extractedType.clazz shouldBe classOf[java.util.List[_]]
    extractedType.params shouldBe List(ClazzRef[SampleClass])
  }

  test("should extract public fields from scala case class") {

    val infos = TypesInformationExtractor.clazzAndItsChildrenDefinition(List(ClazzRef[SampleClass]))(ClassExtractionSettings.Default)
    val sampleClassInfo = infos.find(_.clazzName.refClazzName.contains("SampleClass")).get

    sampleClassInfo.methods shouldBe Map(
      "foo" -> MethodInfo(List.empty, ClazzRef(Integer.TYPE), None),
      "bar" -> MethodInfo(List.empty, ClazzRef[String], None)
    )
  }

  test("shoud detect java beans and fields in java class") {

    EspTypeUtils.clazzDefinition(classOf[JavaSampleClass])(ClassExtractionSettings.Default).methods shouldBe Map(
      //TODO: getNotProperty has no parameters in list, because we list only parameters annotated with @ParamName - should it be this way?
      "getNotProperty" -> MethodInfo(List(), ClazzRef[String], None),
      "bar" -> MethodInfo(List(), ClazzRef[String], None),
      "getBeanProperty" -> MethodInfo(List(), ClazzRef[String], None),
      "beanProperty" -> MethodInfo(List(), ClazzRef[String], None),
      "isBooleanProperty" -> MethodInfo(List(), ClazzRef[Boolean], None),
      "booleanProperty" -> MethodInfo(List(), ClazzRef[Boolean], None),
      "foo" -> MethodInfo(List(), ClazzRef(Integer.TYPE), None)
    )

  }

  test("should skip blacklisted properties") {
    val testCasses = Table(("class", "className"),
      (ClazzRef[SampleClass], "SampleClass"),
      (ClazzRef[JavaSampleClass], "JavaSampleClass")
    )

    val testClassPatterns = Table("classPattern",
      ".*SampleClass",
      ".*SampleAbstractClass",
      ".*SampleInterface"
    )

    forAll(testCasses) { (clazz, clazzName) =>
      forAll(testClassPatterns) { classPattern =>
        val infos = TypesInformationExtractor.clazzAndItsChildrenDefinition(List(clazz))(ClassExtractionSettings(Seq(
          ClassMemberPatternPredicate(Pattern.compile(classPattern), Pattern.compile("ba.*")),
          ClassMemberPatternPredicate(Pattern.compile(classPattern), Pattern.compile("get.*")),
          ClassMemberPatternPredicate(Pattern.compile(classPattern), Pattern.compile("is.*"))
        )))
        val sampleClassInfo = infos.find(_.clazzName.refClazzName.contains(clazzName)).get

        sampleClassInfo.methods shouldBe Map(
          "foo" -> MethodInfo(List.empty, ClazzRef(Integer.TYPE), None)
        )
      }
    }
  }


  case class ScalaSampleDocumentedClass() {

    val field1: Long = 123L

    //we use this @getter annotation here, because vals in case classes are translated by default to private field and public getter method for that field
    //we just annotate public getter method (that will be created by scalac) for field2
    //more here: https://www.scala-lang.org/api/current/scala/annotation/meta/index.html
    @(Documentation @getter)(description = ScalaSampleDocumentedClass.field2Docs)
    val field2: Long = 234L

    def foo(fooParam1: String): Long = {
      0L
    }

    def bar(@ParamName("barparam1") barparam1: Long): String = {
      ""
    }
    @Documentation(description = ScalaSampleDocumentedClass.bazDocs)
    def baz(@ParamName("bazparam1") bazparam1: String, @ParamName("bazparam2") bazparam2: Int): Long = {
      0L
    }

    @Documentation(description = ScalaSampleDocumentedClass.quxDocs)
    def qux(quxParam1: String): Long = {
      0L
    }
  }

  object ScalaSampleDocumentedClass {
    final val field2Docs = "This is sample documentation for field2 field"
    final val bazDocs = "This is sample documentation for baz method"
    final val quxDocs = "This is sample documentation for qux method"
  }

  test("should extract description and params from method") {
    val scalaExtractedInfo = TypesInformationExtractor.clazzAndItsChildrenDefinition(List(ClazzRef[ScalaSampleDocumentedClass]))(ClassExtractionSettings.Default)
    val scalaClazzInfo = scalaExtractedInfo.find(_.clazzName == ClazzRef(classOf[ScalaSampleDocumentedClass])).get

    val javaExtractedInfo = TypesInformationExtractor.clazzAndItsChildrenDefinition(List(ClazzRef[JavaSampleDocumentedClass]))(ClassExtractionSettings.Default)
    val javaClazzInfo = javaExtractedInfo.find(_.clazzName == ClazzRef(classOf[JavaSampleDocumentedClass])).get

    val table = Table(
      ("method", "methodInfo"),
      ("foo", MethodInfo(parameters = List.empty, refClazz = ClazzRef[Long], description = None)),
      ("bar", MethodInfo(parameters = List(param[Long]("barparam1")), refClazz = ClazzRef[String], description = None)),
      ("baz", MethodInfo(parameters = List(param[String]("bazparam1"), param[Int]("bazparam2")), refClazz = ClazzRef[Long], description = Some(ScalaSampleDocumentedClass.bazDocs))),
      ("qux", MethodInfo(parameters = List.empty, refClazz = ClazzRef[Long], description = Some(ScalaSampleDocumentedClass.quxDocs))),
      ("field1", MethodInfo(parameters = List.empty, refClazz = ClazzRef[Long], description = None)),
      ("field2", MethodInfo(parameters = List.empty, refClazz = ClazzRef[Long], description = Some(ScalaSampleDocumentedClass.field2Docs)))
    )
    forAll(table){ case (method, methodInfo) =>
        scalaClazzInfo.methods(method) shouldBe methodInfo
        javaClazzInfo.methods(method) shouldBe methodInfo
    }
  }

  private def param[T:ClassTag](name: String): Parameter = {
    Parameter(name, ClazzRef[T])
  }

}
