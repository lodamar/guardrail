package support

trait SwaggerSpecRunner {

  import _root_.io.swagger.models._
  import _root_.io.swagger.parser.SwaggerParser
  import cats.arrow.FunctionK
  import cats.implicits._
  import com.twilio.guardrail._
  import com.twilio.guardrail.languages.ScalaLanguage
  import com.twilio.guardrail.terms.framework.FrameworkTerms
  import com.twilio.guardrail.terms.{ ScalaTerms, SwaggerTerms }
  import scala.collection.JavaConverters._

  def runSwaggerSpec(
      spec: String
  ): (Context, FunctionK[CodegenApplication[ScalaLanguage, ?], Target]) => (ProtocolDefinitions[ScalaLanguage], Clients[ScalaLanguage], Servers[ScalaLanguage]) =
    runSwagger(new SwaggerParser().parse(spec)) _

  def runSwagger(swagger: Swagger)(context: Context, framework: FunctionK[CodegenApplication[ScalaLanguage, ?], Target])(
      implicit F: FrameworkTerms[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]],
      Sc: ScalaTerms[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]],
      Sw: SwaggerTerms[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]]
  ): (ProtocolDefinitions[ScalaLanguage], Clients[ScalaLanguage], Servers[ScalaLanguage]) = {
    import F._
    import Sw._

    val prog = for {
      protocol <- ProtocolGenerator.fromSwagger[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]](swagger)
      definitions = protocol.elems

      schemes = Option(swagger.getSchemes)
        .fold(List.empty[String])(_.asScala.to[List].map(_.toValue))
      host     = Option(swagger.getHost)
      basePath = Option(swagger.getBasePath)
      paths = Option(swagger.getPaths)
        .map(_.asScala.toList)
        .getOrElse(List.empty)
      routes <- extractOperations(paths)
      classNamedRoutes <- routes
        .map(route => getClassName(route.operation).map(_ -> route))
        .sequence
      groupedRoutes = classNamedRoutes
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toList
      frameworkImports <- getFrameworkImports(context.tracing)

      clients <- ClientGenerator
        .fromSwagger[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]](context, frameworkImports)(schemes, host, basePath, groupedRoutes)(definitions)
      servers <- ServerGenerator
        .fromSwagger[ScalaLanguage, CodegenApplication[ScalaLanguage, ?]](context, swagger, frameworkImports)(definitions)
    } yield (protocol, clients, servers)

    Target.unsafeExtract(prog.foldMap(framework))
  }

}
