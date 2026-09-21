package merger

enum class ConfigFormat { YAML, JSON }

object ConfigIO {
    /** 根据显式格式或文本特征判定格式。 */
    fun detectFormat(text: String, fileName: String? = null): ConfigFormat {
        val lower = fileName?.lowercase().orEmpty()
        if (lower.endsWith(".json")) return ConfigFormat.JSON
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return ConfigFormat.YAML
        val first = text.firstOrNull { !it.isWhitespace() }
        return if (first == '{' || first == '[') ConfigFormat.JSON else ConfigFormat.YAML
    }

    fun parse(text: String, format: ConfigFormat, fileName: String): Node = when (format) {
        ConfigFormat.JSON -> JsonParser(fileName).parse(text)
        ConfigFormat.YAML -> YamlParser(fileName).parse(text)
    }
}
