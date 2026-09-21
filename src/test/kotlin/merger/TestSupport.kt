package merger

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

object TestSupport {

    fun docs(base: String, a: String, b: String, format: Format = Format.YAML): MergeInput {
        return MergeInput(
            base = ConfigParser.parse(base, Source.BASE, format),
            a = ConfigParser.parse(a, Source.A, format),
            b = ConfigParser.parse(b, Source.B, format),
            registry = StrategyRegistry.EMPTY,
            decisions = emptyList(),
            outputFormat = Format.YAML,
            sessionVersion = 0
        )
    }

    fun service(dir: Path): SessionService = SessionService(Store(dir))

    fun putAll(svc: SessionService, id: String, base: String, a: String, b: String) {
        svc.setInput(id, Source.BASE, base, Format.YAML)
        svc.setInput(id, Source.A, a, Format.YAML)
        svc.setInput(id, Source.B, b, Format.YAML)
    }

    fun conflictPaths(o: MergeOutcome): Set<String> =
        o.conflictIndex.values.filter { it.resolvedBy == null }.map { it.path }.toSet()
}
