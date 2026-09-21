package merger

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

data class Document(val id: String, val name: String, val format: String, val text: String, val createdAt: Long)

data class Decision(
    val id: String, val path: String,
    val baseFp: String, val oursFp: String, val theirsFp: String,
    val resolution: String, val value: String?,
    val policyVersion: Int, val createdAt: Long
) {
    fun matches(c: Conflict): Boolean =
        path == c.path && baseFp == c.baseFp && oursFp == c.oursFp && theirsFp == c.theirsFp
}

data class AppliedResolution(val conflictId: String, val path: String, val resolution: String, val value: String?)

data class MergeSessionRecord(
    val id: String, val baseId: String, val oursId: String, val theirsId: String,
    val policyVersion: Int, val policies: Map<String, ArrayPolicy>,
    var version: Int, val resolutions: MutableList<AppliedResolution>,
    val createdAt: Long, var updatedAt: Long
)

data class ExportRecord(val id: String, val mergeId: String, val format: String,
                        val text: String, val fingerprint: String, val createdAt: Long)

data class Bundle(val documents: List<Document>, val policies: PolicySet,
                  val decisions: List<Decision>, val merges: List<MergeSessionRecord>,
                  val exports: List<ExportRecord>)

class Store(val dir: File) {
    val mapper: ObjectMapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

    init { dir.mkdirs() }

    private fun file(name: String) = File(dir, name)

    fun loadDocuments(): MutableList<Document> =
        if (file("documents.json").exists()) mapper.readValue(file("documents.json"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, Document::class.java))
        else mutableListOf()

    fun loadPolicies(): PolicySet =
        if (file("policies.json").exists()) mapper.readValue(file("policies.json"), PolicySet::class.java)
        else PolicySet(emptyMap(), 0)

    fun loadDecisions(): MutableList<Decision> =
        if (file("decisions.json").exists()) mapper.readValue(file("decisions.json"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, Decision::class.java))
        else mutableListOf()

    fun loadMerges(): MutableList<MergeSessionRecord> =
        if (file("merges.json").exists()) mapper.readValue(file("merges.json"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, MergeSessionRecord::class.java))
        else mutableListOf()

    fun loadExports(): MutableList<ExportRecord> =
        if (file("exports.json").exists()) mapper.readValue(file("exports.json"),
            mapper.typeFactory.constructCollectionType(MutableList::class.java, ExportRecord::class.java))
        else mutableListOf()

    fun saveDocuments(v: List<Document>) = mapper.writeValue(file("documents.json"), v)
    fun savePolicies(v: PolicySet) = mapper.writeValue(file("policies.json"), v)
    fun saveDecisions(v: List<Decision>) = mapper.writeValue(file("decisions.json"), v)
    fun saveMerges(v: List<MergeSessionRecord>) = mapper.writeValue(file("merges.json"), v)
    fun saveExports(v: List<ExportRecord>) = mapper.writeValue(file("exports.json"), v)

    fun loadBundle(): Bundle = Bundle(loadDocuments(), loadPolicies(), loadDecisions(), loadMerges(), loadExports())

    fun saveBundle(b: Bundle) {
        saveDocuments(b.documents); savePolicies(b.policies); saveDecisions(b.decisions)
        saveMerges(b.merges); saveExports(b.exports)
    }
}
