package semmerge.merge

import semmerge.json.JsonParser
import semmerge.model.SNode
import semmerge.yaml.YamlParser

object CustomValues {
    fun parse(text: String, format: String): SNode = when (format.lowercase()) {
        "json" -> JsonParser.parse(text)
        "yaml", "yml", "" -> YamlParser.parse(text)
        else -> throw IllegalArgumentException("未知格式: $format")
    }
}
