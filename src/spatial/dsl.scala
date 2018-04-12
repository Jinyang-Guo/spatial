package spatial

trait DSL extends lang.static.FrontendStatics

object lib extends DSL {
  import language.experimental.macros
  import scala.annotation.StaticAnnotation
  import forge.tags.AppTag

  /** Annotation class for @spatial macro annotation. */
  final class spatial extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro spatial.impl
  }
  private object spatial extends AppTag("spatial", "SpatialApp")

  final class struct extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro tags.StagedStructsMacro.impl
  }
}

object dsl extends DSL with lang.static.ShadowingStatics {
  import language.experimental.macros
  import scala.annotation.StaticAnnotation
  import forge.tags.AppTag

  /** Annotation class for @spatial macro annotation. */
  final class spatial extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro spatial.impl
  }
  private object spatial extends AppTag("spatial", "SpatialApp")

  final class struct extends StaticAnnotation {
    def macroTransform(annottees: Any*): Any = macro tags.StagedStructsMacro.impl
  }
}