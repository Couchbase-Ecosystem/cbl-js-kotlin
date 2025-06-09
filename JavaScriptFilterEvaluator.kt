package cbl.js.kotiln

import android.util.Log
import com.couchbase.lite.Document
import com.couchbase.lite.DocumentFlag
import com.eclipsesource.v8.V8
import org.json.JSONObject

object JavaScriptFilterEvaluator {
    private const val TAG = "JSFilterEvaluator"
    
    // Thread-local V8 instances for thread safety and performance
    private val threadLocalV8 = ThreadLocal<V8Runtime>()
    
    private data class V8Runtime(
        val v8: V8,
        val compiledFunctions: MutableMap<String, String> = mutableMapOf()
    )
    
    private fun getV8Runtime(): V8Runtime {
        var runtime = threadLocalV8.get()
        if (runtime == null || runtime.v8.isReleased) {
            val v8 = V8.createV8Runtime()
            
            // Add Android logging bridge
            v8.registerJavaMethod({ _, parameters ->
                val message = parameters?.let { params ->
                    (0 until params.length()).map { i ->
                        params[i]?.toString() ?: "null"
                    }.joinToString(" ")
                } ?: ""
                Log.d("JSFilter", message)
            }, "androidLog")
            
            // Setup console that uses the bridge
            val consoleScript = """
                var console = {
                    log: function() {
                        var args = Array.prototype.slice.call(arguments);
                        androidLog(args.join(' '));
                    }
                };
            """.trimIndent()
            v8.executeScript(consoleScript)
            
            runtime = V8Runtime(v8)
            threadLocalV8.set(runtime)
        }
        return runtime
    }
    
    /**
     * Filter evaluation
     */
    fun evaluateFilter(filterFunction: String, document: Document, flags: Set<DocumentFlag>): Boolean {
        return try {
            val runtime = getV8Runtime()
            val v8 = runtime.v8
            
            val compiledFunction = getCompiledFunction(runtime, filterFunction)
            
            // Convert to JSON
            val docJson = documentToJson(document)
            val flagsJson = flagsToJson(flags)
            
            // Escape JSON for JavaScript
            val escapedDocJson = escapeJsonForJs(docJson)
            val escapedFlagsJson = escapeJsonForJs(flagsJson)
            
            // Execute with JSON parsing
            val script = """
                (function() {
                    var doc = JSON.parse('$escapedDocJson');
                    var flags = JSON.parse('$escapedFlagsJson');
                    return ($compiledFunction)(doc, flags);
                })()
            """.trimIndent()
            
            val result = v8.executeScript(script)
            when (result) {
                is Boolean -> result
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filter evaluation error: ${e.message}")
            false
        }
    }
    
    private fun getCompiledFunction(runtime: V8Runtime, filterFunction: String): String {
        return runtime.compiledFunctions.getOrPut(filterFunction) {
            try {
                val wrapped = """
                    function(doc, flags) {
                        try {
                            var filterFunc = $filterFunction;
                            return !!filterFunc(doc, flags);
                        } catch (e) {
                            console.log('Filter error: ' + e.toString());
                            return false;
                        }
                    }
                """.trimIndent()
                
                // Validate compilation
                runtime.v8.executeScript("($wrapped)")
                wrapped
            } catch (e: Exception) {
                Log.e(TAG, "Function compilation failed: ${e.message}")
                "function(doc, flags) { return false; }"
            }
        }
    }
    
    /**
     * Convert document to JSON string
     */
    private fun documentToJson(document: Document): String {
        return try {
            val docMap = document.toMap().toMutableMap()
            docMap["_id"] = document.id
            JSONObject(docMap).toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize document: ${e.message}")
            "{\"_id\":\"${document.id}\"}"
        }
    }
    
    /**
     * Convert flags to JSON string
     */
    private fun flagsToJson(flags: Set<DocumentFlag>): String {
        return try {
            val flagsObj = JSONObject()
            flagsObj.put("deleted", flags.contains(DocumentFlag.DELETED))
            flagsObj.put("accessRemoved", flags.contains(DocumentFlag.ACCESS_REMOVED))
            flagsObj.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize flags: ${e.message}")
            "{\"deleted\":false,\"accessRemoved\":false}"
        }
    }
    
    /**
     * Escape JSON string for JavaScript
     */
    private fun escapeJsonForJs(json: String): String {
        return json
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
    
    /**
     * Create replication filter
     */
    fun createFilter(functionString: String?): com.couchbase.lite.ReplicationFilter? {
        if (functionString.isNullOrEmpty()) return null
        
        return com.couchbase.lite.ReplicationFilter { document, flags ->
            evaluateFilter(functionString, document, flags)
        }
    }
}
