package cbl.js.kotiln

import android.util.Log
import com.couchbase.lite.Document
import com.couchbase.lite.DocumentFlag
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

object JavaScriptFilterEvaluator {
    private const val TAG = "JSFilterEvaluator"
    
    /**
     * Evaluates a JavaScript filter function against a document and flags
     */
    fun evaluateFilter(filterFunction: String, document: Document, flags: Set<DocumentFlag>): Boolean {
        var jsContext: Context? = null
        try {
            // Create JavaScript context
            jsContext = Context.enter()
            jsContext.optimizationLevel = -1 // Use interpreted mode for better compatibility
            
            // Create scope
            val scope: Scriptable = jsContext.initStandardObjects()
            
            // Setup simple console logging
            setupConsole(jsContext, scope)
            
            // Convert document to JSON
            val docJson = documentToJson(document)
            val flagsJson = flagsToJson(flags)
            
            // Escape JSON strings for JavaScript
            val escapedDocJson = escapeJsonForJs(docJson)
            val escapedFlagsJson = escapeJsonForJs(flagsJson)
            
            // Create the evaluation script
            val script = """
                (function() {
                    try {
                        var filterFunc = $filterFunction;
                        var doc = JSON.parse('$escapedDocJson');
                        var flags = JSON.parse('$escapedFlagsJson');
                        
                        // Call the filter function
                        var result = filterFunc(doc, flags);
                        
                        return !!result;
                    } catch (e) {
                        console.log('Filter error: ' + e.toString());
                        console.log('Stack: ' + (e.stack || 'No stack trace'));
                        return false;
                    }
                })()
            """.trimIndent()
            
            // Execute the script
            val result = jsContext.evaluateString(scope, script, "filterScript", 1, null)
            


            // Convert result to boolean
            return result?.let { Context.toBoolean(it) } ?: false
                        
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating filter: ${e.message}", e)
            return false
        } finally {
            if (jsContext != null) {
                Context.exit()
            }
        }
    }
    
    /**
     * Setup console.log functionality for debugging
     */
    private fun setupConsole(context: Context, scope: Scriptable) {
        val consoleScript = """
            var console = {
                log: function() {
                    var message = Array.prototype.slice.call(arguments).join(' ');
                    java.lang.System.out.println('JSFilter: ' + message);
                }
            };
        """.trimIndent()
        
        try {
            context.evaluateString(scope, consoleScript, "console", 1, null)
        } catch (e: Exception) {
            Log.w(TAG, "Could not setup console logging: ${e.message}")
        }
    }
    
    /**
     * Convert document to JSON string
     */
   private fun documentToJson(document: Document): String {
    return try {
        val map = document.toMap()

        // Add document ID if not present
        if (!docData.has("_id")) {
            docData.put("_id", document.id)
        }
            
        JSONObject(map).toString()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to serialize document: ${e.message}")
        "{\"_id\":\"${document.id}\"}"
    }
}
    
    /**
     * Convert flags to JSON string
     */
    private fun flagsToJson(flags: Set<DocumentFlag>): String {
        try {
            val flagsObj = JSONObject()
            flagsObj.put("deleted", flags.contains(DocumentFlag.DELETED))
            flagsObj.put("accessRemoved", flags.contains(DocumentFlag.ACCESS_REMOVED))
            return flagsObj.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize flags: ${e.message}")
            return "{\"deleted\":false,\"accessRemoved\":false}"
        }
    }
    
    /**
     * Escape JSON string for safe inclusion in JavaScript
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
     * Create a ReplicationFilter from a JavaScript function string
     */
    fun createFilter(functionString: String?): com.couchbase.lite.ReplicationFilter? {
        if (functionString.isNullOrEmpty()) {
            return null
        }
        
        return com.couchbase.lite.ReplicationFilter { document, flags ->
            try {
                evaluateFilter(functionString, document, flags)
            } catch (e: Exception) {
                Log.e(TAG, "Error in replication filter: ${e.message}", e)
                false // Default to not replicating on error
            }
        }
    }
}
