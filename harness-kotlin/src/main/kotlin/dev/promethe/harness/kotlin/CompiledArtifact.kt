package dev.promethe.harness.kotlin

import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlinx.serialization.json.*

/** Versioned data-only transport. Class loading happens in the isolated worker only. */
internal object CompiledArtifact {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_BYTECODE = 160 * 1024

    fun encode(script: CompiledScript): JsonObject {
        val compiled = script as KJvmCompiledScript
        require(compiled.otherScripts.isEmpty())
        val field = requireNotNull(compiled.resultField)
        val files = (compiled.getCompiledModule() as KJvmCompiledModuleInMemory).compilerOutputFiles
        validateFiles(files)
        return buildJsonObject {
            put("format", 1)
            put("className", compiled.scriptClassFQName)
            put("resultField", field.first)
            put("resultType", field.second.typeName)
            put("resultNullable", field.second.isNullable)
            put("files", buildJsonObject { files.forEach { (name, bytes) -> put(name, Base64.getEncoder().encodeToString(bytes)) } })
        }.also { require(it.toString().encodeToByteArray().size <= MAX_BYTES) }
    }

    fun decode(
        artifact: JsonObject,
        configuration: ScriptCompilationConfiguration,
    ): CompiledScript {
        require(artifact.toString().encodeToByteArray().size <= MAX_BYTES)
        require(artifact.getValue("format").jsonPrimitive.int == 1)
        val className = artifact.getValue("className").jsonPrimitive.content
        val resultField = artifact.getValue("resultField").jsonPrimitive.content
        val resultType = artifact.getValue("resultType").jsonPrimitive.content
        require(className.length in 1..512 && resultField.length in 1..512 && resultType.length in 1..512)
        val encoded = artifact.getValue("files").jsonObject
        require(encoded.size in 1..256)
        val files = encoded.mapValues { Base64.getDecoder().decode(it.value.jsonPrimitive.content) }
        validateFiles(files)
        require(files.containsKey(className.replace('.', '/') + ".class"))
        val module = object : KJvmCompiledModuleInMemory {
            override val compilerOutputFiles = files

            override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader =
                object : ClassLoader(baseClassLoader) {
                    override fun findClass(name: String): Class<*> {
                        val bytes = files[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
                        return defineClass(name, bytes, 0, bytes.size)
                    }

                    override fun getResourceAsStream(name: String) = files[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
                }
        }
        return KJvmCompiledScript("processor.kts", configuration, className, resultField to KotlinType(resultType, artifact.getValue("resultNullable").jsonPrimitive.boolean), emptyList(), module)
    }

    private fun validateFiles(files: Map<String, ByteArray>) {
        require(files.size in 1..256 && files.values.sumOf { it.size.toLong() } <= MAX_BYTECODE)
        require(files.keys.all { it.length in 1..1024 && !it.startsWith('/') && !it.contains('\\') && it.split('/').none { part -> part == ".." || part.isEmpty() } })
    }
}
