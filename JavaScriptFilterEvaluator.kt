package cbl.js.kotiln

import android.util.Log
import com.couchbase.lite.Document
import com.couchbase.lite.DocumentFlag
import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8Object

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
            
            // Create V8 objects from document and flags
            val docObj = createDocumentObject(v8, document)
            val flagsObj = createFlagsObject(v8, flags)
            
            try {
                v8.add("doc", docObj)
                v8.add("flags", flagsObj)
                
                val script = "($compiledFunction)(doc, flags)"
                val result = v8.executeScript(script)
                
                when (result) {
                    is Boolean -> result
                    else -> false
                }
            } finally {
                docObj.release()
                flagsObj.release()
                v8.executeScript("delete doc; delete flags;")
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
     * Create V8 object from document
     */
     private fun createDocumentObject(v8: V8, document: Document): V8Object {
        val obj = V8Object(v8)
        
        try {
            val docMap = document.toMap().toMutableMap()
            docMap["_id"] = document.id
            
            // Add properties directly to V8 object
            for ((key, value) in docMap) {
                addValueToV8Object(obj, key, value)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Document conversion warning: ${e.message}")
            obj.add("_id", document.id)
        }
        
        return obj
    }

    /**
     * Add values to V8 object with type checking
     */
    private fun addValueToV8Object(obj: V8Object, key: String, value: Any?) {
        when (value) {
            is String -> obj.add(key, value)
            is Int -> obj.add(key, value)
            is Long -> obj.add(key, value.toInt())
            is Double -> obj.add(key, value)
            is Float -> obj.add(key, value.toDouble())
            is Boolean -> obj.add(key, value)
            is Map<*, *> -> {
                val nestedObj = V8Object(obj.runtime)
                for ((nestedKey, nestedValue) in value) {
                    if (nestedKey is String) {
                        addValueToV8Object(nestedObj, nestedKey, nestedValue)
                    }
                }
                obj.add(key, nestedObj)
                nestedObj.release()
            }
            is List<*> -> {
                obj.add(key, value.toString())
            }
            null -> obj.addNull(key)
            else -> obj.add(key, value.toString())
        }
    }

    
    /**
     * Create V8 object from flags
     */
    private fun createFlagsObject(v8: V8, flags: Set<DocumentFlag>): V8Object {
        val obj = V8Object(v8)
        obj.add("deleted", flags.contains(DocumentFlag.DELETED))
        obj.add("accessRemoved", flags.contains(DocumentFlag.ACCESS_REMOVED))
        return obj
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

    /**
     * Cleanup method - call when appropriate (e.g., app shutdown)
     */
    fun cleanup() {
        try {
            val runtime = threadLocalV8.get()
            if (runtime != null && !runtime.v8.isReleased) {
                runtime.v8.close()
                threadLocalV8.remove()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup V8 warning: ${e.message}")
        }
    }
}
