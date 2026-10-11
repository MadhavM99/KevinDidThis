package com.megathon.incidentassistant.data

import com.megathon.incidentassistant.form.FormSchema
import com.megathon.incidentassistant.form.FormSchemaParser
import com.megathon.incidentassistant.session.FormInterpreter
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The model's reading of each form (questions, reporter fields), kept in app-private storage
 * so a form is read once — not on every launch. A changed form has a new key and is read
 * again; only the most recent readings are kept.
 */
class FormReadingStore(private val dir: File) : FormInterpreter.Store {

    override fun load(key: String): FormSchema? =
        File(dir, "$key.json").takeIf { it.isFile }?.readText()?.let(FormSchemaParser::parse)

    override fun save(key: String, form: FormSchema) {
        dir.mkdirs()
        File(dir, "$key.json").writeText(json.encodeToString(FormSchema.serializer(), form))
        // One small file per form version; keep the most recent ones.
        dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(MAX_READINGS).forEach { it.delete() }
    }

    private companion object {
        const val MAX_READINGS = 32
        val json = Json { encodeDefaults = true }
    }
}
